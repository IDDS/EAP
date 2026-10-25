package com.eap.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
class EapApplicationTests {

    @Test
    void contextLoads() {
    }
}
