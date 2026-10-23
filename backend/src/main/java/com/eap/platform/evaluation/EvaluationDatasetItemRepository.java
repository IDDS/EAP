package com.eap.platform.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface EvaluationDatasetItemRepository extends JpaRepository<EvaluationDatasetItem, Long> {
    Optional<EvaluationDatasetItem> findByDatasetIdAndItemKey(Long datasetId, String itemKey);

    List<EvaluationDatasetItem> findByDatasetIdOrderByCreatedAtAscIdAsc(Long datasetId, Pageable pageable);

    long countByDatasetId(Long datasetId);
}
