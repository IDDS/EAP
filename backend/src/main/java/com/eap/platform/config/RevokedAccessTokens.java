package com.eap.platform.config;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Shared, persistent revocation of a specific validated bearer token. Never stores the token itself. */
@Component
public class RevokedAccessTokens implements OAuth2TokenValidator<Jwt> {
    private final JdbcTemplate jdbc;

    public RevokedAccessTokens(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Integer count;
        try {
            count = jdbc.queryForObject("select count(*) from revoked_access_tokens where token_hash=?",
                    Integer.class, fingerprint(token.getTokenValue()));
        } catch (DataAccessException unavailable) {
            throw new StoreUnavailableException(unavailable);
        }
        if (count == null || count != 0) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "令牌已撤销", null));
        }
        return OAuth2TokenValidatorResult.success();
    }

    public void revoke(Jwt token) {
        Instant expiry = token.getExpiresAt();
        try {
            jdbc.update("insert into revoked_access_tokens(token_hash, revoked_at_ms, expires_at_ms) values(?,?,?)",
                    fingerprint(token.getTokenValue()), System.currentTimeMillis(), expiry == null ? null : expiry.toEpochMilli());
        } catch (DuplicateKeyException ignored) {
            // Concurrent logout requests for the same token have the same effect.
        }
    }

    private static String fingerprint(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static final class StoreUnavailableException extends JwtException {
        public StoreUnavailableException(Throwable cause) { super("令牌撤销检查暂不可用", cause); }
    }
}
