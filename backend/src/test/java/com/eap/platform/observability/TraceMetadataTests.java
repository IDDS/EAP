package com.eap.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TraceMetadataTests {
    @Test void preservesOnlyFixedRuntimePhaseAndNumericTimingOnRead() throws Exception {
        var json = new ObjectMapper();
        var value = json.readTree(TraceMetadata.project("""
                {"eap.runtime.stage":"WAIT_READY","eap.runtime.deadline_exceeded":"true",
                 "eap.runtime.ready_wait_ms":20000,"eap.runtime.proof_read_ms":"0",
                 "eap.task.key":"original-task","stderr":"PRIVATE","command":"PRIVATE"}
                """));
        assertThat(value.size()).isEqualTo(5);
        assertThat(value.path("eap.runtime.stage").asText()).isEqualTo("WAIT_READY");
        assertThat(value.path("eap.runtime.deadline_exceeded").asText()).isEqualTo("true");
        assertThat(value.path("eap.runtime.ready_wait_ms").asText()).isEqualTo("20000");
        assertThat(value.toString()).doesNotContain("PRIVATE");
    }

    @Test void rejectsUnboundedDiagnosticsAndMalformedHistoricalMetadata() {
        for (String input : new String[]{
                "{\"eap.runtime.stage\":\"exception-with-private-content\"}",
                "{\"eap.runtime.deadline_exceeded\":true}",
                "{\"eap.runtime.deadline_exceeded\":\"unknown\"}",
                "{\"eap.runtime.ready_wait_ms\":-1}",
                "{\"eap.runtime.ready_wait_ms\":1.5}",
                "{\"eap.runtime.ready_wait_ms\":\"PRIVATE\"}",
                "{\"eap.runtime.ready_wait_ms\":\"9999999999999999\"}", "[]", "malformed"}) {
            assertThat(TraceMetadata.project(input)).isEqualTo("{}");
        }
    }
    @Test void preservesReadyStartupOnFailedTaskAndOnHistoricalReads() throws Exception {
        var value = new ObjectMapper().readTree(TraceMetadata.project("""
                {"eap.runtime.stage":"READ_PROOF","eap.runtime.deadline_exceeded":"true",
                 "eap.runtime.startup.version":"1","eap.runtime.startup.observer_import_ms":60000,
                 "eap.runtime.startup.identity_ready_ms":"0","eap.runtime.startup.server_import_ms":"3521",
                 "eap.runtime.startup.command":"PRIVATE","eap.runtime.startup.stderr":"PRIVATE"}
                """));
        assertThat(value.size()).isEqualTo(6);
        assertThat(value.path("eap.runtime.startup.version").asText()).isEqualTo("1");
        assertThat(value.path("eap.runtime.startup.observer_import_ms").asText()).isEqualTo("60000");
        assertThat(value.path("eap.runtime.startup.identity_ready_ms").asText()).isEqualTo("0");
        assertThat(TraceMetadata.project(value.toString())).isEqualTo(value.toString());
        assertThat(value.toString()).doesNotContain("PRIVATE");
    }

    @Test void rejectsInvalidStartupValuesWithoutInventingZeroMeasurements() {
        for (String bad : new String[]{"-1", "60001", "100000", "1.5", "true", "null", "[]", "{}",
                "\"PRIVATE\"", "\"999999999999999999999999\"", "\"1e3\""}) {
            assertThat(TraceMetadata.project("{\"eap.runtime.startup.server_import_ms\":" + bad + "}"))
                    .isEqualTo("{}");
        }
        for (String bad : new String[]{"1", "true", "null", "\"2\""}) {
            assertThat(TraceMetadata.project("{\"eap.runtime.startup.version\":" + bad + "}"))
                    .isEqualTo("{}");
        }
        assertThat(TraceMetadata.project("{\"eap.runtime.stage\":\"WAIT_READY\"}"))
                .doesNotContain("startup");
    }

}
