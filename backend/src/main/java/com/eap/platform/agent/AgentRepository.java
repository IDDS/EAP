package com.eap.platform.agent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Agent a where a.id = :id")
    java.util.Optional<Agent> lockForAx(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Agent a where a.id = :id")
    java.util.Optional<Agent> lockForToolPolicy(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Agent a where a.id = :id")
    java.util.Optional<Agent> lockForFeedback(@org.springframework.data.repository.query.Param("id") Long id);
    boolean existsByCode(String code);
}

