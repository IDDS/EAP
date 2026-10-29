package com.eap.platform.orchestration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentPlanRepository extends JpaRepository<AgentPlan, Long> {
    List<AgentPlan> findByTeamId(Long teamId);
    @org.springframework.data.jpa.repository.Query("select p.teamId from AgentPlan p where p.id = :id")
    java.util.Optional<Long> findTeamIdById(Long id);
    boolean existsByExecutionWorkflowId(Long workflowId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from AgentPlan p where p.id = :id")
    java.util.Optional<AgentPlan> findForUpdate(Long id);
    List<AgentPlan> findByStatusInAndExecutionRunKeyIsNotNull(List<PlanStatus> statuses, org.springframework.data.domain.Pageable pageable);
}

