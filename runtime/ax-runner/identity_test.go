package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func taskIdentityFixture(t *testing.T) (*axRunner, ed25519.PrivateKey, string) {
	t.Helper()
	public, private, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(public)
	dir := t.TempDir()
	uid := "3fc7abbb-d480-49f7-baf1-e553050e464e"
	for name, value := range map[string]string{"atespace": "eap-ax", "name": "identity-task", "uid": uid} {
		if err := os.WriteFile(dir+"/"+name, []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
	}
	id, err := newRuntimeIdentity(base64.StdEncoding.EncodeToString(der), dir)
	if err != nil {
		t.Fatal(err)
	}
	r := &runner{identity: id, agentID: "50", taskKey: "identity-task", client: &http.Client{Timeout: time.Second}, maxTokens: 32}
	return &axRunner{engine: r, dir: t.TempDir(), key: r.taskKey}, private, uid
}
func signedIdentityRequest(signing ed25519.PrivateKey, value any) *http.Request {
	raw, _ := json.Marshal(value)
	wire, _ := json.Marshal(map[string]string{"payload": base64.RawURLEncoding.EncodeToString(raw), "signature": base64.RawURLEncoding.EncodeToString(ed25519.Sign(signing, raw))})
	return httptest.NewRequest("POST", "/", bytes.NewReader(wire))
}
func installTaskIdentity(t *testing.T, a *axRunner, private ed25519.PrivateKey, uid string) string {
	t.Helper()
	op := "5f65331b-ee14-4ce0-a128-af7a10a0f8a1"
	rec := httptest.NewRecorder()
	a.engine.identityChallenge(rec, signedIdentityRequest(private, map[string]any{"operation": op, "expiresAt": time.Now().Add(45 * time.Second).Unix(), "serial": 1}))
	if rec.Code != 200 {
		t.Fatal("challenge rejected", rec.Code)
	}
	var c map[string]string
	json.Unmarshal(rec.Body.Bytes(), &c)
	der, _ := base64.RawURLEncoding.DecodeString(c["publicKey"])
	public, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 32)
	rand.Read(buf)
	token := "eap_rt_" + base64.RawURLEncoding.EncodeToString(buf)
	cipher, err := rsa.EncryptOAEP(sha256.New(), rand.Reader, public.(*rsa.PublicKey), []byte(token), nil)
	if err != nil {
		t.Fatal(err)
	}
	body := map[string]any{"protocol": "EAP_IDENTITY_V1", "operation": op, "nonce": c["nonce"], "instanceKey": "eap-ax/identity-task", "agentId": "50", "actorUid": uid, "identityEpoch": "d7e2d3fb-88a1-4e51-b780-e149725e9f4e", "revision": "5f65331b-ee14-4ce0-a128-af7a10a0f8a1", "expiresAt": time.Now().Add(5 * time.Minute).Unix(), "ciphertext": base64.RawURLEncoding.EncodeToString(cipher), "serial": 1}
	rec = httptest.NewRecorder()
	a.engine.identityInstall(rec, signedIdentityRequest(private, body))
	if rec.Code != 200 {
		t.Fatal("install rejected", rec.Code)
	}
	var ack map[string]string
	json.Unmarshal(rec.Body.Bytes(), &ack)
	mac := hmac.New(sha256.New, []byte(token))
	mac.Write([]byte("eap-delivery-v1|" + op + "|" + c["nonce"] + "|eap-ax/identity-task|5f65331b-ee14-4ce0-a128-af7a10a0f8a1"))
	if ack["proof"] != base64.RawURLEncoding.EncodeToString(mac.Sum(nil)) || bytes.Contains(rec.Body.Bytes(), []byte(token)) {
		t.Fatal("invalid HMAC acknowledgement")
	}
	return token
}
func TestTaskIdentityRejectsMissingExpiredAndChangedUIDBeforeJournal(t *testing.T) {
	a, private, uid := taskIdentityFixture(t)
	payload := `{"taskKey":"identity-task","objective":"echo","mode":"ECHO"}`
	check := func(token string) {
		q := httptest.NewRequest("POST", "/eap/task", strings.NewReader(payload))
		q.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		a.start(w, q)
		if w.Code != 401 {
			t.Fatal("invalid identity accepted", w.Code)
		}
		if _, err := os.Stat(a.dir + "/started"); !os.IsNotExist(err) {
			t.Fatal("unauthorized task wrote business intent")
		}
	}
	check("synthetic-shared-credential")
	token := installTaskIdentity(t, a, private, uid)
	a.engine.mu.Lock()
	a.engine.credentialExpiry = time.Now().Add(-time.Second)
	a.engine.mu.Unlock()
	check(token)
	a.engine.mu.Lock()
	a.engine.credentialExpiry = time.Now().Add(time.Minute)
	a.engine.mu.Unlock()
	os.WriteFile(a.engine.identity.directory+"/uid", []byte("508f73ba-82e7-4324-a143-a0c3fce02ed0"), 0600)
	check(token)
}
func TestTaskIdentityUsesScopedModelAndTraceRoutes(t *testing.T) {
	a, private, uid := taskIdentityFixture(t)
	token := installTaskIdentity(t, a, private, uid)
	var models, traces atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if q.Header.Get("X-EAP-Runtime-Key") != "eap-ax/identity-task" || q.Header.Get("X-EAP-Runtime-Token") != token || q.Header.Get("X-EAP-Gateway-Token") != "" || q.Header.Get("X-EAP-Agent-Id") != "" || q.Header.Get("X-EAP-Observability-Token") != "" {
			t.Error("machine request did not use scoped identity")
		}
		switch q.URL.Path {
		case "/api/runtime-access/v1/chat/completions":
			models.Add(1)
			if q.Header.Get("X-EAP-Task-Key") != a.key {
				t.Error("task budget identity missing")
			}
			writeJSON(w, map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": "done"}}}})
		case "/api/runtime-access/v1/traces":
			traces.Add(1)
			w.WriteHeader(200)
		default:
			t.Error("shared machine route used")
			w.WriteHeader(404)
		}
	}))
	defer server.Close()
	a.engine.gateway = server.URL
	a.execute(axInput{TaskKey: a.key, Objective: "synthetic", Mode: "LLM"})
	if models.Load() != 1 || traces.Load() != 1 {
		t.Fatal("expected one scoped model and trace")
	}
	raw, err := os.ReadFile(a.dir + "/result.json")
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(raw, []byte(token)) {
		t.Fatal("credential persisted with receipt")
	}
	var receipt axReceipt
	json.Unmarshal(raw, &receipt)
	if receipt.Status != "SUCCEEDED" || receipt.Output != "done" {
		t.Fatal("model result not persisted")
	}
}
func TestTaskIdentityReinstallPreservesOriginalUnknownIntent(t *testing.T) {
	a, private, uid := taskIdentityFixture(t)
	token := installTaskIdentity(t, a, private, uid)
	in := axInput{TaskKey: a.key, Objective: "original", Mode: "LLM"}
	raw, _ := json.Marshal(in)
	sum := sha256.Sum256(raw)
	fingerprint := fmtHex(sum[:])
	os.WriteFile(a.dir+"/started", []byte(fingerprint), 0600)
	// A restarted process keeps the workspace, but starts with no memory token.
	a.engine.identity = &runtimeIdentity{trust: a.engine.identity.trust, directory: a.engine.identity.directory}
	a.engine.runtimeToken = ""
	a.engine.runtimeKey = ""
	a.engine.credentialSerial = 0
	a.engine.credentialEpoch = ""
	a.engine.credentialUID = ""
	a.engine.credentialExpiry = time.Time{}
	q := httptest.NewRequest("GET", "/eap/result", nil)
	q.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	a.result(w, q)
	if w.Code != 401 {
		t.Fatal("restart restored a shared token")
	}
	token = installTaskIdentity(t, a, private, uid)
	q = httptest.NewRequest("GET", "/eap/result", nil)
	q.Header.Set("Authorization", "Bearer "+token)
	w = httptest.NewRecorder()
	a.result(w, q)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "EXECUTION_UNKNOWN") {
		t.Fatal("original unknown result not preserved")
	}
	q = httptest.NewRequest("POST", "/eap/task", bytes.NewReader(raw))
	q.Header.Set("Authorization", "Bearer "+token)
	w = httptest.NewRecorder()
	a.start(w, q)
	if w.Code != 202 || a.running {
		t.Fatal("reinstall/replay re-executed original intent")
	}
}
func fmtHex(raw []byte) string {
	const digits = "0123456789abcdef"
	out := make([]byte, len(raw)*2)
	for i, b := range raw {
		out[i*2] = digits[b>>4]
		out[i*2+1] = digits[b&15]
	}
	return string(out)
}
func TestTaskModelUncertainResponseUsesCanonicalUnknown(t *testing.T) {
	a, private, uid := taskIdentityFixture(t)
	installTaskIdentity(t, a, private, uid)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if q.URL.Path == "/api/runtime-access/v1/chat/completions" {
			w.WriteHeader(503)
		} else {
			w.WriteHeader(200)
		}
	}))
	defer server.Close()
	a.engine.gateway = server.URL
	a.execute(axInput{TaskKey: a.key, Objective: "synthetic", Mode: "LLM"})
	raw, _ := os.ReadFile(a.dir + "/result.json")
	var receipt axReceipt
	json.Unmarshal(raw, &receipt)
	if receipt.Status != "EXECUTION_UNKNOWN" {
		t.Fatal("uncertain call lost canonical state")
	}
}
func TestTaskIdentityRejectsForgedAndLateControllerDelivery(t *testing.T) {
	a, private, uid := taskIdentityFixture(t)
	_, attacker, _ := ed25519.GenerateKey(rand.Reader)
	body := map[string]any{"operation": "5f65331b-ee14-4ce0-a128-af7a10a0f8a1", "expiresAt": time.Now().Add(45 * time.Second).Unix(), "serial": 1}
	w := httptest.NewRecorder()
	a.engine.identityChallenge(w, signedIdentityRequest(attacker, body))
	if w.Code != 401 || a.engine.identity.private != nil {
		t.Fatal("untrusted controller opened delivery")
	}
	token := installTaskIdentity(t, a, private, uid)
	a.engine.mu.Lock()
	a.engine.credentialSerial = 2
	a.engine.mu.Unlock()
	w = httptest.NewRecorder()
	a.engine.identityChallenge(w, signedIdentityRequest(private, body))
	if w.Code != 409 {
		t.Fatal("late signed delivery reopened old lease")
	}
	a.engine.mu.Lock()
	same := a.engine.runtimeToken == token
	a.engine.mu.Unlock()
	if !same {
		t.Fatal("late controller message changed current credential")
	}
}
func TestTaskIdentityRefusesMixedSharedConfiguration(t *testing.T) {
	public, _, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(public)
	t.Setenv("EAP_IDENTITY_MODE", "PER_INSTANCE_V1")
	t.Setenv("EAP_IDENTITY_PUBLIC_KEY", base64.StdEncoding.EncodeToString(der))
	t.Setenv("EAP_IDENTITY_DIR", "/run/eap-identity")
	r := &runner{taskToken: "synthetic-shared-value"}
	if r.configureIdentity() == nil || r.identity != nil {
		t.Fatal("shared task credential accepted with instance mode")
	}
	r.taskToken = ""
	t.Setenv("EAP_OBSERVABILITY_TOKEN", "synthetic-shared-value")
	if r.configureIdentity() == nil {
		t.Fatal("shared trace credential accepted")
	}
	t.Setenv("EAP_OBSERVABILITY_TOKEN", "")
	if err := r.configureIdentity(); err != nil || r.identity == nil {
		t.Fatal("public instance configuration rejected", err)
	}
}
