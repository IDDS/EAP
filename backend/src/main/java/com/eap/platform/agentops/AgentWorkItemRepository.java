package com.eap.platform.agentops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentWorkItemRepository extends JpaRepository<AgentWorkItem, Long> {
    Optional<AgentWorkItem> findByItemKey(String itemKey);

    List<AgentWorkItem> findAllByOrderBySeverityAscSlaDueAtAsc();

    List<AgentWorkItem> findAllByAgentIdOrderByUpdatedAtDesc(Long agentId);

    long countByStatusIn(List<WorkItemStatus> statuses);

    long countBySeverityAndStatusIn(WorkItemSeverity severity, List<WorkItemStatus> statuses);
}
