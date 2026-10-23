package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiscoveredAgentRepository extends JpaRepository<DiscoveredAgent, Long> {
    Optional<DiscoveredAgent> findByFingerprint(String fingerprint);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select agent from DiscoveredAgent agent where agent.id = :id")
    Optional<DiscoveredAgent> findByIdForUpdate(@Param("id") Long id);

    List<DiscoveredAgent> findAllByOrderByLastSeenAtDesc();

    List<DiscoveredAgent> findAllByManagedAgentIdOrderByLastSeenAtDesc(Long managedAgentId);

    List<DiscoveredAgent> findAllByLastEndpointKey(String endpointKey);

    long countByRiskLevelIn(List<RiskLevel> riskLevels);

    long countByStatus(DiscoveryAssetStatus status);

    long countByAssetTypeIn(List<DiscoveryAssetType> assetTypes);
}
