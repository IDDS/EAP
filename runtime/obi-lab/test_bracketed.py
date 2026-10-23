import copy
import unittest

from bracketed import analyze, split_windows


class BracketedTests(unittest.TestCase):
    def setUp(self):
        self.windows = {name: {"startNs": start, "endNs": start + 100}
                        for name, start in zip(("before", "task", "after"), (100, 300, 500))}
        self.packets = [self.packet("a", 120), self.packet("b", 520)]
        self.controls = {name: {"passed": True, "httpRequests": 1, "nodeName": "n1"}
                         for name in ("before", "after")}
        placement = {"status": "ASSIGNED", "source": "SUBSTRATE_CONTROL_API", "actorState": "RUNNING",
                     "actorUid": "actor-uid", "actorVersion": 1, "instanceKey": "space/actor",
                     "placement": {"workerName": "worker", "workerUid": "worker-uid", "namespace": "ns",
                                   "pool": "pool", "podName": "pod", "podUid": "pod-uid", "nodeName": "n1"}}
        self.placements = {name: copy.deepcopy(placement) for name in ("before", "after")}
        self.observer = {"uninterrupted": True, "exitCode": 0, "restarts": 0, "podUid": "observer", "nodeName": "n1"}

    def packet(self, identity, start, kind=2):
        return {"resourceSpans": [{"scopeSpans": [{"spans": [{"traceId": "trace", "spanId": identity,
                "startTimeUnixNano": str(start), "endTimeUnixNano": str(start + 1), "kind": kind}]}]}]}

    def result(self):
        return analyze(self.packets, self.windows, self.controls, self.placements, self.observer)

    def test_two_controls_and_matching_boundary_allow_bounded_gap(self):
        result = self.result()
        self.assertEqual(result["verdict"]["fraction"], 0)
        self.assertTrue(result["sameNodeBoundaryEvidence"])
        self.assertFalse(result["continuousPlacementProven"])
        self.assertFalse(result["taskIdentityVerified"])

    def test_missing_or_failed_after_control_prevents_gap_claim(self):
        for value in ({}, {"passed": False}, {"passed": True, "httpRequests": 2, "nodeName": "n1"}):
            self.controls["after"] = value
            self.assertEqual(self.result()["verdict"]["verdict"], "INCONCLUSIVE_OBSERVER")

    def test_probe_restart_or_failure_prevents_gap_claim(self):
        for key, value in (("uninterrupted", False), ("exitCode", 1), ("restarts", 1), ("podUid", "")):
            previous = self.observer[key]
            self.observer[key] = value
            self.assertNotIn("fraction", self.result()["verdict"])
            self.observer[key] = previous

    def test_changed_actor_worker_pod_or_node_is_inconclusive(self):
        original = copy.deepcopy(self.placements["after"])
        for key in ("actorUid", "instanceKey", "workerUid", "podUid", "nodeName"):
            self.placements["after"] = copy.deepcopy(original)
            target = self.placements["after"] if key in original else self.placements["after"]["placement"]
            target[key] = "replacement"
            self.assertEqual(self.result()["verdict"]["verdict"], "INCONCLUSIVE_PLACEMENT")

    def test_unknown_or_untrusted_location_is_inconclusive(self):
        for key, value in (("status", "UNKNOWN"), ("source", "CACHE"), ("actorState", "SUSPENDED"), ("actorVersion", 0)):
            previous = self.placements["before"][key]
            self.placements["before"][key] = value
            self.assertNotIn("fraction", self.result()["verdict"])
            self.placements["before"][key] = previous

    def test_delayed_export_is_classified_by_span_time(self):
        self.packets.reverse()
        self.packets.append(self.packet("c", 320))
        self.assertEqual(self.result()["counts"]["task"]["serverSpans"], 1)

    def test_outside_or_crossing_window_prevents_scope_claim(self):
        for start in (250, 200):
            self.packets.append(self.packet("outside", start))
            self.assertEqual(self.result()["verdict"]["verdict"], "INCONCLUSIVE_WINDOW_SCOPE")
            self.packets.pop()

    def test_duplicate_span_across_packets_is_rejected(self):
        self.packets.append(copy.deepcopy(self.packets[0]))
        with self.assertRaises(ValueError):
            self.result()

    def test_missing_overlapping_or_reversed_windows_rejected(self):
        for window in ({}, {"before": {"startNs": 100, "endNs": 400}, **{k: v for k, v in self.windows.items() if k != "before"}}):
            with self.assertRaises(ValueError):
                split_windows(self.packets, window)

    def test_missing_timestamp_rejected(self):
        del self.packets[0]["resourceSpans"][0]["scopeSpans"][0]["spans"][0]["startTimeUnixNano"]
        with self.assertRaises(ValueError):
            self.result()

    def test_internal_spans_do_not_satisfy_control_requests(self):
        self.packets[1] = self.packet("b", 520, 1)
        self.assertEqual(self.result()["verdict"]["verdict"], "INCONCLUSIVE_OBSERVER")

    def test_control_on_other_node_is_not_positive(self):
        self.controls["after"]["nodeName"] = "n2"
        self.assertEqual(self.result()["verdict"]["verdict"], "INCONCLUSIVE_OBSERVER")


if __name__ == "__main__":
    unittest.main()
