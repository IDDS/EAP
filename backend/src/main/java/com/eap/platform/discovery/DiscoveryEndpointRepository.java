package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscoveryEndpointRepository extends JpaRepository<DiscoveryEndpoint, Long> {
    Optional<DiscoveryEndpoint> findByEndpointKey(String endpointKey);

    List<DiscoveryEndpoint> findAllByOrderByLastSeenAtDesc();
}
