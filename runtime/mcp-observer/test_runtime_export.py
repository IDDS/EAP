"""Remote-only tests. All credentials, HTTP services and tools are synthetic."""
import contextlib
import http.server
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock
import uuid

from launch import CredentialWriter, start_observed_server
from observer import McpObserver
from runtime_export import RuntimeExporter, RuntimeLease


def credential(sequence=1, **changes):
    return {**{"instanceKey": "eap-agents/fixture", "agentId": "78",
               "actorUid": "11111111-1111-4111-8111-111111111111",
               "token": "eap_rt_" + secrets.token_urlsafe(32),
               "expiresAt": int(time.time()) + 60, "sequence": sequence}, **changes}


def event():
    events = []
    observer = McpObserver("fixture", ["calculate_sum"], events.append)
    observer.feed("client", b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"calculate_sum","arguments":{"secret":"PRIVATE_INPUT"}}}\n')
    observer.feed("server", b'{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"PRIVATE_OUTPUT"}]}}\n')
    return events[0]


def receipt(**changes):
    data = {"spansScanned": 1, "traceSpansRecorded": 1, "mcpSpansRecorded": 1,
            "genAiSpansDetected": 0, "invocationsRecorded": 0, "warnings": []}
    return json.dumps({"success": True, "data": {**data, **changes}}).encode()


def until(predicate, timeout=3):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError("bounded condition not reached")


@contextlib.contextmanager
def lease_pipe():
    read_fd, write_fd = os.pipe()
    writer = CredentialWriter(write_fd)
    value = credential()
    writer.update(value)
    lease = RuntimeLease(read_fd)
    assert lease.ready.wait(1)
    try:
        yield lease, writer, value
    finally:
        writer.close()
        lease.close()


@contextlib.contextmanager
def receiver(status=200, body=None, delay=0):
    requests = []
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            payload = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            requests.append({"path": self.path, "headers": dict(self.headers), "body": payload})
            time.sleep(delay)
            code, content = status, receipt() if body is None else body
            if callable(body):
                code, content = body(requests[-1])
            try:
                self.send_response(code)
                self.send_header("Location", "http://127.0.0.1:1/should-not-follow")
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)
            except OSError:
                pass
        def log_message(self, *_):
            pass
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05})
    thread.start()
    try:
        yield "http://127.0.0.1:" + str(server.server_port), requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=1)


class LeaseTests(unittest.TestCase):
    def test_rotation_and_stale_updates_do_not_roll_back(self):
        with lease_pipe() as (lease, writer, first):
            newer = credential(2)
            writer.update(newer)
            until(lambda: lease.snapshot() and lease.snapshot()["sequence"] == 2)
            writer.update(first)
            until(lambda: lease.invalid == 1)
            self.assertEqual(lease.snapshot()["sequence"], 2)
            lease.reject(first)
            self.assertIsNotNone(lease.snapshot())
            lease.reject(newer)
            self.assertIsNone(lease.snapshot())

    def test_identity_change_permanently_closes_channel(self):
        for change in [{"instanceKey": "eap-agents/another"}, {"agentId": "79"},
                       {"actorUid": str(uuid.uuid4())}]:
            with self.subTest(change=list(change)):
                with lease_pipe() as (lease, writer, _):
                    writer.update(credential(2, **change))
                    until(lambda: lease.closed)
                    self.assertIsNone(lease.snapshot())
                    self.assertEqual(lease.invalid, 1)

    def test_expiry_and_parent_eof_remove_credential(self):
        with lease_pipe() as (lease, writer, _):
            writer.update(credential(2, expiresAt=int(time.time()) - 1))
            until(lambda: lease.sequence == 2)
            self.assertIsNone(lease.snapshot())
            writer.update(credential(3))
            until(lambda: lease.snapshot() is not None)
            writer.close()
            until(lambda: lease.closed)
            self.assertIsNone(lease.snapshot())

    def test_invalid_duplicate_and_oversized_updates_fail_closed(self):
        for raw in [b"{", b"x" * 2049, b'{"sequence":2,"sequence":3}',
                    json.dumps(credential(sequence=True)).encode()]:
            with self.subTest(size=len(raw)):
                with lease_pipe() as (lease, writer, _):
                    os.write(writer.fd, raw + b"\n")
                    until(lambda: lease.closed)
                    self.assertIsNone(lease.snapshot())
                    self.assertEqual(lease.invalid, 1)

    def test_regular_files_and_stdio_are_rejected(self):
        with tempfile.TemporaryFile() as file:
            with self.assertRaises(ValueError):
                RuntimeLease(file.fileno())
        with self.assertRaises(ValueError):
            RuntimeLease(0)

    def test_full_pipe_cannot_block_owner_renewal(self):
        rfd, wfd = os.pipe()
        writer = CredentialWriter(wfd)
        start = time.monotonic()
        try:
            with self.assertRaises(OSError):
                for sequence in range(1, 10000):
                    writer.update(credential(sequence))
            self.assertLess(time.monotonic() - start, 2)
            self.assertIsNone(writer.fd)
        finally:
            writer.close()
            os.close(rfd)


