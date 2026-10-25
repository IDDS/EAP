//go:build mcphttpfixture && !mcpfixture && !kagent

package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestMCPHTTPFixtureRequiresIdentityAndPreservesUnknownIntent(t *testing.T) {
	r, signing, uid := identityFixture(t)
	r.toolDir = t.TempDir()
	mcpInstall(t, r, signing, uid, 1)
	raw := `{"taskKey":"http-original","traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","objective":"ignored"}`
	call := func(auth bool) *httptest.ResponseRecorder {
		req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(raw))
		if auth {
			req.Header.Set("Authorization", "Bearer "+r.runtimeToken)
		}
		rec := httptest.NewRecorder()
		r.mcpHTTPFixtureTask(rec, req)
		return rec
	}
	if call(false).Code != 401 {
		t.Fatal("anonymous HTTP task accepted")
	}
	dir := filepath.Join(r.toolDir, "mcp-http-fixture-v1")
	if os.MkdirAll(dir, 0700) != nil {
		t.Fatal("journal directory")
	}
	path := filepath.Join(dir, digest([]byte(r.runtimeKey+"|"+uid+"|http-original"))+".json")
	intent := mcpHTTPReceipt{TaskKey: "http-original", TraceID: strings.Repeat("a", 32),
		Status: "UNKNOWN", ReasonCode: "MCP_HTTP_FIXTURE_UNCONFIRMED", Fingerprint: digest([]byte(raw))}
	if savePrivateJournal(path, intent) != nil {
		t.Fatal("persist intent")
	}
	before, _ := os.ReadFile(path)
	// A fresh runner object with the same projection and journal cannot replay
	// the original unknown task after new credential delivery.
	fresh := &runner{agentID: r.agentID, toolDir: r.toolDir,
		identity: &runtimeIdentity{trust: r.identity.trust, directory: r.identity.directory}}
	mcpInstall(t, fresh, signing, uid, 2)
	r = fresh
	var saved mcpHTTPReceipt
	rec := call(true)
	after, _ := os.ReadFile(path)
	if rec.Code != 200 || json.Unmarshal(rec.Body.Bytes(), &saved) != nil ||
		saved.Status != "UNKNOWN" || string(before) != string(after) || len(r.mcpSessions) != 0 {
		t.Fatal("unknown HTTP intent was replayed or altered")
	}
	raw = strings.Replace(raw, "ignored", "changed", 1)
	if call(true).Code != 409 {
		t.Fatal("conflicting HTTP intent accepted")
	}
}

func TestMCPHTTPFixtureProofRejectsMissingConflictingOrExtraEvidence(t *testing.T) {
	good := `{"version":"EAP_MCP_HTTP_PROBE_V1","transport":"STREAMABLE_HTTP","synthetic":true,"protocolVersions":["2025-11-25","2026-07-28"],"protocolOutcomes":["SUCCEEDED","FAILED","UNKNOWN","SUCCEEDED","FAILED","UNKNOWN"],"httpRequests":8,"credentialChannelsAbsent":true}`
	if !validMCPHTTPProof([]byte(good)) {
		t.Fatal("valid HTTP proof rejected")
	}
	for _, bad := range []string{
		strings.Replace(good, `"httpRequests":8`, `"httpRequests":9`, 1),
		strings.Replace(good, `"UNKNOWN"`, `"SUCCEEDED"`, 1),
		strings.Replace(good, `"synthetic":true`, `"synthetic":true,"synthetic":true`, 1),
		strings.Replace(good, `"transport":"STREAMABLE_HTTP"`, `"Transport":"STREAMABLE_HTTP"`, 1),
		strings.Replace(good, `"credentialChannelsAbsent":true`, `"credentialChannelsAbsent":false`, 1),
		good + `{}`,
	} {
		if validMCPHTTPProof([]byte(bad)) {
			t.Fatal("invalid HTTP proof accepted")
		}
	}
}

