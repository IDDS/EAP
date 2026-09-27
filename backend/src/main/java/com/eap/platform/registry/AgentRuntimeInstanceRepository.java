package com.eap.platform.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

import java.util.List;
import java.util.Optional;

public interface AgentRuntimeInstanceRepository extends JpaRepository<AgentRuntimeInstance, Long> {
    List<AgentRuntimeInstance> findByAgentId(Long agentId);

    Optional<AgentRuntimeInstance> findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(Long agentId, RuntimeStatus status, Instant cutoff);

    // Substrate actors are excluded: suspended actors send no heartbeats, and Substrate owns their state.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentRuntimeInstance r set r.status = :offline, r.updatedAt = :now "
            + "where r.status in :active and (r.lastHeartbeatAt is null or r.lastHeartbeatAt <= :cutoff) "
            + "and upper(r.protocol) <> 'SUBSTRATE'")
    int expireSilentRuntimes(@Param("active") List<RuntimeStatus> active, @Param("offline") RuntimeStatus offline,
                            @Param("cutoff") Instant cutoff, @Param("now") Instant now);

    Optional<AgentRuntimeInstance> findByInstanceKey(String instanceKey);
}
