"""Bounded GEPA adapter. EAP owns authorization, persistent intents and actual cost.

No provider client, credentials on disk, generated code, holdout data or automatic
adoption. Importing this module never starts or resumes a job.
"""
from __future__ import annotations

import hashlib
import functools
import importlib.metadata
import json
import time
import unicodedata
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Protocol

ENGINE_VERSION = "gepa-0.1.4"
MAX_PROMPT_BYTES = 8000
MAX_RESPONSE_BYTES = 131072


class ContractError(Exception):
    """Messages are stable reason codes, never private response/credential text."""


class SearchLimit(Exception):
    pass


class EvaluationUnknown(ContractError):
    pass


def guarded(function):
    @functools.wraps(function)
    def call(self, *args, **kwargs):
        if self.failure is not None:
            raise self.failure
        try:
            return function(self, *args, **kwargs)
        except ContractError as error:
            # GEPA may catch proposal errors and retry. Keep the first failure
            # latched so its fallback cannot issue a fresh uncertain operation.
            self.failure = error
            raise
    return call


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def prompt(candidate: Any) -> str:
    if not isinstance(candidate, dict) or set(candidate) != {"system_prompt"}:
        raise ContractError("CANDIDATE_FIELDS_REJECTED")
    value = candidate["system_prompt"]
    if not isinstance(value, str) or not value.strip() or len(value.encode("utf-8")) > MAX_PROMPT_BYTES:
        raise ContractError("CANDIDATE_PROMPT_REJECTED")
    return value


def normalized(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split())


def validate_context(value: Any) -> dict:
    # Exact allowlist prevents accidentally forwarding a nested holdout field.
    fields = {"runId", "engine", "engineVersion", "taskMode", "maxMetricCalls",
              "maxReflections", "baselinePrompt", "train", "validation"}
    if not isinstance(value, dict) or set(value) != fields:
        raise ContractError("CONTEXT_FIELDS_REJECTED")
    if type(value["runId"]) is not int or value["runId"] < 1:
        raise ContractError("RUN_ID_REJECTED")
    if value["engine"] not in {"GEPA", "SIMPLE"} or value["engineVersion"] != ENGINE_VERSION:
        raise ContractError("ENGINE_VERSION_REJECTED")
    if value["taskMode"] != "STATELESS_PROMPT_V1":
        raise ContractError("TASK_MODE_REJECTED")
    if type(value["maxMetricCalls"]) is not int or not 2 <= value["maxMetricCalls"] <= 16:
        raise ContractError("METRIC_LIMIT_REJECTED")
    if type(value["maxReflections"]) is not int or not 0 <= value["maxReflections"] <= 2:
        raise ContractError("REFLECTION_LIMIT_REJECTED")
    prompt({"system_prompt": value["baselinePrompt"]})
    ids, inputs, groups = set(), set(), {}
    for split in ("train", "validation"):
        samples = value[split]
        if not isinstance(samples, list) or not 1 <= len(samples) <= 3:
            raise ContractError("SAMPLE_COUNT_REJECTED")
        for sample in samples:
            if not isinstance(sample, dict) or set(sample) != {"id", "groupId", "input", "expected", "split"}:
                raise ContractError("SAMPLE_FIELDS_REJECTED")
            if sample["split"] != split.upper():
                raise ContractError("SAMPLE_SPLIT_REJECTED")
            for key in ("id", "groupId", "input", "expected"):
                if not isinstance(sample[key], str) or not sample[key].strip() or len(sample[key].encode("utf-8")) > (64 if key in ("id", "groupId") else 2000):
                    raise ContractError("SAMPLE_VALUE_REJECTED")
            if sample["id"] in ids or normalized(sample["input"]) in inputs:
                raise ContractError("SAMPLE_OVERLAP_REJECTED")
            if sample["groupId"] in groups and groups[sample["groupId"]] != split:
                raise ContractError("GROUP_OVERLAP_REJECTED")
            ids.add(sample["id"])
            inputs.add(normalized(sample["input"]))
            groups[sample["groupId"]] = split
    if value["maxMetricCalls"] < len(value["validation"]):
        raise ContractError("INITIAL_VALIDATION_LIMIT_REJECTED")
    # Detach the frozen data from caller-owned mutable references.
    return json.loads(canonical(value))


