package com.eap.platform.config;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One database per project. This is not a row-level multitenancy filter. */
@Component
@DependsOnDatabaseInitialization
public class ProjectIsolation implements OAuth2TokenValidator<Jwt> {
    public static final String CLAIM = "eap_project";
    private final String projectKey;

    public ProjectIsolation(SecurityProperties security, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        projectKey = security.getProjectKey();
        if (!projectKey.isEmpty() && (!projectKey.matches("[a-z][a-z0-9-]{0,47}")
                || !security.isJwtMode() || security.getIssuerUri().isEmpty() || security.getAudience().isEmpty())) {
            throw new IllegalStateException("项目部署必须使用有效项目标识及 JWT issuer、audience");
        }
        if (projectKey.isEmpty() && security.isInitializeProjectBinding()) {
            throw new IllegalStateException("初始化数据库项目绑定必须指定项目");
        }
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var rows = jdbc.query("select project_key from platform_project_binding where id=1 for update",
                    (rs, row) -> rs.getString(1));
            if (rows.size() != 1) throw new IllegalStateException("数据库项目绑定记录缺失");
            String stored = rows.get(0);
            if (stored != null) {
                if (!stored.equals(projectKey)) throw new IllegalStateException("数据库已绑定其他项目或隔离配置被关闭");
            } else if (!projectKey.isEmpty()) {
                if (!security.isInitializeProjectBinding()) {
                    throw new IllegalStateException("数据库尚未绑定项目；首次绑定须由运维显式启用");
                }
                if (jdbc.update("update platform_project_binding set project_key=?, bound_at=CURRENT_TIMESTAMP where id=1 and project_key is null", projectKey) != 1) {
                    throw new IllegalStateException("数据库项目绑定发生冲突");
                }
            }
        });
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        // No coercion, list membership, caller header or role can select a database/project.
        if (projectKey.isEmpty() || projectKey.equals(token.getClaims().get(CLAIM))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "令牌不属于当前项目", null));
    }

    public Context context() {
        return new Context(projectKey.isEmpty() ? "SHARED_TRUST_DOMAIN" : "DEDICATED_PROJECT",
                projectKey.isEmpty() ? null : projectKey);
    }

    public void requireRuntimeScope(String scope) {
        if (!projectKey.isEmpty() && !("eap-" + projectKey + "-agents").equals(scope)
                && !("eap-" + projectKey + "-ax").equals(scope)) {
            throw new com.eap.platform.substrate.SubstrateClient.SubstrateException("PERMISSION_DENIED", "运行空间不属于当前项目");
        }
    }

    public void requireBuildNamespace(String namespace) {
        if (!projectKey.isEmpty() && !("eap-" + projectKey + "-builds").equals(namespace)) {
            throw new com.eap.platform.deployment.ImageBuilder.BuildException("构建命名空间不属于当前项目");
        }
    }

    public record Context(String isolationMode, String projectKey) {}
}
