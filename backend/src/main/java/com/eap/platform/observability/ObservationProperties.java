package com.eap.platform.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.observability")
public class ObservationProperties {
    private boolean enabled = true;
    private ContentCaptureMode captureMode = ContentCaptureMode.HASH_ONLY;
    private long maxContentBytes = 65_536;
    private int maxListItems = 200;
    private String ingestionToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ContentCaptureMode getCaptureMode() {
        return captureMode == null ? ContentCaptureMode.HASH_ONLY : captureMode;
    }

    public void setCaptureMode(ContentCaptureMode captureMode) {
        this.captureMode = captureMode;
    }

    public long getMaxContentBytes() {
        return maxContentBytes;
    }

    public void setMaxContentBytes(long maxContentBytes) {
        this.maxContentBytes = Math.max(1024, Math.min(maxContentBytes, 4_194_304));
    }

    public int getMaxListItems() {
        return maxListItems;
    }

    public void setMaxListItems(int maxListItems) {
        this.maxListItems = Math.max(1, Math.min(maxListItems, 500));
    }

    public String getIngestionToken() {
        return ingestionToken == null ? "" : ingestionToken;
    }

    public void setIngestionToken(String ingestionToken) {
        this.ingestionToken = ingestionToken == null ? "" : ingestionToken;
    }
}
