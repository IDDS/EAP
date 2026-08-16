package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscoveryEnrollmentTokenRepository extends JpaRepository<DiscoveryEnrollmentToken, Long> {
    Optional<DiscoveryEnrollmentToken> findByTokenHash(String tokenHash);

    List<DiscoveryEnrollmentToken> findTop100ByOrderByCreatedAtDesc();
}
