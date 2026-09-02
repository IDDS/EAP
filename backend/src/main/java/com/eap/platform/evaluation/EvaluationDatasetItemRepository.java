package com.eap.platform.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvaluationDatasetItemRepository extends JpaRepository<EvaluationDatasetItem, Long> {
    Optional<EvaluationDatasetItem> findByDatasetIdAndItemKey(Long datasetId, String itemKey);

    List<EvaluationDatasetItem> findAllByDatasetIdOrderByCreatedAtAsc(Long datasetId);
}