func TestMCPHTTPFixtureTaskExportsSixStatesAndReusesReceipt(t *testing.T) {
	mcpHTTPTestConfig(t)
	r, signing, uid := identityFixture(t)
	r.toolDir = t.TempDir()
	mcpInstall(t, r, signing, uid, 1)
	server, mu, payloads := mcpReceiver(t, r)
	defer server.Close()
	r.gateway, r.client = server.URL, server.Client()
	raw := `{"taskKey":"http-new-task","traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","objective":"ignored"}`
	call := func() *httptest.ResponseRecorder {
		req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(raw))
		req.Header.Set("Authorization", "Bearer "+r.runtimeToken)
		rec := httptest.NewRecorder()
		r.mcpHTTPFixtureTask(rec, req)
		return rec
	}
	first := call()
	var receipt mcpHTTPReceipt
	if first.Code != 200 || json.Unmarshal(first.Body.Bytes(), &receipt) != nil || receipt.Status != "SUCCEEDED" {
		t.Fatal("HTTP task unconfirmed", first.Code, first.Body.String())
	}
	if receipt.Diagnostic == nil || receipt.Diagnostic.Stage != "COMPLETED" || receipt.Diagnostic.DeadlineExceeded {
		t.Fatal("HTTP phase evidence missing")
	}
	var result struct {
		Observation mcpRelaySummary `json:"observation"`
	}
	if json.Unmarshal(receipt.Output, &result) != nil || result.Observation.HTTPStartup == nil || !result.Observation.HTTPStartup.valid() {
		t.Fatal("HTTP startup phase evidence missing or invalid")
	}
	readyRaw, _ := json.Marshal(receipt.Diagnostic.Startup)
	summaryRaw, _ := json.Marshal(result.Observation.HTTPStartup)
	if string(readyRaw) != string(summaryRaw) {
		t.Fatal("READY startup lost or changed in success receipt")
	}
	if second := call(); second.Body.String() != first.Body.String() {
		t.Fatal("HTTP receipt changed")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(*payloads) != 7 {
		t.Fatal("expected six MCP spans and one parent; replay must not dispatch", len(*payloads))
	}
	parent, parents := "", []string{}
	for _, payload := range *payloads {
		span := payload["resourceSpans"].([]any)[0].(map[string]any)["scopeSpans"].([]any)[0].(map[string]any)["spans"].([]any)[0].(map[string]any)
		if span["name"] == "agent.task" {
			parent = span["spanId"].(string)
		} else {
			parents = append(parents, span["parentSpanId"].(string))
		}
	}
	if parent == "" || len(parents) != 6 {
		t.Fatal("HTTP parent missing")
	}
	for _, id := range parents {
		if id != parent {
			t.Fatal("HTTP span attached to wrong task")
		}
	}
}

