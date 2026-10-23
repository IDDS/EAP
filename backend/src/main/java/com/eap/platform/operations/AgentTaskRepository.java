package com.eap.platform.operations;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<AgentTask> {
    interface FeedbackTask { Long getId(); String getTaskType(); AgentTaskStatus getStatus(); }
    @org.springframework.data.jpa.repository.Query("select t.id as id,t.taskType as taskType,t.status as status from AgentTask t where t.agentId=:agentId order by t.id desc")
    java.util.List<FeedbackTask> feedbackTasks(@org.springframework.data.repository.query.Param("agentId") Long agentId,org.springframework.data.domain.Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from AgentTask task where task.id = :id")
    Optional<AgentTask> findByIdForUpdate(@Param("id") Long id);

    long countByStatus(AgentTaskStatus status);
}

