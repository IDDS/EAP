package com.eap.platform.observability;

import com.eap.platform.common.ObservationHistoryQuery;
import com.eap.platform.discovery.AgentDiscoveryService;
import com.eap.platform.knowledge.KnowledgeService;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpProtocolObservationTests {
    private final ObjectMapper json = new ObjectMapper();
    private final TraceSpanObservationRepository spans = mock(TraceSpanObservationRepository.class);
    private final ToolInvocationService tools = mock(ToolInvocationService.class);
    private final ModelTelemetryService models = mock(ModelTelemetryService.class);
    private final ContentObservationService content = mock(ContentObservationService.class);
    private final OtlpTraceIngestionService ingestion = new OtlpTraceIngestionService(
            json, mock(AgentDiscoveryService.class), models, content,
            new SensitiveContentSanitizer(), spans, mock(KnowledgeService.class), tools);

    @ParameterizedTest
    @CsvSource({
            "SUCCEEDED,TOOL_RESULT,PROTOCOL_RESPONSE,1,SUCCEEDED",
            "FAILED,TOOL_ERROR,PROTOCOL_RESPONSE,2,FAILED",
            "FAILED,RPC_ERROR,PROTOCOL_RESPONSE,STATUS_CODE_ERROR,FAILED",
            "UNKNOWN,STREAM_CLOSED,NO_CONFIRMED_RESPONSE,0,UNKNOWN",
            "UNKNOWN,CANCEL_REQUESTED,NO_CONFIRMED_RESPONSE,1,UNKNOWN",
            "SUCCEEDED,TOOL_RESULT,PROTOCOL_RESPONSE,0,UNKNOWN",
            "SUCCEEDED,TOOL_RESULT,PROTOCOL_RESPONSE,2,UNKNOWN",
            "FAILED,TOOL_ERROR,PROTOCOL_RESPONSE,1,UNKNOWN",
            "SUCCEEDED,STREAM_CLOSED,NO_CONFIRMED_RESPONSE,1,UNKNOWN",
            "PRIVATE_BODY,TOOL_RESULT,PROTOCOL_RESPONSE,1,UNKNOWN"
    })
    void storesOnlyConsistentProtocolOutcomes(String outcome, String reason, String evidence,
                                             String code, String expected) throws Exception {
        ObjectNode payload = payload(outcome, reason, evidence, code);
        ingestion.ingestScoped(payload, 75L, "runtime-observer-fixture");
        assertRecords(expected);
        verifyNoInteractions(models, content);
    }

    @Test
    void rejectsPartialUnsupportedOrWronglyTypedContracts() throws Exception {
        for (String field : new String[]{"contract", "outcome", "effect"}) {
            ObjectNode payload = payload("SUCCEEDED", "TOOL_RESULT", "PROTOCOL_RESPONSE", "1");
            ObjectNode attributes = attrs(payload);
            attributes.put("eap.observation." + field, "PRIVATE_UNSUPPORTED");
            ingestion.ingest(payload);
            assertRecords("UNKNOWN");
            clearInvocations(spans, tools);
        }
        ObjectNode payload = payload("SUCCEEDED", "TOOL_RESULT", "PROTOCOL_RESPONSE", "1");
        attrs(payload).remove("eap.observation.contract");
        attrs(payload).putObject("eap.observation.outcome").put("value", "SUCCEEDED");
        ingestion.ingest(payload);
        assertRecords("UNKNOWN");
    }

    @Test
    void legacyMcpUnsetAndNonMcpModelSemanticsRemainUnchanged() throws Exception {
        ObjectNode payload = payload("UNKNOWN", "STREAM_CLOSED", "NO_CONFIRMED_RESPONSE", "0");
        attrs(payload).remove(McpProtocolObservation.VALUES.keySet());
        ingestion.ingest(payload);
        assertRecords("SUCCEEDED");
        clearInvocations(spans, tools);
        payload = payload("UNKNOWN", "STREAM_CLOSED", "NO_CONFIRMED_RESPONSE", "0");
        attrs(payload).remove("mcp.server.name");
        attrs(payload).put("gen_ai.request.model", "fixture-model");
        span(payload).put("name", "chat fixture-model");
        ingestion.ingest(payload);
        var capture = ArgumentCaptor.forClass(TraceSpanObservation.class);
        verify(spans).save(capture.capture());
        assertThat(capture.getValue().spanKind).isEqualTo(TraceSpanKind.MODEL);
        assertThat(capture.getValue().status).isEqualTo("SUCCEEDED");
        verifyNoInteractions(tools);
    }

    @Test
    void publicMetadataUsesClosedEnumsAndNeverClaimsVerifiedEffect() throws Exception {
        ObjectNode payload = payload("UNKNOWN", "STREAM_CLOSED", "NO_CONFIRMED_RESPONSE", "0");
        attrs(payload).put("mcp.response", "PRIVATE_RESULT");
        attrs(payload).put("eap.observation.effect", "VERIFIED");
        attrs(payload).put("eap.observation.reason", "PRIVATE_REASON");
        String projected = TraceMetadata.project(attrs(payload).toString());
        assertThat(projected).contains("MCP_PROTOCOL_V1", "UNKNOWN", "NO_CONFIRMED_RESPONSE")
                .doesNotContain("PRIVATE", "VERIFIED", "mcp.response", "eap.observation.reason");
        assertThat(TraceMetadata.project(projected)).isEqualTo(projected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownTraceStatusCanBeFilteredInPublicHistory() {
        when(spans.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        var query = new ObservationHistoryQuery();
        query.setStatus("UNKNOWN");
        ingestion.page(query);
        verify(spans).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void consumesActualRelayFixtureWhenProvided() throws Exception {
        // The remote contract job supplies output from real MCP subprocesses.
        // Keep a checked-in fixture for ordinary regression test runs.
        String fixture = System.getenv("EAP_MCP_OTLP_FIXTURE");
        JsonNode cases = fixture == null
                ? json.readTree(getClass().getResourceAsStream("/mcp-protocol-fixture.json"))
                : json.readTree(java.nio.file.Path.of(fixture).toFile());
        assertThat(cases.size()).isEqualTo(3);
        for (JsonNode item : cases) {
            ingestion.ingestScoped(item.path("otlp"), 75L, "runtime-observer-fixture");
            assertRecords(item.path("event").path("status").asText());
            verifyNoInteractions(models, content);
            clearInvocations(spans, tools);
        }
    }

    private void assertRecords(String expected) throws Exception {
        var trace = ArgumentCaptor.forClass(TraceSpanObservation.class);
        verify(spans).save(trace.capture());
        assertThat(trace.getValue().spanKind).isEqualTo(TraceSpanKind.MCP);
        assertThat(trace.getValue().status).isEqualTo(expected);
        assertThat(trace.getValue().attributesJson).doesNotContain("PRIVATE");
        var tool = ArgumentCaptor.forClass(ToolInvocationService.RecordCommand.class);
        verify(tools).record(tool.capture());
        assertThat(tool.getValue().status().name()).isEqualTo(expected);
        assertThat(tool.getValue().observationSource()).isEqualTo(ToolInvocationSource.OTLP);
        assertThat(tool.getValue().inputObservationKey()).isNull();
        assertThat(tool.getValue().outputObservationKey()).isNull();
    }

    private ObjectNode payload(String outcome, String reason, String evidence, String code) throws Exception {
        ObjectNode payload = (ObjectNode) json.readTree("""
            {"resourceSpans":[{"scopeSpans":[{"spans":[{
              "traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","spanId":"bbbbbbbbbbbbbbbb",
              "name":"mcp tools/call","attributes":{"mcp.server.name":"fixture","tool.name":"calculate_sum",
                "rpc.method":"tools/call","eap.observation.contract":"MCP_PROTOCOL_V1",
                "eap.observation.effect":"UNVERIFIED"},"status":{}
            }]}]}]}
            """);
        attrs(payload).put("eap.observation.outcome", outcome).put("eap.observation.reason", reason)
                .put("eap.observation.evidence", evidence);
        ((ObjectNode) span(payload).path("status")).put("code", code);
        return payload;
    }

    private ObjectNode span(JsonNode payload) {
        return (ObjectNode) payload.path("resourceSpans").get(0).path("scopeSpans").get(0).path("spans").get(0);
    }

    private ObjectNode attrs(JsonNode payload) {
        return (ObjectNode) span(payload).path("attributes");
    }
}
