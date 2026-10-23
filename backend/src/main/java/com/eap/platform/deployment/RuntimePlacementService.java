package com.eap.platform.deployment;

import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeStatus;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Current Control API evidence only; no cached/historical location, wakeup or routing inference. */
@Service
public class RuntimePlacementService {
    private static final Pattern UID = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?");
    private final AgentRuntimeInstanceRepository runtimes;
    private final SubstrateClient client;

    public RuntimePlacementService(AgentRuntimeInstanceRepository runtimes, SubstrateClient client) {
        this.runtimes = runtimes;
        this.client = client;
    }

    public record Snapshot(String instanceKey, String status, String reason, String source,
                           Instant observationStartedAt, Instant observedAt, String actorUid, Long actorVersion,
                           String actorState, SubstrateClient.WorkerPlacement placement) {}

    public Snapshot current(Long agentId, String instanceKey) {
        return current(agentId, instanceKey, null, null);
    }

    /** A delayed observation must still use the original task's immutable binding. */
    public Snapshot currentForBinding(Long agentId, String instanceKey, String actorUid, String template) {
        if (actorUid == null || template == null)
            return unknown(instanceKey, "IDENTITY_UNBOUND", Instant.now());
        return current(agentId, instanceKey, actorUid, template);
    }

    private Snapshot current(Long agentId, String instanceKey, String expectedUid, String expectedTemplate) {
        if (instanceKey == null || instanceKey.length() > 128) throw new BusinessException("实例标识不正确");
        var runtime = runtimes.findByInstanceKey(instanceKey)
                .filter(r -> Objects.equals(agentId, r.getAgentId()) && SubstrateActorRef.isSubstrate(r.getProtocol()))
                .orElseThrow(() -> new BusinessException("实例不存在"));
        Instant started = Instant.now();
        if (expectedUid != null && (!expectedUid.equals(runtime.getBoundActorUid())
                || !expectedTemplate.equals(runtime.getBoundActorTemplate())))
            return unknown(instanceKey, "ORIGINAL_IDENTITY_MISMATCH", started);
        if (runtime.getStatus() == RuntimeStatus.OFFLINE) return unknown(instanceKey, "INSTANCE_OFFLINE", started);
        if (!validUid(runtime.getBoundActorUid()) || runtime.getBoundActorTemplate() == null)
            return unknown(instanceKey, "IDENTITY_UNBOUND", started);
        SubstrateActorRef ref;
        try { ref = SubstrateActorRef.parse(runtime.getEndpoint()); }
        catch (IllegalArgumentException e) { return unknown(instanceKey, "INVALID_REFERENCE", started); }
        if (!ref.routingValue().equals(instanceKey)) return unknown(instanceKey, "INVALID_REFERENCE", started);
        SubstrateClient.ActorLocation location;
        try { location = client.locate(ref); }
        catch (SubstrateClient.SubstrateException e) {
            String reason = switch (e.code()) {
                case "NOT_FOUND" -> "ACTOR_OR_WORKER_NOT_FOUND";
                case "ABORTED" -> "SNAPSHOT_CHANGED";
                case "DATA_LOSS" -> "INCONSISTENT_ASSIGNMENT";
                default -> "CONTROL_API_UNAVAILABLE";
            };
            // Neither upstream messages nor arbitrary status codes are public evidence.
            return unknown(instanceKey, reason, started);
        }
        if (location == null || location.actor() == null) return unknown(instanceKey, "INCOMPLETE_METADATA", started);
        var actor = location.actor();
        if (!ref.atespace().equals(actor.atespace()) || !ref.name().equals(actor.name())
                || !runtime.getBoundActorUid().equals(actor.uid())
                || !runtime.getBoundActorTemplate().equals(actor.template()))
            return unknown(instanceKey, "IDENTITY_MISMATCH", started);
        if (location.actorVersion() <= 0 || actor.state() == null)
            return unknown(instanceKey, "INCOMPLETE_METADATA", started);
        if (Set.of("SUSPENDED", "PAUSED", "CRASHED").contains(actor.state()))
            return snapshot(instanceKey, "UNASSIGNED", "ACTOR_NOT_RUNNING", started, location, null);
        if (!"RUNNING".equals(actor.state())) return unknown(instanceKey, "ACTOR_TRANSITIONING", started);
        var p = location.placement();
        if (p == null) return unknown(instanceKey, "NO_WORKER_ASSIGNMENT", started);
        if (!validUid(p.workerUid()) || !validUid(p.podUid()) || !validName(p.workerName())
                || !validName(p.namespace()) || !validName(p.pool()) || !validName(p.podName()) || !validName(p.nodeName()))
            return unknown(instanceKey, "INCOMPLETE_METADATA", started);
        return snapshot(instanceKey, "ASSIGNED", "VERIFIED_CONTROL_SNAPSHOT", started, location, p);
    }

    private static boolean validUid(String value) { return value != null && UID.matcher(value).matches(); }
    private static boolean validName(String value) { return value != null && NAME.matcher(value).matches(); }
    private static Snapshot unknown(String key, String reason, Instant started) {
        return new Snapshot(key, "UNKNOWN", reason, null, started, Instant.now(), null, null, null, null);
    }
    private static Snapshot snapshot(String key, String status, String reason, Instant started,
                                     SubstrateClient.ActorLocation location, SubstrateClient.WorkerPlacement placement) {
        return new Snapshot(key, status, reason, "SUBSTRATE_CONTROL_API", started, Instant.now(),
                location.actor().uid(), location.actorVersion(), location.actor().state(), placement);
    }
}
