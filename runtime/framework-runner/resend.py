"""Private Runner subprocess: resend sealed telemetry, never import the framework worker."""
import json
import logging
import os
from pathlib import Path
import stat
import sys
import time
import uuid

from eap_background import identity, resume
from eap_execution import ExportError, HttpTransport, Journal

VERSIONS = {"langgraph": "1.2.17", "autogen-core": "0.7.5"}
MAX_FILES = 4096
MAX_CYCLES_PER_PASS = 8


def private_directory(directory):
    path = Path(directory)
    mode = path.lstat()
    if not path.is_absolute() or not stat.S_ISDIR(mode.st_mode) or mode.st_uid != os.getuid() or mode.st_mode & 0o077:
        raise ValueError("private observation directory required")
    return path


class ExpiringTransport:
    def __init__(self, delegate, expires_at):
        self.delegate, self.expires_at = delegate, expires_at

    def _check(self):
        if time.time() >= self.expires_at:
            raise ExportError("BLOCKED_AUTH")

    def send(self, *args):
        self._check()
        return self.delegate.send(*args)

    def report_delivery(self, *args):
        self._check()
        return self.delegate.report_delivery(*args)


def execute(payload, transport_factory=HttpTransport):
    required = {"framework", "runtimeKey", "credential", "gateway", "directory", "actorUid", "identityEpoch", "expiresAt"}
    if set(payload) != required or payload["framework"] not in VERSIONS:
        raise ValueError("invalid resend request")
    identity(payload["actorUid"], payload["identityEpoch"])
    if type(payload["expiresAt"]) is not int or payload["expiresAt"] <= time.time():
        return {"state": "IDENTITY_UNAVAILABLE", "processed": 0, "scanned": 0, "issues": 0}
    try:
        directory = private_directory(payload["directory"])
    except FileNotFoundError:
        return {"state": "NO_JOURNALS", "processed": 0, "scanned": 0, "issues": 0}
    names = []
    with os.scandir(directory) as entries:
        for entry in entries:
            names.append(entry.name)
            if len(names) > MAX_FILES:
                return {"state": "DIRECTORY_LIMIT", "processed": 0, "scanned": len(names), "issues": 0}
    delegate = transport_factory(payload["gateway"], lambda: payload["credential"], allow_http=True, timeout=5)
    transport = ExpiringTransport(delegate, payload["expiresAt"])
    processed = scanned = issues = 0
    for name in sorted(names):
        if processed >= MAX_CYCLES_PER_PASS or time.time() >= payload["expiresAt"]:
            break
        if not name.endswith(".sqlite"):
            continue
        scanned += 1
        try:
            if str(uuid.UUID(name[:-7])) != name[:-7]:
                continue
            # No create, symlinks, shared permissions, concurrent writer, or foreign runtime/framework.
            journal = Journal(directory/name, payload["runtimeKey"], framework=payload["framework"],
                              version=VERSIONS[payload["framework"]], existing_only=True)
            try:
                before = journal.db.execute("select value from metadata where key='backgroundDelivery'").fetchone()
                before_cycles = json.loads(before[0]).get("cycles") if before else None
                try:
                    resume(journal, transport, payload["actorUid"], payload["identityEpoch"])
                finally:
                    # Even a later local exception consumes a durably reserved cycle.
                    after = journal.db.execute("select value from metadata where key='backgroundDelivery'").fetchone()
                    after_cycles = json.loads(after[0]).get("cycles") if after else None
                    processed += int(type(after_cycles) is int and after_cycles != before_cycles)
            finally:
                journal.close()
        except BlockingIOError:
            continue  # A live producer owns the flock; retry inspection on a later pass.
        except Exception:
            # Busy, malformed, unbound and replaced files do not enable execution or disclose input.
            issues += 1
            continue
    return {"state": "PASS_WITH_ISSUES" if issues else "PASS_FINISHED", "processed": processed,
            "scanned": scanned, "issues": issues}


def main():
    logging.disable(logging.CRITICAL)
    try:
        raw = sys.stdin.buffer.read(16385)
        if len(raw) > 16384:
            raise ValueError("request too large")
        sys.stdout.write(json.dumps(execute(json.loads(raw)), allow_nan=False)+"\n")
    except Exception:
        sys.stdout.write('{"error":"DELIVERY_PASS_UNCONFIRMED"}\n')
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
