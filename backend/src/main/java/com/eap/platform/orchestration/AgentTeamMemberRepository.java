package com.eap.platform.orchestration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTeamMemberRepository extends JpaRepository<AgentTeamMember, Long> {
    List<AgentTeamMember> findByTeamId(Long teamId);
}

