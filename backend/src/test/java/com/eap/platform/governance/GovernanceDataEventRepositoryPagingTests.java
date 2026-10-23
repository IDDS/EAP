package com.eap.platform.governance;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceDataEventRepositoryPagingTests {
    @Test
    void retrievesOlderAgentRowsBeyondGlobal500AndPagesDeterministically() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        // DriverManagerDataSource closes every connection; retain schema between DDL and test transactions.
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.eap.platform.governance");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        EntityManager em = factory.getObject().createEntityManager();
        try {
            em.getTransaction().begin();
            var first = row("target-1", 7L);
            var second = row("target-2", 7L);
            em.persist(first);
            em.persist(second);
            for (int index = 0; index < 501; index++) em.persist(row("other-" + index, 8L));
            em.flush();
            // Equal timestamps exercise the id tie-breaker and place the target behind 501 other rows.
            em.createNativeQuery("update governance_data_events set observed_at = :date")
                    .setParameter("date", Instant.parse("2026-01-01T00:00:00Z")).executeUpdate();
            em.clear();
            var repository = new JpaRepositoryFactory(em).getRepository(GovernanceDataEventRepository.class);
            assertThat(repository.findAllByOrderByObservedAtDescIdDesc(PageRequest.of(0, 500)))
                    .noneMatch(row -> row.agentId.equals(7L));
            assertThat(repository.findByAgentIdOrderByObservedAtDescIdDesc(7L, PageRequest.of(0, 1)))
                    .extracting(row -> row.id).containsExactly(second.id);
            assertThat(repository.findByAgentIdOrderByObservedAtDescIdDesc(7L, PageRequest.of(1, 1)))
                    .extracting(row -> row.id).containsExactly(first.id);
            assertThat(repository.findByAgentIdOrderByObservedAtDescIdDesc(7L, PageRequest.of(2, 1))).isEmpty();
            em.getTransaction().rollback();
        } finally {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
            factory.destroy();
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    private GovernanceDataEvent row(String key, Long agentId) {
        var row = new GovernanceDataEvent();
        row.agentId = agentId;
        row.eventKey = key;
        row.direction = GovernanceDataDirection.INPUT_TO_MODEL;
        row.dataCategory = "TEST";
        row.sensitivity = GovernanceDataSensitivity.PUBLIC;
        row.action = GovernanceDataAction.ALLOW;
        row.observedAt = Instant.parse("2026-01-01T00:00:00Z");
        return row;
    }
}
