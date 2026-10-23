package com.eap.platform.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.observability.otlp.grpc")
public class OtlpGrpcProperties {
    private boolean enabled = true;
    private int port = 4317;
    private int maxInboundMessageBytes = 4_194_304;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getMaxInboundMessageBytes() { return maxInboundMessageBytes; }
    public void setMaxInboundMessageBytes(int maxInboundMessageBytes) { this.maxInboundMessageBytes = maxInboundMessageBytes; }
}
