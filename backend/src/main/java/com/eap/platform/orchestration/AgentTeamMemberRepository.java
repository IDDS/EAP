package com.eap.platform.orchestration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTeamMemberRepository extends JpaRepository<AgentTeamMember, Long> {
    List<AgentTeamMember> findByTeamId(Long teamId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from AgentTeamMember m where m.teamId = :teamId order by m.id")
    List<AgentTeamMember> findCurrentByTeamId(Long teamId);
}

