package com.eap.platform.telemetry;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.eap.platform.governance.GovernancePreflightDecisionRepository;
import com.eap.platform.governance.GovernanceDataEventRepository;
import com.eap.platform.governance.GovernancePreflightDecision;
import com.eap.platform.governance.GovernanceGatewayDirective;
import com.eap.platform.governance.GovernanceDataEvent;
import com.eap.platform.operations.AgentTaskService;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ModelTelemetryServiceTests {
    @Mock
    private ModelInvocationEventRepository eventRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private DiscoveryEndpointRepository endpointRepository;
    @Mock
    private AgentRuntimeInstanceRepository runtimeRepository;
    @Mock
    private AgentTaskService taskService;
    @Mock
    private GovernancePreflightDecisionRepository governanceDecisionRepository;
    @Mock
    private GovernanceDataEventRepository governanceDataEventRepository;

    private ModelTelemetryService service;

    @BeforeEach
    void setUp() {
        TelemetryProperties telemetryProperties = new TelemetryProperties();
        telemetryProperties.setRequireGovernancePreflight(false);
        service = new ModelTelemetryService(
                eventRepository, agentRepository, endpointRepository, runtimeRepository,
                taskService, telemetryProperties, governanceDecisionRepository, governanceDataEventRepository
        );
    }

    @Test
    void recordsIdempotentlyAndReturnsTokenBreakdown() {
        when(eventRepository.findByEventKey("evt-1")).thenReturn(Optional.empty());
        when(agentRepository.existsById(7L)).thenReturn(true);
        when(eventRepository.save(any())).thenAnswer(invocation -> {
            ModelInvocationEvent event = invocation.getArgument(0);
            event.id = 11L;
            event.createdAt = Instant.now();
            return event;
        });

        ModelTelemetryDtos.ModelInvocationResponse response = service.record(request("evt-1", 7L, 120, 80));

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.totalTokens()).isEqualTo(200L);
        assertThat(response.moneyCost()).isEqualByComparingTo("0.004200");
        verify(taskService).recordTelemetryUsage(null, 200L, new BigDecimal("0.004200"), 320L);
    }

    @Test
    void persistsSharedCredentialUsagePoolAlias() {
        when(eventRepository.findByEventKey("evt-pool")).thenReturn(Optional.empty());
        when(agentRepository.existsById(7L)).thenReturn(true);
        when(eventRepository.save(any())).thenAnswer(invocation -> {
            ModelInvocationEvent event = invocation.getArgument(0);
            event.id = 13L;
            event.createdAt = Instant.now();
            return event;
        });
        ModelTelemetryDtos.ModelInvocationRequest request = new ModelTelemetryDtos.ModelInvocationRequest(
                "evt-pool", null, null, 7L, null, null, null, null, "OpenAI", "gpt-5",
                ModelInvocationStatus.SUCCEEDED, 20, 10, 0, 0, 100,
                new BigDecimal("0.002000"), "USD", 0, 0, null, " shared-openai-prod ", Instant.now()
        );

        ModelTelemetryDtos.ModelInvocationResponse response = service.record(request);

        assertThat(response.usagePoolKey()).isEqualTo("shared-openai-prod");
    }

    @Test
    void aggregatesCallsTokensCostAndLatency() {
        when(eventRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any())).thenReturn(List.of(
                event("evt-1", ModelInvocationStatus.SUCCEEDED, 120, 80, 320, "gpt-4.1"),
                event("evt-2", ModelInvocationStatus.FAILED, 40, 20, 900, "gpt-4.1")
        ));
        when(agentRepository.findAll()).thenReturn(List.of());
        when(endpointRepository.findAll()).thenReturn(List.of());
        when(runtimeRepository.findAll()).thenReturn(List.of());

        ModelTelemetryDtos.PhysicalTelemetrySummary summary = service.summary(24);

        assertThat(summary.totalCalls()).isEqualTo(2);
        assertThat(summary.totalTokens()).isEqualTo(260);
        assertThat(summary.failedCalls()).isEqualTo(1);
        assertThat(summary.successRate()).isEqualTo(0.5);
        assertThat(summary.averageLatencyMs()).isEqualTo(610);
        assertThat(summary.p95LatencyMs()).isEqualTo(900);
        assertThat(summary.modelUsage()).hasSize(1);
        assertThat(summary.modelUsage().getFirst().modelName()).isEqualTo("gpt-4.1");
    }

    @Test
    void isolatesSelectedCurrencyInTotalsSubjectAndModelUsage() {
        var usd = event("usd", ModelInvocationStatus.SUCCEEDED, 100, 50, 100, "model-a");
        usd.currency = "USD";
        usd.moneyCost = new BigDecimal("2");
        var cny = event("cny", ModelInvocationStatus.SUCCEEDED, 200, 60, 200, "model-a");
        cny.currency = " cny ";
        cny.moneyCost = new BigDecimal("20");
        when(eventRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any())).thenReturn(List.of(usd, cny));

        var summary = service.summary(24, "CNY");

        assertThat(summary.totalCalls()).isEqualTo(1);
        assertThat(summary.moneyCost()).isEqualByComparingTo("20");
        assertThat(summary.totalTokens()).isEqualTo(260);
        assertThat(summary.subjectUsage()).singleElement().satisfies(item ->
                assertThat(item.moneyCost()).isEqualByComparingTo("20"));
        assertThat(summary.modelUsage()).singleElement().satisfies(item ->
                assertThat(item.moneyCost()).isEqualByComparingTo("20"));
    }

    @Test
    void normalizesAndDeduplicatesAvailableCurrencies() {
        when(eventRepository.findCurrenciesSince(any())).thenReturn(java.util.Arrays.asList(" usd ", "USD", "cny", null));

        assertThat(service.currencies(24)).containsExactly("CNY", "USD");
    }

    @Test
    void rejectsUnattributedEvents() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.record(request("evt-3", null, 1, 2)))
                .hasMessageContaining("必须关联 Agent 或发现端点");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void requiresAllowedGovernanceDecisionWhenEnforcementIsEnabled() {
        TelemetryProperties enforcedProperties = new TelemetryProperties();
        enforcedProperties.setRequireGovernancePreflight(true);
        ModelTelemetryService enforcedService = new ModelTelemetryService(
                eventRepository, agentRepository, endpointRepository, runtimeRepository,
                taskService, enforcedProperties, governanceDecisionRepository, governanceDataEventRepository
        );
        GovernancePreflightDecision decision = new GovernancePreflightDecision();
        ReflectionTestUtils.setField(decision, "requestAllowed", false);
        ReflectionTestUtils.setField(decision, "gatewayDirective", GovernanceGatewayDirective.BLOCK);
        ReflectionTestUtils.setField(decision, "dataEventId", 5L);
        ReflectionTestUtils.setField(decision, "decidedAt", Instant.now());
        when(governanceDecisionRepository.findByDecisionKey("decision-1")).thenReturn(Optional.of(decision));
        when(agentRepository.existsById(7L)).thenReturn(true);
        GovernanceDataEvent dataEvent = new GovernanceDataEvent();
        ReflectionTestUtils.setField(dataEvent, "agentId", 7L);
        when(governanceDataEventRepository.findById(5L)).thenReturn(Optional.of(dataEvent));

        assertThatThrownBy(() -> enforcedService.record(requestWithDecision("evt-blocked", 7L, "decision-1")))
                .hasMessageContaining("未允许");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void acceptsTelemetryOnlyForMatchingAllowedDecision() {
        TelemetryProperties enforcedProperties = new TelemetryProperties();
        enforcedProperties.setRequireGovernancePreflight(true);
        ModelTelemetryService enforcedService = new ModelTelemetryService(
                eventRepository, agentRepository, endpointRepository, runtimeRepository,
                taskService, enforcedProperties, governanceDecisionRepository, governanceDataEventRepository
        );
        GovernancePreflightDecision decision = new GovernancePreflightDecision();
        ReflectionTestUtils.setField(decision, "requestAllowed", true);
        ReflectionTestUtils.setField(decision, "gatewayDirective", GovernanceGatewayDirective.PROCEED);
        ReflectionTestUtils.setField(decision, "dataEventId", 6L);
        ReflectionTestUtils.setField(decision, "decidedAt", Instant.now());
        ReflectionTestUtils.setField(decision, "provider", "OpenAI");
        ReflectionTestUtils.setField(decision, "modelName", "gpt-4.1");
        when(governanceDecisionRepository.findByDecisionKey("decision-2")).thenReturn(Optional.of(decision));
        when(agentRepository.existsById(7L)).thenReturn(true);
        GovernanceDataEvent dataEvent = new GovernanceDataEvent();
        ReflectionTestUtils.setField(dataEvent, "agentId", 7L);
        when(governanceDataEventRepository.findById(6L)).thenReturn(Optional.of(dataEvent));
        when(eventRepository.findByEventKey("evt-allowed")).thenReturn(Optional.empty());
        when(eventRepository.save(any())).thenAnswer(invocation -> {
            ModelInvocationEvent event = invocation.getArgument(0);
            event.id = 12L;
            event.createdAt = Instant.now();
            return event;
        });

        assertThat(enforcedService.record(requestWithDecision("evt-allowed", 7L, "decision-2")).id()).isEqualTo(12L);
    }

    private ModelTelemetryDtos.ModelInvocationRequest request(String eventKey, Long agentId, long input, long output) {
        return new ModelTelemetryDtos.ModelInvocationRequest(
                eventKey, null, null, agentId, null, null, null, null, "OpenAI", "gpt-4.1",
                ModelInvocationStatus.SUCCEEDED, input, output, 0, 0, 320,
                new BigDecimal("0.004200"), "USD", 1, 0, null, null, Instant.now()
        );
    }

    private ModelTelemetryDtos.ModelInvocationRequest requestWithDecision(String eventKey, Long agentId, String decisionKey) {
        return new ModelTelemetryDtos.ModelInvocationRequest(
                eventKey, null, null, agentId, null, null, null, decisionKey, "OpenAI", "gpt-4.1",
                ModelInvocationStatus.SUCCEEDED, 10, 8, 0, 0, 120,
                new BigDecimal("0.001000"), "USD", 0, 0, null, null, Instant.now()
        );
    }

    private ModelInvocationEvent event(String key, ModelInvocationStatus status, long input, long output, long latency, String model) {
        ModelInvocationEvent event = new ModelInvocationEvent();
        event.eventKey = key;
        event.agentId = 7L;
        event.provider = "OpenAI";
        event.modelName = model;
        event.status = status;
        event.inputTokens = input;
        event.outputTokens = output;
        event.cachedInputTokens = 0L;
        event.reasoningTokens = 0L;
        event.latencyMs = latency;
        event.moneyCost = new BigDecimal("0.004200");
        event.observedAt = Instant.now();
        return event;
    }
}
