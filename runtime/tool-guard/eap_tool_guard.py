"""Standard-library client for the scoped EAP tool gate. No automatic tool retries."""
from __future__ import annotations

import http.client
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable


class GuardUnavailable(RuntimeError):
    """An API response is unconfirmed. Never infer that a tool is safe to replay."""


class ToolStopped(RuntimeError):
    def __init__(self, state: str):
        self.state = state
        super().__init__("EAP stopped the tool: " + state)


@dataclass(frozen=True)
class ToolResult:
    value: Any
    call_id: str
    receipt_confirmed: bool


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class ToolGuard:
    def __init__(self, base_url: str, instance_key: str, credential: str,
                 *, allow_insecure_http: bool = False, timeout: float = 10):
        parsed = urllib.parse.urlsplit(base_url)
        allowed = {"https", "http"} if allow_insecure_http else {"https"}
        if parsed.scheme not in allowed or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("Invalid EAP base URL; HTTP requires explicit opt-in")
        self._base = base_url.rstrip("/")
        self._instance = instance_key
        self._credential = credential
        self._timeout = timeout
        self._opener = urllib.request.build_opener(_NoRedirect())

    def _post(self, action: str, payload: dict) -> dict:
        request = urllib.request.Request(
            self._base + "/api/runtime-access/v1/tools/" + action,
            data=json.dumps(payload, ensure_ascii=False, allow_nan=False).encode(),
            headers={"Content-Type": "application/json", "X-EAP-Runtime-Key": self._instance,
                     "X-EAP-Runtime-Token": self._credential}, method="POST")
        try:
            with self._opener.open(request, timeout=self._timeout) as response:
                body = response.read(65537)
                if len(body) > 65536:
                    raise GuardUnavailable("EAP response exceeded the limit")
                result = json.loads(body)
                if not isinstance(result.get("data"), dict):
                    raise GuardUnavailable("Invalid EAP response")
                return result["data"]
        except urllib.error.HTTPError as error:
            # Do not copy upstream bodies, URLs or credentials into exceptions/logs.
            raise GuardUnavailable("EAP rejected tool request (HTTP %d)" % error.code) from None
        except (OSError, http.client.HTTPException, ValueError, KeyError, TypeError, AttributeError):
            raise GuardUnavailable("EAP response unconfirmed; do not retry the tool") from None

    def execute(self, *, call_id: str, tool_name: str, arguments: dict,
                trace_id: str, invoke: Callable[[dict], Any], tool_kind: str = "TOOL") -> ToolResult:
        """Run a caller-owned tool once, only after a successful one-time claim.

        Persist call_id with the caller's task before calling. Reusing it never reruns
        the callback; changing it represents a new business operation. ASK/BLOCK,
        failed checks and uncertain claims always stop before invoke.
        """
        # Freeze the exact JSON arguments checked by EAP, independent of caller mutation.
        snapshot = json.loads(json.dumps(arguments, ensure_ascii=False, allow_nan=False))
        checked = self._post("check", {"callId": call_id, "toolName": tool_name, "toolKind": tool_kind,
                                       "traceId": trace_id, "arguments": snapshot})
        if checked.get("state") != "APPROVED":
            raise ToolStopped(str(checked.get("state", "UNKNOWN")))
        claim = self._post("claim", {"callId": call_id})
        if claim.get("execute") is not True:
            raise ToolStopped("NOT_CLAIMED_OR_ALREADY_CONSUMED")
        started = time.monotonic()
        try:
            value = invoke(snapshot)
        except Exception:
            try:
                self._post("receipt", {"callId": call_id, "outcome": "FAILED",
                                      "durationMs": min(86400000, int((time.monotonic() - started) * 1000))})
            except GuardUnavailable:
                pass
            raise
        try:
            digest = hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False).encode()).hexdigest()
        except (TypeError, ValueError):
            digest = None
        confirmed = True
        try:
            self._post("receipt", {"callId": call_id, "outcome": "SUCCEEDED", "outputDigest": digest,
                                  "durationMs": min(86400000, int((time.monotonic() - started) * 1000))})
        except GuardUnavailable:
            confirmed = False
        return ToolResult(value, call_id, confirmed)
