package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ed25519"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func mcpTestConfig(t *testing.T) mcpLaunchConfig {
	t.Helper()
	dir := os.Getenv("EAP_MCP_TEST_DIR")
	if dir == "" {
		t.Skip("remote Python integration fixture not configured")
	}
	python, err := exec.LookPath("python3")
	if err != nil {
		t.Fatal("remote Python unavailable")
	}
	return mcpLaunchConfig{Python: python, Relay: filepath.Join(dir, "relay.py"), Server: "eap-synthetic-mcp",
		Tools: []string{"observation_probe"}, Command: []string{python, filepath.Join(dir, "fixture_server.py")}, AllowHTTP: true}
}

func mcpInstall(t *testing.T, r *runner, signing ed25519.PrivateKey, uid string, serial int64) {
	t.Helper()
	operation := "5f65331b-ee14-4ce0-a128-af7a10a0f8a1"
	body := challengeBody(operation)
	body["serial"] = serial
	rec := httptest.NewRecorder()
	r.identityChallenge(rec, signedRequest(signing, body))
	if rec.Code != 200 {
		t.Fatal("challenge failed", rec.Code)
	}
	var challenge map[string]string
	if json.Unmarshal(rec.Body.Bytes(), &challenge) != nil {
		t.Fatal("challenge malformed")
	}
	token := "eap_rt_" + randomHex(22)[:43]
	install := installBody(t, r, challenge, uid, token)
	install["serial"] = serial
	rec = httptest.NewRecorder()
	r.identityInstall(rec, signedRequest(signing, install))
	if rec.Code != 200 {
		t.Fatal("install failed", rec.Code)
	}
	if bytes.Contains(rec.Body.Bytes(), []byte(token)) {
		t.Fatal("credential in receipt")
	}
}

func mcpReceiver(t *testing.T, r *runner) (*httptest.Server, *sync.Mutex, *[]map[string]any) {
	t.Helper()
	mu, payloads := &sync.Mutex{}, &[]map[string]any{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		r.mu.Lock()
		token, key := r.runtimeToken, r.runtimeKey
		r.mu.Unlock()
		if req.URL.Path != "/api/runtime-access/v1/traces" || req.Header.Get("X-EAP-Runtime-Key") != key ||
			req.Header.Get("X-EAP-Runtime-Token") != token || req.Header.Get("Authorization") != "" {
			http.Error(w, "unauthorized", 401)
			return
		}
		var body map[string]any
		if json.NewDecoder(req.Body).Decode(&body) != nil {
			http.Error(w, "bad", 400)
			return
		}
		mu.Lock()
		*payloads = append(*payloads, body)
		mu.Unlock()
		writeJSON(w, map[string]any{"success": true, "data": map[string]any{"spansScanned": 1, "traceSpansRecorded": 1,
			"mcpSpansRecorded": 1, "genAiSpansDetected": 0, "invocationsRecorded": 0, "warnings": []any{}}})
	}))
	return server, mu, payloads
}

func mcpAwait(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("bounded MCP condition not reached")
}

func TestMCPIdentityInstallRenewsRunningRelayWithoutExposingCredential(t *testing.T) {
	cfg := mcpTestConfig(t)
	r, signing, uid := identityFixture(t)
	mcpInstall(t, r, signing, uid, 1)
	server, mu, payloads := mcpReceiver(t, r)
	defer server.Close()
	r.gateway = server.URL
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	s, err := r.startObservedMCP(ctx, cfg, strings.Repeat("a", 32), strings.Repeat("b", 16))
	if err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(s.Stdout)
	for i := 1; i <= 2; i++ {
		if i == 2 {
			mcpInstall(t, r, signing, uid, 2)
		}
		body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": i, "method": "tools/call", "params": map[string]any{"name": "observation_probe", "arguments": map[string]any{"mode": "success"}}})
		if _, err := s.Stdin.Write(append(body, '\n')); err != nil {
			t.Fatal("protocol write failed")
		}
		if _, err := reader.ReadBytes('\n'); err != nil {
			t.Fatal("protocol response unavailable")
		}
		count := i
		mcpAwait(t, func() bool { mu.Lock(); defer mu.Unlock(); return len(*payloads) == count })
	}
	s.Stdin.Close()
	io.Copy(io.Discard, reader)
	summary, err := s.finish()
	if err != nil || summary.RuntimeExport.Accepted != 2 || summary.RuntimeExport.InvalidLeaseUpdates != 0 {
		t.Fatal("renewed observations not confirmed", err, summary)
	}
	if len(r.mcpSessions) != 0 {
		t.Fatal("finished session retained")
	}
	raw, _ := json.Marshal(payloads)
	r.mu.Lock()
	token := r.runtimeToken
	r.mu.Unlock()
	if bytes.Contains(raw, []byte(token)) || bytes.Contains(raw, []byte("arguments")) {
		t.Fatal("credential/body in telemetry")
	}
}

