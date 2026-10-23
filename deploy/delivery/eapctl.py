"""EAP delivery inspection and reviewable upgrade plans (standard library only).

Run cluster commands on the authorized operations server. This program never
reads Secret values, applies manifests, executes business tasks or retries writes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


class DeliveryError(Exception):
    pass


def image(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[0-9a-f]{64}", value):
        raise DeliveryError("镜像必须使用完整 sha256 引用，不能使用可变标签")
    return value


def manifest(path):
    data = json.loads(Path(path).read_text())
    if data.get("contract") != "EAP_DELIVERY_V1" or data.get("profile") != "ASSISTED_PRIVATE_PILOT":
        raise DeliveryError("不支持的交付合同或交付档位")
    if set(data.get("applications", {})) != {"backend", "frontend"}:
        raise DeliveryError("必须指定后端与前端版本")
    for value in data["applications"].values():
        image(value)
    if type(data.get("schema")) is not int or data["schema"] < 1:
        raise DeliveryError("必须指定数据库结构版本")
    if data.get("productionReady") is not False:
        raise DeliveryError("当前合同不能声明生产环境验收完成")
    return data


def kubectl(namespace, args):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", namespace):
        raise DeliveryError("命名空间无效")
    if sys.platform != "linux":
        raise DeliveryError("集群检查只能在远程 Linux 运维节点运行")
    try:
        result = subprocess.run(["kubectl", "--request-timeout=10s", "-n", namespace] + args,
                                capture_output=True, timeout=15, check=True)
        return json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError):
        raise DeliveryError("集群读取失败；未输出命令错误正文，请由运维人员核对连接") from None


def application_facts(deployment, expected):
    spec = deployment["spec"]
    containers = spec["template"]["spec"]["containers"]
    if len(containers) != 1:
        raise DeliveryError("当前交付合同只支持单应用容器的 Deployment")
    container = containers[0]
    status = deployment.get("status", {})
    replicas = spec.get("replicas", 1)
    ready = replicas > 0 and status.get("observedGeneration", 0) >= deployment["metadata"]["generation"]
    ready = ready and all(status.get(key, 0) == replicas for key in ["replicas", "updatedReplicas", "readyReplicas", "availableReplicas"])
    secret_refs = set()
    for env in container.get("env", []):
        reference = env.get("valueFrom", {}).get("secretKeyRef")
        if reference:
            secret_refs.add(reference["name"])
    for volume in spec["template"]["spec"].get("volumes", []):
        if volume.get("secret"):
            secret_refs.add(volume["secret"]["secretName"])
    return {"name": deployment["metadata"]["name"], "uid": deployment["metadata"]["uid"],
            "imageMatches": container["image"] == expected, "ready": ready,
            "replicas": replicas, "secretReferences": sorted(secret_refs)}


def inspect(release, namespace):
    applications = {}
    for name, expected in release["applications"].items():
        applications[name] = application_facts(kubectl(namespace, ["get", "deployment", name, "-o", "json"]), expected)
    return {"contract": "EAP_DELIVERY_INSPECTION_V1", "releaseId": release["releaseId"], "applications": applications,
            "applicationChecksPassed": all(a["ready"] and a["imageMatches"] for a in applications.values()),
            "productionReady": False, "unverified": ["数据库结构版本", "首次安装", "HTTP 载荷及登录", "业务任务", "备份恢复", "容量与高可用"],
            "notice": "Pod 就绪和镜像匹配只证明应用部署状态；必须继续完成交付验收。"}


def upgrade_plan(deployment, component, expected, target):
    image(expected); image(target)
    if component not in {"backend", "frontend"} or deployment["metadata"]["name"] != component:
        raise DeliveryError("升级对象不匹配")
    container = deployment["spec"]["template"]["spec"]["containers"]
    if len(container) != 1 or container[0]["name"] != component or container[0]["image"] != expected:
        raise DeliveryError("应用容器或基线镜像已变化，请重新检查")
    if expected == target:
        return {"component": component, "changed": False, "patch": []}
    patch = [{"op": "test", "path": "/metadata/uid", "value": deployment["metadata"]["uid"]},
             {"op": "test", "path": "/metadata/resourceVersion", "value": deployment["metadata"]["resourceVersion"]},
             {"op": "test", "path": "/spec/template/spec/containers/0/image", "value": expected},
             {"op": "replace", "path": "/spec/template/spec/containers/0/image", "value": target}]
    strategy = deployment["spec"].get("strategy", {"type": "RollingUpdate"})
    if component == "backend":
        patch += [{"op": "test", "path": "/spec/strategy", "value": strategy},
                  {"op": "replace", "path": "/spec/strategy", "value": {"type": "Recreate"}}]
    return {"component": component, "changed": True, "patch": patch, "previousImage": expected,
            "targetImage": target, "previousStrategy": strategy,
            "execution": "REVIEW_REQUIRED_NOT_APPLIED",
            "requirements": ["确认没有活跃派发与旧写者", "验证目标版本与数据库结构兼容", "保留可验证的恢复点", "核对目标镜像中的受测载荷", "后端切换后确认旧 Pod 消失", "前端核对 HTTP 载荷与 version.json", "数据库升级不能仅靠回退镜像恢复"]}


def verify_source(root, source_manifest):
    root = Path(root).resolve()
    entries = json.loads(Path(source_manifest).read_text())
    if not isinstance(entries, dict) or not entries:
        raise DeliveryError("源码清单不能为空")
    for name, expected in entries.items():
        relative = Path(name)
        path = root / relative
        if relative.is_absolute() or ".." in relative.parts or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise DeliveryError("源码清单包含无效条目")
        if not path.is_file() or path.is_symlink() or root not in path.resolve().parents:
            raise DeliveryError("源码文件缺失或越界")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise DeliveryError("源码摘要不匹配，停止交付")
    return {"verifiedFiles": len(entries), "passed": True, "scope": "只核对清单所列文件；不证明测试通过或不存在额外文件"}


def main():
    parser = argparse.ArgumentParser(description="EAP 交付检查与升级计划；不执行部署或业务调用")
    parser.add_argument("--manifest", default=str(Path(__file__).with_name("release.json")))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check-release")
    check = commands.add_parser("inspect"); check.add_argument("--namespace", default="eap")
    plan = commands.add_parser("plan-upgrade"); plan.add_argument("component", choices=["backend", "frontend"])
    plan.add_argument("--namespace", default="eap"); plan.add_argument("--target", required=True)
    source = commands.add_parser("verify-source"); source.add_argument("--root", required=True); source.add_argument("--source-manifest", required=True)
    args = parser.parse_args()
    try:
        release = manifest(args.manifest)
        if args.command == "check-release": result = {"releaseId": release["releaseId"], "profile": release["profile"], "schema": release["schema"], "productionReady": False}
        elif args.command == "inspect": result = inspect(release, args.namespace)
        elif args.command == "verify-source": result = verify_source(args.root, args.source_manifest)
        else: result = upgrade_plan(kubectl(args.namespace, ["get", "deployment", args.component, "-o", "json"]), args.component, release["applications"][args.component], args.target)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if args.command == "inspect" and not result["applicationChecksPassed"]: return 2
        return 0
    except (DeliveryError, OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"passed": False, "error": "交付检查失败；请核对合同、文件与目标环境。未输出配置或集群错误正文。"}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(main())
