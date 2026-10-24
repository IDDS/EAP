"""Opt-in lifecycle reporting. Metadata only; no code execution or business retries."""
import asyncio
import contextvars
import fcntl
import hashlib
import http.client
import json
import math
import os
import re
import sqlite3
import stat
import threading
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from urllib.parse import urlsplit

_CURRENT = contextvars.ContextVar("eap_execution_context", default=None)
_INHERIT = object()
_KINDS = {"RUN_STARTED", "RUN_FINISHED", "TOOL_STARTED", "TOOL_FINISHED", "ARTIFACT_REGISTERED"}
_EXTRA = {"RUN_STARTED": {"definitionKey", "parentRunId"}, "RUN_FINISHED": {"outcome"},
          "TOOL_STARTED": {"toolCallId", "definitionKey", "artifactDigest"},
          "TOOL_FINISHED": {"toolCallId", "outcome"}, "ARTIFACT_REGISTERED": {"artifactDigest", "sizeBytes", "language"}}


@dataclass(frozen=True)
class RunContext:
    producer_id: str
    run_id: str
    trace_id: str


@dataclass(frozen=True)
class CodeSnapshot:
    """Hash and immutable bytes; callers must execute these bytes to claim this version."""
    digest: str
    language: str
    data: bytes = field(repr=False)

    def __post_init__(self):
        if not isinstance(self.data, bytes) or hashlib.sha256(self.data).hexdigest() != self.digest:
            raise ValueError("code snapshot digest differs")


class ObservationError(RuntimeError):
    pass


class ExportError(ObservationError):
    """Fixed error codes only. Never retain HTTP bodies or credential material."""
    CODES = {"RETRYABLE_NETWORK", "RETRYABLE_SERVER", "RETRYABLE_THROTTLED",
             "BLOCKED_AUTH", "BLOCKED_CONFLICT", "BLOCKED_REQUEST", "BLOCKED_RECEIPT", "BLOCKED_TRANSPORT"}

    def __init__(self, code):
        if code not in self.CODES:
            raise ValueError("invalid export error code")
        self.code = code
        self.retryable = code.startswith("RETRYABLE_")
        super().__init__(code)


def _slug(value, limit):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:/@+-]{1," + str(limit) + "}", value):
        raise ValueError("invalid observation identifier")
    return value


