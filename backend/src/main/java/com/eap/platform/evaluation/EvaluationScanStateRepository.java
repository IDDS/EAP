package com.eap.platform.evaluation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface EvaluationScanStateRepository extends JpaRepository<EvaluationScanState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EvaluationScanState s where s.id = 1")
    Optional<EvaluationScanState> lockState();
}
