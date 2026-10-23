package com.eap.platform.learning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface LearningFeedbackRepository extends JpaRepository<LearningFeedback,Long> {
    long countByStatus(LearningFeedbackStatus status);
    Optional<LearningFeedback> findByAgentIdAndSubmissionKey(Long agentId,String submissionKey);
    @Query("select f from LearningFeedback f, Agent a where f.agentId=a.id and (:agentId is null or f.agentId=:agentId) and (:includeDemo=true or a.recordOrigin<>com.eap.platform.agent.AgentRecordOrigin.DEMO) order by f.id desc")
    List<LearningFeedback> recent(@Param("agentId") Long agentId,@Param("includeDemo") boolean includeDemo,Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from LearningFeedback f where f.id=:id")
    Optional<LearningFeedback> lock(@Param("id") Long id);
}
