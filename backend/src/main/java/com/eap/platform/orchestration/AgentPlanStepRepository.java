package com.eap.platform.orchestration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentPlanStepRepository extends JpaRepository<AgentPlanStep, Long> {
    List<AgentPlanStep> findByPlanIdOrderByStepOrder(Long planId);
}

