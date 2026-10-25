import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import uuid

from eap_execution import Journal, ExportError, ObservationError, HttpTransport
from eap_links import ExecutionReference, LinkIntent, LinkJournal, reference


class Transport:
    def __init__(self): self.links = {}; self.calls = []; self.lose = False; self.bad = False
    def send(self, runtime, body):
        return {'streamKey': hashlib.sha256((runtime + body['producerId']).encode()).hexdigest(), 'received': len(body['events']), 'recorded': len(body['events']), 'duplicates': 0}
    def report_link(self, runtime, body):
        self.calls.append(copy.deepcopy(body)); key = (body['linkId'], body['side']); duplicate = key in self.links
        self.links[key] = copy.deepcopy(body)
        if self.lose: raise ExportError('RETRYABLE_NETWORK')
        return {'linkId': body['linkId'], 'state': 'CONFIRMED' if body['side'] == 'TARGET' else 'PENDING_CONFIRMATION',
                'duplicate': duplicate, 'executionPermissionGranted': self.bad}


class LinkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.transport = Transport(); self.journals = []; self.contexts = []; self.snapshots = []
        for i in range(2):
            j = Journal(str(Path(self.tmp.name)/str(i)), 'runtime-'+str(i)); self.journals.append(j); self.addCleanup(j.close)
            with j.agent('temporary-agent', parent=None) as ctx:
                self.contexts.append(ctx); self.snapshots.append(j.capture_code(b'result = 2 + 3\n'))
            j.flush(self.transport)
        self.source, self.target = (reference(j, ctx) for j, ctx in zip(self.journals, self.contexts))
        self.intent = LinkIntent(str(uuid.uuid4()), self.source, self.target)

    def test_different_journals_and_traces_use_explicit_references_not_parent_reassignment(self):
        self.assertNotEqual(self.contexts[0].trace_id, self.contexts[1].trace_id)
        self.assertNotEqual(self.source.stream_key, self.target.stream_key)
        for j, side in zip(self.journals, ['SOURCE', 'TARGET']):
            out = LinkJournal(j); out.queue(self.intent, side=side); result = out.deliver(self.transport)
            self.assertEqual(result['pending'], 0)
            self.assertEqual(result['items'][0]['lastReceiptState'], 'PENDING_CONFIRMATION' if side == 'SOURCE' else 'CONFIRMED')
        self.assertTrue(all(j.delivery_status()['total'] == 3 for j in self.journals))

    def test_code_handoff_uses_same_bytes_captured_independently_by_each_run(self):
        intent = LinkIntent(str(uuid.uuid4()), self.source, self.target, 'CODE_HANDOFF', self.snapshots[0].digest)
        for j, side in zip(self.journals, ['SOURCE', 'TARGET']): LinkJournal(j).queue(intent, side=side)
        payload = intent.envelope(); self.assertNotIn('code', payload); self.assertNotIn('result =', json.dumps(payload))
        self.assertEqual(LinkIntent.parse(payload), intent)

    def test_lost_response_reopens_and_resends_exact_intent_without_new_events(self):
        j = self.journals[0]; out = LinkJournal(j); out.queue(self.intent, side='SOURCE'); self.transport.lose = True
        self.assertEqual(out.deliver(self.transport)['pending'], 1); original = self.transport.calls[-1]; j.close()
        reopened = Journal(str(Path(self.tmp.name)/'0'), 'runtime-0', existing_only=True); self.addCleanup(reopened.close)
        out = LinkJournal(reopened); out.queue(self.intent, side='SOURCE'); self.transport.lose = False
        self.assertEqual(out.deliver(self.transport)['pending'], 0); self.assertEqual(self.transport.calls[-1], original)
        self.assertEqual(reopened.delivery_status()['total'], 3)
        with self.assertRaises(ObservationError): out.queue(LinkIntent(str(uuid.uuid4()), self.source, self.target), side='SOURCE')

    def test_invalid_receipt_cannot_acknowledge_or_grant_permission(self):
        out = LinkJournal(self.journals[0]); out.queue(self.intent, side='SOURCE'); self.transport.bad = True
        result = out.deliver(self.transport)
        self.assertEqual(result['pending'], 1); self.assertEqual(result['items'][0]['errorCode'], 'BLOCKED_RECEIPT')

    def test_target_cannot_accept_pending_source_only_receipt(self):
        out = LinkJournal(self.journals[1]); out.queue(self.intent, side='TARGET')
        class Bad:
            def report_link(_, key, body): return {'linkId': body['linkId'], 'state': 'PENDING_CONFIRMATION', 'duplicate': False, 'executionPermissionGranted': False}
        self.assertEqual(out.deliver(Bad())['items'][0]['errorCode'], 'BLOCKED_RECEIPT')

    def test_changed_tuple_or_wrong_local_stream_is_rejected(self):
        out = LinkJournal(self.journals[0]); out.queue(self.intent, side='SOURCE')
        with self.assertRaises(ObservationError): out.queue(self.intent, side='TARGET')
        changed = LinkIntent(self.intent.link_id, self.source, ExecutionReference(self.target.stream_key, str(uuid.uuid4())))
        with self.assertRaises(ObservationError): out.queue(changed, side='SOURCE')

    def test_missing_code_registration_or_run_never_creates_intent(self):
        out = LinkJournal(self.journals[0])
        with self.assertRaises(ObservationError): out.queue(LinkIntent(str(uuid.uuid4()), self.source, self.target, 'CODE_HANDOFF', 'b'*64), side='SOURCE')
        with self.assertRaises(ObservationError): out.queue(LinkIntent(str(uuid.uuid4()), ExecutionReference(self.source.stream_key, str(uuid.uuid4())), self.target), side='SOURCE')
        self.assertEqual(out.status()['total'], 0)

    def test_only_acknowledged_run_start_may_be_shared(self):
        j = self.journals[0]
        with j.agent('new-run') as ctx:
            with self.assertRaises(ObservationError): reference(j, ctx)
        with self.assertRaises(ObservationError): reference(j, self.contexts[1])

    def test_bounded_outbox_keeps_original_at_capacity(self):
        out = LinkJournal(self.journals[0]); out.queue(self.intent, side='SOURCE')
        for _ in range(63): out.queue(LinkIntent(str(uuid.uuid4()), self.source, self.target), side='SOURCE')
        with self.assertRaises(ObservationError): out.queue(LinkIntent(str(uuid.uuid4()), self.source, self.target), side='SOURCE')
        out.queue(self.intent, side='SOURCE'); self.assertEqual(out.deliver(self.transport, limit=1)['pending'], 63)
        self.assertEqual(len(self.transport.calls), 1)

    def test_foreign_exception_body_is_not_saved(self):
        out = LinkJournal(self.journals[0]); out.queue(self.intent, side='SOURCE')
        class Bad:
            def report_link(_, key, body): raise RuntimeError('SYNTHETIC_PRIVATE_ERROR')
        result = out.deliver(Bad()); self.assertEqual(result['items'][0]['errorCode'], 'BLOCKED_TRANSPORT')
        self.assertNotIn('SYNTHETIC_PRIVATE_ERROR', Path(self.tmp.name, '0').read_bytes().decode(errors='ignore'))

    def test_strict_envelope_rejects_authority_content_and_ambiguous_references(self):
        for field in ['code', 'token', 'agentId', 'artifactDigest']:
            value = self.intent.envelope(); value[field] = None
            with self.subTest(field=field), self.assertRaises(ValueError): LinkIntent.parse(value)
        with self.assertRaises(ValueError): LinkIntent(str(uuid.uuid4()), self.source, self.source)
        with self.assertRaises(ValueError): ExecutionReference('a'*64, '00000000-0000-0000-0000-000000000000')

    def test_http_transport_uses_fixed_observation_route(self):
        class Fixed(HttpTransport):
            def _post(self, path, key, body): return path, key, body
        result = Fixed('https://eap.invalid', lambda: None).report_link('runtime', {'test': True})
        self.assertEqual(result, ('/api/runtime-access/v1/execution-links', 'runtime', {'test': True}))


if __name__ == '__main__': unittest.main()
