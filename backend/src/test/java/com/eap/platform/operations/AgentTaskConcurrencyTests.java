package com.eap.platform.operations;

import com.eap.platform.agent.AgentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentTaskConcurrencyTests {
    @Test
    void concurrentStatusAndTelemetryUpdatesPreserveAllUsage() throws Exception {
        var factory = new LocalContainerEntityManagerFactoryBean();
        // Keep the isolated schema alive between DDL, seed and concurrent transaction connections.
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.eap.platform.operations");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        var executor = Executors.newFixedThreadPool(4);
        try {
            EntityManager seed = emf.createEntityManager();
            seed.getTransaction().begin();
            var task = new AgentTask();
            task.agentId = 1L;
            task.taskType = "CONCURRENCY_TEST";
            seed.persist(task);
            seed.getTransaction().commit();
            Long id = task.id;
            seed.close();
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                boolean updateStatus = worker == 0;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int attempt = 0; attempt < 10; attempt++) {
                            EntityManager em = emf.createEntityManager();
                            try {
                                em.getTransaction().begin();
                                var repository = new JpaRepositoryFactory(em).getRepository(AgentTaskRepository.class);
                                var service = new AgentTaskService(repository, mock(AgentRepository.class));
                                if (updateStatus) {
                                    service.updateStatus(id, new AgentTaskDtos.StatusRequest(AgentTaskStatus.RUNNING, null));
                                } else {
                                    service.recordTelemetryUsage(id, 3, new BigDecimal("0.25"), 100);
                                }
                                em.getTransaction().commit();
                            } finally {
                                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                                em.close();
                            }
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(exception);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
            EntityManager check = emf.createEntityManager();
            try {
                AgentTask result = check.find(AgentTask.class, id);
                assertThat(result.status).isEqualTo(AgentTaskStatus.RUNNING);
                assertThat(result.tokenCost).isEqualTo(90L);
                assertThat(result.moneyCost).isEqualByComparingTo("7.50");
                assertThat(result.latencyMs).isEqualTo(100L);
                assertThat(result.startedAt).isNotNull();
            } finally {
                check.close();
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            factory.destroy();
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }
}
