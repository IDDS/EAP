"""Trusted host integration seam. Credentials travel only through a pipe."""
import json
import os
from pathlib import Path
import subprocess
import sys
import threading


def start_observed_server(command, *, server, tools, runtime_url, lease,
                          trace_id=None, parent_span_id=None, allow_http=False):
    """Return (Popen, credential_writer) to the instance owner.

    The owner copies each newly installed lease into credential_writer.update().
    Keep it open until relay exit. This helper does not request credentials or
    authenticate tasks; those remain the owner's existing EAP responsibilities.
    No events file is required, and no credential appears in argv/environment.
    """
    if not command or not all(isinstance(x, str) and x for x in command):
        raise ValueError("explicit server command required")
    read_fd, write_fd = os.pipe()
    writer = CredentialWriter(write_fd)
    args = [sys.executable, str(Path(__file__).with_name("relay.py")),
            "--server", server, "--runtime-url", runtime_url,
            "--runtime-credentials-fd", str(read_fd)]
    for tool in tools:
        args += ["--tool", tool]
    if trace_id is not None:
        args += ["--trace-id", trace_id]
    if parent_span_id is not None:
        args += ["--parent-span-id", parent_span_id]
    if allow_http:
        args.append("--allow-insecure-runtime-http")
    try:
        writer.update(lease)
        child = subprocess.Popen(args + ["--"] + list(command), pass_fds=(read_fd,),
                                 close_fds=True, stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return child, writer
    except Exception:
        writer.close()
        raise
    finally:
        os.close(read_fd)


class CredentialWriter:
    def __init__(self, fd):
        self.fd = fd
        self.max_update = min(2048, os.fpathconf(fd, "PC_PIPE_BUF"))
        os.set_inheritable(fd, False)
        # Never block the owner/identity refresh on a stalled relay.
        os.set_blocking(fd, False)
        self.lock = threading.Lock()

    def update(self, lease):
        raw = json.dumps(lease, separators=(",", ":")).encode() + b"\n"
        if len(raw) > self.max_update:
            raise ValueError("lease too large")
        with self.lock:
            if self.fd is None:
                raise ValueError("credential channel closed")
            try:
                # <= PIPE_BUF, so writes either complete or fail without blocking.
                if os.write(self.fd, raw) != len(raw):
                    raise OSError("incomplete credential delivery")
            except OSError:
                os.close(self.fd)
                self.fd = None
                raise OSError("credential delivery unavailable") from None

    def close(self):
        with self.lock:
            if self.fd is not None:
                os.close(self.fd)
                self.fd = None
