package com.eap.platform.management;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.management")
public class ManagementProperties {
    private String ingestionToken = "";

    public String getIngestionToken() {
        return ingestionToken;
    }

    public void setIngestionToken(String ingestionToken) {
        this.ingestionToken = ingestionToken == null ? "" : ingestionToken;
    }
}
