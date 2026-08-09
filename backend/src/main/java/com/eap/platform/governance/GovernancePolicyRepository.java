package com.eap.platform.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GovernancePolicyRepository extends JpaRepository<GovernancePolicy, Long> {
    List<GovernancePolicy> findByEnabledTrue();
}
