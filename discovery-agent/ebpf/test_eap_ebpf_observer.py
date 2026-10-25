import importlib.util
import pathlib
import sys
import tempfile
import unittest.mock
import unittest
import argparse
import contextlib
import io
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from concurrent.futures import Future
from types import SimpleNamespace


MODULE_PATH = pathlib.Path(__file__).with_name("eap_ebpf_observer.py")
SPEC = importlib.util.spec_from_file_location("eap_ebpf_observer", MODULE_PATH)
observer = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = observer
SPEC.loader.exec_module(observer)


class EbpfObserverTests(unittest.TestCase):
    def test_inherited_credential_pipe_is_consumed_and_closed(self):
        reader, writer = os.pipe()
        os.write(writer, b"synthetic-pipe-credential")
        os.close(writer)
        self.assertEqual(observer.read_credential_fd(reader), "synthetic-pipe-credential")
        with self.assertRaises(OSError):
            os.fstat(reader)

    def test_inherited_credential_rejects_empty_oversized_and_control_bytes(self):
        for value in (b"", b"x" * 257, b"synthetic\ncredential", b"\xff"):
            reader, writer = os.pipe()
            os.write(writer, value)
            os.close(writer)
            with self.assertRaisesRegex(ValueError, "^INVALID_MACHINE_CREDENTIAL$"):
                observer.read_credential_fd(reader)
            with self.assertRaises(OSError):
                os.fstat(reader)

    def test_memory_credential_upload_never_reads_state_file(self):
        response = unittest.mock.MagicMock()
        response.__enter__.return_value.read.return_value = b'{"success":true,"data":{"observationsInspected":1,"observationsRecorded":1}}'
        opener = unittest.mock.Mock()
        opener.open.return_value = response
        with unittest.mock.patch.object(observer, "load_json", side_effect=AssertionError("state file read")), \
                unittest.mock.patch.object(observer.urllib.request, "build_opener", return_value=opener):
            result = observer.upload({"server_url": "https://eap.example", "_machine_credential": "synthetic-memory-credential"}, [{"processName": "agent.py"}])
        self.assertEqual(opener.open.call_args.args[0].get_header("X-eap-agent-credential"), "synthetic-memory-credential")
        self.assertNotIn("synthetic-memory-credential", str(result))

    def test_parses_ipv4_and_mapped_ipv6(self):
        ipv4 = observer.parse_event_line("EAPBPF\t10\t42\tboss-agent\t4\t203.0.113.4\t443\n")
        mapped = observer.parse_event_line("EAPBPF\t11\t43\tworker\t6\t::ffff:127.0.0.1\t8080\n")

        self.assertEqual(ipv4.remote_address, "203.0.113.4")
        self.assertEqual(ipv4.remote_port, 443)
        self.assertEqual(mapped.family, 4)
        self.assertEqual(mapped.remote_address, "127.0.0.1")

    def test_rejects_invalid_event_lines(self):
        self.assertIsNone(observer.parse_event_line("noise"))
        self.assertIsNone(observer.parse_event_line("EAPBPF\tx\t42\ta\t4\t127.0.0.1\t443"))
        self.assertIsNone(observer.parse_event_line("EAPBPF\t1\t42\ta\t4\t127.0.0.1\t99999"))

    def test_merges_docker_identity_for_agent_workload(self):
        container_id = "a" * 64
        workload = observer.DockerWorkload(container_id, "boss-agent-backend", "boss:latest", "boss-agent", "backend")
        event = observer.ConnectEvent(1, 42, "python", 4, "203.0.113.10", 443)

        result = observer.build_observation(
            event,
            "worker-01",
            {container_id: workload},
            {"executable": "/usr/bin/python", "cmdline": "python app.py", "cgroup": f"0::/docker/{container_id}"},
        )

        self.assertEqual(result["processName"], "boss-agent-backend")
        self.assertEqual(result["identityKey"], "docker:worker-01:boss-agent:backend")
        self.assertEqual(result["containerId"], container_id)
        self.assertEqual(result["observationKind"], "CONNECT_ATTEMPT")
        self.assertNotIn("protocolHint", result)

    def test_filters_infrastructure_and_eap_processes(self):
        for value in ("tat_agent", "/usr/local/qcloud/tat_agent", "eap-discovery-agent", "node_exporter"):
            self.assertFalse(observer.looks_agent(value), value)
        self.assertTrue(observer.looks_agent("boss-agent-backend"))
        self.assertTrue(observer.looks_agent("langgraph worker"))

    def test_process_identity_without_container(self):
        event = observer.ConnectEvent(1, 99, "agent.py", 6, "2001:db8::1", 8443)
        result = observer.build_observation(
            event,
            "worker-02",
            {},
            {"executable": "/opt/agent/agent.py", "cmdline": "python agent.py", "cgroup": ""},
        )
        self.assertEqual(result["identityKey"], "process:worker-02:/opt/agent/agent.py")
        self.assertEqual(result["remoteAddress"], "2001:db8::1")

    def test_upload_uses_machine_credential_without_returning_it(self):
        with tempfile.TemporaryDirectory() as directory:
            state_path = pathlib.Path(directory) / "state.json"
            state_path.write_text('{"credential":"machine-secret"}', encoding="utf-8")
            config = {
                "server_url": "https://eap.example",
                "state_dir": directory,
            }

            class Response:
                def __enter__(self):
                    return self

                def __exit__(self, *_args):
                    return False

                def read(self, _size):
                    return b'{"success":true,"data":{"observationsInspected":1,"observationsRecorded":1}}'

            opener = unittest.mock.Mock()
            opener.open.return_value = Response()
            with unittest.mock.patch.object(observer.urllib.request, "build_opener", return_value=opener):
                result = observer.upload(config, [{"processName": "boss-agent"}])

            sent = opener.open.call_args.args[0]
            self.assertEqual(sent.get_header("X-eap-agent-credential"), "machine-secret")
            self.assertEqual(result["observationsRecorded"], 1)
            self.assertNotIn("machine-secret", str(result))


