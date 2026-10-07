import copy
import importlib.metadata
import json
import unittest
from unittest.mock import patch

from adapter import (ContractError, EapAdapter, EvaluationUnknown, HttpBackend,
                     SearchLimit, digest, search, validate_context)


def context(engine="GEPA", metric_calls=8):
    return {"runId": 45, "engine": engine, "engineVersion": "gepa-0.1.4",
            "taskMode": "STATELESS_PROMPT_V1", "maxMetricCalls": metric_calls,
            "maxReflections": 2, "baselinePrompt": "Answer with explanation.",
            "train": [{"id": "train-1", "groupId": "g1", "input": "Reply RED", "expected": "RED", "split": "TRAIN"}],
            "validation": [{"id": "validation-1", "groupId": "g2", "input": "Reply BLUE", "expected": "BLUE", "split": "VALIDATION"}]}


class FakeBackend:
    """Synthetic protocol peer. Does not claim real EAP integration/model gains."""
    def __init__(self, c=None):
        self.context = c or context()
        self.operations = {}
        self.reflections = {}
        self.calls = 0
        self.post_count = 0
        self.requests = []
        self.transform = lambda r: r

    def evaluate(self, request):
        self.post_count += 1
        self.requests.append(copy.deepcopy(request))
        if request["requestKey"] in self.operations:
            if self.operations[request["requestKey"]][0] != request:
                raise ContractError("FAKE_FINGERPRINT_CONFLICT")
            return copy.deepcopy(self.operations[request["requestKey"]][1])
        self.calls += 2 * len(request["sampleIds"])
        samples = {s["id"]: s for split in ("train", "validation") for s in self.context[split]}
        good = "only" in request["candidate"]["system_prompt"].lower()
        rows = [{"sampleId": sid, "status": "SUCCEEDED", "score": int(good),
                 "output": samples[sid]["expected"] if good else "explanation", "traceId": digest(sid)[:32],
                 "inputTokens": 10, "outputTokens": 2 if good else 20,
                 "usageSource": "PROVIDER_REPORTED", "latencyMs": 3} for sid in reversed(request["sampleIds"])]
        result = self.transform({"operationKey": request["requestKey"], "status": "COMPLETED", "results": rows})
        self.operations[request["requestKey"]] = (copy.deepcopy(request), copy.deepcopy(result))
        return result

    def reflect(self, request):
        if request["ordinal"] in self.reflections:
            old, result = self.reflections[request["ordinal"]]
            if request != old:
                raise ContractError("FAKE_REFLECTION_CONFLICT")
            return result
        self.calls += 1
        result = {"status": "COMPLETED", "candidate": {"system_prompt": "Return only the requested final answer."}}
        self.reflections[request["ordinal"]] = (copy.deepcopy(request), result)
        return result


