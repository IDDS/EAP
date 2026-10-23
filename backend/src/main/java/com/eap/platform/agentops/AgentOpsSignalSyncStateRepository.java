package com.eap.platform.agentops;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface AgentOpsSignalSyncStateRepository extends JpaRepository<AgentOpsSignalSyncState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AgentOpsSignalSyncState s where s.id = 1")
    Optional<AgentOpsSignalSyncState> lockState();
}
