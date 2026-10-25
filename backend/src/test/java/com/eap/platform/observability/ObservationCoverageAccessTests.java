package com.eap.platform.observability;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class ObservationCoverageAccessTests {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db;
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void allConsoleRolesReadMetadataWithoutContentOrMutation(String role)throws Exception {
        long before=db.queryForObject("select count(*) from observation_operations",Long.class);
        var auth=jwt().jwt(j->j.subject("fixture-"+role)).authorities(new SimpleGrantedAuthority("ROLE_"+role));
        mvc.perform(get("/api/observability/coverage?minutes=60").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.denominatorContract").value("ORIGINAL_SUBMISSION_V1"));
        mvc.perform(get("/api/observability/runtime-placement-sampling").with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queueCapacity").value(32)).andExpect(jsonPath("$.data.workerCapacity").value(2))
                .andExpect(jsonPath("$.data.phases.length()").value(2)).andExpect(jsonPath("$.data.processStartedAt").exists());
        mvc.perform(get("/api/observability/runtime-placement-sampling")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/observability/runtime-placement-sampling").with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_DATA_OWNER")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/observability/coverage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/observability/coverage?minutes=0").with(auth)).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("select count(*) from observation_operations",Long.class)).isEqualTo(before);
    }
}
