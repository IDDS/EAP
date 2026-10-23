package com.eap.platform.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowEdgeRepository extends JpaRepository<WorkflowEdge, Long> {
    List<WorkflowEdge> findByWorkflowId(Long workflowId);
}

