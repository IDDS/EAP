package com.eap.platform.substrate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent Substrate runtime connection. Disabled unless the backend runs inside the Substrate cluster. */
@Component
@ConfigurationProperties(prefix = "eap.substrate")
public class SubstrateProperties {
    private boolean enabled = false;
    private String inventoryAtespaces = "";
    public void setInventoryAtespaces(String value) { inventoryAtespaces = value; }
    public java.util.Set<String> inventoryScopes() {
        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        scopes.add(deployAtespace);
        if (inventoryAtespaces != null) java.util.Arrays.stream(inventoryAtespaces.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).forEach(scopes::add);
        if (scopes.size() > 8 || scopes.stream().anyMatch(s -> s == null || !s.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")))
            throw new IllegalArgumentException("发现范围配置无效");
        return java.util.Collections.unmodifiableSet(scopes);
    }
    private String apiTarget = "api.ate-system.svc:443";
    private String apiAuthority = "api.ate-system.svc";
    private String tokenFile = "/var/run/secrets/ate-api/token";
    private String caFile = "/var/run/secrets/ate-api/ca.crt";
    private long callTimeoutMs = 60000;
    private String routerUrl = "http://atenet-router.ate-system.svc";
    private String taskPath = "/eap/task";
    // Console deployment (docs/SUBSTRATE_RUNTIME_INTEGRATION.md section 6).
    private String deployAtespace = "eap-agents";
    private String workerLabel = "workload=eap-agents";
    private String snapshotLocation = "gs://ate-snapshots/eap/";
    private String runnerImage = "";
    private String kagentRunnerImage = "";
    private String frameworkSampleImage = "";
    private String localRegistryPrefix = "localhost:5001/";
    private String localRegistryUrl = "";
    private String gatewayUrl = "";
    private String gatewayHost = "";
    private String gatewayToken = "";
    private long goldenTimeoutSeconds = 900;
    // Source builds (deployment source SOURCE): kaniko Jobs in their own namespace.
    private boolean buildEnabled = false;
    private String buildNamespace = "eap-builds";
    private String buildImage = "gcr.io/kaniko-project/executor:v1.23.2";
    private String buildRegistryMirror = "";
    private String buildPushPrefix = "";
    private int buildTimeoutSeconds = 1800;
    private String buildHttpGitHosts = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiTarget() { return apiTarget; }
    public void setApiTarget(String apiTarget) { this.apiTarget = apiTarget; }
    public String getApiAuthority() { return apiAuthority; }
    public void setApiAuthority(String apiAuthority) { this.apiAuthority = apiAuthority; }
    public String getTokenFile() { return tokenFile; }
    public void setTokenFile(String tokenFile) { this.tokenFile = tokenFile; }
    public String getCaFile() { return caFile; }
    public void setCaFile(String caFile) { this.caFile = caFile; }
    public long getCallTimeoutMs() { return Math.max(1000, callTimeoutMs); }
    public void setCallTimeoutMs(long callTimeoutMs) { this.callTimeoutMs = callTimeoutMs; }
    public String getRouterUrl() { return routerUrl; }
    public void setRouterUrl(String routerUrl) { this.routerUrl = routerUrl; }
    public String getTaskPath() { return taskPath == null || taskPath.isBlank() ? "/eap/task" : taskPath; }
    public void setTaskPath(String taskPath) { this.taskPath = taskPath; }
    public String getDeployAtespace() { return deployAtespace; }
    public void setDeployAtespace(String deployAtespace) { this.deployAtespace = deployAtespace; }
    public String getWorkerLabel() { return workerLabel; }
    public void setWorkerLabel(String workerLabel) { this.workerLabel = workerLabel; }
    public String getSnapshotLocation() { return snapshotLocation; }
    public void setSnapshotLocation(String snapshotLocation) { this.snapshotLocation = snapshotLocation; }
    public String getRunnerImage() { return runnerImage == null ? "" : runnerImage; }
    public void setRunnerImage(String runnerImage) { this.runnerImage = runnerImage; }
    public String getKagentRunnerImage() { return kagentRunnerImage == null ? "" : kagentRunnerImage; }
    public void setKagentRunnerImage(String value) { this.kagentRunnerImage = value; }
    public String getFrameworkSampleImage() { return frameworkSampleImage == null ? "" : frameworkSampleImage; }
    public void setFrameworkSampleImage(String value) { frameworkSampleImage = value; }
    public boolean frameworkSampleAvailable() { return getFrameworkSampleImage().matches("[^\\s]+@sha256:[a-f0-9]{64}"); }
    public boolean kagentRunnerAvailable() { return getKagentRunnerImage().matches("[^\\s]+@sha256:[a-f0-9]{64}"); }
    public String getLocalRegistryPrefix() { return localRegistryPrefix; }
    public void setLocalRegistryPrefix(String localRegistryPrefix) { this.localRegistryPrefix = localRegistryPrefix; }
    public String getLocalRegistryUrl() { return localRegistryUrl == null ? "" : localRegistryUrl; }
    public void setLocalRegistryUrl(String localRegistryUrl) { this.localRegistryUrl = localRegistryUrl; }
    public String getGatewayUrl() { return gatewayUrl == null ? "" : gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
    public String getGatewayHost() { return gatewayHost == null ? "" : gatewayHost; }
    public void setGatewayHost(String gatewayHost) { this.gatewayHost = gatewayHost; }
    public String getGatewayToken() { return gatewayToken == null ? "" : gatewayToken; }
    public void setGatewayToken(String gatewayToken) { this.gatewayToken = gatewayToken; }
    public long getGoldenTimeoutSeconds() { return Math.max(60, goldenTimeoutSeconds); }
    public void setGoldenTimeoutSeconds(long goldenTimeoutSeconds) { this.goldenTimeoutSeconds = goldenTimeoutSeconds; }

    public boolean isBuildEnabled() { return buildEnabled; }
    public void setBuildEnabled(boolean buildEnabled) { this.buildEnabled = buildEnabled; }
    public String getBuildNamespace() { return buildNamespace; }
    public void setBuildNamespace(String buildNamespace) { this.buildNamespace = buildNamespace; }
    public String getBuildImage() { return buildImage; }
    public void setBuildImage(String buildImage) { this.buildImage = buildImage; }
    public String getBuildRegistryMirror() { return buildRegistryMirror == null ? "" : buildRegistryMirror; }
    public void setBuildRegistryMirror(String buildRegistryMirror) { this.buildRegistryMirror = buildRegistryMirror; }
    /** Registry prefix build pods push to; the same repository is referenced as local-registry-prefix in templates. */
    public String getBuildPushPrefix() { return buildPushPrefix == null || buildPushPrefix.isBlank() ? localRegistryPrefix : buildPushPrefix; }
    public void setBuildPushPrefix(String buildPushPrefix) { this.buildPushPrefix = buildPushPrefix; }
    /** Internal Git hosts (e.g. an in-cluster mirror) whose repositories may be cloned over plain http. */
    public java.util.Set<String> buildHttpGitHosts() {
        return java.util.Arrays.stream(buildHttpGitHosts == null ? new String[0] : buildHttpGitHosts.split(","))
                .map(host -> host.trim().toLowerCase(java.util.Locale.ROOT)).filter(host -> !host.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public void setBuildHttpGitHosts(String buildHttpGitHosts) { this.buildHttpGitHosts = buildHttpGitHosts; }
    public int getBuildTimeoutSeconds() { return Math.max(120, buildTimeoutSeconds); }
    public void setBuildTimeoutSeconds(int buildTimeoutSeconds) { this.buildTimeoutSeconds = buildTimeoutSeconds; }

    public java.util.Map<String, String> workerLabels() {
        String[] parts = workerLabel.split("=", 2);
        return java.util.Map.of(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "");
    }
}
