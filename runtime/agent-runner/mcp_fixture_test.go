//go:build mcpfixture && !kagent

package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestMCPFixtureTaskRequiresIdentityAndPreservesUnknownIntent(t *testing.T) {
	r, signing, uid := identityFixture(t)
	r.toolDir = t.TempDir()
	mcpInstall(t, r, signing, uid, 1)
	raw := `{"taskKey":"fixture-original","traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","objective":"unused"}`
	request := func(auth bool) *httptest.ResponseRecorder {
		req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(raw))
		if auth {
			req.Header.Set("Authorization", "Bearer "+r.runtimeToken)
		}
		rec := httptest.NewRecorder()
		r.mcpFixtureTask(rec, req)
		return rec
	}
	if request(false).Code != 401 {
		t.Fatal("anonymous fixture accepted")
	}
	dir := filepath.Join(r.toolDir, "mcp-fixture-v1")
	os.MkdirAll(dir, 0700)
	path := filepath.Join(dir, digest([]byte(r.runtimeKey+"|"+uid+"|fixture-original"))+".json")
	intent := mcpFixtureReceipt{TaskKey: "fixture-original", TraceID: strings.Repeat("a", 32), Status: "UNKNOWN",
		ReasonCode: "MCP_FIXTURE_UNCONFIRMED", Fingerprint: digest([]byte(raw))}
	if savePrivateJournal(path, intent) != nil {
		t.Fatal("intent")
	}
	rec := request(true)
	var result mcpFixtureReceipt
	if rec.Code != 200 || json.Unmarshal(rec.Body.Bytes(), &result) != nil || result.Status != "UNKNOWN" {
		t.Fatal("old intent replayed or changed")
	}
	raw = strings.Replace(raw, "unused", "changed", 1)
	if request(true).Code != 409 {
		t.Fatal("changed intent accepted")
	}
}

func TestMCPFixtureInitializationAndResponsesAreValidated(t *testing.T) {
	good := []byte("{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"result\":{\"protocolVersion\":\"2025-06-18\"}}\n" +
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[],\"isError\":false}}\n" +
		"{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":true}}\n")
	if !validMCPFixtureReplies(good) {
		t.Fatal("valid fixture rejected")
	}
	for _, bad := range []string{
		strings.Replace(string(good), "2025-06-18", "unknown", 1),
		strings.Replace(string(good), `"isError":true`, `"isError":false`, 1),
		string(good) + `{"jsonrpc":"2.0","id":3,"result":{"content":[]}}`,
		strings.Replace(string(good), `"content":[]`, `"content":null`, 1),
	} {
		if validMCPFixtureReplies([]byte(bad)) {
			t.Fatal("invalid fixture accepted")
		}
	}
}

func TestMCPFixtureProcessRestartPreservesUnknownIntentWithoutLaunchingService(t *testing.T) {
	original, signing, uid := identityFixture(t)
	original.toolDir = t.TempDir()
	mcpInstall(t, original, signing, uid, 1)
	raw := `{"taskKey":"restart-original","traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","objective":"synthetic"}`
	dir := filepath.Join(original.toolDir, "mcp-fixture-v1")
	if os.MkdirAll(dir, 0700) != nil {
		t.Fatal("journal directory")
	}
	path := filepath.Join(dir, digest([]byte(original.runtimeKey+"|"+uid+"|restart-original"))+".json")
	intent := mcpFixtureReceipt{TaskKey: "restart-original", TraceID: strings.Repeat("a", 32),
		Status: "UNKNOWN", ReasonCode: "MCP_FIXTURE_UNCONFIRMED", Fingerprint: digest([]byte(raw))}
	if savePrivateJournal(path, intent) != nil {
		t.Fatal("persist original intent")
	}
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("read original intent")
	}
	fresh := &runner{agentID: original.agentID, toolDir: original.toolDir,
		identity: &runtimeIdentity{trust: original.identity.trust, directory: original.identity.directory}}
	mcpInstall(t, fresh, signing, uid, 2)
	request := httptest.NewRequest("POST", "/eap/task", strings.NewReader(raw))
	request.Header.Set("Authorization", "Bearer "+fresh.runtimeToken)
	rec := httptest.NewRecorder()
	fresh.mcpFixtureTask(rec, request)
	var receipt mcpFixtureReceipt
	if rec.Code != 200 || json.Unmarshal(rec.Body.Bytes(), &receipt) != nil || !reflect.DeepEqual(receipt, intent) {
		t.Fatal("restart did not preserve the original unknown receipt")
	}
	after, err := os.ReadFile(path)
	if err != nil || string(after) != string(before) || len(fresh.mcpSessions) != 0 {
		t.Fatal("restart changed the intent or launched a service")
	}
}

func TestMCPFixtureTaskExportsThreeStatesAndReusesDurableReceipt(t *testing.T) {
	mcpTestConfig(t) // Require remote fixture setup; task uses the image's fixed paths.
	r, signing, uid := identityFixture(t)
	var err error
	r.mcpRegistry, err = loadMCPRegistry(filepath.Join(os.Getenv("EAP_MCP_TEST_DIR"), "services.fixture.json"))
	if err != nil {
		t.Fatal("fixture manifest unavailable")
	}
	r.toolDir = t.TempDir()
	mcpInstall(t, r, signing, uid, 1)
	server, mu, payloads := mcpReceiver(t, r)
	defer server.Close()
	r.gateway = server.URL
	r.client = server.Client()
	raw := `{"taskKey":"fixture-run","traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","objective":"ignored"}`
	request := func() *httptest.ResponseRecorder {
		req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(raw))
		req.Header.Set("Authorization", "Bearer "+r.runtimeToken)
		rec := httptest.NewRecorder()
		r.mcpFixtureTask(rec, req)
		return rec
	}
	first := request()
	var result mcpFixtureReceipt
	if first.Code != 200 || json.Unmarshal(first.Body.Bytes(), &result) != nil || result.Status != "SUCCEEDED" {
		t.Fatal("fixture task not confirmed", first.Code, first.Body.String())
	}
	mu.Lock()
	count := len(*payloads)
	mu.Unlock()
	if count != 4 {
		t.Fatal("expected three MCP spans and their agent parent", count)
	}
	second := request()
	if second.Body.String() != first.Body.String() {
		t.Fatal("cached receipt changed")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(*payloads) != count {
		t.Fatal("receipt replay exported or executed again")
	}
	var parent string
	parents := []string{}
	for _, payload := range *payloads {
		span := payload["resourceSpans"].([]any)[0].(map[string]any)["scopeSpans"].([]any)[0].(map[string]any)["spans"].([]any)[0].(map[string]any)
		if span["name"] == "agent.task" {
			parent = span["spanId"].(string)
		} else {
			parents = append(parents, span["parentSpanId"].(string))
		}
	}
	if parent == "" || len(parents) != 3 {
		t.Fatal("missing parent relationship")
	}
	for _, p := range parents {
		if p != parent {
			t.Fatal("MCP parent does not match original task span")
		}
	}
}
