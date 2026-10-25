package com.eap.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class DiagnosticPolicyAccessContractTests {
    static final String PATH="/api/observability/diagnostics/policy";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    RequestPostProcessor role(String name) {
        return jwt().jwt(j->j.subject("public-fixture-"+name)).authorities(new SimpleGrantedAuthority("ROLE_"+name));
    }
    long version() throws Exception {
        var response=mvc.perform(get(PATH).with(role("VIEWER"))).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("version").asLong();
    }

    @ParameterizedTest @CsvSource({"VIEWER,false","OPERATOR,false","GOVERNANCE_ADMIN,true","PLATFORM_ADMIN,true"})
    void policyReadAndPublicationUseTheSameCentralCapability(String name, boolean manage) throws Exception {
        mvc.perform(get("/api/platform/me").with(role(name))).andExpect(jsonPath("$.data.capabilities.governance").value(manage));
        mvc.perform(get(PATH).with(role(name))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.algorithm").value("SHA256_TRACE_V1"));
        var request=mvc.perform(post(PATH).with(role(name)).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("expectedVersion",version(),"sampleBasisPoints",10000))));
        request.andExpect(manage?status().isOk():status().isForbidden());
    }

    @Test void concurrentPublicationCannotOverwriteAnAlreadyPublishedVersion() throws Exception {
        long original=version();
        mvc.perform(post(PATH).with(role("GOVERNANCE_ADMIN")).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("expectedVersion",original,"sampleBasisPoints",0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(original+1));
        mvc.perform(post(PATH).with(role("PLATFORM_ADMIN")).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("expectedVersion",original,"sampleBasisPoints",10000))))
                .andExpect(status().isConflict());
        mvc.perform(get(PATH).with(role("VIEWER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sampleBasisPoints").value(0));
    }

    @Test void invalidBoundsAndCallerRiskOrContentCannotChangePolicy() throws Exception {
        long original=version();
        for(int rate:new int[]{-1,10001}) mvc.perform(post(PATH).with(role("GOVERNANCE_ADMIN"))
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("expectedVersion",original,"sampleBasisPoints",rate))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).with(role("GOVERNANCE_ADMIN")).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("expectedVersion",original,"sampleBasisPoints",0,
                        "risk","LOW","contents","PUBLIC_CONTENT_MARKER"))))
                .andExpect(status().isBadRequest());
        assertThat(version()).isEqualTo(original);
    }

    @Test void anonymousAndRolesWithoutConsoleAccessCannotReadPolicy() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(role("DATA_OWNER"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(role("NONE"))).andExpect(status().isForbidden());
    }
}
