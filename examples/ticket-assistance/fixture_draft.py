"""Build a human-review draft from one synthetic lookup, without executing actions."""
from datetime import datetime

from fixture_lookup import STATES, TICKET_ID

SOURCE = "LOOPBACK_SYNTHETIC_FIXTURE"
UNCONFIRMED = {"NOT_VISIBLE", "ACCESS_DENIED", "AMBIGUOUS", "INVALID_RESPONSE", "UNAVAILABLE"}

SUGGESTIONS = {
    "OPEN": "人工核对工单影响和现有记录后，决定是否接手处理。",
    "IN_PROGRESS": "人工核对负责人和当前处理记录，决定是否补充跟进。",
    "RESOLVED": "人工核对解决记录及业务确认情况，再决定是否需要后续处理。",
    "CLOSED": "人工核对关闭记录和后续需求，再决定是否需要进一步处理。",
}


def draft_from_lookup(result):
    """Use fixed suggestions; evidence remains data and no external action is available."""
    if not isinstance(result, dict) or set(result) != {"ticketId", "source", "observedAt", "outcome", "ticket"}:
        raise ValueError("invalid lookup record")
    ticket_id, source, observed_at, outcome = (result[k] for k in ("ticketId", "source", "observedAt", "outcome"))
    if (not isinstance(ticket_id, str) or TICKET_ID.fullmatch(ticket_id) is None or source != SOURCE
            or not isinstance(observed_at, str) or len(observed_at) > 40):
        raise ValueError("invalid lookup provenance")
    try:
        timestamp = datetime.fromisoformat(observed_at)
    except ValueError as exc:
        raise ValueError("invalid lookup time") from exc
    if timestamp.tzinfo is None or timestamp.utcoffset() is None:
        raise ValueError("lookup time requires timezone")
    if not isinstance(outcome, str) or outcome not in UNCONFIRMED | {"FOUND"}:
        raise ValueError("invalid lookup outcome")
    draft = {"status": "LOOKUP_UNCONFIRMED", "ticketId": ticket_id, "source": source,
             "observedAt": observed_at, "lookupOutcome": outcome, "evidence": None,
             "suggestions": [], "humanReviewRequired": True, "actionsExecuted": False}
    ticket = result["ticket"]
    if outcome != "FOUND":
        if ticket is not None:
            raise ValueError("unconfirmed lookup cannot contain a ticket")
        return draft
    if (not isinstance(ticket, dict) or set(ticket) != {"id", "state", "title", "description", "contentTrust"}
            or ticket["id"] != ticket_id or not isinstance(ticket["state"], str) or ticket["state"] not in STATES
            or not isinstance(ticket["title"], str) or not 1 <= len(ticket["title"]) <= 200
            or not isinstance(ticket["description"], str) or len(ticket["description"]) > 4000
            or ticket["contentTrust"] != "UNTRUSTED_INPUT"):
        raise ValueError("invalid ticket evidence")
    draft["status"] = "DRAFT_FOR_REVIEW"
    draft["evidence"] = dict(ticket)
    draft["suggestions"] = [SUGGESTIONS[ticket["state"]]]
    return draft
