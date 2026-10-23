import unittest
from eap_tool_guard import ToolGuard, ToolStopped, GuardUnavailable


class GuardTests(unittest.TestCase):
    def client(self, responses):
        guard = ToolGuard("https://synthetic.invalid", "space/test", "synthetic-only")
        self.actions = []
        def post(action, payload):
            self.actions.append((action, payload))
            result = responses.pop(0)
            if isinstance(result, Exception):
                raise result
            return result
        guard._post = post
        return guard

    def run_tool(self, guard, callback):
        return guard.execute(call_id="00000000-0000-4000-8000-000000000001", tool_name="lookup",
                             arguments={"query": "synthetic"}, trace_id="1" * 32, invoke=callback)

    def test_block_and_review_never_call_tool(self):
        for state in ["BLOCKED", "REVIEW_REQUIRED", "EXPIRED", "CLAIMED", "SUCCEEDED"]:
            with self.assertRaises(ToolStopped):
                self.run_tool(self.client([{"state": state}]), lambda _: self.fail("must not run"))
            self.assertEqual([a[0] for a in self.actions], ["check"])

    def test_lost_or_duplicate_claim_never_calls_tool(self):
        for result in [{"execute": False}, GuardUnavailable("unconfirmed")]:
            with self.assertRaises((ToolStopped, GuardUnavailable)):
                self.run_tool(self.client([{"state": "APPROVED"}, result]), lambda _: self.fail("must not run"))
            self.assertEqual([a[0] for a in self.actions], ["check", "claim"])

    def test_receipt_failure_returns_local_result_without_replaying(self):
        count = []
        result = self.run_tool(self.client([{"state": "APPROVED"}, {"execute": True}, GuardUnavailable("lost")]),
                               lambda args: count.append(args) or {"ok": True})
        self.assertEqual(len(count), 1)
        self.assertFalse(result.receipt_confirmed)
        self.assertEqual(result.value, {"ok": True})
        self.assertNotIn("output", self.actions[-1][1])

    def test_callback_failure_reports_failed_once(self):
        def fail(_):
            raise ValueError("synthetic failure")
        with self.assertRaises(ValueError):
            self.run_tool(self.client([{"state": "APPROVED"}, {"execute": True}, {}]), fail)
        self.assertEqual(self.actions[-1][1]["outcome"], "FAILED")
        self.assertNotIn("synthetic failure", str(self.actions))

    def test_http_requires_explicit_opt_in_and_url_rejects_embedded_credentials(self):
        for url in ["http://synthetic.invalid", "https://user:password@synthetic.invalid", "https://synthetic.invalid?token=synthetic"]:
            with self.assertRaises(ValueError):
                ToolGuard(url, "space/test", "synthetic-only")


if __name__ == "__main__":
    unittest.main()
