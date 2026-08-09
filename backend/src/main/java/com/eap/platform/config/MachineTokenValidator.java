package com.eap.platform.config;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class MachineTokenValidator {
    private final SecurityProperties securityProperties;

    public MachineTokenValidator(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public void require(String configured, String provided, String name) {
        String expected = configured == null ? "" : configured.trim();
        if (expected.isBlank() && securityProperties.isDevelopmentMode()) {
            return;
        }
        if (expected.isBlank()) {
            throw new UnauthorizedException(name + "未配置，已拒绝机器上报");
        }
        if (provided == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new UnauthorizedException(name + "无效");
        }
    }
}
