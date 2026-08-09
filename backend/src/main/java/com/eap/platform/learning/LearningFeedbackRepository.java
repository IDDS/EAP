package com.eap.platform.learning;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningFeedbackRepository extends JpaRepository<LearningFeedback, Long> {
    long countByStatus(LearningFeedbackStatus status);
}

