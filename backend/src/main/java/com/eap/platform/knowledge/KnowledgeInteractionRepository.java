package com.eap.platform.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KnowledgeInteractionRepository extends JpaRepository<KnowledgeInteraction, Long> {
    Optional<KnowledgeInteraction> findByInteractionKey(String interactionKey);

    List<KnowledgeInteraction> findTop500ByOrderByCreatedAtDesc();

    List<KnowledgeInteraction> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(Instant createdAt);
}
