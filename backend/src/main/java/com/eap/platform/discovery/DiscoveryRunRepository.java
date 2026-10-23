package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscoveryRunRepository extends JpaRepository<DiscoveryRun, Long> {
    List<DiscoveryRun> findTop20ByOrderByStartedAtDesc();

    Optional<DiscoveryRun> findTopByOrderByStartedAtDesc();
}
