package com.eap.platform.orchestration;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTeamRepository extends JpaRepository<AgentTeam, Long> {
    boolean existsByCode(String code);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from AgentTeam t where t.id = :id")
    java.util.Optional<AgentTeam> findForUpdate(Long id);
}

