"""Loopback-only synthetic MCP server for the dedicated EAP acceptance image.

No production tool, model, outbound HTTP, credential file or general execution.
The separate trusted host observes real HTTP responses from this subprocess.
"""
import time
_PROCESS_START = time.monotonic_ns()

import http.server
import json
import os
import stat
import socket
import sys

from observer import _unique_object


_IMPORT_DONE = time.monotonic_ns()


def create_server(handler):
    """Measure the existing constructor, including its resolver call.

    This dedicated process has no serving threads yet. Restore the function
    even on failure; do not change resolution, bind semantics or timeouts.
    """
    original = socket.getfqdn
    fqdn_ns = 0

    def timed_fqdn(*args, **kwargs):
        nonlocal fqdn_ns
        mark = time.monotonic_ns()
        try:
            return original(*args, **kwargs)
        finally:
            fqdn_ns += time.monotonic_ns() - mark

    mark = time.monotonic_ns()
    socket.getfqdn = timed_fqdn
    try:
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    finally:
        socket.getfqdn = original
    startup = {"serverImportMs": (_IMPORT_DONE - _PROCESS_START) // 1_000_000,
               "serverBindMs": (time.monotonic_ns() - mark) // 1_000_000,
               "serverFqdnMs": fqdn_ns // 1_000_000}
    return server, startup


def main():
    # Check before opening the listener: runtime credential pipes must not reach
    # this process. This is an FD/environment check, not same-UID isolation.
    assert not any(k.startswith("EAP_") for k in os.environ)
    for name in os.listdir("/proc/self/fd"):
        if int(name) < 3:
            continue
        try:
            assert not stat.S_ISFIFO(os.fstat(int(name)).st_mode)
        except OSError:
            pass

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def reply(self, status, body=b"", content_type="application/json"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if (self.path != "/mcp" or self.headers.get("Origin") is not None
                    or self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}"
                    or any(k.lower().startswith("x-eap-") for k in self.headers)
                    or self.headers.get("Transfer-Encoding") is not None):
                self.reply(403)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                assert 0 < length <= 16384
                item = json.loads(self.rfile.read(length).decode("utf-8"), object_pairs_hook=_unique_object)
                assert item["jsonrpc"] == "2.0"
                version = self.headers.get("MCP-Protocol-Version")
                assert version in {"2025-11-25", "2026-07-28"}
                method = item["method"]
                if method == "initialize" and version == "2025-11-25":
                    assert item["params"]["protocolVersion"] == version
                    self.reply(200, json.dumps({"jsonrpc": "2.0", "id": item["id"], "result": {
                        "protocolVersion": version, "capabilities": {"tools": {}},
                        "serverInfo": {"name": "eap-http-synthetic", "version": "1"}}}).encode())
                    return
                if method == "notifications/initialized" and version == "2025-11-25":
                    self.reply(202)
                    return
                assert method == "tools/call" and item["params"]["name"] == "observation_probe"
                if version == "2026-07-28":
                    assert item["params"]["_meta"]["io.modelcontextprotocol/protocolVersion"] == version
                    assert self.headers.get("Mcp-Method") == method
                    assert self.headers.get("Mcp-Name") == "observation_probe"
                mode = item["params"]["arguments"]["mode"]
                assert mode in {"success", "failure", "disconnect"}
                result = json.dumps({"jsonrpc": "2.0", "id": item["id"], "result": {
                    "content": [{"type": "text", "text": "SYNTHETIC_HTTP_PRIVATE_RESULT"}],
                    "isError": mode == "failure"}}, indent=2).encode()
            except (ValueError, KeyError, TypeError, AssertionError):
                self.reply(400)
                return
            if mode == "success":
                self.reply(200, result)
            elif mode == "failure":
                # Actual SSE frames and chunked transfer; a different read
                # segmentation must not change semantic observation.
                payload = b"\xef\xbb\xbf: heartbeat\r\n\r\nevent: message\r\n"
                payload += b"\r\n".join(b"data: " + line for line in result.split(b"\n")) + b"\r\n\r\n"
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Transfer-Encoding", "chunked")
                self.send_header("Connection", "close")
                self.end_headers()
                for start in range(0, len(payload), 11):
                    part = payload[start:start + 11]
                    self.wfile.write(f"{len(part):x}\r\n".encode() + part + b"\r\n")
                    self.wfile.flush()
                self.wfile.write(b"0\r\n\r\n")
            else:
                # Connection closes without a final RPC result. No tool effect
                # or cancellation outcome can be inferred from this stream.
                self.reply(200, b": heartbeat\n\nid: SYNTHETIC_PRIVATE_CURSOR\ndata:\n\n",
                           "text/event-stream")

        def log_message(self, *_):
            pass

    server, startup = create_server(Handler)
    server.daemon_threads = True
    startup["serverBeforeReadyMs"] = (time.monotonic_ns() - _PROCESS_START) // 1_000_000
    print(json.dumps({"version": "EAP_MCP_HTTP_SERVER_V1", "port": server.server_port,
                      "credentialChannelsAbsent": True, "startup": startup}), flush=True)
    server.serve_forever(poll_interval=0.05)


if __name__ == "__main__":
    main()
