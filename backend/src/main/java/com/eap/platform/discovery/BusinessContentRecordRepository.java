package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessContentRecordRepository extends JpaRepository<BusinessContentRecord, Long> {
    Optional<BusinessContentRecord> findByObservationKey(String observationKey);
}
