"""All network endpoints, authentication values and tool payloads are synthetic.

Run only on the remote PoC node/cluster under the repository's execution policy.
"""
import concurrent.futures
import json
import socket
import unittest
from unittest import mock

from http_fixture import endpoint, generate, request, result, sse
from http_observer import HttpExchangeObserver
from http_transport import ObservedHttpClient
from observer import otlp
from runtime_export import RuntimeExporter
from test_runtime_export import lease_pipe, receiver, until


class HttpObservationTests(unittest.TestCase):
    def observe(self, body, *, version="2025-11-25", request_body=None,
                content_type="application/json", status=200, encoding="",
                complete=True, step=7, max_frame=65536):
        events = []
        tap = HttpExchangeObserver("test_http", ["calculate_sum"], events.append,
                                   protocol_version=version, max_frame=max_frame,
                                   trace_id="a" * 32, parent_span_id="b" * 16)
        tap.request(request(version) if request_body is None else request_body)
        tap.response(status, content_type, encoding)
        for offset in range(0, len(body), step):
            tap.feed(body[offset:offset + step])
        tap.finish(complete=complete)
        tap.finish(complete=complete)
        return events, tap

    def test_pretty_json_both_versions_and_metadata_only(self):
        for version in ["2025-11-25", "2026-07-28"]:
            with self.subTest(version=version):
                events, _ = self.observe(result(), version=version)
                self.assertEqual(len(events), 1)
                self.assertEqual(events[0]["status"], "SUCCEEDED")
                raw = json.dumps(otlp(events[0]))
                self.assertIn("eap-mcp-http-observer", raw)
                self.assertNotIn("PRIVATE", raw)
                self.assertEqual(events[0]["parentSpanId"], "b" * 16)
                self.assertFalse(events[0]["effectVerified"])

    def test_modern_version_mismatch_cannot_report_success(self):
        events, _ = self.observe(result(), version="2026-07-28",
                                 request_body=request("2025-11-25"))
        self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_sse_bom_multiline_unicode_and_all_delimiters(self):
        payload = result().replace(b"PRIVATE_RESULT", "工具完成".encode())
        for ending in [b"\n", b"\r\n", b"\r"]:
            body = b"\xef\xbb\xbf: heartbeat" + ending * 2 + sse(payload, ending=ending)
            for step in [1, 3, 17, 65536]:
                with self.subTest(ending=ending, step=step):
                    events, _ = self.observe(body, content_type="Text/Event-Stream; charset=utf-8", step=step)
                    self.assertEqual([e["status"] for e in events], ["SUCCEEDED"])

    def test_sse_progress_empty_prime_cursor_and_retry_not_exported(self):
        progress = b'{"jsonrpc":"2.0","method":"notifications/progress","params":{"private":"PRIVATE_PROGRESS"}}'
        body = b"id: PRIVATE_CURSOR\ndata:\nretry: 123\n\n: PRIVATE_COMMENT\n\n"
        body += sse(progress) + sse(result())
        events, _ = self.observe(body, content_type="text/event-stream")
        self.assertEqual(events[0]["status"], "SUCCEEDED")
        self.assertNotIn("PRIVATE", json.dumps(events))

    def test_tool_error_and_rpc_error(self):
        for body in [result(failed=True),
                     b'{"jsonrpc":"2.0","id":1,"error":{"code":-1,"message":"PRIVATE_ERROR"}}']:
            events, _ = self.observe(body)
            self.assertEqual(events[0]["status"], "FAILED")
            self.assertNotIn("PRIVATE", json.dumps(events))

    def test_http_status_is_not_tool_result(self):
        for status in [202, 204, 301, 307, 400, 401, 403, 404, 429, 500, 503]:
            with self.subTest(status=status):
                events, _ = self.observe(result(), status=status)
                self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_wrong_content_type_and_compression_stay_unknown(self):
        for content_type, encoding in [("text/plain", ""), ("application/json", "gzip"),
                                        ("text/event-stream", "br")]:
            events, _ = self.observe(result(), content_type=content_type, encoding=encoding)
            self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_sse_incomplete_event_not_dispatched_at_eof(self):
        for body in [sse(result()).rstrip(b"\n"), sse(result())[:-1], b": heartbeat\n\n"]:
            events, _ = self.observe(body, content_type="text/event-stream")
            self.assertEqual(events[0]["status"], "UNKNOWN")
            self.assertEqual(events[0]["reason"], "STREAM_CLOSED")

    def test_partial_json_caller_close_and_network_loss_unknown(self):
        for body, complete in [(result(), False), (result()[:-1], True), (b"", True)]:
            events, _ = self.observe(body, complete=complete)
            self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_wrong_ids_types_and_server_requests_cannot_complete_call(self):
        for body in [result("1"), result(2), result(True),
                     b'{"jsonrpc":"2.0","id":1,"method":"sampling/createMessage","params":{}}']:
            events, _ = self.observe(body)
            self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_invalid_json_duplicate_utf8_and_nonfinite_unknown(self):
        bodies = [b'{"jsonrpc":"2.0","id":1,"id":1,"result":{"content":[]}}',
                  result().replace(b"PRIVATE_RESULT", b"\xff"),
                  result().decode().encode("utf-16"),
                  b'{"jsonrpc":"2.0","id":1,"result":{"content":[]},"other":NaN}',
                  b"[" + result() + b"]"]
        for body in bodies:
            events, _ = self.observe(body)
            self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_async_and_mrtr_are_not_final_tool_success(self):
        for field in ["task", "inputRequests"]:
            payload = json.loads(result())
            payload["result"][field] = {}
            for version in ["2025-11-25", "2026-07-28"]:
                events, _ = self.observe(json.dumps(payload).encode(), version=version)
                self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_frame_line_event_bounds(self):
        for body, kind in [(result().replace(b"PRIVATE_RESULT", b"x" * 2048), "application/json"),
                           (b":" + b"x" * 2048 + b"\n\n", "text/event-stream"),
                           ((b"data: " + b"x" * 100 + b"\n") * 20 + b"\n", "text/event-stream")]:
            events, tap = self.observe(body, content_type=kind, max_frame=1024)
            self.assertEqual(events[0]["status"], "UNKNOWN")
            self.assertTrue(tap.core.degraded)
            self.assertEqual(len(tap.body) + len(tap.line) + len(tap.data), 0)

    def test_duplicate_result_only_one_observation(self):
        events, _ = self.observe(sse(result()) * 2, content_type="text/event-stream")
        self.assertEqual([e["status"] for e in events], ["SUCCEEDED"])

    def test_non_message_sse_event_cannot_complete_call(self):
        events, _ = self.observe(sse(result()).replace(b"event: message", b"event: endpoint"),
                                 content_type="text/event-stream")
        self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_unregistered_name_is_redacted(self):
        events, _ = self.observe(result(), request_body=request().replace(
            b"calculate_sum", b"PRIVATE_TOOL_NAME"))
        self.assertEqual(events[0]["tool"], "unregistered_tool")
        self.assertNotIn("PRIVATE", json.dumps(events))

    def test_notification_has_no_invented_tool_span(self):
        events, _ = self.observe(b"", status=202,
                                 request_body=b'{"jsonrpc":"2.0","method":"notifications/initialized"}')
        self.assertEqual(events, [])

    def test_emit_failure_is_contained(self):
        def fail(_):
            raise RuntimeError("synthetic telemetry failure")
        tap = HttpExchangeObserver("test_http", ["calculate_sum"], fail,
                                   protocol_version="2025-11-25")
        tap.request(request())
        tap.response(200, "application/json")
        tap.feed(result())
        tap.finish(complete=True)
        self.assertEqual(tap.core.delivery_failures, 1)


