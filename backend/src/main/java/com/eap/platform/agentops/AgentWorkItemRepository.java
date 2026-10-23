package com.eap.platform.agentops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentWorkItemRepository extends JpaRepository<AgentWorkItem, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<AgentWorkItem> {
    Optional<AgentWorkItem> findByItemKey(String itemKey);

    List<AgentWorkItem> findAllByOrderBySeverityAscSlaDueAtAsc();

    List<AgentWorkItem> findAllByAgentIdOrderByUpdatedAtDesc(Long agentId);

    List<AgentWorkItem> findByStatusIn(List<WorkItemStatus> statuses);

    List<AgentWorkItem> findByAgentIdAndStatusIn(Long agentId, List<WorkItemStatus> statuses);

    long countByStatusIn(List<WorkItemStatus> statuses);

    long countBySeverityAndStatusIn(WorkItemSeverity severity, List<WorkItemStatus> statuses);
}
