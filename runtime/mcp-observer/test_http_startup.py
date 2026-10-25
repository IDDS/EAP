"""Fixed startup diagnostics: attribution, failure restoration and input bounds."""
import http.server
import io
import json
import os
import subprocess
import sys
import time
import unittest
from unittest import mock

import http_actor_probe as probe
import http_actor_server as server


class StartupTests(unittest.TestCase):
    def test_resolver_delay_is_measured_inside_bind(self):
        def delayed(host):
            self.assertEqual(host, "127.0.0.1")
            time.sleep(0.08)
            return "synthetic-loopback"
        with mock.patch.object(server.socket, "getfqdn", delayed):
            listener, timings = server.create_server(http.server.BaseHTTPRequestHandler)
            try:
                self.assertIs(server.socket.getfqdn, delayed)
                self.assertGreaterEqual(timings["serverFqdnMs"], 60)
                self.assertGreaterEqual(timings["serverBindMs"], timings["serverFqdnMs"])
                self.assertEqual(listener.server_name, "synthetic-loopback")
                self.assertEqual(listener.server_address[0], "127.0.0.1")
            finally:
                listener.server_close()

    def test_resolver_restored_when_constructor_fails(self):
        def failure(host):
            raise RuntimeError("SYNTHETIC_PRIVATE_ERROR")
        with mock.patch.object(server.socket, "getfqdn", failure):
            with self.assertRaises(RuntimeError):
                server.create_server(http.server.BaseHTTPRequestHandler)
            self.assertIs(server.socket.getfqdn, failure)

    def test_real_ready_has_bounded_startup(self):
        child = subprocess.Popen([sys.executable, server.__file__], stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 close_fds=True, env={k: v for k, v in os.environ.items()
                                                     if not k.startswith("EAP_")})
        try:
            value = probe.readiness(child.stdout)
            self.assertIn("startup", value)
            self.assertEqual(set(value["startup"]), probe.SERVER_TIMINGS)
            self.assertTrue(probe.valid_server_timings(value["startup"]))
            self.assertTrue(value["credentialChannelsAbsent"])
        finally:
            child.terminate()
            child.wait(timeout=2)
            child.stdout.close()

    def test_bounds_and_exact_keys(self):
        good = dict(serverImportMs=2, serverBindMs=4, serverFqdnMs=3, serverBeforeReadyMs=9)
        self.assertTrue(probe.valid_server_timings(good))
        for key in good:
            for bad in [-1, 60001, True, "4", 0.5, None]:
                with self.subTest(key=key, value=bad):
                    self.assertFalse(probe.valid_server_timings({**good, key: bad}))
        for bad in [{}, None, [], {**good, "private": "SYNTHETIC_PRIVATE"},
                    {**good, "serverFqdnMs": 5}, {**good, "serverImportMs": 10},
                    {**good, "serverBeforeReadyMs": 3}]:
            self.assertFalse(probe.valid_server_timings(bad))

    def test_readiness_rejects_private_or_invalid_timing(self):
        payload = {"version": "EAP_MCP_HTTP_SERVER_V1", "port": 1234,
                   "credentialChannelsAbsent": True,
                   "startup": {"private": "SYNTHETIC_PRIVATE"}}
        read_fd, write_fd = os.pipe()
        with os.fdopen(read_fd, "rb") as pipe:
            os.write(write_fd, json.dumps(payload).encode() + b"\n")
            os.close(write_fd)
            with self.assertRaisesRegex(ValueError, "readiness unconfirmed"):
                probe.readiness(pipe)


if __name__ == "__main__":
    unittest.main()
