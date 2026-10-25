"""Offline candidate contracts; no evaluator, model, tool, network or file access.

Inputs are supplied by an evaluator adapter. This library cannot authenticate
receipts. The first consumer is an explicitly synthetic, zero-model validation.
It does not change GEPA selection, seal a candidate or authorize deployment.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from fractions import Fraction


class CandidateError(ValueError):
    """Fixed reason codes; never include rejected prompt or receipt text."""


def canonical(value):
    try:
        encoded = json.dumps(value, sort_keys=True, separators=(",", ":"),
                             ensure_ascii=False, allow_nan=False)
        encoded.encode("utf-8")
        return encoded
    except (TypeError, ValueError, RecursionError, UnicodeError):
        raise CandidateError("JSON_CONTRACT_REJECTED") from None


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def identifier(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}", value)


def integer(value, minimum, maximum):
    return type(value) is int and minimum <= value <= maximum


@dataclass(frozen=True)
class Candidate:
    candidate_id: str
    parent_id: str | None
    generation: int
    changed_role: str | None
    configuration_json: str

    def configuration(self):
        return json.loads(self.configuration_json)


@dataclass(frozen=True)
class Metrics:
    denominator: int
    passed: int
    failed: int
    unknown: int
    not_run: int
    total_tokens: int | None
    total_latency_ms: int | None
    reason_code: str

    @property
    def quality(self):
        return Fraction(self.passed, self.denominator)


class CandidateRegistry:
    """Bounded, process-local ledger for the offline validation stage.

    One original receipt per candidate; even an UNKNOWN receipt is immutable.
    Repeated operation keys are reads. New keys cannot replace old evaluations.
    Durable platform intents/authorization remain the backend's responsibility.
    """
    def __init__(self, protocol, baseline):
        fields = {"protocolDigest", "batchDigest", "sampleIds", "roles",
                  "maxCandidates", "maxAttempts", "maxGeneration", "minimumQualityBps"}
        if not isinstance(protocol, dict) or set(protocol) != fields:
            raise CandidateError("PROTOCOL_FIELDS_REJECTED")
        for key in ("protocolDigest", "batchDigest"):
            if not isinstance(protocol[key], str) or not re.fullmatch(r"[0-9a-f]{64}", protocol[key]):
                raise CandidateError("PROTOCOL_DIGEST_REJECTED")
        for key, maximum in (("sampleIds", 100), ("roles", 8)):
            values = protocol[key]
            if (not isinstance(values, list) or not 1 <= len(values) <= maximum
                    or any(not identifier(v) for v in values) or len(set(values)) != len(values)):
                raise CandidateError("PROTOCOL_IDENTITIES_REJECTED")
        for key, low, high in (("maxCandidates", 1, 32), ("maxAttempts", 1, 128),
                               ("maxGeneration", 0, 8), ("minimumQualityBps", 0, 10000)):
            if not integer(protocol[key], low, high):
                raise CandidateError("PROTOCOL_LIMIT_REJECTED")
        self._protocol_json = canonical(protocol)
        self._candidates = {}
        self._attempts = {}
        self._observations = {}
        self._receipts = {}
        configuration = self._configuration(baseline)
        seed = self._candidate(configuration, None, 0, None)
        self._candidates[seed.candidate_id] = seed
        self.baseline_id = seed.candidate_id

    @property
    def protocol(self):
        return json.loads(self._protocol_json)

    @property
    def attempts(self):
        return len(self._attempts)

    def _configuration(self, value):
        if not isinstance(value, dict) or set(value) != {"rolePrompts"}:
            raise CandidateError("CANDIDATE_FIELDS_REJECTED")
        prompts = value["rolePrompts"]
        if not isinstance(prompts, dict) or set(prompts) != set(self.protocol["roles"]):
            raise CandidateError("CANDIDATE_ROLES_REJECTED")
        if any(not isinstance(p, str) or not p.strip() or len(p.encode("utf-8")) > 8000
               for p in prompts.values()):
            raise CandidateError("CANDIDATE_PROMPT_REJECTED")
        if sum(len(p.encode("utf-8")) for p in prompts.values()) > 32000:
            raise CandidateError("CANDIDATE_SIZE_REJECTED")
        return canonical(value)

    def _candidate(self, configuration, parent, generation, role):
        candidate_id = digest([self.protocol["protocolDigest"], self.protocol["batchDigest"],
                               json.loads(configuration)])
        return Candidate(candidate_id, parent, generation, role, configuration)

    def candidates(self):
        return tuple(self._candidates[k] for k in sorted(self._candidates))

    def register(self, operation_key, parent_id, configuration):
        if not identifier(operation_key):
            raise CandidateError("OPERATION_KEY_REJECTED")
        fingerprint = digest([parent_id, configuration])
        if operation_key in self._attempts:
            old, candidate_id, reason = self._attempts[operation_key]
            if old != fingerprint:
                raise CandidateError("OPERATION_CONFLICT")
            if reason:
                raise CandidateError(reason)
            return self._candidates[candidate_id]
        if self.attempts >= self.protocol["maxAttempts"]:
            raise CandidateError("ATTEMPT_LIMIT_REACHED")
        # Failed proposals also consume the finite attempt allowance. Retain
        # only their fingerprint/reason, never their rejected fields or text.
        try:
            if not isinstance(parent_id, str) or parent_id not in self._candidates:
                raise CandidateError("PARENT_UNCONFIRMED")
            encoded = self._configuration(configuration)
            parent = self._candidates[parent_id]
            prompts = json.loads(encoded)["rolePrompts"]
            original = parent.configuration()["rolePrompts"]
            changed = [r for r in sorted(prompts) if prompts[r] != original[r]]
            if len(changed) != 1:
                raise CandidateError("SINGLE_ROLE_CHANGE_REQUIRED")
            if parent.generation >= self.protocol["maxGeneration"]:
                raise CandidateError("GENERATION_LIMIT_REACHED")
            candidate = self._candidate(encoded, parent_id, parent.generation + 1, changed[0])
            if candidate.candidate_id in self._candidates:
                raise CandidateError("CANDIDATE_ALREADY_REGISTERED")
            if len(self._candidates) >= self.protocol["maxCandidates"]:
                raise CandidateError("CANDIDATE_LIMIT_REACHED")
        except CandidateError as error:
            self._attempts[operation_key] = (fingerprint, None, str(error))
            raise
        self._candidates[candidate.candidate_id] = candidate
        self._attempts[operation_key] = (fingerprint, candidate.candidate_id, None)
        return candidate

    def observe(self, operation_key, candidate_id, receipt):
        if (not identifier(operation_key) or not isinstance(candidate_id, str)
                or candidate_id not in self._candidates):
            raise CandidateError("OBSERVATION_ID_REJECTED")
        fingerprint = digest([candidate_id, receipt])
        if operation_key in self._observations:
            old, old_candidate = self._observations[operation_key]
            if old != fingerprint or old_candidate != candidate_id:
                raise CandidateError("OBSERVATION_CONFLICT")
            return self._receipts[candidate_id]
        if candidate_id in self._receipts:
            raise CandidateError("ORIGINAL_OBSERVATION_REQUIRED")
        metrics = self._metrics(candidate_id, receipt)
        self._observations[operation_key] = (fingerprint, candidate_id)
        self._receipts[candidate_id] = metrics
        return metrics

    def _metrics(self, candidate_id, receipt):
        protocol = self.protocol
        fields = {"protocolDigest", "batchDigest", "candidateId", "split", "results"}
        if not isinstance(receipt, dict) or set(receipt) != fields:
            raise CandidateError("RECEIPT_FIELDS_REJECTED")
        if (receipt["protocolDigest"] != protocol["protocolDigest"]
                or receipt["batchDigest"] != protocol["batchDigest"]
                or receipt["candidateId"] != candidate_id or receipt["split"] != "VALIDATION"):
            raise CandidateError("RECEIPT_SCOPE_REJECTED")
        rows = receipt["results"]
        if not isinstance(rows, list) or len(rows) > len(protocol["sampleIds"]):
            raise CandidateError("RECEIPT_ROWS_REJECTED")
        seen = set()
        passed = failed = unknown = not_run = tokens = latency = 0
        usage_known = latency_known = True
        for row in rows:
            if not isinstance(row, dict) or set(row) != {"sampleId", "status", "score", "inputTokens", "outputTokens", "usageSource", "latencyMs"}:
                raise CandidateError("RESULT_FIELDS_REJECTED")
            sid = row["sampleId"]
            if not identifier(sid) or sid not in protocol["sampleIds"] or sid in seen:
                raise CandidateError("RESULT_SAMPLE_REJECTED")
            seen.add(sid)
            status = row["status"]
            if status not in ("SUCCEEDED", "FAILED", "UNKNOWN", "NOT_RUN"):
                raise CandidateError("RESULT_STATUS_REJECTED")
            if not integer(row["score"], 0, 1):
                raise CandidateError("RESULT_SCORE_REJECTED")
            passed += status == "SUCCEEDED" and row["score"] == 1
            failed += status == "FAILED" or status == "SUCCEEDED" and row["score"] == 0
            unknown += status == "UNKNOWN"
            not_run += status == "NOT_RUN"
            usage = row["usageSource"] == "PROVIDER_REPORTED" and all(
                integer(row[k], 0, 10**12) for k in ("inputTokens", "outputTokens"))
            timing = integer(row["latencyMs"], 0, 10**12)
            usage_known &= usage
            latency_known &= timing
            if usage:
                tokens += row["inputTokens"] + row["outputTokens"]
            if timing:
                latency += row["latencyMs"]
        not_run += len(protocol["sampleIds"]) - len(seen)
        # Missing rows and UNKNOWN never become free/fast candidates. Known
        # business failures remain in the registered denominator with score 0.
        complete = not unknown and not not_run and usage_known and latency_known
        return Metrics(len(protocol["sampleIds"]), passed, failed, unknown, not_run,
                       tokens if usage_known and not unknown and not not_run else None,
                       latency if latency_known and not unknown and not not_run else None,
                       "COMPLETE" if complete else "EVIDENCE_INCOMPLETE")

    def assess(self):
        baseline = self._receipts.get(self.baseline_id)
        confirmed = baseline is not None and baseline.reason_code == "COMPLETE"
        floor = Fraction(self.protocol["minimumQualityBps"], 10000)
        if confirmed:
            floor = max(floor, baseline.quality)
        decisions, eligible = {}, {}
        for candidate in self.candidates():
            cid = candidate.candidate_id
            metrics = self._receipts.get(cid)
            if metrics is None or metrics.reason_code != "COMPLETE":
                decisions[cid] = "EVIDENCE_INCOMPLETE"
            elif not confirmed:
                decisions[cid] = "BASELINE_UNCONFIRMED"
            elif metrics.quality < floor:
                decisions[cid] = "QUALITY_GATE_REJECTED"
            else:
                eligible[cid] = metrics
        for cid, metrics in eligible.items():
            def dominates(other):
                left = (other.quality, -other.total_tokens, -other.total_latency_ms)
                right = (metrics.quality, -metrics.total_tokens, -metrics.total_latency_ms)
                return all(a >= b for a, b in zip(left, right)) and any(a > b for a, b in zip(left, right))
            decisions[cid] = "DOMINATED" if any(dominates(v) for k, v in eligible.items() if k != cid) else "FRONTIER"
        return {"frontier": sorted(cid for cid, reason in decisions.items() if reason == "FRONTIER"),
                "decisions": {cid: decisions[cid] for cid in sorted(decisions)},
                "attempts": self.attempts, "registeredCandidates": len(self._candidates),
                "recommendation": "NO_ADOPTION_OFFLINE_ONLY"}
