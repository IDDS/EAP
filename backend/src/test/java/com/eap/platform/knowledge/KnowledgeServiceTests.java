package com.eap.platform.knowledge;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTests {
    @Mock
    private KnowledgeInteractionRepository interactionRepository;
    @Mock
    private KnowledgeTraceStepRepository stepRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private DiscoveryEndpointRepository endpointRepository;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(
                interactionRepository, stepRepository, agentRepository, endpointRepository,
                new KnowledgeProperties(), new ObjectMapper()
        );
    }

    @Test
    void filtersAgentInRepositoryBeforeApplyingPage() {
        when(interactionRepository.findByAgentIdOrderByCreatedAtDescIdDesc(7L, PageRequest.of(2, 20)))
                .thenReturn(List.of());
        assertThat(service.list(20, 7L, 2)).isEmpty();
        verify(interactionRepository).findByAgentIdOrderByCreatedAtDescIdDesc(7L, PageRequest.of(2, 20));
    }

    @Test
    void keepsLegacyListCallOnFirstGlobalPage() {
        when(interactionRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 100)))
                .thenReturn(List.of());
        assertThat(service.list(100)).isEmpty();
        verify(interactionRepository).findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 100));
    }

    @Test
    void recordsInputProcessOutputAndRedactsSummary() {
        when(stepRepository.findByInteractionIdOrderByStepOrderAsc(any())).thenReturn(List.of());
        when(interactionRepository.findByInteractionKey("knowledge-1")).thenReturn(Optional.empty());
        when(endpointRepository.findByEndpointKey("endpoint-1")).thenReturn(Optional.of(new com.eap.platform.discovery.DiscoveryEndpoint()));
        when(interactionRepository.save(any())).thenAnswer(invocation -> {
            KnowledgeInteraction interaction = invocation.getArgument(0);
            interaction.id = 12L;
            interaction.createdAt = Instant.now();
            return interaction;
        });
        KnowledgeDtos.KnowledgeInteractionResponse response = service.record(new KnowledgeDtos.KnowledgeInteractionRequest(
                "knowledge-1", "trace-1", null, "endpoint-1", null,
                "RAG_QUERY", 3, 120,
                "查询 api_key=do-not-store 的制度摘要", List.of("policy:leave-v3"),
                "POLICY_ANSWER", 2, 80, "已生成脱敏制度答案", List.of("policy:leave-v3"),
                List.of(new KnowledgeDtos.KnowledgeStepRequest(
                        1, KnowledgeStepType.RETRIEVAL, "检索制度库", KnowledgeStepStatus.SUCCEEDED,
                        3, 40, 20, 120, List.of("policy:leave-v3"), Map.of("token", "secret-value")
                )),
                KnowledgeInteractionStatus.SUCCEEDED, true, true, 88, Instant.now(), Instant.now()
        ));

        assertThat(response.inputSummary()).contains("api_key=[REDACTED]");
        assertThat(response.processStepCount()).isEqualTo(1);
        assertThat(response.processTokenCount()).isEqualTo(60);
        assertThat(response.adopted()).isTrue();
        assertThat(response.reused()).isTrue();
    }

    @Test
    void aggregatesAdoptionAndReuseRates() {
        KnowledgeInteraction first = interaction("k-1", true, true, 80);
        KnowledgeInteraction second = interaction("k-2", false, true, 60);
        when(interactionRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(any())).thenReturn(List.of(first, second));
        when(agentRepository.findAll()).thenReturn(List.of());

        KnowledgeDtos.KnowledgeSummary summary = service.summary(24);

        assertThat(summary.totalInteractions()).isEqualTo(2);
        assertThat(summary.adoptionRate()).isEqualTo(0.5);
        assertThat(summary.reuseRate()).isEqualTo(1.0);
        assertThat(summary.averageQualityScore()).isEqualTo(70.0);
    }

    private KnowledgeInteraction interaction(String key, boolean adopted, boolean reused, int quality) {
        KnowledgeInteraction interaction = new KnowledgeInteraction();
        interaction.interactionKey = key;
        interaction.endpointKey = "endpoint-1";
        interaction.inputType = "RAG_QUERY";
        interaction.inputSourceCount = 2;
        interaction.inputTokenCount = 10L;
        interaction.outputSourceCount = 1;
        interaction.outputTokenCount = 20L;
        interaction.outputSummary = "answer";
        interaction.status = KnowledgeInteractionStatus.SUCCEEDED;
        interaction.adopted = adopted;
        interaction.reused = reused;
        interaction.qualityScore = quality;
        interaction.processLatencyMs = 100L;
        interaction.createdAt = Instant.now();
        return interaction;
    }
    @Test
    void legacyOtlpKnowledgeDoesNotExposeBodyThroughSummariesOrReferences() throws Exception {
        var item = new KnowledgeInteraction(); item.id = 987L; item.interactionKey = "otlp-knowledge-legacy";
        item.inputSummary = "PRIVATE_QUERY"; item.outputSummary = "PRIVATE_DOCUMENT";
        item.inputRefsJson = "[\"PRIVATE_REFERENCE\"]"; item.outputRefsJson = item.inputRefsJson;
        var step = new KnowledgeTraceStep(); step.stepOrder = 0; step.refsJson = item.inputRefsJson;
        when(interactionRepository.findById(987L)).thenReturn(Optional.of(item));
        when(stepRepository.findByInteractionIdOrderByStepOrderAsc(987L)).thenReturn(List.of(step));
        var response = service.get(987L);
        assertThat(new ObjectMapper().writeValueAsString(response)).doesNotContain("PRIVATE");
        assertThat(item.inputSummary).isEqualTo("PRIVATE_QUERY");
    }

}
