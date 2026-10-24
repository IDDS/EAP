import asyncio
import hashlib
import json
import logging
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
from unittest.mock import patch

import worker
from eap_execution import ExportError, Journal

logging.disable(logging.CRITICAL)


class Transport:
    def __init__(self): self.bodies = []
    def send(self, key, body):
        self.bodies.append(body)
        n = len(body["events"])
        return {"streamKey": "a" * 64, "received": n, "recorded": n, "duplicates": 0}


class WorkerTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.payload = {"framework": "langgraph", "numbers": [10, 20, 12], "callId": str(uuid.uuid4()),
                        "traceId": "1" * 32, "runtimeKey": "eap-agents/test", "credential": "test-only-private-marker",
                        "gateway": "http://127.0.0.1", "directory": self.temp.name}
    def tearDown(self): self.temp.cleanup()

    async def scenario(self, framework):
        self.payload["framework"] = framework
        transport = Transport()
        result = await worker.execute(self.payload, lambda *a, **k: transport)
        self.assertEqual(result["sum"], 42)
        self.assertEqual(result["observation"]["pending"], 0)
        self.assertEqual(result["observation"]["events"], 9)
        self.assertEqual(result["observation"]["deliveryState"], "ACKNOWLEDGED")
        self.assertEqual(result["observation"]["deliveryAttempts"], 1)
        events = transport.bodies[0]["events"]
        self.assertEqual({e["processId"] for e in events}, {os.getpid()})
        self.assertEqual({e["traceId"] for e in events}, {self.payload["traceId"]})
        runs = [e for e in events if e["kind"] == "RUN_STARTED"]
        root = next(e for e in runs if e["definitionKey"] == "coordinator")
        self.assertEqual({e["definitionKey"] for e in runs if e.get("parentRunId") == root["runId"]}, {"validator", "temporary-coder"})
        artifact = next(e for e in events if e["kind"] == "ARTIFACT_REGISTERED")
        self.assertEqual(artifact["artifactDigest"], hashlib.sha256(worker.CODE).hexdigest())
        self.assertEqual(artifact["sizeBytes"], len(worker.CODE))
        self.assertNotIn("test-only-private-marker", json.dumps(transport.bodies))
        self.assertNotIn("numbers", json.dumps(transport.bodies))
        return result

    async def test_real_langgraph_parent_child_tool_and_task_trace(self): await self.scenario("langgraph")
    async def test_real_autogen_parent_child_tool_and_task_trace(self): await self.scenario("autogen-core")

    async def test_deleted_temporary_file_keeps_digest_and_successful_execution(self):
        paths = []
        real = tempfile.TemporaryDirectory
        def tracked(*a, **k):
            temp = real(*a, **k); paths.append(temp.name); return temp
        with patch.object(worker.tempfile, "TemporaryDirectory", tracked):
            await self.scenario("langgraph")
        self.assertTrue(paths)
        self.assertTrue(all(not Path(p).exists() for p in paths))

    async def test_export_outage_keeps_success_and_original_events_without_reexecution(self):
        class Outage:
            def send(self, *args): raise RuntimeError("not exported")
        result = await worker.execute(self.payload, lambda *a, **k: Outage())
        self.assertEqual(result["sum"], 42)
        self.assertEqual(result["observation"]["pending"], 9)
        self.assertEqual(result["observation"]["streamKey"], "")
        with patch.object(worker, "temporary_tool", side_effect=AssertionError("must not execute again")):
            with self.assertRaises(FileExistsError): await worker.execute(self.payload)
        journal = Journal(Path(self.temp.name)/(self.payload["callId"]+".sqlite"), self.payload["runtimeKey"], framework="langgraph", version="1.2.17")
        try:
            original = journal.pending()
            self.assertEqual(journal.flush(Transport()), 9)
            self.assertEqual(len(original), 9)
            self.assertEqual(journal.pending(), [])
        finally: journal.close()

    async def test_tool_failure_retains_failed_metadata_without_exporting_arguments(self):
        broken = b"def calculate(numbers):\n    return 1 / 0\n"
        with patch.object(worker, "CODE", broken):
            with self.assertRaises(ZeroDivisionError): await worker.execute(self.payload)
        db = sqlite3.connect(Path(self.temp.name)/(self.payload["callId"]+".sqlite"))
        try: events = [json.loads(row[0]) for row in db.execute("select event from events")]
        finally: db.close()
        self.assertEqual([e["outcome"] for e in events if e["kind"] == "TOOL_FINISHED"], ["FAILED"])
        self.assertNotIn("test-only-private-marker", json.dumps(events))

    async def test_request_cannot_select_code_or_an_unsupported_framework(self):
        for extra in [{"code": "raise RuntimeError()"}, {"framework": "arbitrary"}]:
            with self.assertRaises(ValueError): await worker.execute(dict(self.payload, **extra))
        self.assertEqual(list(Path(self.temp.name).iterdir()), [])

    async def test_argument_limits_are_checked_before_start_or_export(self):
        for numbers in [[], [True], [float("nan")], [1e13], [1] * 101]:
            with self.assertRaises(ValueError): await worker.execute(dict(self.payload, numbers=numbers))
        self.assertEqual(list(Path(self.temp.name).iterdir()), [])

    async def test_existing_journal_prevents_duplicate_execution(self):
        path = Path(self.temp.name)/(self.payload["callId"]+".sqlite")
        path.write_bytes(b"original unknown intent")
        with self.assertRaises(FileExistsError): await worker.execute(self.payload)
        self.assertEqual(path.read_bytes(), b"original unknown intent")

    async def test_transient_delivery_retries_events_without_reexecuting_code(self):
        receiver=Transport();send=receiver.send
        def interrupted(key,body):
            receipt=send(key,body)
            if len(receiver.bodies)<3:raise ExportError("RETRYABLE_NETWORK")
            return receipt
        receiver.send=interrupted
        with patch.object(worker,"temporary_tool",wraps=worker.temporary_tool) as execute:
            result=await worker.execute(self.payload,lambda *a,**k:receiver)
        self.assertEqual(execute.call_count,1)
        self.assertEqual(result["sum"],42)
        self.assertEqual(receiver.bodies,[receiver.bodies[0]]*3)
        self.assertEqual((result["observation"]["deliveryState"],result["observation"]["deliveryAttempts"]),("ACKNOWLEDGED",3))

    async def test_blocked_and_exhausted_exports_keep_success_and_durable_status(self):
        for code,expected,attempts in [("BLOCKED_AUTH","BLOCKED_AUTH",1),("RETRYABLE_SERVER","RETRY_EXHAUSTED",3)]:
            self.payload["callId"]=str(uuid.uuid4())
            class Failure:
                def send(self,*args):raise ExportError(code)
            result=await worker.execute(self.payload,lambda *a,**k:Failure())
            self.assertEqual(result["sum"],42)
            self.assertEqual((result["observation"]["deliveryState"],result["observation"]["deliveryAttempts"],result["observation"]["pending"]),(expected,attempts,9))
            journal=Journal(Path(self.temp.name)/(self.payload["callId"]+".sqlite"),self.payload["runtimeKey"],framework="langgraph",version="1.2.17",existing_only=True)
            try:self.assertEqual(journal.delivery_status()["state"],expected)
            finally:journal.close()