func TestMCPHTTPDiagnosticPinsFailurePhaseAndCleansProcesses(t *testing.T) {
	ready := `print('{"version":"EAP_MCP_HTTP_PROBE_V1","phase":"READY","startup":` + mcpReadyStartupTest + `}', flush=True)` + "\n"
	proof := `{"version":"EAP_MCP_HTTP_PROBE_V1","transport":"STREAMABLE_HTTP","synthetic":true,"protocolVersions":["2025-11-25","2026-07-28"],"protocolOutcomes":["SUCCEEDED","FAILED","UNKNOWN","SUCCEEDED","FAILED","UNKNOWN"],"httpRequests":8,"credentialChannelsAbsent":true}`
	summary := `{"observer":"eap-mcp-http-observer","degraded":false,"runtimeExport":{"submitted":6,"accepted":6,"workerStopped":true}}`
	for _, tc := range []struct {
		name, code, stage string
		deadline          bool
	}{
		{"ready-timeout", "import time\ntime.sleep(60)\n", "WAIT_READY", true},
		{"invalid-ready", "print('PRIVATE', flush=True)\n", "WAIT_READY", false},
		{"proof-timeout", "import sys,time\n" + ready + "sys.stdin.buffer.read()\ntime.sleep(60)\n", "READ_PROOF", true},
		{"exit-failure", "import sys\n" + ready + "sys.stdin.buffer.read()\nprint('PRIVATE',file=sys.stderr)\nsys.exit(1)\n", "WAIT_EXIT", false},
		{"invalid-proof", "import sys\n" + ready + "sys.stdin.buffer.read()\nprint('{}')\nprint('" + summary + "',file=sys.stderr)\n", "VALIDATE_PROOF", false},
		{"export-unconfirmed", "import sys\n" + ready + "sys.stdin.buffer.read()\nprint('" + proof + "')\nprint('" + strings.Replace(summary, `"accepted":6`, `"accepted":0`, 1) + "',file=sys.stderr)\n", "VERIFY_EXPORT", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg := mcpHTTPTestConfig(t)
			r, signing, uid := identityFixture(t)
			mcpInstall(t, r, signing, uid, 1)
			r.gateway = "http://127.0.0.1:1"
			cfg.Relay = filepath.Join(t.TempDir(), "probe.py")
			if os.WriteFile(cfg.Relay, []byte(tc.code), 0600) != nil {
				t.Fatal("write trusted probe")
			}
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			start := time.Now()
			result, _, diagnostic, err := r.runMCPHTTPProbe(ctx, cfg, strings.Repeat("a", 32), strings.Repeat("b", 16), 1)
			if err == nil || result != nil || diagnostic.Stage != tc.stage || diagnostic.DeadlineExceeded != tc.deadline {
				t.Fatal("wrong diagnostic", diagnostic, err)
			}
			if time.Since(start) > 5*time.Second || len(r.mcpSessions) != 0 {
				t.Fatal("bounded subprocess cleanup failed")
			}
			raw, _ := json.Marshal(diagnostic.attributes())
			if strings.Contains(string(raw), "PRIVATE") || strings.Contains(string(raw), r.runtimeToken) {
				t.Fatal("private diagnostic content")
			}
			if tc.stage != "WAIT_READY" && diagnostic.attributes()["eap.runtime.startup.version"] != "1" {
				t.Fatal("READY evidence discarded by later failure", diagnostic)
			}
			if tc.stage == "WAIT_READY" && strings.Contains(string(raw), "startup") {
				t.Fatal("invented startup")
			}
			if diagnostic.StartMS < 0 || diagnostic.ReadyMS < 0 || diagnostic.ControlMS < 0 || diagnostic.ProofMS < 0 || diagnostic.ExitMS < 0 {
				t.Fatal("negative phase duration")
			}
		})
	}
}

func TestMCPHTTPDiagnosticRecordsObserverStartFailure(t *testing.T) {
	cfg := mcpHTTPTestConfig(t)
	r, signing, uid := identityFixture(t)
	mcpInstall(t, r, signing, uid, 1)
	cfg.Python = filepath.Join(t.TempDir(), "missing-python")
	_, _, d, err := r.runMCPHTTPProbe(context.Background(), cfg, strings.Repeat("a", 32), strings.Repeat("b", 16), 1)
	if err == nil || d.Stage != "START_OBSERVER" || d.DeadlineExceeded || len(r.mcpSessions) != 0 {
		t.Fatal("start diagnostic", d)
	}
}