class Backend(Protocol):
    def evaluate(self, request: dict) -> dict: ...
    def reflect(self, request: dict) -> dict: ...


class HttpBackend:
    """Fixed internal target; unknown writes are followed only by original-ID reads.

    Claim is run-scoped and ephemeral. This client does not prove the backend
    authorization contract; that must be tested separately before enabling T3.
    """
    def __init__(self, run_id: int, claim: str, timeout: float = 900):
        if type(run_id) is not int or run_id < 1 or not isinstance(claim, str) or not 32 <= len(claim) <= 128 or not claim.isascii() or not claim.isalnum():
            raise ContractError("CLAIM_FORMAT_REJECTED")
        self._base = f"http://backend.eap.svc:8080/internal/agent-optimizations/{run_id}"
        self._claim = claim
        self._deadline = time.monotonic() + min(max(timeout, 1), 900)

    def _request(self, path: str, body: dict | None = None) -> dict:
        remaining = self._deadline - time.monotonic()
        if remaining <= 0:
            raise EvaluationUnknown("WORKER_DEADLINE_EXCEEDED")
        raw = None if body is None else canonical(body).encode("utf-8")
        request = urllib.request.Request(self._base + path, raw, {
            "Content-Type": "application/json", "X-EAP-Optimization-Claim": self._claim,
        }, method="GET" if raw is None else "POST")
        # Reject redirects instead of sending claim/data to a new target.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        try:
            with urllib.request.build_opener(NoRedirect).open(request, timeout=min(20, remaining)) as response:
                data = response.read(MAX_RESPONSE_BYTES + 1)
                if len(data) > MAX_RESPONSE_BYTES:
                    raise EvaluationUnknown("CALLBACK_RESPONSE_TOO_LARGE")
                value = json.loads(data)
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                raise ContractError("CLAIM_REJECTED") from None
            raise EvaluationUnknown("CALLBACK_RECEIPT_UNKNOWN") from None
        except (OSError, ValueError):
            raise EvaluationUnknown("CALLBACK_RECEIPT_UNKNOWN") from None
        if not isinstance(value, dict) or value.get("success") is not True or not isinstance(value.get("data"), dict):
            raise EvaluationUnknown("CALLBACK_CONTRACT_UNKNOWN")
        return value["data"]

    def context(self) -> dict:
        return validate_context(self._request("/context"))

    def _operation(self, path: str, key: str, body: dict) -> dict:
        try:
            result = self._request(path, body)
        except EvaluationUnknown:
            # Never resend a POST. A missing read cannot establish no effect.
            result = self._request(path + "/" + key)
        while result.get("status") in {"QUEUED", "RUNNING", "DISPATCHED"}:
            if time.monotonic() >= self._deadline:
                raise EvaluationUnknown("WORKER_DEADLINE_EXCEEDED")
            time.sleep(min(.5, max(0, self._deadline - time.monotonic())))
            result = self._request(path + "/" + key)
        return result

    def evaluate(self, request: dict) -> dict:
        return self._operation("/evaluations", request["requestKey"], request)

    def reflect(self, request: dict) -> dict:
        return self._operation("/reflections", str(request["ordinal"]), request)


@dataclass
class SearchResult:
    candidate: dict
    candidate_digest: str
    validation_score: float
    metric_calls: int
    reflection_intents: int
    engine_version: str
    reason_code: str


class QuietLogger:
    def log(self, message: str):
        pass


