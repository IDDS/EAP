package com.eap.platform.substrate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent Substrate runtime connection. Disabled unless the backend runs inside the Substrate cluster. */
@Component
@ConfigurationProperties(prefix = "eap.substrate")
public class SubstrateProperties {
    private boolean enabled = false;
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
    private String localRegistryPrefix = "localhost:5001/";
    private String localRegistryUrl = "";
    private String gatewayUrl = "";
    private String gatewayHost = "";
    private String gatewayToken = "";
    private long goldenTimeoutSeconds = 900;

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

    public java.util.Map<String, String> workerLabels() {
        String[] parts = workerLabel.split("=", 2);
        return java.util.Map.of(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "");
    }
}