class EbpfReliabilityTests(unittest.TestCase):
    def test_connect_attempt_does_not_invent_protocol_or_byte_measurements(self):
        event = observer.ConnectEvent(1, 42, "agent.py", 4, "203.0.113.10", 443)
        result = observer.build_observation(event, "fixture", {},
                {"executable": "/fixture/agent.py", "cmdline": "agent.py", "cgroup": ""})
        self.assertEqual(result.get("observationKind"), "CONNECT_ATTEMPT")
        for field in ("transport", "protocolHint", "bytesSent", "bytesReceived", "tlsServerName"):
            self.assertNotIn(field, result)

    def run_stream(self, fail_attempts):
        clock = SimpleNamespace(now=0.0)
        lines = iter("EAPBPF\t1\t42\tagent.py\t4\t203.0.113.10\t%d\n" % port
                     for port in range(10000, 10020))
        stream = SimpleNamespace(readline=lambda: next(lines, ""), fileno=lambda: -1)
        child = unittest.mock.Mock(stdout=stream)
        child.poll.return_value = None
        selector = unittest.mock.Mock()
        def select(_timeout):
            clock.now += 1.0
            return [(SimpleNamespace(fileobj=stream), 1)] if clock.now <= 20 else []
        selector.select.side_effect = select
        attempts = []
        def emit(_config, batch, _dry_run):
            attempts.append((clock.now, len(batch)))
            if len(attempts) <= fail_attempts:
                raise OSError("fixture-private-response")
        def immediate_upload(config, batch, dry_run):
            future = Future()
            try: emit(config, batch, dry_run); future.set_result(None)
            except Exception as error: future.set_exception(error)
            return future
        config = {"ebpf_observer": {"max_events": 3, "batch_interval_seconds": 5,
                  "retry_initial_seconds": 5, "retry_max_seconds": 10, "status_interval_seconds": 5}}
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.ExitStack() as stack:
            stack.enter_context(contextlib.redirect_stdout(output))
            stack.enter_context(contextlib.redirect_stderr(errors))
            for target, value in [("load_json", config), ("docker_workloads", {}),
                                  ("process_metadata", {"cmdline": "agent.py", "executable": "/fixture/agent.py", "cgroup": ""})]:
                stack.enter_context(unittest.mock.patch.object(observer, target, return_value=value))
            stack.enter_context(unittest.mock.patch.object(observer.sys, "platform", "linux"))
            stack.enter_context(unittest.mock.patch.object(observer.shutil, "which", return_value="/fixture/bpftrace"))
            stack.enter_context(unittest.mock.patch.object(observer.Path, "is_file", return_value=True))
            stack.enter_context(unittest.mock.patch.object(observer.subprocess, "Popen", return_value=child))
            stack.enter_context(unittest.mock.patch.object(observer.os, "read", side_effect=lambda *_: stream.readline().encode()))
            stack.enter_context(unittest.mock.patch.object(observer.selectors, "DefaultSelector", return_value=selector))
            stack.enter_context(unittest.mock.patch.object(observer.time, "monotonic", side_effect=lambda: clock.now))
            stack.enter_context(unittest.mock.patch.object(observer.signal, "signal"))
            stack.enter_context(unittest.mock.patch.object(observer, "emit_batch", side_effect=emit))
            stack.enter_context(unittest.mock.patch.object(observer, "start_upload", side_effect=immediate_upload, create=True))
            observer.observe(argparse.Namespace(config="fixture", program="fixture", duration=40, dry_run=False))
        return attempts, output.getvalue(), errors.getvalue()

    def test_failed_uploads_keep_cache_bounded_and_back_off(self):
        attempts, output, errors = self.run_stream(100)
        self.assertTrue(attempts)
        self.assertLessEqual(max(size for _, size in attempts), 3)
        self.assertTrue(all(b[0] - a[0] >= 5 for a, b in zip(attempts, attempts[1:])))
        self.assertNotIn("fixture-private-response", output + errors)


    def test_delivery_recovers_and_exposes_drops_without_payloads(self):
        attempts, output, errors = self.run_stream(2)
        statuses = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
        health = [row for row in statuses if row.get("event") == "observer_health"]
        self.assertTrue(health)
        self.assertGreater(health[-1]["observationsAcknowledged"], 0)
        self.assertGreater(health[-1]["observationsDropped"], 0)
        self.assertEqual(health[-1]["queuedObservations"], 0)
        self.assertEqual(health[-1]["uploadFailures"], 2)
        self.assertNotIn("203.0.113.10", output + errors)
        self.assertNotIn("fixture-private-response", output + errors)


