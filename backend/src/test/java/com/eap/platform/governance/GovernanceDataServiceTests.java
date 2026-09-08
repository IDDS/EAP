package com.eap.platform.governance;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.discovery.DiscoveryEndpoint;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GovernanceDataServiceTests {
    @Mock
    private GovernanceDataEventRepository eventRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private DiscoveryEndpointRepository endpointRepository;

    private GovernanceDataService service;

    @BeforeEach
    void setUp() {
        service = new GovernanceDataService(eventRepository, agentRepository, endpointRepository, new GovernanceDataProperties());
    }

    @Test
    void filtersAgentInRepositoryBeforeApplyingPage() {
        when(eventRepository.findByAgentIdOrderByObservedAtDescIdDesc(7L, PageRequest.of(2, 20)))
                .thenReturn(List.of());
        assertThat(service.list(20, 7L, 2)).isEmpty();
        verify(eventRepository).findByAgentIdOrderByObservedAtDescIdDesc(7L, PageRequest.of(2, 20));
    }

    @Test
    void keepsLegacyListCallOnFirstGlobalPage() {
        when(eventRepository.findAllByOrderByObservedAtDescIdDesc(PageRequest.of(0, 100)))
                .thenReturn(List.of());
        assertThat(service.list(100)).isEmpty();
        verify(eventRepository).findAllByOrderByObservedAtDescIdDesc(PageRequest.of(0, 100));
    }

    @Test
    void recordsClassifiedMetadataWithoutPayloadContent() {
        when(eventRepository.findByEventKey("gov-1")).thenReturn(Optional.empty());
        when(endpointRepository.findByEndpointKey("endpoint-1")).thenReturn(Optional.of(new DiscoveryEndpoint()));
        when(eventRepository.save(any())).thenAnswer(invocation -> {
            GovernanceDataEvent event = invocation.getArgument(0);
            event.id = 8L;
            event.createdAt = Instant.now();
            return event;
        });

        GovernanceDataDtos.DataEventResponse response = service.record(new GovernanceDataDtos.DataEventRequest(
                "gov-1", null, "endpoint-1", null, "trace-1", GovernanceDataDirection.INPUT_TO_MODEL,
                "SOURCE_CODE", GovernanceDataSensitivity.SECRET, GovernanceDataAction.BLOCK,
                false, false, 420, 4096, null, "COMPANY_SECRET_BLOCK", Instant.now()
        ));

        assertThat(response.id()).isEqualTo(8L);
        assertThat(response.internalData()).isTrue();
        assertThat(response.companySecret()).isTrue();
        assertThat(response.action()).isEqualTo(GovernanceDataAction.BLOCK);
    }

    @Test
    void aggregatesInternalSecretAndEnforcementRates() {
        GovernanceDataEvent allowed = event("gov-1", GovernanceDataSensitivity.INTERNAL, GovernanceDataAction.ALLOW, true, false);
        GovernanceDataEvent blocked = event("gov-2", GovernanceDataSensitivity.SECRET, GovernanceDataAction.BLOCK, true, true);
        GovernanceDataEvent redacted = event("gov-3", GovernanceDataSensitivity.CONFIDENTIAL, GovernanceDataAction.REDACT, true, false);
        redacted.direction = GovernanceDataDirection.TOOL_OUTPUT;
        when(eventRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any())).thenReturn(List.of(allowed, blocked, redacted));
        when(agentRepository.findAll()).thenReturn(List.of());

        GovernanceDataDtos.GovernanceSummary summary = service.summary(24);

        assertThat(summary.totalEvents()).isEqualTo(3);
        assertThat(summary.modelInputEvents()).isEqualTo(2);
        assertThat(summary.internalInfoEvents()).isEqualTo(2);
        assertThat(summary.companySecretEvents()).isEqualTo(1);
        assertThat(summary.blockedEvents()).isEqualTo(1);
        assertThat(summary.redactedEvents()).isEqualTo(1);
        assertThat(summary.blockRate()).isEqualTo(0.5);
        assertThat(summary.subjectUsage()).hasSize(1);
    }

    private GovernanceDataEvent event(
            String key,
            GovernanceDataSensitivity sensitivity,
            GovernanceDataAction action,
            boolean internal,
            boolean companySecret
    ) {
        GovernanceDataEvent event = new GovernanceDataEvent();
        event.eventKey = key;
        event.endpointKey = "endpoint-1";
        event.direction = GovernanceDataDirection.INPUT_TO_MODEL;
        event.dataCategory = "CUSTOMER_DATA";
        event.sensitivity = sensitivity;
        event.action = action;
        event.internalData = internal;
        event.companySecret = companySecret;
        event.tokenCount = 100L;
        event.payloadBytes = 1024L;
        event.observedAt = Instant.now();
        return event;
    }
}
