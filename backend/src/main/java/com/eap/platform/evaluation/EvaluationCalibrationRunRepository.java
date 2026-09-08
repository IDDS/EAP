package com.eap.platform.evaluation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvaluationCalibrationRunRepository extends JpaRepository<EvaluationCalibrationRun, String> {
    List<Summary> findByDatasetKeyOrderByCalculatedAtDescRunIdDesc(String datasetKey, Pageable pageable);
    Optional<EvaluationCalibrationRun> findByRunIdAndDatasetKey(String runId, String datasetKey);

    // A closed projection keeps the potentially large evidence JSON out of history listings.
    interface Summary {
        String getRunId();
        String getDatasetKey();
        String getDatasetVersion();
        int getRequestedLimit();
        int getTotalItems();
        int getEvaluatedItems();
        int getFailedItems();
        java.time.Instant getCalculatedAt();
    }
}
