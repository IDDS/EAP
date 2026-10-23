package com.eap.platform.control;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface AgentControlCommandRepository extends JpaRepository<AgentControlCommand, Long> {
    List<AgentControlCommand> findTop20ByOrderByCreatedAtDesc();

    Optional<AgentControlCommand> findByCommandKey(String commandKey);
    // Serialize retries across requests and backend replicas until the attempt is persisted.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AgentControlCommand c where c.commandKey = :key")
    Optional<AgentControlCommand> findForRetry(@Param("key") String commandKey);
}
