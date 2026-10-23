package com.eap.platform.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.discovery.host-observer")
public class HostObservationProperties {
    private boolean enabled = true;
    private int maxObservations = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxObservations() { return maxObservations; }
    public void setMaxObservations(int maxObservations) { this.maxObservations = maxObservations; }
}
