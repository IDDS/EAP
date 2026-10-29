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
    static volatile JsonNode responseOutput;
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
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
                    "output", responseOutput == null ? json.getNodeFactory().textNode("result-from-" + body.path("nodeKey").asText()) : responseOutput));
            exchange.sendResponseHeaders(200, out.length); exchange.getResponseBody().write(out); exchange.close();
        }); server.start();
    }
    @AfterAll static void stop() { server.stop(0); }
    @BeforeEach void setup() {
        received.clear(); responseOutput = null; reply = "SUCCEEDED"; allow(SafetyDecision.ALLOW);
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

    @Autowired WorkflowStopService stops;
    @Test void stoppedPlanRemainsFrozenAndUnstartedStepsAreSkipped() {
        var submitted = plans.submit(plan, request("stop-plan"), "operator");
        stops.request(submitted.workflowId(), submitted.execution().runKey(), new WorkflowStopService.Request("stop"), "operator");
        assertThat(plans.synchronize(plan).execution().status()).isEqualTo("CANCELLED");
        assertThat(repository.findById(plan).orElseThrow().status).isEqualTo(PlanStatus.CANCELLED);
        assertThat(stepRepository.findByPlanIdOrderByStepOrder(plan)).allMatch(s -> s.status == StepStatus.SKIPPED);
        assertThat(plans.submit(plan, request("stop-plan"), "operator").execution().runKey()).isEqualTo(submitted.execution().runKey());
        assertThatThrownBy(() -> plans.submit(plan, request("new-key"), "operator")).isInstanceOf(ConflictException.class);
        executions.processNext(); assertThat(received).isEmpty();
    }

    @Test void globalReclaimBlockerIsReportedReadOnlyAndRejectsEntirePlanBeforeCreatingExecution() {
        for (Long agent : List.of(producer, reviewer)) {
            db.update("update agent_runtime_instances set protocol='SUBSTRATE',endpoint=? where agent_id=?",
                    "substrate://plan/" + UUID.randomUUID(), agent);
        }
        String legacy = UUID.randomUUID().toString();
        db.update("insert into runtime_work_claims values (?,'*','LEGACY','UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", legacy);
        long workflowsBefore = definitions.count(), executionsBefore = executionRepository.count();
        long claimsBefore = db.queryForObject("select count(*) from runtime_work_claims", Long.class);
        try {
            var check = plans.reclaimReadiness(plan);
            assertThat(check.allowed()).isFalse();
            assertThat(check.steps()).extracting(PlanExecutionService.ReclaimStep::stepOrder).containsExactly(1, 2);
            assertThat(check.steps()).extracting(PlanExecutionService.ReclaimStep::code).containsOnly("GLOBAL_UNRESOLVED_WORK");
            assertThatThrownBy(() -> plans.submit(plan, new PlanExecutionService.Request("blocked", Map.of(), true), "operator"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("历史占用");
            assertThat(definitions.count()).isEqualTo(workflowsBefore);
            assertThat(executionRepository.count()).isEqualTo(executionsBefore);
            assertThat(repository.findById(plan).orElseThrow().executionRunKey).isNull();
            assertThat(repository.findById(plan).orElseThrow().status).isEqualTo(PlanStatus.READY);
            assertThat(db.queryForObject("select count(*) from runtime_work_claims", Long.class)).isEqualTo(claimsBefore);
            assertThat(db.queryForObject("select state from runtime_work_claims where work_id=?", String.class, legacy)).isEqualTo("UNKNOWN");
            assertThat(received).isEmpty();
        } finally { db.update("delete from runtime_work_claims where work_id=?", legacy); }
    }

    @Test void reclaimPreviewDistinguishesHttpAxAndUnassignedWithoutBlockingNormalExecution() {
        String legacy = UUID.randomUUID().toString();
        db.update("insert into runtime_work_claims values (?,'*','LEGACY','UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", legacy);
        try {
            assertThat(plans.reclaimReadiness(plan).steps()).extracting(PlanExecutionService.ReclaimStep::code).containsOnly("UNSUPPORTED_PROTOCOL");
            var request = request("ordinary-http");
            var submitted = plans.submit(plan, request, "operator");
            executions.processNext();
            assertThat(plans.synchronize(plan).execution().status()).isEqualTo("SUCCEEDED");
            assertThat(plans.submit(plan, request, "operator").execution().runKey()).isEqualTo(submitted.execution().runKey());
            assertThat(received).hasSize(2);
            assertThat(executions.reclaimReadiness(producer, "AX").code()).isEqualTo("AX_MANAGED");
            assertThat(executions.reclaimReadiness(null, "AX").allowed()).isFalse();
        } finally { db.update("delete from runtime_work_claims where work_id=?", legacy); }
    }

    @Test void reclaimPreviewMatchesSelectedRuntimeAndDetectsAxOwnedAlias() {
        String endpoint = "substrate://plan/" + UUID.randomUUID();
        db.update("update agent_runtime_instances set protocol='SUBSTRATE',endpoint=? where agent_id=?", endpoint, producer);
        assertThat(plans.reclaimReadiness(plan).steps().getFirst().code()).isEqualTo("READY");
        db.update("update agent_runtime_instances set protocol='SUBSTRATE_TASK',endpoint=? where agent_id=?", endpoint, reviewer);
        assertThat(plans.reclaimReadiness(plan).steps().getFirst().code()).isEqualTo("AX_LIFECYCLE_OWNER");
    }

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
            // Only this never-dispatched H2 fixture is discarded; preserve its scheduling audit row.
            // This is test cleanup, not a production cancellation API.
            var record=executionRepository.findByWorkflowIdAndRunKey(submitted.workflowId(),submitted.execution().runKey()).orElseThrow();
            assertThat(db.update("update workflow_executions set status='FAILED',reason='Synthetic test fixture not dispatched',completed_at=CURRENT_TIMESTAMP where run_key=? and status='QUEUED'",submitted.execution().runKey())).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from workflow_schedule_entries s join workflow_executions e on e.id=s.execution_id where e.run_key=?",Long.class,submitted.execution().runKey())).isEqualTo(1);
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

    @RepeatedTest(3) void concurrentSubmissionsCreateOneWorkflowAndOneRun() throws Exception {
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

    @Test void concurrentMemberAssignmentAndContractEditPreserveBothChanges() throws Exception {
        for (int round = 0; round < 5; round++) {
            orchestration.updateOutputContract(first, new OrchestrationDtos.OutputContractRequest(null));
            orchestration.assignStep(first, new OrchestrationDtos.AssignRequest(producer, StepStatus.ASSIGNED));
            var gate = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var assignment = pool.submit(() -> { gate.await(); return orchestration.assignStep(first,
                        new OrchestrationDtos.AssignRequest(reviewer, StepStatus.ASSIGNED)); });
                var contract = pool.submit(() -> { gate.await(); return orchestration.updateOutputContract(first,
                        new OrchestrationDtos.OutputContractRequest(RESULT_DEFINITION)); });
                gate.countDown(); assignment.get(15, TimeUnit.SECONDS); contract.get(15, TimeUnit.SECONDS);
            }
            var saved = stepRepository.findById(first).orElseThrow();
            assertThat(saved.assignedAgentId).isEqualTo(reviewer);
            assertThat(json.readTree(saved.outputContractJson)).isEqualTo(json.readTree(RESULT_DEFINITION));
        }
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

    static final String RESULT_DEFINITION = """
        {"version":"EAP_JSON_V1","source":"JSON_TEXT","schema":{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"],"additionalProperties":false}}
        """;

    @Test void planContractCanBeSavedBeforeAssignmentUpdatedAndExplicitlyCleared() {
        var unassigned = orchestration.addStep(plan, new OrchestrationDtos.StepRequest(3,"later","review","RUNTIME",RESULT_DEFINITION));
        assertThat(unassigned.assignedAgentId()).isNull(); assertThat(unassigned.outputContractJson()).contains("JSON_TEXT");
        var changed = orchestration.updateOutputContract(unassigned.id(),new OrchestrationDtos.OutputContractRequest(RESULT_DEFINITION.replace("JSON_TEXT","OUTPUT")));
        assertThat(changed.outputContractJson()).contains("OUTPUT");
        assertThat(orchestration.getPlan(plan).steps().get(2).outputContractJson()).isEqualTo(changed.outputContractJson());
        assertThat(orchestration.updateOutputContract(unassigned.id(),new OrchestrationDtos.OutputContractRequest(null)).outputContractJson()).isNull();
    }

    @Test void planContractRejectsAxMalformedDuplicateOrUnsupportedDefinitionsWithoutPersisting() {
        long count = stepRepository.count();
        assertThatThrownBy(()->orchestration.addStep(plan,new OrchestrationDtos.StepRequest(3,"ax","review","AX",RESULT_DEFINITION))).isInstanceOf(BusinessException.class);
        assertThat(stepRepository.count()).isEqualTo(count);
        for (String bad : List.of("", "null", "{}", RESULT_DEFINITION+"{}", RESULT_DEFINITION.replace("\"version\":", "\"version\":\"duplicate\",\"version\":"), RESULT_DEFINITION.replace("EAP_JSON_V1","unsupported"), RESULT_DEFINITION.replace("\"type\":\"string\"","\"type\":\"string\",\"pattern\":\".*\""), " ".repeat(8193))) {
            assertThatThrownBy(()->orchestration.updateOutputContract(first,new OrchestrationDtos.OutputContractRequest(bad))).isInstanceOf(BusinessException.class);
            assertThat(stepRepository.findById(first).orElseThrow().outputContractJson).isNull();
        }
        var ax = orchestration.addStep(plan,new OrchestrationDtos.StepRequest(3,"ax","review","AX"));
        assertThatThrownBy(()->orchestration.updateOutputContract(ax.id(),new OrchestrationDtos.OutputContractRequest(RESULT_DEFINITION))).isInstanceOf(BusinessException.class);
    }

    @Test void submittedPlanFreezesContractAndHandsOffOnlyValidatedJson() throws Exception {
        orchestration.updateOutputContract(first,new OrchestrationDtos.OutputContractRequest(RESULT_DEFINITION));
        responseOutput = json.getNodeFactory().textNode("{\"summary\":\"private-result\"}");
        var queued = plans.submit(plan,request("contract-success"),"operator");
        var config = workflows.detail(queued.workflowId()).nodes().stream().filter(n->n.nodeKey().equals("step-"+first)).findFirst().orElseThrow().configJson();
        assertThat(json.readTree(config).get("eapOutput")).isEqualTo(json.readTree(RESULT_DEFINITION));
        // MySQL normalizes workflow_nodes JSON key order after this transaction. The digest
        // binds the persisted execution snapshot, which was frozen inside the submission.
        var snapshot = json.readTree(db.queryForObject("select snapshot_json from workflow_executions where run_key=?",
                String.class, queued.execution().runKey())).path("nodes");
        var frozenConfig = java.util.stream.StreamSupport.stream(snapshot.spliterator(), false)
                .filter(n -> n.path("nodeKey").asText().equals("step-" + first))
                .map(n -> n.path("configJson").asText()).findFirst().orElseThrow();
        assertThat(json.readTree(frozenConfig).get("eapOutput")).isEqualTo(json.readTree(RESULT_DEFINITION));
        assertThatThrownBy(()->orchestration.updateOutputContract(first,new OrchestrationDtos.OutputContractRequest(null))).isInstanceOf(ConflictException.class);
        executions.processNext(); var result = plans.synchronize(plan);
        assertThat(result.execution().status()).isEqualTo("SUCCEEDED"); assertThat(received).hasSize(2);
        var checked = result.execution().steps().stream().filter(v->v.nodeKey().equals("step-"+first)).findFirst().orElseThrow();
        assertThat(checked.resultValidation().status()).isEqualTo("PASSED");
        assertThat(checked.resultValidation().contractSha256()).isEqualTo(WorkflowOutputContract.read(frozenConfig,"AGENT",producer).sha256());
        assertThat(received.get(1).path("context").path("eapPreviousResults").path("step-"+first).path("output").path("summary").asText()).isEqualTo("private-result");
        assertThat(checked.output()).isNull();
        assertThat(executions.outputs(queued.workflowId(),queued.execution().runKey()).get("step-"+first)).isEqualTo(responseOutput);
        assertThat(repository.findById(plan).orElseThrow().status).isEqualTo(PlanStatus.COMPLETED);
    }

    @Test void rejectedPlanResultPreservesReceiptButFailsStepSkipsHandoffAndNeverReplays() throws Exception {
        orchestration.updateOutputContract(first,new OrchestrationDtos.OutputContractRequest(RESULT_DEFINITION.replace("JSON_TEXT","OUTPUT")));
        responseOutput = json.readTree("{\"summary\":42}");
        var queued = plans.submit(plan,request("contract-rejected"),"operator");
        executions.processNext(); var result = plans.synchronize(plan);
        assertThat(result.execution().status()).isEqualTo("FAILED"); assertThat(received).hasSize(1);
        var checked = result.execution().steps().stream().filter(v->v.nodeKey().equals("step-"+first)).findFirst().orElseThrow();
        assertThat(checked.status()).isEqualTo("SUCCEEDED"); assertThat(checked.resultValidation().status()).isEqualTo("REJECTED");
        assertThat(checked.resultValidation().code()).isEqualTo("TYPE_MISMATCH");
        assertThat(stepRepository.findByPlanIdOrderByStepOrder(plan)).extracting(v->v.status).containsExactly(StepStatus.FAILED,StepStatus.SKIPPED);
        assertThat(executions.outputs(queued.workflowId(),queued.execution().runKey()).get("step-"+first)).isEqualTo(responseOutput);
        assertThat(plans.submit(plan,request("contract-rejected"),"operator").execution().runKey()).isEqualTo(queued.execution().runKey());
        executions.processNext();assertThat(received).hasSize(1);
    }

    @Test void planContractNeverReclassifiesUnknownAsValidationFailure() throws Exception {
        orchestration.updateOutputContract(first,new OrchestrationDtos.OutputContractRequest(RESULT_DEFINITION));
        reply="RUNNING";responseOutput=json.getNodeFactory().textNode("{\"summary\":\"unconfirmed\"}");
        plans.submit(plan,request("contract-unknown"),"operator");executions.processNext();var result=plans.synchronize(plan);
        assertThat(result.execution().status()).isEqualTo("EXECUTION_UNKNOWN");assertThat(received).hasSize(1);
        var checked=result.execution().steps().stream().filter(v->v.nodeKey().equals("step-"+first)).findFirst().orElseThrow();
        assertThat(checked.resultValidation().status()).isEqualTo("NOT_EVALUATED");
        assertThat(stepRepository.findByPlanIdOrderByStepOrder(plan)).extracting(v->v.status).containsExactly(StepStatus.EXECUTION_UNKNOWN,StepStatus.SKIPPED);
    }

    @Test void corruptedSavedPlanContractCannotCreateWorkflowOrExecution() {
        db.update("update agent_plan_steps set output_contract_json=? where id=?","{}",first);
        long count=definitions.count();
        assertThatThrownBy(()->plans.submit(plan,request("corrupt-contract"),"operator")).isInstanceOf(BusinessException.class);
        assertThat(definitions.count()).isEqualTo(count);assertThat(repository.findById(plan).orElseThrow().executionRunKey).isNull();assertThat(received).isEmpty();
    }

    long revision() { return orchestration.getPlan(plan).plan().revision(); }
    OrchestrationDtos.TeamResponse currentTeam() { return orchestration.listTeams().stream().filter(t -> t.id().equals(team)).findFirst().orElseThrow(); }

    @Test void teamEditsRejectStaleVersionsAndDuplicateMembers() {
        var before = currentTeam();
        var changed = orchestration.updateTeam(team, new OrchestrationDtos.TeamUpdateRequest(before.revision(), "new name", "new purpose", "SEQUENTIAL", "context only"));
        assertThat(changed.revision()).isGreaterThan(before.revision());
        assertThat(changed.code()).isEqualTo(before.code());
        assertThatThrownBy(() -> orchestration.updateTeam(team, new OrchestrationDtos.TeamUpdateRequest(before.revision(), "stale", "", "SEQUENTIAL", "x")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.addMember(team, new OrchestrationDtos.MemberRequest(producer, "duplicate", "x")))
                .isInstanceOf(ConflictException.class);
        assertThat(orchestration.listMembers(team)).hasSize(2);
    }

    @Test void memberRemovalRequiresUnassignedDraftsAndPreservesOtherTeam() {
        var member = orchestration.listMembers(team).stream().filter(m -> m.agentId().equals(producer)).findFirst().orElseThrow();
        long rev = currentTeam().revision();
        assertThatThrownBy(() -> orchestration.removeMember(team, member.id(), new OrchestrationDtos.RevisionRequest(rev))).isInstanceOf(ConflictException.class);
        orchestration.assignStep(first, new OrchestrationDtos.AssignRequest(null, StepStatus.PENDING));
        var updated = orchestration.updateMember(team, member.id(), new OrchestrationDtos.MemberUpdateRequest(rev, "analyst", "check facts"));
        assertThat(updated.role()).isEqualTo("analyst");
        assertThatThrownBy(() -> orchestration.removeMember(team, member.id(), new OrchestrationDtos.RevisionRequest(rev))).isInstanceOf(ConflictException.class);
        orchestration.removeMember(team, member.id(), new OrchestrationDtos.RevisionRequest(currentTeam().revision()));
        assertThat(orchestration.listMembers(team)).hasSize(1);
        assertThatThrownBy(() -> orchestration.assignStep(first, new OrchestrationDtos.AssignRequest(producer, StepStatus.ASSIGNED))).isInstanceOf(BusinessException.class);
    }

    @Test void onlyEmptyTeamsCanBeDeleted() {
        assertThatThrownBy(() -> orchestration.deleteTeam(team, new OrchestrationDtos.RevisionRequest(currentTeam().revision()))).isInstanceOf(ConflictException.class);
        var empty = orchestration.createTeam(new OrchestrationDtos.TeamRequest("EMPTY-"+UUID.randomUUID(), "empty", "", "SEQUENTIAL", "none"));
        orchestration.deleteTeam(empty.id(), new OrchestrationDtos.RevisionRequest(empty.revision()));
        assertThat(orchestration.listTeams()).noneMatch(t -> t.id().equals(empty.id()));
    }

    @Test void editsAndReordersAreVersionedAndRejectPartialOrForeignLists() {
        long initial = revision();
        var edited = orchestration.updatePlan(plan, new OrchestrationDtos.PlanUpdateRequest(initial, "changed goal", "new context"));
        assertThat(edited.revision()).isGreaterThan(initial);
        assertThatThrownBy(() -> orchestration.updatePlan(plan, new OrchestrationDtos.PlanUpdateRequest(initial, "stale", ""))).isInstanceOf(ConflictException.class);
        long rev = revision();
        assertThatThrownBy(() -> orchestration.reorderSteps(plan, new OrchestrationDtos.ReorderRequest(rev, List.of(first)))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.reorderSteps(plan, new OrchestrationDtos.ReorderRequest(rev, List.of(first, first)))).isInstanceOf(ConflictException.class);
        var reordered = orchestration.reorderSteps(plan, new OrchestrationDtos.ReorderRequest(rev, List.of(second, first)));
        assertThat(reordered.steps()).extracting(OrchestrationDtos.StepResponse::id).containsExactly(second, first);
        assertThat(reordered.steps()).extracting(OrchestrationDtos.StepResponse::stepOrder).containsExactly(1, 2);
        assertThatThrownBy(() -> orchestration.deleteStep(first, new OrchestrationDtos.RevisionRequest(rev))).isInstanceOf(ConflictException.class);
    }

    @Test void stepEditingPreservesAssignmentAndRejectsInvalidBackendContract() {
        long rev = revision();
        assertThatThrownBy(() -> orchestration.updateStep(first, new OrchestrationDtos.StepUpdateRequest(rev, 2, "changed", "review", "RUNTIME", null))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.updateStep(first, new OrchestrationDtos.StepUpdateRequest(rev, 1, "changed", "review", "AX", RESULT_DEFINITION))).isInstanceOf(BusinessException.class);
        var changed = orchestration.updateStep(first, new OrchestrationDtos.StepUpdateRequest(rev, 1, "changed", "review", "RUNTIME", RESULT_DEFINITION));
        assertThat(changed.title()).isEqualTo("changed");
        assertThat(changed.assignedAgentId()).isEqualTo(producer);
        assertThat(changed.outputContractJson()).isNotNull();
        orchestration.deleteStep(second, new OrchestrationDtos.RevisionRequest(revision()));
        assertThat(orchestration.getPlan(plan).steps()).hasSize(1);
    }

    @Test void deletingDraftRemovesStepsWithoutCreatingExecution() {
        long count = executionRepository.count();
        orchestration.deletePlan(plan, new OrchestrationDtos.RevisionRequest(revision()));
        assertThat(repository.existsById(plan)).isFalse();
        assertThat(stepRepository.findByPlanIdOrderByStepOrder(plan)).isEmpty();
        assertThat(executionRepository.count()).isEqualTo(count);
    }

    @Test void copyOfSubmittedPlanHasFreshIdentityNoAssignmentsOrExecutionAndKeepsContract() {
        orchestration.updateOutputContract(first, new OrchestrationDtos.OutputContractRequest(RESULT_DEFINITION));
        var original = plans.submit(plan, request("copy-origin"), "operator");
        stops.request(original.workflowId(), original.execution().runKey(), new WorkflowStopService.Request("copy-origin-stop"), "operator");
        long before = executionRepository.count();
        var copy = orchestration.copyPlan(plan, new OrchestrationDtos.PlanUpdateRequest(revision(), "next independent plan", "fresh context"));
        assertThat(copy.id()).isNotEqualTo(plan);
        assertThat(copy.executionRunKey()).isNull();
        assertThat(copy.executionWorkflowId()).isNull();
        assertThat(copy.status()).isEqualTo(PlanStatus.READY);
        var detail = orchestration.getPlan(copy.id());
        assertThat(detail.steps()).hasSize(2).allMatch(s -> s.assignedAgentId() == null && s.status() == StepStatus.PENDING);
        assertThat(detail.steps()).extracting(OrchestrationDtos.StepResponse::id).doesNotContain(first, second);
        assertThat(detail.steps().getFirst().outputContractJson()).isNotNull();
        assertThat(executionRepository.count()).isEqualTo(before);
        assertThat(plans.submit(plan, request("copy-origin"), "operator").execution().runKey()).isEqualTo(original.execution().runKey());
        assertThatThrownBy(() -> plans.submit(copy.id(), request("copy-unassigned"), "operator")).isInstanceOf(BusinessException.class);
        assertThat(received).isEmpty();
    }

    @Test void submittedPlanRejectsEveryDefinitionMutationAndMemberEditDoesNotRewriteSnapshot() throws Exception {
        var submitted = plans.submit(plan, request("edit-frozen"), "operator");
        var config = workflows.detail(submitted.workflowId());
        long rev = revision();
        assertThatThrownBy(() -> orchestration.updatePlan(plan, new OrchestrationDtos.PlanUpdateRequest(rev, "changed", ""))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.deletePlan(plan, new OrchestrationDtos.RevisionRequest(rev))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.updateStep(first, new OrchestrationDtos.StepUpdateRequest(rev, 1, "changed", "x", "RUNTIME", null))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.deleteStep(first, new OrchestrationDtos.RevisionRequest(rev))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orchestration.reorderSteps(plan, new OrchestrationDtos.ReorderRequest(rev, List.of(second, first)))).isInstanceOf(ConflictException.class);
        var member = orchestration.listMembers(team).getFirst();
        orchestration.removeMember(team, member.id(), new OrchestrationDtos.RevisionRequest(currentTeam().revision()));
        assertThat(workflows.detail(submitted.workflowId()).nodes()).isEqualTo(config.nodes());
        stops.request(submitted.workflowId(), submitted.execution().runKey(), new WorkflowStopService.Request("frozen-stop"), "operator");
        assertThat(received).isEmpty();
    }

    @Test void concurrentDraftEditsAllowOnlyOneRevisionWinner() throws Exception {
        long rev = revision();
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = java.util.stream.IntStream.range(0,2).mapToObj(i -> pool.submit(() -> {
                gate.await();
                try { orchestration.updatePlan(plan, new OrchestrationDtos.PlanUpdateRequest(rev, "goal-"+i, "")); return true; }
                catch (ConflictException expected) { return false; }
            })).toList();
            gate.countDown();
            int successes = 0;
            for (var task : tasks) if (task.get(20, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(revision()).isEqualTo(rev+1);
        } finally { pool.shutdownNow(); }
    }

    void allow(SafetyDecision decision) {
        when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,
            new SafetyDtos.DecisionResponse(1L,1L,"test",decision,null,null,"test",null,null,null)));
    }
}
