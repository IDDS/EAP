package autoattach

import (
	"context"
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

//go:embed assets/sitecustomize.py assets/eap_litellm_callback.py
var instrumentationAssets embed.FS

var safeName = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)

const instrumentationVersion = "1.2.2"

type Options struct {
	Target                   string
	Profile                  string
	AgentID                  int64
	UsagePoolKey             string
	Provider                 string
	CollectorURL             string
	InstrumentationTokenFile string
	ProxyInstance            string
	Apply                    bool
}

type Plan struct {
	Target                   string   `json:"target"`
	Profile                  string   `json:"profile"`
	ComposeProjectName       string   `json:"composeProjectName"`
	ComposeProjectDirectory  string   `json:"composeProjectDirectory"`
	ComposeFiles             []string `json:"composeFiles"`
	ComposeService           string   `json:"composeService"`
	OverrideFile             string   `json:"overrideFile"`
	InstrumentationDirectory string   `json:"instrumentationDirectory"`
	InstrumentationTokenFile string   `json:"instrumentationTokenFile"`
	ProxyService             string   `json:"proxyService"`
	AgentID                  int64    `json:"agentId"`
	Provider                 string   `json:"provider"`
	UsagePoolKey             string   `json:"usagePoolKey"`
	CollectorURL             string   `json:"collectorUrl"`
	PythonPath               string   `json:"pythonPath"`
	AlreadyAttached          bool     `json:"alreadyAttached"`
	RestartRequired          bool     `json:"restartRequired"`
	ApplyRequested           bool     `json:"applyRequested"`
	OverrideYAML             string   `json:"overrideYaml"`
}

type dockerInspect struct {
	Name   string `json:"Name"`
	Config struct {
		Image  string            `json:"Image"`
		Env    []string          `json:"Env"`
		Labels map[string]string `json:"Labels"`
	} `json:"Config"`
	NetworkSettings struct {
		Networks map[string]struct {
			Gateway string `json:"Gateway"`
		} `json:"Networks"`
	} `json:"NetworkSettings"`
}

func BuildPlan(ctx context.Context, options Options) (Plan, error) {
	target := strings.TrimSpace(options.Target)
	if target == "" || !safeName.MatchString(target) {
		return Plan{}, errors.New("attach target must be a Docker container name")
	}
	if options.AgentID <= 0 {
		return Plan{}, errors.New("agent-id must be greater than zero")
	}
	pool := strings.TrimSpace(options.UsagePoolKey)
	if pool == "" {
		return Plan{}, errors.New("usage-pool-key is required")
	}
	document, err := inspectContainer(ctx, target)
	if err != nil {
		return Plan{}, err
	}
	labels := document.Config.Labels
	composeDir := strings.TrimSpace(labels["com.docker.compose.project.working_dir"])
	projectName := strings.TrimSpace(labels["com.docker.compose.project"])
	service := strings.TrimSpace(labels["com.docker.compose.service"])
	override := filepath.Join(composeDir, "docker-compose.eap-observability.yml")
	composeFiles := withoutFile(splitComposeFiles(labels["com.docker.compose.project.config_files"]), override)
	if composeDir == "" || projectName == "" || service == "" || len(composeFiles) == 0 {
		return Plan{}, errors.New("target is not managed by Docker Compose; automatic attachment is unavailable")
	}
	if !safeName.MatchString(projectName) || !safeName.MatchString(service) {
		return Plan{}, errors.New("Docker Compose project or service name is unsafe")
	}
	profile := strings.ToLower(strings.TrimSpace(options.Profile))
	if profile == "" || profile == "auto" {
		profile, err = detectProfile(ctx, target)
		if err != nil {
			return Plan{}, err
		}
	}
	if profile != "litellm" {
		return Plan{}, fmt.Errorf("unsupported auto-attach profile %q", profile)
	}
	provider := firstNonBlank(options.Provider, environmentValue(document.Config.Env, "LLM_PROVIDER"), "unknown")
	collectorURL := resolveCollectorURL(options.CollectorURL, document)
	tokenFile := strings.TrimSpace(options.InstrumentationTokenFile)
	if tokenFile == "" || !filepath.IsAbs(tokenFile) {
		return Plan{}, errors.New("instrumentation-token-file must be an absolute path")
	}
	proxyInstance := strings.TrimSpace(options.ProxyInstance)
	if proxyInstance == "" || !safeName.MatchString(proxyInstance) {
		return Plan{}, errors.New("proxy-instance is required and must contain only letters, numbers, dot, dash or underscore")
	}
	instrumentationDir := "/var/lib/eap-discovery-agent/instrumentation/python"
	plan := Plan{
		Target: target, Profile: profile, ComposeProjectName: projectName, ComposeProjectDirectory: composeDir, ComposeFiles: composeFiles,
		ComposeService: service, OverrideFile: override, InstrumentationDirectory: instrumentationDir,
		InstrumentationTokenFile: tokenFile, ProxyService: "eap-content-proxy@" + proxyInstance + ".service",
		AgentID: options.AgentID, Provider: provider,
		UsagePoolKey: pool, CollectorURL: strings.TrimRight(collectorURL, "/"),
		ApplyRequested: options.Apply,
	}
	plan.PythonPath = mergedPythonPath(environmentValue(document.Config.Env, "PYTHONPATH"))
	plan.AlreadyAttached = isAttached(document.Config.Env, plan)
	plan.RestartRequired = !plan.AlreadyAttached
	plan.OverrideYAML = renderOverride(plan)
	return plan, nil
}

