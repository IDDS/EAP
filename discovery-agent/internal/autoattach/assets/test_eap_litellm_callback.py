import asyncio
import json
import os
import tempfile
import unittest
from threading import Event
from types import SimpleNamespace
from unittest.mock import patch

from eap_litellm_callback import EAPLiteLLMCallback, install


class CallbackTests(unittest.TestCase):
    def setUp(self):
        self.received = []
        self.event = Event()
        self.temp = tempfile.TemporaryDirectory()
        self.token_file = os.path.join(self.temp.name, "token")
        with open(self.token_file, "w", encoding="utf-8") as handle:
            handle.write("scoped-token")
        os.environ.update({
            "EAP_CONTENT_AUTOINSTRUMENT": "true",
            "EAP_INSTRUMENTATION_URL": "http://collector.internal:8687",
            "EAP_INSTRUMENTATION_TOKEN_FILE": self.token_file,
            "EAP_INSTRUMENTATION_PROVIDER": "deepseek",
        })
        owner = self

        class _Response:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

        def fake_urlopen(request, timeout):
            owner.received.append((request.headers, json.loads(request.data.decode("utf-8")), timeout))
            owner.event.set()
            return _Response()

        self.urlopen = patch("urllib.request.urlopen", side_effect=fake_urlopen)
        self.urlopen.start()

    def tearDown(self):
        self.urlopen.stop()
        self.temp.cleanup()

    def test_sync_callbacks_capture_both_directions_with_one_trace(self):
        callback = EAPLiteLLMCallback()
        payloads = []
        callback._submit = payloads.append
        kwargs = {"litellm_call_id": "sync-call", "model": "test"}
        callback.log_pre_api_call("test", [{"role": "user", "content": "EAP test"}], kwargs)
        callback.log_success_event(kwargs, {"choices": [{"message": {"content": "OK"}}]}, None, None)
        self.assertEqual([p["direction"] for p in payloads], ["INPUT", "OUTPUT"])
        self.assertEqual({p["traceId"] for p in payloads}, {"sync-call"})
        callback.log_failure_event(kwargs, RuntimeError("private-error"), None, None)
        self.assertNotIn("private-error", json.dumps(payloads))

    def test_delivery_failure_is_visible_without_leaking_payload(self):
        callback = EAPLiteLLMCallback()
        with patch("urllib.request.urlopen", side_effect=RuntimeError("private-token")):
            with self.assertLogs("eap_litellm_callback", level="WARNING") as captured:
                callback._post({"content": "private-content"})
        self.assertIn("RuntimeError", str(captured.output))
        self.assertNotIn("private-token", str(captured.output))
        self.assertNotIn("private-content", str(captured.output))

    def test_install_and_capture_usage_without_provider_secret(self):
        fake_litellm = SimpleNamespace(callbacks=[])
        self.assertTrue(install(fake_litellm))
        callback = fake_litellm.callbacks[0]
        kwargs = {"litellm_call_id": "call-1", "model": "openai/deepseek-v4-pro", "api_key": "must-not-leak"}
        asyncio.run(callback.async_log_pre_api_call("openai/deepseek-v4-pro", [{"role": "user", "content": "hello"}], kwargs))
        self.assertTrue(self.event.wait(2))
        self.event.clear()
        response = {
            "model": "deepseek-v4-pro",
            "choices": [{"message": {"content": "world"}}],
            "usage": {"prompt_tokens": 5, "completion_tokens": 2},
            "_hidden_params": {"api_key": "response-secret", "response_cost": 0.01},
        }
        asyncio.run(callback.async_log_success_event(kwargs, response, None, None))
        self.assertTrue(self.event.wait(2))
        serialized = json.dumps([payload for _, payload, _ in self.received])
        self.assertNotIn("must-not-leak", serialized)
        self.assertNotIn("response-secret", serialized)
        self.assertEqual(self.received[-1][1]["metadata"]["usage"]["prompt_tokens"], 5)
        self.assertEqual(self.received[-1][1]["content"], "world")
        self.assertEqual(self.received[-1][0]["X-eap-instrumentation-token"], "scoped-token")


if __name__ == "__main__":
    unittest.main()
