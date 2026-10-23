package com.eap.platform.gateway;

import com.eap.platform.governance.GovernanceDataAction;
import com.eap.platform.governance.GovernanceApprovalStatus;
import com.eap.platform.governance.GovernanceDataDtos;
import com.eap.platform.governance.GovernanceGatewayDirective;
import com.eap.platform.governance.GovernancePreflightService;
import com.eap.platform.telemetry.ModelInvocationStatus;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.eap.platform.observability.ContentObservationService;
import com.eap.platform.observability.ToolInvocationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class OpenAiCompatibleGatewayServiceTests {
    private final GovernancePreflightService preflightService = mock(GovernancePreflightService.class);
    private final ModelTelemetryService telemetryService = mock(ModelTelemetryService.class);
    private final ContentObservationService contentObservationService = mock(ContentObservationService.class);
    private final ToolInvocationService toolInvocationService = mock(ToolInvocationService.class);
    private final OpenAiUpstreamClient upstreamClient = mock(OpenAiUpstreamClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenAiCompatibleGatewayService service;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.setEnabled(true);
        properties.setUpstreamBaseUrl("http://upstream.example");
        properties.setProvider("openai");
        service = new OpenAiCompatibleGatewayService(properties, preflightService, telemetryService, contentObservationService, toolInvocationService, objectMapper, upstreamClient);
    }

    @Test
    void staleApprovedPreflightCannotBypassTheExplicitReuseGuardInEitherGatewayPath() throws Exception {
        JsonNode request = objectMapper.readTree("{\"model\":\"gpt-test\",\"messages\":[{\"role\":\"user\",\"content\":\"public fixture\"}]}");
        JsonNode streamRequest = request.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) streamRequest).put("stream", true);
        for (String state : List.of("CHANGED", "UNRECORDED")) {
            when(preflightService.evaluate(any())).thenReturn(new GovernanceDataDtos.PreflightResponse(
                    1L,"old-approved",1L,GovernanceDataAction.APPROVAL_REQUIRED,GovernanceGatewayDirective.PROCEED_WITH_WARNING,true,1,List.of(),List.of(),
                    "fixture","openai","gpt-test",GovernanceApprovalStatus.APPROVED,"reviewer",null,Instant.now(),"fingerprint",Instant.now(),state));
            assertThatThrownBy(() -> service.chatCompletions(request,1L,null,null,"trace-context",GatewayDtos.ClassificationHints.empty()))
                    .isInstanceOf(GatewayException.class).hasMessageContaining("审批依据");
            assertThatThrownBy(() -> service.chatCompletionsStream(streamRequest,1L,null,null,"trace-context-stream",GatewayDtos.ClassificationHints.empty()))
                    .isInstanceOf(GatewayException.class).hasMessageContaining("审批依据");
        }
        org.mockito.Mockito.verifyNoInteractions(upstreamClient);
    }

    @Test
    void blocksSecretBeforeCallingUpstream() throws Exception {
        when(preflightService.evaluate(any())).thenReturn(decision("input-block", GovernanceGatewayDirective.BLOCK, false));

        JsonNode request = objectMapper.readTree("{\"model\":\"gpt-test\",\"messages\":[{\"role\":\"user\",\"content\":\"normal text\"}]}");
        var hints = new GatewayDtos.ClassificationHints("COMPANY_SECRET", null, true, true, List.of("SOURCE_CODE"));

        assertThatThrownBy(() -> service.chatCompletions(request, 1L, null, null, "trace-1", hints))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("BLOCK");
        verify(upstreamClient, never()).chatCompletions(any());
        verify(telemetryService, never()).recordGateway(any());
    }

    @Test
    void forwardsAllowedRequestAndRecordsTelemetryAfterOutputCheck() throws Exception {
        when(preflightService.evaluate(any()))
                .thenReturn(decision("input-allow", GovernanceGatewayDirective.PROCEED, true))
                .thenReturn(decision("output-allow", GovernanceGatewayDirective.PROCEED, true));
        JsonNode body = objectMapper.readTree("{\"id\":\"chat-1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}");
        when(upstreamClient.chatCompletions(any())).thenReturn(ResponseEntity.ok(body));

        JsonNode request = objectMapper.readTree("{\"model\":\"gpt-test\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        ResponseEntity<JsonNode> response = service.chatCompletions(request, 1L, null, 2L, "trace-2", GatewayDtos.ClassificationHints.empty());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-EAP-Governance-Decision")).isEqualTo("input-allow");
        assertThat(response.getHeaders().getFirst("X-EAP-Output-Governance-Decision")).isEqualTo("output-allow");
        ArgumentCaptor<ModelTelemetryDtos.ModelInvocationRequest> telemetry = ArgumentCaptor.forClass(ModelTelemetryDtos.ModelInvocationRequest.class);
        verify(telemetryService).recordGateway(telemetry.capture());
        assertThat(telemetry.getValue().status()).isEqualTo(ModelInvocationStatus.SUCCEEDED);
        assertThat(telemetry.getValue().inputTokens()).isEqualTo(12);
        assertThat(telemetry.getValue().outputTokens()).isEqualTo(3);
        assertThat(telemetry.getValue().governanceDecisionKey()).isEqualTo("input-allow");
        verify(preflightService, times(2)).evaluate(any());
    }

    @Test
    void redactsThenRechecksBeforeForwarding() throws Exception {
        when(preflightService.evaluate(any()))
                .thenReturn(decision("redact", GovernanceGatewayDirective.REDACT_AND_RECHECK, false))
                .thenReturn(decision("redacted-allow", GovernanceGatewayDirective.PROCEED, true))
                .thenReturn(decision("output-allow", GovernanceGatewayDirective.PROCEED, true));
        JsonNode body = objectMapper.readTree("{\"choices\":[{\"message\":{\"content\":\"done\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}");
        when(upstreamClient.chatCompletions(any())).thenReturn(ResponseEntity.ok(body));
        JsonNode request = objectMapper.readTree("{\"model\":\"gpt-test\",\"messages\":[{\"role\":\"user\",\"content\":\"email=a@example.com password=abc123\"}]}");

        service.chatCompletions(request, 1L, null, null, "trace-3", GatewayDtos.ClassificationHints.empty());

        ArgumentCaptor<JsonNode> forwarded = ArgumentCaptor.forClass(JsonNode.class);
        verify(upstreamClient).chatCompletions(forwarded.capture());
        String serialized = forwarded.getValue().toString();
        assertThat(serialized).doesNotContain("a@example.com").doesNotContain("abc123").contains("REDACTED");

        ArgumentCaptor<GovernanceDataDtos.PreflightRequest> preflightRequests = ArgumentCaptor.forClass(GovernanceDataDtos.PreflightRequest.class);
        verify(preflightService, times(3)).evaluate(preflightRequests.capture());
        assertThat(preflightRequests.getAllValues().getFirst().detectedTypes()).contains("EMAIL", "PII", "CREDENTIAL");
        assertThat(preflightRequests.getAllValues().get(1).detectedTypes()).isEmpty();

        ArgumentCaptor<ContentObservationService.CaptureCommand> observations = ArgumentCaptor.forClass(ContentObservationService.CaptureCommand.class);
        verify(contentObservationService, times(2)).capture(observations.capture());
        ContentObservationService.CaptureCommand inputObservation = observations.getAllValues().getFirst();
        assertThat(inputObservation.governanceAction()).isEqualTo("REDACT_AND_RECHECK");
        assertThat(inputObservation.sensitivity()).isEqualTo("INTERNAL");
        assertThat(inputObservation.content()).contains("a@example.com", "abc123");
        assertThat(inputObservation.redactedContent()).doesNotContain("a@example.com", "abc123").contains("REDACTED");
    }

    @Test
    void buffersAndGovernedForwardsSseStream() throws Exception {
        when(preflightService.evaluate(any()))
                .thenReturn(decision("stream-input", GovernanceGatewayDirective.PROCEED, true))
                .thenReturn(decision("stream-output", GovernanceGatewayDirective.PROCEED, true));
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                + "data: [DONE]\n\n";
        when(upstreamClient.chatCompletionsStream(any())).thenReturn(ResponseEntity.ok(stream));
        JsonNode request = objectMapper.readTree("{\"model\":\"gpt-test\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        ResponseEntity<String> response = service.chatCompletionsStream(request, 1L, null, null, "trace-stream", GatewayDtos.ClassificationHints.empty());

        assertThat(response.getHeaders().getFirst("X-EAP-Governance-Decision")).isEqualTo("stream-input");
        assertThat(response.getHeaders().getFirst("X-EAP-Output-Governance-Decision")).isEqualTo("stream-output");
        assertThat(response.getBody()).contains("hel").contains("lo").contains("[DONE]");
        ArgumentCaptor<ModelTelemetryDtos.ModelInvocationRequest> telemetry = ArgumentCaptor.forClass(ModelTelemetryDtos.ModelInvocationRequest.class);
        verify(telemetryService).recordGateway(telemetry.capture());
        assertThat(telemetry.getValue().status()).isEqualTo(ModelInvocationStatus.SUCCEEDED);
    }

    private GovernanceDataDtos.PreflightResponse decision(String key, GovernanceGatewayDirective directive, boolean allowed) {
        GovernanceDataAction action = switch (directive) {
            case PROCEED -> GovernanceDataAction.ALLOW;
            case PROCEED_WITH_WARNING -> GovernanceDataAction.WARN;
            case REDACT_AND_RECHECK -> GovernanceDataAction.REDACT;
            case REQUEST_APPROVAL -> GovernanceDataAction.APPROVAL_REQUIRED;
            case BLOCK -> GovernanceDataAction.BLOCK;
        };
        return new GovernanceDataDtos.PreflightResponse(
                1L, key, 1L, action, directive, allowed, 0, List.of(), List.of(),
                "test-classifier", "openai", "gpt-test", GovernanceApprovalStatus.NOT_REQUIRED, null, null, null, null, Instant.now(), "NOT_APPLICABLE"
        );
    }
}
