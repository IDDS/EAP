import copy
import dataclasses
import itertools
import unittest

from candidate_frontier import CandidateError, CandidateRegistry
from frontier_demo import configuration, demo, protocol, receipt


class CandidateFrontierTests(unittest.TestCase):
    def registry(self, **limits):
        return CandidateRegistry(protocol(**limits), configuration())

    def propose(self, registry, name="A", parent=None, **prompts):
        return registry.register("proposal-" + name, parent or registry.baseline_id,
                                 configuration(extractor=name, **prompts))

    def observe(self, registry, cid, **metrics):
        return registry.observe("eval-" + cid[:12], cid, receipt(registry, cid, **metrics))

    def test_public_fixture_preserves_cost_latency_tradeoffs_and_rejects_low_quality(self):
        report = demo()
        self.assertEqual(report["namedFrontier"], ["A", "B", "baseline"])
        self.assertEqual(report["namedDecisions"]["C"], "QUALITY_GATE_REJECTED")
        self.assertEqual(report["namedDecisions"]["D"], "EVIDENCE_INCOMPLETE")
        self.assertEqual(report["namedDecisions"]["E"], "DOMINATED")
        self.assertEqual(report["recommendation"], "NO_ADOPTION_OFFLINE_ONLY")
        self.assertEqual(report["actualModelCalls"], 0)

    def test_single_role_lineage_is_immutable_and_caller_data_detached(self):
        config = configuration()
        p = protocol()
        r = CandidateRegistry(p, config)
        p["sampleIds"].clear()
        config["rolePrompts"]["extractor"] = "changed"
        child = self.propose(r)
        grandchild = r.register("proposal-B", child.candidate_id,
                               configuration(extractor="A", classifier="B"))
        self.assertEqual((child.parent_id, child.generation, child.changed_role),
                         (r.baseline_id, 1, "extractor"))
        self.assertEqual((grandchild.parent_id, grandchild.generation, grandchild.changed_role),
                         (child.candidate_id, 2, "classifier"))
        detached = child.configuration()
        detached["rolePrompts"]["extractor"] = "tampered"
        self.assertEqual(child.configuration()["rolePrompts"]["extractor"], "A")
        self.assertEqual(len(r.protocol["sampleIds"]), 5)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            child.generation = 9

    def test_same_key_reads_original_and_changed_parameters_conflict(self):
        r = self.registry()
        child = self.propose(r)
        self.assertIs(self.propose(r), child)
        self.assertEqual(r.attempts, 1)
        with self.assertRaisesRegex(CandidateError, "OPERATION_CONFLICT"):
            r.register("proposal-A", r.baseline_id, configuration(extractor="other"))
        self.assertEqual(len(r.candidates()), 2)

    def test_all_non_prompt_mutations_and_unknown_roles_are_rejected(self):
        for field in ("model", "tools", "permissions", "code", "budget", "identity", "topology", "holdout"):
            with self.subTest(field=field):
                r = self.registry()
                changed = configuration(extractor="new")
                changed[field] = "unapproved"
                with self.assertRaisesRegex(CandidateError, "CANDIDATE_FIELDS_REJECTED"):
                    r.register("attempt", r.baseline_id, changed)
                self.assertEqual(len(r.candidates()), 1)
        r = self.registry()
        changed = configuration(extractor="new")
        changed["rolePrompts"]["third"] = "new role"
        with self.assertRaisesRegex(CandidateError, "CANDIDATE_ROLES_REJECTED"):
            r.register("attempt", r.baseline_id, changed)

    def test_zero_or_two_role_changes_unknown_parent_and_utf8_limit_rejected(self):
        r = self.registry()
        cases = [(r.baseline_id, configuration(), "SINGLE_ROLE"),
                 (r.baseline_id, configuration("A", "B"), "SINGLE_ROLE"),
                 ("missing", configuration("A"), "PARENT_UNCONFIRMED"),
                 (r.baseline_id, configuration("中" * 2667), "PROMPT_REJECTED")]
        for i, (parent, config, reason) in enumerate(cases):
            with self.subTest(reason=reason), self.assertRaisesRegex(CandidateError, reason):
                r.register("attempt-" + str(i), parent, config)
        self.assertEqual(len(r.candidates()), 1)

    def test_rejected_proposals_consume_independent_attempt_limit_and_replay_same_rejection(self):
        r = self.registry(maxAttempts=2)
        for key in ("one", "one", "two"):
            with self.assertRaisesRegex(CandidateError, "SINGLE_ROLE"):
                r.register(key, r.baseline_id, configuration())
        self.assertEqual(r.attempts, 2)
        with self.assertRaisesRegex(CandidateError, "ATTEMPT_LIMIT_REACHED"):
            self.propose(r)
        self.assertEqual(len(r.candidates()), 1)

    def test_candidate_and_generation_limits_do_not_depend_on_frontier_size(self):
        r = self.registry(maxCandidates=2)
        self.propose(r)
        with self.assertRaisesRegex(CandidateError, "CANDIDATE_LIMIT_REACHED"):
            self.propose(r, "B")
        r = self.registry(maxGeneration=1)
        child = self.propose(r)
        with self.assertRaisesRegex(CandidateError, "GENERATION_LIMIT_REACHED"):
            self.propose(r, "B", parent=child.candidate_id)

    def test_duplicate_content_cannot_invent_second_lineage(self):
        r = self.registry()
        child = self.propose(r)
        with self.assertRaisesRegex(CandidateError, "CANDIDATE_ALREADY_REGISTERED"):
            r.register("another-key", r.baseline_id, child.configuration())
        with self.assertRaisesRegex(CandidateError, "CANDIDATE_ALREADY_REGISTERED"):
            r.register("cycle", child.candidate_id, configuration())

    def test_protocol_fields_limits_and_sample_identity_are_frozen(self):
        cases = []
        for field, value in (("maxAttempts", True), ("maxCandidates", 0), ("maxGeneration", 9),
                             ("minimumQualityBps", 10001), ("sampleIds", ["same", "same"]),
                             ("protocolDigest", "not-a-digest"), ("holdout", [])):
            cases.append(protocol(**{field: value}))
        for p in cases:
            with self.subTest(protocol=p), self.assertRaises(CandidateError):
                CandidateRegistry(p, configuration())

    def test_different_protocol_or_batch_cannot_share_candidate_identity_or_receipt(self):
        r = self.registry()
        for field in ("protocolDigest", "batchDigest"):
            other = self.registry(**{field: "b" * 64})
            self.assertNotEqual(r.baseline_id, other.baseline_id)
            value = receipt(r, r.baseline_id)
            value[field] = "b" * 64
            with self.assertRaisesRegex(CandidateError, "RECEIPT_SCOPE_REJECTED"):
                r.observe("scope-" + field, r.baseline_id, value)
        self.assertEqual(r.assess()["frontier"], [])

    def test_train_holdout_or_extra_content_never_enters_frontier(self):
        r = self.registry()
        for split in ("TRAIN", "HOLDOUT"):
            value = receipt(r, r.baseline_id)
            value["split"] = split
            with self.assertRaisesRegex(CandidateError, "RECEIPT_SCOPE_REJECTED"):
                r.observe("eval", r.baseline_id, value)
        value = receipt(r, r.baseline_id)
        value["results"][0]["output"] = "not accepted"
        with self.assertRaisesRegex(CandidateError, "RESULT_FIELDS_REJECTED"):
            r.observe("eval", r.baseline_id, value)

    def test_unknown_is_not_free_or_fast_and_cannot_be_replaced_by_new_key(self):
        r = self.registry()
        self.observe(r, r.baseline_id)
        child = self.propose(r)
        value = receipt(r, child.candidate_id, total_tokens=0, total_latency=0)
        value["results"][0]["status"] = "UNKNOWN"
        metrics = r.observe("original", child.candidate_id, value)
        self.assertEqual((metrics.denominator, metrics.passed, metrics.unknown), (5, 4, 1))
        self.assertIsNone(metrics.total_tokens)
        self.assertIsNone(metrics.total_latency_ms)
        self.assertIs(r.observe("original", child.candidate_id, copy.deepcopy(value)), metrics)
        with self.assertRaisesRegex(CandidateError, "ORIGINAL_OBSERVATION_REQUIRED"):
            r.observe("retry", child.candidate_id, receipt(r, child.candidate_id))
        with self.assertRaisesRegex(CandidateError, "OBSERVATION_CONFLICT"):
            r.observe("original", child.candidate_id, receipt(r, child.candidate_id))
        self.assertEqual(r.assess()["frontier"], [r.baseline_id])

    def test_missing_and_not_run_results_keep_full_registered_denominator(self):
        for missing in (True, False):
            r = self.registry()
            value = receipt(r, r.baseline_id)
            if missing:
                value["results"].pop()
            else:
                value["results"][-1]["status"] = "NOT_RUN"
            metrics = r.observe("original", r.baseline_id, value)
            self.assertEqual((metrics.denominator, metrics.passed, metrics.not_run), (5, 4, 1))
            self.assertEqual(metrics.reason_code, "EVIDENCE_INCOMPLETE")
            self.assertIsNone(metrics.total_tokens)
            self.assertEqual(r.assess()["frontier"], [])

    def test_failed_status_cannot_claim_passing_score_and_costs_are_preserved(self):
        r = self.registry(minimumQualityBps=0)
        value = receipt(r, r.baseline_id)
        value["results"][0]["status"] = "FAILED"
        metrics = r.observe("original", r.baseline_id, value)
        self.assertEqual((metrics.passed, metrics.failed, metrics.total_tokens), (4, 1, 1000))
        self.assertEqual(metrics.reason_code, "COMPLETE")

    def test_missing_negative_boolean_nan_or_estimated_metrics_are_never_ranked(self):
        for field, value in (("inputTokens", None), ("outputTokens", -1), ("inputTokens", True),
                             ("latencyMs", None), ("latencyMs", -1), ("usageSource", "ESTIMATED")):
            with self.subTest(field=field, value=value):
                r = self.registry()
                result = receipt(r, r.baseline_id)
                result["results"][0][field] = value
                self.assertEqual(r.observe("original", r.baseline_id, result).reason_code, "EVIDENCE_INCOMPLETE")
                self.assertEqual(r.assess()["frontier"], [])
        r = self.registry()
        result = receipt(r, r.baseline_id)
        result["results"][0]["latencyMs"] = float("nan")
        with self.assertRaisesRegex(CandidateError, "JSON_CONTRACT_REJECTED"):
            r.observe("original", r.baseline_id, result)

    def test_duplicate_foreign_rows_and_boolean_scores_are_rejected(self):
        for change in ("duplicate", "foreign", "boolean"):
            r = self.registry()
            value = receipt(r, r.baseline_id)
            if change == "duplicate":
                value["results"][1] = copy.deepcopy(value["results"][0])
            elif change == "foreign":
                value["results"][0]["sampleId"] = "outside-batch"
            else:
                value["results"][0]["score"] = True
            with self.subTest(change=change), self.assertRaises(CandidateError):
                r.observe("original", r.baseline_id, value)

    def test_baseline_must_be_complete_and_its_quality_cannot_be_traded_for_speed(self):
        r = self.registry(minimumQualityBps=0)
        child = self.propose(r)
        self.observe(r, child.candidate_id, total_tokens=5, total_latency=5, passed=4)
        self.assertEqual(r.assess()["decisions"][child.candidate_id], "BASELINE_UNCONFIRMED")
        self.observe(r, r.baseline_id)
        self.assertEqual(r.assess()["decisions"][child.candidate_id], "QUALITY_GATE_REJECTED")

    def test_strict_dominance_and_equal_metrics_retain_all_ties(self):
        r = self.registry()
        a = self.propose(r, "A")
        b = self.propose(r, "B")
        self.observe(r, r.baseline_id)
        for candidate in (a, b):
            self.observe(r, candidate.candidate_id, total_tokens=800, total_latency=1200)
        report = r.assess()
        self.assertEqual(report["frontier"], sorted([a.candidate_id, b.candidate_id]))
        self.assertEqual(report["decisions"][r.baseline_id], "DOMINATED")

    def test_registration_and_receipt_order_cannot_change_frontier(self):
        expected = None
        for order in itertools.permutations(["A", "B", "C"]):
            r = self.registry()
            candidates = {name: self.propose(r, name) for name in order}
            self.observe(r, r.baseline_id)
            for name in reversed(order):
                self.observe(r, candidates[name].candidate_id, total_tokens=800 if name == "A" else 1200,
                             total_latency=900 if name == "B" else 1500, passed=4 if name == "C" else 5)
            report = r.assess()
            if expected is None:
                expected = report
            self.assertEqual(report, expected)


if __name__ == "__main__":
    unittest.main(verbosity=2)