func TestMCPHTTPStartupSummaryIsBounded(t *testing.T) {
	good := `{"version":1,"observerImportMs":3,"identityReadyMs":0,"exporterStartMs":0,"serverSpawnMs":1,"serverReadyWaitMs":8,"observerBeforeReadyMs":12,"serverImportMs":2,"serverBindMs":4,"serverFqdnMs":3,"serverBeforeReadyMs":7}`
	var original mcpHTTPStartup
	if json.Unmarshal([]byte(good), &original) != nil || !original.valid() || !mcpHTTPStartup(nil).valid() {
		t.Fatal("valid or legacy startup rejected")
	}
	for _, bad := range []string{
		`{}`, strings.Replace(good, `"version":1`, `"version":2`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":-1`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":true`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":0.5`, 1),
		strings.Replace(good, `"observerBeforeReadyMs":12`, `"observerBeforeReadyMs":60001`, 1),
		strings.Replace(good, `"serverFqdnMs":3`, `"serverFqdnMs":5`, 1),
		strings.Replace(good, `"observerImportMs":3`, `"observerImportMs":13`, 1),
		strings.Replace(good, `"serverImportMs":2`, `"serverImportMs":8`, 1),
		strings.Replace(good, `"version":1`, `"version":1,"private":"SYNTHETIC_PRIVATE"`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"private":0`, 1),
	} {
		var candidate mcpHTTPStartup
		if json.Unmarshal([]byte(bad), &candidate) == nil && candidate.valid() {
			t.Fatal("invalid startup accepted")
		}
	}
}

const mcpReadyStartupTest = `{"version":1,"observerImportMs":3,"identityReadyMs":0,"exporterStartMs":0,"serverSpawnMs":1,"serverReadyWaitMs":8,"observerBeforeReadyMs":12,"serverImportMs":2,"serverBindMs":4,"serverFqdnMs":3,"serverBeforeReadyMs":7}`

func TestMCPHTTPReadyStartupSurvivesProofTimeout(t *testing.T) {
	cfg := mcpHTTPTestConfig(t)
	r, signing, uid := identityFixture(t)
	mcpInstall(t, r, signing, uid, 1)
	r.gateway = "http://127.0.0.1:1"
	cfg.Relay = filepath.Join(t.TempDir(), "probe.py")
	code := "import sys,time\nprint('" + `{"version":"EAP_MCP_HTTP_PROBE_V1","phase":"READY","startup":` + mcpReadyStartupTest + "}',flush=True)\nsys.stdin.buffer.read()\ntime.sleep(60)\n"
	if os.WriteFile(cfg.Relay, []byte(code), 0600) != nil {
		t.Fatal("write trusted probe")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	result, _, d, err := r.runMCPHTTPProbe(ctx, cfg, strings.Repeat("a", 32), strings.Repeat("b", 16), 1)
	if err == nil || result != nil || d.Stage != "READ_PROOF" || !d.DeadlineExceeded {
		t.Fatal("expected original proof timeout", d, err)
	}
	attrs := d.attributes()
	if attrs["eap.runtime.startup.version"] != "1" || attrs["eap.runtime.startup.observer_import_ms"] != "3" || attrs["eap.runtime.startup.server_import_ms"] != "2" {
		t.Fatal("READY startup missing after timeout", attrs)
	}
	// Save and reload the original UNKNOWN receipt; no successful summary exists.
	path := filepath.Join(t.TempDir(), "receipt.json")
	original := mcpHTTPReceipt{TaskKey: "original-timeout", Status: "UNKNOWN", Diagnostic: &d}
	if savePrivateJournal(path, original) != nil {
		t.Fatal("save original diagnostic")
	}
	raw, err := os.ReadFile(path)
	var saved mcpHTTPReceipt
	if err != nil || json.Unmarshal(raw, &saved) != nil || saved.Diagnostic == nil || saved.Status != "UNKNOWN" || saved.Diagnostic.attributes()["eap.runtime.startup.observer_import_ms"] != "3" {
		t.Fatal("startup missing from durable UNKNOWN receipt")
	}
	if len(r.mcpSessions) != 0 {
		t.Fatal("observer session leaked")
	}
}

func TestMCPHTTPReadyStrictlyBoundsOptionalStartup(t *testing.T) {
	legacy := `{"version":"EAP_MCP_HTTP_PROBE_V1","phase":"READY"}`
	good := strings.TrimSuffix(legacy, "}") + `,"startup":` + mcpReadyStartupTest + `}`
	for _, raw := range []string{legacy, good} {
		startup, ok := parseMCPHTTPReady([]byte(raw))
		if !ok || (raw == legacy && startup != nil) || (raw == good && startup == nil) {
			t.Fatal("valid READY rejected")
		}
	}
	for _, bad := range []string{
		good + `{}`, strings.Replace(good, `"READY"`, `"READY","phase":"READY"`, 1),
		strings.Replace(good, `"phase":`, `"Phase":`, 1),
		strings.Replace(good, `"startup":`+mcpReadyStartupTest, `"startup":null`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":null`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":true`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":0.5`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":-1`, 1),
		strings.Replace(good, `"observerBeforeReadyMs":12`, `"observerBeforeReadyMs":60001`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":0,"identityReadyMs":0`, 1),
		strings.Replace(good, `"phase":"READY"`, `"phase":"READY","private":"PRIVATE"`, 1),
		strings.Replace(good, `"identityReadyMs":0`, `"identityReadyMs":0,"private":0`, 1),
		strings.Replace(good, `"serverFqdnMs":3`, `"serverFqdnMs":5`, 1),
	} {
		if startup, ok := parseMCPHTTPReady([]byte(bad)); ok || startup != nil {
			t.Fatal("invalid READY accepted")
		}
	}
}
