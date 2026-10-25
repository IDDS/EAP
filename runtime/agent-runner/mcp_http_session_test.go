package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func mcpHTTPTestConfig(t *testing.T) mcpLaunchConfig {
	t.Helper()
	cfg := mcpTestConfig(t)
	dir := filepath.Dir(cfg.Relay)
	cfg.Relay = filepath.Join(dir, "http_actor_probe.py")
	cfg.Command = []string{cfg.Python, filepath.Join(dir, "http_actor_server.py")}
	cfg.Server, cfg.Observer = "eap-http-synthetic-mcp", "eap-mcp-http-observer"
	return cfg
}

func TestMCPHTTPHostUsesRenewedLeaseAndOriginalTaskTrace(t *testing.T) {
	cfg := mcpHTTPTestConfig(t)
	r, signing, uid := identityFixture(t)
	mcpInstall(t, r, signing, uid, 1)
	server, mu, payloads := mcpReceiver(t, r)
	defer server.Close()
	r.gateway = server.URL
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	session, err := r.startObservedMCP(ctx, cfg, strings.Repeat("a", 32), strings.Repeat("b", 16))
	if err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(session.Stdout)
	line, err := reader.ReadBytes('\n')
	if err != nil || !bytes.Contains(line, []byte(`"READY"`)) {
		t.Fatal("HTTP host did not become ready")
	}
	// The child HTTP service is already running. Existing identity installation
	// refreshes the same pipe before its first request, not a new host process.
	mcpInstall(t, r, signing, uid, 2)
	io.WriteString(session.Stdin, `{"version":"EAP_MCP_HTTP_PROBE_V1","minimumCredentialSequence":2}`)
	session.Stdin.Close()
	proof, readErr := io.ReadAll(io.LimitReader(reader, 4097))
	summary, err := session.finish()
	if readErr != nil || err != nil || len(proof) > 4096 || summary.Observer != cfg.Observer ||
		summary.RuntimeExport.Accepted != 6 || summary.RuntimeExport.Submitted != 6 ||
		summary.RuntimeExport.Rejected+summary.RuntimeExport.Unknown+summary.RuntimeExport.InvalidLeaseUpdates != 0 {
		t.Fatal("HTTP host observations not confirmed", err, summary)
	}
	var receipt map[string]any
	if json.Unmarshal(proof, &receipt) != nil || receipt["httpRequests"] != float64(8) ||
		receipt["credentialChannelsAbsent"] != true {
		t.Fatal("synthetic HTTP receipt invalid")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(*payloads) != 6 || len(r.mcpSessions) != 0 {
		t.Fatal("unexpected HTTP observations or retained session")
	}
	counts := map[string]int{}
	for _, payload := range *payloads {
		rs := payload["resourceSpans"].([]any)[0].(map[string]any)
		span := rs["scopeSpans"].([]any)[0].(map[string]any)["spans"].([]any)[0].(map[string]any)
		if span["traceId"] != strings.Repeat("a", 32) || span["parentSpanId"] != strings.Repeat("b", 16) {
			t.Fatal("HTTP task association lost")
		}
		for _, attr := range span["attributes"].([]any) {
			a := attr.(map[string]any)
			if a["key"] == "eap.observation.outcome" {
				counts[a["value"].(map[string]any)["stringValue"].(string)]++
			}
		}
	}
	if counts["SUCCEEDED"] != 2 || counts["FAILED"] != 2 || counts["UNKNOWN"] != 2 {
		t.Fatal("HTTP versions or outcomes missing", counts)
	}
	raw, _ := json.Marshal(payloads)
	if bytes.Contains(raw, []byte(r.runtimeToken)) || bytes.Contains(raw, []byte("PRIVATE")) ||
		!bytes.Contains(raw, []byte("eap-mcp-http-observer")) {
		t.Fatal("HTTP telemetry contract or credential separation failed")
	}
}

func TestMCPHTTPRejectsInvalidObserverAndUninstalledIdentity(t *testing.T) {
	cfg := mcpHTTPTestConfig(t)
	r, signing, uid := identityFixture(t)
	if s, err := r.startObservedMCP(context.Background(), cfg, strings.Repeat("a", 32), strings.Repeat("b", 16)); s != nil || err == nil {
		t.Fatal("HTTP host started without installed identity")
	}
	mcpInstall(t, r, signing, uid, 1)
	cfg.Observer = "untrusted-observer"
	if s, err := r.startObservedMCP(context.Background(), cfg, strings.Repeat("a", 32), strings.Repeat("b", 16)); s != nil || err == nil {
		t.Fatal("unrecognized observer receipt accepted")
	}
}

func TestMCPHTTPCancelBeforeDispatchReleasesHost(t *testing.T) {
	cfg := mcpHTTPTestConfig(t)
	r, signing, uid := identityFixture(t)
	mcpInstall(t, r, signing, uid, 1)
	server, mu, payloads := mcpReceiver(t, r)
	defer server.Close()
	r.gateway = server.URL
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	session, err := r.startObservedMCP(ctx, cfg, strings.Repeat("a", 32), strings.Repeat("b", 16))
	if err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(session.Stdout)
	if _, err := reader.ReadBytes('\n'); err != nil {
		t.Fatal("HTTP host not ready")
	}
	cancel()
	done := make(chan error, 1)
	go func() { io.Copy(io.Discard, reader); _, err := session.finish(); done <- err }()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled HTTP host claimed success")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("HTTP host cancellation exceeded bound")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(r.mcpSessions) != 0 || len(*payloads) != 0 {
		t.Fatal("cancelled host retained session or dispatched observations")
	}
}
