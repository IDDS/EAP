package com.eap.platform.management;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.eap.platform.knowledge.KnowledgeDtos;
import com.eap.platform.knowledge.KnowledgeService;
import com.eap.platform.operations.AgentTaskDtos;
import com.eap.platform.operations.AgentTaskService;
import com.eap.platform.operations.AgentTaskStatus;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementServiceTests {
    @Mock
    private ManagementValueEventRepository valueRepository;
    @Mock
    private ManagementValueAttributionRepository attributionRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private DiscoveryEndpointRepository endpointRepository;
    @Mock
    private AgentTaskService taskService;
    @Mock
    private ModelTelemetryService telemetryService;
    @Mock
    private KnowledgeService knowledgeService;

    private ManagementService service;

    @BeforeEach
    void setUp() {
        service = new ManagementService(
                valueRepository, attributionRepository, agentRepository, endpointRepository, taskService,
                telemetryService, knowledgeService, new ManagementProperties()
        );
    }

    @Test
    void countsBusinessOutcomesSeparatelyFromExecutionSuccess() {
        stubFinancialContext("USD", "0");
        when(taskService.list()).thenReturn(List.of(
            outcomeTask(1L, true), outcomeTask(2L, false), outcomeTask(3L, null)));
        var summary = service.summary(24);
        assertThat(summary.succeededTasks()).isEqualTo(3);
        assertThat(summary.resolvedTasks()).isEqualTo(1);
        assertThat(summary.unresolvedTasks()).isEqualTo(1);
        assertThat(summary.unconfirmedTasks()).isEqualTo(1);
    }

    private AgentTaskDtos.TaskResponse outcomeTask(Long id, Boolean resolved) {
        return new AgentTaskDtos.TaskResponse(id, 1L, null, "TEST", null, null, AgentTaskStatus.SUCCEEDED,
            Instant.now(), Instant.now(), 10L, 0L, BigDecimal.ZERO, null, resolved, null, Instant.now(), Instant.now());
    }

    @Test
    void calculatesValidatedValueAgainstModelCost() {
        when(telemetryService.summary(24, "USD")).thenReturn(new ModelTelemetryDtos.PhysicalTelemetrySummary(
                24, 4, 3, 1, 0.75, 1000, 500, 100, 0, 1500,
                new BigDecimal("2.000000"), 300, 600, 1, 1, 2, 0, 0, Instant.now(), List.of(), List.of()
        ));
        when(knowledgeService.summary(24)).thenReturn(new KnowledgeDtos.KnowledgeSummary(
                24, 2, 2, 0, 1.0, 100, 80, 3, 2, 1, 1, 0.5, 0.5, 90, 120, Instant.now(), List.of()
        ));
        when(taskService.list()).thenReturn(List.of(new AgentTaskDtos.TaskResponse(
                1L, 7L, null, "ANALYSIS", "input", AgentTaskStatus.SUCCEEDED,
                Instant.now(), Instant.now(), 300L, 1500L, new BigDecimal("2.000000"), "ok", Instant.now(), Instant.now()
        )));
        ManagementValueEvent value = new ManagementValueEvent();
        value.eventKey = "value-1";
        value.endpointKey = "endpoint-1";
        value.benefitMoney = new BigDecimal("10.000000");
        value.observedAt = Instant.now();
        when(valueRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any())).thenReturn(List.of(value));
        when(agentRepository.findAll()).thenReturn(List.of());

        ManagementDtos.ManagementSummary summary = service.summary(24);

        assertThat(summary.totalCost()).isEqualByComparingTo("2.000000");
        assertThat(summary.validatedBusinessValue()).isEqualByComparingTo("10.000000");
        assertThat(summary.netBenefit()).isEqualByComparingTo("8.000000");
        assertThat(summary.roi()).isEqualByComparingTo("4.0000");
        assertThat(summary.knowledgeAdoptionRate()).isEqualTo(0.5);
        assertThat(summary.knowledgeReuseRate()).isEqualTo(0.5);
        assertThat(summary.hasValidatedValue()).isTrue();
    }

    @Test
    void recordsValueEventIdempotently() {
        when(valueRepository.findByEventKey("value-1")).thenReturn(Optional.empty());
        when(endpointRepository.findByEndpointKey("endpoint-1")).thenReturn(Optional.of(new com.eap.platform.discovery.DiscoveryEndpoint()));
        when(valueRepository.save(any())).thenAnswer(invocation -> {
            ManagementValueEvent event = invocation.getArgument(0);
            event.id = 9L;
            event.createdAt = Instant.now();
            return event;
        });

        ManagementDtos.ValueEventResponse response = service.recordValue(new ManagementDtos.ValueEventRequest(
                "value-1", null, "endpoint-1", null, null, "TIME_SAVED", "人工确认节省工时",
                "owner@example.com", new BigDecimal("5.500000"), "USD", Instant.now()
        ));

        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.benefitMoney()).isEqualByComparingTo("5.500000");
    }

    @Test
    void calculatesConfirmedImprovementFromBaselineAndCurrentMetric() {
        when(valueRepository.findByEventKey("impact-1")).thenReturn(Optional.empty());
        when(endpointRepository.findByEndpointKey("endpoint-1")).thenReturn(Optional.of(new com.eap.platform.discovery.DiscoveryEndpoint()));
        when(valueRepository.save(any())).thenAnswer(invocation -> {
            ManagementValueEvent event = invocation.getArgument(0);
            event.id = 10L;
            event.createdAt = Instant.now();
            return event;
        });

        ManagementDtos.ValueEventResponse response = service.recordValue(new ManagementDtos.ValueEventRequest(
                "impact-1", null, "endpoint-1", null, null, "EFFICIENCY_GAIN", "首响时长下降",
                "业务负责人", new BigDecimal("100.000000"), "USD", Instant.now(),
                "首响时长", "分钟", new BigDecimal("8.000000"), new BigDecimal("2.000000"), "LOWER_IS_BETTER"
        ));

        assertThat(response.metricName()).isEqualTo("首响时长");
        assertThat(response.improvementRate()).isEqualByComparingTo("0.7500");
    }

    @Test
    void recordsBusinessSystemEvidenceAndMultiAgentContributions() {
        when(valueRepository.findByEventKey("crm-impact-1")).thenReturn(Optional.empty());
        when(valueRepository.findBySourceSystemAndSourceRecordKey("CRM", "deal-1001")).thenReturn(Optional.empty());
        when(agentRepository.existsById(1L)).thenReturn(true);
        when(agentRepository.existsById(2L)).thenReturn(true);
        when(valueRepository.save(any())).thenAnswer(invocation -> {
            ManagementValueEvent event = invocation.getArgument(0);
            event.id = 11L;
            event.createdAt = Instant.now();
            return event;
        });
        when(attributionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManagementDtos.ValueEventResponse response = service.recordValue(new ManagementDtos.ValueEventRequest(
                "crm-impact-1", null, null, null, null, "REVENUE", "多智能体协作推动成交",
                "销售运营", new BigDecimal("1000.000000"), "USD", Instant.now(),
                "成交金额", "USD", new BigDecimal("5000.000000"), new BigDecimal("6000.000000"), "HIGHER_IS_BETTER",
                "BUSINESS_SYSTEM", "CRM", "deal-1001", new BigDecimal("0.9500"),
                Instant.now().minusSeconds(3600), Instant.now(), List.of(
                new ManagementDtos.AgentContributionRequest(1L, new BigDecimal("0.700000"), "BUSINESS_CONFIRMED", "crm:deal-1001"),
                new ManagementDtos.AgentContributionRequest(2L, new BigDecimal("0.300000"), "BUSINESS_CONFIRMED", "crm:deal-1001")
        )));

        assertThat(response.sourceType()).isEqualTo("BUSINESS_SYSTEM");
        assertThat(response.sourceSystem()).isEqualTo("CRM");
        assertThat(response.agentContributions()).hasSize(2);
        assertThat(response.agentContributions().get(0).contributionWeight()).isEqualByComparingTo("0.700000");
    }

    @Test
    void allocatesValidatedValueAcrossContributingAgents() {
        when(telemetryService.summary(24, "USD")).thenReturn(new ModelTelemetryDtos.PhysicalTelemetrySummary(
                24, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0, null, List.of(), List.of()
        ));
        when(knowledgeService.summary(24)).thenReturn(new KnowledgeDtos.KnowledgeSummary(
                24, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of()
        ));
        when(taskService.list()).thenReturn(List.of());
        when(agentRepository.findAll()).thenReturn(List.of());
        ManagementValueEvent value = new ManagementValueEvent();
        value.id = 21L;
        value.eventKey = "shared-value";
        value.valueType = "REVENUE";
        value.benefitMoney = new BigDecimal("100.000000");
        value.observedAt = Instant.now();
        when(valueRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any())).thenReturn(List.of(value));
        ManagementValueAttribution first = new ManagementValueAttribution();
        first.valueEventId = 21L;
        first.agentId = 1L;
        first.contributionWeight = new BigDecimal("0.700000");
        ManagementValueAttribution second = new ManagementValueAttribution();
        second.valueEventId = 21L;
        second.agentId = 2L;
        second.contributionWeight = new BigDecimal("0.300000");
        when(attributionRepository.findByValueEventIdIn(any())).thenReturn(List.of(first, second));

        ManagementDtos.ManagementSummary summary = service.summary(24);

        assertThat(summary.agentUsage()).extracting(ManagementDtos.AgentManagementUsage::validatedValue)
                .containsExactlyInAnyOrder(new BigDecimal("70.000000000000"), new BigDecimal("30.000000000000"));
    }
    @Test
    void suppressesMixedCurrencyTotalsAndEveryAgentFinancialMetric() {
        var usd = financialValue(31L, 1L, "USD", "10");
        var cny = financialValue(32L, 2L, "CNY", "100");
        when(valueRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any())).thenReturn(List.of(usd, cny));
        stubFinancialContext(null, "9");
        when(telemetryService.currencies(24)).thenReturn(List.of("USD", "CNY"));

        var summary = service.summary(24);

        assertThat(summary.availableCurrencies()).containsExactly("CNY", "USD");
        assertThat(summary.currency()).isNull();
        assertThat(summary.mixedCurrencies()).isTrue();
        assertThat(summary.totalCost()).isNull();
        assertThat(summary.validatedBusinessValue()).isNull();
        assertThat(summary.netBenefit()).isNull();
        assertThat(summary.roi()).isNull();
        assertThat(summary.agentUsage()).hasSize(2).allSatisfy(agent -> {
            assertThat(agent.totalCost()).isNull();
            assertThat(agent.validatedValue()).isNull();
            assertThat(agent.netBenefit()).isNull();
            assertThat(agent.roi()).isNull();
        });
    }

    @Test
    void detectsMixedCurrencyWhenCostAndBenefitUseDifferentCurrencies() {
        when(valueRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any()))
                .thenReturn(List.of(financialValue(31L, 1L, "CNY", "100")));
        when(telemetryService.currencies(24)).thenReturn(List.of("USD"));
        stubFinancialContext(null, "2");

        var summary = service.summary(24);

        assertThat(summary.mixedCurrencies()).isTrue();
        assertThat(summary.roi()).isNull();
    }

    @Test
    void filtersBenefitAndAgentAttributionToTheSelectedCostCurrency() {
        var usd = financialValue(31L, 1L, "USD", "10");
        var cny = financialValue(32L, 2L, " cny ", "100");
        when(valueRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any())).thenReturn(List.of(usd, cny));
        when(telemetryService.currencies(24)).thenReturn(List.of("USD", "CNY"));
        stubFinancialContext("CNY", "20");
        var contribution = new ManagementValueAttribution();
        contribution.valueEventId = 32L;
        contribution.agentId = 3L;
        contribution.contributionWeight = BigDecimal.ONE;
        when(attributionRepository.findByValueEventIdIn(List.of(32L))).thenReturn(List.of(contribution));

        var summary = service.summary(24, " cny ");

        assertThat(summary.currency()).isEqualTo("CNY");
        assertThat(summary.mixedCurrencies()).isFalse();
        assertThat(summary.totalCost()).isEqualByComparingTo("20");
        assertThat(summary.validatedBusinessValue()).isEqualByComparingTo("100");
        assertThat(summary.netBenefit()).isEqualByComparingTo("80");
        assertThat(summary.roi()).isEqualByComparingTo("4");
        assertThat(summary.agentUsage()).singleElement().satisfies(agent -> {
            assertThat(agent.agentId()).isEqualTo(3L);
            assertThat(agent.validatedValue()).isEqualByComparingTo("100");
        });
    }

    @Test
    void automaticallyUsesTheOnlyCurrencyWithoutAssumingUsd() {
        when(valueRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any()))
                .thenReturn(List.of(financialValue(31L, 1L, "EUR", "15")));
        when(telemetryService.currencies(24)).thenReturn(List.of("EUR"));
        stubFinancialContext("EUR", "5");

        var summary = service.summary(24);

        assertThat(summary.currency()).isEqualTo("EUR");
        assertThat(summary.mixedCurrencies()).isFalse();
        assertThat(summary.roi()).isEqualByComparingTo("2");
    }

    private ManagementValueEvent financialValue(Long id, Long agentId, String currency, String amount) {
        var event = new ManagementValueEvent();
        event.id = id;
        event.agentId = agentId;
        event.currency = currency;
        event.benefitMoney = new BigDecimal(amount);
        event.observedAt = Instant.now();
        return event;
    }

    private void stubFinancialContext(String currency, String cost) {
        when(telemetryService.summary(24, currency)).thenReturn(new ModelTelemetryDtos.PhysicalTelemetrySummary(
                24, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new BigDecimal(cost), 0, 0, 0, 0, 0, 0, 0, null, List.of(), List.of()));
        when(knowledgeService.summary(24)).thenReturn(new KnowledgeDtos.KnowledgeSummary(
                24, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of()));
    }

}
