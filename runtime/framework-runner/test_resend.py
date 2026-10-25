import json
import os
from pathlib import Path
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

import resend
from eap_background import enroll
from eap_execution import ExportError, Journal
from test_background import Receiver, UID, EPOCH


class ResendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.payload = dict(framework="langgraph", runtimeKey="eap-agents/test", credential="synthetic-credential",
                            gateway="http://127.0.0.1", directory=self.temp.name, actorUid=UID,
                            identityEpoch=EPOCH, expiresAt=int(time.time())+600)

    def tearDown(self): self.temp.cleanup()

    def prepare(self, sealed=True, framework="langgraph", runtime="eap-agents/test"):
        path = Path(self.temp.name)/(str(uuid.uuid4())+".sqlite")
        journal = Journal(path, runtime, framework=framework, version=resend.VERSIONS[framework])
        with journal.agent("coordinator", trace_id="1"*32): pass
        if sealed:
            with patch("eap_background.time.time", return_value=0): enroll(journal, UID, EPOCH)
        journal.close()
        return path

    def run_pass(self, receiver, **changes):
        return resend.execute(dict(self.payload, **changes), lambda *a, **k: receiver)

    def test_successful_pass_and_no_credential_persisted(self):
        path = self.prepare()
        receiver = Receiver()
        self.assertEqual(self.run_pass(receiver)["processed"], 1)
        self.assertEqual(len(receiver.events), 1)
        self.assertEqual(self.run_pass(receiver)["processed"], 0)
        self.assertNotIn(b"synthetic-credential", path.read_bytes())

    def test_legacy_foreign_and_different_epoch_never_send(self):
        self.prepare(sealed=False)
        self.prepare(framework="autogen-core")
        self.prepare(runtime="eap-agents/foreign")
        receiver = Receiver()
        self.assertEqual(self.run_pass(receiver)["processed"], 0)
        self.prepare()
        self.assertEqual(self.run_pass(receiver, identityEpoch=str(uuid.uuid4()))["processed"], 0)
        self.assertEqual(receiver.events, [])

    def test_expired_identity_never_constructs_transport_or_touches_logs(self):
        path = self.prepare()
        before = path.read_bytes()
        with patch.object(resend, "Journal", side_effect=AssertionError("must not open")):
            result = self.run_pass(Receiver(), expiresAt=int(time.time())-1)
        self.assertEqual(result["state"], "IDENTITY_UNAVAILABLE")
        self.assertEqual(path.read_bytes(), before)

    def test_expiry_is_checked_again_before_report(self):
        receiver = Receiver()
        transport = resend.ExpiringTransport(receiver, 200)
        with patch("resend.time.time", return_value=201):
            with self.assertRaises(ExportError): transport.report_delivery("key", {})
            with self.assertRaises(ExportError): transport.send("key", {})
        self.assertEqual(receiver.events+receiver.reports, [])

    def test_busy_journal_waits_for_producer_close(self):
        path = self.prepare()
        journal = Journal(path, self.payload["runtimeKey"], framework="langgraph", version="1.2.17", existing_only=True)
        receiver = Receiver()
        try: self.assertEqual(self.run_pass(receiver)["processed"], 0)
        finally: journal.close()
        self.assertEqual(self.run_pass(receiver)["processed"], 1)

    def test_symlink_and_shared_file_are_skipped(self):
        path = self.prepare()
        original = path.with_suffix(".saved")
        path.rename(original)
        path.symlink_to(original)
        second = self.prepare()
        second.chmod(0o644)
        receiver = Receiver()
        self.assertEqual(self.run_pass(receiver)["processed"], 0)
        self.assertEqual(receiver.events, [])

    def test_symlink_or_shared_directory_rejected(self):
        with tempfile.TemporaryDirectory() as outer:
            link = Path(outer)/"link"
            link.symlink_to(self.temp.name, target_is_directory=True)
            with self.assertRaises(ValueError): self.run_pass(Receiver(), directory=str(link))
        os.chmod(self.temp.name, 0o755)
        with self.assertRaises(ValueError): self.run_pass(Receiver())

    def test_directory_limit_blocks_whole_pass_without_network(self):
        for _ in range(3): self.prepare()
        receiver = Receiver()
        with patch.object(resend, "MAX_FILES", 2):
            self.assertEqual(self.run_pass(receiver)["state"], "DIRECTORY_LIMIT")
        self.assertEqual(receiver.events, [])

    def test_pass_limit_and_completed_logs_do_not_starve_later_logs(self):
        for _ in range(10): self.prepare()
        receiver = Receiver()
        self.assertEqual(self.run_pass(receiver)["processed"], 8)
        self.assertEqual(self.run_pass(receiver)["processed"], 2)
        self.assertEqual(len(receiver.unique), 20)

    def test_missing_directory_is_not_created_and_task_fields_rejected(self):
        missing = str(Path(self.temp.name)/"absent")
        self.assertEqual(self.run_pass(Receiver(), directory=missing)["state"], "NO_JOURNALS")
        self.assertFalse(Path(missing).exists())
        for extra in [{"numbers": [42]}, {"code": "execute"}, {"framework": "arbitrary"}]:
            with self.assertRaises(ValueError): self.run_pass(Receiver(), **extra)

    def test_corrupted_journal_is_visible_without_exposing_error_or_path(self):
        path = Path(self.temp.name)/(str(uuid.uuid4())+".sqlite")
        path.write_bytes(b"private-corrupt-content")
        path.chmod(0o600)
        result = self.run_pass(Receiver())
        self.assertEqual(result, {"state": "PASS_WITH_ISSUES", "processed": 0, "scanned": 1, "issues": 1})
        self.assertNotIn(self.temp.name, json.dumps(result))

    def test_reserved_budget_counts_toward_pass_limit_after_local_failure(self):
        for _ in range(10): self.prepare()
        original = resend.resume
        def interrupted(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError("local failure after delivery")
        receiver = Receiver()
        with patch.object(resend, "resume", interrupted):
            result = self.run_pass(receiver)
        self.assertEqual((result["processed"], result["issues"]), (8, 8))
        self.assertEqual(len(receiver.events), 8)
        self.assertEqual(self.run_pass(receiver)["processed"], 2)
