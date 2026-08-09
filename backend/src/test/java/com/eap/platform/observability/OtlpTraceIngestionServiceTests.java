package com.eap.platform.observability;

import com.eap.platform.discovery.AgentDiscoveryService;
import com.eap.platform.knowledge.KnowledgeService;
import com.eap.platform.telemetry.ModelObservationSource;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtlpTraceIngestionServiceTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentDiscoveryService discoveryService = mock(AgentDiscoveryService.class);
    private final ModelTelemetryService telemetryService = mock(ModelTelemetryService.class);
    private final ContentObservationService contentObservationService = mock(ContentObservationService.class);
    private final TraceSpanObservationRepository traceSpanRepository = mock(TraceSpanObservationRepository.class);
    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);

    @Test
    void normalizesCurrentGenAiAttributesAndRedactsSensitiveInput() throws Exception {
        OtlpTraceIngestionService service = new OtlpTraceIngestionService(
                objectMapper, discoveryService, telemetryService, contentObservationService,
                new SensitiveContentSanitizer(), traceSpanRepository, knowledgeService
        );
        when(traceSpanRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        JsonNode payload = objectMapper.readTree("""
                {
                  "resourceSpans": [{
                    "resource": {"attributes": [
                      {"key":"service.name","value":{"stringValue":"finance-agent"}},
                      {"key":"service.instance.id","value":{"stringValue":"finance-agent-7"}},
                      {"key":"host.name","value":{"stringValue":"worker-01"}},
                      {"key":"telemetry.sdk.name","value":{"stringValue":"opentelemetry"}},
                      {"key":"telemetry.sdk.version","value":{"stringValue":"1.50.0"}}
                    ]},
                    "scopeSpans": [{"spans": [{
                      "traceId":"00112233445566778899aabbccddeeff",
                      "spanId":"0011223344556677",
                      "name":"chat demo-model",
                      "startTimeUnixNano":"1786240000000000000",
                      "endTimeUnixNano":"1786240000250000000",
                      "status":{"code":"STATUS_CODE_OK"},
                      "attributes": [
                        {"key":"gen_ai.provider.name","value":{"stringValue":"openai"}},
                        {"key":"gen_ai.request.model","value":{"stringValue":"demo-model"}},
                        {"key":"gen_ai.usage.input_tokens","value":{"intValue":"21"}},
                        {"key":"gen_ai.usage.output_tokens","value":{"intValue":"9"}},
                        {"key":"gen_ai.input.messages","value":{"stringValue":"手机号 13800138000，邮箱 finance@example.com"}},
                        {"key":"gen_ai.output.messages","value":{"stringValue":"合规摘要已生成"}}
                      ]
                    }]}]
                  }]
                }
                """);

        OtlpTraceIngestionDtos.IngestionResponse response = service.ingest(payload);

        assertThat(response.spansScanned()).isEqualTo(1);
        assertThat(response.genAiSpansDetected()).isEqualTo(1);
        assertThat(response.invocationsRecorded()).isEqualTo(1);
        assertThat(response.contentObservationsRecorded()).isEqualTo(2);
        assertThat(response.traceSpansRecorded()).isEqualTo(1);

        ArgumentCaptor<ModelTelemetryDtos.ModelInvocationRequest> invocation = ArgumentCaptor.forClass(ModelTelemetryDtos.ModelInvocationRequest.class);
        ArgumentCaptor<ModelObservationSource> source = ArgumentCaptor.forClass(ModelObservationSource.class);
        verify(telemetryService).recordPassive(invocation.capture(), source.capture());
        assertThat(source.getValue()).isEqualTo(ModelObservationSource.OTLP);
        assertThat(invocation.getValue().provider()).isEqualTo("openai");
        assertThat(invocation.getValue().modelName()).isEqualTo("demo-model");
        assertThat(invocation.getValue().inputTokens()).isEqualTo(21);
        assertThat(invocation.getValue().outputTokens()).isEqualTo(9);
        assertThat(invocation.getValue().latencyMs()).isEqualTo(250);
        assertThat(invocation.getValue().endpointKey()).startsWith("otlp-finance-agent-");

        ArgumentCaptor<ContentObservationService.CaptureCommand> observations = ArgumentCaptor.forClass(ContentObservationService.CaptureCommand.class);
        verify(contentObservationService, times(2)).capture(observations.capture());
        ContentObservationService.CaptureCommand input = observations.getAllValues().getFirst();
        assertThat(input.direction()).isEqualTo(ContentDirection.INPUT);
        assertThat(input.redactedContent()).contains("[REDACTED_PHONE]", "[REDACTED_EMAIL]");
        assertThat(input.redactedContent()).doesNotContain("13800138000", "finance@example.com");
        assertThat(input.governanceAction()).isEqualTo("PASSIVE_REDACT");
    }

    @Test
    void recordsToolMcpRagAndA2aSpansWithContentSeparated() throws Exception {
        OtlpTraceIngestionService service = new OtlpTraceIngestionService(
                objectMapper, discoveryService, telemetryService, contentObservationService,
                new SensitiveContentSanitizer(), traceSpanRepository, knowledgeService
        );
        when(traceSpanRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        JsonNode payload = objectMapper.readTree("""
                {"resourceSpans":[{"resource":{"attributes":{"service.name":"agent-runtime"}},"scopeSpans":[{"spans":[
                  {"traceId":"t1","spanId":"s1","name":"tool execute","attributes":{"tool.name":"search","tool.input":"secret=abc","tool.output":"done"}},
                  {"traceId":"t1","spanId":"s2","name":"mcp call","attributes":{"mcp.server.name":"filesystem","mcp.request":"list","mcp.response":"ok"}},
                  {"traceId":"t1","spanId":"s3","name":"retrieval query","attributes":{"rag.source":"kb-main","rag.query":"policy","rag.documents":"doc-1"}},
                  {"traceId":"t1","spanId":"s4","name":"agent handoff","attributes":{"a2a.peer.agent":"reviewer","a2a.message.input":"review","a2a.message.output":"accepted"}}
                ]}]}]}
                """);

        OtlpTraceIngestionDtos.IngestionResponse response = service.ingest(payload);

        assertThat(response.traceSpansRecorded()).isEqualTo(4);
        assertThat(response.toolSpansRecorded()).isEqualTo(1);
        assertThat(response.mcpSpansRecorded()).isEqualTo(1);
        assertThat(response.ragSpansRecorded()).isEqualTo(1);
        assertThat(response.a2aSpansRecorded()).isEqualTo(1);
        assertThat(response.knowledgeInteractionsRecorded()).isEqualTo(1);
        assertThat(response.contentObservationsRecorded()).isEqualTo(8);
        verify(traceSpanRepository, times(4)).save(org.mockito.ArgumentMatchers.any());
        verify(contentObservationService, times(8)).capture(org.mockito.ArgumentMatchers.any(ContentObservationService.CaptureCommand.class));
    }
}
