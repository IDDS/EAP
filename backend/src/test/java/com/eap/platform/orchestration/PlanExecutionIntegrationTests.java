package com.eap.platform.orchestration;

import com.eap.platform.agent.*;
import com.eap.platform.registry.*;
import com.eap.platform.workflow.*;
import com.eap.platform.common.*;
import com.eap.platform.safety.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true", "eap.workflow.execution.enabled=true",
    "eap.workflow.execution.worker-enabled=false", "eap.orchestration.execution.worker-enabled=false",
    "eap.workflow.execution.allowed-hosts=127.0.0.1", "eap.workflow.execution.token=plan-test-token",
    "eap.workflow.execution.allow-insecure-http=true", "eap.workflow.execution.allow-private-networks=true"})
@ActiveProfiles("test")
class PlanExecutionIntegrationTests {
    static HttpServer server;
    static final ObjectMapper json = new ObjectMapper();
    static final List<JsonNode> received = new CopyOnWriteArrayList<>();
    static volatile String reply = "SUCCEEDED";
    @Autowired OrchestrationService orchestration;
    @Autowired PlanExecutionService plans;
    @Autowired AgentPlanRepository repository;
    @Autowired AgentPlanStepRepository stepRepository;
    @Autowired AgentRepository agents;
    @Autowired AgentRegistryService registry;
    @Autowired WorkflowExecutionService executions;
    @Autowired WorkflowDefinitionRepository definitions;
    @Autowired WorkflowExecutionRepository executionRepository;
    @Autowired WorkflowService workflows;
    @MockitoBean GenseeSafetyService safety;
    @Autowired com.eap.platform.ax.AxProperties axProperties;
    Long team, plan, producer, reviewer, first, second;

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry ignored) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/task", exchange -> {
            if (!"Bearer plan-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            var body = json.readTree(exchange.getRequestBody()); received.add(body);
            var out = json.writeValueAsBytes(Map.of("taskKey", body.path("taskKey").asText(), "status", reply,
                    "output", "result-from-" + body.path("nodeKey").asText()));
            exchange.sendResponseHeaders(200, out.length); exchange.getResponseBody().write(out); exchange.close();
        }); server.start();
    }
    @AfterAll static void stop() { server.stop(0); }
    @BeforeEach void setup() {
        received.clear(); reply = "SUCCEEDED"; allow(SafetyDecision.ALLOW);
        team = orchestration.createTeam(new OrchestrationDtos.TeamRequest("PLAN-"+UUID.randomUUID(), "test", "goal", "SEQUENTIAL", "test-policy")).id();
        producer = agent(); reviewer = agent();
        for (Long id : List.of(producer, reviewer)) orchestration.addMember(team, new OrchestrationDtos.MemberRequest(id, "worker", "test responsibility"));
        plan = orchestration.createPlan(new OrchestrationDtos.PlanRequest(team, "overall goal", "frozen context")).id();
        second = orchestration.addStep(plan, new OrchestrationDtos.StepRequest(2, "review result", "review")).id();
        first = orchestration.addStep(plan, new OrchestrationDtos.StepRequest(1, "produce evidence", "draft")).id();
        orchestration.assignStep(first, new OrchestrationDtos.AssignRequest(producer, StepStatus.ASSIGNED));
        orchestration.assignStep(second, new OrchestrationDtos.AssignRequest(reviewer, StepStatus.ASSIGNED));
    }
    Long agent() {
        var a = new Agent(); a.setCode("PLAN-"+UUID.randomUUID()); a.setName("test");
        a.setOwnerDepartment("test"); a.setOwnerUser("test"); a.setCategory("test");
        a.setRiskLevel(RiskLevel.LOW); a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE); a = agents.save(a);
        registry.registerRuntime(a.getId(), new AgentRegistryDtos.RuntimeInstanceRequest("plan-"+UUID.randomUUID(),
            "http://127.0.0.1:"+server.getAddress().getPort()+"/task", "EAP_HTTP_TASK_V1", false));
        return a.getId();
    }
    PlanExecutionService.Request request(String key) { return new PlanExecutionService.Request(key, Map.of("ticket", "synthetic"), false); }

    @Test void planFreezesBackendSelectionAndRollsBackIfAxUnavailable() {
        var step=stepRepository.findById(first).orElseThrow();step.executionBackend="AX";stepRepository.save(step);
        assertThatThrownBy(()->plans.submit(plan,request("ax-plan"),"operator")).isInstanceOf(BusinessException.class);
        assertThat(repository.findById(plan).orElseThrow().executionWorkflowId).isNull();
        String image=axProperties.getRunnerImage();boolean enabled=axProperties.isEnabled();
        try {
            axProperties.setEnabled(true);axProperties.setRunnerImage("localhost:5001/test@sha256:"+"a".repeat(64));
            var submitted=plans.submit(plan,request("ax-plan"),"operator");
            var nodes=workflows.detail(submitted.workflowId()).nodes();
            assertThat(nodes.stream().map(WorkflowDtos.NodeResponse::positionY).distinct().count()).isEqualTo(nodes.size());
            assertThat(nodes.stream().filter(n->n.nodeKey().equals("step-"+first)).findFirst().orElseThrow().configJson()).contains("AX");
            assertThat(nodes.stream().filter(n->n.nodeKey().equals("step-"+second)).findFirst().orElseThrow().configJson()).contains("RUNTIME");
            assertThatThrownBy(()->orchestration.addStep(plan,new OrchestrationDtos.StepRequest(3,"later","review","AX"))).isInstanceOf(ConflictException.class);
            // Do not leave a runnable fixture for other serial execution tests.
            var record=executionRepository.findByWorkflowIdAndRunKey(submitted.workflowId(),submitted.execution().runKey()).orElseThrow();
            executionRepository.delete(record);
        } finally {axProperties.setEnabled(enabled);axProperties.setRunnerImage(image);}
    }

    @Test void freezesOrderedMemberMappingHandsOffResultsAndProjectsCompletion() {
        var queued = plans.submit(plan, request("once"), "operator");
        assertThat(queued.execution().status()).isEqualTo("QUEUED");
        assertThat(repository.findById(plan).orElseThrow().executionWorkflowId).isEqualTo(queued.workflowId());
        executions.processNext();
        new PlanExecutionWorker(repository, plans).synchronize(); // Same path as scheduled projection, no GET required.
        assertThat(repository.findById(plan).orElseThrow().status).isEqualTo(PlanStatus.COMPLETED);
        assertThat(stepRepository.findByPlanIdOrderByStepOrder(plan)).extracting(s -> s.status).containsExactly(StepStatus.DONE, StepStatus.DONE);
        assertThat(received).hasSize(2);
        assertThat(received.getFirst().path("agentId").asLong()).isEqualTo(producer);
        assertThat(received.get(1).path("agentId").asLong()).isEqualTo(reviewer);
        assertThat(received.getFirst().path("objective").asText()).isEqualTo("produce evidence");
        assertThat(received.get(1).path("objective").asText()).isEqualTo("review result");
        assertThat(received.get(1).path("context").path("eapPreviousResults").path("step-"+first).path("output").asText()).isEqualTo("result-from-step-"+first);
        assertThat(received.getFirst().path("context").path("eapPlan").path("contextSummary").asText()).isEqualTo("frozen context");
        assertThat(plans.synchronize(plan).execution().steps()).allMatch(s -> s.output() == null);
        verify(safety).evaluate(argThat(e -> e.agentId() != null && e.agentId().equals(reviewer) && e.payloadJson().contains("review result")));
        assertThat(plans.submit(plan, request("once"), "operator").execution().runKey()).isEqualTo(queued.execution().runKey());
        executions.processNext(); assertThat(received).hasSize(2);
    }

    @Test void concurrentSubmissionsCreateOneWorkflowAndOneRun() throws Exception {
        long count = definitions.count();
        var gate = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i=0; i<4; i++) results.add(pool.submit(() -> { gate.await(); return plans.submit(plan, request("same"), "operator").execution().runKey(); }));
            gate.countDown(); Set<String> keys = new HashSet<>();
            for (var future : results) keys.add(future.get(15, TimeUnit.SECONDS));
            assertThat(keys).hasSize(1);
        }
        assertThat(definitions.count()).isEqualTo(count+1);
        executions.processNext(); assertThat(received).hasSize(2);
    }

    @Test void rejectsChangedRequestsFrozenEditsAndIndependentWorkflowDispatch() {
        var queued = plans.submit(plan, request("same"), "operator");
        assertThatThrownBy(() -> plans.submit(plan, request("another"), "operator")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> plans.submit(plan, new PlanExecutionService.Request("same", Map.of("changed", true), false), "operator")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.addStep(plan, new OrchestrationDtos.StepRequest(3,"new","new"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.assignStep(first, new OrchestrationDtos.AssignRequest(reviewer, StepStatus.ASSIGNED))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> workflows.upsertNode(queued.workflowId(), new WorkflowDtos.NodeRequest("step-"+first,"changed","AGENT",reviewer,null,0,0,"{}"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> executions.submit(queued.workflowId(), new WorkflowDtos.RunRequest("bypass"), "operator")).isInstanceOf(ConflictException.class);
        executions.processNext(); assertThat(received).hasSize(2);
    }

    @Test void rejectsUnassignedOutsiderDuplicateOrderAndFakeRunning() {
        long count = definitions.count();
        assertThatThrownBy(() -> orchestration.assignStep(first, new OrchestrationDtos.AssignRequest(agent(), StepStatus.ASSIGNED))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orchestration.assignStep(first, new OrchestrationDtos.AssignRequest(producer, StepStatus.RUNNING))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orchestration.addStep(plan, new OrchestrationDtos.StepRequest(1,"duplicate","x"))).isInstanceOf(BusinessException.class);
        orchestration.assignStep(first, new OrchestrationDtos.AssignRequest(null, StepStatus.PENDING));
        assertThatThrownBy(() -> plans.submit(plan, request("invalid"), "operator")).isInstanceOf(BusinessException.class);
        assertThat(definitions.count()).isEqualTo(count); assertThat(received).isEmpty();
    }

    @Test void unavailableRuntimeRollsBackWorkflowAndLinkAtomically() {
        var a = agents.findById(reviewer).orElseThrow(); a.setLifecycleStatus(AgentLifecycleStatus.PAUSED); agents.save(a);
        long count = definitions.count();
        assertThatThrownBy(() -> plans.submit(plan, request("offline"), "operator")).isInstanceOf(BusinessException.class);
        assertThat(definitions.count()).isEqualTo(count);
        assertThat(repository.findById(plan).orElseThrow().executionRunKey).isNull();
        assertThat(received).isEmpty();
    }

    @Test void failedStepStopsFollowingStepsAndCannotBeResubmitted() {
        reply = "FAILED"; var submitted = plans.submit(plan, request("failure"), "operator");
        executions.processNext(); plans.synchronize(plan);
        assertThat(repository.findById(plan).orElseThrow().status).isEqualTo(PlanStatus.FAILED);
        assertThat(stepRepository.findByPlanIdOrderByStepOrder(plan)).extracting(s -> s.status).containsExactly(StepStatus.FAILED, StepStatus.SKIPPED);
        assertThat(plans.submit(plan, request("failure"), "operator").execution().runKey()).isEqualTo(submitted.execution().runKey());
        executions.processNext(); assertThat(received).hasSize(1);
    }

    @Test void unknownReceiptRemainsUnknownInsteadOfReportingFailureOrSuccess() {
        reply = "RUNNING"; plans.submit(plan, request("unknown"), "operator"); executions.processNext(); plans.synchronize(plan);
        assertThat(repository.findById(plan).orElseThrow().status).isEqualTo(PlanStatus.EXECUTION_UNKNOWN);
        assertThat(stepRepository.findByPlanIdOrderByStepOrder(plan)).extracting(s -> s.status).containsExactly(StepStatus.EXECUTION_UNKNOWN, StepStatus.SKIPPED);
        assertThat(received).hasSize(1);
    }

    @Test void governanceApprovalIsNotTreatedAsExecutionAndReservedContextIsRejected() {
        assertThatThrownBy(() -> plans.submit(plan, new PlanExecutionService.Request("spoof", Map.of("eapPlan", "spoof"), false), "operator")).isInstanceOf(BusinessException.class);
        allow(SafetyDecision.ASK); plans.submit(plan, request("ask"), "operator"); executions.processNext(); plans.synchronize(plan);
        assertThat(repository.findById(plan).orElseThrow().status).isEqualTo(PlanStatus.WAITING_APPROVAL);
        assertThat(received).isEmpty();
    }

    void allow(SafetyDecision decision) {
        when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,
            new SafetyDtos.DecisionResponse(1L,1L,"test",decision,null,null,"test",null,null,null)));
    }
}
