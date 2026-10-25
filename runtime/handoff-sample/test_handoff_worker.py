import asyncio
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import handoff_worker as worker


class Transport:
    def __init__(self):
        self.events, self.links = {}, {}
        self.drop = False
        self.posts = 0

    def send(self, runtime, body):
        key = hashlib.sha256((runtime+body["producerId"]).encode()).hexdigest()
        if key in self.events:
            raise AssertionError("business events were sent again")
        self.events[key] = body
        return dict(streamKey=key, received=len(body["events"]), recorded=len(body["events"]), duplicates=0)

    def report_delivery(self, runtime, body):
        return dict(streamKey=hashlib.sha256((runtime+body["producerId"]).encode()).hexdigest(), reportSequence=body["reportSequence"], duplicate=False)

    def report_link(self, runtime, body):
        self.posts += 1
        key, side = body["linkId"], body["side"]
        old = self.links.setdefault(key, {})
        duplicate = side in old
        if duplicate and old[side] != body:
            raise AssertionError("intent changed")
        if side == "TARGET" and "SOURCE" not in old:
            raise ValueError("no source offer")
        old[side] = copy.deepcopy(body)
        if self.drop:
            self.drop = False
            raise OSError("private failure text")
        return dict(linkId=key, state="CONFIRMED" if "TARGET" in old else "PENDING_CONFIRMATION", duplicate=duplicate, executionPermissionGranted=False)


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.transport = Transport()
        self.session = str(uuid.uuid4())
        self.source = self.payload("source", "generate", 1)
        self.target = self.payload("target", "execute", 2)

    def payload(self, role, action, sequence):
        return dict(role=role, action=action, session=self.session, taskKey="wf-exec-"+self.session+":"+str(sequence),
                    traceId=uuid.uuid4().hex, runtimeKey="sample/"+role, credential="test-secret-marker",
                    actorUid=str(uuid.uuid4()), identityEpoch=str(uuid.uuid4()), gateway="http://127.0.0.1",
                    directory=str(Path(self.temp.name)/role), numbers=[10, 20, 12] if action == "execute" else None, previous=None)

    def run_phase(self, payload):
        return asyncio.run(worker.process(payload, lambda *args, **kwargs: self.transport))

    def both(self):
        source = self.run_phase(self.source)
        self.target["previous"] = source
        result = self.run_phase(self.target)
        return source, result

    def meta(self, original, action, result):
        return dict(original, action=action, numbers=None, taskKey="wf-exec-"+self.session+":3", previous=result)

    def test_framework_handoff_tracks_exact_bytes_different_streams_and_nested_executor(self):
        source, result = self.both()
        self.assertEqual(result["sum"], 42)
        self.assertEqual(result["count"], 3)
        self.assertNotEqual(source["source"]["streamKey"], result["handoff"]["target"]["streamKey"])
        events = [value["events"] for value in self.transport.events.values()]
        self.assertEqual(sorted(map(len, events)), [5, 9])
        self.assertNotEqual(events[0][0]["traceId"], events[1][0]["traceId"])
        for batch in events:
            self.assertEqual(len({e["processId"] for e in batch}), 1)
        registrations = [e for batch in events for e in batch if e["kind"] == "ARTIFACT_REGISTERED"]
        self.assertEqual(len(registrations), 2)
        self.assertEqual({e["artifactDigest"] for e in registrations}, {hashlib.sha256(worker.program()).hexdigest()})
        pending = self.run_phase(self.meta(self.source, "offer", result))
        self.assertTrue(all(x["state"] == "PENDING_CONFIRMATION" for x in pending["links"]))
        confirmed = self.run_phase(self.meta(self.target, "confirm", result))
        self.assertTrue(all(x["state"] == "CONFIRMED" for x in confirmed["links"]))
        self.assertFalse(confirmed["businessReexecuted"])

    def test_completed_business_replay_returns_original_without_execution_or_new_ids(self):
        source, result = self.both()
        with patch.object(worker, "business", side_effect=AssertionError("reexecuted")):
            self.assertEqual(self.run_phase(self.source), source)
            self.assertEqual(self.run_phase(self.target), result)
            self.assertEqual(self.run_phase(dict(self.target, action="status", numbers=None, traceId="")), result)
        self.assertEqual(len(self.transport.events), 2)

    def test_lost_link_reply_resends_original_intent_without_business(self):
        _, result = self.both()
        offer = self.meta(self.source, "offer", result)
        self.transport.drop = True
        with self.assertRaises(OSError):
            self.run_phase(offer)
        with patch.object(worker, "business", side_effect=AssertionError("reexecuted")):
            replay = self.run_phase(dict(offer, action="resend-source"))
            self.assertTrue(replay["links"][0]["duplicate"])
            self.run_phase(self.meta(self.target, "confirm", result))
            confirmed = self.run_phase(dict(offer, action="resend-source"))
            self.assertTrue(all(x["duplicate"] and x["state"] == "CONFIRMED" for x in confirmed["links"]))
        self.assertEqual(len(self.transport.links), 2)

    def test_uncertain_execution_never_replays(self):
        with patch.object(worker, "business", side_effect=RuntimeError("private failure")) as business:
            with self.assertRaises(RuntimeError): self.run_phase(self.source)
            with self.assertRaises(ValueError): self.run_phase(self.source)
            self.assertEqual(business.call_count, 1)

    def test_changed_binding_task_arguments_and_trace_rejected(self):
        self.both()
        for original, field, value in [(self.source, "actorUid", str(uuid.uuid4())), (self.source, "identityEpoch", str(uuid.uuid4())),
                (self.source, "runtimeKey", "sample/foreign"), (self.target, "numbers", [1, 2, 3]),
                (self.source, "traceId", uuid.uuid4().hex), (self.source, "taskKey", "wf-exec-"+self.session+":99")]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.run_phase(dict(original, **{field: value}))

    def test_arbitrary_code_never_executes(self):
        source = self.run_phase(self.source)
        source["codeBase64"] = worker.base64.b64encode(b"raise SystemExit(0)").decode()
        source["artifactDigest"] = hashlib.sha256(b"raise SystemExit(0)").hexdigest()
        self.target["previous"] = source
        with patch.object(worker, "business", side_effect=AssertionError("executed")), self.assertRaises(ValueError):
            self.run_phase(self.target)

    def test_foreign_source_and_altered_link_rejected_before_send(self):
        _, result = self.both()
        for field in ("source", "link"):
            changed = copy.deepcopy(result)
            if field == "source":
                changed["handoff"]["generated"]["source"]["runId"] = str(uuid.uuid4())
            else:
                changed["handoff"]["links"][1]["targetRun"] = str(uuid.uuid4())
            with self.assertRaises(ValueError): self.run_phase(self.meta(self.source, "offer", changed))
        self.assertEqual(self.transport.posts, 0)

    def test_resend_requires_original_outbox(self):
        _, result = self.both()
        with self.assertRaises(ValueError): self.run_phase(self.meta(self.source, "resend-source", result))
        self.assertEqual(self.transport.posts, 0)

    def test_no_credentials_or_exception_text_persisted(self):
        _, result = self.both()
        self.transport.drop = True
        with self.assertRaises(OSError): self.run_phase(self.meta(self.source, "offer", result))
        for path in Path(self.temp.name).rglob("*"):
            if path.is_file():
                self.assertNotIn(b"test-secret-marker", path.read_bytes())
                self.assertNotIn(b"private failure", path.read_bytes())

    def test_symlink_and_public_state_rejected(self):
        self.run_phase(self.source)
        path = Path(self.source["directory"])/self.session/"state.json"
        path.chmod(0o644)
        with self.assertRaises(ValueError): self.run_phase(self.source)
        path.chmod(0o600)
        saved = path.with_suffix(".saved"); path.rename(saved); path.symlink_to(saved)
        with self.assertRaises(OSError): self.run_phase(self.source)

    def test_strict_json_rejects_duplicate_fields(self):
        with self.assertRaises(ValueError): worker.parse('{"role":"source","role":"target"}')

    def test_invalid_receipt_cannot_grant_execution(self):
        _, result = self.both()
        with patch.object(self.transport, "report_link", return_value=dict(linkId=result["handoff"]["links"][0]["linkId"], state="CONFIRMED", duplicate=False, executionPermissionGranted=True)):
            with self.assertRaises(ValueError): self.run_phase(self.meta(self.source, "offer", result))

    def test_actual_child_processes_exit_before_link_confirmation(self):
        transport = self.transport
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                runtime = self.headers["X-EAP-Runtime-Key"]
                if self.path.endswith("/execution-events"): result = transport.send(runtime, body)
                elif self.path.endswith("/execution-delivery"): result = transport.report_delivery(runtime, body)
                elif self.path.endswith("/execution-links"): result = transport.report_link(runtime, body)
                else: self.send_error(404); return
                self.send_response(200); self.end_headers()
                self.wfile.write(json.dumps({"success": True, "data": result}).encode())
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        def child(payload):
            payload = dict(payload, gateway="http://127.0.0.1:"+str(server.server_port), credential="eap_rt_"+"x"*43)
            result = subprocess.run([sys.executable, str(Path(worker.__file__))], input=json.dumps(payload).encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout.decode())
            self.assertEqual(result.stderr, b"")
            return json.loads(result.stdout)
        self.target["previous"] = child(self.source)
        result = child(self.target)
        pids = {e["processId"] for v in transport.events.values() for e in v["events"]}
        self.assertEqual(len(pids), 2)
        for pid in pids:
            with self.assertRaises(ProcessLookupError): os.kill(int(pid), 0)
        child(self.meta(self.source, "offer", result))
        child(self.meta(self.target, "confirm", result))
        child(self.meta(self.source, "resend-source", result))
        child(self.meta(self.target, "resend-target", result))
        self.assertEqual(sum(len(v["events"]) for v in transport.events.values()), 14)
        self.assertEqual(len(transport.links), 2)


if __name__ == "__main__":
    unittest.main()