class EbpfPipeTests(unittest.TestCase):
    def test_fragmented_lines_and_oversized_input_remain_bounded(self):
        reader = observer.EventLines()
        self.assertEqual(reader.feed(b"EAP"), ([], 0))
        lines, invalid = reader.feed(b"BPF\t1\t42\tagent.py\t4\t127.0.0.1\t80\n")
        self.assertEqual(len(lines), 1)
        self.assertIsNotNone(observer.parse_event_line(lines[0]))
        self.assertEqual(invalid, 0)
        self.assertEqual(reader.feed(b"x" * 65536), ([], 1))
        self.assertEqual(reader.pending, b"")
        self.assertEqual(reader.feed(b"y" * 65536), ([], 0))
        self.assertEqual(reader.feed(b"\nvalid\n\xff\n"), (["valid"], 1))

    def test_burst_in_real_pipe_is_fully_consumed_while_writer_stays_open(self):
        read_fd, write_fd = os.pipe()
        output = io.StringIO()
        with os.fdopen(read_fd, "r") as stream:
            try:
                os.write(write_fd, b"".join(("EAPBPF\t1\t42\tagent.py\t4\t127.0.0.1\t%d\n" % (10000+i)).encode() for i in range(6)))
                child = unittest.mock.Mock(stdout=stream); child.poll.return_value = None
                future = Future(); future.set_result(None)
                with contextlib.ExitStack() as stack:
                    stack.enter_context(contextlib.redirect_stdout(output))
                    stack.enter_context(unittest.mock.patch.object(observer.sys, "platform", "linux"))
                    stack.enter_context(unittest.mock.patch.object(observer.shutil, "which", return_value="/fixture/bpftrace"))
                    stack.enter_context(unittest.mock.patch.object(observer.Path, "is_file", return_value=True))
                    stack.enter_context(unittest.mock.patch.object(observer, "load_json", return_value={"ebpf_observer": {"batch_interval_seconds": 300}}))
                    stack.enter_context(unittest.mock.patch.object(observer, "docker_workloads", return_value={}))
                    stack.enter_context(unittest.mock.patch.object(observer, "process_metadata", return_value={"cmdline": "agent.py"}))
                    stack.enter_context(unittest.mock.patch.object(observer.subprocess, "Popen", return_value=child))
                    stack.enter_context(unittest.mock.patch.object(observer.signal, "signal"))
                    stack.enter_context(unittest.mock.patch.object(observer, "start_upload", return_value=future))
                    observer.observe(argparse.Namespace(config="fixture", program="fixture", duration=1, dry_run=True))
            finally: os.close(write_fd)
        health = [json.loads(line) for line in output.getvalue().splitlines() if line.startswith("{")][-1]
        self.assertEqual(health["eventsSeen"], 6)
        self.assertEqual(health["observationsPrinted"], 6)


