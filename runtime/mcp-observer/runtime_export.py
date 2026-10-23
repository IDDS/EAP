"""Instance-scoped, best-effort export. No credential files or business retries."""
import http.client
import json
import os
import queue
import re
import select
import socket
import stat
import threading
import time
from urllib.parse import urlsplit

from observer import _unique_object, otlp


class RuntimeLease:
    """Read bounded lease updates from a trusted parent's anonymous pipe.

    The parent must keep the pipe open. EOF/invalid input invalidates the lease.
    An instance is pinned for this relay lifetime; rotation cannot reattribute
    already queued observations. This is a local delivery channel, not proof
    that EAP issued the credential; EAP remains the authentication authority.
    """

    def __init__(self, fd):
        if fd < 3 or not stat.S_ISFIFO(os.fstat(fd).st_mode):
            raise ValueError("runtime credentials require an inherited pipe")
        self.fd = os.dup(fd)
        os.close(fd)
        os.set_inheritable(self.fd, False)
        self.lock = threading.Lock()
        self.ready = threading.Event()
        self.stop = threading.Event()
        self.current = None
        self.identity = None
        self.sequence = 0
        self.invalid = 0
        self.closed = False
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _accept(self, raw):
        item = json.loads(raw, object_pairs_hook=_unique_object)
        if not isinstance(item, dict) or set(item) != {
            "instanceKey", "agentId", "actorUid", "token", "expiresAt", "sequence"
        }:
            raise ValueError("invalid lease")
        if (not isinstance(item["instanceKey"], str)
                or not re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,62}/[a-z0-9][a-z0-9.-]{0,62}", item["instanceKey"])
                or not isinstance(item["agentId"], str)
                or not re.fullmatch(r"[1-9][0-9]{0,18}", item["agentId"])
                or not isinstance(item["actorUid"], str)
                or not re.fullmatch(r"[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}", item["actorUid"])
                or not isinstance(item["token"], str)
                or not re.fullmatch(r"eap_rt_[A-Za-z0-9_-]{43}", item["token"])
                or type(item["sequence"]) is not int or not 1 <= item["sequence"] <= 2**53
                or type(item["expiresAt"]) is not int
                or not 0 < item["expiresAt"] <= time.time() + 86460):
            raise ValueError("invalid lease")
        identity = (item["instanceKey"], item["agentId"], item["actorUid"])
        with self.lock:
            if self.identity is not None and self.identity != identity:
                raise ValueError("instance changed")
            if item["sequence"] <= self.sequence:
                self.invalid += 1
                return
            self.identity = identity
            self.sequence = item["sequence"]
            self.current = item
            self.ready.set()

    def _read(self):
        pending = bytearray()
        try:
            while not self.stop.is_set():
                if not select.select([self.fd], [], [], 0.1)[0]:
                    continue
                chunk = os.read(self.fd, 2048)
                if not chunk:
                    break
                pending.extend(chunk)
                while b"\n" in pending:
                    line, _, rest = pending.partition(b"\n")
                    pending = bytearray(rest)
                    if len(line) > 2048:
                        raise ValueError("lease too large")
                    self._accept(line)
                if len(pending) > 2048:
                    raise ValueError("lease too large")
        except Exception:
            with self.lock:
                self.invalid += 1
        finally:
            with self.lock:
                self.current = None
                self.closed = True
            self.ready.set()
            os.close(self.fd)

    def snapshot(self):
        with self.lock:
            if self.current is None or self.current["expiresAt"] <= time.time():
                return None
            return dict(self.current)

    def reject(self, lease):
        with self.lock:
            # An old request's 401 must not revoke a newer local delivery.
            if self.current and self.current["sequence"] == lease["sequence"]:
                self.current = None

    def close(self):
        self.stop.set()
        self.thread.join(timeout=0.3)


