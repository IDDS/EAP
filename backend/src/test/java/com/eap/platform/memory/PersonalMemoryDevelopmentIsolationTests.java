package com.eap.platform.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=DEVELOPMENT"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class PersonalMemoryDevelopmentIsolationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    @Test void unauthenticatedDevelopmentModeCannotReadOrCreatePersonalMemories() throws Exception {
        long before = db.queryForObject("select count(*) from personal_memories", Long.class);
        mvc.perform(get("/api/personal-memories")).andExpect(status().isForbidden());
        mvc.perform(post("/api/personal-memories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"00000000-0000-4000-8000-000000000001\",\"content\":\"synthetic\",\"explicitlyRemember\":true}"))
                .andExpect(status().isForbidden());
        assertThat(db.queryForObject("select count(*) from personal_memories", Long.class)).isEqualTo(before);
    }
}
