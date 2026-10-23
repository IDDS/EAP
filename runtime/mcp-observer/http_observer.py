"""Bounded, request-scoped MCP JSON/SSE observation; never executes or retries.

Only complete final tool results are classified. HTTP errors, unsupported
encodings, async results, MRTR and interrupted streams cannot prove an effect.
No HTTP header, URL, SSE cursor, argument or result is emitted.
"""
from __future__ import annotations

import json

from observer import McpObserver, _unique_object


class HttpExchangeObserver:
    """One POST exchange, one trusted task association, at most one tool event.

    Call request() before sending bytes, response() after receiving headers,
    feed() for each response chunk, then finish(complete=...) exactly once.
    Hosts must serialize these calls within one exchange; exchanges are isolated.
    """

    def __init__(self, server, tools, emit, *, protocol_version,
                 trace_id=None, parent_span_id=None, max_frame=65536):
        if protocol_version not in {"2025-11-25", "2026-07-28"}:
            raise ValueError("unsupported observation protocol version")
        def output(event):
            emit({**event, "transport": "STREAMABLE_HTTP"})
        self.core = McpObserver(server, tools, output, trace_id=trace_id,
                                parent_span_id=parent_span_id,
                                max_pending=1, max_frame=max_frame)
        self.protocol_version = protocol_version
        self.max_frame = max_frame
        self.started = self.headers_received = self.closed = False
        self.kind = None
        self.body = bytearray()
        self.line = bytearray()
        self.data = bytearray()
        self.event_type = b""
        self.first_line = True
        self.after_cr = False

    def request(self, body):
        if self.started or self.closed:
            raise ValueError("exchange request already observed")
        self.started = True
        self.core.message("client", body)
        # Modern metadata is checked only for the version used by this adapter;
        # negotiation, capabilities and MRTR remain the client's responsibility.
        if self.protocol_version == "2026-07-28" and self.core.pending:
            try:
                obj = json.loads(body.decode("utf-8"), object_pairs_hook=_unique_object)
                meta = obj["params"].get("_meta", {})
                if not isinstance(meta, dict) or meta.get("io.modelcontextprotocol/protocolVersion") != self.protocol_version:
                    self.core.invalidate()
            except (ValueError, UnicodeError, KeyError, TypeError, RecursionError):
                self.core.invalidate()

    def response(self, status, content_type, content_encoding=""):
        if not self.started or self.headers_received or self.closed:
            raise ValueError("invalid exchange response order")
        self.headers_received = True
        media = content_type.split(";", 1)[0].strip().lower()
        if status != 200 or content_encoding.strip().lower() not in {"", "identity"}:
            self.core.invalidate()
        elif media == "application/json":
            self.kind = "json"
        elif media == "text/event-stream":
            self.kind = "sse"
        else:
            self.core.invalidate()

    def feed(self, chunk):
        if not self.headers_received or self.closed:
            raise ValueError("invalid exchange body order")
        if self.core.degraded:
            return
        if self.kind == "json":
            if len(self.body) + len(chunk) > self.max_frame:
                self.core.invalidate()
                self.body.clear()
            else:
                self.body.extend(chunk)
        elif self.kind == "sse":
            # Byte-level CR/LF framing is safe for UTF-8. Keep at most one bounded
            # line and one bounded event, even for comment/unknown-field floods.
            for value in chunk:
                if self.core.degraded:
                    self.line.clear()
                    self.data.clear()
                    return
                if self.after_cr:
                    self.after_cr = False
                    if value == 10:
                        continue
                if value in {10, 13}:
                    self._line(bytes(self.line))
                    self.line.clear()
                    self.after_cr = value == 13
                elif len(self.line) >= self.max_frame:
                    self.core.invalidate()
                else:
                    self.line.append(value)

    def _line(self, line):
        if self.first_line:
            self.first_line = False
            line = line.removeprefix(b"\xef\xbb\xbf")
        # Invalid UTF-8 is conservatively unknown; it cannot become a confirmed
        # success through replacement-character decoding.
        try:
            line.decode("utf-8")
        except UnicodeError:
            self.core.invalidate()
            return
        if not line:
            if self.data:
                payload = bytes(self.data[:-1])
                if payload:
                    if self.event_type in {b"", b"message"}:
                        self._message(payload)
                    else:
                        self.core.invalidate()
            self.data.clear()
            self.event_type = b""
            return
        if line.startswith(b":"):
            return
        name, _, value = line.partition(b":")
        if value.startswith(b" "):
            value = value[1:]
        if name == b"data":
            if len(self.data) + len(value) + 1 > self.max_frame:
                self.core.invalidate()
            else:
                self.data.extend(value + b"\n")
        elif name == b"event":
            self.event_type = value
        # id/retry are deliberately neither retained nor acted upon. This tap
        # does not resume streams or manufacture a second tool execution.

    def _message(self, frame):
        try:
            value = json.loads(frame.decode("utf-8"), object_pairs_hook=_unique_object)
            result = value.get("result") if isinstance(value, dict) else None
            if isinstance(result, dict) and ("inputRequests" in result or "task" in result):
                self.core.invalidate()
                return
        except (ValueError, UnicodeError, RecursionError):
            self.core.invalidate()
            return
        self.core.message("server", frame)

    def finish(self, *, complete=False):
        if self.closed:
            return
        if complete and self.kind == "json" and not self.core.degraded:
            self._message(bytes(self.body))
        # SSE dispatch requires a blank line. EOF never dispatches an incomplete
        # event, and an HTTP success status alone never establishes tool success.
        self.core.close()
        self.body.clear()
        self.line.clear()
        self.data.clear()
        self.event_type = b""
        self.closed = True

