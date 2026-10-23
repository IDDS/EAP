package com.eap.platform.management;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ManagementValueAttributionRepository extends JpaRepository<ManagementValueAttribution, Long> {
    List<ManagementValueAttribution> findByValueEventId(Long valueEventId);

    List<ManagementValueAttribution> findByValueEventIdIn(Collection<Long> valueEventIds);
}
