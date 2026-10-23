package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
	"unicode"
	"unicode/utf8"
)

const mcpManifestPath = "/opt/eap-mcp/services.json"
const mcpManifestVersion = "EAP_MCP_SERVICES_V1"
const mcpManifestLimit = 16384

var mcpManifestError = errors.New("invalid MCP service manifest")
var mcpServiceName = regexp.MustCompile(`^[a-z][a-z0-9_-]{0,63}$`)
var mcpToolName = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$`)

// Loaded once before serving requests. No task, environment, model output, or
// HTTP configuration can select a manifest path or replace these commands.
type mcpRegistry struct {
	digest   string
	services map[string]mcpLaunchConfig
	order    []string
}

type mcpServiceProfile struct {
	Name   string   `json:"name"`
	Server string   `json:"server"`
	Tools  []string `json:"tools"`
}

type mcpRegistryProfile struct {
	Version  string              `json:"version"`
	Enabled  bool                `json:"enabled"`
	Digest   string              `json:"manifestSha256,omitempty"`
	Services []mcpServiceProfile `json:"services"`
}

func loadMCPRegistry(path string) (*mcpRegistry, error) {
	// A dangling symlink is invalid, not an absent optional manifest.
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		return &mcpRegistry{}, nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Size() > mcpManifestLimit {
		return nil, mcpManifestError
	}
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, mcpManifestError
	}
	file := os.NewFile(uintptr(fd), path)
	defer file.Close()
	info, err = file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		return nil, mcpManifestError
	}
	raw, err := io.ReadAll(io.LimitReader(file, mcpManifestLimit+1))
	if err != nil {
		return nil, mcpManifestError
	}
	return parseMCPRegistry(raw)
}

// encoding/json alone accepts duplicate keys, case-folded field names, null
// scalars, and invalid UTF-8. Reject all of them before interpreting a command.
func validateMCPJSON(dec *json.Decoder, depth int) error {
	if depth > 8 {
		return mcpManifestError
	}
	token, err := dec.Token()
	if err != nil || token == nil {
		return mcpManifestError
	}
	delim, compound := token.(json.Delim)
	if !compound {
		return nil
	}
	switch delim {
	case '{':
		seen := map[string]bool{}
		for dec.More() {
			key, err := dec.Token()
			name, ok := key.(string)
			if err != nil || !ok || seen[name] {
				return mcpManifestError
			}
			seen[name] = true
			if validateMCPJSON(dec, depth+1) != nil {
				return mcpManifestError
			}
		}
	case '[':
		for dec.More() {
			if validateMCPJSON(dec, depth+1) != nil {
				return mcpManifestError
			}
		}
	default:
		return mcpManifestError
	}
	end, err := dec.Token()
	if err != nil || (delim == '{' && end != json.Delim('}')) || (delim == '[' && end != json.Delim(']')) {
		return mcpManifestError
	}
	return nil
}

func mcpExactFields(raw json.RawMessage, required, optional []string) bool {
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil || fields == nil {
		return false
	}
	for _, key := range required {
		if _, ok := fields[key]; !ok {
			return false
		}
		delete(fields, key)
	}
	for _, key := range optional {
		delete(fields, key)
	}
	return len(fields) == 0
}

func parseMCPRegistry(raw []byte) (*mcpRegistry, error) {
	if len(raw) == 0 || len(raw) > mcpManifestLimit || !utf8.Valid(raw) {
		return nil, mcpManifestError
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	if validateMCPJSON(dec, 0) != nil {
		return nil, mcpManifestError
	}
	if _, err := dec.Token(); err != io.EOF ||
		!mcpExactFields(raw, []string{"version", "services"}, []string{"allowInsecureRuntimeHttp"}) {
		return nil, mcpManifestError
	}
	var document struct {
		Version   string            `json:"version"`
		AllowHTTP bool              `json:"allowInsecureRuntimeHttp"`
		Services  []json.RawMessage `json:"services"`
	}
	if json.Unmarshal(raw, &document) != nil || document.Version != mcpManifestVersion ||
		len(document.Services) < 1 || len(document.Services) > 8 {
		return nil, mcpManifestError
	}
	registry := &mcpRegistry{services: map[string]mcpLaunchConfig{}}
	servers := map[string]bool{}
	for _, rawService := range document.Services {
		if !mcpExactFields(rawService, []string{"name", "server", "tools", "command"}, nil) {
			return nil, mcpManifestError
		}
		var service struct {
			Name    string   `json:"name"`
			Server  string   `json:"server"`
			Tools   []string `json:"tools"`
			Command []string `json:"command"`
		}
		if json.Unmarshal(rawService, &service) != nil || !mcpServiceName.MatchString(service.Name) ||
			!mcpServiceName.MatchString(service.Server) || servers[service.Server] ||
			len(service.Tools) < 1 || len(service.Tools) > 32 || len(service.Command) < 1 || len(service.Command) > 32 {
			return nil, mcpManifestError
		}
		if _, exists := registry.services[service.Name]; exists {
			return nil, mcpManifestError
		}
		executable := service.Command[0]
		if !filepath.IsAbs(executable) || filepath.Clean(executable) != executable || len(executable) > 512 {
			return nil, mcpManifestError
		}
		for _, arg := range service.Command {
			if len(arg) > 1024 || strings.IndexFunc(arg, unicode.IsControl) >= 0 {
				return nil, mcpManifestError
			}
		}
		tools := map[string]bool{}
		for _, tool := range service.Tools {
			if !mcpToolName.MatchString(tool) || tools[tool] {
				return nil, mcpManifestError
			}
			tools[tool] = true
		}
		registry.services[service.Name] = mcpLaunchConfig{
			Python: "/usr/local/bin/python3", Relay: "/opt/eap-mcp/relay.py",
			Server: service.Server, Tools: service.Tools, Command: service.Command, AllowHTTP: document.AllowHTTP,
		}
		registry.order = append(registry.order, service.Name)
		servers[service.Server] = true
	}
	hash := sha256.Sum256(raw)
	registry.digest = hex.EncodeToString(hash[:])
	return registry, nil
}

func (registry *mcpRegistry) lookup(name string) (mcpLaunchConfig, bool) {
	if registry == nil {
		return mcpLaunchConfig{}, false
	}
	cfg, ok := registry.services[name]
	// Do not expose the frozen slices to callers.
	cfg.Tools = append([]string(nil), cfg.Tools...)
	cfg.Command = append([]string(nil), cfg.Command...)
	return cfg, ok
}

func (registry *mcpRegistry) profile() mcpRegistryProfile {
	result := mcpRegistryProfile{Version: mcpManifestVersion, Services: []mcpServiceProfile{}}
	if registry == nil {
		return result
	}
	result.Enabled, result.Digest = len(registry.order) > 0, registry.digest
	for _, name := range registry.order {
		cfg, _ := registry.lookup(name)
		result.Services = append(result.Services, mcpServiceProfile{Name: name, Server: cfg.Server, Tools: cfg.Tools})
	}
	return result
}

// Only trusted host code chooses a service and performs MCP initialization/tool
// calls. This registry is not an execution endpoint or a tool authorization layer.
func (r *runner) startMCPService(ctx context.Context, name, traceID, parentID string) (*observedMCP, error) {
	cfg, ok := r.mcpRegistry.lookup(name)
	if !ok {
		return nil, errors.New("MCP service unavailable")
	}
	return r.startObservedMCP(ctx, cfg, traceID, parentID)
}

func (r *runner) mcpProfile(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodGet {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	writeJSON(w, r.mcpRegistry.profile())
}
