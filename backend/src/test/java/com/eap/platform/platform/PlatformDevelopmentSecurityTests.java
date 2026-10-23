package com.eap.platform.platform;

import com.eap.platform.config.PlatformPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "eap.security.mode=DEVELOPMENT", "eap.security.oidc-client-id=",
        "spring.flyway.enabled=true", "eap.substrate.deploy-worker-enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PlatformDevelopmentSecurityTests {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void developmentGrantsAllCapabilitiesWithoutATokenAndOmitsOidcWhenUnconfigured() throws Exception {
        var statusResponse = mockMvc.perform(get("/api/platform/status")).andExpect(status().isOk()).andReturn().getResponse();
        var statusData = objectMapper.readTree(statusResponse.getContentAsString()).path("data");
        assertTrue(statusData.has("oidc"));
        assertTrue(statusData.path("oidc").isNull());
        var response = mockMvc.perform(get("/api/platform/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("DEVELOPMENT"))
                .andReturn().getResponse();
        var capabilities = objectMapper.readTree(response.getContentAsString()).path("data").path("capabilities");
        assertEquals(PlatformPermission.values().length, capabilities.size());
        capabilities.forEach(value -> assertTrue(value.asBoolean()));
    }
}
