# EAP Discovery Agent

Independent endpoint software for discovering AI agents and optionally observing governed business content. It has its own Go module and does not import EAP backend code.

## Binaries

- `eap-discovery-agent`: enrollment, heartbeat, process/Docker/port/manifest discovery, encrypted offline queue and idempotent uploads.
- `eap-content-proxy`: separately authorized local reverse proxy for model, MCP, A2A and tool business content.

The content proxy will not start until EAP returns a policy with `contentCaptureEnabled=true`. The default `DUAL` mode requires platform-admin and data-owner approval; deployments may explicitly set `EAP_DISCOVERY_AGENT_CONTENT_APPROVAL_MODE=NONE` when the current product version does not use an approval workflow. Manual disable and audit events remain available in both modes.

## Cross-platform host observation

Discovery Agent 1.5.0 includes a portable `PROCESS_NETWORK_SCAN` sensor, a Linux native eBPF connect-event adapter and a sensor-neutral upload protocol. It also consumes EAP's centrally versioned signature catalog, recognizes Hermes Agent and OpenClaw profiles/workspaces, emits a full inventory every five minutes, and treats model API network traffic as supporting evidence rather than an Agent identity. Linux uses a BTF-aware `bpftrace` program on `sys_enter_connect`, enriches PID events through `/proc` and read-only Docker metadata, and keeps the portable `ss` snapshot as fallback. macOS parsing uses `lsof`; Windows parsing uses `netstat` and `tasklist`. The normalized protocol also accepts `ETW`, `WFP`, `ENDPOINT_SECURITY` and `NETWORK_EXTENSION`, so native collectors can be added without changing EAP ingestion.

The observer only reports Agent-like processes and connection metadata. It explicitly excludes EAP itself, Tencent TAT, common telemetry agents and ordinary infrastructure processes. Stable `identityKey` values merge network evidence with the Docker/process asset while preserving its canonical name, vendor, product and fingerprint.

It cannot decrypt HTTPS or obtain Prompt, response text, API keys or exact Token usage. Those require the separately governed Content Proxy, an application/runtime OpenTelemetry signal, a model gateway, or provider usage reconciliation.

## Zero-code Docker attachment

For supported frameworks, Discovery Agent can inject observability without changing Agent source code, model credentials or provider routing. The first profile supports Python applications using LiteLLM. A read-only `sitecustomize` hook registers LiteLLM's official `CustomLogger`, and sends Prompt, response and usage data to the Agent-scoped local content collector. The provider API key is never read or copied by the instrumentation.

Generate a sanitized plan without changing the workload:

```bash
sudo eap-discovery-agent attach \
  --config /etc/eap-discovery-agent/config.json \
  --target boss-agent-backend \
  --profile auto \
  --agent-id 4 \
  --provider deepseek \
  --usage-pool-key shared-deepseek-production \
  --collector-url http://host.docker.internal:8687 \
  --instrumentation-token-file /var/lib/eap-content-proxy/helloboss/instrumentation-token \
  --proxy-instance helloboss
```

Add `--apply` to install the read-only instrumentation, start the scoped collector and recreate only the target Compose service. Apply is fail-closed unless EAP policy has both `contentCaptureEnabled=true` and `contentAutoAttachEnabled=true`, and the detected profile is centrally allowed.

When `--collector-url` is omitted, auto-attach derives the collector address from the target container's Docker network gateway (for example `http://172.18.0.1:8687` on Linux). This avoids the common Docker Desktop/Linux mismatch where `host.docker.internal` resolves to a different bridge. An explicit collector URL always takes precedence.

For unattended operation, declare one or more targets in the main configuration:

```json
{
  "auto_attach": [
    {
      "enabled": true,
      "target": "boss-agent-backend",
      "profile": "litellm",
      "agent_id": 4,
      "provider": "deepseek",
      "usage_pool_key": "shared-deepseek-production",
      "collector_url": "http://host.docker.internal:8687",
      "instrumentation_token_file": "/var/lib/eap-content-proxy/helloboss/instrumentation-token",
      "proxy_instance": "helloboss"
    }
  ]
}
```

The installer enables `eap-discovery-auto-attach.timer`. Its root-only one-shot reconciler checks central authorization every minute and applies configured targets only after authorization becomes effective. The long-running discovery process remains unprivileged. Reconciliation is idempotent: matching instrumentation is not restarted, while attribution or instrumentation-version changes trigger a controlled recreation. The business user and Agent developer do not need to run a command; the first authorized attachment still recreates the target container once and should follow the infrastructure maintenance policy.

## Shared provider credentials

When multiple agents share one provider API key, use a stable non-secret `usage_pool_key` alias in every proxy configuration. Do not place the real API key in this field. Run one proxy instance per agent so each instance has its own `agent_id`, listen port and state directory while all instances may forward the same provider credential.

Example files:

```text
/etc/eap-discovery-agent/content-proxy-helloboss.json
/etc/eap-discovery-agent/content-proxy-finance.json
```

Each file should use the same `usage_pool_key` such as `shared-openai-production`, but a different `agent_id`, `listen` address and `content_state_dir`. Create each state directory with owner `eap-content:eap-agent`, then copy the enrolled `content-identity.json` from the original content proxy state directory into it with mode `0600`. Start the instances with:

```bash
systemctl enable --now eap-content-proxy@helloboss.service
systemctl enable --now eap-content-proxy@finance.service
```

The provider billing collector submits aggregate windows to `/api/telemetry/provider-usage-snapshots` using the same alias. EAP reconciles those totals against per-agent events and keeps any difference as unallocated usage instead of averaging it across agents.

## Commands

```bash
eap-discovery-agent enroll --config ./config.json --token 'one-time-token'
eap-discovery-agent scan --config ./config.json --dry-run
sudo eap-discovery-agent observe-host --config ./config.json --dry-run
eap-discovery-agent diagnose --config ./config.json
sudo eap-discovery-agent reconcile-attachments --config ./config.json
eap-discovery-agent run --config ./config.json
```

Plain HTTP is rejected unless `allow_insecure_http` is explicitly enabled for a test environment. Discovery and content capture use different machine credentials and separate state directories. Offline batches are encrypted with AES-GCM and bounded by EAP policy.

## Build

```bash
make test
make package
```

The packaged production target is Linux amd64/arm64. Native eBPF activation requires Linux BTF and `bpftrace`; installation is fail-open and leaves `PROCESS_NETWORK_SCAN` active if those prerequisites are absent or the service cannot start. Host-observer parsers are covered for Linux, Windows and macOS, while signed binaries, native Windows ETW/WFP, macOS EndpointSecurity/NetworkExtension collectors and their service installers remain later releases.
