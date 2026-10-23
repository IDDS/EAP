package discovery

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/matrix-eap/discovery-agent/internal/config"
	"github.com/matrix-eap/discovery-agent/internal/protocol"
)

type Signature struct {
	Product         string
	Vendor          string
	Framework       string
	Markers         []string
	ContentMarkers  []string
	PackageNames    []string
	HomeDirectories []string
	ManifestNames   []string
}

var signatures = []Signature{
	{Product: "HelloBoss", Vendor: "深圳矩维智能", Markers: []string{"boss-agent", "helloboss"}},
	{Product: "Codex", Vendor: "OpenAI", Markers: []string{"codex", "@openai/codex"}},
	{Product: "Claude Code", Vendor: "Anthropic", Markers: []string{"claude", "@anthropic-ai/claude-code"}},
	{Product: "Cursor", Vendor: "Cursor", Markers: []string{"cursor"}},
	{Product: "Gemini CLI", Vendor: "Google", Markers: []string{"@google/gemini-cli", "gemini"}},
	{Product: "OpenClaw", Vendor: "OpenClaw Foundation", Framework: "OpenClaw", Markers: []string{"openclaw", "openclaw gateway"}, ContentMarkers: []string{".openclaw/", "openclaw.json", "openclaw.plugin.json"}, PackageNames: []string{"openclaw"}, HomeDirectories: []string{".openclaw"}, ManifestNames: []string{"openclaw.json", "openclaw.plugin.json"}},
	{Product: "Hermes Agent", Vendor: "Nous Research", Framework: "Hermes Agent", Markers: []string{"hermes-agent", "hermes gateway", "/hermes", "\\hermes.exe"}, ContentMarkers: []string{".hermes/", "hermes-agent", "gateway.pid"}, PackageNames: []string{"hermes-agent"}, HomeDirectories: []string{".hermes"}, ManifestNames: []string{"config.yaml", "gateway.pid", "plugin.yaml"}},
	{Product: "LangGraph Agent", Vendor: "LangChain", Framework: "LangGraph", Markers: []string{"langgraph"}},
	{Product: "LangChain Agent", Vendor: "LangChain", Framework: "LangChain", Markers: []string{"langchain"}},
	{Product: "CrewAI Agent", Vendor: "CrewAI", Framework: "CrewAI", Markers: []string{"crewai"}},
	{Product: "AutoGen Agent", Vendor: "Microsoft", Framework: "AutoGen", Markers: []string{"autogen"}},
	{Product: "Semantic Kernel Agent", Vendor: "Microsoft", Framework: "Semantic Kernel", Markers: []string{"semantic-kernel", "semantic_kernel"}},
	{Product: "OpenAI Agents SDK", Vendor: "OpenAI", Framework: "OpenAI Agents SDK", Markers: []string{"openai-agents", "agents.sdk"}},
	{Product: "Dify Agent", Vendor: "Dify", Framework: "Dify", Markers: []string{"dify"}},
	{Product: "Flowise Agent", Vendor: "Flowise", Framework: "Flowise", Markers: []string{"flowise"}},
	{Product: "Langflow Agent", Vendor: "Langflow", Framework: "Langflow", Markers: []string{"langflow"}},
}

var modelMarkers = map[string]string{
	"openai": "OpenAI", "gpt-": "OpenAI GPT", "anthropic": "Anthropic", "claude": "Claude",
	"deepseek": "DeepSeek", "dashscope": "通义千问", "qwen": "通义千问", "moonshot": "Moonshot",
	"volcengine": "火山方舟", "doubao": "豆包", "ollama": "Ollama", "gemini": "Google Gemini",
}

var dockerInfrastructureServices = map[string]bool{
	"mysql": true, "db": true, "database": true, "redis": true,
	"postgres": true, "postgresql": true, "mongo": true, "mongodb": true,
	"frontend": true, "web": true, "nginx": true, "proxy": true,
	"prometheus": true, "grafana": true, "jaeger": true, "otel": true,
	"minio": true, "rabbitmq": true, "kafka": true, "zookeeper": true,
}

