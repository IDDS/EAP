package com.eap.platform.evaluation;

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
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap", "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.workflow.execution.worker-enabled=false", "eap.evaluation.async-worker-enabled=false", "eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class EvaluationDatasetLookupIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EvaluationDatasetRepository datasets;
    @Autowired EvaluationDatasetItemRepository items;
    @Autowired com.eap.platform.audit.AuditLogRepository audit;

    private EvaluationDataset dataset(String key) {
        var row = new EvaluationDataset(); row.datasetKey = key; row.name = "synthetic lookup";
        row.description = "private description must not be returned";
        return datasets.saveAndFlush(row);
    }
    @Test void findsBeyondFiveHundredWithinItsDatasetAndReturnsOnlyMetadata() throws Exception {
        var ds = dataset("LOOKUP/汉?#");
        var other = dataset("LOOKUP_OTHER");
        List<EvaluationDatasetItem> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            var item = new EvaluationDatasetItem(); item.datasetId = ds.id; item.itemKey = "case-" + i;
            item.evaluatorKey = "ACTION_COMPLETION"; item.inputJson = "{\"private\":\"input\"}"; item.actualJson = "{\"private\":\"output\"}";
            rows.add(item);
        }
        rows.get(500).itemKey = "Case/?#汉";
        items.saveAllAndFlush(rows);
        long itemCount = items.count(), auditCount = audit.count(), datasetCount = datasets.count();
        var result = mvc.perform(get("/api/evaluations/dataset-items/lookup").param("datasetKey", " lookup/汉?# ").param("itemKey", "Case/?#汉")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("FOUND"))
                .andExpect(jsonPath("$.data.recordId").value(rows.get(500).id)).andReturn().getResponse().getContentAsString();
        var fields = new ArrayList<String>(); mapper.readTree(result).path("data").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("kind", "state", "datasetKey", "itemKey", "recordId", "createdAt");
        assertThat(result).doesNotContain("private", "input", "actual", "annotation", "groundTruth");
        mvc.perform(get("/api/evaluations/dataset-items/lookup").param("datasetKey", other.datasetKey).param("itemKey", "Case/?#汉")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("NOT_FOUND"));
        mvc.perform(get("/api/evaluations/datasets/lookup").param("datasetKey", ds.datasetKey).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("FOUND")).andExpect(jsonPath("$.data.recordId").value(ds.id))
                .andExpect(jsonPath("$.data.description").doesNotExist()).andExpect(jsonPath("$.data.name").doesNotExist());
        assertThat(items.count()).isEqualTo(itemCount); assertThat(audit.count()).isEqualTo(auditCount); assertThat(datasets.count()).isEqualTo(datasetCount);
    }
    @ParameterizedTest @CsvSource({"VIEWER,true", "OPERATOR,true", "GOVERNANCE_ADMIN,true", "PLATFORM_ADMIN,true", "DATA_OWNER,false", "NONE,false"})
    void lookupUsesViewConsoleCapability(String role, boolean allowed) throws Exception {
        var identity = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
        mvc.perform(get("/api/evaluations/datasets/lookup").param("datasetKey", "MISSING").with(identity)).andExpect(allowed ? status().isOk() : status().isForbidden());
        mvc.perform(get("/api/evaluations/dataset-items/lookup").param("datasetKey", "MISSING").param("itemKey", "case").with(identity)).andExpect(allowed ? status().isOk() : status().isForbidden());
    }
    @Test void anonymousAndInvalidQueriesAreRejected() throws Exception {
        mvc.perform(get("/api/evaluations/datasets/lookup").param("datasetKey", "MISSING")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/evaluations/dataset-items/lookup").param("datasetKey", "MISSING").param("itemKey", "case")).andExpect(status().isUnauthorized());
        var identity = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
        for (String key : List.of(" ", "x".repeat(129))) {
            mvc.perform(get("/api/evaluations/datasets/lookup").param("datasetKey", key).with(identity)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/evaluations/dataset-items/lookup").param("datasetKey", "DS").param("itemKey", key).with(identity)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/evaluations/dataset-items/lookup").param("datasetKey", "DS").with(identity)).andExpect(status().isBadRequest());
    }
}
