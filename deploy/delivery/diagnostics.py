"""Bounded, read-only operations diagnostics. Never collect logs or Secret objects."""
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
from itertools import islice
import json
import os
from pathlib import Path
import re

from eapctl import DeliveryError, application_facts, kubectl

CONTRACT = "EAP_DIAGNOSTICS_V1"
MAX_ITEMS = 200
MAX_BYTES = 1024 * 1024
STATUSES = {"PASS", "WARN", "FAIL", "UNKNOWN"}
REASONS = {"CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "CreateContainerConfigError",
           "ContainerCreating", "PodInitializing", "OOMKilled", "Error", "Completed", "Evicted"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(data):
    return (json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def name(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", value):
        raise DeliveryError("资源名称无效")
    return value


def natural(value):
    if type(value) is not int or value < 0:
        raise DeliveryError("缺少有效数值")
    return value


def timestamp(value):
    if not isinstance(value, str):
        raise DeliveryError("缺少带时区时间")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise DeliveryError("时间必须带时区")
    return result


def quantity(value):
    """Kubernetes non-negative byte quantities; reject unknown formats instead of guessing."""
    if not isinstance(value, str) or len(value) > 64:
        raise DeliveryError("容量单位无效")
    match = re.fullmatch(r"(\d+(?:\.\d+)?)([EPTGMK]i|[EPTGMk]|m|[eE][+-]?\d+)?", value)
    if not match:
        raise DeliveryError("容量单位无效")
    number, suffix = Decimal(match[1]), match[2] or ""
    if suffix.endswith("i"):
        number *= Decimal(1024) ** ("KMGTPE".index(suffix[0]) + 1)
    elif suffix in "kMGTPE" and suffix:
        number *= Decimal(1000) ** ("kMGTPE".index(suffix) + 1)
    elif suffix == "m":
        number /= 1000
    elif suffix:
        exponent = int(suffix[1:])
        if abs(exponent) > 30:
            raise DeliveryError("容量超出范围")
        number *= Decimal(10) ** exponent
    if number > 2 ** 63:
        raise DeliveryError("容量超出范围")
    return number


def threshold(value, capacity):
    if isinstance(value, str) and re.fullmatch(r"\d+(?:\.\d+)?%", value):
        percent = Decimal(value[:-1])
        if percent > 100:
            raise DeliveryError("阈值无效")
        return Decimal(capacity) * percent / 100
    return quantity(value)


def filesystem(stats, hard, kind, now, reserve_bytes, max_age):
    capacity, available = natural(stats.get("capacityBytes")), natural(stats.get("availableBytes"))
    if capacity == 0 or available > capacity:
        raise DeliveryError("文件系统容量无效")
    age = (now - timestamp(stats.get("time"))).total_seconds()
    if age < -30 or age > max_age:
        raise DeliveryError("文件系统采样已过期")
    result = {"kind": kind, "capacityBytes": capacity, "availableBytes": available,
              "sampleAgeSeconds": round(age, 3), "status": "UNKNOWN"}
    # Do not infer default thresholds, filesystem aliases, or containerfs thresholds.
    key = kind + ".available"
    if key not in hard or kind == "containerfs":
        result["code"] = "EFFECTIVE_THRESHOLD_UNVERIFIED"
        return result
    margin = Decimal(available) - threshold(hard[key], capacity)
    result.update(marginBytes=int(margin), reserveBytes=reserve_bytes,
                  status="FAIL" if margin <= 0 else "WARN" if margin < reserve_bytes else "PASS")
    inodes, free = stats.get("inodes"), stats.get("inodesFree")
    inode_key = kind + ".inodesFree"
    if inode_key not in hard or inodes is None or free is None:
        result["inodeStatus"] = "UNKNOWN"
    else:
        natural(inodes); natural(free)
        if inodes == 0 or free > inodes:
            raise DeliveryError("inode统计无效")
        inode_margin = Decimal(free) - threshold(hard[inode_key], inodes)
        result.update(inodeMargin=int(inode_margin), inodeStatus="FAIL" if inode_margin <= 0 else "PASS")
    result["code"] = "DISK_HEADROOM"
    return result


def collect(release, namespace, *, reserve_bytes=1024 ** 3, max_age=120, read=kubectl, now=None):
    name(namespace)
    if len(namespace) > 63 or "." in namespace or not 0 < reserve_bytes <= 1024 ** 4 or not 5 <= max_age <= 3600:
        raise DeliveryError("诊断参数无效")
    now = now or datetime.now(timezone.utc)
    report = {"contract": CONTRACT, "namespace": namespace, "createdAt": now.isoformat(),
              "releaseSha256": sha(encoded(release)), "checks": [], "applications": {}, "nodes": {}, "volumes": [],
              "readOnly": True, "productionReady": False,
              "unverified": ["HTTP及真实登录", "数据库结构与业务记录", "身份服务与外部依赖", "运行池槽位与业务吞吐",
                             "PVC实际使用量", "软驱逐阈值与容器文件系统别名", "业务数据保留", "备份恢复与高可用"]}

    def check(key, status, code):
        report["checks"].append({"id": key, "status": status, "code": code})

    def get(key, args):
        try:
            value = read(namespace, args)
            if not isinstance(value, dict):
                raise DeliveryError("对象无效")
            return value
        except (DeliveryError, KeyError, TypeError, ValueError, OSError):
            check(key, "UNKNOWN", "READ_UNAVAILABLE_OR_INVALID")
            return None

    def items(key, path):
        value = get(key, ["get", "--raw", path + "?limit=" + str(MAX_ITEMS + 1)])
        if value is None:
            return None
        entries = value.get("items")
        if not isinstance(entries, list) or len(entries) > MAX_ITEMS or value.get("metadata", {}).get("continue"):
            check(key, "UNKNOWN", "LIST_INCOMPLETE_OR_OVER_LIMIT")
            return None
        return entries

    original_namespace = get("namespace", ["get", "namespace", namespace, "-o", "json"])
    pods = items("pods", f"/api/v1/namespaces/{namespace}/pods")
    replicas = items("replicasets", f"/apis/apps/v1/namespaces/{namespace}/replicasets")
    volumes = items("volumes", f"/api/v1/namespaces/{namespace}/persistentvolumeclaims")
    nodes = set()
    for component, expected in release["applications"].items():
        if component not in {"backend", "frontend"}:
            raise DeliveryError("应用范围无效")
        before = get(component, ["get", "deployment", component, "-o", "json"])
        if before is None:
            continue
        try:
            facts = application_facts(before, expected)
            # Keep no config, environment, annotations, labels, commands or free-form messages.
            report["applications"][component] = {k: facts[k] for k in ["ready", "imageMatches", "replicas"]}
            check(component + "/deployment", "PASS" if facts["ready"] and facts["imageMatches"] else "FAIL", "DEPLOYMENT_VERSION_READINESS")
            uid = before["metadata"]["uid"]
            owned_rs = {x["metadata"]["uid"] for x in replicas or [] if any(o.get("uid") == uid and o.get("kind") == "Deployment" and o.get("controller") is True for o in x["metadata"].get("ownerReferences", []))}
            owned = [p for p in pods or [] if any(o.get("uid") in owned_rs and o.get("kind") == "ReplicaSet" and o.get("controller") is True for o in p["metadata"].get("ownerReferences", []))]
            summary = []
            for pod in owned:
                status = pod.get("status", {})
                ready = any(c.get("type") == "Ready" and c.get("status") == "True" for c in status.get("conditions", []))
                containers = status.get("containerStatuses", [])
                exact = len(containers) == 1 and containers[0].get("name") == component and containers[0].get("ready") is True
                digest_match = exact and isinstance(containers[0].get("imageID"), str) and containers[0]["imageID"].endswith("@" + expected.split("@")[1])
                restarts = sum(natural(c.get("restartCount")) for c in containers)
                reasons = sorted({s.get("reason") if s.get("reason") in REASONS else "OTHER" for c in containers + status.get("initContainerStatuses", []) for k, s in c.get("state", {}).items() if k in {"waiting", "terminated"}})
                phase = status.get("phase")
                summary.append({"phase": phase if phase in {"Running", "Pending", "Succeeded", "Failed", "Unknown"} else "Unknown", "ready": ready,
                                "digestMatches": bool(digest_match), "restarts": restarts, "reasons": reasons,
                                "terminating": bool(pod["metadata"].get("deletionTimestamp"))})
                if pod.get("spec", {}).get("nodeName"):
                    nodes.add(name(pod["spec"]["nodeName"]))
            report["applications"][component]["pods"] = summary
            healthy = len(summary) == facts["replicas"] and len(summary) > 0 and all(x["phase"] == "Running" and x["ready"] and x["digestMatches"] and not x["terminating"] for x in summary)
            check(component + "/pods", "UNKNOWN" if pods is None or replicas is None else "PASS" if healthy else "FAIL", "OWNED_POD_VERSION_READINESS")
            if any(x["restarts"] for x in summary):
                check(component + "/restarts", "WARN", "CONTAINER_RESTARTS_OBSERVED")
            after = get(component + "/consistency", ["get", "deployment", component, "-o", "json"])
            if after is not None and (after["metadata"].get("uid"), after["metadata"].get("generation")) != (uid, before["metadata"].get("generation")):
                check(component + "/consistency", "UNKNOWN", "CHANGED_DURING_COLLECTION")
        except (DeliveryError, KeyError, TypeError, ValueError):
            check(component, "UNKNOWN", "APPLICATION_DATA_INVALID")
    for volume in volumes or []:
        try:
            volume_name = name(volume["metadata"]["name"])
            phase = volume.get("status", {}).get("phase")
            capacity = quantity(volume.get("status", {}).get("capacity", {}).get("storage", "0"))
            report["volumes"].append({"name": volume_name, "phase": phase if phase in {"Bound", "Pending", "Lost"} else "Unknown",
                                      "declaredCapacityBytes": int(capacity), "usageVerified": False})
            check("volume/" + volume_name, "PASS" if phase == "Bound" else "FAIL" if phase in {"Lost", "Pending"} else "UNKNOWN", "PVC_BINDING_ONLY")
        except (DeliveryError, KeyError, TypeError, ValueError, InvalidOperation):
            check("volumes", "UNKNOWN", "VOLUME_DATA_INVALID")
    if not nodes or len(nodes) > 16:
        check("nodes", "UNKNOWN", "NODE_SCOPE_EMPTY_OR_OVER_LIMIT")
        nodes = set()
    for node_name in sorted(nodes):
        node = get(node_name, ["get", "node", node_name, "-o", "json"])
        if node is not None:
            conditions = {c.get("type"): c.get("status") for c in node.get("status", {}).get("conditions", [])}
            for key in ["Ready", "MemoryPressure", "DiskPressure", "PIDPressure"]:
                value = conditions.get(key)
                check(node_name + "/" + key, "UNKNOWN" if value not in {"True", "False"} else "PASS" if value == ("True" if key == "Ready" else "False") else "FAIL", "NODE_CONDITION")
        summary = get(node_name + "/stats", ["get", "--raw", f"/api/v1/nodes/{node_name}/proxy/stats/summary"])
        config = get(node_name + "/thresholds", ["get", "--raw", f"/api/v1/nodes/{node_name}/proxy/configz"])
        if summary is None or config is None:
            continue
        try:
            node_stats, hard = summary["node"], config["kubeletconfig"].get("evictionHard", {})
            if node_stats.get("nodeName") != node_name:
                raise DeliveryError("节点统计身份不一致")
            fs = {"nodefs": node_stats.get("fs"), "imagefs": node_stats.get("runtime", {}).get("imageFs")}
            if node_stats.get("runtime", {}).get("containerFs"):
                fs["containerfs"] = node_stats["runtime"]["containerFs"]
            report["nodes"][node_name] = []
            for kind, stats in fs.items():
                try:
                    value = filesystem(stats or {}, hard, kind, now, reserve_bytes, max_age)
                    report["nodes"][node_name].append(value)
                    check(node_name + "/" + kind, value["status"], value["code"])
                    if "inodeStatus" in value:
                        check(node_name + "/" + kind + "/inodes", value["inodeStatus"], "INODE_HEADROOM")
                except (DeliveryError, KeyError, TypeError, ValueError, InvalidOperation):
                    check(node_name + "/" + kind, "UNKNOWN", "FILESYSTEM_DATA_STALE_OR_INVALID")
        except (DeliveryError, KeyError, TypeError, ValueError):
            check(node_name + "/stats", "UNKNOWN", "NODE_DATA_INVALID")
    final_namespace = get("namespace/consistency", ["get", "namespace", namespace, "-o", "json"])
    if original_namespace is not None and final_namespace is not None:
        original_uid = original_namespace.get("metadata", {}).get("uid")
        if not original_uid or original_uid != final_namespace.get("metadata", {}).get("uid"):
            check("namespace/consistency", "UNKNOWN", "CHANGED_DURING_COLLECTION")
    statuses = {c["status"] for c in report["checks"]}
    report["status"] = next(s for s in ["FAIL", "UNKNOWN", "WARN", "PASS"] if s in statuses)
    report["snapshotOnly"] = True
    return report


def write_bundle(report, directory):
    body = encoded(report)
    if report.get("contract") != CONTRACT or len(body) > MAX_BYTES:
        raise DeliveryError("诊断包无效或超限")
    directory = Path(directory)
    directory.mkdir(mode=0o700)  # Exclusive creation; never overwrite an older report.
    digest = sha(body)
    for filename, data in [("report.json", body), ("manifest.json", encoded({"contract": "EAP_DIAGNOSTIC_BUNDLE_V1", "reportSha256": digest}))]:
        fd = os.open(directory / filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
    return {"contract": "EAP_DIAGNOSTIC_BUNDLE_V1", "reportSha256": digest, "status": report["status"], "files": 2}


def retention_plan(directory, *, days=14, keep=3, now=None):
    """Only inspect our two-file bundles. No deletion, traversal or business data cleanup."""
    if type(days) is not int or not 1 <= days <= 3650 or type(keep) is not int or not 1 <= keep <= 100:
        raise DeliveryError("保留参数无效")
    root = Path(directory)
    if root.is_symlink() or not root.is_dir():
        raise DeliveryError("保留目录无效")
    entries = list(islice(root.iterdir(), 1001))
    if len(entries) > 1000:
        raise DeliveryError("诊断目录超过1000项，请分范围核对")
    now = now or datetime.now(timezone.utc)
    groups, skipped = {}, 0
    for entry in entries:
        try:
            if entry.is_symlink() or not entry.is_dir() or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", entry.name):
                raise DeliveryError("不是可管理诊断包")
            if {p.name for p in islice(entry.iterdir(), 3)} != {"report.json", "manifest.json"}:
                raise DeliveryError("包含其他文件")
            paths = [entry / n for n in ["report.json", "manifest.json"]]
            if any(p.is_symlink() or not p.is_file() or p.stat().st_size > MAX_BYTES for p in paths):
                raise DeliveryError("文件无效")
            body, manifest_body = (p.read_bytes() for p in paths)
            report, manifest = json.loads(body), json.loads(manifest_body)
            if manifest.get("contract") != "EAP_DIAGNOSTIC_BUNDLE_V1" or manifest.get("reportSha256") != sha(body) or report.get("contract") != CONTRACT:
                raise DeliveryError("诊断包摘要无效")
            created = timestamp(report["createdAt"])
            if created > now or report.get("status") not in STATUSES or report.get("readOnly") is not True:
                raise DeliveryError("诊断包元数据无效")
            namespace = name(report["namespace"])
            item = {"directory": entry.name, "createdAt": created.isoformat(), "reportSha256": sha(body),
                    "manifestSha256": sha(manifest_body), "bytes": len(body) + len(manifest_body)}
            groups.setdefault(namespace, []).append((created, item))
        except (DeliveryError, OSError, KeyError, ValueError, TypeError):
            skipped += 1
    candidates, protected = [], 0
    for namespace, values in groups.items():
        for index, (created, item) in enumerate(sorted(values, key=lambda v: (v[0], v[1]["directory"]), reverse=True)):
            if index < keep or (now - created).total_seconds() < days * 86400:
                protected += 1
            else:
                candidates.append(dict(item, namespace=namespace))
    return {"contract": "EAP_DIAGNOSTIC_RETENTION_PLAN_V1", "createdAt": now.isoformat(), "retentionDays": days,
            "keepNewestPerNamespace": keep, "candidates": sorted(candidates, key=lambda x: x["directory"]),
            "protectedBundles": protected, "unmanagedEntries": skipped, "deleteExecuted": False,
            "candidateBytes": sum(x["bytes"] for x in candidates),
            "notice": "仅为人工审阅计划；执行前重新核对两个文件摘要。不得据此清理业务数据、原始证据或其他目录。"}
