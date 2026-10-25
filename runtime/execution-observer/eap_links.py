"""Explicit cross-runtime observations. No remote task execution or authorization transfer."""
from dataclasses import dataclass
import json
import os
import re
import uuid

from eap_execution import ExportError, ObservationError, RunContext


def _uuid(value):
    return isinstance(value, str) and bool(re.fullmatch(r"[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}", value)) and value.replace('-', '') != '0' * 32


@dataclass(frozen=True)
class ExecutionReference:
    stream_key: str
    run_id: str

    def __post_init__(self):
        if not isinstance(self.stream_key, str) or not re.fullmatch(r"[a-f0-9]{64}", self.stream_key) or not _uuid(self.run_id):
            raise ValueError("invalid execution reference")


@dataclass(frozen=True)
class LinkIntent:
    link_id: str
    source: ExecutionReference
    target: ExecutionReference
    relation: str = "DELEGATE"
    artifact_digest: str | None = None

    def __post_init__(self):
        if (not _uuid(self.link_id) or not isinstance(self.source, ExecutionReference) or not isinstance(self.target, ExecutionReference)
                or self.source.stream_key == self.target.stream_key or self.relation not in {"DELEGATE", "CODE_HANDOFF"}
                or (self.relation == "DELEGATE" and self.artifact_digest is not None)
                or (self.relation == "CODE_HANDOFF" and (not isinstance(self.artifact_digest, str) or not re.fullmatch(r"[a-f0-9]{64}", self.artifact_digest)))):
            raise ValueError("invalid execution link")

    def envelope(self):
        """Public observation references only; never credentials or source code."""
        body = dict(linkId=self.link_id, relation=self.relation, sourceStream=self.source.stream_key,
                    sourceRun=self.source.run_id, targetStream=self.target.stream_key, targetRun=self.target.run_id)
        if self.artifact_digest is not None:
            body['artifactDigest'] = self.artifact_digest
        return body

    @classmethod
    def parse(cls, value):
        required = {'linkId', 'relation', 'sourceStream', 'sourceRun', 'targetStream', 'targetRun'}
        if not isinstance(value, dict) or set(value) != (required | {'artifactDigest'} if value.get('relation') == 'CODE_HANDOFF' else required):
            raise ValueError("invalid execution link envelope")
        return cls(value['linkId'], ExecutionReference(value['sourceStream'], value['sourceRun']),
                   ExecutionReference(value['targetStream'], value['targetRun']), value['relation'], value.get('artifactDigest'))


def _events(journal):
    return [(json.loads(raw), acknowledged == 1) for raw, acknowledged in journal.db.execute('select event,acknowledged from events order by sequence')]


def reference(journal, context):
    with journal.lock:
        status = journal.delivery_status()
        if not isinstance(context, RunContext) or context.producer_id != journal.producer_id:
            raise ObservationError("execution context differs")
        if not status['streamKey'] or not any(acked and event['kind'] == 'RUN_STARTED' and event['runId'] == context.run_id for event, acked in _events(journal)):
            raise ObservationError("original run start is not acknowledged")
        return ExecutionReference(status['streamKey'], context.run_id)


class LinkJournal:
    """Bounded outbox in the original private Journal; reopen only to resend original intent."""
    def __init__(self, journal):
        self.journal = journal
        with journal.lock:
            journal.delivery_status()
            journal.db.execute('create table if not exists execution_links (link_id text primary key, body text not null, reported integer not null default 0, receipt_state text, error_code text)')
            journal.db.commit()

    def queue(self, intent, *, side):
        if not isinstance(intent, LinkIntent) or side not in {'SOURCE', 'TARGET'}:
            raise ValueError("invalid execution link side")
        j = self.journal
        body = dict(schemaVersion=1, producerId=j.producer_id, framework=j.framework, frameworkVersion=j.version,
                    side=side, **intent.envelope())
        raw = json.dumps(body, sort_keys=True, separators=(',', ':'))
        with j.lock:
            status = j.delivery_status()
            own = intent.source if side == 'SOURCE' else intent.target
            if status['streamKey'] != own.stream_key:
                raise ObservationError("execution stream differs")
            old = j.db.execute('select body from execution_links where link_id=?', (intent.link_id,)).fetchone()
            if old:
                if old[0] != raw:
                    raise ObservationError("original link intent differs")
                return intent.link_id
            if j.reopened or j.delivery_sealed or os.getpid() != j.process_id:
                raise ObservationError("journal cannot accept new links")
            events = _events(j)
            if not any(acked and e['kind'] == 'RUN_STARTED' and e['runId'] == own.run_id for e, acked in events):
                raise ObservationError("original run start is not acknowledged")
            if intent.artifact_digest is not None and not any(acked and e['kind'] == 'ARTIFACT_REGISTERED' and e['runId'] == own.run_id and e['artifactDigest'] == intent.artifact_digest for e, acked in events):
                raise ObservationError("original code registration is not acknowledged")
            if j.db.execute('select count(*) from execution_links').fetchone()[0] >= 64:
                raise ObservationError("link outbox capacity exhausted")
            with j.db:
                j.db.execute('insert into execution_links(link_id,body) values(?,?)', (intent.link_id, raw))
            return intent.link_id

    def deliver(self, transport, *, limit=16):
        if type(limit) is not int or not 1 <= limit <= 64:
            raise ValueError("invalid link delivery bound")
        j = self.journal
        with j.flush_lock:
            with j.lock:
                j.delivery_status()
                pending = list(j.db.execute('select link_id,body from execution_links where reported=0 order by rowid limit ?', (limit,)))
            for link_id, raw in pending:
                try:
                    body = json.loads(raw)
                    receipt = transport.report_link(j.runtime_key, body)
                    if (not isinstance(receipt, dict) or set(receipt) != {'linkId', 'state', 'duplicate', 'executionPermissionGranted'}
                            or receipt['linkId'] != link_id or receipt['state'] not in {'PENDING_CONFIRMATION', 'CONFIRMED'}
                            or body['side'] == 'TARGET' and receipt['state'] != 'CONFIRMED'
                            or type(receipt['duplicate']) is not bool or receipt['executionPermissionGranted'] is not False):
                        raise ExportError('BLOCKED_RECEIPT')
                    with j.lock, j.db:
                        j.db.execute('update execution_links set reported=1,receipt_state=?,error_code=null where link_id=?', (receipt['state'], link_id))
                except Exception as error:
                    code = error.code if isinstance(error, ExportError) else 'BLOCKED_TRANSPORT'
                    with j.lock, j.db:
                        j.db.execute('update execution_links set error_code=? where link_id=?', (code, link_id))
                    break  # One explicit attempt; never retry business or create a new intent.
            return self.status()

    def status(self):
        with self.journal.lock:
            self.journal.delivery_status()
            rows = list(self.journal.db.execute('select link_id,reported,receipt_state,error_code from execution_links order by rowid'))
            return {'total': len(rows), 'pending': sum(not row[1] for row in rows),
                    'items': [{'linkId': row[0], 'reported': bool(row[1]), 'lastReceiptState': row[2], 'errorCode': row[3]} for row in rows]}
