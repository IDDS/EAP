package com.eap.platform.agentops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentWorkItemEventRepository extends JpaRepository<AgentWorkItemEvent, Long> {
    List<AgentWorkItemEvent> findByWorkItemIdOrderByCreatedAtAsc(Long workItemId);
}
