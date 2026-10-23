package com.eap.platform.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineTokenValidatorTests {

    @Test
    void developmentModeKeepsBlankTokenCompatibleForLocalDemo() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityProperties.Mode.DEVELOPMENT);

        new MachineTokenValidator(properties).require("", null, "测试凭证");
    }

    @Test
    void jwtModeRejectsMissingConfiguredToken() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityProperties.Mode.JWT);

        assertThatThrownBy(() -> new MachineTokenValidator(properties).require("", null, "测试凭证"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void configuredTokenMustMatchExactly() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityProperties.Mode.JWT);
        MachineTokenValidator validator = new MachineTokenValidator(properties);

        validator.require("machine-secret", "machine-secret", "测试凭证");
        assertThatThrownBy(() -> validator.require("machine-secret", "machine-secret-typo", "测试凭证"))
                .isInstanceOf(UnauthorizedException.class);
    }
}