// resolveCollectorURL prefers an explicitly configured URL. When it is omitted,
// use the target container's Docker network gateway so Linux containers can reach
// a host-bound collector even when host.docker.internal resolves to a different
// bridge. The host.docker.internal fallback keeps the plan portable for runtimes
// that do not expose a gateway in inspect output (for example Docker Desktop).
func resolveCollectorURL(requested string, document dockerInspect) string {
	if configured := strings.TrimSpace(requested); configured != "" {
		return strings.TrimRight(configured, "/")
	}
	for _, network := range document.NetworkSettings.Networks {
		gateway := strings.TrimSpace(network.Gateway)
		if gateway == "" {
			continue
		}
		return "http://" + net.JoinHostPort(gateway, "8687")
	}
	return "http://host.docker.internal:8687"
}

func Apply(ctx context.Context, plan Plan) error {
	if os.Geteuid() != 0 {
		return errors.New("automatic Docker attachment must run as root")
	}
	if err := ensureInstrumentationToken(plan.InstrumentationTokenFile); err != nil {
		return err
	}
	if err := os.MkdirAll(plan.InstrumentationDirectory, 0o755); err != nil {
		return err
	}
	for _, name := range []string{"sitecustomize.py", "eap_litellm_callback.py"} {
		data, readErr := instrumentationAssets.ReadFile("assets/" + name)
		if readErr != nil {
			return readErr
		}
		if writeErr := os.WriteFile(filepath.Join(plan.InstrumentationDirectory, name), data, 0o644); writeErr != nil {
			return writeErr
		}
	}
	if existing, readErr := os.ReadFile(plan.OverrideFile); readErr == nil && string(existing) != plan.OverrideYAML {
		backup := plan.OverrideFile + ".bak-" + time.Now().UTC().Format("20060102T150405Z")
		if writeErr := os.WriteFile(backup, existing, 0o600); writeErr != nil {
			return fmt.Errorf("backup existing override: %w", writeErr)
		}
	}
	if err := os.WriteFile(plan.OverrideFile, []byte(plan.OverrideYAML), 0o600); err != nil {
		return err
	}
	proxy := exec.CommandContext(ctx, "systemctl", "enable", "--now", plan.ProxyService)
	proxy.Stdout = os.Stdout
	proxy.Stderr = os.Stderr
	if err := proxy.Run(); err != nil {
		return fmt.Errorf("start scoped content collector %s: %w", plan.ProxyService, err)
	}
	if plan.AlreadyAttached {
		return nil
	}
	args := []string{"compose"}
	for _, composeFile := range plan.ComposeFiles {
		args = append(args, "-f", composeFile)
	}
	args = append(args, "-f", plan.OverrideFile, "--project-name", plan.ComposeProjectName, "up", "-d", plan.ComposeService)
	command := exec.CommandContext(ctx, "docker", args...)
	command.Dir = plan.ComposeProjectDirectory
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Run(); err != nil {
		return fmt.Errorf("apply Docker Compose observability override: %w", err)
	}
	return nil
}

func ensureInstrumentationToken(path string) error {
	if token, err := os.ReadFile(path); err == nil {
		if strings.TrimSpace(string(token)) == "" {
			return errors.New("instrumentation token file is empty")
		}
		return nil
	} else if !os.IsNotExist(err) {
		return fmt.Errorf("read instrumentation token: %w", err)
	}
	parent := filepath.Dir(path)
	if err := os.MkdirAll(parent, 0o750); err != nil {
		return err
	}
	contentUser, userErr := user.Lookup("eap-content")
	agentGroup, groupErr := user.LookupGroup("eap-agent")
	if userErr != nil || groupErr != nil {
		return errors.New("eap-content user and eap-agent group must exist before automatic attachment")
	}
	uid, uidErr := strconv.Atoi(contentUser.Uid)
	gid, gidErr := strconv.Atoi(agentGroup.Gid)
	if uidErr != nil || gidErr != nil {
		return errors.New("eap-content user or eap-agent group has an invalid numeric ID")
	}
	if err := os.Chown(parent, uid, gid); err != nil {
		return err
	}
	random := make([]byte, 32)
	if _, err := rand.Read(random); err != nil {
		return err
	}
	if err := os.WriteFile(path, []byte(hex.EncodeToString(random)+"\n"), 0o640); err != nil {
		return err
	}
	if err := os.Chown(path, uid, gid); err != nil {
		return err
	}
	return nil
}