var dockerBackendServices = map[string]bool{
	"backend": true, "api": true, "agent": true, "runtime": true,
	"worker": true, "server": true, "app": true,
}

var ignoredProcessNames = map[string]bool{
	"bash": true, "sh": true, "dash": true, "zsh": true, "fish": true,
	"sudo": true, "su": true, "ssh": true, "sshd": true,
	"curl": true, "wget": true, "jq": true, "grep": true, "rg": true,
	"sed": true, "awk": true, "find": true, "timeout": true, "sleep": true,
	"docker": true, "systemctl": true, "journalctl": true,
}

type Result struct {
	Observations []protocol.Observation
	Warnings     []string
	StartedAt    time.Time
	FinishedAt   time.Time
}

func Collect(cfg config.ScanConfig, centralRules ...[]protocol.AgentSignature) Result {
	result := Result{StartedAt: time.Now()}
	catalogue := effectiveSignatures(centralRules...)
	var observations []protocol.Observation
	if cfg.Processes {
		items, warnings := processObservations(cfg.Ports, catalogue)
		observations = append(observations, items...)
		result.Warnings = append(result.Warnings, warnings...)
	}
	if cfg.Docker {
		items, warnings := dockerObservations(catalogue)
		observations = append(observations, items...)
		result.Warnings = append(result.Warnings, warnings...)
	}
	if cfg.Files {
		items, warnings := fileObservations(cfg, catalogue)
		observations = append(observations, items...)
		result.Warnings = append(result.Warnings, warnings...)
	}
	result.Observations = deduplicate(observations)
	result.FinishedAt = time.Now()
	return result
}

func HostMetadata() (host, account, osName, arch, fingerprint string) {
	host, _ = os.Hostname()
	current, _ := user.Current()
	if current != nil {
		account = current.Username
	}
	osName = runtime.GOOS + "/" + runtime.Version()
	arch = runtime.GOARCH
	machineID, _ := os.ReadFile("/etc/machine-id")
	fingerprint = digest(strings.TrimSpace(string(machineID)), host, runtime.GOOS, runtime.GOARCH)
	return
}

func processObservations(withPorts bool, catalogue []Signature) ([]protocol.Observation, []string) {
	if runtime.GOOS != "linux" {
		return nil, []string{"process detector currently provides full coverage on Linux only"}
	}
	host, account, _, _, _ := HostMetadata()
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, []string{err.Error()}
	}
	var observations []protocol.Observation
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || !entry.IsDir() {
			continue
		}
		comm := strings.TrimSpace(readText(filepath.Join("/proc", entry.Name(), "comm"), 256))
		if ignoredProcessNames[strings.ToLower(filepath.Base(comm))] {
			continue
		}
		cmdline := strings.ReplaceAll(readText(filepath.Join("/proc", entry.Name(), "cmdline"), 8192), "\x00", " ")
		executable, _ := os.Readlink(filepath.Join("/proc", entry.Name(), "exe"))
		searchable := strings.ToLower(strings.Join([]string{comm, cmdline, executable}, " "))
		signature, ok := matchSignature(searchable, catalogue)
		if !ok {
			if !looksGenericAgentProcess(searchable) {
				continue
			}
			signature = Signature{Product: "Unknown Agent", Vendor: "Unknown", Framework: "Unknown"}
		}
		ports := []string{}
		if withPorts {
			ports = listeningPorts(pid)
		}
		endpoints := make([]string, 0, len(ports))
		for _, port := range ports {
			endpoints = append(endpoints, "tcp://"+host+":"+port)
		}
		models := matchModels(searchable)
		agentIdentity := "process:" + host + ":" + firstNonBlank(executable, comm)
		fingerprintBasis := firstNonBlank(executable, comm)
		if profile := profileIdentity(searchable, host); profile != "" {
			agentIdentity = profile
			fingerprintBasis = profile
		}
		security := map[string]any{
			"autoApproveDetected": containsAny(searchable, "--dangerously-skip-permissions", "--yolo", "--full-auto"),
			"untrustedProcess":    strings.HasPrefix(executable, "/tmp/") || strings.Contains(executable, "/downloads/"),
			"agentIdentity":       agentIdentity,
		}
		observations = append(observations, protocol.Observation{
			Fingerprint: digest("process", signature.Product, host, fingerprintBasis),
			Name:        signature.Product + " @ " + host, Vendor: signature.Vendor, Product: signature.Product,
			AssetType: "AGENT", SourceType: "PROCESS", SourceLocation: fmt.Sprintf("process://%s/%d", host, pid),
			AccountName: processAccount(pid, account), ProcessName: comm, ExecutablePath: executable, Framework: signature.Framework,
			RuntimeStatus: "RUNNING", Ports: ports, Protocols: protocolsForPorts(ports), ServiceEndpoints: endpoints,
			Models: models, SecuritySignals: security, Confidence: map[bool]int{true: 92, false: 72}[ok], EvidenceType: "RUNNING_PROCESS",
		})
	}
	return observations, nil
}

