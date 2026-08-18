package com.eap.platform.discovery;

import java.util.List;

/**
 * Canonical product signatures shared by EAP's compatibility scanner and the
 * independently deployed Discovery Agent through the machine policy response.
 */
public final class AgentSignatureCatalog {
    private AgentSignatureCatalog() {
    }

    public static final List<AgentSignatureRule> RULES = List.of(
            rule("Codex", "OpenAI", null,
                    List.of("codex.app", "/codex", "\\codex.exe", "@openai/codex"),
                    List.of("@openai/codex", ".codex/config.toml"), List.of("@openai/codex"),
                    List.of(".codex"), List.of()),
            rule("Claude Code", "Anthropic", null,
                    List.of("claude.app", "/claude", "\\claude.exe", "@anthropic-ai/claude-code"),
                    List.of("@anthropic-ai/claude-code", "claude_desktop_config"), List.of("@anthropic-ai/claude-code"),
                    List.of(".claude"), List.of()),
            rule("Cursor", "Cursor", null, List.of("cursor.app", "/cursor", "\\cursor.exe"),
                    List.of(".cursor/mcp.json", "cursor.agent"), List.of(), List.of(".cursor"), List.of()),
            rule("Windsurf", "Codeium", null, List.of("windsurf.app", "/windsurf", "\\windsurf.exe"),
                    List.of(".windsurf/mcp.json", "codeium.windsurf"), List.of(), List.of(".windsurf"), List.of()),
            rule("Kiro", "Amazon Web Services", null, List.of("kiro.app", "/kiro", "\\kiro.exe"),
                    List.of(".kiro/settings/mcp.json", "kiro-agent"), List.of(), List.of(".kiro"), List.of()),
            rule("OpenCode", "Anomaly", null, List.of("/opencode", "\\opencode.exe", "opencode-ai"),
                    List.of("opencode.json", "opencode-ai"), List.of("opencode-ai"), List.of(".opencode"), List.of()),
            rule("Amp", "Sourcegraph", null, List.of("@sourcegraph/amp", "/amp.app/", "\\amp.exe"),
                    List.of("@sourcegraph/amp"), List.of("@sourcegraph/amp"), List.of(".amp"), List.of()),
            rule("Amazon Q Developer", "Amazon Web Services", null, List.of("amazon q.app", "amazonq", "aws q"),
                    List.of("aws.amazonq", "amazon q developer"), List.of(), List.of(".aws/amazonq"), List.of()),
            rule("Antigravity", "Google", null, List.of("antigravity.app", "/antigravity", "\\antigravity.exe"),
                    List.of(".antigravity/", "antigravity.agent"), List.of(), List.of(".antigravity"), List.of()),
            rule("OpenClaw", "OpenClaw Foundation", "OpenClaw",
                    List.of("/openclaw", "\\openclaw.exe", "openclaw gateway"),
                    List.of(".openclaw/", "openclaw.json", "openclaw.plugin.json"), List.of("openclaw"),
                    List.of(".openclaw"), List.of("openclaw.json", "openclaw.plugin.json")),
            rule("Hermes Agent", "Nous Research", "Hermes Agent",
                    List.of("/hermes", "\\hermes.exe", "hermes gateway", "hermes-agent"),
                    List.of(".hermes/", "hermes-agent", "gateway.pid"), List.of("hermes-agent"),
                    List.of(".hermes"), List.of("config.yaml", "gateway.pid", "plugin.yaml")),
            rule("Cline", "Cline", null, List.of("saoudrizwan.claude-dev", "cline-agent"),
                    List.of("saoudrizwan.claude-dev", "cline_mcp_settings"), List.of(), List.of(), List.of()),
            rule("Roo Code", "Roo Veterinary", null, List.of("rooveterinaryinc.roo-cline", "roo-code"),
                    List.of("rooveterinaryinc.roo-cline", "roo-cline"), List.of(), List.of(), List.of()),
            rule("Gemini CLI", "Google", null, List.of("/gemini", "\\gemini.exe", "@google/gemini-cli"),
                    List.of("@google/gemini-cli"), List.of("@google/gemini-cli"), List.of(".gemini"), List.of()),
            rule("GitHub Copilot", "GitHub", null, List.of("github.copilot", "copilot-agent"),
                    List.of("github.copilot", "@github/copilot"), List.of("@github/copilot"), List.of(), List.of()),
            rule("Mastra", "Mastra", "Mastra", List.of("mastra dev", "mastra start"),
                    List.of("@mastra/core"), List.of("@mastra/core"), List.of(), List.of()),
            rule("LangGraph", "LangChain", "LangGraph", List.of("langgraph"),
                    List.of("langgraph", "langchain"), List.of("langgraph", "langchain"), List.of(), List.of()),
            rule("CrewAI", "CrewAI", "CrewAI", List.of("crewai"), List.of("crewai"), List.of("crewai"), List.of(), List.of()),
            rule("AutoGen", "Microsoft", "AutoGen", List.of("autogen"),
                    List.of("pyautogen", "autogen-agentchat"), List.of("pyautogen", "autogen-agentchat"), List.of(), List.of()),
            rule("Semantic Kernel", "Microsoft", "Semantic Kernel", List.of("semantic-kernel", "semantic_kernel"),
                    List.of("semantic-kernel", "microsoft.semantickernel"), List.of("semantic-kernel", "Microsoft.SemanticKernel"), List.of(), List.of()),
            rule("OpenAI Agents SDK", "OpenAI", "OpenAI Agents SDK", List.of("openai-agents", "agents.sdk"),
                    List.of("openai-agents"), List.of("openai-agents"), List.of(), List.of()),
            rule("Dify Agent", "Dify", "Dify", List.of("dify"), List.of("dify"), List.of(), List.of(), List.of()),
            rule("Flowise Agent", "Flowise", "Flowise", List.of("flowise"), List.of("flowise"), List.of(), List.of(), List.of()),
            rule("Langflow Agent", "Langflow", "Langflow", List.of("langflow"), List.of("langflow"), List.of(), List.of(), List.of()),
            rule("Agent Skills", "Open Agent Standard", null, List.of(), List.of(), List.of(), List.of(".agents"), List.of("SKILL.md"))
    );

    private static AgentSignatureRule rule(
            String product,
            String vendor,
            String framework,
            List<String> processMarkers,
            List<String> contentMarkers,
            List<String> packageNames,
            List<String> homeDirectories,
            List<String> manifestNames
    ) {
        return new AgentSignatureRule(product, vendor, framework, processMarkers, contentMarkers,
                packageNames, homeDirectories, manifestNames);
    }

    public record AgentSignatureRule(
            String product,
            String vendor,
            String framework,
            List<String> processMarkers,
            List<String> contentMarkers,
            List<String> packageNames,
            List<String> homeDirectories,
            List<String> manifestNames
    ) {
    }
}
