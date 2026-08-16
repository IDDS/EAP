package com.eap.platform.management;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManagementValueConnectorRepository extends JpaRepository<ManagementValueConnector, Long> {
    Optional<ManagementValueConnector> findByConnectorKey(String connectorKey);

    List<ManagementValueConnector> findByEnabledTrueOrderByNameAsc();

    List<ManagementValueConnector> findAllByOrderByNameAsc();
}
