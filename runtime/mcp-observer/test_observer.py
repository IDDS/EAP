import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from observer import McpObserver, otlp


def wire(value):
    return json.dumps(value, separators=(",", ":")).encode() + b"\n"


def request(key, name="calculate_sum"):
    return {"jsonrpc": "2.0", "id": key, "method": "tools/call",
            "params": {"name": name, "arguments": {"private": "DO_NOT_EXPORT_INPUT"}}}


def response(key, is_error=False):
    return {"jsonrpc": "2.0", "id": key, "result": {"content": [{"type": "text", "text": "DO_NOT_EXPORT_RESULT"}], "isError": is_error}}


class ObserverTests(unittest.TestCase):
    def create(self, **kw):
        events = []
        return McpObserver("fixture", ["calculate_sum"], events.append, **kw), events

    def test_fragmented_multiplexed_responses_and_typed_ids(self):
        observer, events = self.create()
        raw = wire(request(1)) + wire(request("1"))
        for b in raw:
            observer.feed("client", bytes([b]))
        observer.feed("server", wire(response("1", True)) + wire(response(1)))
        self.assertEqual([e["status"] for e in events], ["FAILED", "SUCCEEDED"])
        self.assertEqual(len({e["spanId"] for e in events}), 2)
        self.assertEqual(len({e["traceId"] for e in events}), 2)
        encoded = json.dumps([{"event": e, "otlp": otlp(e, 75)} for e in events])
        self.assertNotIn("DO_NOT_EXPORT", encoded)
        self.assertNotIn("arguments", encoded)
        self.assertTrue(all(not e["effectVerified"] for e in events))

    def test_trusted_task_parent_and_untrusted_wire_metadata(self):
        observer, events = self.create(trace_id="a" * 32, parent_span_id="b" * 16)
        req = request(1)
        req["params"]["_meta"] = {"traceparent": "forged", "agentId": 999}
        observer.feed("client", wire(req))
        observer.feed("server", wire(response(1)))
        span = otlp(events[0], 75)["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
        self.assertEqual(span["traceId"], "a" * 32)
        self.assertEqual(span["parentSpanId"], "b" * 16)
        self.assertNotIn("forged", json.dumps(events))
        self.assertEqual(span["status"]["code"], 1)
        attrs = {a["key"]: a["value"]["stringValue"] for a in span["attributes"]}
        self.assertEqual(attrs["eap.observation.contract"], "MCP_PROTOCOL_V1")
        self.assertEqual(attrs["eap.observation.reason"], "TOOL_RESULT")
        self.assertEqual(attrs["eap.observation.effect"], "UNVERIFIED")

    def test_cancel_eof_and_rpc_error(self):
        for mode, expected in [("cancel", "UNKNOWN"), ("eof", "UNKNOWN"), ("rpc", "FAILED")]:
            with self.subTest(mode=mode):
                observer, events = self.create()
                observer.feed("client", wire(request(1)))
                if mode == "cancel":
                    observer.feed("client", wire({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 1}}))
                    observer.feed("server", wire(response(1)))
                elif mode == "rpc":
                    observer.feed("server", wire({"jsonrpc": "2.0", "id": 1, "error": {"code": -32603, "message": "PRIVATE_ERROR"}}))
                observer.close()
                self.assertEqual(len(events), 1)
                self.assertEqual(events[0]["status"], expected)
                self.assertEqual(events[0]["evidence"],
                                 "PROTOCOL_RESPONSE" if expected == "FAILED" else "NO_CONFIRMED_RESPONSE")
                self.assertNotIn("PRIVATE_ERROR", json.dumps(events))
                self.assertEqual(otlp(events[0])["resourceSpans"][0]["scopeSpans"][0]["spans"][0]["status"]["code"],
                                 2 if mode == "rpc" else 0)

    def test_ambiguous_and_reused_ids_never_gain_false_success(self):
        for completed in [False, True]:
            observer, events = self.create()
            observer.feed("client", wire(request(1)))
            if completed:
                observer.feed("server", wire(response(1)))
            observer.feed("client", wire(request(1)))
            observer.feed("server", wire(response(1)))
            self.assertTrue(observer.degraded)
            self.assertEqual(len(events), 1)
            self.assertEqual(events[0]["status"], "SUCCEEDED" if completed else "UNKNOWN")

    def test_limits_and_invalid_frames_lose_correlation(self):
        frames = [b"not-json\n", b'{"jsonrpc":"2.0","id":1,"id":2}\n',
                  b"[" + b"0," * 600 + b"0]\n", wire({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "x"}})]
        for frame in frames:
            observer, events = self.create(max_frame=1024, max_pending=1)
            observer.feed("client", wire(request(1)))
            observer.feed("client", frame)
            observer.feed("server", wire(response(1)))
            self.assertTrue(observer.degraded)
            self.assertEqual([e["status"] for e in events], ["UNKNOWN"])

    def test_non_tool_traffic_and_progress_do_not_create_tool_success(self):
        observer, events = self.create()
        observer.feed("client", wire({"jsonrpc": "2.0", "method": "tools/list", "id": 10}))
        observer.feed("server", wire({"jsonrpc": "2.0", "result": {"tools": []}, "id": 10}))
        observer.feed("client", wire(request(11, "DO_NOT_EXPORT_TOOL_NAME")))
        observer.feed("server", wire({"jsonrpc": "2.0", "method": "notifications/progress", "params": {}}))
        self.assertFalse(events)
        observer.feed("server", wire(response(11)))
        self.assertEqual(events[0]["tool"], "unregistered_tool")

    def test_malformed_and_deferred_results_remain_unknown(self):
        for result in [{"task": {"taskId": "private"}}, {}, {"content": [], "isError": "false"}, {"content": [1]},
                       {"content": [{"type": "text"}]}, {"content": [{"type": "image", "data": 1}]},
                       {"content": [{"type": "resource", "resource": {"uri": "urn:test"}}]},
                       {"content": [], "structuredContent": "private"}]:
            observer, events = self.create()
            observer.feed("client", wire(request(1)))
            observer.feed("server", wire({"jsonrpc": "2.0", "id": 1, "result": result}))
            self.assertEqual(events[0]["status"], "UNKNOWN")

    def test_content_shapes_and_invalid_error_are_checked_without_exporting_bodies(self):
        observer, events = self.create()
        observer.feed("client", wire(request(1)))
        observer.feed("server", wire({"jsonrpc": "2.0", "id": 1, "result": {"content": [
            {"type": "text", "text": "PRIVATE"},
            {"type": "image", "data": "PRIVATE", "mimeType": "image/png"},
            {"type": "audio", "data": "PRIVATE", "mimeType": "audio/wav"},
            {"type": "resource_link", "name": "PRIVATE", "uri": "urn:PRIVATE"},
            {"type": "resource", "resource": {"uri": "urn:PRIVATE", "text": "PRIVATE"}},
            {"type": "resource", "resource": {"uri": "urn:PRIVATE", "blob": "PRIVATE"}}
        ]}}))
        self.assertEqual(events[0]["status"], "SUCCEEDED")
        self.assertNotIn("PRIVATE", json.dumps(events))
        observer.feed("client", wire(request(2)))
        observer.feed("server", wire({"jsonrpc": "2.0", "id": 2, "error": {"code": -32603}}))
        self.assertEqual(events[1]["status"], "UNKNOWN")
        invalid = request(3)
        invalid["params"]["arguments"] = []
        observer.feed("client", wire(invalid))
        self.assertTrue(observer.degraded)

    def test_export_error_does_not_replay_or_raise(self):
        observer = McpObserver("fixture", [], lambda _: (_ for _ in ()).throw(OSError()))
        observer.feed("client", wire(request(1)))
        observer.feed("server", wire(response(1)))
        observer.close()
        self.assertEqual(observer.delivery_failures, 1)
        self.assertFalse(observer.pending)

    def test_real_stdio_relay_preserves_exact_wire_and_records_metadata(self):
        here = Path(__file__).parent
        with tempfile.TemporaryDirectory() as temp:
            server = Path(temp) / "fixture.py"
            # Actual child process: deliberately noncanonical JSON spacing.
            reply = b'{ "jsonrpc": "2.0", "id": 3, "result": { "content": [], "isError": true } }\n'
            server.write_text("import sys,os,json\nline=sys.stdin.buffer.readline()\n"
                              "assert json.loads(line)['params']['arguments']['private']=='DO_NOT_EXPORT_INPUT'\n"
                              "assert 'EAP_RUNTIME_TOKEN' not in os.environ\n"
                              f"sys.stdout.buffer.write({reply!r});sys.stdout.buffer.flush()\n")
            destination = Path(temp) / "events.jsonl"
            run = subprocess.run([sys.executable, str(here / "relay.py"), "--server", "fixture", "--tool", "calculate_sum",
                                  "--events", str(destination), "--", sys.executable, str(server)],
                                 input=wire(request(3)), capture_output=True, timeout=10,
                                 env={**os.environ, "EAP_RUNTIME_TOKEN": "synthetic-only"})
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertEqual(run.stdout, reply)
            saved = json.loads(destination.read_text())
            self.assertEqual(saved["event"]["status"], "FAILED")
            self.assertNotIn("DO_NOT_EXPORT", destination.read_text())
            self.assertNotIn("synthetic-only", destination.read_text())
            self.assertEqual(destination.stat().st_mode & 0o777, 0o600)

    def test_child_that_closes_output_cannot_block_relay_shutdown(self):
        here = Path(__file__).parent
        with tempfile.TemporaryDirectory() as temp:
            server = Path(temp) / "hanging.py"
            server.write_text("import os,time\nos.close(1)\ntime.sleep(30)\n")
            run = subprocess.run([sys.executable, str(here / "relay.py"), "--server", "fixture",
                                  "--exit-timeout", "0.1", "--events", str(Path(temp) / "events.jsonl"),
                                  "--", sys.executable, str(server)], input=b"", capture_output=True, timeout=8)
            self.assertNotEqual(run.returncode, 0)
            self.assertTrue(json.loads(run.stderr)["childExitTimeout"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