class AdapterTests(unittest.TestCase):
    def test_pinned_real_gepa_runs_adapter_without_default_provider(self):
        self.assertEqual(importlib.metadata.version("gepa"), "0.1.4")
        backend = FakeBackend()
        result = search(context(), backend)
        self.assertEqual(result.validation_score, 1)
        self.assertIn("only", result.candidate["system_prompt"])
        self.assertGreaterEqual(result.reflection_intents, 1)
        self.assertLessEqual(result.reflection_intents, 2)
        self.assertLessEqual(result.metric_calls, 8)
        self.assertLessEqual(backend.calls, 2 * 8 + 2)
        self.assertEqual(result.candidate_digest, digest(result.candidate))

    def test_three_train_and_validation_samples_still_validate_a_proposal_within_eight_metrics(self):
        c = context(metric_calls=8)
        for split in ("train", "validation"):
            first = c[split][0]
            c[split] = [dict(first, id=f"{split}-{i}", groupId=f"group-{split}-{i}", input=f"{first['input']} number {i}") for i in range(3)]
        b = FakeBackend(c)
        result = search(c, b)
        self.assertEqual(result.validation_score, 1)
        self.assertIn("only", result.candidate["system_prompt"])
        self.assertLessEqual(result.metric_calls, 8)
        self.assertLessEqual(b.calls, 18)
        self.assertTrue(any(r["split"] == "VALIDATION" and len(r["sampleIds"]) == 3 and "only" in r["candidate"]["system_prompt"].lower() for r in b.requests))
        self.assertTrue(all(len(r["sampleIds"]) == 1 for r in b.requests if r["split"] == "TRAIN"))

    def test_simple_search_uses_same_frozen_adapter_contract(self):
        backend = FakeBackend(context("SIMPLE"))
        result = search(context("SIMPLE"), backend)
        self.assertEqual(result.validation_score, 1)
        self.assertEqual(result.reflection_intents, 0)
        self.assertEqual(result.metric_calls, 3)
        self.assertEqual(backend.calls, 6)

    def test_training_success_cannot_replace_actual_validation_failure(self):
        c = context()
        b = FakeBackend(c)
        def different_split_scores(result):
            split = b.requests[-1]["split"]
            if split == "VALIDATION":
                result["results"][0]["score"] = 0
                result["results"][0]["output"] = "VALIDATION_FAILED"
            return result
        b.transform = different_split_scores
        result = search(c, b)
        self.assertEqual(result.validation_score, 0)
        self.assertTrue(any(r["split"] == "VALIDATION" and "only" in r["candidate"]["system_prompt"].lower() for r in b.requests))

    def test_gepa_proposal_fallback_cannot_retry_unknown_reflection(self):
        b = FakeBackend()
        counter = []
        def unknown(request):
            counter.append(request)
            raise EvaluationUnknown("REFLECTION_RECEIPT_LOST")
        b.reflect = unknown
        with self.assertRaisesRegex(EvaluationUnknown, "REFLECTION_RECEIPT_LOST"):
            search(context(), b)
        self.assertEqual(len(counter), 1)

    def test_holdout_and_nested_extra_fields_are_rejected(self):
        for field in ("holdout", "holdoutAnswers", "providerKey", "callbackUrl"):
            c = context()
            c[field] = "SHOULD_NEVER_ENTER_CONTEXT"
            with self.subTest(field=field), self.assertRaises(ContractError):
                validate_context(c)
        c = context()
        c["train"][0]["holdout"] = "secret"
        with self.assertRaises(ContractError):
            validate_context(c)

    def test_group_and_normalized_input_overlap_are_rejected(self):
        c = context()
        c["validation"][0]["groupId"] = "g1"
        with self.assertRaisesRegex(ContractError, "GROUP_OVERLAP"):
            validate_context(c)
        c = context()
        c["validation"][0]["input"] = "  Reply　RED  "
        with self.assertRaisesRegex(ContractError, "SAMPLE_OVERLAP"):
            validate_context(c)

    def test_candidate_cannot_change_model_tools_permissions_or_code(self):
        c = context()
        backend = FakeBackend()
        adapter = EapAdapter(c, backend)
        for field in ("model", "tools", "permissions", "code"):
            with self.subTest(field=field), self.assertRaises(ContractError):
                adapter.evaluate(c["validation"], {"system_prompt": "ok", field: "x"})
        self.assertEqual(backend.calls, 0)

    def test_utf8_bytes_and_non_string_prompt_are_checked_before_callback(self):
        c = context()
        backend = FakeBackend()
        adapter = EapAdapter(c, backend)
        for value in ("中" * 2667, " ", None, 1, {}):
            with self.subTest(value=type(value).__name__), self.assertRaises(ContractError):
                adapter.evaluate(c["validation"], {"system_prompt": value})
        self.assertEqual(backend.calls, 0)

    def test_sample_identity_and_split_may_not_be_replaced(self):
        c = context()
        backend = FakeBackend()
        adapter = EapAdapter(c, backend)
        modified = copy.deepcopy(c["validation"])
        modified[0]["expected"] = "hidden answer"
        with self.assertRaises(ContractError):
            adapter.evaluate(modified, {"system_prompt": "ok"})
        with self.assertRaises(ContractError):
            adapter.evaluate(c["train"] + c["validation"], {"system_prompt": "ok"})
        self.assertEqual(backend.calls, 0)

    def test_rows_are_reordered_by_persisted_sample_identity(self):
        c = context()
        c["validation"].append({"id": "validation-2", "groupId": "g3", "input": "Reply GREEN", "expected": "GREEN", "split": "VALIDATION"})
        adapter = EapAdapter(c, FakeBackend(c))
        batch = adapter.evaluate(c["validation"], {"system_prompt": "Only answer"}, True)
        self.assertEqual(batch.outputs, ["BLUE", "GREEN"])
        self.assertEqual([r["sampleId"] for r in batch.trajectories], ["validation-1", "validation-2"])

    def test_missing_duplicate_and_foreign_rows_are_unknown(self):
        transforms = [lambda r: {**r, "results": []},
                      lambda r: {**r, "results": r["results"] * 2},
                      lambda r: {**r, "results": [{**r["results"][0], "sampleId": "holdout-1"}]}]
        for transform in transforms:
            b = FakeBackend()
            b.transform = transform
            with self.assertRaises(EvaluationUnknown):
                EapAdapter(context(), b).evaluate(context()["validation"], {"system_prompt": "ok"})

    def test_unknown_and_missing_usage_stop_search_but_remain_zero_in_evidence(self):
        for status, usage in (("UNKNOWN", "UNKNOWN"), ("SUCCEEDED", "UNKNOWN")):
            b = FakeBackend()
            b.transform = lambda r: {**r, "results": [{**r["results"][0], "status": status, "usageSource": usage}]}
            adapter = EapAdapter(context(), b)
            with self.assertRaises(EvaluationUnknown):
                adapter.evaluate(context()["validation"], {"system_prompt": "ok"})
            self.assertEqual(len(adapter.evidence_batches[0]), 1)
            self.assertEqual(adapter.evidence_batches[0][0]["score"], 0)
            self.assertEqual(adapter.full_validation, {})

    def test_known_failure_is_in_denominator_even_if_receipt_claims_score_one(self):
        b = FakeBackend()
        b.transform = lambda r: {**r, "status": "FAILED", "results": [{**r["results"][0], "status": "FAILED", "score": 1}]}
        batch = EapAdapter(context(), b).evaluate(context()["validation"], {"system_prompt": "Only answer"})
        self.assertEqual(batch.scores, [0])

    def test_partial_validation_cannot_supply_best_candidate(self):
        c = context()
        c["validation"].append({"id": "validation-2", "groupId": "g3", "input": "Reply GREEN", "expected": "GREEN", "split": "VALIDATION"})
        adapter = EapAdapter(c, FakeBackend(c))
        adapter.evaluate(c["validation"][:1], {"system_prompt": "Only answer"})
        with self.assertRaisesRegex(EvaluationUnknown, "NO_COMPLETE_VALIDATION"):
            adapter.best("done")

    def test_reflection_accepts_only_approved_training_data(self):
        c = context()
        adapter = EapAdapter(c, FakeBackend())
        candidate = {"system_prompt": "ok"}
        batch = adapter.evaluate(c["validation"], candidate, True)
        with self.assertRaisesRegex(ContractError, "REFLECTION_DATA_REJECTED"):
            adapter.make_reflective_dataset(candidate, batch, ["system_prompt"])
        with self.assertRaisesRegex(ContractError, "REFLECTION_DATA_REJECTED"):
            adapter.propose_new_texts(candidate, {"system_prompt": [{"sampleId": "train-1", "input": "forged", "expected": "RED"}]}, ["system_prompt"])

    def test_counters_stop_before_dispatch_and_do_not_claim_to_be_model_calls(self):
        c = context(metric_calls=2)
        c["maxReflections"] = 0
        b = FakeBackend(c)
        adapter = EapAdapter(c, b)
        adapter.evaluate(c["validation"], {"system_prompt": "ok"})
        adapter.evaluate(c["validation"], {"system_prompt": "Only answer"})
        with self.assertRaises(SearchLimit):
            adapter.evaluate(c["validation"], {"system_prompt": "another"})
        self.assertEqual(adapter.metric_calls, 2)
        self.assertEqual(b.calls, 4)
        with self.assertRaises(SearchLimit):
            adapter.propose_new_texts({"system_prompt": "ok"}, {"system_prompt": [{"sampleId": "train-1", "input": "Reply RED", "expected": "RED"}]}, ["system_prompt"])

    def test_adapter_restart_uses_same_intent_and_does_not_issue_extra_fake_model_calls(self):
        c = context()
        b = FakeBackend(c)
        first = EapAdapter(c, b)
        first.evaluate(c["validation"], {"system_prompt": "ok"})
        second = EapAdapter(c, b)
        second.evaluate(c["validation"], {"system_prompt": "ok"})
        self.assertEqual(b.calls, 2)
        self.assertEqual(b.requests[0]["requestKey"], b.requests[1]["requestKey"])

    def test_http_unknown_write_only_queries_original_identity(self):
        b = HttpBackend(45, "a" * 64)
        replies = [EvaluationUnknown("CALLBACK_RECEIPT_UNKNOWN"), {"status": "COMPLETED", "operationKey": "k"}]
        with patch.object(b, "_request", side_effect=replies) as request:
            self.assertEqual(b.evaluate({"requestKey": "k"})["status"], "COMPLETED")
            self.assertEqual(request.call_args_list[0].args, ("/evaluations", {"requestKey": "k"}))
            self.assertEqual(request.call_args_list[1].args, ("/evaluations/k",))
        with patch.object(b, "_request", side_effect=EvaluationUnknown("CALLBACK_RECEIPT_UNKNOWN")) as request:
            with self.assertRaises(EvaluationUnknown):
                b.evaluate({"requestKey": "k"})
            self.assertEqual(request.call_count, 2)

    def test_context_is_detached_from_callers_and_version_mismatch_fails(self):
        c = context()
        frozen = validate_context(c)
        c["validation"][0]["expected"] = "changed"
        self.assertEqual(frozen["validation"][0]["expected"], "BLUE")
        with patch("importlib.metadata.version", return_value="0.2.0"), self.assertRaises(ContractError):
            search(context(), FakeBackend())


if __name__ == "__main__":
    unittest.main(verbosity=2)