class ExportTests(unittest.TestCase):
    def test_scoped_metadata_receipt_and_no_environment_proxy(self):
        with receiver() as (url, requests), lease_pipe() as (lease, _, value), \
                mock.patch.dict(os.environ, {"HTTP_PROXY": "http://127.0.0.1:1", "http_proxy": "http://127.0.0.1:1",
                                             "NO_PROXY": "", "no_proxy": ""}):
            exporter = RuntimeExporter(url, lease, allow_http=True)
            exporter.submit(event())
            counts = exporter.close()
            self.assertEqual(counts["accepted"], 1)
            self.assertEqual(len(requests), 1)
            request = requests[0]
            self.assertEqual(request["path"], "/api/runtime-access/v1/traces")
            self.assertEqual(request["headers"]["X-EAP-Runtime-Key"], value["instanceKey"])
            self.assertEqual(request["headers"]["X-EAP-Runtime-Token"], value["token"])
            self.assertNotIn("Authorization", request["headers"])
            self.assertNotIn(b"PRIVATE", request["body"])
            self.assertNotIn(value["token"].encode(), request["body"])
            self.assertNotIn("eap.agent.id", request["body"].decode())
            self.assertIn(b'MCP_PROTOCOL_V1', request["body"])

    def test_redirect_error_and_ambiguous_receipt_are_never_retried(self):
        for status, body, expected in [
            (302, b"", "unknown"), (500, b"", "unknown"), (429, b"", "rejected"),
            (200, b"not-json", "unknown"), (200, b"x" * 8193, "unknown"),
            (200, receipt(mcpSpansRecorded=0), "unknown"),
            (200, receipt(spansScanned=True), "unknown"),
            (200, receipt(warnings=["PRIVATE"]), "unknown"),
            (200, b'{"success":true,"success":false}', "unknown"),
        ]:
            with self.subTest(status=status, expected=expected):
                with receiver(status, body) as (url, requests), lease_pipe() as (lease, _, _):
                    exporter = RuntimeExporter(url, lease, allow_http=True)
                    exporter.submit(event())
                    counts = exporter.close()
                    self.assertEqual(counts[expected], 1)
                    self.assertEqual(counts["accepted"], 0)
                    self.assertEqual(len(requests), 1)

    def test_rejected_credential_stops_sends_until_new_delivery(self):
        with receiver(401) as (url, requests), lease_pipe() as (lease, writer, _):
            exporter = RuntimeExporter(url, lease, allow_http=True)
            exporter.submit(event())
            until(lambda: exporter.counts["rejected"] == 1)
            exporter.submit(event())
            until(lambda: exporter.counts["unavailable"] == 1)
            writer.update(credential(2))
            until(lambda: lease.snapshot() is not None)
            exporter.submit(event())
            counts = exporter.close()
            self.assertEqual(counts["rejected"], 2)
            self.assertEqual(len(requests), 2)

    def test_old_inflight_rejection_cannot_disable_rotated_credential(self):
        waiting, release = threading.Event(), threading.Event()
        newer = credential(2)
        def reply(request):
            if request["headers"]["X-EAP-Runtime-Token"] == newer["token"]:
                return 200, receipt()
            waiting.set()
            release.wait(1)
            return 401, b""
        with receiver(body=reply) as (url, requests), lease_pipe() as (lease, writer, _):
            exporter = RuntimeExporter(url, lease, allow_http=True)
            exporter.submit(event())
            self.assertTrue(waiting.wait(1))
            writer.update(newer)
            until(lambda: lease.snapshot() and lease.snapshot()["sequence"] == 2)
            release.set()
            until(lambda: exporter.counts["rejected"] == 1)
            self.assertIsNotNone(lease.snapshot())
            exporter.submit(event())
            counts = exporter.close()
            self.assertEqual(counts["accepted"], 1)
            self.assertEqual(len(requests), 2)

    def test_timeout_cannot_replay_tool_or_telemetry(self):
        with receiver(delay=0.5) as (url, requests), lease_pipe() as (lease, _, _):
            exporter = RuntimeExporter(url, lease, allow_http=True, timeout=0.1)
            exporter.submit(event())
            counts = exporter.close()
            self.assertEqual(counts["unknown"], 1)
            self.assertEqual(len(requests), 1)

    def test_queue_backpressure_and_shutdown_are_bounded_and_counted(self):
        with receiver(delay=2) as (url, requests), lease_pipe() as (lease, _, _):
            exporter = RuntimeExporter(url, lease, allow_http=True, timeout=5, capacity=2)
            exporter.submit(event())
            until(lambda: len(requests) == 1)
            for _ in range(10):
                exporter.submit(event())
            start = time.monotonic()
            counts = exporter.close(grace=0.01)
            self.assertLess(time.monotonic() - start, 0.6)
            self.assertEqual(counts["submitted"], 11)
            self.assertEqual(counts["unknown"], 1)
            self.assertEqual(counts["queueDropped"], 8)
            self.assertEqual(counts["shutdownDropped"], 2)
            self.assertEqual(len(requests), 1)

    def test_invalid_target_or_unavailable_identity_makes_no_request(self):
        with lease_pipe() as (lease, writer, _):
            for url in ["http://127.0.0.1", "https://u:p@localhost",
                        "https://localhost/x", "https://localhost?token=x",
                        "https://localhost#x", "file:///tmp/x", "https://localhost\n"]:
                with self.assertRaises(ValueError):
                    RuntimeExporter(url, lease)
            writer.close()
            until(lambda: lease.closed)
            with receiver() as (url, requests):
                exporter = RuntimeExporter(url, lease, allow_http=True)
                exporter.submit(event())
                counts = exporter.close()
                self.assertEqual(counts["unavailable"], 1)
                self.assertEqual(requests, [])


