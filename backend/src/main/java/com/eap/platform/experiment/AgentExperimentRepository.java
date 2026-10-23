package com.eap.platform.experiment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AgentExperimentRepository extends JpaRepository<AgentExperiment,Long> {
 Optional<AgentExperiment> findBySubmissionKey(String key);
 Optional<AgentExperiment> findFirstByStatusInOrderByIdAsc(Collection<String> statuses);
 List<AgentExperiment> findTop50ByOrderByIdDesc();
}
