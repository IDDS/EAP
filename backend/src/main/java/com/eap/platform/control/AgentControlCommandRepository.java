package com.eap.platform.control;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentControlCommandRepository extends JpaRepository<AgentControlCommand, Long> {
    List<AgentControlCommand> findTop20ByOrderByCreatedAtDesc();

    Optional<AgentControlCommand> findByCommandKey(String commandKey);
}
