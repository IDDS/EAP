import unittest
from summarize import counters, flatten, latency, visibility


class AccountingTests(unittest.TestCase):
    def packet(self, span):
        return {"resourceSpans": [{"scopeSpans": [{"spans": [span]}]}]}

    def test_http_200_cannot_claim_tool_success(self):
        value = flatten([self.packet({"traceId": "a", "spanId": "b", "kind": 2,
                        "attributes": [{"key": "http.response.status_code", "value": {"intValue": "200"}}]})])[0]
        self.assertEqual(value["status"], 0)
        self.assertNotIn("toolOutcome", value)
        self.assertFalse(visibility(1, 1, True, same_node=True)["toolEffectsVerified"])

    def test_duplicate_span_does_not_inflate_coverage(self):
        p = self.packet({"traceId": "a", "spanId": "b"})
        with self.assertRaises(ValueError):
            flatten([p, p])
        self.assertEqual(visibility(1, 2, True, same_node=True)["verdict"], "INCONCLUSIVE_DUPLICATION_OR_SCOPE")

    def test_missing_positive_control_is_inconclusive(self):
        value = visibility(8, 0, False)
        self.assertEqual(value["verdict"], "INCONCLUSIVE_OBSERVER")
        self.assertNotIn("fraction", value)

    def test_zero_observed_is_explicit_gap(self):
        self.assertEqual(visibility(8, 0, True, same_node=True)["fraction"], 0)
        self.assertEqual(visibility(8, 0, True, same_node=True)["verdict"], "VISIBILITY_GAP")

    def test_missing_or_other_node_does_not_prove_visibility_gap(self):
        for same_node in [None, False, "true"]:
            value = visibility(8, 0, True, same_node=same_node)
            self.assertEqual(value["verdict"], "INCONCLUSIVE_PLACEMENT")
            self.assertNotIn("fraction", value)

    def test_internal_subspans_are_not_extra_requests(self):
        packets = [self.packet({"traceId": "t", "spanId": str(i), "kind": kind})
                   for i, kind in enumerate([2, 1, 1])]
        counts = counters(flatten(packets))
        self.assertEqual(counts["spans"], 3)
        self.assertEqual(counts["serverSpans"], 1)
        self.assertEqual(counts["internalSpans"], 2)

    def test_no_zero_or_negative_denominator(self):
        for total, seen in [(0, 0), (-1, 0), (1, -1), (True, 0)]:
            with self.assertRaises(ValueError):
                visibility(total, seen, True)

    def test_raw_payload_and_headers_omitted(self):
        value = flatten([self.packet({"traceId": "a", "spanId": "b", "name": "private text",
                        "attributes": [{"key": k, "value": {"stringValue": "private"}} for k in
                                       ["url.full", "http.request.header.authorization", "gen_ai.input.messages"]]})])
        self.assertNotIn("private", str(value))

    def test_invalid_span_identity_and_duplicate_attributes(self):
        for span in [{"spanId": "x"}, {"traceId": "a", "spanId": "b", "attributes":
                     [{"key": "x", "value": {"stringValue": "y"}}] * 2}]:
            with self.assertRaises(ValueError):
                flatten([self.packet(span)])

    def test_latency_requires_real_finite_samples(self):
        for values in [[], [float("nan")], [-1], [True]]:
            with self.assertRaises(ValueError):
                latency(values)
        self.assertEqual(latency([1, 2, 3, 4])["medianMs"], 2.5)


if __name__ == "__main__":
    unittest.main()
