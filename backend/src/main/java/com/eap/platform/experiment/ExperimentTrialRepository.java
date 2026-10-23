package com.eap.platform.experiment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ExperimentTrialRepository extends JpaRepository<ExperimentTrial,Long> {
 List<ExperimentTrial> findByExperimentIdOrderByIdAsc(Long id);
 Optional<ExperimentTrial> findFirstByStatusOrderByIdAsc(String status);
}
