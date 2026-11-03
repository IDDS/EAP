"""Isolated, loopback-only ticket lookup fixture; not an external EAP connector."""
import http.client
import json
import re
import socket
from datetime import datetime, timezone

MAX_RESPONSE_BYTES = 32768
TICKET_ID = re.compile(r"[A-Z0-9][A-Z0-9_-]{0,63}\Z")
STATES = {"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"}


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


class FixtureTicketLookup:
    """Only connects to a test server on 127.0.0.1; never retries or writes."""

    def __init__(self, port, role="operator", timeout=2.0):
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("invalid fixture port")
        if role not in {"operator", "restricted"} or not 0 < timeout <= 5:
            raise ValueError("invalid fixture settings")
        self._port, self._role, self._timeout = port, role, timeout

    def lookup(self, ticket_id):
        if not isinstance(ticket_id, str) or TICKET_ID.fullmatch(ticket_id) is None:
            raise ValueError("invalid fixture ticket ID")
        observed_at = datetime.now(timezone.utc).isoformat()
        result = {"ticketId": ticket_id, "source": "LOOPBACK_SYNTHETIC_FIXTURE",
                  "observedAt": observed_at, "outcome": "UNAVAILABLE", "ticket": None}
        connection = http.client.HTTPConnection("127.0.0.1", self._port, timeout=self._timeout)
        try:
            connection.request("GET", "/tickets/" + ticket_id,
                               headers={"Accept": "application/json", "X-Fixture-Role": self._role})
            response = connection.getresponse()
            if response.status in (401, 403):
                result["outcome"] = "ACCESS_DENIED"
                return result
            if response.status == 404:
                result["outcome"] = "NOT_VISIBLE"
                return result
            if response.status != 200:
                return result
            if response.headers.get_content_type() != "application/json":
                result["outcome"] = "INVALID_RESPONSE"
                return result
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            # HTTPResponse.read(amt) can return a valid JSON prefix after a
            # premature EOF without raising IncompleteRead for Content-Length.
            if len(raw) > MAX_RESPONSE_BYTES or (response.length is not None and response.length != 0):
                result["outcome"] = "INVALID_RESPONSE"
                return result
            payload = json.loads(raw, object_pairs_hook=_unique_object,
                                 parse_constant=lambda _: (_ for _ in ()).throw(ValueError("invalid number")))
            if not isinstance(payload, dict) or set(payload) != {"complete", "tickets"} or type(payload["complete"]) is not bool or not isinstance(payload["tickets"], list):
                result["outcome"] = "INVALID_RESPONSE"
            elif not payload["complete"] or len(payload["tickets"]) > 1:
                result["outcome"] = "AMBIGUOUS"
            elif not payload["tickets"]:
                result["outcome"] = "NOT_VISIBLE"
            else:
                ticket = payload["tickets"][0]
                if (not isinstance(ticket, dict) or set(ticket) != {"id", "state", "title", "description"}
                        or ticket["id"] != ticket_id or ticket["state"] not in STATES
                        or not isinstance(ticket["title"], str) or not 1 <= len(ticket["title"]) <= 200
                        or not isinstance(ticket["description"], str) or len(ticket["description"]) > 4000):
                    result["outcome"] = "INVALID_RESPONSE"
                else:
                    result["outcome"] = "FOUND"
                    result["ticket"] = {**ticket, "contentTrust": "UNTRUSTED_INPUT"}
        except (OSError, socket.timeout, http.client.HTTPException):
            pass
        except (ValueError, UnicodeError, TypeError, RecursionError):
            result["outcome"] = "INVALID_RESPONSE"
        finally:
            connection.close()
        return result
