package com.eap.platform.observability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ContentObservationRepository extends JpaRepository<ContentObservation, Long>, JpaSpecificationExecutor<ContentObservation> {
    Optional<ContentObservation> findByObservationKey(String observationKey);

    List<ContentObservation> findTop500ByOrderByObservedAtDesc();
}