type dockerInspect struct {
	ID     string `json:"Id"`
	Name   string `json:"Name"`
	Config struct {
		Image      string            `json:"Image"`
		User       string            `json:"User"`
		Entrypoint any               `json:"Entrypoint"`
		Cmd        any               `json:"Cmd"`
		Env        []string          `json:"Env"`
		Labels     map[string]string `json:"Labels"`
	} `json:"Config"`
	HostConfig struct {
		Privileged  bool   `json:"Privileged"`
		PidMode     string `json:"PidMode"`
		NetworkMode string `json:"NetworkMode"`
	} `json:"HostConfig"`
	Mounts          []struct{ Source, Destination string } `json:"Mounts"`
	NetworkSettings struct {
		Ports map[string][]struct {
			HostIP   string `json:"HostIp"`
			HostPort string `json:"HostPort"`
		} `json:"Ports"`
		Networks map[string]any `json:"Networks"`
	} `json:"NetworkSettings"`
	State struct {
		Status string `json:"Status"`
	} `json:"State"`
}

func dockerObservations(catalogue []Signature) ([]protocol.Observation, []string) {
	idsRaw, err := exec.Command("docker", "ps", "-q").Output()
	if err != nil {
		return nil, []string{"docker unavailable: " + err.Error()}
	}
	ids := strings.Fields(string(idsRaw))
	if len(ids) == 0 {
		return nil, nil
	}
	args := append([]string{"inspect"}, ids...)
	raw, err := exec.Command("docker", args...).Output()
	if err != nil {
		return nil, []string{"docker inspect failed: " + err.Error()}
	}
	var documents []dockerInspect
	if err := json.Unmarshal(raw, &documents); err != nil {
		return nil, []string{"docker inspect JSON invalid"}
	}
	host, _, _, _, _ := HostMetadata()
	var observations []protocol.Observation
	for _, document := range documents {
		labels := document.Config.Labels
		project, service := labels["com.docker.compose.project"], labels["com.docker.compose.service"]
		name := strings.TrimPrefix(document.Name, "/")
		if strings.EqualFold(project, "eap") || strings.HasPrefix(strings.ToLower(name), "eap-") {
			continue
		}
		explicit := truthy(labels["eap.agent"]) || truthy(labels["eap.discovery.agent"]) || truthy(labels["ai.agent"])
		if dockerInfrastructureServices[strings.ToLower(service)] && !explicit {
			continue
		}
		searchable := strings.ToLower(strings.Join([]string{name, document.Config.Image, service, toString(document.Config.Entrypoint), toString(document.Config.Cmd)}, " "))
		signature, matched := matchSignature(searchable, catalogue)
		aiKeys := environmentKeys(document.Config.Env)
		backendRole := dockerBackendServices[strings.ToLower(service)]
		if !matched && !explicit && !(len(aiKeys) > 0 && backendRole) {
			continue
		}
		if !matched {
			signature = Signature{Product: firstNonBlank(labels["eap.agent.product"], project, name, "Docker Agent"), Vendor: firstNonBlank(labels["eap.agent.vendor"], "Unknown")}
		}
		ports := dockerPorts(document)
		endpoints := dockerServiceEndpoints(document, host)
		dockerSocket := false
		for _, mount := range document.Mounts {
			if mount.Destination == "/var/run/docker.sock" {
				dockerSocket = true
			}
		}
		security := map[string]any{
			"privileged": document.HostConfig.Privileged, "hostPidMode": document.HostConfig.PidMode == "host",
			"hostNetworkMode": document.HostConfig.NetworkMode == "host", "dockerSocketMounted": dockerSocket,
			"llmCredentialEnvKeys": aiKeys,
			"agentIdentity":        "docker:" + host + ":" + firstNonBlank(project, name) + ":" + firstNonBlank(service, name),
		}
		models := matchModels(searchable + " " + strings.Join(aiKeys, " "))
		observations = append(observations, protocol.Observation{
			Fingerprint: digest("docker", signature.Product, host, project, firstNonBlank(service, name, document.Config.Image)),
			Name:        signature.Product + " @ " + host, Vendor: signature.Vendor, Product: signature.Product,
			Version: imageVersion(document.Config.Image), AssetType: "AGENT", SourceType: "DOCKER",
			SourceLocation: "docker://" + host + "/" + name, AccountName: firstNonBlank(document.Config.User, "root"), ProcessName: name,
			ExecutablePath: document.Config.Image, Framework: signature.Framework, RuntimeStatus: strings.ToUpper(document.State.Status),
			Ports: ports, Protocols: protocolsForPorts(ports), ServiceEndpoints: endpoints, Models: models,
			Relationships: dockerRelationships(project, service, document.NetworkSettings.Networks), SecuritySignals: security,
			Confidence: map[bool]int{true: 98, false: 90}[explicit || matched], EvidenceType: "DOCKER_AGENT_WORKLOAD",
		})
	}
	return observations, nil
}