func TestMCPRefusesMissingExpiredOrChangedProjectedIdentity(t *testing.T) {
	cfg := mcpTestConfig(t)
	r, signing, uid := identityFixture(t)
	for index, stage := range []string{"missing", "expired", "changed"} {
		if stage != "missing" {
			mcpInstall(t, r, signing, uid, int64(index+1))
		}
		if stage == "expired" {
			r.credentialExpiry = time.Now().Add(-time.Second)
		}
		if stage == "changed" {
			os.WriteFile(r.identity.directory+"/uid", []byte("11111111-1111-4111-8111-111111111111"), 0600)
		}
		s, err := r.startObservedMCP(context.Background(), cfg, strings.Repeat("a", 32), strings.Repeat("b", 16))
		if err == nil || s != nil {
			t.Fatal("invalid identity launched a process", stage)
		}
	}
}

func TestMCPChangedUIDInvalidatesExistingPipeWithoutReattribution(t *testing.T) {
	cfg := mcpTestConfig(t)
	r, signing, uid := identityFixture(t)
	mcpInstall(t, r, signing, uid, 1)
	server, _, _ := mcpReceiver(t, r)
	defer server.Close()
	r.gateway = server.URL
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	s, err := r.startObservedMCP(ctx, cfg, strings.Repeat("a", 32), strings.Repeat("b", 16))
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(r.identity.directory+"/uid", []byte("11111111-1111-4111-8111-111111111111"), 0600)
	r.refreshMCPLeases()
	s.mu.Lock()
	closed := s.writer == nil
	s.mu.Unlock()
	if !closed {
		t.Fatal("changed identity retained credential pipe")
	}
	s.Stdin.Close()
	io.Copy(io.Discard, s.Stdout)
	_, err = s.finish()
	if err != nil {
		t.Fatal(err)
	}
}

func TestMCPCancelStopsRelayAndReleasesSession(t *testing.T) {
	cfg := mcpTestConfig(t)
	r, signing, uid := identityFixture(t)
	mcpInstall(t, r, signing, uid, 1)
	server, _, _ := mcpReceiver(t, r)
	defer server.Close()
	r.gateway = server.URL
	ctx, cancel := context.WithCancel(context.Background())
	s, err := r.startObservedMCP(ctx, cfg, strings.Repeat("a", 32), strings.Repeat("b", 16))
	if err != nil {
		t.Fatal(err)
	}
	cancel()
	done := make(chan error, 1)
	go func() { io.Copy(io.Discard, s.Stdout); _, e := s.finish(); done <- e }()
	select {
	case e := <-done:
		if e == nil {
			t.Fatal("canceled process gained success")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("cancel did not bound wait")
	}
	if len(r.mcpSessions) != 0 {
		t.Fatal("canceled session retained")
	}
}

func TestMCPBoundedPipeCannotBlockIdentityRefresh(t *testing.T) {
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer read.Close()
	lease := mcpLease{InstanceKey: "eap-agents/fixture", AgentID: "71", ActorUID: "uid", Token: strings.Repeat("x", 43), ExpiresAt: time.Now().Add(time.Minute).Unix()}
	s := &observedMCP{writer: write, pinned: lease}
	start := time.Now()
	for serial := int64(1); serial < 10000; serial++ {
		lease.Sequence = serial
		s.update(lease, true)
		if s.writer == nil {
			break
		}
	}
	if s.writer != nil {
		s.writer.Close()
		t.Fatal("full pipe not invalidated")
	}
	if time.Since(start) > time.Second {
		t.Fatal("identity refresh blocked")
	}
}
