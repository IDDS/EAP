package com.eap.platform.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KnowledgeInteractionRepository extends JpaRepository<KnowledgeInteraction, Long> {
    Optional<KnowledgeInteraction> findByInteractionKey(String interactionKey);

    List<KnowledgeInteraction> findTop500ByOrderByCreatedAtDesc();

    List<KnowledgeInteraction> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<KnowledgeInteraction> findByAgentIdOrderByCreatedAtDescIdDesc(Long agentId, Pageable pageable);

    List<KnowledgeInteraction> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(Instant createdAt);
    @org.springframework.data.jpa.repository.Query("select coalesce(max(e.id), 0) from KnowledgeInteraction e")
    long scanUpperBound();

    List<KnowledgeInteraction> findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
            long after, long upper, org.springframework.data.domain.Pageable page);

}
