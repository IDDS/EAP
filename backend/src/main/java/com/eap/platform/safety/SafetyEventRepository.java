package com.eap.platform.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SafetyEventRepository extends JpaRepository<SafetyEvent, Long> {
    List<SafetyEvent> findBySessionKeyOrderByCreatedAtDesc(String sessionKey);
}

