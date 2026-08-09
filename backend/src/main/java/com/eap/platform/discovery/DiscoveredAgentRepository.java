package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscoveredAgentRepository extends JpaRepository<DiscoveredAgent, Long> {
    Optional<DiscoveredAgent> findByFingerprint(String fingerprint);

    List<DiscoveredAgent> findAllByOrderByLastSeenAtDesc();

    long countByRiskLevelIn(List<RiskLevel> riskLevels);

    long countByStatus(DiscoveryAssetStatus status);

    long countByAssetTypeIn(List<DiscoveryAssetType> assetTypes);
}
