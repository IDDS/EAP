package com.eap.platform.monitoring;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component
@ConfigurationProperties(prefix="eap.substrate.monitoring")
public class SubstrateMonitoringProperties {
    private String prometheusUrl = "";
    public String getPrometheusUrl() { return prometheusUrl; }
    public void setPrometheusUrl(String value) { prometheusUrl = value == null ? "" : value; }
}
