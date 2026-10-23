"""Opt-in HTTP host adapter, not a proxy, SDK replacement or TLS interception.

Trusted host configuration fixes the endpoint and names. Each call owns its
socket and observation state. Callers own MCP negotiation/session lifecycle.
Response bytes/status/headers remain available unchanged; no request retries,
redirects, GET resumption, environment proxies or credential discovery occur.
"""
from __future__ import annotations

import http.client
import re
from urllib.parse import urlsplit

from http_observer import HttpExchangeObserver


class ObservedHttpClient:
    def __init__(self, endpoint, *, server, tools, emit, protocol_version,
                 allow_http=False, timeout=5.0, max_frame=65536):
        target = urlsplit(endpoint)
        if (target.scheme not in ({"https", "http"} if allow_http else {"https"})
                or not target.hostname or target.username is not None
                or target.password is not None or target.query or target.fragment
                or any(ord(c) < 33 or ord(c) > 126 for c in endpoint)
                or not 0.1 <= timeout <= 30):
            raise ValueError("invalid trusted MCP endpoint configuration")
        self.host, self.port, self.scheme = target.hostname, target.port, target.scheme
        self.path = target.path or "/"
        self.server, self.tools, self.emit = server, tuple(tools), emit
        self.version, self.timeout, self.max_frame = protocol_version, timeout, max_frame
        # Validate all observation configuration before any connection is made.
        HttpExchangeObserver(server, self.tools, emit, protocol_version=protocol_version,
                             max_frame=max_frame)

    def post(self, body, *, headers=None, trace_id=None, parent_span_id=None):
        """Send exactly one raw UTF-8 JSON body; return a streaming context manager.

        headers may carry the MCP server's Authorization/session values in RAM.
        EAP runtime credentials must only go to RuntimeExporter, never here.
        No headers are persisted or copied into observations.
        """
        if not isinstance(body, bytes):
            raise ValueError("HTTP body must be bytes")
        prepared = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": self.version,
        }
        seen = set()
        reserved = {"host", "content-length", "transfer-encoding", "connection",
                    "proxy-authorization", "proxy-connection", "trailer", "te", "upgrade",
                    "content-type", "accept", "mcp-protocol-version", "accept-encoding",
                    "content-encoding", "expect"}
        for name, value in (headers or {}).items():
            if (not isinstance(name, str) or not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name)
                    or name.lower() in reserved or name.lower().startswith("x-eap-")
                    or name.lower() in seen or not isinstance(value, str)
                    or any(ord(c) < 32 or ord(c) > 126 for c in value)):
                raise ValueError("invalid MCP request headers")
            seen.add(name.lower())
            prepared[name] = value
        observer = HttpExchangeObserver(
            self.server, self.tools, self.emit, protocol_version=self.version,
            trace_id=trace_id, parent_span_id=parent_span_id, max_frame=self.max_frame)
        observer.request(body)
        cls = http.client.HTTPSConnection if self.scheme == "https" else http.client.HTTPConnection
        conn = cls(self.host, self.port, timeout=self.timeout)
        try:
            # http.client does not consume environment proxies or follow redirects.
            conn.request("POST", self.path, body, prepared)
            response = conn.getresponse()
            observer.response(response.status, response.getheader("Content-Type", ""),
                              response.getheader("Content-Encoding", ""))
            return ObservedHttpResponse(conn, response, observer)
        except BaseException:
            observer.finish()
            conn.close()
            raise


class ObservedHttpResponse:
    def __init__(self, connection, response, observer):
        self.connection, self.response, self.observer = connection, response, observer
        self.status = response.status
        self.headers = tuple(response.getheaders())
        self.closed = False

    def read(self, size=65536):
        if type(size) is not int or not 1 <= size <= 65536:
            raise ValueError("read size must be between 1 and 65536")
        if self.closed:
            return b""
        try:
            chunk = self.response.read1(size)
            self.observer.feed(chunk)
            # Python 3.10 read1() can return b"" at length == 0 without
            # closing fp. Completion must not depend on isclosed() alone.
            if not chunk or self.response.length == 0 or self.response.isclosed():
                # A short Content-Length body must not turn into a valid JSON
                # success merely because the prefix happened to parse.
                complete = self.response.length in {None, 0}
                self.observer.finish(complete=complete)
                self.close()
            return chunk
        except BaseException:
            self.close()
            raise

    def close(self):
        if not self.closed:
            self.closed = True
            self.observer.finish()
            self.response.close()
            self.connection.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
