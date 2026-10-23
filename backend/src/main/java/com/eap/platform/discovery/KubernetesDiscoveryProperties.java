package com.eap.platform.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.discovery.kubernetes")
public class KubernetesDiscoveryProperties {
    private boolean enabled = true;
    private int maxPayloadBytes = 1_048_576;
    private int maxContainers = 256;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxPayloadBytes() { return maxPayloadBytes; }
    public void setMaxPayloadBytes(int maxPayloadBytes) { this.maxPayloadBytes = maxPayloadBytes; }
    public int getMaxContainers() { return maxContainers; }
    public void setMaxContainers(int maxContainers) { this.maxContainers = maxContainers; }
}
