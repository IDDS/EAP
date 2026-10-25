"""Metadata-only accounting for a bounded OBI experiment; never an EAP importer."""
from collections import Counter
import math
import statistics


def flatten(packets):
    """Reject oversized or malformed input rather than infer missing spans."""
    if not isinstance(packets, list) or len(packets) > 2048:
        raise ValueError("invalid packet batch")
    result, seen = [], set()
    for packet in packets:
        for resource in packet.get("resourceSpans", []):
            for scope in resource.get("scopeSpans", []):
                for span in scope.get("spans", []):
                    key = (span.get("traceId"), span.get("spanId"))
                    if not all(isinstance(v, str) and v for v in key):
                        raise ValueError("missing span identity")
                    if key in seen:
                        raise ValueError("duplicate span identity")
                    seen.add(key)
                    attrs = {}
                    for item in span.get("attributes", []):
                        if item["key"] in attrs:
                            raise ValueError("duplicate attribute")
                        attrs[item["key"]] = next(iter(item["value"].values()), None)
                    # No URL, message, body, authorization or raw request id leaves this view.
                    allowed = {k: attrs[k] for k in [
                        "http.request.method", "http.response.status_code", "rpc.method",
                        "gen_ai.operation.name", "gen_ai.tool.name", "mcp.method.name", "error.type"]
                               if k in attrs}
                    result.append({"kind": span.get("kind"), "status": span.get("status", {}).get("code", 0),
                                   "attributes": allowed})
                    if len(result) > 10000:
                        raise ValueError("too many spans")
    return result


def latency(values):
    if not values or any(type(v) not in (int, float) or not math.isfinite(v) or v < 0 for v in values):
        raise ValueError("invalid latency samples")
    ordered = sorted(values)
    return {"samples": len(values), "medianMs": statistics.median(values),
            "p95Ms": ordered[math.ceil(len(values) * .95) - 1]}


def visibility(expected, observed, positive_control, *, same_node=None):
    if type(expected) is not int or expected <= 0 or type(observed) is not int or observed < 0:
        raise ValueError("invalid denominator")
    # A failed observer is not evidence of sandbox invisibility.
    if not positive_control:
        return {"verdict": "INCONCLUSIVE_OBSERVER", "expected": expected, "observed": observed}
    if same_node is not True:
        return {"verdict": "INCONCLUSIVE_PLACEMENT", "expected": expected, "observed": observed}
    if observed > expected:
        return {"verdict": "INCONCLUSIVE_DUPLICATION_OR_SCOPE", "expected": expected, "observed": observed}
    return {"verdict": "VISIBLE" if observed == expected else "VISIBILITY_GAP",
            "expected": expected, "observed": observed, "fraction": observed / expected,
            "taskIdentityVerified": False, "toolEffectsVerified": False}


def counters(spans):
    return {"spans": len(spans), "kinds": dict(Counter(str(s["kind"]) for s in spans)),
            "serverSpans": sum(s["kind"] in (2, "SPAN_KIND_SERVER") for s in spans),
            "internalSpans": sum(s["kind"] in (1, "SPAN_KIND_INTERNAL") for s in spans),
            "statuses": dict(Counter(str(s["status"]) for s in spans)),
            "mcpMethods": dict(Counter(str(s["attributes"]["mcp.method.name"])
                                      for s in spans if "mcp.method.name" in s["attributes"])),
            "httpStatuses": dict(Counter(str(s["attributes"]["http.response.status_code"])
                                        for s in spans if "http.response.status_code" in s["attributes"])),
            "toolNames": dict(Counter(str(s["attributes"]["gen_ai.tool.name"])
                                     for s in spans if "gen_ai.tool.name" in s["attributes"]))}
