package com.eap.platform.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.workflow.execution.worker-enabled=false",
        "eap.evaluation.async-worker-enabled=false", "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks", "eap.security.oidc-client-id=eap-console"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class ManagementValuePagingIntegrationTests {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void filtersBeforePagingAndCountsDirectAndAttributedAgents() throws Exception {
        String key = "value-paging-" + UUID.randomUUID();
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 105; i++) {
            insert(key + "-match-" + i, i == 0 ? 1 : 2, key, "CNY", from.plusSeconds(i));
        }
        Long attributedId = db.queryForObject("select id from management_value_events where event_key=?", Long.class, key + "-match-0");
        db.update("insert into management_value_attributions(value_event_id,agent_id,contribution_weight,attribution_method,created_at) values(?,2,1,'BUSINESS_CONFIRMED',CURRENT_TIMESTAMP)", attributedId);
        insert(key + "-old", 2, key, "CNY", from.minusSeconds(1));
        for (int i = 0; i < 140; i++) insert(key + "-other-" + i, 2, key, "USD", from.plusSeconds(1000 + i));

        JsonNode first = page(key, from, 1);
        JsonNode second = page(key, from, 2);
        assertThat(first.path("totalElements").asLong()).isEqualTo(105);
        assertThat(second.path("totalElements").asLong()).isEqualTo(105);
        assertThat(first.path("items").size()).isEqualTo(100);
        assertThat(second.path("items").size()).isEqualTo(5);
        assertThat(first.path("items").get(0).path("eventKey").asText()).isEqualTo(key + "-match-104");
        assertThat(second.path("items").get(4).path("eventKey").asText()).isEqualTo(key + "-match-0");
        assertThat(second.path("items").get(4).path("agentContributions").size()).isEqualTo(1);
        for (JsonNode item : first.path("items")) {
            assertThat(item.path("currency").asText()).isEqualTo("CNY");
            assertThat(item.path("eventKey").asText()).startsWith(key + "-match-");
        }
        mvc.perform(get("/api/management/value-events/page")).andExpect(status().isUnauthorized());
    }

    private JsonNode page(String endpoint, Instant from, int page) throws Exception {
        String content = mvc.perform(get("/api/management/value-events/page")
                        .param("page", Integer.toString(page)).param("size", "100").param("order", "desc")
                        .param("from", from.toString()).param("currency", "CNY").param("agentId", "2")
                        .param("endpointKey", endpoint)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(content).path("data");
    }

    private void insert(String key, int agentId, String endpoint, String currency, Instant observedAt) {
        db.update("insert into management_value_events(event_key,agent_id,endpoint_key,value_type,validated_by,benefit_money,currency,observed_at,created_at) values(?,?,?,'TEST','fixture',1,?,?,CURRENT_TIMESTAMP)",
                key, agentId, endpoint, currency, Timestamp.from(observedAt));
    }
}
