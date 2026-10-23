package com.eap.platform.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeTraceStepRepository extends JpaRepository<KnowledgeTraceStep, Long> {
    List<KnowledgeTraceStep> findByInteractionIdOrderByStepOrderAsc(Long interactionId);
}
