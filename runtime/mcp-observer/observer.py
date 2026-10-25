"""Metadata-only MCP stdio observation, with no tool execution or request replay.

The relay preserves wire bytes. JSON-RPC identifiers, arguments and results stay
in memory; OTLP contains configured identity, allowlisted tool names and outcome.
"""
from __future__ import annotations

import json
import re
import secrets
import threading
import time
from collections import OrderedDict


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON field")
        value[key] = item
    return value


class McpObserver:
    """One instance per stdio process/session; never shared across MCP servers."""

    def __init__(self, server_name, allowed_tools, emit, *, trace_id=None,
                 parent_span_id=None, max_pending=128, max_frame=65536,
                 now_ns=time.time_ns, monotonic=time.monotonic):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", server_name):
            raise ValueError("invalid configured MCP server name")
        if not 1 <= max_pending <= 1024 or not 1024 <= max_frame <= 1048576:
            raise ValueError("invalid observation bounds")
        if any(not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", n) for n in allowed_tools):
            raise ValueError("invalid configured tool name")
        if trace_id is not None and (not re.fullmatch(r"[a-f0-9]{32}", trace_id) or int(trace_id, 16) == 0):
            raise ValueError("invalid trusted trace")
        if parent_span_id is not None and (trace_id is None or not re.fullmatch(r"[a-f0-9]{16}", parent_span_id) or int(parent_span_id, 16) == 0):
            raise ValueError("invalid trusted parent")
        self.server_name = server_name
        self.allowed_tools = frozenset(allowed_tools)
        self.emit = emit
        self.trace_id, self.parent_span_id = trace_id, parent_span_id
        self.max_pending, self.max_frame = max_pending, max_frame
        self.now_ns, self.monotonic = now_ns, monotonic
        self.pending = OrderedDict()
        self.seen = set()
        self.buffers = {"client": bytearray(), "server": bytearray()}
        self.skipping = {"client": False, "server": False}
        self.lock = threading.Lock()
        self.closed = False
        self.degraded = False
        self.dropped_frames = 0
        self.delivery_failures = 0

    @staticmethod
    def _key(value):
        # bool is an int in Python; JSON-RPC identifiers preserve their types.
        if isinstance(value, bool) or value is None:
            return None
        if isinstance(value, str) and len(value) <= 256:
            return ("string", value)
        if isinstance(value, int) and abs(value) <= 9007199254740991:
            return ("number", value)
        return None

    @staticmethod
    def _content(item):
        if not isinstance(item, dict):
            return False
        kind = item.get("type")
        if kind == "text":
            return isinstance(item.get("text"), str)
        if kind in {"image", "audio"}:
            return isinstance(item.get("data"), str) and isinstance(item.get("mimeType"), str)
        if kind == "resource_link":
            return isinstance(item.get("uri"), str) and isinstance(item.get("name"), str)
        if kind == "resource":
            resource = item.get("resource")
            return (isinstance(resource, dict) and isinstance(resource.get("uri"), str)
                    and (isinstance(resource.get("text"), str) != isinstance(resource.get("blob"), str)))
        return False

    def feed(self, direction, chunk):
        if direction not in self.buffers:
            raise ValueError("invalid direction")
        # Observation failure is contained by relay; no chunk is ever modified.
        with self.lock:
            if self.closed:
                return
            for part in chunk.splitlines(keepends=True):
                # Only LF delimits MCP stdio. splitlines also recognizes CR;
                # retain it until LF rather than inventing an RPC boundary.
                ended = part.endswith(b"\n")
                if not self.skipping[direction]:
                    self.buffers[direction].extend(part)
                    if len(self.buffers[direction]) > self.max_frame:
                        self.buffers[direction].clear()
                        self.skipping[direction] = True
                        self._lose_correlation("FRAME_TOO_LARGE")
                if ended:
                    if not self.skipping[direction]:
                        self._frame(direction, bytes(self.buffers[direction]))
                    self.buffers[direction].clear()
                    self.skipping[direction] = False

    def message(self, direction, frame):
        """Observe one externally delimited message (for example HTTP/SSE).

        This does not treat embedded newlines as stdio message boundaries.
        The transport remains responsible for bounding and completing a frame.
        """
        if direction not in self.buffers:
            raise ValueError("invalid direction")
        with self.lock:
            if self.closed:
                return
            if len(frame) > self.max_frame:
                self._lose_correlation("FRAME_TOO_LARGE")
            else:
                self._frame(direction, frame)

    def invalidate(self):
        """A transport could not establish a valid protocol response."""
        with self.lock:
            if not self.closed and not self.degraded:
                self._lose_correlation("INVALID_RESPONSE")

    def _lose_correlation(self, reason):
        self.dropped_frames += 1
        # After unparseable data, matching a later reused ID is unsafe. Keep
        # forwarding but stop semantic correlation for this process lifetime.
        self.degraded = True
        for key in list(self.pending):
            self._finish(key, "UNKNOWN", reason)

    def _frame(self, direction, frame):
        if self.degraded:
            return
        try:
            obj = json.loads(frame.decode("utf-8"), object_pairs_hook=_unique_object,
                             parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        except (ValueError, UnicodeError, RecursionError):
            self._lose_correlation("INVALID_FRAME")
            return
        if not isinstance(obj, dict) or obj.get("jsonrpc") != "2.0":
            self._lose_correlation("UNSUPPORTED_FRAME")
            return
        method = obj.get("method")
        if direction == "client":
            if method == "notifications/cancelled":
                params = obj.get("params")
                key = self._key(params.get("requestId")) if isinstance(params, dict) else None
                if key in self.pending:
                    self._finish(key, "UNKNOWN", "CANCEL_REQUESTED")
                # Cancellation is not evidence of no effect. Reuse of the ID
                # could make a late result look like another call's response.
                self._lose_correlation("CANCEL_BREAKS_CORRELATION")
                return
            key = self._key(obj.get("id"))
            if method is not None and key is not None:
                if key in self.seen or len(self.seen) >= 4096:
                    self._lose_correlation("AMBIGUOUS_OR_EXHAUSTED_REQUEST_IDS")
                    return
                self.seen.add(key)
            if method != "tools/call":
                return
            key = self._key(obj.get("id"))
            params = obj.get("params")
            if (key is None or not isinstance(params, dict) or not isinstance(params.get("name"), str)
                    or "arguments" in params and not isinstance(params["arguments"], dict)):
                self._lose_correlation("INVALID_TOOL_REQUEST")
                return
            if key in self.pending:
                self._lose_correlation("AMBIGUOUS_REQUEST_ID")
                return
            if len(self.pending) >= self.max_pending:
                self._lose_correlation("PENDING_LIMIT")
                return
            tool = params["name"] if params["name"] in self.allowed_tools else "unregistered_tool"
            self.pending[key] = {"name": tool, "start": self.now_ns(), "clock": self.monotonic(),
                                 "spanId": secrets.token_hex(8),
                                 "traceId": self.trace_id or secrets.token_hex(16)}
            return
        if method is not None:
            return  # Server-initiated requests/progress are not tool results.
        key = self._key(obj.get("id"))
        if key not in self.pending:
            return
        if ("error" in obj) == ("result" in obj):
            self._finish(key, "UNKNOWN", "INVALID_RESPONSE")
            return
        if "error" in obj:
            error = obj["error"]
            valid = (isinstance(error, dict) and isinstance(error.get("code"), int)
                     and not isinstance(error["code"], bool) and isinstance(error.get("message"), str))
            self._finish(key, "FAILED" if valid else "UNKNOWN", "RPC_ERROR" if valid else "INVALID_RESPONSE")
            return
        result = obj["result"]
        valid = isinstance(result, dict) and isinstance(result.get("content"), list)
        valid = valid and all(self._content(item) for item in result["content"])
        valid = valid and ("isError" not in result or isinstance(result["isError"], bool))
        valid = valid and ("structuredContent" not in result or isinstance(result["structuredContent"], dict))
        if not valid or "task" in result:
            self._finish(key, "UNKNOWN", "INVALID_RESPONSE")
        else:
            self._finish(key, "FAILED" if result.get("isError") else "SUCCEEDED",
                         "TOOL_ERROR" if result.get("isError") else "TOOL_RESULT")

    def _finish(self, key, state, reason):
        call = self.pending.pop(key)
        duration = max(0, self.monotonic() - call["clock"])
        event = {"server": self.server_name, "tool": call["name"], "traceId": call["traceId"],
                 "spanId": call["spanId"], "startTimeUnixNano": str(call["start"]),
                 "endTimeUnixNano": str(call["start"] + int(duration * 1e9)),
                 "status": state, "reason": reason,
                 "evidence": "PROTOCOL_RESPONSE" if state != "UNKNOWN" else "NO_CONFIRMED_RESPONSE",
                 "effectVerified": False, "taskCorrelation": "CONFIGURED" if self.trace_id else "UNAVAILABLE"}
        if self.parent_span_id:
            event["parentSpanId"] = self.parent_span_id
        try:
            self.emit(event)
        except Exception:
            self.delivery_failures += 1  # Observation delivery never replays tools.

    def close(self):
        with self.lock:
            if self.closed:
                return
            for key in list(self.pending):
                self._finish(key, "UNKNOWN", "STREAM_CLOSED")
            self.closed = True


def otlp(event, agent_id=None):
    """Requires EAP MCP_PROTOCOL_V1 ingestion; never send to a legacy collector."""
    attrs = {"mcp.server.name": event["server"], "tool.name": event["tool"], "rpc.method": "tools/call",
             "eap.observation.contract": "MCP_PROTOCOL_V1",
             "eap.observation.outcome": event["status"], "eap.observation.reason": event["reason"],
             "eap.observation.evidence": event["evidence"], "eap.observation.effect": "UNVERIFIED",
             "eap.observation.task_correlation": event["taskCorrelation"]}
    transport = "http" if event.get("transport") == "STREAMABLE_HTTP" else "stdio"
    resource = {"service.name": "eap-mcp-" + transport + "-observer", "service.version": "0.1.0"}
    if agent_id is not None:
        if not re.fullmatch(r"[1-9][0-9]{0,18}", str(agent_id)):
            raise ValueError("invalid configured agent")
        resource["eap.agent.id"] = str(agent_id)
    span = {"traceId": event["traceId"], "spanId": event["spanId"], "name": "mcp tools/call", "kind": 3,
            "startTimeUnixNano": event["startTimeUnixNano"], "endTimeUnixNano": event["endTimeUnixNano"],
            "attributes": [{"key": k, "value": {"stringValue": v}} for k, v in attrs.items()],
            "status": {"code": {"SUCCEEDED": 1, "FAILED": 2, "UNKNOWN": 0}[event["status"]]}}
    if "parentSpanId" in event:
        span["parentSpanId"] = event["parentSpanId"]
    return {"resourceSpans": [{"resource": {"attributes": [{"key": k, "value": {"stringValue": v}} for k, v in resource.items()]},
                               "scopeSpans": [{"scope": {"name": "eap.mcp.protocol"}, "spans": [span]}]}]}
