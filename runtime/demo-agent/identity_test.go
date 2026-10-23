package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"
)

func identityFixture(t *testing.T) (*runner, ed25519.PrivateKey, string) {
	t.Helper()
	public, private, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(public)
	dir := t.TempDir()
	uid := "3fc7abbb-d480-49f7-baf1-e553050e464e"
	for name, value := range map[string]string{"atespace": "eap-agents", "name": "identity-test", "uid": uid} {
		if e := os.WriteFile(dir+"/"+name, []byte(value), 0600); e != nil {
			t.Fatal(e)
		}
	}
	id, e := newRuntimeIdentity(base64.StdEncoding.EncodeToString(der), dir)
	if e != nil {
		t.Fatal(e)
	}
	mu.Lock()
	handled = map[string]bool{"existing-task": true}
	count = 1
	lastObjective = "public existing objective"
	mu.Unlock()
	return &runner{identity: id, agentID: "71"}, private, uid
}
func signedRequest(private ed25519.PrivateKey, body any) *http.Request {
	raw, _ := json.Marshal(body)
	wire, _ := json.Marshal(map[string]any{"payload": base64.RawURLEncoding.EncodeToString(raw), "signature": base64.RawURLEncoding.EncodeToString(ed25519.Sign(private, raw))})
	return httptest.NewRequest("POST", "/", bytes.NewReader(wire))
}
func challengeBody(operation string) map[string]any {
	return map[string]any{"operation": operation, "serial": int64(1), "expiresAt": time.Now().Add(45 * time.Second).Unix()}
}
func installBody(t *testing.T, r *runner, challenge map[string]string, uid, token string) map[string]any {
	t.Helper()
	raw, _ := base64.RawURLEncoding.DecodeString(challenge["publicKey"])
	public, e := x509.ParsePKIXPublicKey(raw)
	if e != nil {
		t.Fatal(e)
	}
	cipher, e := rsa.EncryptOAEP(sha256.New(), rand.Reader, public.(*rsa.PublicKey), []byte(token), nil)
	if e != nil {
		t.Fatal(e)
	}
	return map[string]any{"protocol": "EAP_IDENTITY_V1", "serial": int64(1), "operation": challenge["operation"], "nonce": challenge["nonce"], "instanceKey": "eap-agents/identity-test", "agentId": "71", "actorUid": uid, "identityEpoch": "d7e2d3fb-88a1-4e51-b780-e149725e9f4e", "revision": "5f65331b-ee14-4ce0-a128-af7a10a0f8a1", "expiresAt": time.Now().Add(time.Minute * 5).Unix(), "ciphertext": base64.RawURLEncoding.EncodeToString(cipher)}
}
func TestIdentityEncryptedInstallKeepsBusinessJournalAndExpires(t *testing.T) {
	r, signing, uid := identityFixture(t)
	operation := "5f65331b-ee14-4ce0-a128-af7a10a0f8a1"
	rec := httptest.NewRecorder()
	r.identityChallenge(rec, signedRequest(signing, challengeBody(operation)))
	if rec.Code != 200 {
		t.Fatal(rec.Code)
	}
	var challenge map[string]string
	if e := json.Unmarshal(rec.Body.Bytes(), &challenge); e != nil {
		t.Fatal(e)
	}
	token := "eap_rt_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
	body := installBody(t, r, challenge, uid, token)
	rec = httptest.NewRecorder()
	r.identityInstall(rec, signedRequest(signing, body))
	if rec.Code != 200 || r.runtimeToken != token {
		t.Fatal("encrypted install did not succeed")
	}
	if bytes.Contains(rec.Body.Bytes(), []byte(token)) {
		t.Fatal("token in response")
	}
	if !handled["existing-task"] || count != 1 || lastObjective != "public existing objective" {
		t.Fatal("business journal changed")
	}
	req := httptest.NewRequest("POST", "/", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	if !r.authorized(req) {
		t.Fatal("fresh lease rejected")
	}
	r.mu.Lock()
	r.credentialExpiry = time.Now().Add(-time.Second)
	r.mu.Unlock()
	if r.authorized(req) {
		t.Fatal("expired lease accepted")
	}
}
func TestIdentityRejectsUnsignedForgedForeignAndReplayToAnotherUid(t *testing.T) {
	r, signing, uid := identityFixture(t)
	_, attacker, _ := ed25519.GenerateKey(rand.Reader)
	operation := "5f65331b-ee14-4ce0-a128-af7a10a0f8a1"
	rec := httptest.NewRecorder()
	r.identityChallenge(rec, signedRequest(attacker, challengeBody(operation)))
	if rec.Code != 401 || r.identity.private != nil {
		t.Fatal("forged controller allowed")
	}
	rec = httptest.NewRecorder()
	r.identityChallenge(rec, signedRequest(signing, challengeBody(operation)))
	var challenge map[string]string
	json.Unmarshal(rec.Body.Bytes(), &challenge)
	body := installBody(t, r, challenge, uid, "eap_rt_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
	body["agentId"] = "72"
	rec = httptest.NewRecorder()
	r.identityInstall(rec, signedRequest(signing, body))
	if rec.Code != 409 {
		t.Fatal("foreign agent accepted")
	}
	body["agentId"] = "71"
	os.WriteFile(r.identity.directory+"/uid", []byte("508f73ba-82e7-4324-a143-a0c3fce02ed0"), 0600)
	rec = httptest.NewRecorder()
	r.identityInstall(rec, signedRequest(signing, body))
	if rec.Code != 409 || r.runtimeToken != "" {
		t.Fatal("old projection replay accepted")
	}
}
func TestIdentityLateOldControllerMessageCannotRollbackNewLease(t *testing.T) {
	r, signing, uid := identityFixture(t)
	op := "5f65331b-ee14-4ce0-a128-af7a10a0f8a1"
	rec := httptest.NewRecorder()
	r.identityChallenge(rec, signedRequest(signing, challengeBody(op)))
	var challenge map[string]string
	json.Unmarshal(rec.Body.Bytes(), &challenge)
	old := installBody(t, r, challenge, uid, "eap_rt_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
	r.mu.Lock()
	r.credentialSerial = 2
	r.credentialEpoch = "new-epoch"
	r.runtimeToken = "new-current-credential"
	r.mu.Unlock()
	rec = httptest.NewRecorder()
	r.identityInstall(rec, signedRequest(signing, old))
	if rec.Code != 409 || r.runtimeToken != "new-current-credential" {
		t.Fatal("late delivery rolled back a new credential")
	}
	rec = httptest.NewRecorder()
	r.identityChallenge(rec, signedRequest(signing, challengeBody(op)))
	if rec.Code != 409 {
		t.Fatal("old signed challenge reopened prior lease")
	}
}

func TestIdentityRejectsLegacyCredentialBeforeInstallAndAfterUidChanges(t *testing.T) {
	r, signing, uid := identityFixture(t)
	r.taskToken = "synthetic-legacy-token"
	req := httptest.NewRequest("POST", "/eap/task", nil)
	req.Header.Set("Authorization", "Bearer synthetic-legacy-token")
	if r.authorized(req) {
		t.Fatal("legacy token bypassed uninstalled identity")
	}
	rec := httptest.NewRecorder()
	r.identityChallenge(rec, signedRequest(signing, challengeBody("5f65331b-ee14-4ce0-a128-af7a10a0f8a1")))
	var challenge map[string]string
	json.Unmarshal(rec.Body.Bytes(), &challenge)
	token := "eap_rt_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
	rec = httptest.NewRecorder()
	r.identityInstall(rec, signedRequest(signing, installBody(t, r, challenge, uid, token)))
	if rec.Code != 200 {
		t.Fatal("fixture identity installation failed")
	}
	req.Header.Set("Authorization", "Bearer "+token)
	if !r.authorized(req) {
		t.Fatal("installed identity rejected")
	}
	os.WriteFile(r.identity.directory+"/uid", []byte("508f73ba-82e7-4324-a143-a0c3fce02ed0"), 0600)
	if r.authorized(req) {
		t.Fatal("credential accepted after UID changed")
	}
	os.Remove(r.identity.directory + "/uid")
	if r.authorized(req) {
		t.Fatal("missing projection accepted")
	}
}