class EapAdapter:
    def __init__(self, context: dict, backend: Backend):
        self.context = validate_context(context)
        self.backend = backend
        self.metric_calls = 0
        self.reflection_intents = 0
        self.failure: ContractError | None = None
        self.samples = {s["id"]: s for split in ("train", "validation") for s in self.context[split]}
        self.full_validation: dict[str, tuple[dict, float, int | None]] = {}
        self.evidence_batches: list[list[dict]] = []

    @guarded
    def evaluate(self, batch, candidate, capture_traces=False):
        from gepa.core.adapter import EvaluationBatch
        prompt(candidate)
        if not isinstance(batch, list) or not batch or len(batch) > 3 or any(not isinstance(s, dict) or not isinstance(s.get("id"), str) for s in batch) or len({s["id"] for s in batch}) != len(batch):
            raise ContractError("EVALUATION_BATCH_REJECTED")
        if any(self.samples.get(s["id"]) != s for s in batch):
            raise ContractError("EVALUATION_SAMPLE_REJECTED")
        splits = {s["split"] for s in batch}
        if len(splits) != 1:
            raise ContractError("EVALUATION_SPLIT_REJECTED")
        if self.metric_calls + len(batch) > self.context["maxMetricCalls"]:
            raise SearchLimit("METRIC_LIMIT_REACHED")
        split = next(iter(splits))
        ids = sorted(s["id"] for s in batch)
        key = digest([self.context["runId"], digest(candidate), split, ids])
        result = self.backend.evaluate({"candidate": candidate, "split": split, "sampleIds": ids, "requestKey": key})
        self.metric_calls += len(batch)
        if result.get("operationKey") != key or not isinstance(result.get("results"), list):
            raise EvaluationUnknown("EVALUATION_RECEIPT_MISMATCH")
        rows = result["results"]
        if len(rows) != len(batch) or any(not isinstance(r, dict) for r in rows):
            raise EvaluationUnknown("EVALUATION_RECEIPT_INCOMPLETE")
        if len({r.get("sampleId") for r in rows}) != len(rows) or {r.get("sampleId") for r in rows} != set(ids):
            raise EvaluationUnknown("EVALUATION_SAMPLE_MISMATCH")
        by_id = {r["sampleId"]: r for r in rows}
        outputs, scores, traces = [], [], []
        unknown = result.get("status") == "UNKNOWN"
        cost = 0
        known_cost = True
        for sample in batch:
            row = by_id[sample["id"]]
            status = row.get("status")
            if status not in {"SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN"}:
                raise EvaluationUnknown("EVALUATION_STATUS_UNKNOWN")
            score = row.get("score")
            if status == "SUCCEEDED" and (type(score) is not int or score not in (0, 1)):
                raise EvaluationUnknown("EVALUATION_SCORE_UNKNOWN")
            score = float(score) if status == "SUCCEEDED" else 0.0
            scores.append(score)
            output = row.get("output", "")
            if not isinstance(output, str) or len(output.encode("utf-8")) > 16384:
                raise EvaluationUnknown("EVALUATION_OUTPUT_UNKNOWN")
            outputs.append(output)
            usage = row.get("usageSource") == "PROVIDER_REPORTED" and all(type(row.get(k)) is int and row[k] >= 0 for k in ("inputTokens", "outputTokens"))
            if usage:
                cost += row["inputTokens"] + row["outputTokens"]
            else:
                known_cost = False
            unknown |= status == "UNKNOWN" or status == "SUCCEEDED" and not usage
            traces.append({"sampleId": sample["id"], "input": sample["input"],
                           "expected": sample["expected"], "output": output, "status": status,
                           "score": score, "traceId": row.get("traceId"), "usageKnown": usage})
        # Retain all registered results, including failure/UNKNOWN as zero.
        self.evidence_batches.append(traces)
        if unknown or result.get("status") not in {"COMPLETED", "FAILED"}:
            raise EvaluationUnknown("EVALUATION_EVIDENCE_UNKNOWN")
        val_ids = {s["id"] for s in self.context["validation"]}
        if split == "VALIDATION" and set(ids) == val_ids:
            self.full_validation[digest(candidate)] = (dict(candidate), sum(scores) / len(batch), cost if known_cost else None)
        return EvaluationBatch(outputs=outputs, scores=scores,
                               trajectories=traces if capture_traces else None,
                               num_metric_calls=len(batch))

    @guarded
    def make_reflective_dataset(self, candidate, eval_batch, components_to_update):
        prompt(candidate)
        if components_to_update != ["system_prompt"] or not eval_batch.trajectories:
            raise ContractError("REFLECTION_COMPONENT_REJECTED")
        if any(t["sampleId"] not in {s["id"] for s in self.context["train"]} for t in eval_batch.trajectories):
            raise ContractError("REFLECTION_DATA_REJECTED")
        return {"system_prompt": eval_batch.trajectories}

    @guarded
    def propose_new_texts(self, candidate, reflective_dataset, components_to_update, *, metadata=None):
        prompt(candidate)
        if components_to_update != ["system_prompt"] or set(reflective_dataset) != {"system_prompt"}:
            raise ContractError("REFLECTION_COMPONENT_REJECTED")
        approved = {s["id"]: s for s in self.context["train"]}
        traces = reflective_dataset["system_prompt"]
        if not isinstance(traces, list) or not traces or any(not isinstance(t, dict) or not isinstance(t.get("sampleId"), str) or t.get("sampleId") not in approved or t.get("input") != approved[t["sampleId"]]["input"] or t.get("expected") != approved[t["sampleId"]]["expected"] for t in traces):
            raise ContractError("REFLECTION_DATA_REJECTED")
        if self.reflection_intents >= self.context["maxReflections"]:
            raise SearchLimit("REFLECTION_LIMIT_REACHED")
        ordinal = self.reflection_intents
        self.reflection_intents += 1
        result = self.backend.reflect({"ordinal": ordinal, "candidate": candidate, "feedback": traces})
        if result.get("status") != "COMPLETED":
            raise EvaluationUnknown("REFLECTION_EVIDENCE_UNKNOWN")
        proposal = result.get("candidate")
        prompt(proposal)
        return proposal

    def best(self, reason: str) -> SearchResult:
        if not self.full_validation:
            raise EvaluationUnknown("NO_COMPLETE_VALIDATION")
        # Ties prefer known lower observed cost; never rank an incomplete subset.
        key, (candidate, score, _) = max(self.full_validation.items(),
            key=lambda item: (item[1][1], -(item[1][2] if item[1][2] is not None else float("inf"))))
        return SearchResult(candidate, key, score, self.metric_calls, self.reflection_intents, ENGINE_VERSION, reason)


