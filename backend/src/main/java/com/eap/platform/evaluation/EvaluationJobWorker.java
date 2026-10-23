package com.eap.platform.evaluation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eap.evaluation.async-worker-enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationJobWorker {
    private final EvaluationJobService service;

    public EvaluationJobWorker(EvaluationJobService service) {
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${eap.evaluation.worker-initial-delay-ms:10000}",
            fixedDelayString = "${eap.evaluation.worker-interval-ms:5000}"
    )
    public void process() {
        service.processNext();
    }
}
