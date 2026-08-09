package com.eap.platform.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.discovery.ebpf")
public class EbpfDiscoveryProperties {
    private boolean enabled = true;
    private int maxFlows = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxFlows() { return maxFlows; }
    public void setMaxFlows(int maxFlows) { this.maxFlows = maxFlows; }
}
