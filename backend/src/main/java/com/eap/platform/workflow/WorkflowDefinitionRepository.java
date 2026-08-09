package com.eap.platform.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, Long> {
    boolean existsByCode(String code);
}

