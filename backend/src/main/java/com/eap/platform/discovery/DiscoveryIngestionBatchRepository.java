package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiscoveryIngestionBatchRepository extends JpaRepository<DiscoveryIngestionBatch, Long> {
    Optional<DiscoveryIngestionBatch> findByEndpointKeyAndBatchId(String endpointKey, String batchId);
}
