package com.eap.platform.experiment;

import com.eap.platform.agent.AgentService;
import com.eap.platform.common.BusinessException;
import com.eap.platform.observability.TraceSpanKind;
import com.eap.platform.observability.TraceSpanObservationRepository;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.telemetry.ModelInvocationEventRepository;
import com.eap.platform.telemetry.ModelInvocationStatus;
import com.eap.platform.telemetry.ModelObservationSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Evidence coverage, separate from business outcomes and content authorization. */
@Service
public class AgentExperimentTelemetryService {
    public record TrialEvidence(Long trialId, String traceId, String executionStatus,
                                Long agentSpans, Long modelInvocations, Long successfulModelInvocations, String coverage) {}
    public record Evidence(Long experimentId, String identityEvidence, int registeredTrials,
                           long completeTrials, List<TrialEvidence> trials) {}

    private final AgentExperimentRepository experiments;
    private final ExperimentTrialRepository trials;
    private final AgentRuntimeInstanceRepository runtimes;
    private final TraceSpanObservationRepository spans;
    private final ModelInvocationEventRepository models;
    private final AgentService agents;

    public AgentExperimentTelemetryService(AgentExperimentRepository experiments,
            ExperimentTrialRepository trials, AgentRuntimeInstanceRepository runtimes,
            TraceSpanObservationRepository spans, ModelInvocationEventRepository models, AgentService agents) {
        this.experiments = experiments;
        this.trials = trials;
        this.runtimes = runtimes;
        this.spans = spans;
        this.models = models;
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public Evidence get(Long id) {
        var experiment = experiments.findById(id).orElseThrow(() -> new BusinessException("实验不存在"));
        agents.get(experiment.agentId); // Includes the production visibility boundary.
        var runtime = runtimes.findByInstanceKey(experiment.instanceKey)
                .filter(r -> matches(r, experiment)).orElse(null);
        var evidence = trials.findByExperimentIdOrderByIdAsc(id).stream().map(t -> {
            if (runtime == null) {
                return new TrialEvidence(t.id, t.traceId, t.status, null, null, null, "IDENTITY_UNCONFIRMED");
            }
            String endpoint = "runtime-" + runtime.getId();
            long agent = count(t.traceId, experiment.agentId, endpoint, TraceSpanKind.AGENT, "agent.experiment");
            long model = countModels(t.traceId, experiment.agentId, endpoint, false);
            long successfulModel = countModels(t.traceId, experiment.agentId, endpoint, true);
            return new TrialEvidence(t.id, t.traceId, t.status, agent, model, successfulModel, coverage(t.status, agent, model, successfulModel));
        }).toList();
        return new Evidence(id, runtime == null ? "UNCONFIRMED" : "FROZEN_REGISTRATION_MATCH",
                evidence.size(), evidence.stream().filter(t -> "COMPLETE".equals(t.coverage())).count(), evidence);
    }

    private boolean matches(AgentRuntimeInstance r, AgentExperiment e) {
        return r.supportsMachineIdentity() && Objects.equals(r.getAgentId(), e.agentId)
                && Objects.equals(r.getEndpoint(), e.endpoint)
                && Objects.equals(r.getBoundActorUid(), e.actorUid)
                && Objects.equals(r.getBoundActorTemplate(), e.template);
    }

    private long count(String trace, Long agent, String endpoint, TraceSpanKind kind, String name) {
        return spans.count((root, query, cb) -> {
            var scope = cb.and(cb.equal(root.get("traceId"), trace), cb.equal(root.get("agentId"), agent),
                    cb.equal(root.get("endpointKey"), endpoint), cb.equal(root.get("spanKind"), kind));
            return name == null ? scope : cb.and(scope, cb.equal(root.get("spanName"), name));
        });
    }

    private long countModels(String trace, Long agent, String endpoint, boolean successful) {
        return models.count((root, query, cb) -> {
            var scope = cb.and(cb.equal(root.get("traceId"), trace), cb.equal(root.get("agentId"), agent),
                    cb.equal(root.get("endpointKey"), endpoint), cb.equal(root.get("observationSource"), ModelObservationSource.GATEWAY));
            return successful ? cb.and(scope, cb.equal(root.get("status"), ModelInvocationStatus.SUCCEEDED)) : scope;
        });
    }

    private String coverage(String status, long agent, long model, long successfulModel) {
        if (agent > 1 || model > 1) return "DUPLICATE_EVIDENCE";
        if ("UNKNOWN".equals(status) || "FAILED".equals(status)) return "EXECUTION_UNCONFIRMED";
        if (!"SUCCEEDED".equals(status)) {
            if (agent != 0 || model != 0) return "UNEXPECTED_EVIDENCE";
            return "CANCELLED".equals(status) ? "NOT_EXPECTED" : "PENDING";
        }
        if (model > 0 && successfulModel != model) return "MODEL_STATUS_UNCONFIRMED";
        if (agent == 1 && model == 1) return "COMPLETE";
        if (agent == 0 && model == 0) return "MISSING_BOTH";
        return agent == 0 ? "MISSING_AGENT" : "MISSING_MODEL";
    }
}