class RuntimeExporter:
    """A bounded memory queue; each event gets at most one HTTP attempt.

    Success means an explicit EAP ingestion receipt, never a tool effect.
    Timeouts/malformed receipts are unknown. All failures leave MCP wire alone.
    """

    def __init__(self, base_url, lease, *, allow_http=False, timeout=1.0, capacity=64):
        target = urlsplit(base_url)
        if (target.scheme not in ({"https", "http"} if allow_http else {"https"})
                or not target.hostname or target.username is not None or target.password is not None
                or target.query or target.fragment or target.path not in {"", "/"}
                or any(ord(c) < 33 or ord(c) > 126 for c in base_url)
                or not 0.1 <= timeout <= 5 or not 1 <= capacity <= 256):
            raise ValueError("invalid runtime export configuration")
        # Validate the port before spawning a business subprocess.
        self.host, self.port, self.scheme = target.hostname, target.port, target.scheme
        self.lease, self.timeout = lease, timeout
        self.queue = queue.Queue(maxsize=capacity)
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.connection = None
        self.inflight = False
        self.closed = False
        self.counts = dict(submitted=0, accepted=0, rejected=0, unknown=0,
                           unavailable=0, queueDropped=0, shutdownDropped=0)
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def submit(self, event):
        # Generate only the observer's metadata contract; no arbitrary OTLP input.
        body = json.dumps(otlp(event), separators=(",", ":")).encode()
        if len(body) > 8192:
            raise ValueError("observation too large")
        with self.lock:
            self.counts["submitted"] += 1
            if self.closed:
                self.counts["shutdownDropped"] += 1
                return
            try:
                self.queue.put_nowait(body)
            except queue.Full:
                self.counts["queueDropped"] += 1

    def _send(self, body, lease):
        cls = http.client.HTTPSConnection if self.scheme == "https" else http.client.HTTPConnection
        conn = cls(self.host, self.port, timeout=self.timeout)
        with self.lock:
            if self.closed:
                return "unknown"
            self.connection = conn
        try:
            conn.request("POST", "/api/runtime-access/v1/traces", body, {
                "Content-Type": "application/json",
                "X-EAP-Runtime-Key": lease["instanceKey"],
                "X-EAP-Runtime-Token": lease["token"],
            })
            response = conn.getresponse()
            if response.status in {401, 403}:
                self.lease.reject(lease)
                return "rejected"
            # No redirects, retries, environment proxies or error body logging.
            if 400 <= response.status < 500:
                return "rejected"
            if response.status != 200:
                return "unknown"
            raw = response.read(8193)
            if len(raw) > 8192:
                return "unknown"
            receipt = json.loads(raw, object_pairs_hook=_unique_object)
            if not isinstance(receipt, dict) or receipt.get("success") is not True:
                return "unknown"
            data = receipt.get("data")
            expected = {"spansScanned": 1, "traceSpansRecorded": 1, "mcpSpansRecorded": 1,
                        "genAiSpansDetected": 0, "invocationsRecorded": 0}
            if (not isinstance(data, dict)
                    or any(type(data.get(k)) is not int or data[k] != v for k, v in expected.items())
                    or data.get("warnings") != []):
                return "unknown"
            return "accepted"
        except Exception:
            return "unknown"
        finally:
            conn.close()
            with self.lock:
                self.connection = None

    def _run(self):
        while not self.stop.is_set():
            with self.lock:
                if self.closed:
                    return
                try:
                    body = self.queue.get_nowait()
                    self.inflight = True
                except queue.Empty:
                    body = None
            if body is None:
                self.stop.wait(0.01)
                continue
            lease = self.lease.snapshot()
            outcome = "unavailable" if lease is None else self._send(body, lease)
            with self.lock:
                if self.inflight:
                    self.counts[outcome] += 1
                    self.inflight = False

    def close(self, grace=2.0):
        deadline = time.monotonic() + grace
        while time.monotonic() < deadline:
            with self.lock:
                done = self.queue.empty() and not self.inflight
            if done:
                break
            time.sleep(0.01)
        with self.lock:
            self.closed = True
            self.stop.set()
            if self.inflight:
                self.counts["unknown"] += 1
                self.inflight = False
            while True:
                try:
                    self.queue.get_nowait()
                    self.counts["shutdownDropped"] += 1
                except queue.Empty:
                    break
            conn = self.connection
        if conn and conn.sock:
            try:
                conn.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.thread.join(timeout=0.1)
        self.lease.close()
        with self.lock:
            return {**self.counts, "invalidLeaseUpdates": self.lease.invalid,
                    "workerStopped": not self.thread.is_alive()}
