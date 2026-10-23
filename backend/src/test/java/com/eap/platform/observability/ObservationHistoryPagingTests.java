package com.eap.platform.observability;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.discovery.AgentDiscoveryService;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.eap.platform.governance.GovernanceDataEventRepository;
import com.eap.platform.governance.GovernancePreflightDecisionRepository;
import com.eap.platform.knowledge.KnowledgeService;
import com.eap.platform.operations.AgentTaskService;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.telemetry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ObservationHistoryPagingTests {
    enum Kind { MODEL, TRACE, TOOL, CONTENT }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void filtersBeforePagingAndUsesIdToBreakTimestampTies(Kind kind) {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.eap.platform.observability", "com.eap.platform.telemetry");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        EntityManager em = factory.getObject().createEntityManager();
        try {
            em.getTransaction().begin();
            Object target1 = row(kind, "target-1", 7L);
            Object target2 = row(kind, "target-2", 7L);
            em.persist(target1);
            em.persist(target2);
            em.persist(row(kind, "other-1", 8L));
            em.persist(row(kind, "other-2", 8L));
            em.flush();
            em.clear();
            var repositories = new JpaRepositoryFactory(em);
            assertThat(list(kind, repositories, 7L, 0)).extracting(item -> ReflectionTestUtils.getField(item, "id"))
                    .containsExactly(ReflectionTestUtils.getField(target2, "id"));
            assertThat(list(kind, repositories, 7L, 1)).extracting(item -> ReflectionTestUtils.getField(item, "id"))
                    .containsExactly(ReflectionTestUtils.getField(target1, "id"));
            assertThat(list(kind, repositories, 7L, 2)).isEmpty();
            // Global lists also honor page; the former unfiltered top-500 shortcut ignored it.
            assertThat(list(kind, repositories, null, 3)).extracting(item -> ReflectionTestUtils.getField(item, "id"))
                    .containsExactly(ReflectionTestUtils.getField(target1, "id"));
            em.getTransaction().rollback();
        } finally {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
            factory.destroy();
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    private List<?> list(Kind kind, JpaRepositoryFactory repositories, Long agentId, int page) {
        return switch (kind) {
            case MODEL -> new ModelTelemetryService(repositories.getRepository(ModelInvocationEventRepository.class),
                    mock(AgentRepository.class), mock(DiscoveryEndpointRepository.class), mock(AgentRuntimeInstanceRepository.class),
                    mock(AgentTaskService.class), new TelemetryProperties(), mock(GovernancePreflightDecisionRepository.class),
                    mock(GovernanceDataEventRepository.class)).list(1, agentId, null, null, null, null, null, null, page);
            case TRACE -> new OtlpTraceIngestionService(new ObjectMapper(), mock(AgentDiscoveryService.class),
                    mock(ModelTelemetryService.class), mock(ContentObservationService.class), new SensitiveContentSanitizer(),
                    repositories.getRepository(TraceSpanObservationRepository.class), mock(KnowledgeService.class),
                    mock(ToolInvocationService.class)).listTraceSpans(1, agentId, null, null, null, null, null, page);
            case TOOL -> new ToolInvocationService(repositories.getRepository(ToolInvocationEventRepository.class))
                    .list(1, agentId, null, null, null, null, null, null, null, null, page);
            case CONTENT -> new ContentObservationService(repositories.getRepository(ContentObservationRepository.class), new ObservationProperties())
                    .list(1, agentId, null, null, null, null, null, page);
        };
    }

    private Object row(Kind kind, String key, Long agentId) {
        Object result;
        switch (kind) {
            case MODEL -> {
                var row = new ModelInvocationEvent();
                ReflectionTestUtils.setField(row, "eventKey", key);
                ReflectionTestUtils.setField(row, "provider", "test");
                ReflectionTestUtils.setField(row, "modelName", "test");
                ReflectionTestUtils.setField(row, "status", ModelInvocationStatus.SUCCEEDED);
                result = row;
            }
            case TRACE -> {
                var row = new TraceSpanObservation();
                row.traceId = key;
                row.spanId = key;
                row.endpointKey = "endpoint";
                row.spanName = key;
                row.spanKind = TraceSpanKind.AGENT;
                result = row;
            }
            case TOOL -> {
                var row = new ToolInvocationEvent();
                row.invocationKey = key;
                row.observationSource = ToolInvocationSource.OTLP;
                row.toolKind = ToolInvocationKind.TOOL;
                row.toolName = key;
                row.status = ToolInvocationStatus.SUCCEEDED;
                result = row;
            }
            case CONTENT -> {
                var row = new ContentObservation();
                row.observationKey = key;
                row.direction = ContentDirection.INPUT;
                row.captureMode = ContentCaptureMode.HASH_ONLY;
                row.contentHash = key;
                result = row;
            }
            default -> throw new IllegalArgumentException();
        }
        ReflectionTestUtils.setField(result, "agentId", agentId);
        ReflectionTestUtils.setField(result, "observedAt", Instant.parse("2026-01-01T00:00:00Z"));
        return result;
    }
}
