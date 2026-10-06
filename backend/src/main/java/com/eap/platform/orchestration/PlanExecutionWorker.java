package com.eap.platform.orchestration;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import java.util.List;

@Component
@ConditionalOnProperty(name="eap.orchestration.execution.worker-enabled", havingValue="true", matchIfMissing=true)
public class PlanExecutionWorker {
    private final AgentPlanRepository plans;
    private final PlanExecutionService service;
    public PlanExecutionWorker(AgentPlanRepository plans, PlanExecutionService service) { this.plans = plans; this.service = service; }
    @Scheduled(initialDelayString="${eap.orchestration.execution.initial-delay-ms:5000}", fixedDelayString="${eap.orchestration.execution.interval-ms:2000}")
    public void synchronize() {
        for (var plan : plans.findByStatusInAndExecutionRunKeyIsNotNull(List.of(PlanStatus.QUEUED, PlanStatus.RUNNING), PageRequest.of(0, 100))) {
            try { service.synchronize(plan.id); }
            catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("计划状态同步失败，planId={}", plan.id); }
        }
    }
}
