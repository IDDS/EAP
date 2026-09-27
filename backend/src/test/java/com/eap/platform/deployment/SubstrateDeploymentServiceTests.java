package com.eap.platform.deployment;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.AgentRegistryService;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.safety.GenseeSafetyService;
import com.eap.platform.safety.SafetyDecision;
import com.eap.platform.safety.SafetyDtos;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.substrate.SubstrateProperties;
import com.eap.platform.workflow.WorkflowExecutionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubstrateDeploymentServiceTests {
    private static final String RUNNER = "localhost:5001/eap-agent-runner@sha256:" + "a".repeat(64);

    private final SubstrateDeploymentRepository repository = mock(SubstrateDeploymentRepository.class);
    private final AgentRuntimeInstanceRepository runtimes = mock(AgentRuntimeInstanceRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentRegistryService registry = mock(AgentRegistryService.class);
    private final SubstrateClient client = mock(SubstrateClient.class);
    private final GenseeSafetyService safety = mock(GenseeSafetyService.class);
    private final SubstrateProperties properties = new SubstrateProperties();
    private final WorkflowExecutionProperties workflow = new WorkflowExecutionProperties();
    private SubstrateDeploymentService service;
    private SubstrateDeployment stored;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setRunnerImage(RUNNER);
        properties.setGatewayUrl("http://backend.eap.svc.cluster.local:8080");
        properties.setGatewayHost("backend.eap.svc.cluster.local");
        properties.setGatewayToken("gateway-token");
        workflow.setEnabled(true);
        workflow.setToken("task-token");
        workflow.setAllowedHosts("atenet-router.ate-system.svc");
        Agent agent = new Agent();
        agent.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);
        when(agents.findById(7L)).thenReturn(Optional.of(agent));
        when(repository.findFirstByAgentIdOrderByVersionDesc(7L)).thenReturn(Optional.empty());
        when(repository.save(any(SubstrateDeployment.class))).thenAnswer(call -> {
            SubstrateDeployment deployment = call.getArgument(0);
            if (deployment.id == null) deployment.id = 1L;
            if (deployment.createdAt == null) deployment.createdAt = java.time.Instant.now();
            stored = deployment;
            return deployment;
        });
        when(repository.findById(1L)).thenAnswer(call -> Optional.ofNullable(stored));
        allow(SafetyDecision.ALLOW);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new SubstrateDeploymentService(repository, agents, runtimes, registry, client,
                properties, workflow, safety, mock(AuditLogRepository.class), new ObjectMapper().registerModule(new JavaTimeModule()), transactions);
    }

    @Test
    void runnerDeploymentAdvancesToReadyWithGatewayOnlyEgressAndNoSecretsInConfig() {
        var response = service.submit(7L, runner(Map.of("TONE", "formal"), List.of()), "operator");
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.templateName()).isEqualTo("eap-a7-v1");
        assertThat(response.imageRef()).isEqualTo(RUNNER);
        assertThat(stored.configJson).doesNotContain("task-token").doesNotContain("gateway-token");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(call -> Optional.of(stored));

        service.processNext();
        ArgumentCaptor<SubstrateClient.TemplateSpec> spec = ArgumentCaptor.forClass(SubstrateClient.TemplateSpec.class);
        verify(client).createTemplate(spec.capture());
        assertThat(spec.getValue().env()).containsEntry("EAP_TASK_TOKEN", "task-token").containsEntry("EAP_GATEWAY_TOKEN", "gateway-token")
                .containsEntry("AGENT_MODEL", "deepseek-flash").containsEntry("EAP_AGENT_ID", "7").containsEntry("TONE", "formal");
        assertThat(stored.status).isEqualTo("BUILDING_GOLDEN");

        when(client.goldenStatus("eap-agents", "eap-a7-v1")).thenReturn(new SubstrateClient.GoldenStatus(true, null));
        service.processNext();
        assertThat(stored.status).isEqualTo("CREATING_INSTANCE");

        service.processNext();
        assertThat(stored.status).isEqualTo("READY");
        assertThat(stored.instanceKey).matches("eap-agents/a7-v1-[a-z0-9]{6}");
        verify(client).setEgressHostnames(any(SubstrateActorRef.class), org.mockito.ArgumentMatchers.eq(List.of("backend.eap.svc.cluster.local")));
        verify(registry).registerRuntime(org.mockito.ArgumentMatchers.eq(7L), any());
    }

    @Test
    void secretLookingOrReservedEnvIsRejected() {
        assertThatThrownBy(() -> service.submit(7L, runner(Map.of("OPENAI_API_KEY", "sk-x"), List.of()), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("密钥");
        assertThatThrownBy(() -> service.submit(7L, runner(Map.of("EAP_TASK_TOKEN", "x"), List.of()), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("保留");
        verifyNoInteractions(client);
    }

    @Test
    void extraEgressNeedsGovernanceAndAskStopsTheDeployment() {
        when(safety.evaluate(any())).thenAnswer(call -> {
            SafetyDtos.SafetyEventRequest event = call.getArgument(0);
            return decision("egress_allow".equals(event.action()) ? SafetyDecision.ASK : SafetyDecision.ALLOW);
        });

        var response = service.submit(7L, runner(Map.of(), List.of("api.example.com")), "operator");

        assertThat(response.status()).isEqualTo("WAITING_APPROVAL");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(Optional.empty());
        service.processNext();
        verifyNoInteractions(client);
    }

    @Test
    void failedGoldenSnapshotFailsTheDeployment() {
        service.submit(7L, runner(Map.of(), List.of()), "operator");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(call -> Optional.of(stored));
        service.processNext();
        when(client.goldenStatus(any(), any())).thenReturn(new SubstrateClient.GoldenStatus(false, "image pull failed"));

        service.processNext();

        assertThat(stored.status).isEqualTo("FAILED");
        assertThat(stored.reason).contains("image pull failed");
        verify(client, never()).createActor(any(), any());
    }

    @Test
    void failedInstanceStepRetriesFromThatStepWithoutRebuildingTheTemplate() {
        service.submit(7L, runner(Map.of(), List.of()), "operator");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(call -> Optional.of(stored));
        when(client.goldenStatus(any(), any())).thenReturn(new SubstrateClient.GoldenStatus(true, null));
        doThrow(new SubstrateClient.SubstrateException("INVALID_ARGUMENT", "bad policy"))
                .doNothing().when(client).setEgressHostnames(any(), any());
        service.processNext();
        service.processNext();
        service.processNext();
        assertThat(stored.status).isEqualTo("FAILED");
        verify(client).deleteActor(any());
        when(repository.findByAgentIdAndVersion(7L, 1)).thenReturn(Optional.of(stored));

        assertThat(service.retry(7L, 1, "operator").status()).isEqualTo("CREATING_INSTANCE");
        service.processNext();

        assertThat(stored.status).isEqualTo("READY");
        assertThat(stored.templateName).isEqualTo("eap-a7-v1");
        verify(client, times(1)).createTemplate(any());
        assertThatThrownBy(() -> service.retry(7L, 1, "operator")).isInstanceOf(BusinessException.class);
    }

    @Test
    void deletingAnInstanceRemovesTheActorAndItsRegistration() {
        com.eap.platform.registry.AgentRuntimeInstance runtime = mock(com.eap.platform.registry.AgentRuntimeInstance.class);
        when(runtime.getAgentId()).thenReturn(7L);
        when(runtime.getProtocol()).thenReturn("SUBSTRATE");
        when(runtime.getStatus()).thenReturn(com.eap.platform.registry.RuntimeStatus.ONLINE);
        when(runtime.getEndpoint()).thenReturn("substrate://eap-agents/a7-v1-abc123");
        when(runtime.getInstanceKey()).thenReturn("eap-agents/a7-v1-abc123");
        when(runtimes.findByInstanceKey("eap-agents/a7-v1-abc123")).thenReturn(Optional.of(runtime));

        service.deleteInstance(7L, "a7-v1-abc123", "operator");

        verify(client).deleteActor(new SubstrateActorRef("eap-agents", "a7-v1-abc123"));
        verify(runtimes).delete(runtime);
        assertThatThrownBy(() -> service.deleteInstance(8L, "a7-v1-abc123", "operator")).isInstanceOf(BusinessException.class);
    }

    @Test
    void foreignImagesMustBePinnedByDigest() {
        assertThatThrownBy(() -> service.resolveImage("docker.io/library/nginx:latest"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sha256");
        assertThat(service.resolveImage("ghcr.io/org/agent@sha256:" + "b".repeat(64))).endsWith("b".repeat(64));
    }

    private static SubstrateDeploymentDtos.DeployRequest runner(Map<String, String> env, List<String> hosts) {
        return new SubstrateDeploymentDtos.DeployRequest("RUNNER", null, null, null, null, env,
                "你是企业助手", "deepseek-flash", "GVISOR", "SMALL", hosts, null);
    }

    private void allow(SafetyDecision value) {
        when(safety.evaluate(any())).thenReturn(decision(value));
    }

    private static SafetyDtos.SafetyEvaluationResponse decision(SafetyDecision value) {
        return new SafetyDtos.SafetyEvaluationResponse(null, new SafetyDtos.DecisionResponse(null, null, null, value,
                null, null, null, null, null, null));
    }
}