class Journal:
    """One bounded durable stream per file, pinned to a runtime key.

    Reopen only to flush pending events. Use a fresh journal for a new process.
    Credential material is never stored. Parent edges never cross journals.
    """
    def __init__(self, path, runtime_key, *, framework="eap-native", version="0.1.0", capacity=4096, existing_only=False):
        if type(capacity) is not int or not 1 <= capacity <= 4096:
            raise ValueError("invalid journal capacity")
        self.runtime_key = _slug(runtime_key, 128)
        self.framework, self.version = _slug(framework, 64), _slug(version, 32)
        self.capacity = capacity
        self.process_id = os.getpid()
        self.lock = threading.RLock()
        self.flush_lock = threading.RLock()
        self.closed = False
        fd = os.open(path, (0 if existing_only else os.O_CREAT) | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            mode = os.fstat(fd)
            if not stat.S_ISREG(mode.st_mode) or mode.st_uid != os.getuid() or mode.st_mode & 0o077:
                raise ValueError("journal must be a private regular file")
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BaseException:
            os.close(fd)
            raise
        self.fd = fd
        try:
            self.db = sqlite3.connect(path, check_same_thread=False, timeout=5)
            if (os.lstat(path).st_dev, os.lstat(path).st_ino) != (mode.st_dev, mode.st_ino):
                self.db.close()
                raise ValueError("journal path changed")
        except BaseException:
            os.close(fd)
            raise
        if existing_only:
            try:
                if not dict(self.db.execute("select key,value from metadata")):
                    raise ValueError("empty journal")
                self.db.execute("select sequence,event,acknowledged from events limit 1")
            except Exception:
                self.db.close()
                os.close(fd)
                raise ObservationError("existing execution journal unavailable") from None
        self.db.execute("pragma journal_mode=DELETE")
        self.db.execute("pragma synchronous=FULL")
        self.db.execute("create table if not exists metadata (key text primary key, value text not null)")
        self.db.execute("create table if not exists events (sequence integer primary key, event text not null, acknowledged integer not null default 0)")
        meta = dict(self.db.execute("select key,value from metadata"))
        self.reopened = bool(meta)
        if meta:
            if (meta.get("runtimeKey"), meta.get("framework"), meta.get("version")) != (runtime_key, framework, version):
                self.db.close()
                os.close(self.fd)
                raise ValueError("journal identity differs")
            self.producer_id = meta["producerId"]
        else:
            self.producer_id = str(uuid.uuid4())
            self.db.executemany("insert into metadata values(?,?)", {"runtimeKey": runtime_key, "framework": framework,
                "version": version, "producerId": self.producer_id}.items())
            self.db.commit()
        self.artifacts = set()

    def emit(self, kind, context, **extra):
        if kind not in _KINDS or context.producer_id != self.producer_id:
            raise ValueError("observation context differs")
        if set(extra) - _EXTRA[kind] or str(uuid.UUID(context.run_id)) != context.run_id or not re.fullmatch(r"[a-f0-9]{32}", context.trace_id) or context.trace_id == "0" * 32:
            raise ValueError("invalid execution metadata")
        for key in ("toolCallId", "parentRunId"):
            if key in extra and str(uuid.UUID(extra[key])) != extra[key]:
                raise ValueError("invalid execution reference")
        if "definitionKey" in extra:
            _slug(extra["definitionKey"], 64)
        if kind.endswith("FINISHED") and extra.get("outcome") not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            raise ValueError("invalid execution outcome")
        if "artifactDigest" in extra and not re.fullmatch(r"[a-f0-9]{64}", extra["artifactDigest"]):
            raise ValueError("invalid artifact digest")
        if kind == "ARTIFACT_REGISTERED" and (type(extra.get("sizeBytes")) is not int or not 0 <= extra["sizeBytes"] <= 1048576 or extra.get("language") not in {"PYTHON", "JAVASCRIPT", "SHELL", "OTHER"}):
            raise ValueError("invalid artifact metadata")
        with self.lock:
            if self.closed or self.reopened or os.getpid() != self.process_id:
                raise ObservationError("journal cannot accept new events")
            sequence = self.db.execute("select coalesce(max(sequence),0)+1 from events").fetchone()[0]
            if sequence > self.capacity:
                raise ObservationError("journal capacity exhausted")
            event = dict(eventId=str(uuid.uuid4()), sequence=sequence, kind=kind, runId=context.run_id,
                         traceId=context.trace_id, occurredAt=datetime.now(timezone.utc).isoformat(), processId=os.getpid(), **extra)
            raw = json.dumps(event, sort_keys=True, separators=(",", ":"))
            self.db.execute("insert into events(sequence,event) values(?,?)", (sequence, raw))
            self.db.commit()
            return event

    def pending(self, limit=64):
        with self.lock:
            if self.closed or os.getpid() != self.process_id:
                raise ObservationError("journal closed")
            return [json.loads(row[0]) for row in self.db.execute("select event from events where acknowledged=0 order by sequence limit ?", (min(max(limit, 1), 64),))]

    def delivery_status(self):
        """Durable delivery metadata, separate from the business execution outcome."""
        with self.lock:
            if self.closed or os.getpid() != self.process_id:
                raise ObservationError("journal closed")
            total, pending = self.db.execute("select count(*),coalesce(sum(acknowledged=0),0) from events").fetchone()
            row = self.db.execute("select value from metadata where key='delivery'").fetchone()
            status = json.loads(row[0]) if row else {"state": "PENDING", "attempts": 0}
            if not pending:
                status["state"] = "ACKNOWLEDGED" if total else "EMPTY"
            elif status["state"] in {"EMPTY", "ACKNOWLEDGED"}:
                status["state"] = "PENDING"
            stream = self.db.execute("select value from metadata where key='streamKey'").fetchone()
            return dict(status, total=total, pending=pending, acknowledged=total-pending,
                        streamKey=stream[0] if stream else "")

    def _record_delivery(self, state, *, attempt=False, acknowledged=False):
        with self.lock:
            status = self.delivery_status()
            saved = {k:status[k] for k in ("attempts", "lastAttemptAt", "lastAcknowledgedAt") if k in status}
            saved["state"] = state
            now = datetime.now(timezone.utc).isoformat()
            if attempt:
                saved["attempts"] += 1
                saved["lastAttemptAt"] = now
            if acknowledged:
                saved["lastAcknowledgedAt"] = now
            with self.db:
                self.db.execute("insert or replace into metadata values('delivery',?)", (json.dumps(saved),))

    def flush(self, transport):
        """One HTTP attempt per batch. Retry explicitly with the same stored events."""
        return self._flush_batch(transport, None)

    def _flush_batch(self, transport, events):
        with self.flush_lock:
            events = self.pending() if events is None else events
            if not events:
                return 0
            body = dict(schemaVersion=1, producerId=self.producer_id, framework=self.framework,
                        frameworkVersion=self.version, events=events)
            self._record_delivery("SENDING", attempt=True)
            try:
                receipt = transport.send(self.runtime_key, body)
            except Exception as error:
                safe = error if isinstance(error, ExportError) else ExportError("BLOCKED_TRANSPORT")
                self._record_delivery(safe.code)
                raise safe from None
            if (not isinstance(receipt, dict) or set(receipt) != {"streamKey", "received", "recorded", "duplicates"}
                    or not isinstance(receipt["streamKey"], str) or not re.fullmatch(r"[a-f0-9]{64}", receipt["streamKey"])
                    or any(type(receipt[k]) is not int or receipt[k] < 0 for k in ("received", "recorded", "duplicates"))
                    or receipt["received"] != len(events) or receipt["recorded"] + receipt["duplicates"] != len(events)):
                self._record_delivery("BLOCKED_RECEIPT")
                raise ExportError("BLOCKED_RECEIPT")
            with self.lock:
                old = self.db.execute("select value from metadata where key='streamKey'").fetchone()
                if old and old[0] != receipt["streamKey"]:
                    self._record_delivery("BLOCKED_CONFLICT")
                    raise ExportError("BLOCKED_CONFLICT")
                with self.db:
                    self.db.execute("insert or ignore into metadata values('streamKey',?)", (receipt["streamKey"],))
                    self.db.executemany("update events set acknowledged=1 where sequence=?", [(e["sequence"],) for e in events])
                self._record_delivery("ACKNOWLEDGED", acknowledged=True)
            return len(events)

    def deliver(self, transport, *, max_attempts=3, max_batches=1, backoff_seconds=0.25, stop_event=None):
        """Bounded retry of stored events only. No timers or business callbacks survive this call."""
        if (type(max_attempts) is not int or not 1 <= max_attempts <= 8
                or type(max_batches) is not int or not 1 <= max_batches <= 64
                or type(backoff_seconds) not in (int, float) or not math.isfinite(backoff_seconds)
                or not 0 <= backoff_seconds <= 5
                or stop_event is not None and not isinstance(stop_event, threading.Event)):
            raise ValueError("invalid delivery bounds")
        stop = stop_event if stop_event is not None else threading.Event()
        with self.flush_lock:
            for _ in range(max_batches):
                events = self.pending()
                for attempt in range(max_attempts):
                    if not events:
                        return self.delivery_status()
                    if stop.is_set():
                        self._record_delivery("CANCELLED")
                        return self.delivery_status()
                    try:
                        self._flush_batch(transport, events)
                        break
                    except ExportError as error:
                        if not error.retryable:
                            return self.delivery_status()
                        if attempt+1 == max_attempts:
                            self._record_delivery("RETRY_EXHAUSTED")
                            return self.delivery_status()
                        self._record_delivery("RETRY_WAIT")
                        if stop.wait(min(backoff_seconds * 2**attempt, 5)):
                            self._record_delivery("CANCELLED")
                            return self.delivery_status()
            if self.pending():
                self._record_delivery("BATCH_LIMIT")
            return self.delivery_status()

    def close(self):
        with self.flush_lock, self.lock:
            if not self.closed:
                self.closed = True
                self.db.close()
                os.close(self.fd)

    def current(self):
        ctx = self.current_or_none()
        if ctx is None:
            raise ObservationError("no current agent execution in this journal")
        return ctx

    def current_or_none(self):
        ctx = _CURRENT.get()
        if ctx is not None and ctx.producer_id != self.producer_id:
            raise ObservationError("cross-journal delegation is not supported")
        return ctx

    @contextmanager
    def agent(self, definition_key, *, parent=_INHERIT, incomplete_on=(), trace_id=None):
        definition_key = _slug(definition_key, 64)
        if not isinstance(incomplete_on, tuple) or any(not isinstance(t, type) or not issubclass(t, Exception) for t in incomplete_on):
            raise ValueError("invalid incomplete exception policy")
        parent = self.current_or_none() if parent is _INHERIT else parent
        if parent is not None and (not isinstance(parent, RunContext) or parent.producer_id != self.producer_id):
            raise ObservationError("cross-journal delegation is not supported")
        if trace_id is not None and (not isinstance(trace_id, str) or not re.fullmatch(r"[a-f0-9]{32}", trace_id)
                                     or trace_id == "0" * 32 or parent is not None and trace_id != parent.trace_id):
            raise ValueError("invalid or mismatched trace identifier")
        ctx = RunContext(self.producer_id, str(uuid.uuid4()), parent.trace_id if parent else (trace_id or uuid.uuid4().hex))
        self.emit("RUN_STARTED", ctx, definitionKey=definition_key, **({"parentRunId": parent.run_id} if parent else {}))
        token = _CURRENT.set(ctx)
        try:
            yield ctx
        except BaseException as error:
            if isinstance(error, incomplete_on):
                raise  # Framework control flow is neither a successful nor failed completion.
            outcome = "CANCELLED" if isinstance(error, asyncio.CancelledError) else "FAILED"
            try:
                self.emit("RUN_FINISHED", ctx, outcome=outcome)
            except Exception:
                pass  # Preserve business failure; durable START remains incomplete.
            raise
        else:
            self.emit("RUN_FINISHED", ctx, outcome="SUCCEEDED")
        finally:
            _CURRENT.reset(token)

    @contextmanager
    def tool(self, definition_key, *, code=None):
        definition_key = _slug(definition_key, 64)
        ctx = self.current()
        if code is not None and not isinstance(code, CodeSnapshot):
            raise ValueError("invalid code snapshot")
        if code is not None and (ctx.run_id, code.digest) not in self.artifacts:
            raise ObservationError("code snapshot is not registered by this run")
        invocation = str(uuid.uuid4())
        self.emit("TOOL_STARTED", ctx, toolCallId=invocation, definitionKey=definition_key,
                  **({"artifactDigest": code.digest} if code else {}))
        try:
            yield invocation
        except BaseException as error:
            try:
                self.emit("TOOL_FINISHED", ctx, toolCallId=invocation,
                          outcome="CANCELLED" if isinstance(error, asyncio.CancelledError) else "FAILED")
            except Exception:
                pass
            raise
        else:
            self.emit("TOOL_FINISHED", ctx, toolCallId=invocation, outcome="SUCCEEDED")

    def capture_code(self, source, *, language="PYTHON"):
        ctx = self.current()
        if not isinstance(source, bytes) or len(source) > 1048576 or language not in {"PYTHON", "JAVASCRIPT", "SHELL", "OTHER"}:
            raise ValueError("invalid code snapshot")
        digest = hashlib.sha256(source).hexdigest()
        with self.lock:
            if (ctx.run_id, digest) not in self.artifacts:
                self.emit("ARTIFACT_REGISTERED", ctx, artifactDigest=digest, sizeBytes=len(source), language=language)
                self.artifacts.add((ctx.run_id, digest))
        return CodeSnapshot(digest, language, source)


class HttpTransport:
    """Fixed EAP target, RAM-only credential callback, no proxies or redirects."""
    def __init__(self, base_url, credential, *, allow_http=False, timeout=5):
        target = urlsplit(base_url)
        if (target.scheme not in ({"https", "http"} if allow_http else {"https"}) or not target.hostname
                or target.username is not None or target.password is not None or target.path not in {"", "/"}
                or target.query or target.fragment or any(ord(c) < 33 or ord(c) > 126 for c in base_url)
                or not callable(credential) or not 0.1 <= timeout <= 10):
            raise ValueError("invalid execution export configuration")
        self.host, self.port, self.scheme = target.hostname, target.port, target.scheme
        self.credential, self.timeout = credential, timeout

    def send(self, runtime_key, body):
        token = self.credential()
        if not isinstance(token, str) or not re.fullmatch(r"eap_rt_[A-Za-z0-9_-]{43}", token):
            raise ExportError("BLOCKED_AUTH")
        cls = http.client.HTTPSConnection if self.scheme == "https" else http.client.HTTPConnection
        conn = cls(self.host, self.port, timeout=self.timeout)
        try:
            conn.request("POST", "/api/runtime-access/v1/execution-events", json.dumps(body, separators=(",", ":")).encode(),
                         {"Content-Type": "application/json", "X-EAP-Runtime-Key": runtime_key, "X-EAP-Runtime-Token": token})
            response = conn.getresponse()
            if response.status != 200:
                code = ("BLOCKED_AUTH" if response.status in {401, 403} else
                        "BLOCKED_CONFLICT" if response.status == 409 else
                        "RETRYABLE_THROTTLED" if response.status == 429 else
                        "RETRYABLE_SERVER" if 500 <= response.status < 600 else "BLOCKED_REQUEST")
                raise ExportError(code)
            raw = response.read(4097)
            if len(raw) > 4096:
                raise ExportError("BLOCKED_RECEIPT")
            try:
                result = json.loads(raw)
            except (ValueError, UnicodeError):
                raise ExportError("BLOCKED_RECEIPT") from None
            if not isinstance(result, dict) or result.get("success") is not True:
                raise ExportError("BLOCKED_RECEIPT")
            return result.get("data")
        except ObservationError:
            raise
        except (OSError, http.client.HTTPException):
            raise ExportError("RETRYABLE_NETWORK") from None
        except Exception:
            raise ExportError("BLOCKED_TRANSPORT") from None
        finally:
            conn.close()
