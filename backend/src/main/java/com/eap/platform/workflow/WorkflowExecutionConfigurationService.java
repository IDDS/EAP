package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.substrate.SubstrateActorRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Original configuration provenance, never reconstructed from today's workflow or runtime registry. */
@Service
public class WorkflowExecutionConfigurationService {
    private final WorkflowExecutionRepository executions;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    public WorkflowExecutionConfigurationService(WorkflowExecutionRepository executions, JdbcTemplate db, ObjectMapper mapper) {
        this.executions = executions; this.db = db; this.mapper = mapper;
    }
    public record RuntimeBinding(String contract, String instanceKey, String protocol, String actorUid,
            String actorTemplate, String deploymentKey, Integer deploymentVersion, String sourceType,
            String imageDigest, Instant capturedAt) {}
    public record Node(int sequence, String nodeKey, String label, String nodeType, Long agentId,
            Long nodeVersion, String executionBackend, String bindingState, RuntimeBinding runtime) {}
    public record Report(String contract, Long workflowId, String runKey, Instant submittedAt, List<Node> nodes) {}

    /** Called once before saving DISPATCHING. The same value survives receipts and reclaim updates. */
    RuntimeBinding capture(AgentRuntimeInstance runtime) {
        String deploymentKey = null, sourceType = null, imageDigest = null;
        Integer version = null;
        if (runtime.getBoundActorUid() != null && runtime.getBoundActorTemplate() != null
                && SubstrateActorRef.isSubstrate(runtime.getProtocol())) {
            var actor = SubstrateActorRef.parse(runtime.getEndpoint());
            // A name, current Agent version or latest deployment is not an original ownership relationship.
            var rows = db.queryForList("""
                    select d.deployment_key,d.version,d.source_type,d.image_ref,d.template_name
                    from platform_runtime_creations p join substrate_agent_deployments d
                      on d.deployment_key=p.deployment_key and d.agent_id=p.agent_id
                    where p.agent_id=? and p.instance_key=? and p.actor_uid=? and d.atespace=?
                    """, runtime.getAgentId(), runtime.getInstanceKey(), runtime.getBoundActorUid(), actor.atespace());
            if (rows.size() == 1 && runtime.getInstanceKey().equals(actor.routingValue())) {
                var row = rows.getFirst();
                if (runtime.getBoundActorTemplate().equals(actor.atespace() + "/" + row.get("template_name"))) {
                    deploymentKey = (String) row.get("deployment_key");
                    version = ((Number) row.get("version")).intValue();
                    sourceType = (String) row.get("source_type");
                    String image = (String) row.get("image_ref");
                    // Return only the immutable digest, never registry credentials or mutable image tags.
                    if (image != null && image.matches(".+@sha256:[a-f0-9]{64}"))
                        imageDigest = image.substring(image.lastIndexOf('@') + 1);
                }
            }
        }
        return new RuntimeBinding("DISPATCH_BINDING_V1", runtime.getInstanceKey(), runtime.getProtocol(),
                runtime.getBoundActorUid(), runtime.getBoundActorTemplate(), deploymentKey, version, sourceType,
                imageDigest, Instant.now());
    }

    @Transactional(readOnly = true)
    public Report get(Long workflowId, String runKey) {
        var run = executions.findByWorkflowIdAndRunKey(workflowId, runKey)
                .orElseThrow(() -> new BusinessException("执行记录不存在"));
        try {
            var snapshot = mapper.readValue(run.snapshotJson, WorkflowExecutionService.Snapshot.class);
            var steps = mapper.readValue(run.stepsJson, new TypeReference<List<WorkflowExecutionService.Step>>() {});
            if (snapshot.nodes() == null || snapshot.nodes().size() > 64 || steps.size() > snapshot.nodes().size())
                throw new IllegalArgumentException();
            List<Node> nodes = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < snapshot.nodes().size(); i++) {
                var node = snapshot.nodes().get(i);
                if (!Objects.equals(node.workflowId(), workflowId) || node.nodeKey() == null || !keys.add(node.nodeKey()))
                    throw new IllegalArgumentException();
                boolean task = Set.of("AGENT", "ARTIFACT").contains(node.nodeType().toUpperCase(Locale.ROOT));
                var config = mapper.readTree(node.configJson() == null ? "{}" : node.configJson());
                String backend = task ? config.path("executionBackend").asText("RUNTIME") : "CONTROL";
                if (!Set.of("RUNTIME", "AX", "CONTROL").contains(backend)) throw new IllegalArgumentException();
                RuntimeBinding binding = null;
                String state = !task ? "NOT_APPLICABLE" : "AX".equals(backend) ? "AX_NOT_CAPTURED" : "NOT_RECORDED";
                if (i < steps.size()) {
                    var step = steps.get(i);
                    if (step.sequence() != i || !node.nodeKey().equals(step.nodeKey()) || !Objects.equals(node.agentId(), step.agentId()))
                        throw new IllegalArgumentException();
                    binding = step.runtimeBinding();
                    if (binding != null) {
                        if (!"RUNTIME".equals(backend) || !"DISPATCH_BINDING_V1".equals(binding.contract())
                                || binding.instanceKey() == null || binding.instanceKey().isBlank()
                                || !Objects.equals(binding.instanceKey(), step.runtimeInstanceKey()) || binding.capturedAt() == null
                                || !(runKey + ":" + i).equals(step.taskKey())
                                || binding.deploymentKey() != null && (binding.deploymentVersion() == null || binding.deploymentVersion() < 1))
                            throw new IllegalArgumentException();
                        state = binding.deploymentKey() == null ? "RUNTIME_RECORDED" : "DEPLOYMENT_RECORDED";
                    }
                }
                nodes.add(new Node(i, node.nodeKey(), node.label(), node.nodeType(), node.agentId(), node.version(), backend, state, binding));
            }
            return new Report("EXECUTION_CONFIGURATION_V1", workflowId, runKey, run.createdAt, nodes);
        } catch (Exception e) { throw new BusinessException("原执行配置无法核对，未使用当前配置替代"); }
    }
}
