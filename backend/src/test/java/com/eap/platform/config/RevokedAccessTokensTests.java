package com.eap.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RevokedAccessTokensTests {
    @Test
    void databaseFailureDoesNotTreatTokenAsNotRevoked() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenThrow(new DataAccessResourceFailureException("synthetic unavailable"));
        Jwt token = Jwt.withTokenValue("synthetic-only").header("alg", "test").subject("test").build();
        assertThatThrownBy(() -> new RevokedAccessTokens(jdbc).validate(token))
                .isInstanceOf(RevokedAccessTokens.StoreUnavailableException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }
}
