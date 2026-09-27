package com.eap.platform.deployment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KanikoImageBuilderTests {
    @Test
    void gitContextUsesBranchRefsAndPassesCommitsAndFullRefsThrough() {
        assertThat(KanikoImageBuilder.gitContext("https://gitee.com/org/agent.git", "main"))
                .isEqualTo("git://gitee.com/org/agent.git#refs/heads/main");
        assertThat(KanikoImageBuilder.gitContext("https://gitee.com/org/agent.git", "refs/tags/v1.2"))
                .isEqualTo("git://gitee.com/org/agent.git#refs/tags/v1.2");
        String commit = "0123456789abcdef0123456789abcdef01234567";
        assertThat(KanikoImageBuilder.gitContext("https://gitee.com/org/agent.git", commit))
                .isEqualTo("git://gitee.com/org/agent.git#" + commit);
        assertThat(KanikoImageBuilder.gitContext("http://git.eap-git.svc/agent.git", "main"))
                .isEqualTo("git://git.eap-git.svc/agent.git#refs/heads/main");
    }
}
