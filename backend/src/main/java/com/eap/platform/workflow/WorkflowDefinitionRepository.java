package com.eap.platform.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowDefinition w where w.id = :id")
    Optional<WorkflowDefinition> findForUpdate(Long id);
    boolean existsByCode(String code);
}

