"""Internal single-run worker. No startup scan, persisted claim, provider key or raw logs."""
import hashlib
import importlib.metadata
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from adapter import (ENGINE_VERSION, ContractError, HttpBackend, search)


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.active = None
        self.completed = []


def execute(state, identity, backend, context):
    try:
        try:
            result = search(context, backend)
            receipt = {"status": "COMPLETED", "candidate": result.candidate,
                       "candidateDigest": result.candidate_digest,
                       "engineVersion": ENGINE_VERSION, "reasonCode": result.reason_code}
        except Exception:
            receipt = {"status": "UNKNOWN", "engineVersion": ENGINE_VERSION,
                       "reasonCode": "SEARCH_EVIDENCE_UNKNOWN"}
        # One write only. Backend seal is durable; a lost response does not
        # authorize repeating the search or its model calls.
        backend._request("/search-result", receipt)
    except Exception:
        pass
    finally:
        with state.lock:
            state.completed.append(identity)
            state.completed[:] = state.completed[-32:]
            state.active = None


class Handler(BaseHTTPRequestHandler):
    state = State()

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, format, *args):
        pass

    def response(self, status, body):
        raw = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/health":
            self.response(200, {"status": "UP", "engineVersion": ENGINE_VERSION})
        else:
            self.response(404, {"reasonCode": "ROUTE_NOT_FOUND"})

    def do_POST(self):
        if self.path != "/run":
            self.response(404, {"reasonCode": "ROUTE_NOT_FOUND"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= 256:
                raise ContractError("REQUEST_SIZE_REJECTED")
            request = json.loads(self.rfile.read(length))
            if not isinstance(request, dict) or set(request) != {"runId"}:
                raise ContractError("REQUEST_FIELDS_REJECTED")
            claim = self.headers.get("X-EAP-Optimization-Claim")
            backend = HttpBackend(request["runId"], claim)
            identity = (request["runId"], hashlib.sha256(claim.encode()).hexdigest())
            # The hash only is retained after the active thread finishes.
            with self.state.lock:
                if identity in self.state.completed or self.state.active == identity:
                    self.response(409, {"reasonCode": "RUN_INTENT_RECORDED"})
                    return
                if self.state.active is not None:
                    self.response(409, {"reasonCode": "WORKER_BUSY"})
                    return
                context = backend.context()  # Backend verifies claim and scope.
                if context["runId"] != request["runId"]:
                    raise ContractError("CONTEXT_ID_REJECTED")
                self.state.active = identity
        except Exception:
            self.response(403, {"reasonCode": "RUN_AUTHORIZATION_UNCONFIRMED"})
            return
        # Start exactly once even if the caller loses the acceptance response.
        threading.Thread(target=execute, args=(self.state, identity, backend, context), daemon=True).start()
        self.response(202, {"accepted": True, "runId": request["runId"], "engineVersion": ENGINE_VERSION})


if __name__ == "__main__":
    if importlib.metadata.version("gepa") != "0.1.4":
        raise RuntimeError("INSTALLED_ENGINE_VERSION_REJECTED")
    ThreadingHTTPServer(("0.0.0.0", 8091), Handler).serve_forever()
