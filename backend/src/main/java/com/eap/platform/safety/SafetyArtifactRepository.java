package com.eap.platform.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SafetyArtifactRepository extends JpaRepository<SafetyArtifact, Long> {
    List<SafetyArtifact> findBySessionKeyOrderByCreatedAtDesc(String sessionKey);
}