def search(context: dict, backend: Backend) -> SearchResult:
    if importlib.metadata.version("gepa") != "0.1.4":
        raise ContractError("INSTALLED_ENGINE_VERSION_REJECTED")
    import gepa
    adapter = EapAdapter(context, backend)
    seed = {"system_prompt": adapter.context["baselinePrompt"]}
    reason = "SEARCH_COMPLETED"
    try:
        if adapter.context["engine"] == "SIMPLE":
            candidates = [seed,
                {"system_prompt": seed["system_prompt"] + "\nReturn only the requested final answer. Do not add commentary."},
                {"system_prompt": seed["system_prompt"] + "\nFollow the user's requested output format exactly."}]
            for candidate in candidates:
                adapter.evaluate(adapter.context["validation"], candidate)
        else:
            gepa.optimize(seed_candidate=seed, trainset=adapter.context["train"],
                valset=adapter.context["validation"], adapter=adapter,
                reflection_lm=None, max_metric_calls=adapter.context["maxMetricCalls"],
                reflection_minibatch_size=1,
                # Seed validation(3) + original/proposed training(1+1) + full proposed validation(3) fits the eight-sample search cap.
                # List loaders use overlapping numeric IDs for train/validation.
                # Only EAP's split-aware persistent cache may reuse an intent.
                skip_perfect_score=False, use_merge=False, cache_evaluation=False,
                stop_callbacks=[lambda state: adapter.failure is not None or
                    adapter.reflection_intents >= adapter.context["maxReflections"] or
                    adapter.metric_calls >= adapter.context["maxMetricCalls"]],
                seed=0, logger=QuietLogger(), run_dir=None, display_progress_bar=False,
                use_cloudpickle=False, use_wandb=False, use_mlflow=False,
                track_best_outputs=False, raise_on_exception=True)
    except SearchLimit:
        reason = "LOCAL_SEARCH_LIMIT_REACHED"
    if adapter.failure is not None:
        raise adapter.failure
    # Unknown evaluations propagate; do not disguise them as a successful search.
    return adapter.best(reason)
