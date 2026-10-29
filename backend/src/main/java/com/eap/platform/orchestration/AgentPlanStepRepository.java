package com.eap.platform.orchestration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentPlanStepRepository extends JpaRepository<AgentPlanStep, Long> {
    List<AgentPlanStep> findByPlanIdOrderByStepOrder(Long planId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from AgentPlanStep s where s.planId = :planId order by s.stepOrder, s.id")
    List<AgentPlanStep> findCurrentByPlanId(Long planId);

    @org.springframework.data.jpa.repository.Query("select count(s) from AgentPlanStep s, AgentPlan p where s.planId = p.id and p.teamId = :teamId and p.executionRunKey is null and s.assignedAgentId = :agentId")
    long countUnsubmittedAssignments(Long teamId, Long agentId);

    @org.springframework.data.jpa.repository.Query("select s.planId from AgentPlanStep s where s.id = :id")
    java.util.Optional<Long> findPlanIdById(Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from AgentPlanStep s where s.id = :id")
    java.util.Optional<AgentPlanStep> findForUpdate(Long id);
}

