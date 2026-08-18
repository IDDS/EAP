package com.eap.platform.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "eap.discovery")
public class DiscoveryProperties {
    private boolean enabled = true;
    private boolean includeKnownUserLocations = true;
    private List<String> scanRoots = new ArrayList<>();
    private int maxDepth = 6;
    private int maxFiles = 3000;
    private long maxFileBytes = 1_048_576;
    private int maxProcesses = 2000;
    private String ingestionToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getScanRoots() {
        return scanRoots;
    }

    public boolean isIncludeKnownUserLocations() {
        return includeKnownUserLocations;
    }

    public void setIncludeKnownUserLocations(boolean includeKnownUserLocations) {
        this.includeKnownUserLocations = includeKnownUserLocations;
    }

    public void setScanRoots(List<String> scanRoots) {
        this.scanRoots = scanRoots == null ? new ArrayList<>() : scanRoots;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getMaxProcesses() {
        return maxProcesses;
    }

    public void setMaxProcesses(int maxProcesses) {
        this.maxProcesses = maxProcesses;
    }

    public String getIngestionToken() {
        return ingestionToken;
    }

    public void setIngestionToken(String ingestionToken) {
        this.ingestionToken = ingestionToken == null ? "" : ingestionToken;
    }

    public List<String> effectiveScanRoots() {
        Set<String> roots = new LinkedHashSet<>();
        roots.add(System.getProperty("user.dir"));
        if (scanRoots != null) {
            scanRoots.stream().filter(value -> value != null && !value.isBlank()).forEach(roots::add);
        }
        if (includeKnownUserLocations) {
            addKnownUserLocations(roots);
        }
        return roots.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private void addKnownUserLocations(Set<String> roots) {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return;
        }
        for (String relativePath : List.of(
                ".codex",
                ".claude",
                ".cursor",
                ".gemini",
                ".windsurf",
                ".kiro",
                ".opencode",
                ".amp",
                ".antigravity",
                ".openclaw",
                ".hermes",
                ".agents",
                ".config/Claude",
                ".config/Cursor",
                ".config/Code/User",
                ".config/opencode",
                ".config/agents",
                ".aws/amazonq",
                "Library/Application Support/Claude",
                "Library/Application Support/Cursor/User",
                "Library/Application Support/Code/User",
                "Library/Application Support/Windsurf/User",
                "Library/Application Support/Kiro/User"
        )) {
            roots.add(java.nio.file.Path.of(userHome, relativePath).toString());
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            roots.add(java.nio.file.Path.of(appData, "Claude").toString());
            roots.add(java.nio.file.Path.of(appData, "Cursor", "User").toString());
            roots.add(java.nio.file.Path.of(appData, "Code", "User").toString());
            roots.add(java.nio.file.Path.of(appData, "Windsurf", "User").toString());
            roots.add(java.nio.file.Path.of(appData, "Kiro", "User").toString());
        }
    }
}
