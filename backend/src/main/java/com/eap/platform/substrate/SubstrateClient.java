package com.eap.platform.substrate;

import java.util.List;
import java.util.Map;

/**
 * The only EAP entry point into the Agent Substrate Control API. Keeping callers on this interface
 * isolates them from upstream API changes; Substrate is pre-release and its API is expected to move.
 */
public interface SubstrateClient {
    ActorState get(SubstrateActorRef actor);

    ActorState suspend(SubstrateActorRef actor);

    /** Returns only after every container wakeup probe has succeeded. */
    ActorState resume(SubstrateActorRef actor);

    ActorState revert(SubstrateActorRef actor);

    /** Creates the atespace if it does not exist yet. */
    void ensureAtespace(String atespace);

    /** Templates are immutable upstream; a changed deployment is always a new template. */
    void createTemplate(TemplateSpec spec);

    GoldenStatus goldenStatus(String atespace, String template);

    /** Creates a suspended actor; the first request or ResumeActor wakes it. */
    ActorState createActor(SubstrateActorRef actor, String template);

    /** Deletes an actor in any state, including one stuck mid-operation. */
    void deleteActor(SubstrateActorRef actor);

    /** Replaces the actor's egress policy with one hostname allowlist; an empty list denies all egress. */
    void setEgressHostnames(SubstrateActorRef actor, List<String> hostnames);

    /** @param state upstream lifecycle state without the {@code ACTOR_STATE_} prefix, e.g. RUNNING, SUSPENDED, CRASHED */
    record ActorState(String state) {
    }

    record GoldenStatus(boolean ready, String error) {
    }

    /**
     * One-container actor template. {@code image} must be pinned by digest; {@code env} values are
     * stored in plain text by Substrate, so callers must not put provider credentials here.
     */
    record TemplateSpec(String atespace, String name, String image, List<String> command, Map<String, String> env,
                        String wakeupPath, int wakeupPort, String cpu, String memory, boolean microVm,
                        Map<String, String> workerLabels, String snapshotLocation) {
    }

    /** A failed Control API call; {@code code} is the gRPC status code name, e.g. RESOURCE_EXHAUSTED. */
    class SubstrateException extends RuntimeException {
        private final String code;

        public SubstrateException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
