package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiscoveryIdentityLinkRepository extends JpaRepository<DiscoveryIdentityLink, Long> {
    @Query("select link from DiscoveryIdentityLink link join fetch link.discoveredAgent where link.identityKey = :identityKey")
    Optional<DiscoveryIdentityLink> findByIdentityKey(@Param("identityKey") String identityKey);

    @EntityGraph(attributePaths = "discoveredAgent")
    List<DiscoveryIdentityLink> findTop100ByOrderByLastSeenAtDesc();
}