class HttpTransportTests(unittest.TestCase):
    def client(self, url, events, **kwargs):
        return ObservedHttpClient(url, server="test_http", tools=["calculate_sum"],
                                  emit=events.append, protocol_version="2025-11-25",
                                  allow_http=True, **kwargs)

    @staticmethod
    def read_all(response):
        body = bytearray()
        while chunk := response.read(13):
            body.extend(chunk)
        return bytes(body)

    def test_actual_http_json_sse_and_unknown_contract_fixture(self):
        rows = generate()
        self.assertEqual([r["event"]["status"] for r in rows], ["SUCCEEDED", "FAILED", "UNKNOWN"])
        self.assertNotIn("PRIVATE", json.dumps(rows))

    def test_complete_json_with_content_length_or_chunked_is_confirmed_before_context_exit(self):
        for chunked in [False, True]:
            events = []
            with endpoint(result(), chunked=chunked) as (url, _):
                with self.client(url, events).post(request()) as response:
                    self.assertEqual(self.read_all(response), result())
                    self.assertEqual([e["status"] for e in events], ["SUCCEEDED"])
                    self.assertTrue(response.closed)

    def test_raw_body_headers_and_stream_preserved(self):
        body = b": heartbeat\r\n\r\n" + sse(result(), ending=b"\r\n")
        events = []
        with endpoint(body, content_type="text/event-stream", chunked=True) as (url, calls):
            with self.client(url, events).post(request(), headers={
                "Authorization": "Bearer SYNTHETIC_ONLY",
                "Mcp-Session-Id": "SYNTHETIC_SESSION",
            }) as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(self.read_all(response), body)
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0]["body"], request())
            self.assertEqual(calls[0]["headers"]["Authorization"], "Bearer SYNTHETIC_ONLY")
        self.assertEqual(events[0]["status"], "SUCCEEDED")
        self.assertNotIn("SYNTHETIC", json.dumps(events))

    def test_http_errors_and_redirect_are_not_retried(self):
        for status in [302, 307, 401, 429, 503]:
            events = []
            with endpoint(result(), status=status) as (url, calls):
                with self.client(url, events).post(request()) as response:
                    self.assertEqual(response.status, status)
                    self.assertEqual(self.read_all(response), result())
                self.assertEqual(len(calls), 1)
            self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_early_close_no_result_and_no_retry(self):
        events = []
        with endpoint(result()) as (url, calls):
            with self.client(url, events).post(request()):
                pass
            self.assertEqual(len(calls), 1)
        self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_truncated_content_length_cannot_confirm_json(self):
        events = []
        with endpoint(result(), length_delta=100) as (url, calls):
            with self.client(url, events).post(request()) as response:
                self.assertEqual(self.read_all(response), result())
            self.assertEqual(len(calls), 1)
        self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_socket_timeout_returns_to_caller_without_retry(self):
        events = []
        with endpoint(result(), delay=0.3) as (url, calls):
            with self.client(url, events, timeout=0.1).post(request()) as response:
                with self.assertRaises((TimeoutError, socket.timeout)):
                    self.read_all(response)
            self.assertEqual(len(calls), 1)
        self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_parallel_same_rpc_id_keeps_task_trace_isolated(self):
        events = []
        with endpoint(sse(result()), content_type="text/event-stream", chunked=True) as (url, calls):
            client = self.client(url, events)
            def run(index):
                with client.post(request(), trace_id=f"{index + 1:032x}") as response:
                    self.read_all(response)
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                list(pool.map(run, range(8)))
            self.assertEqual(len(calls), 8)
        self.assertEqual({e["traceId"] for e in events}, {f"{i + 1:032x}" for i in range(8)})
        self.assertEqual(len({e["spanId"] for e in events}), 8)
        self.assertTrue(all(e["status"] == "SUCCEEDED" for e in events))

    def test_runtime_export_uses_separate_credential_channel(self):
        with lease_pipe() as (lease, _, value), receiver() as (runtime, accepted):
            exporter = RuntimeExporter(runtime, lease, allow_http=True)
            try:
                with endpoint(result()) as (url, calls):
                    client = ObservedHttpClient(url, server="test_http", tools=["calculate_sum"],
                                                emit=exporter.submit, protocol_version="2025-11-25",
                                                allow_http=True)
                    with client.post(request()) as response:
                        self.read_all(response)
                    self.assertNotIn(value["token"], json.dumps(calls, default=str))
                until(lambda: exporter.counts["accepted"] == 1)
                self.assertEqual(accepted[0]["headers"]["X-EAP-Runtime-Token"], value["token"])
                self.assertNotIn("PRIVATE", accepted[0]["body"].decode())
                self.assertIn("eap-mcp-http-observer", accepted[0]["body"].decode())
                self.assertIn("SUCCEEDED", accepted[0]["body"].decode())
            finally:
                exporter.close()

    def test_environment_proxy_ignored(self):
        with endpoint(result()) as (url, calls), mock.patch.dict("os.environ", {
            "HTTP_PROXY": "http://127.0.0.1:1", "http_proxy": "http://127.0.0.1:1",
            "ALL_PROXY": "http://127.0.0.1:1", "NO_PROXY": "",
        }):
            with self.client(url, []).post(request()) as response:
                self.assertEqual(self.read_all(response), result())
            self.assertEqual(len(calls), 1)

    def test_modern_request_headers_are_owned_by_host(self):
        events = []
        with endpoint(sse(result()), content_type="text/event-stream") as (url, calls):
            client = ObservedHttpClient(url, server="test_http", tools=["calculate_sum"],
                                        emit=events.append, protocol_version="2026-07-28",
                                        allow_http=True)
            with client.post(request("2026-07-28"), headers={
                "Mcp-Method": "tools/call", "Mcp-Name": "calculate_sum",
            }) as response:
                self.read_all(response)
            self.assertEqual(calls[0]["headers"]["MCP-Protocol-Version"], "2026-07-28")
            self.assertEqual(calls[0]["headers"]["Mcp-Method"], "tools/call")
        self.assertEqual(events[0]["status"], "SUCCEEDED")

    def test_export_failure_does_not_change_http_result(self):
        def fail(_):
            raise RuntimeError("synthetic export failure")
        with endpoint(result()) as (url, calls):
            client = ObservedHttpClient(url, server="test_http", tools=["calculate_sum"],
                                        emit=fail, protocol_version="2025-11-25", allow_http=True)
            with client.post(request()) as response:
                self.assertEqual(self.read_all(response), result())
                self.assertEqual(response.observer.core.delivery_failures, 1)
            self.assertEqual(len(calls), 1)

    def test_invalid_configuration_headers_and_trace_do_not_dispatch(self):
        for url in ["http://example.test/mcp", "https://user:password@example.test/mcp",
                    "https://example.test/mcp?secret=x", "https://example.test/mcp#x",
                    "https://example.test/\nprivate"]:
            with self.assertRaises(ValueError):
                ObservedHttpClient(url, server="test", tools=[], emit=lambda _: None,
                                   protocol_version="2025-11-25")
        with endpoint(result()) as (url, calls):
            client = self.client(url, [])
            for headers in [{"X-EAP-Runtime-Token": "SYNTHETIC"}, {"Transfer-Encoding": "chunked"},
                            {"Host": "other"}, {"Authorization": "Bearer x\r\nOther: y"},
                            {"Mcp-Session-Id": "a", "mcp-session-id": "b"}]:
                with self.assertRaises(ValueError):
                    client.post(request(), headers=headers)
            with self.assertRaises(ValueError):
                client.post(request(), trace_id="untrusted")
            self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
