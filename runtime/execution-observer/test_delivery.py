"""Delivery faults must never create new events or replay business execution."""
import json
import socket
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from eap_execution import ExportError, HttpTransport, Journal, ObservationError


class Receiver:
    def __init__(self):
        self.payloads = []
    def send(self, key, body):
        self.payloads.append(body)
        n = len(body['events'])
        return dict(streamKey='a'*64, received=n, recorded=n, duplicates=0)


class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)/'events.sqlite'
        self.journal = Journal(self.path, 'eap-agents/delivery')
        with self.journal.agent('once'):
            pass
    def tearDown(self):
        self.journal.close()
        self.temp.cleanup()

    def test_transient_failures_reuse_original_event_ids_and_payload(self):
        receiver = Receiver()
        original = receiver.send
        def send(key, body):
            receipt = original(key, body)
            if len(receiver.payloads) < 3:
                raise ExportError('RETRYABLE_SERVER')
            return receipt
        receiver.send = send
        status = self.journal.deliver(receiver, backoff_seconds=0)
        self.assertEqual((status['state'], status['attempts'], status['pending']), ('ACKNOWLEDGED', 3, 0))
        self.assertEqual(receiver.payloads, [receiver.payloads[0]]*3)
        self.assertEqual(status['total'], 2)

    def test_lost_http_ack_retries_same_events_and_accepts_duplicate_receipt(self):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                requests.append(self.rfile.read(int(self.headers['Content-Length'])))
                if len(requests) == 1:
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                body = json.loads(requests[-1]);n = len(body['events'])
                data = {'success': True, 'data': dict(streamKey='a'*64, received=n, recorded=0, duplicates=n)}
                raw = json.dumps(data).encode();self.send_response(200);self.send_header('Content-Length', str(len(raw)));self.end_headers();self.wfile.write(raw)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True);thread.start()
        try:
            status = self.journal.deliver(HttpTransport('http://127.0.0.1:'+str(server.server_port), lambda:'eap_rt_'+'A'*43, allow_http=True), backoff_seconds=0)
            self.assertEqual((status['state'], status['attempts']), ('ACKNOWLEDGED', 2))
            self.assertEqual(requests, [requests[0]]*2)
            self.assertNotIn(b'eap_rt_', requests[0])
        finally:
            server.shutdown();server.server_close();thread.join(2)

    def test_http_failures_classified_and_permanent_failures_not_retried(self):
        state = {'code':401, 'calls':0}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']));state['calls']+=1
                self.send_response(state['code']);self.end_headers();self.wfile.write(b'PRIVATE_RESPONSE')
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        transport=HttpTransport('http://127.0.0.1:'+str(server.server_port),lambda:'eap_rt_'+'B'*43,allow_http=True)
        try:
            original=self.journal.pending()
            for code,expected,calls in [(401,'BLOCKED_AUTH',1),(403,'BLOCKED_AUTH',1),(409,'BLOCKED_CONFLICT',1),(400,'BLOCKED_REQUEST',1),(413,'BLOCKED_REQUEST',1),(302,'BLOCKED_REQUEST',1),(429,'RETRY_EXHAUSTED',3),(503,'RETRY_EXHAUSTED',3)]:
                with self.subTest(code=code):
                    state.update(code=code,calls=0)
                    report=self.journal.deliver(transport,backoff_seconds=0)
                    self.assertEqual(report['state'],expected)
                    self.assertEqual(state['calls'],calls)
                    self.assertEqual(self.journal.pending(),original)
            self.assertNotIn(b'PRIVATE_RESPONSE',self.path.read_bytes())
        finally:
            server.shutdown();server.server_close();thread.join(2)

    def test_exhausted_delivery_reopens_and_retries_only_original_journal(self):
        class Outage:
            def send(self,*_):raise ExportError('RETRYABLE_NETWORK')
        original=self.journal.pending()
        self.assertEqual(self.journal.deliver(Outage(),backoff_seconds=0)['state'],'RETRY_EXHAUSTED')
        self.journal.close()
        self.journal=Journal(self.path,'eap-agents/delivery',existing_only=True)
        self.assertEqual(self.journal.delivery_status()['attempts'],3)
        self.assertEqual(self.journal.pending(),original)
        with self.assertRaises(ObservationError):
            with self.journal.agent('must-not-reexecute'):self.fail('replay')
        status=self.journal.deliver(Receiver())
        self.assertEqual((status['state'],status['attempts']),('ACKNOWLEDGED',4))

    def test_bounded_batch_reports_all_pending_events_not_only_first_page(self):
        for i in range(49):
            with self.journal.agent('more'):pass
        status=self.journal.deliver(Receiver(),max_batches=1)
        self.assertEqual((status['state'],status['total'],status['pending'],status['acknowledged']),('BATCH_LIMIT',100,36,64))
        status=self.journal.deliver(Receiver())
        self.assertEqual((status['state'],status['pending'],status['attempts']),('ACKNOWLEDGED',0,2))

    def test_cancelled_backoff_leaves_events_and_stops_new_attempts(self):
        stop=threading.Event();calls=[]
        class Outage:
            def send(self,*args):calls.append(args);stop.set();raise ExportError('RETRYABLE_NETWORK')
        original=self.journal.pending()
        report=self.journal.deliver(Outage(),backoff_seconds=5,stop_event=stop)
        self.assertEqual((report['state'],len(calls)),('CANCELLED',1))
        self.assertEqual(self.journal.pending(),original)

    def test_cancel_before_send_has_no_attempt_or_execution_side_effect(self):
        stop=threading.Event();stop.set();receiver=Receiver()
        report=self.journal.deliver(receiver,stop_event=stop)
        self.assertEqual((report['state'],report['attempts'],report['pending']),('CANCELLED',0,2))
        self.assertEqual(receiver.payloads,[])

    def test_concurrent_deliveries_share_single_ack_and_empty_does_not_send(self):
        receiver=Receiver()
        with ThreadPoolExecutor(max_workers=4) as pool:
            reports=list(pool.map(lambda _:self.journal.deliver(receiver),range(4)))
        self.assertEqual(len(receiver.payloads),1)
        self.assertTrue(all(r['state']=='ACKNOWLEDGED' for r in reports))
        self.assertEqual(self.journal.deliver(receiver)['attempts'],1)

    def test_bad_receipt_never_acknowledges_or_loops(self):
        receiver=Receiver();original=receiver.send
        def send(key,body):
            receipt=original(key,body);receipt['received']=True;return receipt
        receiver.send=send
        report=self.journal.deliver(receiver)
        self.assertEqual((report['state'],report['pending'],report['attempts']),('BLOCKED_RECEIPT',2,1))

    def test_unknown_transport_failure_is_sanitized_and_not_retried(self):
        class Broken:
            def send(self,*_):raise RuntimeError('PRIVATE_CREDENTIAL_MARKER')
        report=self.journal.deliver(Broken())
        self.assertEqual((report['state'],report['attempts']),('BLOCKED_TRANSPORT',1))
        self.assertNotIn(b'PRIVATE_CREDENTIAL_MARKER',self.path.read_bytes())

    def test_invalid_bounds_are_rejected_before_any_attempt(self):
        for kw in [{'max_attempts':0},{'max_attempts':True},{'max_attempts':9},{'max_batches':0},{'max_batches':65},{'backoff_seconds':float('nan')},{'backoff_seconds':-1},{'backoff_seconds':6},{'stop_event':object()}]:
            with self.subTest(kw=kw):
                with self.assertRaises(ValueError):self.journal.deliver(Receiver(),**kw)
        self.assertEqual(self.journal.delivery_status()['attempts'],0)

    def test_existing_only_does_not_create_missing_or_initialize_empty_journal(self):
        path=Path(self.temp.name)/'missing.sqlite'
        with self.assertRaises(FileNotFoundError):Journal(path,'eap-agents/delivery',existing_only=True)
        self.assertFalse(path.exists())
        path.touch(mode=0o600)
        with self.assertRaises(ObservationError):Journal(path,'eap-agents/delivery',existing_only=True)
        self.assertEqual(path.read_bytes(),b'')

    def test_missing_credential_blocks_delivery_without_retry(self):
        # Callback errors are terminal; the caller can explicitly invoke again after renewal.
        transport=HttpTransport('http://127.0.0.1:1',lambda:None,allow_http=True)
        status=self.journal.deliver(transport)
        self.assertEqual((status['state'],status['attempts']),('BLOCKED_AUTH',1))
        self.assertEqual(status['pending'],2)

    def test_concurrent_new_events_do_not_change_retry_batch(self):
        receiver=Receiver();send=receiver.send
        def interrupted(key,body):
            receipt=send(key,body)
            if len(receiver.payloads)==1:
                with self.journal.agent('arrived-during-export'):pass
                raise ExportError('RETRYABLE_NETWORK')
            return receipt
        receiver.send=interrupted
        status=self.journal.deliver(receiver,backoff_seconds=0)
        self.assertEqual(receiver.payloads,[receiver.payloads[0]]*2)
        self.assertEqual((status['state'],status['pending'],status['total']),('BATCH_LIMIT',2,4))


if __name__=='__main__':unittest.main()
