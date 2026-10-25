"""Short-lived logical agents and captured code, without process-based inference."""
import asyncio
import fcntl
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest

from eap_execution import CodeSnapshot, Journal


class EphemeralTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "events.sqlite"
        self.journal = Journal(self.path, "eap-agents/ephemeral")

    def tearDown(self):
        self.journal.close()
        self.temp.cleanup()

    def events(self):
        return [json.loads(row[0]) for row in self.journal.db.execute("select event from events order by sequence")]

    async def test_one_hundred_short_lived_agents_keep_distinct_parents_tools_and_code(self):
        async def child(index):
            with self.journal.agent("ephemeral-coder") as coder:
                source = ("result = " + str(index) + "\n").encode()
                snapshot = self.journal.capture_code(source)
                await asyncio.sleep(0)
                with self.journal.agent("ephemeral-executor") as executor:
                    executable = self.journal.bind_code(snapshot)
                    with self.journal.tool("generated-tool", code=executable):
                        namespace = {"__builtins__": {}}
                        exec(compile(executable.data, "<generated>", "exec"), namespace)
                        self.assertEqual(namespace["result"], index)
                self.assertEqual(self.journal.current(), coder)
                return coder.run_id, executor.run_id, snapshot.digest
        with self.journal.agent("coordinator") as root:
            runs = await asyncio.gather(*(child(i) for i in range(100)))
            self.assertEqual(self.journal.current(), root)
        events = self.events()
        self.assertEqual(len(events), 802)
        self.assertEqual({e["processId"] for e in events}, {os.getpid()})
        self.assertEqual({e["traceId"] for e in events}, {root.trace_id})
        self.assertEqual(len({e["eventId"] for e in events}), len(events))
        started = {e["runId"]: e for e in events if e["kind"] == "RUN_STARTED"}
        finished = {e["runId"]: e for e in events if e["kind"] == "RUN_FINISHED"}
        self.assertEqual(set(started), set(finished))
        self.assertEqual(len(started), 201)
        for coder, executor, digest in runs:
            self.assertEqual(started[coder]["parentRunId"], root.run_id)
            self.assertEqual(started[executor]["parentRunId"], coder)
            artifacts = [e for e in events if e["kind"] == "ARTIFACT_REGISTERED" and e["artifactDigest"] == digest]
            self.assertEqual({e["runId"] for e in artifacts}, {coder, executor})
            tool = next(e for e in events if e["kind"] == "TOOL_STARTED" and e["runId"] == executor)
            end = next(e for e in events if e["kind"] == "TOOL_FINISHED" and e["toolCallId"] == tool["toolCallId"])
            self.assertEqual(tool["artifactDigest"], digest)
            self.assertEqual((end["runId"], end["outcome"]), (executor, "SUCCEEDED"))
        original = [e["eventId"] for e in events]
        self.journal.close()
        self.journal = Journal(self.path, "eap-agents/ephemeral", existing_only=True)
        received = []
        class Receiver:
            def send(self, key, body):
                received.extend(body["events"])
                return dict(streamKey="a"*64, received=len(body["events"]), recorded=len(body["events"]), duplicates=0)
        while self.journal.pending(): self.journal.flush(Receiver())
        self.assertEqual([e["eventId"] for e in received], original)
        self.assertNotIn("result =", json.dumps(received))

    async def test_cancelled_tool_and_child_keep_terminal_events_and_restore_parent(self):
        started = asyncio.Event()
        async def child():
            with self.journal.agent("temporary-child"):
                with self.journal.tool("temporary-tool"):
                    started.set()
                    await asyncio.Future()
        with self.journal.agent("parent") as parent:
            task = asyncio.create_task(child())
            await started.wait()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError): await task
            self.assertEqual(self.journal.current(), parent)
        self.assertEqual([e["outcome"] for e in self.events() if e["kind"].endswith("FINISHED")],
                         ["CANCELLED", "CANCELLED", "SUCCEEDED"])

    async def test_deleted_or_replaced_file_cannot_change_bound_execution_bytes(self):
        with self.journal.agent("generator"):
            path = Path(self.temp.name) / "generated.py"
            path.write_bytes(b"result = 42\n")
            snapshot = self.journal.capture_code(path.read_bytes())
            path.write_bytes(b"raise AssertionError('replacement must not execute')\n")
            path.unlink()
            with self.journal.agent("consumer"):
                code = self.journal.bind_code(snapshot)
                with self.journal.tool("generated-tool", code=code):
                    namespace = {"__builtins__": {}}
                    exec(compile(code.data, "<generated>", "exec"), namespace)
                    self.assertEqual(namespace["result"], 42)
        self.assertFalse(path.exists())
        self.assertEqual({e["artifactDigest"] for e in self.events() if "artifactDigest" in e}, {hashlib.sha256(b"result = 42\n").hexdigest()})

    async def test_binding_rejects_unregistered_cross_journal_and_cross_trace_snapshots(self):
        with self.journal.agent("generator", trace_id="1"*32):
            captured = self.journal.capture_code(b"result = 42")
            unknown = CodeSnapshot(captured.digest, captured.language, captured.data)
            with self.assertRaises(ValueError): self.journal.bind_code(unknown)
        with self.journal.agent("other-trace", trace_id="2"*32):
            with self.assertRaises(ValueError): self.journal.bind_code(captured)
        other = Journal(Path(self.temp.name)/"other.sqlite", "eap-agents/ephemeral")
        try:
            with other.agent("other-journal", trace_id="1"*32):
                with self.assertRaises(ValueError): other.bind_code(captured)
        finally: other.close()

    async def test_rebinding_in_one_consumer_deduplicates_artifacts_not_calls(self):
        with self.journal.agent("generator"):
            captured = self.journal.capture_code(b"result = 42")
            with self.journal.agent("consumer"):
                for _ in range(3):
                    with self.journal.tool("generated-tool", code=self.journal.bind_code(captured)): pass
        events = self.events()
        self.assertEqual(len([e for e in events if e["kind"] == "ARTIFACT_REGISTERED"]), 2)
        self.assertEqual(len({e["toolCallId"] for e in events if e["kind"] == "TOOL_STARTED"}), 3)


class JournalConstructionTests(unittest.TestCase):
    def test_failed_initialization_releases_file_lock_and_descriptors(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"broken.sqlite"
            path.write_bytes(b"not a sqlite database"); path.chmod(0o600)
            before = len(list(Path('/proc/self/fd').iterdir()))
            for _ in range(20):
                with self.assertRaises(sqlite3.DatabaseError): Journal(path, "eap-agents/corrupt")
                fd = os.open(path, os.O_RDWR)
                try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                finally: os.close(fd)
            self.assertEqual(len(list(Path('/proc/self/fd').iterdir())), before)
            self.assertEqual(path.read_bytes(), b"not a sqlite database")
