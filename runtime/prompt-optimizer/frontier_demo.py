"""Public synthetic examples only. Run remotely; never invokes an evaluator."""
import json

from candidate_frontier import CandidateRegistry, digest


def protocol(**overrides):
    value = {"protocolDigest": digest({"fixture": "PUBLIC_SYNTHETIC_V1", "scorer": "EXACT_BINARY_V1"}),
             "batchDigest": digest(["public-1", "public-2", "public-3", "public-4", "public-5"]),
             "sampleIds": [f"public-{i}" for i in range(1, 6)],
             "roles": ["extractor", "classifier"], "maxCandidates": 8,
             "maxAttempts": 12, "maxGeneration": 2, "minimumQualityBps": 10000}
    value.update(overrides)
    return value


def configuration(extractor="Extract public facts.", classifier="Classify public facts."):
    return {"rolePrompts": {"extractor": extractor, "classifier": classifier}}


def receipt(registry, candidate_id, total_tokens=1000, total_latency=1200, passed=5):
    p = registry.protocol
    rows = [{"sampleId": sid, "status": "SUCCEEDED", "score": int(i < passed),
             "inputTokens": total_tokens // 5, "outputTokens": 0,
             "usageSource": "PROVIDER_REPORTED", "latencyMs": total_latency // 5}
            for i, sid in enumerate(p["sampleIds"])]
    return {"protocolDigest": p["protocolDigest"], "batchDigest": p["batchDigest"],
            "candidateId": candidate_id, "split": "VALIDATION", "results": rows}


def demo():
    registry = CandidateRegistry(protocol(), configuration())
    ids = {"baseline": registry.baseline_id}
    registry.observe("eval-baseline", ids["baseline"], receipt(registry, ids["baseline"]))
    values = [("A", 800, 1500, 5), ("B", 1200, 900, 5),
              ("C", 500, 600, 4), ("D", 1000, 1000, 5), ("E", 1400, 1600, 5)]
    for name, tokens, latency, passed in values:
        candidate = registry.register("proposal-" + name, registry.baseline_id,
                                      configuration(extractor="Public variant " + name))
        ids[name] = candidate.candidate_id
        result = receipt(registry, candidate.candidate_id, tokens, latency, passed)
        if name == "D":
            result["results"][0]["inputTokens"] = None
        registry.observe("eval-" + name, candidate.candidate_id, result)
    report = registry.assess()
    reverse = {cid: name for name, cid in ids.items()}
    report["namedDecisions"] = {name: report["decisions"][cid] for name, cid in ids.items()}
    report["namedFrontier"] = sorted(reverse[cid] for cid in report["frontier"])
    report["evidenceKind"] = "PUBLIC_SYNTHETIC_NOT_MODEL_OR_PRODUCTION"
    report["actualModelCalls"] = report["actualToolCalls"] = 0
    return report


if __name__ == "__main__":
    print(json.dumps(demo(), ensure_ascii=False, indent=2))
