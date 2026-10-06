package com.eap.platform.ax;

import java.time.Instant;

/** Immutable task identity projection; uses the same Instant mapping as the task entity. */
public record AxTaskIdentity(Long agentId, String taskKey, String instanceKey, String identityMode,
        String identityScope, String actorUid, String actorTemplate, String deliveryRequest,
        String identityPublicKey, Instant identityUntil, boolean identityRevoked) {}
