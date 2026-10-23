"""Remote-only synthetic HTTP fixture; no production tools or credentials."""
import contextlib
import http.server
import json
import threading
import time

from http_transport import ObservedHttpClient
from observer import otlp


def request(version="2025-11-25", request_id=1):
    params = {"name": "calculate_sum", "arguments": {"private": "PRIVATE_INPUT"}}
    if version == "2026-07-28":
        params["_meta"] = {"io.modelcontextprotocol/protocolVersion": version}
    return json.dumps({"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                       "params": params}, indent=2).encode()


def result(request_id=1, failed=False):
    return json.dumps({"jsonrpc": "2.0", "id": request_id, "result": {
        "content": [{"type": "text", "text": "PRIVATE_RESULT"}], "isError": failed}},
        indent=2).encode()


def sse(frame, *, ending=b"\n"):
    return b"event: message" + ending + ending.join(
        b"data: " + line for line in frame.split(b"\n")) + ending * 2


@contextlib.contextmanager
def endpoint(body, *, content_type="application/json", status=200,
             chunked=False, content_encoding="", length_delta=0, delay=0):
    calls = []
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def do_POST(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            calls.append({"body": raw, "headers": dict(self.headers), "path": self.path})
            payload = body(raw) if callable(body) else body
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            if content_encoding:
                self.send_header("Content-Encoding", content_encoding)
            self.send_header("Location", "/must-not-follow")
            if chunked:
                self.send_header("Transfer-Encoding", "chunked")
            else:
                self.send_header("Content-Length", str(len(payload) + length_delta))
            self.send_header("Connection", "close")
            self.end_headers()
            time.sleep(delay)
            try:
                if chunked:
                    for offset in range(0, len(payload), 7):
                        part = payload[offset:offset + 7]
                        self.wfile.write(f"{len(part):x}\r\n".encode() + part + b"\r\n")
                        self.wfile.flush()
                    self.wfile.write(b"0\r\n\r\n")
                else:
                    self.wfile.write(payload)
                self.wfile.flush()
            except OSError:
                pass
        def log_message(self, *_):
            pass
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02})
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/mcp", calls
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=1)


def generate():
    rows = []
    scenarios = [
        ("application/json", result(), "SUCCEEDED"),
        ("text/event-stream", sse(result(failed=True)), "FAILED"),
        ("text/event-stream", b": heartbeat\n\nid: PRIVATE_CURSOR\ndata:\n\n", "UNKNOWN"),
    ]
    for content_type, body, expected in scenarios:
        events = []
        with endpoint(body, content_type=content_type, chunked=True) as (url, calls):
            client = ObservedHttpClient(url, server="http_fixture", tools=["calculate_sum"],
                                        emit=events.append, protocol_version="2025-11-25",
                                        allow_http=True)
            with client.post(request(), trace_id="c" * 32, parent_span_id="d" * 16) as response:
                received = bytearray()
                while chunk := response.read(11):
                    received.extend(chunk)
            assert bytes(received) == body and len(calls) == 1
        assert len(events) == 1 and events[0]["status"] == expected
        row = {"event": events[0], "otlp": otlp(events[0])}
        assert "PRIVATE" not in json.dumps(row)
        rows.append(row)
    return rows


if __name__ == "__main__":
    print(json.dumps(generate(), indent=2))