func fileObservations(cfg config.ScanConfig, catalogue []Signature) ([]protocol.Observation, []string) {
	host, account, _, _, _ := HostMetadata()
	var observations []protocol.Observation
	seen := 0
	for _, configuredRoot := range effectiveRoots(cfg.Roots) {
		root := filepath.Clean(configuredRoot)
		rootDepth := strings.Count(root, string(os.PathSeparator))
		err := filepath.WalkDir(root, func(path string, entry os.DirEntry, walkErr error) error {
			if walkErr != nil {
				return nil
			}
			depth := strings.Count(path, string(os.PathSeparator)) - rootDepth
			if entry.IsDir() {
				if depth > cfg.MaxDepth || excludedDirectory(entry.Name()) {
					return filepath.SkipDir
				}
				return nil
			}
			if seen >= cfg.MaxFiles {
				return filepath.SkipAll
			}
			kind := manifestKind(path, catalogue)
			if kind == "" {
				return nil
			}
			seen++
			data, err := os.ReadFile(path)
			if err != nil || len(data) > 1024*1024 {
				return nil
			}
			text := strings.ToLower(string(data))
			signature, matched := matchSignature(text+" "+strings.ToLower(path), catalogue)
			if !matched && kind != "SKILL" && kind != "PLUGIN" && !strings.Contains(text, "mcpservers") && !strings.Contains(text, "mcp_servers") {
				return nil
			}
			if !matched {
				signature = Signature{Product: map[string]string{"SKILL": "Agent Skill", "PLUGIN": "Agent Plugin"}[kind], Vendor: "Unknown"}
			}
			assetType, sourceType := "AGENT_PROJECT", "MANIFEST"
			if kind == "SKILL" {
				assetType, sourceType = "AGENT_SKILL", "SKILL"
			}
			if kind == "PLUGIN" {
				assetType, sourceType = "AGENT_PLUGIN", "PLUGIN"
			}
			mcp := extractNames(string(data), []string{"mcpservers", "mcp_servers"})
			models := matchModels(text)
			security := map[string]any{
				"credentialReferenceDetected": containsAny(text, "api_key", "apikey", "access_token", "secret"),
				"promptInjectionDetected":     containsAny(text, "ignore previous instructions", "reveal the system prompt", "jailbreak"),
				"toolPoisoningDetected":       containsAny(text, "tool poisoning", "silently upload", "do not tell the user"),
				"autoApproveDetected":         containsAny(text, "dangerously-skip-permissions", "approval_policy=never", "--yolo"),
			}
			if profile := profileIdentity(strings.ToLower(path), host); profile != "" {
				security["agentIdentity"] = profile
			}
			observation := protocol.Observation{
				Fingerprint: digest("file", signature.Product, host, filepath.Dir(path)),
				Name:        signature.Product + " · " + filepath.Base(filepath.Dir(path)), Vendor: signature.Vendor, Product: signature.Product,
				AssetType: assetType, SourceType: sourceType, SourceLocation: redactPath(path), AccountName: account,
				Framework: signature.Framework, Models: models, MCPServers: mcp, SecuritySignals: security,
				Confidence: 82, EvidenceType: kind + "_MANIFEST",
			}
			if kind == "SKILL" {
				observation.Skills = []string{filepath.Base(filepath.Dir(path))}
			}
			if kind == "PLUGIN" {
				pluginDirectory := filepath.Dir(path)
				if filepath.Base(pluginDirectory) == ".codex-plugin" || filepath.Base(pluginDirectory) == ".claude-plugin" {
					pluginDirectory = filepath.Dir(pluginDirectory)
				}
				observation.Plugins = []string{filepath.Base(pluginDirectory)}
			}
			observations = append(observations, observation)
			return nil
		})
		if err != nil {
			return observations, []string{err.Error()}
		}
	}
	return observations, nil
}

