package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscoveryIdentityLinkRepository extends JpaRepository<DiscoveryIdentityLink, Long> {
    Optional<DiscoveryIdentityLink> findByIdentityKey(String identityKey);

    @EntityGraph(attributePaths = "discoveredAgent")
    List<DiscoveryIdentityLink> findTop100ByOrderByLastSeenAtDesc();
}
