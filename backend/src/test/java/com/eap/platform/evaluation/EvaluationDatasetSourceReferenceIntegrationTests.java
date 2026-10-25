package com.eap.platform.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap", "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.workflow.execution.worker-enabled=false", "eap.evaluation.async-worker-enabled=false", "eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class EvaluationDatasetSourceReferenceIntegrationTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired EvaluationDatasetRepository datasets; @Autowired EvaluationDatasetItemRepository items;
    @Autowired EntityManager em;
    Long agent;
    @BeforeEach void setup() {
        var ds = new EvaluationDataset(); ds.datasetKey = "SOURCE_FIXTURE"; ds.name = "synthetic source fixture"; datasets.saveAndFlush(ds);
        agent = jdbc.queryForObject("select min(id) from agents", Long.class);
        jdbc.update("insert into trace_span_observations(trace_id,span_id,endpoint_key,agent_id,span_name,span_kind,duration_ms,observed_at,created_at) values('source-trace','source-span','fixture-endpoint',?,'private span name','AGENT',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", agent);
    }
    private String body(boolean checked) throws Exception {
        return mapper.writeValueAsString(Map.of("itemKey", "source-case", "evaluatorKey", "ACTION_COMPLETION", "agentId", agent,
                "traceId", "source-trace", "spanId", "source-span", "input", Map.of("task", "synthetic"), "actual", Map.of("completed", true), "verifySourceReference", checked));
    }
    @Test void freezesSnapshotAcrossObservationChangeRemovalAndAnnotation() throws Exception {
        var created = mvc.perform(post("/api/evaluations/datasets/SOURCE_FIXTURE/items").contentType("application/json").content(body(true))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceReference.state").value("OBSERVATION_MATCHED"))
                .andExpect(jsonPath("$.data.sourceReference.endpointKey").value("fixture-endpoint"))
                .andReturn().getResponse().getContentAsString();
        var snapshot = mapper.readTree(created).path("data").path("sourceReference");
        assertThat(snapshot.has("attributesJson")).isFalse(); assertThat(created).doesNotContain("private span name");
        em.flush(); em.clear();
        jdbc.update("update trace_span_observations set endpoint_key='changed-endpoint',agent_id=null where trace_id='source-trace'");
        jdbc.update("delete from trace_span_observations where trace_id='source-trace'");
        mvc.perform(patch("/api/evaluations/datasets/SOURCE_FIXTURE/items/source-case/annotation").contentType("application/json")
                .content("{\"groundTruthLabel\":\"PASS\"}").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isOk());
        em.flush(); em.clear();
        var loaded = mvc.perform(get("/api/evaluations/datasets/SOURCE_FIXTURE/items").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(loaded).path("data").get(0).path("sourceReference")).isEqualTo(snapshot);
    }
    @Test void missingReferenceRejectsWholeWriteAndCount() throws Exception {
        jdbc.update("delete from trace_span_observations where trace_id='source-trace'");
        long count = items.count();
        mvc.perform(post("/api/evaluations/datasets/SOURCE_FIXTURE/items").contentType("application/json").content(body(true))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))).andExpect(status().isBadRequest());
        assertThat(items.count()).isEqualTo(count);
        assertThat(datasets.findByDatasetKey("SOURCE_FIXTURE").orElseThrow().itemCount).isZero();
    }
    @Test void oldClientStaysManualEvenIfMatchingObservationExists() throws Exception {
        var payload = mapper.readTree(body(false)); ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("verifySourceReference");
        mvc.perform(post("/api/evaluations/datasets/SOURCE_FIXTURE/items").contentType("application/json").content(payload.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceReference.state").value("MANUAL_UNVERIFIED"))
                .andExpect(jsonPath("$.data.sourceReference.observationId").isEmpty());
    }
    @ParameterizedTest @CsvSource({"VIEWER,false", "GOVERNANCE_ADMIN,false", "OPERATOR,true", "PLATFORM_ADMIN,true"})
    void usesExistingManageEvaluationsPermission(String role, boolean allowed) throws Exception {
        long count = items.count();
        mvc.perform(post("/api/evaluations/datasets/SOURCE_FIXTURE/items").contentType("application/json").content(body(true))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().is(allowed ? 200 : 403));
        assertThat(items.count()).isEqualTo(count + (allowed ? 1 : 0));
    }
}
