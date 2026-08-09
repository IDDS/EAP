package com.eap.platform.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.knowledge")
public class KnowledgeProperties {
    private String ingestionToken = "";

    public String getIngestionToken() {
        return ingestionToken;
    }

    public void setIngestionToken(String ingestionToken) {
        this.ingestionToken = ingestionToken == null ? "" : ingestionToken;
    }
}
