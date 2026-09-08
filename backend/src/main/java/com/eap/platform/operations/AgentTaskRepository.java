package com.eap.platform.operations;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from AgentTask task where task.id = :id")
    Optional<AgentTask> findByIdForUpdate(@Param("id") Long id);

    long countByStatus(AgentTaskStatus status);
}

