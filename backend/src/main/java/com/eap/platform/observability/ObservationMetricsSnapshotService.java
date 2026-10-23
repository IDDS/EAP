package com.eap.platform.observability;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A separate read transaction keeps monitoring outside dispatch and model execution. */
@Service
public class ObservationMetricsSnapshotService {
    private final ObservationCoverageService coverage;

    public ObservationMetricsSnapshotService(ObservationCoverageService coverage) {
        this.coverage = coverage;
    }

    @Transactional(readOnly = true, timeout = 2)
    public ObservationCoverageService.Report read(int minutes) {
        return coverage.report(minutes);
    }
}