class EbpfDeliveryTests(unittest.TestCase):
    def observation(self, port=443):
        return {"identityKey": "fixture", "processId": 42, "remoteAddress": "127.0.0.1", "remotePort": port}

    def buffer(self, **kwargs):
        return observer.DeliveryBuffer(kwargs.get("config", {}), 3, 5, 5, 10, kwargs.get("dry_run", False), 0)

    def test_slow_upload_keeps_one_flight_and_bounded_pending_observations(self):
        pending = Future()
        buffer = self.buffer()
        with unittest.mock.patch.object(observer, "start_upload", return_value=pending) as send:
            for port in range(100):
                buffer.offer(self.observation(port))
                buffer.poll(port)
            self.assertEqual(send.call_count, 1)
            self.assertEqual(len(buffer.pending), 3)
            self.assertEqual(len(buffer.batch), 3)
            self.assertEqual(buffer.stats["observationsDropped"], 97)
            self.assertEqual(buffer.stats["uploadAttempts"], 1)

    def test_event_arriving_during_upload_is_not_erased_by_earlier_ack(self):
        first = Future()
        buffer = self.buffer()
        buffer.offer(self.observation())
        with unittest.mock.patch.object(observer, "start_upload", return_value=first):
            buffer.poll(5)
            newer = self.observation()
            buffer.offer(newer)
            first.set_result(None)
            buffer.poll(6, start=False)
        self.assertEqual(len(buffer.pending), 1)
        self.assertIs(next(iter(buffer.pending.values())), newer)
        self.assertEqual(buffer.stats["observationsCoalesced"], 1)
        self.assertEqual(buffer.stats["observationsAcknowledged"], 1)

    def test_dry_run_is_not_counted_as_server_acknowledgement(self):
        future = Future(); future.set_result(None)
        buffer = self.buffer(dry_run=True); buffer.offer(self.observation())
        with unittest.mock.patch.object(observer, "start_upload", return_value=future):
            buffer.poll(5); buffer.poll(6, start=False)
        self.assertEqual(buffer.stats["observationsAcknowledged"], 0)
        self.assertEqual(buffer.stats["observationsPrinted"], 1)

    def test_shutdown_does_not_bypass_backoff(self):
        future = Future(); future.set_exception(OSError("fixture-private-error"))
        buffer = self.buffer(); buffer.offer(self.observation())
        with unittest.mock.patch.object(observer, "start_upload", return_value=future) as send:
            buffer.poll(5); buffer.poll(6)
            buffer.finish(7)
            self.assertEqual(send.call_count, 1)
        self.assertEqual(buffer.health(7)["lastDeliveryError"], "UPLOAD_FAILED")

    def test_unavailable_kernel_sensor_reports_reason(self):
        output = io.StringIO()
        with unittest.mock.patch.object(observer.sys, "platform", "linux"), unittest.mock.patch.object(observer.shutil, "which", return_value=None), contextlib.redirect_stdout(output):
            self.assertEqual(observer.observe(argparse.Namespace()), 0)
        self.assertEqual(json.loads(output.getvalue())["state"], "UNAVAILABLE")
        self.assertEqual(json.loads(output.getvalue())["reason"], "BPFTRACE_MISSING")


class EbpfHttpDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.storage = tempfile.TemporaryDirectory()
        pathlib.Path(self.storage.name, "state.json").write_text('{"credential":"synthetic-machine-identity"}')
        self.responses = []
        self.requests = []
        self.started, self.release = threading.Event(), threading.Event()
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args): pass
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                owner.requests.append((self.path, self.headers.get("X-EAP-Agent-Credential"), json.loads(body)))
                status, response, hold = owner.responses.pop(0)
                owner.started.set()
                if hold: owner.release.wait(3)
                self.send_response(status)
                if status == 302: self.send_header("Location", owner.config["server_url"] + "/redirected")
                self.end_headers(); self.wfile.write(response)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.config = {"server_url": "http://127.0.0.1:" + str(self.server.server_port), "allow_insecure_http": True, "state_dir": self.storage.name}
        self.batch = [{"processName": "agent.py"}]

    def tearDown(self):
        self.release.set(); self.server.shutdown(); self.server.server_close(); self.thread.join(3); self.storage.cleanup()

    def receipt(self, inspected=1):
        return json.dumps({"success": True, "data": {"observationsInspected": inspected, "observationsRecorded": 1,
                          "untrustedExtra": "synthetic-private-response"}}).encode()

    def test_http_failure_is_sanitized_and_following_upload_recovers(self):
        self.responses = [(503, b'synthetic-private-response', False), (200, self.receipt(), False)]
        with self.assertRaisesRegex(observer.UploadFailure, '^HTTP_503$'):
            observer.upload(self.config, self.batch)
        ack = observer.upload(self.config, self.batch)
        self.assertEqual(ack, {"observationsInspected": 1, "observationsRecorded": 1})
        self.assertEqual(len(self.requests), 2)
        self.assertNotIn("synthetic-private-response", str(ack))

    def test_upload_does_not_follow_redirect_with_machine_identity(self):
        self.responses = [(302, b'synthetic-private-response', False)]
        with self.assertRaisesRegex(observer.UploadFailure, '^HTTP_302$'):
            observer.upload(self.config, self.batch)
        self.assertEqual(len(self.requests), 1)
        self.assertEqual(self.requests[0][0], "/api/discovery/v1/agent/host-observations")

    def test_partial_or_invalid_receipts_do_not_acknowledge_batch(self):
        for body in (self.receipt(0), b'{}', b'{"success":false}', b'x' * 65537):
            self.responses = [(200, body, False)]
            with self.assertRaisesRegex(observer.UploadFailure, '^INVALID_ACKNOWLEDGEMENT$'):
                observer.upload(self.config, self.batch)

    def test_slow_http_upload_returns_control_before_response(self):
        self.responses = [(200, self.receipt(), True)]
        future = observer.start_upload(self.config, self.batch, False)
        try:
            self.assertTrue(self.started.wait(2))
            self.assertFalse(future.done())
        finally: self.release.set()
        future.result(timeout=3)

    def test_network_failure_keeps_batch_and_delivery_is_not_acknowledged(self):
        self.server.shutdown(); self.server.server_close()
        future = observer.start_upload(self.config, self.batch, False)
        with self.assertRaisesRegex(observer.UploadFailure, '^NETWORK_ERROR$'):
            future.result(timeout=3)



if __name__ == "__main__":
    unittest.main()
