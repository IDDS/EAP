package com.eap.platform.discovery;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
class DiscoveryEnrollmentConcurrencyTests {
    @Autowired DiscoveryAgentV1Service service;
    @Autowired DiscoveryEnrollmentTokenRepository tokens;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void singleUseTokenSerializesConcurrentEnrollmentsUntilCommit() throws Exception {
        var token = service.createEnrollmentToken(new DiscoveryAgentV1Dtos.EnrollmentTokenRequest(
                "default", "test", "test", "test", null, 15, 1, "test"));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var response = service.enroll(request(token.token(), "first"));
                held.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return response;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return service.enroll(request(token.token(), "second"));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).credential()).startsWith("eap_agent_");
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class);
            var stored = tokens.findById(token.id()).orElseThrow();
            assertThat(stored.usedCount).isEqualTo(1);
            assertThat(stored.status).isEqualTo("USED");
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private DiscoveryAgentV1Dtos.EnrollRequest request(String token, String suffix) {
        return new DiscoveryAgentV1Dtos.EnrollRequest(token, "concurrent-" + suffix + UUID.randomUUID(),
                "fingerprint", "concurrent-host", "test", "Linux", "amd64", "1.0.0", List.of("PROCESS"));
    }
}
