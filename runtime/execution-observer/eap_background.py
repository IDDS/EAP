"""Bounded resend of explicitly enrolled journals. No business execution callbacks."""
import json
import math
import time
import uuid

MAX_CYCLES = 6
TERMINAL = {"COMPLETED", "EXHAUSTED", "BLOCKED", "CANCELLED"}


def identity(actor_uid, epoch):
    for value in (actor_uid, epoch):
        if not isinstance(value, str) or str(uuid.UUID(value)) != value:
            raise ValueError("invalid delivery identity")
    return {"actorUid": actor_uid, "identityEpoch": epoch}


def _save(journal, state):
    with journal.lock, journal.db:
        journal.db.execute("insert or replace into metadata values('backgroundDelivery',?)", (json.dumps(state),))


def enroll(journal, actor_uid, epoch):
    """Seal only a fresh, completed producer; never enroll an old or uncertain journal.

    The caller supplies the identity captured BEFORE business execution. Credentials
    are excluded. A crash before enrollment deliberately leaves manual-only telemetry.
    """
    binding = identity(actor_uid, epoch)
    with journal.flush_lock, journal.lock:
        status = journal.delivery_status()
        if journal.reopened or journal.db.execute("select 1 from metadata where key='backgroundDelivery'").fetchone():
            raise ValueError("journal already sealed or reopened")
        state = dict(schemaVersion=1, **binding, cycles=0, nextAttemptAt=time.time()+30, state="WAITING")
        report = journal.delivery_report_status()
        if status["state"].startswith("BLOCKED_") or report["state"].startswith("BLOCKED_"):
            state["state"] = "BLOCKED"
        elif status["state"] == "CANCELLED":
            state["state"] = "CANCELLED"
        elif not status["pending"] and report["state"] == "ACKNOWLEDGED":
            state["state"] = "COMPLETED"
        _save(journal, state)
        journal.delivery_sealed = True
        return state


def resume(journal, transport, actor_uid, epoch, *, now=None):
    """At most one event batch and one report. Reserve the durable cycle before I/O.

    Reopening the process cannot reset the budget. A lost response reuses the original
    events; report-only recovery never resends already acknowledged events.
    """
    binding = identity(actor_uid, epoch)
    now = time.time() if now is None else now
    if type(now) not in (int, float) or not math.isfinite(now) or now < 0:
        raise ValueError("invalid delivery time")
    with journal.flush_lock, journal.lock:
        status = journal.delivery_status()
        row = journal.db.execute("select value from metadata where key='backgroundDelivery'").fetchone()
        if not journal.reopened or not row:
            return {"state": "NOT_ENROLLED"}
        state = json.loads(row[0])
        if (set(state) != {"schemaVersion", "actorUid", "identityEpoch", "cycles", "nextAttemptAt", "state"}
                or state["schemaVersion"] != 1 or type(state["cycles"]) is not int or not 0 <= state["cycles"] <= MAX_CYCLES
                or type(state["nextAttemptAt"]) not in (int, float) or not math.isfinite(state["nextAttemptAt"])
                or state["state"] not in TERMINAL | {"WAITING", "SENDING"}):
            raise ValueError("invalid background delivery state")
        if any(state[k] != v for k, v in binding.items()):
            return {"state": "IDENTITY_MISMATCH"}
        if state["state"] in TERMINAL:
            return state
        # Explicit cancellation or a permanent foreground/manual failure also stops enrollment.
        report = journal.delivery_report_status()
        if status["state"].startswith("BLOCKED_") or report["state"].startswith("BLOCKED_"):
            state["state"] = "BLOCKED"
        elif status["state"] == "CANCELLED":
            state["state"] = "CANCELLED"
        elif state["cycles"] >= MAX_CYCLES:
            state["state"] = "EXHAUSTED"
        elif now < state["nextAttemptAt"]:
            return dict(state, state="COOLDOWN")
        else:
            state.update(cycles=state["cycles"]+1, state="SENDING")
            state["nextAttemptAt"] = now+min(30*2**(state["cycles"]-1), 300)
            _save(journal, state)
            # The caller's identity-bound transport checks expiry before EACH HTTP request.
            delivery = journal.deliver(transport, max_attempts=1, max_batches=1)
            if delivery["state"].startswith("BLOCKED_"):
                state["state"] = "BLOCKED"
            elif delivery["state"] == "CANCELLED":
                state["state"] = "CANCELLED"
            else:
                report = journal.report_status(transport)
                if report["state"].startswith("BLOCKED_"):
                    state["state"] = "BLOCKED"
                elif not delivery["pending"] and report["state"] == "ACKNOWLEDGED":
                    state["state"] = "COMPLETED"
                else:
                    state["state"] = "EXHAUSTED" if state["cycles"] == MAX_CYCLES else "WAITING"
        _save(journal, state)
        return state
