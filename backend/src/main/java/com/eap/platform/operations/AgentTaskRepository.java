package com.eap.platform.operations;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
    long countByStatus(AgentTaskStatus status);
}

