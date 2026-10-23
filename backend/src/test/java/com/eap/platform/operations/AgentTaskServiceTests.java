package com.eap.platform.operations;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AgentTaskServiceTests {
    private AgentTaskRepository repository;
    private AgentTaskService service;
    private AgentTask task;

    @BeforeEach
    void setUp() {
        repository = mock(AgentTaskRepository.class);
        service = new AgentTaskService(repository, mock(AgentRepository.class));
        task = new AgentTask();
        task.id = 1L;
        task.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
    }

    @ParameterizedTest
    @EnumSource(value = AgentTaskStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void terminalTaskCannotReopenOrChangeTerminalStatus(AgentTaskStatus terminal) {
        task.status = terminal;
        for (AgentTaskStatus next : AgentTaskStatus.values()) {
            if (next == terminal) continue;
            assertThatThrownBy(() -> service.updateStatus(1L, new AgentTaskDtos.StatusRequest(next, "overwrite")))
                    .isInstanceOf(BusinessException.class);
            assertThat(task.status).isEqualTo(terminal);
            assertThat(task.resultSummary).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(value = AgentTaskStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void pendingCanCompleteWithoutStartedReport(AgentTaskStatus terminal) {
        var response = service.updateStatus(1L, new AgentTaskDtos.StatusRequest(terminal, "completed"));
        assertThat(response.status()).isEqualTo(terminal);
        assertThat(response.startedAt()).isEqualTo(task.createdAt);
        assertThat(response.finishedAt()).isNotNull();
        assertThat(response.latencyMs()).isPositive();
    }

    @ParameterizedTest
    @EnumSource(value = AgentTaskStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void runningCanFinishButNotReturnToPending(AgentTaskStatus terminal) {
        service.updateStatus(1L, new AgentTaskDtos.StatusRequest(AgentTaskStatus.RUNNING, null));
        Instant start = task.startedAt;
        assertThatThrownBy(() -> service.updateStatus(1L, new AgentTaskDtos.StatusRequest(AgentTaskStatus.PENDING, null)))
                .isInstanceOf(BusinessException.class);
        var result = service.updateStatus(1L, new AgentTaskDtos.StatusRequest(terminal, "done"));
        assertThat(result.startedAt()).isEqualTo(start);
        assertThat(result.finishedAt()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(AgentTaskStatus.class)
    void repeatedStatusPreservesTimingAndAllowsOutcomeUpdates(AgentTaskStatus status) {
        task.status = status;
        task.startedAt = task.createdAt.plusSeconds(1);
        task.finishedAt = task.createdAt.plusSeconds(3);
        task.latencyMs = 2000L;
        task.resultSummary = "original result";
        var result = service.updateStatus(1L, new AgentTaskDtos.StatusRequest(status, null, true, "  verified  "));
        assertThat(result.startedAt()).isEqualTo(task.createdAt.plusSeconds(1));
        assertThat(result.finishedAt()).isEqualTo(task.createdAt.plusSeconds(3));
        assertThat(result.latencyMs()).isEqualTo(2000L);
        assertThat(result.resultSummary()).isEqualTo("original result");
        assertThat(result.problemResolved()).isTrue();
        assertThat(result.resolutionSummary()).isEqualTo("verified");
    }

    @Test
    void lateTelemetryPreservesTerminalStateAndAccumulatesUnderSameLock() {
        task.status = AgentTaskStatus.SUCCEEDED;
        task.finishedAt = task.createdAt.plusSeconds(10);
        task.latencyMs = 10000L;
        task.tokenCost = 10L;
        task.moneyCost = new BigDecimal("0.1");
        service.recordTelemetryUsage(1L, 20, new BigDecimal("0.2"), 500);
        assertThat(task.status).isEqualTo(AgentTaskStatus.SUCCEEDED);
        assertThat(task.finishedAt).isEqualTo(task.createdAt.plusSeconds(10));
        assertThat(task.tokenCost).isEqualTo(30);
        assertThat(task.moneyCost).isEqualByComparingTo("0.3");
        assertThat(task.latencyMs).isEqualTo(10000);
        verify(repository).findByIdForUpdate(1L);
        verify(repository, never()).findById(anyLong());
    }
}