func matchSignature(value string, catalogues ...[]Signature) (Signature, bool) {
	normalized := strings.ToLower(value)
	catalogue := signatures
	if len(catalogues) > 0 && len(catalogues[0]) > 0 {
		catalogue = catalogues[0]
	}
	for _, signature := range catalogue {
		markers := append(append([]string{}, signature.Markers...), signature.ContentMarkers...)
		for _, marker := range markers {
			if strings.Contains(normalized, marker) {
				return signature, true
			}
		}
	}
	return Signature{}, false
}

func effectiveSignatures(centralRules ...[]protocol.AgentSignature) []Signature {
	if len(centralRules) == 0 || len(centralRules[0]) == 0 {
		return signatures
	}
	result := make([]Signature, 0, len(centralRules[0]))
	for _, rule := range centralRules[0] {
		if strings.TrimSpace(rule.Product) == "" || strings.TrimSpace(rule.Vendor) == "" {
			continue
		}
		result = append(result, Signature{
			Product: rule.Product, Vendor: rule.Vendor, Framework: rule.Framework,
			Markers: rule.ProcessMarkers, ContentMarkers: rule.ContentMarkers,
			PackageNames: rule.PackageNames, HomeDirectories: rule.HomeDirectories, ManifestNames: rule.ManifestNames,
		})
	}
	if len(result) == 0 {
		return signatures
	}
	return result
}

func effectiveRoots(configured []string) []string {
	result := append([]string{}, configured...)
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return unique(result)
	}
	for _, relative := range []string{".hermes", ".openclaw", ".agents"} {
		candidate := filepath.Join(home, relative)
		if _, err := os.Stat(candidate); err == nil {
			result = append(result, candidate)
		}
	}
	return unique(result)
}

func looksGenericAgentProcess(value string) bool {
	return containsAny(value, "agent-worker", "ai-agent", "agent.py", "agent.exe", "agent gateway", "agent runtime")
}

func profileIdentity(value, host string) string {
	normalized := filepath.ToSlash(strings.ToLower(value))
	for _, candidate := range []struct{ home, product string }{
		{"/.hermes/profiles/", "hermes"},
		{"/.openclaw/agents/", "openclaw"},
		{"/.openclaw/workspaces/", "openclaw"},
	} {
		index := strings.Index(normalized, candidate.home)
		if index < 0 {
			continue
		}
		remainder := normalized[index+len(candidate.home):]
		name := strings.FieldsFunc(remainder, func(r rune) bool { return r == '/' || r == ' ' || r == '\t' })
		if len(name) > 0 && name[0] != "" {
			return "profile:" + host + ":" + candidate.product + ":" + strings.TrimSpace(name[0])
		}
	}
	if strings.Contains(normalized, "/.hermes/") {
		return "profile:" + host + ":hermes-default"
	}
	if strings.Contains(normalized, "/.openclaw/") {
		return "profile:" + host + ":openclaw-default"
	}
	return ""
}

