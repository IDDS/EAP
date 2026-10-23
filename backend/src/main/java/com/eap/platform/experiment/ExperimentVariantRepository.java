package com.eap.platform.experiment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant,Long> {
 List<ExperimentVariant> findByExperimentIdOrderByIdAsc(Long id);
}
