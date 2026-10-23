package com.eap.platform.orchestration;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTeamRepository extends JpaRepository<AgentTeam, Long> {
    boolean existsByCode(String code);
}

