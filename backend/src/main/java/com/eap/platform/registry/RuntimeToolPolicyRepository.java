package com.eap.platform.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface RuntimeToolPolicyRepository extends JpaRepository<RuntimeToolPolicy,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RuntimeToolPolicy p where p.agentId=:id")
    Optional<RuntimeToolPolicy> lockByAgentId(@Param("id") Long id);
}
