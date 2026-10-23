package com.eap.platform.deployment;

/** Builds a container image from a Git repository inside the cluster (deployment source "SOURCE"). */
public interface ImageBuilder {
    boolean enabled();

    /** Starts the build; starting a build whose name already exists is a no-op, so a restarted worker can re-issue it. */
    void start(BuildSpec spec);

    BuildStatus status(String name);

    /** The last lines of the build log, or an empty string when no build pod exists. */
    String logTail(String name, int lines);

    void delete(String name);

    /** Repository as templates reference it (without tag or digest), e.g. localhost:5001/eap-src-a7. */
    String repository(Long agentId);

    /** Where the build pod pushes, e.g. registry.example.com/eap-src-a7:v3; same repository as {@link #repository}. */
    String destination(Long agentId, Integer version);

    /**
     * @param gitUrl     https URL of a repository the build pod can clone without credentials
     * @param gitRef     branch, tag ({@code refs/tags/...}) or 40-character commit
     * @param contextPath directory inside the repository used as the build context ("" for the root)
     * @param dockerfile Dockerfile path relative to the context
     */
    record BuildSpec(String name, String gitUrl, String gitRef, String contextPath, String dockerfile, String destination) {
    }

    /** @param state RUNNING, SUCCEEDED, FAILED or MISSING; {@code digest} is set only when SUCCEEDED */
    record BuildStatus(String state, String digest, String reason) {
    }

    class BuildException extends RuntimeException {
        public BuildException(String message) {
            super(message);
        }
    }
}
