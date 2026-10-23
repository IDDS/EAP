package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const testMCPManifest = `{"version":"EAP_MCP_SERVICES_V1","allowInsecureRuntimeHttp":true,"services":[{"name":"synthetic","server":"eap-synthetic-mcp","tools":["observation_probe"],"command":["/usr/local/bin/python3","/opt/eap-mcp/fixture_server.py"]}]}`

func TestMCPManifestRejectsAmbiguousOrUnboundedConfiguration(t *testing.T) {
	cases := map[string]string{
		"empty": "", "null": "null", "array": "[]", "trailing": testMCPManifest + "{}",
		"duplicate":          strings.Replace(testMCPManifest, `"name":"synthetic"`, `"name":"ignored","name":"synthetic"`, 1),
		"escapedDuplicate":   strings.Replace(testMCPManifest, `"name":"synthetic"`, `"na\u006de":"ignored","name":"synthetic"`, 1),
		"case":               strings.Replace(testMCPManifest, `"version"`, `"Version"`, 1),
		"serviceCase":        strings.Replace(testMCPManifest, `"name"`, `"Name"`, 1),
		"unknownField":       strings.Replace(testMCPManifest, `"version":`, `"env":{},"version":`, 1),
		"serviceEnv":         strings.Replace(testMCPManifest, `"name":`, `"env":{},"name":`, 1),
		"version":            strings.Replace(testMCPManifest, "EAP_MCP_SERVICES_V1", "future", 1),
		"nullBool":           strings.Replace(testMCPManifest, ":true", ":null", 1),
		"stringBool":         strings.Replace(testMCPManifest, ":true", `:"true"`, 1),
		"nullTools":          strings.Replace(testMCPManifest, `["observation_probe"]`, `null`, 1),
		"emptyTools":         strings.Replace(testMCPManifest, `["observation_probe"]`, `[]`, 1),
		"duplicateTools":     strings.Replace(testMCPManifest, `["observation_probe"]`, `["observation_probe","observation_probe"]`, 1),
		"invalidTool":        strings.Replace(testMCPManifest, "observation_probe", "--tool", 1),
		"relativeExecutable": strings.Replace(testMCPManifest, "/usr/local/bin/python3", "python3", 1),
		"uncleanExecutable":  strings.Replace(testMCPManifest, "/usr/local/bin/python3", "/usr/local/bin/../bin/python3", 1),
		"nullArg":            strings.Replace(testMCPManifest, `"/opt/eap-mcp/fixture_server.py"`, `null`, 1),
		"controlArg":         strings.Replace(testMCPManifest, "fixture_server.py", `fixture\nserver.py`, 1),
		"oversizeArg":        strings.Replace(testMCPManifest, "fixture_server.py", strings.Repeat("x", 1025), 1),
		"oversizeFile":       testMCPManifest + strings.Repeat(" ", mcpManifestLimit),
		"invalidUTF8":        strings.Replace(testMCPManifest, "fixture_server.py", string([]byte{0xff}), 1),
		"missingServices":    `{"version":"EAP_MCP_SERVICES_V1"}`,
		"emptyServices":      `{"version":"EAP_MCP_SERVICES_V1","services":[]}`,
		"tooDeep":            `{"version":"EAP_MCP_SERVICES_V1","services":[[[[[[[[[[]]]]]]]]]]}`,
	}
	var doc map[string]any
	json.Unmarshal([]byte(testMCPManifest), &doc)
	service := doc["services"].([]any)[0]
	for _, count := range []int{2, 9} {
		services := []any{}
		for i := 0; i < count; i++ {
			services = append(services, service)
		}
		doc["services"] = services
		raw, _ := json.Marshal(doc)
		label := "duplicateService"
		if count == 9 {
			label = "tooManyServices"
		}
		cases[label] = string(raw)
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if registry, err := parseMCPRegistry([]byte(raw)); err != mcpManifestError || registry != nil {
				t.Fatal("unsafe manifest accepted")
			}
		})
	}
}

func TestMCPRegistryFreezesFileAndDoesNotLeakCommandOrMutableSlices(t *testing.T) {
	path := filepath.Join(t.TempDir(), "services.json")
	if os.WriteFile(path, []byte(testMCPManifest), 0600) != nil {
		t.Fatal("write manifest")
	}
	registry, err := loadMCPRegistry(path)
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(path, []byte(`{"version":"changed"}`), 0600)
	cfg, ok := registry.lookup("synthetic")
	if !ok || cfg.Server != "eap-synthetic-mcp" || !cfg.AllowHTTP {
		t.Fatal("service not loaded")
	}
	cfg.Command[0], cfg.Tools[0] = "replaced", "replaced"
	profile := registry.profile()
	profile.Services[0].Tools[0] = "profile-replaced"
	again, _ := registry.lookup("synthetic")
	if again.Command[0] != "/usr/local/bin/python3" || again.Tools[0] != "observation_probe" ||
		registry.profile().Digest != digest([]byte(testMCPManifest)) {
		t.Fatal("frozen registry changed")
	}
	raw, _ := json.Marshal(registry.profile())
	if bytes.Contains(raw, []byte("command")) || bytes.Contains(raw, []byte("/opt")) || bytes.Contains(raw, []byte("python3")) {
		t.Fatal("profile exposes launch details")
	}
	noHTTP, err := parseMCPRegistry([]byte(strings.Replace(testMCPManifest, `"allowInsecureRuntimeHttp":true,`, "", 1)))
	if err != nil {
		t.Fatal(err)
	}
	secure, _ := noHTTP.lookup("synthetic")
	if secure.AllowHTTP {
		t.Fatal("insecure HTTP enabled by default")
	}
}

