package com.eap.platform.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "eap.discovery.agent-v1")
public class DiscoveryAgentV1Properties {
    private String policyVersion = "2026.08.3";
    private String rulesVersion = "2026.08.3";
    private int heartbeatIntervalSeconds = 60;
    private int scanIntervalSeconds = 300;
    private int fullSnapshotIntervalSeconds = 300;
    private int offlineQueueDays = 7;
    private long offlineQueueMaxBytes = 104_857_600L;
    private boolean requireHttps = true;
    private double rawContentSampleRate = 0.10;
    private int rawContentRetentionDays = 30;
    private String contentApprovalMode = "DUAL";
    private boolean contentAutoAttachEnabled = false;
    private String contentAutoAttachProfiles = "litellm";
    private String contentVaultMasterKey = "";
    private String contentVaultKeyVersion = "v1";

    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getRulesVersion() { return rulesVersion; }
    public void setRulesVersion(String rulesVersion) { this.rulesVersion = rulesVersion; }
    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(int value) { heartbeatIntervalSeconds = value; }
    public int getScanIntervalSeconds() { return scanIntervalSeconds; }
    public void setScanIntervalSeconds(int value) { scanIntervalSeconds = value; }
    public int getFullSnapshotIntervalSeconds() { return fullSnapshotIntervalSeconds; }
    public void setFullSnapshotIntervalSeconds(int value) { fullSnapshotIntervalSeconds = value; }
    public int getOfflineQueueDays() { return offlineQueueDays; }
    public void setOfflineQueueDays(int value) { offlineQueueDays = value; }
    public long getOfflineQueueMaxBytes() { return offlineQueueMaxBytes; }
    public void setOfflineQueueMaxBytes(long value) { offlineQueueMaxBytes = value; }
    public boolean isRequireHttps() { return requireHttps; }
    public void setRequireHttps(boolean requireHttps) { this.requireHttps = requireHttps; }
    public double getRawContentSampleRate() { return rawContentSampleRate; }
    public void setRawContentSampleRate(double value) { rawContentSampleRate = value; }
    public int getRawContentRetentionDays() { return rawContentRetentionDays; }
    public void setRawContentRetentionDays(int value) {
        if (value < 1 || value > 36500) throw new IllegalArgumentException("原文保留天数须在 1 至 36500 之间");
        rawContentRetentionDays = value;
    }
    public String getContentApprovalMode() { return contentApprovalMode; }
    public void setContentApprovalMode(String value) { contentApprovalMode = value == null ? "DUAL" : value; }
    public boolean isContentApprovalDisabled() { return "NONE".equalsIgnoreCase(contentApprovalMode.trim()); }
    public String effectiveContentApprovalMode() { return isContentApprovalDisabled() ? "NONE" : "DUAL"; }
    public boolean isContentAutoAttachEnabled() { return contentAutoAttachEnabled; }
    public void setContentAutoAttachEnabled(boolean value) { contentAutoAttachEnabled = value; }
    public String getContentAutoAttachProfiles() { return contentAutoAttachProfiles; }
    public void setContentAutoAttachProfiles(String value) { contentAutoAttachProfiles = value == null ? "" : value; }
    public List<String> contentAutoAttachProfileList() {
        return Arrays.stream(getContentAutoAttachProfiles().split(","))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }
    public String getContentVaultMasterKey() { return contentVaultMasterKey; }
    public void setContentVaultMasterKey(String value) { contentVaultMasterKey = value == null ? "" : value; }
    public String getContentVaultKeyVersion() { return contentVaultKeyVersion; }
    public void setContentVaultKeyVersion(String value) { contentVaultKeyVersion = value; }
}
