package com.eap.platform.evaluation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eap.evaluation.auto-sync-enabled", havingValue = "true")
public class EvaluationProjectionScheduler {
    private final EvaluationJobService jobService;

    public EvaluationProjectionScheduler(EvaluationJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(
            initialDelayString = "${eap.evaluation.auto-sync-initial-delay-ms:30000}",
            fixedDelayString = "${eap.evaluation.auto-sync-interval-ms:60000}"
    )
    public void synchronize() {
        jobService.submitIfIdle(200);
    }
}
