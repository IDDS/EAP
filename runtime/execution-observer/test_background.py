import json
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch

from eap_background import enroll, resume
from eap_execution import ExportError, Journal, ObservationError

UID = "3fc7abbb-d480-49f7-baf1-e553050e464e"
EPOCH = "d7e2d3fb-88a1-4e51-b780-e149725e9f4e"


class Receiver:
    def __init__(self, event_error=None, report_error=None):
        self.events, self.reports, self.unique = [], [], set()
        self.event_error, self.report_error = event_error, report_error

    def send(self, key, body):
        self.events.append(body)
        previous = len(self.unique)
        self.unique.update(e["eventId"] for e in body["events"])
        if self.event_error:
            raise ExportError(self.event_error)
        n = len(body["events"])
        recorded = len(self.unique)-previous
        return {"streamKey": "a"*64, "received": n, "recorded": recorded, "duplicates": n-recorded}

    def report_delivery(self, key, body):
        self.reports.append(body)
        if self.report_error:
            raise ExportError(self.report_error)
        return {"streamKey": "a"*64, "reportSequence": body["reportSequence"], "duplicate": False}


class BackgroundTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)/"events.sqlite"

    def tearDown(self):
        self.temp.cleanup()

    def journal(self, existing=False):
        return Journal(self.path, "eap-agents/test", existing_only=existing)

    def prepare(self, receiver=None, sealed=True):
        journal = self.journal()
        with journal.agent("coordinator", trace_id="1"*32):
            pass
        if receiver:
            journal.deliver(receiver, max_attempts=1)
            journal.report_status(receiver)
        if sealed:
            with patch("eap_background.time.time", return_value=100):
                enroll(journal, UID, EPOCH)
        original = [row[0] for row in journal.db.execute("select event from events order by sequence")]
        journal.close()
        return original

    def cycle(self, receiver, now=130, uid=UID, epoch=EPOCH):
        journal = self.journal(True)
        try:
            return resume(journal, receiver, uid, epoch, now=now)
        finally:
            journal.close()

    def test_resends_original_events_after_process_reopen(self):
        original = self.prepare(Receiver("RETRYABLE_SERVER"))
        receiver = Receiver()
        self.assertEqual(self.cycle(receiver)["state"], "COMPLETED")
        self.assertEqual(receiver.events[0]["events"], [json.loads(e) for e in original])
        self.assertEqual(self.cycle(receiver, 1000)["state"], "COMPLETED")
        self.assertEqual((len(receiver.events), len(receiver.reports)), (1, 1))

    def test_old_unenrolled_journal_never_upgraded(self):
        self.prepare(sealed=False)
        receiver = Receiver()
        self.assertEqual(self.cycle(receiver)["state"], "NOT_ENROLLED")
        self.assertEqual(receiver.events, [])
        journal = self.journal(True)
        try:
            with self.assertRaises(ValueError): enroll(journal, UID, EPOCH)
        finally: journal.close()

    def test_sealed_producer_cannot_append_events_after_enrollment(self):
        journal = self.journal()
        try:
            with journal.agent("completed", trace_id="1"*32): pass
            enroll(journal, UID, EPOCH)
            with self.assertRaises(ObservationError):
                with journal.agent("late", trace_id="1"*32): pass
            self.assertEqual(journal.delivery_status()["total"], 2)
        finally: journal.close()

    def test_binding_never_overwritten_and_invalid_identity_rejected(self):
        journal = self.journal()
        try:
            with self.assertRaises(ValueError): enroll(journal, "foreign", EPOCH)
            enroll(journal, UID, EPOCH)
            with self.assertRaises(ValueError): enroll(journal, UID, str(uuid.uuid4()))
        finally: journal.close()

    def test_uid_and_epoch_mismatch_never_contact_receiver_or_change_file(self):
        self.prepare()
        for uid, epoch in [(str(uuid.uuid4()), EPOCH), (UID, str(uuid.uuid4()))]:
            receiver = Receiver()
            before = self.path.read_bytes()
            self.assertEqual(self.cycle(receiver, uid=uid, epoch=epoch)["state"], "IDENTITY_MISMATCH")
            self.assertEqual(self.path.read_bytes(), before)
            self.assertEqual(receiver.events+receiver.reports, [])

    def test_cooldown_survives_reopen(self):
        self.prepare()
        receiver = Receiver("RETRYABLE_SERVER")
        self.assertEqual(self.cycle(receiver, 129)["state"], "COOLDOWN")
        first = self.cycle(receiver, 130)
        self.assertEqual((first["cycles"], first["nextAttemptAt"]), (1, 160))
        self.assertEqual(self.cycle(receiver, 159)["state"], "COOLDOWN")
        second = self.cycle(receiver, 160)
        self.assertEqual((second["cycles"], second["nextAttemptAt"]), (2, 220))
        self.assertEqual(len(receiver.events), 2)

    def test_total_budget_survives_restarts_and_only_original_ids_are_sent(self):
        self.prepare()
        receiver = Receiver("RETRYABLE_SERVER")
        for i in range(1, 7):
            state = self.cycle(receiver, i*1000)
            self.assertEqual(state["cycles"], i)
        self.assertEqual(state["state"], "EXHAUSTED")
        self.assertEqual(self.cycle(Receiver(), 10000)["state"], "EXHAUSTED")
        self.assertEqual(len(receiver.events), 6)
        self.assertEqual(len(receiver.unique), 2)
        self.assertTrue(all(x == receiver.events[0] for x in receiver.events))

    def test_budget_reserved_before_request_and_process_crash(self):
        self.prepare()
        class Crash:
            def send(_, *args): raise SystemExit()
        with self.assertRaises(SystemExit): self.cycle(Crash())
        receiver = Receiver()
        self.assertEqual(self.cycle(receiver, 131)["state"], "COOLDOWN")
        self.assertEqual(self.cycle(receiver, 160)["cycles"], 2)

    def test_receipt_lost_after_event_commit_does_not_duplicate_events(self):
        self.prepare()
        receiver = Receiver("RETRYABLE_NETWORK")
        self.assertEqual(self.cycle(receiver)["state"], "WAITING")
        receiver.event_error = None
        self.assertEqual(self.cycle(receiver, 160)["state"], "COMPLETED")
        self.assertEqual(len(receiver.unique), 2)
        self.assertEqual(receiver.events[0], receiver.events[1])

    def test_report_only_failure_never_resends_acknowledged_events(self):
        receiver = Receiver(report_error="RETRYABLE_NETWORK")
        self.prepare(receiver)
        receiver.report_error = None
        self.assertEqual(self.cycle(receiver)["state"], "COMPLETED")
        self.assertEqual(len(receiver.events), 1)
        self.assertEqual([r["reportSequence"] for r in receiver.reports], [1, 2])

    def test_report_error_after_background_event_ack_retries_only_report(self):
        self.prepare()
        receiver = Receiver(report_error="RETRYABLE_SERVER")
        self.assertEqual(self.cycle(receiver)["state"], "WAITING")
        receiver.report_error = None
        self.assertEqual(self.cycle(receiver, 160)["state"], "COMPLETED")
        self.assertEqual(len(receiver.events), 1)

    def test_permanent_foreground_errors_prevent_any_automatic_network(self):
        for code in ["BLOCKED_AUTH", "BLOCKED_CONFLICT", "BLOCKED_REQUEST", "BLOCKED_RECEIPT", "BLOCKED_TRANSPORT"]:
            with self.subTest(code=code):
                self.path = Path(self.temp.name)/(code+".sqlite")
                self.prepare(Receiver(code))
                receiver = Receiver()
                self.assertEqual(self.cycle(receiver)["state"], "BLOCKED")
                self.assertEqual(receiver.events+receiver.reports, [])

    def test_permanent_background_failure_stops_without_reporting_again(self):
        self.prepare()
        receiver = Receiver("BLOCKED_AUTH")
        self.assertEqual(self.cycle(receiver)["state"], "BLOCKED")
        self.assertEqual(receiver.reports, [])
        self.assertEqual(self.cycle(Receiver(), 500)["state"], "BLOCKED")

    def test_permanent_report_failure_stops_even_if_events_are_confirmed(self):
        self.prepare()
        receiver = Receiver(report_error="BLOCKED_RECEIPT")
        self.assertEqual(self.cycle(receiver)["state"], "BLOCKED")
        self.assertEqual(self.cycle(Receiver(), 500)["state"], "BLOCKED")

    def test_successful_foreground_needs_no_background_calls(self):
        self.prepare(Receiver())
        receiver = Receiver()
        self.assertEqual(self.cycle(receiver)["state"], "COMPLETED")
        self.assertEqual(receiver.events+receiver.reports, [])

    def test_explicit_delivery_cancellation_is_preserved(self):
        self.prepare()
        journal = self.journal(True)
        journal._record_delivery("CANCELLED")
        journal.close()
        receiver = Receiver()
        self.assertEqual(self.cycle(receiver)["state"], "CANCELLED")
        self.assertEqual(receiver.events, [])

    def test_corrupted_budget_fails_closed(self):
        self.prepare()
        journal = self.journal(True)
        try:
            with journal.db:
                journal.db.execute("update metadata set value=? where key='backgroundDelivery'", (json.dumps({"cycles": -1}),))
        finally: journal.close()
        receiver = Receiver()
        with self.assertRaises(ValueError): self.cycle(receiver)
        self.assertEqual(receiver.events+receiver.reports, [])
