package com.eap.platform.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvaluationDatasetRepository extends JpaRepository<EvaluationDataset, Long> {
    Optional<EvaluationDataset> findByDatasetKey(String datasetKey);

    List<EvaluationDataset> findAllByOrderByDatasetKeyAsc();
}
