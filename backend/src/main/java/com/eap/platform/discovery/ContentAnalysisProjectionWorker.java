package com.eap.platform.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eap.observability.content-analysis-enabled", havingValue = "true", matchIfMissing = true)
public class ContentAnalysisProjectionWorker {
    private static final Logger log = LoggerFactory.getLogger(ContentAnalysisProjectionWorker.class);
    private final BusinessContentRecordRepository repository;
    private final ContentAnalysisProjectionService service;

    public ContentAnalysisProjectionWorker(BusinessContentRecordRepository repository, ContentAnalysisProjectionService service) {
        this.repository = repository;
        this.service = service;
    }

    @Scheduled(initialDelayString = "${eap.observability.content-analysis-initial-delay-ms:15000}",
            fixedDelayString = "${eap.observability.content-analysis-interval-ms:5000}")
    public void process() {
        for (Long id : repository.findPendingProjectionIds(PageRequest.of(0, 50))) {
            try {
                service.project(id);
            } catch (RuntimeException exception) {
                String type = exception.getClass().getSimpleName();
                log.warn("Content analysis projection failed: recordId={}, type={}", id, type);
                service.recordFailure(id, type);
            }
        }
    }
}