func inspectContainer(ctx context.Context, target string) (dockerInspect, error) {
	raw, err := exec.CommandContext(ctx, "docker", "inspect", target).Output()
	if err != nil {
		return dockerInspect{}, fmt.Errorf("docker inspect %s: %w", target, err)
	}
	var documents []dockerInspect
	if err := json.Unmarshal(raw, &documents); err != nil || len(documents) != 1 {
		return dockerInspect{}, errors.New("Docker inspect response is invalid")
	}
	return documents[0], nil
}

func detectProfile(ctx context.Context, target string) (string, error) {
	command := exec.CommandContext(ctx, "docker", "exec", target, "python", "-c",
		"import importlib.util; print('litellm' if importlib.util.find_spec('litellm') else '')")
	output, err := command.Output()
	if err == nil && strings.TrimSpace(string(output)) == "litellm" {
		return "litellm", nil
	}
	return "", errors.New("no supported framework auto-instrumentation profile was detected")
}

func splitComposeFiles(value string) []string {
	items := strings.FieldsFunc(value, func(r rune) bool { return r == ',' || r == ';' })
	result := make([]string, 0, len(items))
	for _, item := range items {
		if normalized := strings.TrimSpace(item); normalized != "" {
			result = append(result, normalized)
		}
	}
	return result
}

func withoutFile(values []string, excluded string) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		if filepath.Clean(value) != filepath.Clean(excluded) {
			result = append(result, value)
		}
	}
	return result
}

func mergedPythonPath(existing string) string {
	values := []string{"/opt/eap-observability"}
	for _, value := range strings.Split(existing, ":") {
		value = strings.TrimSpace(value)
		if value != "" && value != "/opt/eap-observability" {
			values = append(values, value)
		}
	}
	return strings.Join(values, ":")
}

func environmentValue(values []string, key string) string {
	for _, value := range values {
		parts := strings.SplitN(value, "=", 2)
		if len(parts) == 2 && parts[0] == key {
			return strings.TrimSpace(parts[1])
		}
	}
	return ""
}

func isAttached(environment []string, plan Plan) bool {
	expected := map[string]string{
		"EAP_CONTENT_AUTOINSTRUMENT":         "true",
		"EAP_INSTRUMENTATION_VERSION":        instrumentationVersion,
		"EAP_INSTRUMENTATION_URL":            plan.CollectorURL,
		"EAP_INSTRUMENTATION_TOKEN_FILE":     "/var/run/eap/instrumentation-token",
		"EAP_INSTRUMENTATION_PROVIDER":       plan.Provider,
		"EAP_INSTRUMENTATION_AGENT_ID":       fmt.Sprintf("%d", plan.AgentID),
		"EAP_INSTRUMENTATION_USAGE_POOL_KEY": plan.UsagePoolKey,
	}
	for key, value := range expected {
		if environmentValue(environment, key) != value {
			return false
		}
	}
	return strings.Contains(":"+environmentValue(environment, "PYTHONPATH")+":", ":/opt/eap-observability:")
}

func renderOverride(plan Plan) string {
	return fmt.Sprintf("services:\n  %s:\n    extra_hosts:\n      - \"host.docker.internal:host-gateway\"\n    volumes:\n      - \"%s:/opt/eap-observability:ro\"\n      - \"%s:/var/run/eap/instrumentation-token:ro\"\n    environment:\n      PYTHONPATH: %q\n      EAP_CONTENT_AUTOINSTRUMENT: \"true\"\n      EAP_INSTRUMENTATION_VERSION: %q\n      EAP_INSTRUMENTATION_URL: %q\n      EAP_INSTRUMENTATION_TOKEN_FILE: \"/var/run/eap/instrumentation-token\"\n      EAP_INSTRUMENTATION_PROVIDER: %q\n      EAP_INSTRUMENTATION_AGENT_ID: %q\n      EAP_INSTRUMENTATION_USAGE_POOL_KEY: %q\n", plan.ComposeService,
		plan.InstrumentationDirectory, plan.InstrumentationTokenFile, plan.PythonPath, instrumentationVersion, plan.CollectorURL, plan.Provider,
		fmt.Sprintf("%d", plan.AgentID), plan.UsagePoolKey)
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
