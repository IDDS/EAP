package com.eap.platform.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SafetySessionRepository extends JpaRepository<SafetySession, Long> {
    Optional<SafetySession> findBySessionKey(String sessionKey);
}

