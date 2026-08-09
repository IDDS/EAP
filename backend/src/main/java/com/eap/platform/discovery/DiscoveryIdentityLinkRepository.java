package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscoveryIdentityLinkRepository extends JpaRepository<DiscoveryIdentityLink, Long> {
    Optional<DiscoveryIdentityLink> findByIdentityKey(String identityKey);

    List<DiscoveryIdentityLink> findTop100ByOrderByLastSeenAtDesc();
}
