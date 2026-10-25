import asyncio
import contextvars
import hashlib
import json
import os
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from eap_execution import Journal, CodeSnapshot, HttpTransport, ObservationError


class ReceiptTransport:
    def __init__(self):
        self.payloads = []
        self.bad = False

    def send(self, key, body):
        self.payloads.append(body)
        size = len(body["events"])
        return dict(streamKey="a" * 64, received=size, recorded=size + int(self.bad), duplicates=0)


def scenario(journal, directory):
    async def child(name, code):
        with journal.agent(name) as ctx:
            await asyncio.sleep(0)
            if code:
                path = Path(directory) / "temporary_tool.py"
                path.write_bytes(b"result = 6 * 7\n")
                snapshot = journal.capture_code(path.read_bytes())
                path.unlink()
                with journal.tool("generated-calculate", code=snapshot):
                    result = {}
                    exec(compile(snapshot.data, "<controlled-fixture>", "exec"), {}, result)
                    assert result["result"] == 42
                assert not path.exists()
            else:
                with journal.tool("local-sum"):
                    await asyncio.sleep(0)
                    assert sum([20, 22]) == 42
            assert journal.current() == ctx
            return ctx.run_id
    async def run():
        with journal.agent("coordinator") as root:
            children = await asyncio.gather(child("coder", True), child("reviewer", False))
            assert journal.current() == root
            assert len(set(children)) == 2
    asyncio.run(run())


class ExecutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "journal.sqlite"
        self.journal = Journal(self.path, "eap-agents/fixture")

    def tearDown(self):
        self.journal.close()
        self.temp.cleanup()

    def test_explicit_task_trace_is_inherited_by_child_and_tool(self):
        with self.journal.agent("task", trace_id="1" * 32):
            with self.journal.agent("child"):
                with self.journal.tool("calculate"):
                    pass
        self.assertEqual({e["traceId"] for e in self.journal.pending()}, {"1" * 32})

    def test_invalid_or_conflicting_task_trace_cannot_emit_a_run(self):
        for trace in ["0" * 32, "A" * 32, "private-input", 1]:
            with self.assertRaises(ValueError):
                with self.journal.agent("task", trace_id=trace): pass
        self.assertEqual(self.journal.pending(), [])
        with self.journal.agent("task", trace_id="1" * 32):
            with self.assertRaises(ValueError):
                with self.journal.agent("child", trace_id="2" * 32): pass
        self.assertEqual(len(self.journal.pending()), 2)

    def test_same_pid_parallel_agents_keep_parent_and_tool_ownership(self):
        scenario(self.journal, self.temp.name)
        events = self.journal.pending()
        starts = [e for e in events if e["kind"] == "RUN_STARTED"]
        root = next(e for e in starts if "parentRunId" not in e)
        children = {e["runId"] for e in starts if e.get("parentRunId") == root["runId"]}
        self.assertEqual(len(children), 2)
        self.assertEqual({e["processId"] for e in events}, {os.getpid()})
        self.assertEqual({e["runId"] for e in events if e["kind"] == "TOOL_STARTED"}, children)
        self.assertEqual(len({e["traceId"] for e in events}), 1)
        self.assertEqual([e["sequence"] for e in events], list(range(1, len(events) + 1)))

    def test_deleted_code_keeps_digest_and_not_content(self):
        scenario(self.journal, self.temp.name)
        raw = json.dumps(self.journal.pending())
        self.assertIn(hashlib.sha256(b"result = 6 * 7\n").hexdigest(), raw)
        self.assertNotIn("result =", raw)
        self.assertNotIn(self.temp.name, raw)

    def test_snapshot_is_immutable_and_rejects_false_digest(self):
        with self.journal.agent("coder"):
            snapshot = self.journal.capture_code(b"result = 42")
            self.assertNotIn("result =", repr(snapshot))
            with self.assertRaises(ValueError):
                CodeSnapshot(snapshot.digest, "PYTHON", b"different")
            with self.assertRaises(TypeError):
                snapshot.data[0] = 1

    def test_same_path_changed_code_has_distinct_versions(self):
        with self.journal.agent("coder"):
            one = self.journal.capture_code(b"one")
            two = self.journal.capture_code(b"two")
            self.journal.capture_code(b"one")
        self.assertNotEqual(one.digest, two.digest)
        self.assertEqual(sum(e["kind"] == "ARTIFACT_REGISTERED" for e in self.journal.pending()), 2)

    def test_cancellation_is_recorded_and_context_restored(self):
        async def run():
            with self.assertRaises(asyncio.CancelledError):
                with self.journal.agent("cancelled"):
                    with self.journal.tool("waiting"):
                        raise asyncio.CancelledError()
            with self.assertRaises(ObservationError):
                self.journal.current()
        asyncio.run(run())
        ends = [e for e in self.journal.pending() if e["kind"].endswith("FINISHED")]
        self.assertEqual([e["outcome"] for e in ends], ["CANCELLED", "CANCELLED"])

    def test_failure_text_never_enters_journal(self):
        with self.assertRaisesRegex(RuntimeError, "PRIVATE_FAILURE_MARKER"):
            with self.journal.agent("failure"):
                raise RuntimeError("PRIVATE_FAILURE_MARKER")
        self.assertNotIn("PRIVATE_FAILURE_MARKER", json.dumps(self.journal.pending()))

    def test_thread_pool_requires_context_and_explicit_copy_preserves_it(self):
        with self.journal.agent("parent") as root:
            with ThreadPoolExecutor(max_workers=2) as pool:
                with self.assertRaises(ObservationError):
                    pool.submit(self.journal.current).result()
                ctx = contextvars.copy_context()
                self.assertEqual(pool.submit(ctx.run, self.journal.current).result(), root)

    def test_journals_never_implicitly_cross_link(self):
        other = Journal(Path(self.temp.name) / "other.sqlite", "eap-agents/other")
        try:
            with self.journal.agent("root"):
                with self.assertRaises(ObservationError):
                    with other.agent("foreign"):
                        self.fail("foreign body ran")
        finally:
            other.close()

    def test_failed_receipt_preserves_exact_retry_events(self):
        with self.journal.agent("root"):
            pass
        transport = ReceiptTransport()
        transport.bad = True
        original = self.journal.pending()
        with self.assertRaises(ObservationError):
            self.journal.flush(transport)
        self.assertEqual(self.journal.pending(), original)
        transport.bad = False
        self.assertEqual(self.journal.flush(transport), 2)
        self.assertEqual(transport.payloads[0], transport.payloads[1])
        self.assertEqual(self.journal.pending(), [])

    def test_reopen_flushes_durable_start_without_fabricating_end(self):
        manager = self.journal.agent("interrupted")
        manager.__enter__()
        self.journal.close()
        reopened = Journal(self.path, "eap-agents/fixture")
        try:
            self.assertEqual([e["kind"] for e in reopened.pending()], ["RUN_STARTED"])
            with self.assertRaises(ObservationError):
                with reopened.agent("new"):
                    pass
            self.assertEqual(reopened.flush(ReceiptTransport()), 1)
        finally:
            reopened.close()
            try:
                manager.__exit__(None, None, None)
            except ObservationError:
                pass

    def test_capacity_failure_prevents_unrecorded_start_and_keeps_business_error(self):
        self.journal.capacity = 1
        with self.assertRaisesRegex(RuntimeError, "original failure"):
            with self.journal.agent("root"):
                raise RuntimeError("original failure")
        self.assertEqual(len(self.journal.pending()), 1)
        with self.assertRaises(ObservationError):
            with self.journal.agent("cannot-start"):
                self.fail("unrecorded execution started")

    def test_private_file_and_single_writer_and_identity_pin(self):
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(BlockingIOError):
            Journal(self.path, "eap-agents/fixture")
        self.journal.close()
        with self.assertRaises(ValueError):
            Journal(self.path, "eap-agents/other")

    def test_arbitrary_content_fields_are_rejected_before_storage(self):
        with self.journal.agent("root") as ctx:
            with self.assertRaises(ValueError):
                self.journal.emit("RUN_FINISHED", ctx, outcome="FAILED", code="PRIVATE_MARKER")
        self.assertNotIn("PRIVATE_MARKER", json.dumps(self.journal.pending()))

    def test_fixed_transport_rejects_redirect_and_sanitizes_errors(self):
        state = {"status": 302, "received": None}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                state["received"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self.send_response(state["status"])
                self.end_headers()
                self.wfile.write(b"PRIVATE_RESPONSE_MARKER")
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            transport = HttpTransport("http://127.0.0.1:" + str(server.server_port), lambda: "eap_rt_" + "A" * 43, allow_http=True)
            with self.journal.agent("root"):
                pass
            with self.assertRaises(ObservationError) as error:
                self.journal.flush(transport)
            self.assertNotIn("PRIVATE_RESPONSE_MARKER", str(error.exception))
            self.assertEqual(len(self.journal.pending()), 2)
            self.assertNotIn("eap_rt_", json.dumps(state["received"]))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_transport_rejects_unsafe_configuration(self):
        for url in ("http://example.test", "https://user@example.test", "https://example.test/path", "https://example.test?x=y"):
            with self.assertRaises(ValueError):
                HttpTransport(url, lambda: None)

    def test_explicit_parent_survives_an_empty_dispatch_context(self):
        with self.journal.agent("sender") as sender:
            def delivered():
                self.assertIsNone(self.journal.current_or_none())
                with self.journal.agent("receiver", parent=sender) as receiver:
                    self.assertEqual(receiver.trace_id, sender.trace_id)
                self.assertIsNone(self.journal.current_or_none())
            contextvars.Context().run(delivered)
        starts = [e for e in self.journal.pending() if e["kind"] == "RUN_STARTED"]
        self.assertEqual(starts[1]["parentRunId"], sender.run_id)

    def test_explicit_root_ignores_ambient_queue_context_and_restores_it(self):
        with self.journal.agent("ambient") as ambient:
            with self.journal.agent("independent", parent=None) as independent:
                self.assertNotEqual(ambient.trace_id, independent.trace_id)
            self.assertEqual(self.journal.current(), ambient)
        self.assertTrue(all("parentRunId" not in e for e in self.journal.pending() if e["kind"] == "RUN_STARTED"))

    def test_explicit_foreign_parent_is_rejected(self):
        from eap_execution import RunContext
        with self.assertRaises(ObservationError):
            with self.journal.agent("foreign", parent=RunContext("foreign", "invalid", "invalid")):
                self.fail("foreign parent accepted")
        self.assertEqual(self.journal.pending(), [])

    def test_framework_interrupt_remains_incomplete_and_restores_parent(self):
        class FrameworkInterrupt(Exception):
            pass
        with self.journal.agent("parent") as parent:
            with self.assertRaises(FrameworkInterrupt):
                with self.journal.agent("interrupted", incomplete_on=(FrameworkInterrupt,)):
                    raise FrameworkInterrupt("PRIVATE_CONTROL_FLOW")
            self.assertEqual(self.journal.current(), parent)
        events = self.journal.pending()
        child = next(e["runId"] for e in events if e.get("definitionKey") == "interrupted")
        self.assertFalse(any(e["kind"] == "RUN_FINISHED" and e["runId"] == child for e in events))
        self.assertNotIn("PRIVATE_CONTROL_FLOW", json.dumps(events))

    def test_incomplete_policy_rejects_base_exceptions_before_execution(self):
        with self.assertRaises(ValueError):
            with self.journal.agent("invalid", incomplete_on=(BaseException,)):
                self.fail("invalid policy accepted")

    def test_fork_cannot_reuse_parent_journal_or_context(self):
        read_fd, write_fd = os.pipe()
        with self.journal.agent("parent"):
            pid = os.fork()
            if pid == 0:
                os.close(read_fd)
                try:
                    with self.journal.agent("forked"):
                        os.write(write_fd, b"bad")
                except ObservationError:
                    os.write(write_fd, b"blocked")
                finally:
                    os.close(write_fd)
                    os._exit(0)
            os.close(write_fd)
            try:
                self.assertEqual(os.read(read_fd, 64), b"blocked")
                self.assertEqual(os.waitpid(pid, 0)[1], 0)
            finally:
                os.close(read_fd)
        self.assertEqual(len(self.journal.pending()), 2)


if __name__ == "__main__":
    unittest.main()
