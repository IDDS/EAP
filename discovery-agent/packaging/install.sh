#!/usr/bin/env bash
set -euo pipefail

SERVER_URL=""
ENROLLMENT_TOKEN=""
ARTIFACT_BASE=""
ALLOW_INSECURE="false"
SCAN_ROOTS='["/opt", "/srv", "/home"]'

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server) SERVER_URL="$2"; shift 2 ;;
    --token) ENROLLMENT_TOKEN="$2"; shift 2 ;;
    --artifact-base) ARTIFACT_BASE="$2"; shift 2 ;;
    --allow-insecure-http) ALLOW_INSECURE="true"; shift ;;
    --scan-roots) SCAN_ROOTS="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ "${EUID}" -ne 0 ]]; then echo "Run this installer as root" >&2; exit 1; fi
if [[ -z "${SERVER_URL}" || -z "${ENROLLMENT_TOKEN}" ]]; then echo "--server and --token are required" >&2; exit 1; fi
if [[ "${SERVER_URL}" == http://* && "${ALLOW_INSECURE}" != "true" ]]; then echo "HTTP requires --allow-insecure-http" >&2; exit 1; fi
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required by the installer" >&2; exit 1; }

ARCH="$(uname -m)"
case "${ARCH}" in
  x86_64|amd64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) echo "Unsupported architecture: ${ARCH}" >&2; exit 1 ;;
esac

if [[ -z "${ARTIFACT_BASE}" ]]; then ARTIFACT_BASE="${SERVER_URL%/}/downloads"; fi
getent group eap-agent >/dev/null 2>&1 || groupadd --system eap-agent
id eap-discovery >/dev/null 2>&1 || useradd --system --home-dir /var/lib/eap-discovery-agent --shell /usr/sbin/nologin --gid eap-agent eap-discovery
id eap-content >/dev/null 2>&1 || useradd --system --home-dir /var/lib/eap-content-proxy --shell /usr/sbin/nologin --gid eap-agent eap-content
if getent group docker >/dev/null 2>&1; then usermod -a -G docker eap-discovery; fi
install -d -m 0750 -o root -g eap-agent /etc/eap-discovery-agent
install -d -m 0755 -o root -g root /usr/local/lib/eap-discovery-agent
install -d -m 0700 -o eap-discovery -g eap-agent /var/lib/eap-discovery-agent
install -d -m 0700 -o eap-content -g eap-agent /var/lib/eap-content-proxy

curl -fsSL "${ARTIFACT_BASE}/eap-discovery-agent-linux-${ARCH}" -o /usr/local/bin/eap-discovery-agent
curl -fsSL "${ARTIFACT_BASE}/eap-content-proxy-linux-${ARCH}" -o /usr/local/bin/eap-content-proxy
chmod 0755 /usr/local/bin/eap-discovery-agent /usr/local/bin/eap-content-proxy
curl -fsSL "${ARTIFACT_BASE}/eap-discovery-agent.service" -o /etc/systemd/system/eap-discovery-agent.service
curl -fsSL "${ARTIFACT_BASE}/eap-discovery-auto-attach.service" -o /etc/systemd/system/eap-discovery-auto-attach.service
curl -fsSL "${ARTIFACT_BASE}/eap-discovery-auto-attach.timer" -o /etc/systemd/system/eap-discovery-auto-attach.timer
curl -fsSL "${ARTIFACT_BASE}/eap-host-observer.service" -o /etc/systemd/system/eap-host-observer.service
curl -fsSL "${ARTIFACT_BASE}/eap-host-observer.timer" -o /etc/systemd/system/eap-host-observer.timer
curl -fsSL "${ARTIFACT_BASE}/eap-ebpf-observer.service" -o /etc/systemd/system/eap-ebpf-observer.service
curl -fsSL "${ARTIFACT_BASE}/eap-connect.bt" -o /usr/local/lib/eap-discovery-agent/eap-connect.bt
curl -fsSL "${ARTIFACT_BASE}/eap_ebpf_observer.py" -o /usr/local/lib/eap-discovery-agent/eap_ebpf_observer.py
chmod 0755 /usr/local/lib/eap-discovery-agent/eap_ebpf_observer.py
chmod 0644 /usr/local/lib/eap-discovery-agent/eap-connect.bt
curl -fsSL "${ARTIFACT_BASE}/eap-content-proxy.service" -o /etc/systemd/system/eap-content-proxy.service
curl -fsSL "${ARTIFACT_BASE}/eap-content-proxy@.service" -o /etc/systemd/system/eap-content-proxy@.service

python3 - "${SERVER_URL}" "${ALLOW_INSECURE}" "${SCAN_ROOTS}" <<'PY'
import json, sys
server, insecure, roots = sys.argv[1:]
document = {
  "server_url": server,
  "allow_insecure_http": insecure == "true",
  "state_dir": "/var/lib/eap-discovery-agent",
  "content_state_dir": "/var/lib/eap-content-proxy",
  "scan": {"processes": True, "docker": True, "ports": True, "files": True, "roots": json.loads(roots), "max_files": 3000, "max_depth": 6},
  "host_observer": {"mode": "auto", "max_observations": 1000},
  "ebpf_observer": {"mode": "auto", "batch_interval_seconds": 30, "max_events": 1000},
  "content_proxy": {"listen": "127.0.0.1:8686", "upstream_url": "https://api.openai.com/v1", "provider": "openai-compatible", "max_capture_bytes": 4194304}
}
with open("/etc/eap-discovery-agent/config.json", "w", encoding="utf-8") as handle:
    json.dump(document, handle, ensure_ascii=False, indent=2)
PY
chown root:eap-agent /etc/eap-discovery-agent/config.json
chmod 0640 /etc/eap-discovery-agent/config.json

sudo -u eap-discovery /usr/local/bin/eap-discovery-agent enroll --config /etc/eap-discovery-agent/config.json --token "${ENROLLMENT_TOKEN}"
install -m 0600 -o eap-content -g eap-agent /var/lib/eap-discovery-agent/content-identity.json /var/lib/eap-content-proxy/content-identity.json
rm -f /var/lib/eap-discovery-agent/content-identity.json
systemctl daemon-reload
systemctl enable --now eap-discovery-agent.service
systemctl enable --now eap-discovery-auto-attach.timer
systemctl enable --now eap-host-observer.timer
if [[ "$(uname -s)" == "Linux" ]] && command -v bpftrace >/dev/null 2>&1 && [[ -r /sys/kernel/btf/vmlinux ]]; then
  if ! systemctl enable --now eap-ebpf-observer.service; then
    echo "Warning: native eBPF observer could not start; portable Host Observer remains active" >&2
    systemctl disable --now eap-ebpf-observer.service 2>/dev/null || true
  fi
else
  echo "Native eBPF prerequisites unavailable; portable Host Observer remains active"
fi
echo "EAP Discovery Agent installed and enrolled successfully"
echo "Content proxy remains disabled until dual approval is completed in EAP"
