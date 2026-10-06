package com.eap.platform.experiment;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
public interface ExperimentAdmissionRepository extends JpaRepository<ExperimentAdmission,Long> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from ExperimentAdmission a where a.id=1") ExperimentAdmission lock();
}