func matchModels(value string) []string {
	normalized := strings.ToLower(value)
	var result []string
	for marker, name := range modelMarkers {
		if strings.Contains(normalized, marker) {
			result = append(result, name)
		}
	}
	sort.Strings(result)
	return unique(result)
}

func listeningPorts(pid int) []string {
	inodes := map[string]bool{}
	entries, _ := os.ReadDir(fmt.Sprintf("/proc/%d/fd", pid))
	for _, entry := range entries {
		target, err := os.Readlink(fmt.Sprintf("/proc/%d/fd/%s", pid, entry.Name()))
		if err == nil && strings.HasPrefix(target, "socket:[") {
			inodes[strings.TrimSuffix(strings.TrimPrefix(target, "socket:["), "]")] = true
		}
	}
	var ports []string
	for _, table := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		lines := strings.Split(readText(table, 4*1024*1024), "\n")
		for _, line := range lines[1:] {
			fields := strings.Fields(line)
			if len(fields) < 10 || fields[3] != "0A" || !inodes[fields[9]] {
				continue
			}
			parts := strings.Split(fields[1], ":")
			if len(parts) != 2 {
				continue
			}
			port, err := strconv.ParseInt(parts[1], 16, 32)
			if err == nil {
				ports = append(ports, strconv.FormatInt(port, 10))
			}
		}
	}
	sort.Strings(ports)
	return unique(ports)
}

func processAccount(pid int, fallback string) string {
	status := readText(fmt.Sprintf("/proc/%d/status", pid), 8192)
	for _, line := range strings.Split(status, "\n") {
		if !strings.HasPrefix(line, "Uid:") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			break
		}
		owner, err := user.LookupId(fields[1])
		if err == nil && owner.Username != "" {
			return owner.Username
		}
		return fields[1]
	}
	return fallback
}

func dockerPorts(document dockerInspect) []string {
	var result []string
	for containerPort, bindings := range document.NetworkSettings.Ports {
		if len(bindings) == 0 {
			result = append(result, containerPort)
			continue
		}
		for _, binding := range bindings {
			result = append(result, containerPort+"->"+binding.HostIP+":"+binding.HostPort)
		}
	}
	sort.Strings(result)
	return result
}

func dockerServiceEndpoints(document dockerInspect, host string) []string {
	var result []string
	for _, bindings := range document.NetworkSettings.Ports {
		for _, binding := range bindings {
			if binding.HostPort == "" {
				continue
			}
			result = append(result, "tcp://"+net.JoinHostPort(host, binding.HostPort))
		}
	}
	sort.Strings(result)
	return unique(result)
}

func dockerRelationships(project, service string, networks map[string]any) []protocol.Relationship {
	var result []protocol.Relationship
	if project != "" {
		result = append(result, protocol.Relationship{RelationType: "MEMBER_OF_COMPOSE_PROJECT", Target: project, Confidence: 100})
	}
	if service != "" {
		result = append(result, protocol.Relationship{RelationType: "COMPOSE_SERVICE", Target: service, Confidence: 100})
	}
	for network := range networks {
		result = append(result, protocol.Relationship{RelationType: "CONNECTED_TO_NETWORK", Target: network, Confidence: 100})
	}
	return result
}

func environmentKeys(values []string) []string {
	var result []string
	for _, value := range values {
		key := strings.SplitN(value, "=", 2)[0]
		upper := strings.ToUpper(key)
		if containsAny(upper, "OPENAI", "ANTHROPIC", "AZURE_OPENAI", "GEMINI", "DEEPSEEK", "DASHSCOPE", "MOONSHOT", "OLLAMA") {
			result = append(result, key)
		}
	}
	sort.Strings(result)
	return unique(result)
}

