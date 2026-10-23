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
            + "and upper(r.protocol) not in ('SUBSTRATE', 'SUBSTRATE_OBSERVED', 'SUBSTRATE_TASK')")
    int expireSilentRuntimes(@Param("active") List<RuntimeStatus> active, @Param("offline") RuntimeStatus offline,
                            @Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentRuntimeInstance r where r.instanceKey=:key")
    Optional<AgentRuntimeInstance> lockForScopedTools(@Param("key") String key);
    Optional<AgentRuntimeInstance> findByInstanceKey(String instanceKey);
}
