package com.eap.platform.control;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentControlCommandRepository extends JpaRepository<AgentControlCommand, Long> {
    List<AgentControlCommand> findTop20ByOrderByCreatedAtDesc();
}
