#!/usr/bin/env bash
set -euo pipefail
if [[ "${EUID}" -ne 0 ]]; then echo "Run this uninstaller as root" >&2; exit 1; fi
systemctl disable --now eap-ebpf-observer.service eap-host-observer.timer eap-host-observer.service eap-discovery-auto-attach.timer eap-discovery-auto-attach.service eap-content-proxy.service eap-discovery-agent.service 2>/dev/null || true
systemctl list-unit-files 'eap-content-proxy@*.service' --no-legend 2>/dev/null | awk '{print $1}' | xargs -r systemctl disable --now 2>/dev/null || true
if [[ -x /usr/local/bin/eap-discovery-agent && -f /etc/eap-discovery-agent/config.json ]]; then
  sudo -u eap-discovery /usr/local/bin/eap-discovery-agent --config /etc/eap-discovery-agent/config.json uninstall || true
fi
rm -f /etc/systemd/system/eap-discovery-auto-attach.timer /etc/systemd/system/eap-discovery-auto-attach.service
rm -f /etc/systemd/system/eap-host-observer.timer /etc/systemd/system/eap-host-observer.service
rm -f /etc/systemd/system/eap-ebpf-observer.service
rm -f /usr/local/lib/eap-discovery-agent/eap-connect.bt /usr/local/lib/eap-discovery-agent/eap_ebpf_observer.py
rmdir /usr/local/lib/eap-discovery-agent 2>/dev/null || true
rm -f /etc/systemd/system/eap-content-proxy.service /etc/systemd/system/eap-content-proxy@.service /etc/systemd/system/eap-discovery-agent.service
rm -f /usr/local/bin/eap-content-proxy /usr/local/bin/eap-discovery-agent
rm -rf /etc/eap-discovery-agent /var/lib/eap-discovery-agent /var/lib/eap-content-proxy
systemctl daemon-reload
echo "EAP Discovery Agent removed; EAP history has been retained"
