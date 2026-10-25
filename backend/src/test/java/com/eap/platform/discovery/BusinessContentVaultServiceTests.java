package com.eap.platform.discovery;

import com.eap.platform.observability.ContentObservationService;
import com.eap.platform.observability.SensitiveContentSanitizer;
import com.eap.platform.telemetry.ModelObservationSource;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessContentVaultServiceTests {
    @Mock private BusinessContentRecordRepository repository;
    @Mock private DiscoveryAgentV1Service agentService;
    @Mock private SensitiveContentSanitizer sanitizer;
    @Mock private ContentObservationService observationService;
    @Mock private ModelTelemetryService telemetryService;

    private BusinessContentVaultService service;

    @BeforeEach
    void setUp() {
        service = new BusinessContentVaultService(
                repository, agentService, new DiscoveryAgentV1Properties(), sanitizer,
                observationService, telemetryService, new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void recordsOpenAiCompatibleUsageAsPassiveModelTelemetry() {
        DiscoveryEndpoint endpoint = enabledEndpoint();
        when(agentService.authenticateContent("content-key")).thenReturn(endpoint);
        when(agentService.isContentCaptureEnabled(endpoint)).thenReturn(true);
        when(repository.findByObservationKey("proxy-trace-1-output")).thenReturn(Optional.empty());
        when(sanitizer.sanitize(any())).thenReturn(new SensitiveContentSanitizer.SanitizedContent(
                "safe", "PUBLIC", "GENERAL_TEXT", "PASSIVE_OBSERVED", false
        ));
        when(repository.save(any())).thenAnswer(invocation -> {
            BusinessContentRecord record = invocation.getArgument(0);
            record.id = 77L;
            return record;
        });

        Map<String, Object> usage = Map.of(
                "prompt_tokens", 140,
                "completion_tokens", 60,
                "prompt_tokens_details", Map.of("cached_tokens", 40),
                "completion_tokens_details", Map.of("reasoning_tokens", 12),
                "total_cost", "0.0042"
        );
        DiscoveryAgentV1Dtos.ContentCaptureRequest request = new DiscoveryAgentV1Dtos.ContentCaptureRequest(
                "proxy-trace-1-output", null, 12L, 33L, "trace-1", "span-1", "OUTPUT",
                "openai", "gpt-5", "{\"usage\":{}}", null, null,
                Map.of("usage", usage, "httpStatus", 200, "latencyMs", 845, "toolCallCount", 2),
                false, false, Instant.parse("2026-08-14T12:00:00Z")
        );

        service.capture("content-key", request);

        ArgumentCaptor<ModelTelemetryDtos.ModelInvocationRequest> telemetry =
                ArgumentCaptor.forClass(ModelTelemetryDtos.ModelInvocationRequest.class);
        verify(telemetryService).recordPassive(telemetry.capture(), eq(ModelObservationSource.CONTENT_PROXY));
        assertThat(telemetry.getValue().eventKey()).startsWith("content-proxy-").hasSize(78);
        assertThat(telemetry.getValue().agentId()).isEqualTo(12L);
        assertThat(telemetry.getValue().endpointKey()).isEqualTo("endpoint-1");
        assertThat(telemetry.getValue().inputTokens()).isEqualTo(140);
        assertThat(telemetry.getValue().outputTokens()).isEqualTo(60);
        assertThat(telemetry.getValue().cachedInputTokens()).isEqualTo(40);
        assertThat(telemetry.getValue().reasoningTokens()).isEqualTo(12);
        assertThat(telemetry.getValue().latencyMs()).isEqualTo(845);
        assertThat(telemetry.getValue().moneyCost()).isEqualByComparingTo(new BigDecimal("0.0042"));
        assertThat(telemetry.getValue().toolCallCount()).isEqualTo(2);
    }

    @Test
    void doesNotCreateModelEventForInputContent() {
        DiscoveryEndpoint endpoint = enabledEndpoint();
        when(agentService.authenticateContent("content-key")).thenReturn(endpoint);
        when(agentService.isContentCaptureEnabled(endpoint)).thenReturn(true);
        when(repository.findByObservationKey("proxy-trace-2-input")).thenReturn(Optional.empty());
        when(sanitizer.sanitize(any())).thenReturn(new SensitiveContentSanitizer.SanitizedContent(
                "safe", "PUBLIC", "GENERAL_TEXT", "PASSIVE_OBSERVED", false
        ));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.capture("content-key", new DiscoveryAgentV1Dtos.ContentCaptureRequest(
                "proxy-trace-2-input", null, 12L, null, "trace-2", null, "INPUT",
                "anthropic", "claude-sonnet-4", "{}", null, null,
                Map.of("usage", Map.of("input_tokens", 10)), false, false, Instant.now()
        ));

        verify(telemetryService, never()).recordPassive(any(), any());
    }

    private DiscoveryEndpoint enabledEndpoint() {
        DiscoveryEndpoint endpoint = new DiscoveryEndpoint();
        endpoint.id = 9L;
        endpoint.endpointKey = "endpoint-1";
        endpoint.tenantId = "default";
        endpoint.contentCaptureStatus = "ENABLED";
        endpoint.fingerprintReviewRequired = false;
        return endpoint;
    }

    @Test
    void rawRetentionUsesServerReceiptRatherThanProbeClock() {
        var properties = new DiscoveryAgentV1Properties();
        // Generated in memory, never a deployment credential.
        properties.setContentVaultMasterKey(java.util.Base64.getEncoder().encodeToString(
                java.util.UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service = new BusinessContentVaultService(repository, agentService, properties, sanitizer,
                observationService, telemetryService, new ObjectMapper().findAndRegisterModules());
        var endpoint = enabledEndpoint();
        when(agentService.authenticateContent("content-key")).thenReturn(endpoint);
        when(agentService.isContentCaptureEnabled(endpoint)).thenReturn(true);
        when(sanitizer.sanitize(any())).thenReturn(new SensitiveContentSanitizer.SanitizedContent(
                "safe", "PUBLIC", "GENERAL_TEXT", "PASSIVE_OBSERVED", false));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Instant before = Instant.now();
        service.capture("content-key", new DiscoveryAgentV1Dtos.ContentCaptureRequest(
                "future-clock-fixture", null, null, null, null, null, "INPUT", null, null,
                "synthetic sample", null, null, Map.of(), true, true, before.plusSeconds(86400L * 365)));
        var captured = ArgumentCaptor.forClass(BusinessContentRecord.class);
        verify(repository).save(captured.capture());
        assertThat(captured.getValue().expiresAt).isBetween(before.plusSeconds(86400L * 30),
                Instant.now().plusSeconds(86400L * 30));
    }
}
