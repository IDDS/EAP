import json
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from eap_execution import ExportError, HttpTransport, Journal, ObservationError


class Receiver:
    def __init__(self): self.reports = []; self.events = []
    def send(self, key, body):
        self.events.append(body)
        return dict(streamKey='a'*64, received=len(body['events']), recorded=len(body['events']), duplicates=0)
    def report_delivery(self, key, body):
        self.reports.append(body)
        return dict(streamKey='a'*64, reportSequence=body['reportSequence'], duplicate=False)


class StatusReportingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.path = Path(self.temp.name)/'events.sqlite'
        self.journal = Journal(self.path, 'eap-agents/reporting')
        with self.journal.agent('once'): pass
    def tearDown(self): self.journal.close(); self.temp.cleanup()

    def test_report_does_not_flush_events_or_claim_they_were_acknowledged(self):
        receiver = Receiver(); original = self.journal.pending()
        receipt = self.journal.report_status(receiver)
        self.assertEqual(receipt['state'], 'ACKNOWLEDGED'); self.assertEqual(receiver.events, [])
        self.assertEqual(self.journal.pending(), original)
        self.assertEqual(receiver.reports[0]['pending'], 2); self.assertEqual(receiver.reports[0]['attempts'], 0)
        self.assertEqual(self.journal.delivery_status()['state'], 'PENDING')

    def test_report_progress_after_delivery_keeps_original_producer_and_counter(self):
        receiver = Receiver(); self.journal.report_status(receiver); self.journal.deliver(receiver)
        self.journal.report_status(receiver)
        before, after = receiver.reports
        self.assertEqual(before['producerId'], after['producerId']); self.assertEqual(after['reportSequence'], 2)
        self.assertEqual((after['total'], after['acknowledged'], after['pending'], after['attempts']), (2, 2, 0, 1))
        self.assertEqual(after['state'], 'ACKNOWLEDGED')
        self.assertEqual(set(after), {'schemaVersion','producerId','framework','frameworkVersion','reportSequence','total','acknowledged','pending','attempts','state'})

    def test_reopen_reports_original_log_without_executing_or_creating_events(self):
        receiver = Receiver(); self.journal.report_status(receiver); producer = self.journal.producer_id
        self.journal.close(); self.journal = Journal(self.path, 'eap-agents/reporting', existing_only=True)
        self.assertEqual(self.journal.delivery_report_status()['reportSequence'], 1)
        self.journal.report_status(receiver)
        self.assertEqual(receiver.reports[-1]['reportSequence'], 2); self.assertEqual(receiver.reports[-1]['producerId'], producer)
        with self.assertRaises(ObservationError):
            with self.journal.agent('must-not-run'): self.fail('executed')
        self.assertEqual(self.journal.delivery_status()['total'], 2)

    def test_lost_report_response_preserves_sequence_and_leaves_event_delivery_unchanged(self):
        receiver = Receiver(); real = receiver.report_delivery
        def lose(key, body): real(key, body); raise ExportError('RETRYABLE_NETWORK')
        receiver.report_delivery = lose
        result = self.journal.report_status(receiver)
        self.assertEqual(result['state'], 'RETRYABLE_NETWORK'); self.assertEqual(len(receiver.reports), 1)
        self.assertEqual(self.journal.delivery_status()['attempts'], 0)
        receiver.report_delivery = real; self.journal.report_status(receiver)
        self.assertEqual([r['reportSequence'] for r in receiver.reports], [1,2])

    def test_permanent_report_errors_never_loop_or_store_private_error_messages(self):
        for error in [ExportError('BLOCKED_AUTH'), ExportError('BLOCKED_CONFLICT'), RuntimeError('PRIVATE_REPORT_MARKER')]:
            class Broken:
                calls = 0
                def report_delivery(self, *_): self.calls += 1; raise error
            receiver = Broken(); result = self.journal.report_status(receiver)
            self.assertEqual(receiver.calls, 1); self.assertNotEqual(result['state'], 'ACKNOWLEDGED')
            self.assertNotIn('PRIVATE_REPORT_MARKER', self.path.read_bytes().decode(errors='ignore'))
            self.assertEqual(self.journal.delivery_status()['pending'], 2)

    def test_invalid_report_receipts_cannot_change_stream_identity_or_event_state(self):
        receiver = Receiver(); self.journal.deliver(receiver)
        for invalid in [dict(streamKey='a'*64, reportSequence=True, duplicate=False),
                        dict(streamKey='a'*64, reportSequence=999, duplicate=False),
                        dict(streamKey='b'*64, reportSequence=3, duplicate=False),
                        dict(streamKey='a'*64, reportSequence=4, duplicate=False, extra='not allowed')]:
            receiver.report_delivery = lambda *_: invalid
            self.assertNotEqual(self.journal.report_status(receiver)['state'], 'ACKNOWLEDGED')
            self.assertEqual(self.journal.delivery_status()['streamKey'], 'a'*64)
            self.assertEqual(self.journal.delivery_status()['pending'], 0)

    def test_concurrent_reports_are_serialized_and_closed_or_forked_handles_cannot_report(self):
        receiver = Receiver()
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda _: self.journal.report_status(receiver), range(8)))
        self.assertEqual([r['reportSequence'] for r in receiver.reports], list(range(1,9)))
        original = self.journal.process_id; self.journal.process_id = -1
        with self.assertRaises(ObservationError): self.journal.report_status(receiver)
        self.journal.process_id = original; self.journal.close()
        with self.assertRaises(ObservationError): self.journal.report_status(receiver)

    def test_http_reporting_uses_fixed_path_and_ram_only_identity_without_event_payloads(self):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append((self.path, body, self.headers.get('X-EAP-Runtime-Key')))
                data = json.dumps({'success':True, 'data':{'streamKey':'a'*64, 'reportSequence':body['reportSequence'], 'duplicate':False}}).encode()
                self.send_response(200); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler); thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            credential = 'eap_rt_'+'x'*43
            transport = HttpTransport('http://127.0.0.1:'+str(server.server_port),lambda:credential,allow_http=True)
            self.assertEqual(self.journal.report_status(transport)['state'],'ACKNOWLEDGED')
            self.assertEqual(requests[0][0],'/api/runtime-access/v1/execution-delivery')
            self.assertEqual(requests[0][2],'eap-agents/reporting'); self.assertNotIn('events',requests[0][1])
            self.assertNotIn(credential.encode(),self.path.read_bytes())
        finally:server.shutdown();server.server_close();thread.join()


if __name__ == '__main__': unittest.main()
