package com.eap.platform.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowNodeRepository extends JpaRepository<WorkflowNode, Long> {
    List<WorkflowNode> findByWorkflowId(Long workflowId);

    Optional<WorkflowNode> findByWorkflowIdAndNodeKey(Long workflowId, String nodeKey);
}