func manifestKind(path string, catalogues ...[]Signature) string {
	normalized := strings.ToLower(filepath.ToSlash(path))
	name := strings.ToLower(filepath.Base(path))
	if name == "skill.md" && strings.Contains(normalized, "/skills/") {
		return "SKILL"
	}
	if name == "plugin.json" && (strings.Contains(normalized, "/.codex-plugin/") || strings.Contains(normalized, "/.claude-plugin/")) {
		return "PLUGIN"
	}
	if name == "openclaw.plugin.json" || (name == "plugin.yaml" && strings.Contains(normalized, "/.hermes/plugins/")) {
		return "PLUGIN"
	}
	for _, allowed := range []string{"package.json", "pyproject.toml", "requirements.txt", "pom.xml", "mcp.json", ".mcp.json", "claude_desktop_config.json"} {
		if name == allowed {
			return "AGENT"
		}
	}
	catalogue := signatures
	if len(catalogues) > 0 && len(catalogues[0]) > 0 {
		catalogue = catalogues[0]
	}
	for _, signature := range catalogue {
		for _, manifest := range signature.ManifestNames {
			if name != strings.ToLower(manifest) {
				continue
			}
			for _, home := range signature.HomeDirectories {
				if strings.Contains(normalized, "/"+strings.Trim(strings.ToLower(home), "/")+"/") {
					return "AGENT"
				}
			}
		}
	}
	return ""
}

func excludedDirectory(name string) bool {
	switch name {
	case ".git", "node_modules", "target", "dist", "build", ".venv", "venv", "__pycache__":
		return true
	}
	return false
}

func extractNames(document string, keys []string) []string {
	var value any
	if json.Unmarshal([]byte(document), &value) != nil {
		return nil
	}
	wanted := map[string]bool{}
	for _, key := range keys {
		wanted[strings.ToLower(key)] = true
	}
	var result []string
	var visit func(any)
	visit = func(current any) {
		switch typed := current.(type) {
		case map[string]any:
			for key, nested := range typed {
				if wanted[strings.ToLower(key)] {
					switch names := nested.(type) {
					case map[string]any:
						for name := range names {
							result = append(result, name)
						}
					case []any:
						for _, item := range names {
							if text, ok := item.(string); ok {
								result = append(result, text)
							}
							if object, ok := item.(map[string]any); ok {
								if name, ok := object["name"].(string); ok {
									result = append(result, name)
								}
							}
						}
					}
				}
				visit(nested)
			}
		case []any:
			for _, nested := range typed {
				visit(nested)
			}
		}
	}
	visit(value)
	sort.Strings(result)
	return unique(result)
}

func readText(path string, limit int64) string {
	file, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer file.Close()
	data := make([]byte, limit)
	n, _ := file.Read(data)
	return string(data[:n])
}

func redactPath(path string) string {
	home, _ := os.UserHomeDir()
	if home != "" && strings.HasPrefix(path, home) {
		return "~" + strings.TrimPrefix(path, home)
	}
	return path
}

func digest(values ...string) string {
	sum := sha256.Sum256([]byte(strings.Join(values, "|")))
	return hex.EncodeToString(sum[:])
}
func containsAny(value string, needles ...string) bool {
	normalized := strings.ToLower(value)
	for _, needle := range needles {
		if strings.Contains(normalized, strings.ToLower(needle)) {
			return true
		}
	}
	return false
}
func truthy(value string) bool {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}
func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
func toString(value any) string { data, _ := json.Marshal(value); return string(data) }
func imageVersion(image string) string {
	last := filepath.Base(image)
	if index := strings.LastIndex(last, ":"); index >= 0 {
		return last[index+1:]
	}
	return ""
}
func protocolsForPorts(ports []string) []string {
	if len(ports) == 0 {
		return nil
	}
	return []string{"TCP"}
}
func unique(values []string) []string {
	seen := map[string]bool{}
	var result []string
	for _, value := range values {
		if value != "" && !seen[value] {
			seen[value] = true
			result = append(result, value)
		}
	}
	return result
}
func deduplicate(values []protocol.Observation) []protocol.Observation {
	seen := map[string]bool{}
	var result []protocol.Observation
	for _, value := range values {
		if !seen[value.Fingerprint] {
			seen[value.Fingerprint] = true
			result = append(result, value)
		}
	}
	return result
}
