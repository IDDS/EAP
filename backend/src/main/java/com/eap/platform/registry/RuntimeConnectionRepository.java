package com.eap.platform.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RuntimeConnectionRepository extends JpaRepository<RuntimeConnection,Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from RuntimeConnection c where c.instanceKey = :key")
    Optional<RuntimeConnection> lockForToolAccess(@org.springframework.data.repository.query.Param("key") String key);
    Optional<RuntimeConnection> findByInstanceKey(String instanceKey);
}