class RelayRuntimeTests(unittest.TestCase):
    def test_tool_failure_and_disconnect_keep_distinct_exported_outcomes(self):
        for mode, expected, status in [("failure", "FAILED", 2), ("disconnect", "UNKNOWN", 0)]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp, receiver() as (url, requests):
                server = Path(temp) / "server.py"
                code = "import sys\nsys.stdin.buffer.readline()\n"
                if mode == "failure":
                    code += "print('{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[],\"isError\":true}}')\n"
                server.write_text(code)
                process, writer = start_observed_server(
                    [sys.executable, str(server)], server="fixture", tools=["x"],
                    runtime_url=url, lease=credential(), allow_http=True)
                try:
                    output, summary = process.communicate(
                        b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"x"}}\n',
                        timeout=8)
                finally:
                    writer.close()
                self.assertEqual(process.returncode, 0)
                self.assertEqual(json.loads(summary)["runtimeExport"]["accepted"], 1)
                self.assertEqual(len(requests), 1)
                span = json.loads(requests[0]["body"])["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
                attrs = {a["key"]: a["value"]["stringValue"] for a in span["attributes"]}
                self.assertEqual(attrs["eap.observation.outcome"], expected)
                self.assertEqual(attrs["eap.observation.effect"], "UNVERIFIED")
                self.assertEqual(attrs["eap.observation.task_correlation"], "UNAVAILABLE")
                self.assertEqual(span["status"]["code"], status)

    def test_host_launch_preserves_wire_hides_credential_and_correlates_trace(self):
        with tempfile.TemporaryDirectory() as temp, receiver() as (url, requests):
            server = Path(temp) / "server.py"
            # Server asserts no inherited credential descriptors or EAP env.
            server.write_text(
                "import os,sys,json\n"
                "assert not any(k.startswith('EAP_') for k in os.environ)\n"
                "for fd in range(3,256):\n"
                " try: os.fstat(fd)\n"
                " except OSError: continue\n"
                " raise AssertionError('unexpected inherited descriptor')\n"
                "r=json.loads(sys.stdin.buffer.readline())\n"
                "assert r['params']['arguments']['secret']=='PRIVATE_INPUT'\n"
                "sys.stdout.buffer.write(b'{ \"jsonrpc\": \"2.0\", \"id\": 7, \"result\": {\"content\": []} }\\n')\n"
                "sys.stdout.buffer.flush()\n")
            lease = credential()
            process, writer = start_observed_server(
                [sys.executable, str(server)], server="fixture", tools=["calculate_sum"],
                runtime_url=url, lease=lease, trace_id="a" * 32, parent_span_id="b" * 16,
                allow_http=True)
            try:
                output, summary = process.communicate(
                    b'{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"calculate_sum","arguments":{"secret":"PRIVATE_INPUT"}}}\n',
                    timeout=8)
            finally:
                writer.close()
            self.assertEqual(process.returncode, 0, summary.decode())
            self.assertEqual(output, b'{ "jsonrpc": "2.0", "id": 7, "result": {"content": []} }\n')
            self.assertEqual(json.loads(summary)["runtimeExport"]["accepted"], 1)
            self.assertNotIn(lease["token"].encode(), output + summary)
            self.assertEqual(len(requests), 1)
            span = json.loads(requests[0]["body"])["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
            self.assertEqual(span["traceId"], "a" * 32)
            self.assertEqual(span["parentSpanId"], "b" * 16)
            self.assertNotIn(b"PRIVATE", requests[0]["body"])

    def test_export_outage_does_not_change_protocol_or_tool_exit(self):
        with tempfile.TemporaryDirectory() as temp, receiver(503) as (url, requests):
            server = Path(temp) / "server.py"
            server.write_text("import sys\nsys.stdin.buffer.readline()\n"
                              "print('{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}')\n")
            process, writer = start_observed_server(
                [sys.executable, str(server)], server="fixture", tools=["x"],
                runtime_url=url, lease=credential(), allow_http=True)
            try:
                output, summary = process.communicate(
                    b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"x"}}\n',
                    timeout=8)
            finally:
                writer.close()
            self.assertEqual(process.returncode, 0)
            self.assertEqual(json.loads(output)["id"], 1)
            self.assertEqual(json.loads(summary)["runtimeExport"]["unknown"], 1)
            self.assertEqual(len(requests), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
