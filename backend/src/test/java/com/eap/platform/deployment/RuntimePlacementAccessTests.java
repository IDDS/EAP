package com.eap.platform.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"eap.security.mode=JWT", "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "spring.flyway.enabled=true", "eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RuntimePlacementAccessTests {
    static final String PATH = "/api/agents/7/substrate/placement", KEY = "eap-agents/probe";
    @Autowired MockMvc mvc;
    @MockitoBean RuntimePlacementService service;
    @Test void anonymousAndNonConsoleIdentitiesCannotReadPlacement() throws Exception {
        mvc.perform(get(PATH).param("instanceKey", KEY)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).param("instanceKey", KEY).with(jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void allFourConsoleRolesReadOnlyTheTypedSnapshot() throws Exception {
        var now = Instant.now();
        when(service.current(7L, KEY)).thenReturn(new RuntimePlacementService.Snapshot(KEY, "UNASSIGNED",
                "ACTOR_NOT_RUNNING", "SUBSTRATE_CONTROL_API", now, now,
                "00000000-0000-4000-8000-000000000001", 9L, "SUSPENDED", null));
        for (String role : new String[]{"VIEWER", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"}) {
            mvc.perform(get(PATH).param("instanceKey", KEY).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UNASSIGNED"))
                    .andExpect(jsonPath("$.data.placement").isEmpty()).andExpect(jsonPath("$.data.source").value("SUBSTRATE_CONTROL_API"));
        }
        verify(service, times(4)).current(7L, KEY); verifyNoMoreInteractions(service);
    }
}
