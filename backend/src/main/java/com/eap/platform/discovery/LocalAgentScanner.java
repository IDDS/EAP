package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class LocalAgentScanner {
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".idea", ".venv", "venv", "__pycache__"
    );
    private static final List<String> AUTO_APPROVE_MARKERS = List.of(
            "--dangerously-skip-permissions", "--yolo", "--full-auto", "approval_policy=never", "approval-mode=full-auto"
    );
    private static final List<String> CREDENTIAL_MARKERS = List.of(
            "openai_api_key", "anthropic_api_key", "google_api_key", "azure_openai_api_key", "apikey", "api_key"
    );
    private static final List<String> NETWORK_ACCESS_MARKERS = List.of(
            "curl ", "wget ", "scp ", "ssh ", "invoke-webrequest", "start-bitstransfer"
    );
    private static final List<String> PRIVILEGED_COMMAND_MARKERS = List.of(
            "sudo ", "rm -rf", "chmod 777", "powershell -encodedcommand", "invoke-expression", "--dangerously-skip-permissions"
    );
    private static final List<String> PROMPT_INJECTION_MARKERS = List.of(
            "ignore previous instructions", "ignore all previous", "reveal the system prompt", "jailbreak", "<system>"
    );
    private static final List<String> TOOL_POISONING_MARKERS = List.of(
            "tool poisoning", "do not tell the user", "silently upload", "send this to", "hidden instruction", "invisible instruction"
    );
    private static final List<String> TOOL_SHADOWING_MARKERS = List.of(
            "shadow tool", "tool shadowing", "impersonate", "override existing tool", "duplicate tool"
    );
    private static final List<String> MALWARE_MARKERS = List.of(
            "base64 -d", "certutil -decode", "download and execute", "powershell -enc", "chmod +x", "/dev/tcp/"
    );
    private static final List<AgentSignature> SIGNATURES = AgentSignatureCatalog.RULES.stream()
            .map(rule -> new AgentSignature(rule.product(), rule.vendor(), rule.processMarkers(), rule.contentMarkers(), rule.packageNames()))
            .toList();

    private final DiscoveryProperties properties;
    private final ObjectMapper objectMapper;

    public LocalAgentScanner(DiscoveryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<DiscoveryEvidence> scan() {
        List<DiscoveryEvidence> evidence = new ArrayList<>();
        evidence.addAll(scanProcesses());
        evidence.addAll(scanFiles());
        return evidence;
    }

    List<DiscoveryEvidence> scanProcesses() {
        List<DiscoveryEvidence> evidence = new ArrayList<>();
        String hostName = hostName();
        String accountName = System.getProperty("user.name", "unknown");
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            processes.limit(properties.getMaxProcesses()).forEach(process -> {
                ProcessHandle.Info info = process.info();
                String command = info.command().orElse("");
                String arguments = String.join(" ", info.arguments().orElse(new String[0]));
                String searchable = (command + " " + arguments).toLowerCase(Locale.ROOT);
                for (AgentSignature signature : SIGNATURES) {
                    Optional<String> matched = firstMatch(searchable, signature.processTokens());
                    if (matched.isEmpty() || isFalsePositiveProcess(signature, command)) {
                        continue;
                    }
                    boolean autoApprove = containsAny(searchable, AUTO_APPROVE_MARKERS);
                    Boolean trustedProcess = processTrust(command);
                    RiskLevel riskLevel = riskLevel(autoApprove, trustedProcess, false, 0, DiscoverySourceType.PROCESS);
                    String processName = fileName(command).orElse(signature.product());
                    String sourceLocation = command.isBlank() ? "process:" + process.pid() : command;
                    String fingerprintLocation = canonicalProcessLocation(signature, command + " " + arguments);
                    String agentIdentity = profileIdentity(command + " " + arguments, hostName);
                    if (agentIdentity != null) fingerprintLocation = agentIdentity;
                    String fingerprint = fingerprint(signature.vendor(), signature.product(), hostName, accountName, fingerprintLocation);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("pid", process.pid());
                    details.put("matchedSignature", matched.get());
                    details.put("autoApproveFlagDetected", autoApprove);
                    if (agentIdentity != null) details.put("agentIdentity", agentIdentity);
                    evidence.add(new DiscoveryEvidence(
                            fingerprint,
                            signature.product() + " @ " + hostName,
                            signature.vendor(),
                            signature.product(),
                            null,
                            DiscoveryAssetType.AGENT,
                            DiscoverySourceType.PROCESS,
                            sourceLocation,
                            hostName,
                            accountName,
                            processName,
                            blankToNull(command),
                            autoApprove,
                            trustedProcess,
                            0,
                            92,
                            riskLevel,
                            "RUNNING_PROCESS",
                            details
                    ));
                    break;
                }
            });
        }
        return evidence;
    }

    List<DiscoveryEvidence> scanFiles() {
        List<DiscoveryEvidence> evidence = new ArrayList<>();
        for (String configuredRoot : properties.effectiveScanRoots()) {
            Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
            if (!Files.isDirectory(root) || !Files.isReadable(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root, properties.getMaxDepth())) {
                paths.filter(Files::isRegularFile)
                        .filter(this::isEligibleFile)
                        .limit(properties.getMaxFiles())
                        .forEach(path -> inspectFile(path, evidence));
            } catch (IOException | RuntimeException ignored) {
                // One inaccessible scan root must not stop discovery from other configured roots.
            }
        }
        return evidence;
    }

    private void inspectFile(Path path, List<DiscoveryEvidence> target) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > properties.getMaxFileBytes()) {
                return;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String normalizedContent = content.toLowerCase(Locale.ROOT);
            String normalizedPath = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (isSkillManifest(path, normalizedPath) || isPluginManifest(path, normalizedPath)) {
                inspectExtensionManifest(path, content, normalizedContent, normalizedPath, target);
                return;
            }
            boolean mcpConfig = normalizedContent.contains("\"mcpservers\"") || normalizedContent.contains("mcp_servers");
            int mcpServerCount = mcpConfig ? countMcpServers(content) : 0;

            List<AgentSignature> matches = SIGNATURES.stream()
                    .filter(signature -> firstMatch(normalizedContent, signature.contentTokens()).isPresent())
                    .toList();
            if (matches.isEmpty()) {
                matches = signatureForPath(normalizedPath, path.getFileName().toString()).map(List::of).orElse(List.of());
            }
            if (matches.isEmpty() && mcpConfig) {
                matches = List.of(new AgentSignature("MCP-enabled Agent Host", "Unknown", List.of(), List.of("mcpservers"), List.of()));
            }
            for (AgentSignature signature : matches) {
                String matchedToken = firstMatch(normalizedContent, signature.contentTokens())
                        .or(() -> signatureForPath(normalizedPath, path.getFileName().toString()).map(value -> value.product().toLowerCase(Locale.ROOT) + " configuration"))
                        .orElse("mcpServers");
            boolean autoApprove = containsAny(normalizedContent, AUTO_APPROVE_MARKERS);
            boolean credentialReference = containsAny(normalizedContent, CREDENTIAL_MARKERS);
            SecuritySignals securitySignals = securitySignals(normalizedContent);
            DiscoverySourceType sourceType = isConfigurationPath(normalizedPath) ? DiscoverySourceType.CONFIGURATION : DiscoverySourceType.MANIFEST;
                DiscoveryAssetType assetType = signature.product().equals("MCP-enabled Agent Host")
                        ? DiscoveryAssetType.MCP_CONFIGURATION
                        : sourceType == DiscoverySourceType.MANIFEST ? DiscoveryAssetType.AGENT_PROJECT : DiscoveryAssetType.AGENT;
                RiskLevel risk = riskLevel(autoApprove, null, credentialReference, mcpServerCount, sourceType, securitySignals.hasHighRiskSignal());
                String hostName = hostName();
                String accountName = System.getProperty("user.name", "unknown");
                String sourceLocation = path.toAbsolutePath().normalize().toString();
                String installationLocation = path.getParent() == null ? sourceLocation : path.getParent().toString();
                String version = extractVersion(path, content, signature);
                int confidence = sourceType == DiscoverySourceType.CONFIGURATION ? 84 : 76;
                if (!signature.packageNames().isEmpty() && version != null) {
                    confidence = 88;
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("matchedSignature", matchedToken);
                details.put("fileName", path.getFileName().toString());
                details.put("mcpServerCount", mcpServerCount);
                details.put("credentialReferenceDetected", credentialReference);
                details.put("autoApproveFlagDetected", autoApprove);
                details.put("promptInjectionDetected", securitySignals.promptInjection());
                details.put("toolPoisoningDetected", securitySignals.toolPoisoning());
                details.put("toolShadowingDetected", securitySignals.toolShadowing());
                details.put("malwarePayloadDetected", securitySignals.malwarePayload());
                details.put("toxicFlowDetected", securitySignals.toxicFlow(credentialReference));
                String agentIdentity = profileIdentity(normalizedPath, hostName);
                if (agentIdentity != null) details.put("agentIdentity", agentIdentity);
                target.add(new DiscoveryEvidence(
                        fingerprint(signature.vendor(), signature.product(), hostName, accountName, installationLocation),
                        signature.product() + " project · " + displayDirectory(path),
                        signature.vendor(),
                        signature.product(),
                        version,
                        assetType,
                        sourceType,
                        sourceLocation,
                        hostName,
                        accountName,
                        null,
                        null,
                        autoApprove,
                        null,
                        mcpServerCount,
                        confidence,
                        risk,
                        mcpConfig ? "AGENT_OR_MCP_CONFIGURATION" : "AGENT_FRAMEWORK_MANIFEST",
                        details
                ));
            }
        } catch (IOException | RuntimeException ignored) {
            // Discovery is best effort; inaccessible or malformed files are skipped.
        }
    }

    private boolean isEligibleFile(Path path) {
        for (Path part : path) {
            if (EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String normalizedPath = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (isSkillManifest(path, normalizedPath) || isPluginManifest(path, normalizedPath)) {
            return true;
        }
        if (Set.of("package.json", "pyproject.toml", "requirements.txt", "pom.xml", "mcp.json", ".mcp.json", "claude_desktop_config.json").contains(fileName)) {
            return true;
        }
        return (normalizedPath.contains("/.codex/") || normalizedPath.contains("/.claude/") || normalizedPath.contains("/.cursor/")
                || normalizedPath.contains("/.windsurf/") || normalizedPath.contains("/.kiro/") || normalizedPath.contains("/.opencode/")
                || normalizedPath.contains("/.gemini/") || normalizedPath.contains("/.antigravity/") || normalizedPath.contains("/.openclaw/")
                || normalizedPath.contains("/.hermes/")
                || normalizedPath.contains("/.amp/") || normalizedPath.contains("/.aws/amazonq/") || isKnownExtensionStoragePath(normalizedPath)
                || normalizedPath.contains("/mcp/"))
                && (fileName.endsWith(".json") || fileName.endsWith(".toml") || fileName.endsWith(".yaml") || fileName.endsWith(".yml"));
    }

    private boolean isConfigurationPath(String normalizedPath) {
        return normalizedPath.contains("mcp") || normalizedPath.contains("/.codex/") || normalizedPath.contains("/.claude/")
                || normalizedPath.contains("/.cursor/") || normalizedPath.contains("/.windsurf/") || normalizedPath.contains("/.kiro/")
                || normalizedPath.contains("/.opencode/") || normalizedPath.contains("/.gemini/") || normalizedPath.contains("/.antigravity/")
                || normalizedPath.contains("/.openclaw/") || normalizedPath.contains("/.hermes/")
                || normalizedPath.contains("/.amp/") || normalizedPath.contains("/.aws/amazonq/")
                || isKnownExtensionStoragePath(normalizedPath);
    }

    private Optional<AgentSignature> signatureForPath(String normalizedPath, String fileName) {
        String normalizedFileName = fileName.toLowerCase(Locale.ROOT);
        if (normalizedFileName.equals("claude_desktop_config.json")
                || (normalizedPath.contains("/.claude/") && Set.of("settings.json", "settings.local.json", "mcp.json").contains(normalizedFileName))) {
            return Optional.of(SIGNATURES.get(1));
        }
        if (normalizedPath.contains("/.codex/") && Set.of("config.toml", "mcp.json", "config.json").contains(normalizedFileName)) {
            return Optional.of(SIGNATURES.get(0));
        }
        if (normalizedPath.contains("/.cursor/") && Set.of("mcp.json", "settings.json").contains(normalizedFileName)) {
            return Optional.of(SIGNATURES.get(2));
        }
        if (normalizedPath.contains("/.windsurf/")) {
            return signatureByProduct("Windsurf");
        }
        if (normalizedPath.contains("/.kiro/")) {
            return signatureByProduct("Kiro");
        }
        if (normalizedPath.contains("/.opencode/") || normalizedFileName.equals("opencode.json")) {
            return signatureByProduct("OpenCode");
        }
        if (normalizedPath.contains("/.gemini/")) {
            return signatureByProduct("Gemini CLI");
        }
        if (normalizedPath.contains("/.antigravity/")) {
            return signatureByProduct("Antigravity");
        }
        if (normalizedPath.contains("/.openclaw/")) {
            return signatureByProduct("OpenClaw");
        }
        if (normalizedPath.contains("/.hermes/")) {
            return signatureByProduct("Hermes Agent");
        }
        if (normalizedPath.contains("/.amp/")) {
            return signatureByProduct("Amp");
        }
        if (normalizedPath.contains("/.agents/") || normalizedPath.contains("/.config/agents/")) {
            return signatureByProduct("Agent Skills");
        }
        if (normalizedPath.contains("/.aws/amazonq/") || normalizedPath.contains("/amazon q/")) {
            return signatureByProduct("Amazon Q Developer");
        }
        if (normalizedPath.contains("/saoudrizwan.claude-dev/")) {
            return signatureByProduct("Cline");
        }
        if (normalizedPath.contains("/rooveterinaryinc.roo-cline/")) {
            return signatureByProduct("Roo Code");
        }
        return Optional.empty();
    }

    private void inspectExtensionManifest(
            Path path,
            String content,
            String normalizedContent,
            String normalizedPath,
            List<DiscoveryEvidence> target
    ) {
        boolean plugin = isPluginManifest(path, normalizedPath);
        AgentSignature host = extensionHostForPath(normalizedPath).orElseGet(() ->
                new AgentSignature("Agent Extension Host", "Unknown", List.of(), List.of(), List.of()));
        String extensionName = plugin
                ? Optional.ofNullable(extractPluginField(content, "name")).orElseGet(() -> displayPluginDirectory(path))
                : extractSkillName(path, content);
        String version = plugin ? extractPluginField(content, "version") : null;
        boolean autoApprove = containsAny(normalizedContent, AUTO_APPROVE_MARKERS);
        boolean credentialReference = containsAny(normalizedContent, CREDENTIAL_MARKERS);
        boolean networkAccess = containsAny(normalizedContent, NETWORK_ACCESS_MARKERS);
        boolean privilegedCommand = containsAny(normalizedContent, PRIVILEGED_COMMAND_MARKERS);
        SecuritySignals securitySignals = securitySignals(normalizedContent);
        RiskLevel risk = extensionRiskLevel(autoApprove, credentialReference, networkAccess, privilegedCommand, securitySignals);
        String hostName = hostName();
        String accountName = System.getProperty("user.name", "unknown");
        String sourceLocation = path.toAbsolutePath().normalize().toString();
        DiscoveryAssetType assetType = plugin ? DiscoveryAssetType.AGENT_PLUGIN : DiscoveryAssetType.AGENT_SKILL;
        DiscoverySourceType sourceType = plugin ? DiscoverySourceType.PLUGIN : DiscoverySourceType.SKILL;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fileName", path.getFileName().toString());
        details.put("hostProduct", host.product());
        details.put("credentialReferenceDetected", credentialReference);
        details.put("networkAccessDetected", networkAccess);
        details.put("privilegedCommandDetected", privilegedCommand);
        details.put("autoApproveFlagDetected", autoApprove);
        details.put("promptInjectionDetected", securitySignals.promptInjection());
        details.put("malwarePayloadDetected", securitySignals.malwarePayload());
        details.put("toxicFlowDetected", securitySignals.toxicFlow(credentialReference));
        target.add(new DiscoveryEvidence(
                fingerprint("extension", host.vendor(), host.product(), hostName, accountName, sourceLocation),
                extensionName + " · " + host.product(),
                host.vendor(),
                host.product(),
                version,
                assetType,
                sourceType,
                sourceLocation,
                hostName,
                accountName,
                null,
                null,
                autoApprove,
                null,
                0,
                host.product().equals("Agent Extension Host") ? 78 : 90,
                risk,
                plugin ? "AGENT_PLUGIN_MANIFEST" : "AGENT_SKILL_MANIFEST",
                details
        ));
    }

    private boolean isSkillManifest(Path path, String normalizedPath) {
        return path.getFileName().toString().equalsIgnoreCase("SKILL.md") && normalizedPath.contains("/skills/");
    }

    private boolean isPluginManifest(Path path, String normalizedPath) {
        String fileName = path.getFileName().toString();
        return (fileName.equalsIgnoreCase("plugin.json")
                && (normalizedPath.contains("/.codex-plugin/") || normalizedPath.contains("/.claude-plugin/")))
                || (fileName.equalsIgnoreCase("openclaw.plugin.json") && normalizedPath.contains("/.openclaw/"))
                || (fileName.equalsIgnoreCase("plugin.yaml") && normalizedPath.contains("/.hermes/plugins/"));
    }

    private boolean isKnownExtensionStoragePath(String normalizedPath) {
        return normalizedPath.contains("/globalstorage/saoudrizwan.claude-dev/")
                || normalizedPath.contains("/globalstorage/rooveterinaryinc.roo-cline/")
                || normalizedPath.contains("/globalstorage/github.copilot/")
                || normalizedPath.contains("/globalstorage/amazonwebservices.amazon-q-vscode/");
    }

    private Optional<AgentSignature> extensionHostForPath(String normalizedPath) {
        if (normalizedPath.contains("/.codex/") || normalizedPath.contains("/.codex-plugin/")) {
            return signatureByProduct("Codex");
        }
        if (normalizedPath.contains("/.claude/") || normalizedPath.contains("/.claude-plugin/")) {
            return signatureByProduct("Claude Code");
        }
        if (normalizedPath.contains("/.cursor/")) {
            return signatureByProduct("Cursor");
        }
        if (normalizedPath.contains("/.windsurf/")) {
            return signatureByProduct("Windsurf");
        }
        if (normalizedPath.contains("/.kiro/")) {
            return signatureByProduct("Kiro");
        }
        if (normalizedPath.contains("/.opencode/")) {
            return signatureByProduct("OpenCode");
        }
        if (normalizedPath.contains("/.gemini/")) {
            return signatureByProduct("Gemini CLI");
        }
        if (normalizedPath.contains("/.antigravity/")) {
            return signatureByProduct("Antigravity");
        }
        if (normalizedPath.contains("/.openclaw/")) {
            return signatureByProduct("OpenClaw");
        }
        if (normalizedPath.contains("/.hermes/")) {
            return signatureByProduct("Hermes Agent");
        }
        if (normalizedPath.contains("/.amp/")) {
            return signatureByProduct("Amp");
        }
        if (normalizedPath.contains("/.agents/") || normalizedPath.contains("/.config/agents/")) {
            return signatureByProduct("Agent Skills");
        }
        if (normalizedPath.contains("/saoudrizwan.claude-dev/")) {
            return signatureByProduct("Cline");
        }
        if (normalizedPath.contains("/rooveterinaryinc.roo-cline/")) {
            return signatureByProduct("Roo Code");
        }
        return Optional.empty();
    }

    private Optional<AgentSignature> signatureByProduct(String product) {
        return SIGNATURES.stream().filter(signature -> signature.product().equals(product)).findFirst();
    }

    private String extractSkillName(Path path, String content) {
        boolean frontmatter = false;
        for (String line : content.lines().limit(80).toList()) {
            String trimmed = line.trim();
            if (trimmed.equals("---")) {
                if (frontmatter) {
                    break;
                }
                frontmatter = true;
                continue;
            }
            if (frontmatter && trimmed.toLowerCase(Locale.ROOT).startsWith("name:")) {
                String value = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                value = value.replaceAll("^[\\\"']|[\\\"']$", "");
                if (!value.isBlank()) {
                    return truncate(value, 128);
                }
            }
        }
        return displayDirectory(path);
    }

    private String extractPluginField(String content, String field) {
        try {
            JsonNode value = objectMapper.readTree(content).get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return truncate(value.asText(), field.equals("version") ? 64 : 128);
            }
        } catch (IOException ignored) {
            // Malformed plugin manifests still produce a path-based observation.
        }
        return null;
    }

    private String displayPluginDirectory(Path path) {
        Path manifestDirectory = path.getParent();
        if (manifestDirectory != null && manifestDirectory.getFileName() != null
                && !Set.of(".codex-plugin", ".claude-plugin").contains(
                manifestDirectory.getFileName().toString().toLowerCase(Locale.ROOT))) {
            return manifestDirectory.getFileName().toString();
        }
        Path pluginDirectory = manifestDirectory == null ? null : manifestDirectory.getParent();
        if (pluginDirectory != null && pluginDirectory.getFileName() != null) {
            return pluginDirectory.getFileName().toString();
        }
        return "Unnamed plugin";
    }

    private RiskLevel extensionRiskLevel(boolean autoApprove, boolean credentialReference, boolean networkAccess, boolean privilegedCommand, SecuritySignals signals) {
        if (autoApprove && (privilegedCommand || signals.malwarePayload())) {
            return RiskLevel.CRITICAL;
        }
        if (autoApprove || privilegedCommand || credentialReference || signals.hasHighRiskSignal()) {
            return RiskLevel.HIGH;
        }
        if (networkAccess) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private String extractVersion(Path path, String content, AgentSignature signature) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if ("package.json".equals(fileName)) {
            try {
                JsonNode root = objectMapper.readTree(content);
                for (String section : List.of("dependencies", "devDependencies", "peerDependencies")) {
                    JsonNode dependencies = root.path(section);
                    for (String packageName : signature.packageNames()) {
                        JsonNode version = dependencies.get(packageName);
                        if (version != null && version.isTextual()) {
                            return truncate(version.asText(), 64);
                        }
                    }
                }
            } catch (IOException ignored) {
                return null;
            }
        }
        for (String packageName : signature.packageNames()) {
            for (String line : content.lines().toList()) {
                String normalizedLine = line.trim().toLowerCase(Locale.ROOT);
                String normalizedPackage = packageName.toLowerCase(Locale.ROOT);
                if (normalizedLine.startsWith(normalizedPackage + "==") || normalizedLine.startsWith(normalizedPackage + ">=") || normalizedLine.startsWith(normalizedPackage + "~=")) {
                    return truncate(line.trim().substring(packageName.length()), 64);
                }
            }
        }
        return null;
    }

    private int countMcpServers(String content) {
        try {
            return countMcpServers(objectMapper.readTree(content));
        } catch (IOException ignored) {
            return 0;
        }
    }

    private int countMcpServers(JsonNode node) {
        if (node == null) {
            return 0;
        }
        if (node.isObject()) {
            JsonNode servers = node.get("mcpServers");
            int count = servers != null && servers.isObject() ? servers.size() : 0;
            var fields = node.fields();
            while (fields.hasNext()) {
                count += countMcpServers(fields.next().getValue());
            }
            return count;
        }
        if (node.isArray()) {
            int count = 0;
            for (JsonNode child : node) {
                count += countMcpServers(child);
            }
            return count;
        }
        return 0;
    }

    private RiskLevel riskLevel(boolean autoApprove, Boolean trustedProcess, boolean credentialReference, int mcpServerCount, DiscoverySourceType sourceType) {
        return riskLevel(autoApprove, trustedProcess, credentialReference, mcpServerCount, sourceType, false);
    }

    private RiskLevel riskLevel(boolean autoApprove, Boolean trustedProcess, boolean credentialReference, int mcpServerCount, DiscoverySourceType sourceType, boolean securityFinding) {
        if (autoApprove && Boolean.FALSE.equals(trustedProcess)) {
            return RiskLevel.CRITICAL;
        }
        if (autoApprove || Boolean.FALSE.equals(trustedProcess) || credentialReference || securityFinding) {
            return RiskLevel.HIGH;
        }
        if (sourceType == DiscoverySourceType.PROCESS || mcpServerCount > 0) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private Boolean processTrust(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/tmp/") || normalized.contains("/downloads/") || normalized.startsWith("./")) {
            return false;
        }
        if (normalized.startsWith("/applications/") || normalized.startsWith("/system/") || normalized.startsWith("/usr/")
                || normalized.startsWith("/opt/homebrew/") || normalized.startsWith("/library/") || normalized.contains("/program files/")) {
            return true;
        }
        return null;
    }

    private boolean isFalsePositiveProcess(AgentSignature signature, String command) {
        String normalized = command == null ? "" : command.toLowerCase(Locale.ROOT);
        return signature.product().equals("Cursor")
                && (normalized.contains("cursoruiviewservice") || normalized.contains("textinput") || normalized.contains("helper"));
    }

    private String canonicalProcessLocation(AgentSignature signature, String command) {
        if (command == null || command.isBlank()) {
            return signature.product();
        }
        String normalized = command.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        List<String> markers = switch (signature.product()) {
            case "Codex" -> List.of("/applications/codex.app", "/@openai/codex");
            case "Claude Code" -> List.of("/applications/claude.app", "/@anthropic-ai/claude-code");
            case "Cursor" -> List.of("/applications/cursor.app");
            default -> List.of();
        };
        for (String marker : markers) {
            int index = lower.indexOf(marker);
            if (index >= 0) {
                return marker;
            }
        }
        return fileName(command).orElse(signature.product());
    }

    private String profileIdentity(String value, String hostName) {
        if (value == null) return null;
        String normalized = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> candidate : Map.of(
                "/.hermes/profiles/", "hermes",
                "/.openclaw/agents/", "openclaw",
                "/.openclaw/workspaces/", "openclaw"
        ).entrySet()) {
            int index = normalized.indexOf(candidate.getKey());
            if (index < 0) continue;
            String remainder = normalized.substring(index + candidate.getKey().length());
            String profile = remainder.split("[/\\s]", 2)[0].trim();
            if (!profile.isBlank()) return "profile:" + hostName + ":" + candidate.getValue() + ":" + profile;
        }
        if (normalized.contains("/.hermes/")) return "profile:" + hostName + ":hermes-default";
        if (normalized.contains("/.openclaw/")) return "profile:" + hostName + ":openclaw-default";
        return null;
    }

    private Optional<String> firstMatch(String value, List<String> markers) {
        return markers.stream().filter(marker -> value.contains(marker.toLowerCase(Locale.ROOT))).findFirst();
    }

    private boolean containsAny(String value, List<String> markers) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return markers.stream().anyMatch(marker -> normalized.contains(marker.toLowerCase(Locale.ROOT)));
    }

    private SecuritySignals securitySignals(String normalizedContent) {
        boolean promptInjection = containsAny(normalizedContent, PROMPT_INJECTION_MARKERS);
        boolean toolPoisoning = containsAny(normalizedContent, TOOL_POISONING_MARKERS);
        boolean toolShadowing = containsAny(normalizedContent, TOOL_SHADOWING_MARKERS);
        boolean malwarePayload = containsAny(normalizedContent, MALWARE_MARKERS);
        boolean networkAccess = containsAny(normalizedContent, NETWORK_ACCESS_MARKERS);
        return new SecuritySignals(promptInjection, toolPoisoning, toolShadowing, malwarePayload, networkAccess);
    }

    private String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = Arrays.stream(values).map(value -> value == null ? "" : value).reduce((left, right) -> left + "|" + right).orElse("");
            return java.util.HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return System.getenv().getOrDefault("HOSTNAME", "unknown-host");
        }
    }

    private Optional<String> fileName(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Path.of(command).getFileName()).map(Path::toString);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String displayDirectory(Path path) {
        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null) {
            return hostName();
        }
        return parent.getFileName().toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record SecuritySignals(
            boolean promptInjection,
            boolean toolPoisoning,
            boolean toolShadowing,
            boolean malwarePayload,
            boolean networkAccess
    ) {
        boolean hasHighRiskSignal() {
            return promptInjection || toolPoisoning || toolShadowing || malwarePayload;
        }

        boolean toxicFlow(boolean credentialReference) {
            return credentialReference && networkAccess;
        }
    }

    private record AgentSignature(String product, String vendor, List<String> processTokens, List<String> contentTokens, List<String> packageNames) {
    }
}
