"""Prepare an exact, digest-locked build context on the remote build server.

No package installation, registry access, build, push or deployment is performed.
An artifact manifest is a byte identity check, not evidence of successful tests.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys

from eapctl import DeliveryError, image


HERE = Path(__file__).resolve().parent
MAX_FILES = 4096
MAX_BYTES = 512 * 1024 * 1024


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise DeliveryError("JSON 含重复字段")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def entries(value):
    if not isinstance(value, dict) or not 1 <= len(value) <= MAX_FILES:
        raise DeliveryError("产物清单为空或超过文件数量上限")
    for name, digest in value.items():
        path = PurePosixPath(name)
        if (not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", name)
                or path.is_absolute() or any(part in {".", ".."} for part in name.split("/"))
                or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest)):
            raise DeliveryError("产物清单包含无效路径或摘要")
    return value


def files(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise DeliveryError("产物目录无效")
    found, total = {}, 0
    for directory, dirs, names in os.walk(root, followlinks=False):
        for name in dirs + names:
            p = Path(directory) / name
            if p.is_symlink() or not (p.is_file() or p.is_dir()):
                raise DeliveryError("产物包含链接或特殊文件")
        for name in names:
            p = Path(directory) / name
            total += p.stat().st_size
            if total > MAX_BYTES or len(found) >= MAX_FILES:
                raise DeliveryError("产物超过容量限制")
            found[p.relative_to(root).as_posix()] = sha(p)
    return found


def payload(component, expected):
    if component == "backend":
        valid = set(expected) == {"app.jar"}
    elif component == "frontend":
        valid = {"nginx.conf", "dist/index.html", "dist/version.json"} <= set(expected)
        valid = valid and all(n == "nginx.conf" or n.startswith("dist/") for n in expected)
    else:
        valid = False
    if not valid:
        raise DeliveryError("产物不符合组件合同")


def base(lock_path, component):
    lock = read_json(lock_path)
    if lock.get("contract") != "EAP_RUNTIME_BASES_V1" or lock.get("platform") != "linux/amd64":
        raise DeliveryError("基础镜像合同或平台不匹配")
    return image(lock["applications"][component])


def dockerfile(component, reference):
    template = (HERE / "images" / (component + ".Dockerfile")).read_text()
    if template.count("@RUNTIME_IMAGE@") != 1:
        raise DeliveryError("构建模板无效")
    return template.replace("@RUNTIME_IMAGE@", reference)


def prepare(component, artifacts, artifact_manifest, runtime_bases, output):
    expected = entries(read_json(artifact_manifest))
    payload(component, expected)
    reference = base(runtime_bases, component)
    source = Path(artifacts).resolve()
    # Check the original path too: resolve() must not conceal a directory symlink.
    if Path(artifacts).is_symlink() or files(source) != expected:
        raise DeliveryError("产物摘要或文件集合不匹配")
    target = Path(output)
    if target.exists() or target.is_symlink() or source == target.resolve() or source in target.resolve().parents:
        raise DeliveryError("输出必须是独立且不存在的新目录")
    target.mkdir(parents=False)
    # Failures leave an incomplete context without a receipt; never reuse it.
    for name in sorted(expected):
        p = target / name
        p.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, p)
        p.chmod(0o644)
    if files(target) != expected:
        raise DeliveryError("复制过程中产物变化")
    (target / "Dockerfile").write_text(dockerfile(component, reference))
    receipt = {"contract": "EAP_BUILD_CONTEXT_V1", "component": component,
               "platform": "linux/amd64", "baseImage": reference,
               "files": files(target), "artifacts": expected,
               "testsVerified": False, "deployed": False}
    (target / "context.json").write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n")
    return verify(target, runtime_bases)


def verify(context, runtime_bases, expected_context_sha256=None):
    root = Path(context)
    if expected_context_sha256 is not None:
        if (not re.fullmatch(r"[0-9a-f]{64}", expected_context_sha256)
                or sha(root / "context.json") != expected_context_sha256):
            raise DeliveryError("构建回执与外部固定摘要不匹配")
    receipt = read_json(root / "context.json")
    if receipt.get("contract") != "EAP_BUILD_CONTEXT_V1" or receipt.get("platform") != "linux/amd64":
        raise DeliveryError("构建上下文合同无效")
    component = receipt["component"]
    reference = base(runtime_bases, component)
    if receipt["baseImage"] != reference:
        raise DeliveryError("基础镜像已变化")
    expected = entries(receipt["files"])
    artifacts = entries(receipt["artifacts"])
    payload(component, artifacts)
    if set(expected) != set(artifacts) | {"Dockerfile"} or any(expected[n] != h for n, h in artifacts.items()):
        raise DeliveryError("构建上下文不符合产物合同")
    actual = files(root)
    actual.pop("context.json", None)
    if actual != expected or (root / "Dockerfile").read_text() != dockerfile(component, reference):
        raise DeliveryError("构建上下文发生变化")
    return {"contract": "EAP_BUILD_CONTEXT_CHECK_V1", "passed": True, "component": component,
            "baseImage": reference, "artifactFiles": len(artifacts), "contextSha256": sha(root / "context.json"),
            "testsVerified": False, "deployed": False}


def main():
    parser = argparse.ArgumentParser(description="远程受测产物构建上下文；不执行构建、推送或部署")
    parser.add_argument("--runtime-bases", default=str(HERE / "runtime-bases.json"))
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("component", choices=["backend", "frontend"])
    p.add_argument("--artifacts", required=True)
    p.add_argument("--artifact-manifest", required=True)
    p.add_argument("--output", required=True)
    p = sub.add_parser("verify")
    p.add_argument("--context", required=True)
    p.add_argument("--expected-context-sha256", required=True)
    args = parser.parse_args()
    try:
        if sys.platform != "linux":
            raise DeliveryError("仅在远程 Linux 构建服务器执行")
        if args.command == "prepare":
            result = prepare(args.component, args.artifacts, args.artifact_manifest, args.runtime_bases, args.output)
        else:
            result = verify(args.context, args.runtime_bases, args.expected_context_sha256)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (DeliveryError, OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"passed": False, "error": "构建上下文检查失败；请核对产物、固定基础镜像与新目录。未输出文件正文。"}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(main())
