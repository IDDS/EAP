"""Bounded, same-node experiment accounting. Not a production telemetry importer."""
from summarize import counters, flatten, visibility


PHASES = ("before", "task", "after")
PLACEMENT_FIELDS = ("workerName", "workerUid", "namespace", "pool", "podName", "podUid", "nodeName")


def split_windows(packets, windows):
    """Classify by span timestamps, never by the arrival time of a delayed export."""
    flatten(packets)  # Validate global uniqueness before splitting into windows.
    if set(windows) != set(PHASES):
        raise ValueError("missing observation window")
    previous = 0
    for name in PHASES:
        start, end = windows[name]["startNs"], windows[name]["endNs"]
        if type(start) is not int or type(end) is not int or not previous < start < end:
            raise ValueError("invalid or overlapping observation windows")
        previous = end
    groups = {name: [] for name in PHASES}
    outside = 0
    for packet in packets:
        for resource in packet.get("resourceSpans", []):
            for scope in resource.get("scopeSpans", []):
                for span in scope.get("spans", []):
                    try:
                        start, end = (int(span[key]) for key in ("startTimeUnixNano", "endTimeUnixNano"))
                    except (KeyError, ValueError, TypeError):
                        raise ValueError("missing span timestamps") from None
                    if start <= 0 or end < start:
                        raise ValueError("invalid span timestamps")
                    matching = [name for name, window in windows.items()
                                if window["startNs"] <= start <= end <= window["endNs"]]
                    if not matching:
                        outside += 1
                        continue
                    groups[matching[0]].append({"resourceSpans": [{"scopeSpans": [{"spans": [span]}]}]})
    return {name: counters(flatten(group)) for name, group in groups.items()}, outside


def placement_matches(before, after, node):
    def identity(snapshot):
        if (snapshot.get("status") != "ASSIGNED" or snapshot.get("source") != "SUBSTRATE_CONTROL_API"
                or snapshot.get("actorState") != "RUNNING" or not snapshot.get("actorUid")
                or not snapshot.get("instanceKey") or type(snapshot.get("actorVersion")) is not int
                or snapshot["actorVersion"] <= 0):
            return None
        placement = snapshot.get("placement") or {}
        if any(not isinstance(placement.get(k), str) or not placement[k] for k in PLACEMENT_FIELDS):
            return None
        if placement["nodeName"] != node:
            return None
        return snapshot["instanceKey"], snapshot["actorUid"], {k: placement[k] for k in PLACEMENT_FIELDS}
    first = identity(before)
    return first is not None and first == identity(after)


def analyze(packets, windows, controls, placements, observer, expected=8):
    counts, outside = split_windows(packets, windows)
    healthy = (observer.get("uninterrupted") is True and observer.get("exitCode") == 0
               and type(observer.get("restarts")) is int and observer["restarts"] == 0
               and isinstance(observer.get("podUid"), str) and bool(observer["podUid"])
               and isinstance(observer.get("nodeName"), str) and bool(observer["nodeName"]))
    for phase in ("before", "after"):
        control = controls.get(phase, {})
        total = control.get("httpRequests")
        healthy = (healthy and type(total) is int and total > 0
                   and counts[phase]["serverSpans"] == total
                   and control.get("nodeName") == observer.get("nodeName")
                   and control.get("passed") is True)
    matched = placement_matches(placements.get("before", {}), placements.get("after", {}),
                                observer.get("nodeName"))
    verdict = visibility(expected, counts["task"]["serverSpans"], healthy, same_node=matched)
    if outside:
        verdict = {"verdict": "INCONCLUSIVE_WINDOW_SCOPE", "expected": expected,
                   "observed": counts["task"]["serverSpans"]}
    return {"verdict": verdict, "counts": counts, "outsideWindowSpans": outside,
            "beforeAndAfterPositiveControls": bool(healthy), "sameNodeBoundaryEvidence": matched,
            "continuousPlacementProven": False, "taskIdentityVerified": False,
            "toolEffectsVerified": False, "productionSupportProven": False}
