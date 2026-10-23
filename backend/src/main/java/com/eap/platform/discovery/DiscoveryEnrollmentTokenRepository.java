package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface DiscoveryEnrollmentTokenRepository extends JpaRepository<DiscoveryEnrollmentToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DiscoveryEnrollmentToken> findByTokenHash(String tokenHash);

    List<DiscoveryEnrollmentToken> findTop100ByOrderByCreatedAtDesc();
}