func TestMCPManifestOptionalButInvalidFilesFailClosed(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "missing.json")
	registry, err := loadMCPRegistry(path)
	if err != nil || registry.profile().Enabled || len(registry.profile().Services) != 0 {
		t.Fatal("optional manifest not disabled")
	}
	if _, err := loadMCPRegistry(dir); err != mcpManifestError {
		t.Fatal("directory accepted")
	}
	os.Symlink(filepath.Join(dir, "absent"), path)
	if _, err := loadMCPRegistry(path); err != mcpManifestError {
		t.Fatal("dangling manifest symlink treated as optional")
	}
	os.Remove(path)
	os.WriteFile(path, []byte("invalid"), 0600)
	if _, err := loadMCPRegistry(path); err != mcpManifestError {
		t.Fatal("invalid manifest ignored")
	}
}

func TestMCPProfileRequiresCurrentRuntimeCredential(t *testing.T) {
	r, signing, uid := identityFixture(t)
	mcpInstall(t, r, signing, uid, 1)
	r.mcpRegistry, _ = parseMCPRegistry([]byte(testMCPManifest))
	request := func(method, token string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, "/eap/mcp/profile", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		rec := httptest.NewRecorder()
		r.mcpProfile(rec, req)
		return rec
	}
	if request("GET", "").Code != 401 || request("POST", r.runtimeToken).Code != 405 {
		t.Fatal("profile authorization/method boundary")
	}
	old := r.runtimeToken
	mcpInstall(t, r, signing, uid, 2)
	if request("GET", old).Code != 401 {
		t.Fatal("old credential accepted")
	}
	response := request("GET", r.runtimeToken)
	var profile mcpRegistryProfile
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &profile) != nil || !profile.Enabled ||
		profile.Digest != digest([]byte(testMCPManifest)) {
		t.Fatal("profile mismatch")
	}
	r.credentialExpiry = time.Now().Add(-time.Second)
	if request("GET", r.runtimeToken).Code != 401 {
		t.Fatal("expired credential accepted")
	}
}

func TestMCPNamedServicesUseFrozenLaunchAndIdentityLifecycle(t *testing.T) {
	mcpTestConfig(t)
	var doc map[string]any
	json.Unmarshal([]byte(testMCPManifest), &doc)
	var other map[string]any
	json.Unmarshal([]byte(`{"name":"second","server":"second-mcp","tools":["observation_probe"],"command":["/usr/local/bin/python3","/opt/eap-mcp/fixture_server.py"]}`), &other)
	doc["services"] = append(doc["services"].([]any), other)
	raw, _ := json.Marshal(doc)
	r, signing, uid := identityFixture(t)
	r.mcpRegistry, _ = parseMCPRegistry(raw)
	mcpInstall(t, r, signing, uid, 1)
	server, mu, payloads := mcpReceiver(t, r)
	defer server.Close()
	r.gateway = server.URL
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, name := range []string{"missing", "/bin/sh", "synthetic;ignored"} {
		if child, err := r.startMCPService(ctx, name, strings.Repeat("a", 32), strings.Repeat("b", 16)); child != nil || err == nil {
			t.Fatal("unknown service launched")
		}
	}
	for _, name := range []string{"synthetic", "second"} {
		s, err := r.startMCPService(ctx, name, strings.Repeat("a", 32), strings.Repeat("b", 16))
		if err != nil {
			t.Fatal(err)
		}
		reader := bufio.NewReader(s.Stdout)
		if _, err := s.Stdin.Write([]byte("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"observation_probe\",\"arguments\":{\"mode\":\"success\"}}}\n")); err != nil {
			t.Fatal("write")
		}
		if _, err := reader.ReadBytes('\n'); err != nil {
			t.Fatal("response")
		}
		s.Stdin.Close()
		io.Copy(io.Discard, reader)
		summary, err := s.finish()
		if err != nil || summary.RuntimeExport.Accepted != 1 {
			t.Fatal("named service observation unavailable")
		}
	}
	if len(r.mcpSessions) != 0 {
		t.Fatal("session retained")
	}
	mu.Lock()
	count := len(*payloads)
	exported, _ := json.Marshal(payloads)
	mu.Unlock()
	if count != 2 || !bytes.Contains(exported, []byte("second-mcp")) || !bytes.Contains(exported, []byte("eap-synthetic-mcp")) {
		t.Fatal("service identity lost")
	}
	r.credentialExpiry = time.Now().Add(-time.Second)
	if s, err := r.startMCPService(ctx, "synthetic", strings.Repeat("a", 32), strings.Repeat("b", 16)); s != nil || err == nil {
		t.Fatal("expired identity launched registered command")
	}
}
