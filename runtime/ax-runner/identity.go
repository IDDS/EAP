package main

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

// This material stays in guest memory. Trust is a public key; the template holds no bearer secret.
type runtimeIdentity struct {
	mu          sync.Mutex
	trust       ed25519.PublicKey
	directory   string
	operation   string
	nonce       string
	actorUID    string
	instanceKey string
	issued      time.Time
	private     *rsa.PrivateKey
}

func newRuntimeIdentity(public, directory string) (*runtimeIdentity, error) {
	if public == "" {
		return nil, nil
	}
	raw, e := base64.StdEncoding.DecodeString(public)
	if e != nil {
		return nil, errors.New("invalid identity public key")
	}
	parsed, e := x509.ParsePKIXPublicKey(raw)
	if e != nil {
		return nil, errors.New("invalid identity public key")
	}
	key, ok := parsed.(ed25519.PublicKey)
	if !ok || directory == "" {
		return nil, errors.New("identity projection and Ed25519 key required")
	}
	return &runtimeIdentity{trust: key, directory: directory}, nil
}
func (id *runtimeIdentity) fields() (string, string, error) {
	read := func(name string) (string, error) {
		b, e := os.ReadFile(id.directory + "/" + name)
		if e != nil || len(b) > 128 {
			return "", errors.New("identity projection unavailable")
		}
		return strings.TrimSpace(string(b)), nil
	}
	space, e := read("atespace")
	if e != nil {
		return "", "", e
	}
	name, e := read("name")
	if e != nil {
		return "", "", e
	}
	uid, e := read("uid")
	if e != nil {
		return "", "", e
	}
	dns := regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)
	if !dns.MatchString(space) || !dns.MatchString(name) || !canonicalUUID(uid) {
		return "", "", errors.New("invalid projected identity")
	}
	return space + "/" + name, uid, nil
}
func (r *runner) signedIdentity(req *http.Request, target any) error {
	if req.Method != http.MethodPost || r.identity == nil {
		return errors.New("identity disabled")
	}
	var wire struct {
		Payload   string `json:"payload"`
		Signature string `json:"signature"`
	}
	if json.NewDecoder(http.MaxBytesReader(nil, req.Body, 8192)).Decode(&wire) != nil {
		return errors.New("invalid envelope")
	}
	body, e := base64.RawURLEncoding.DecodeString(wire.Payload)
	if e != nil || len(body) > 4096 {
		return errors.New("invalid envelope")
	}
	signature, e := base64.RawURLEncoding.DecodeString(wire.Signature)
	if e != nil || !ed25519.Verify(r.identity.trust, body, signature) {
		return errors.New("invalid controller signature")
	}
	if json.Unmarshal(body, target) != nil {
		return errors.New("invalid envelope")
	}
	return nil
}
func (r *runner) identityChallenge(w http.ResponseWriter, req *http.Request) {
	var in struct {
		Operation string `json:"operation"`
		ExpiresAt int64  `json:"expiresAt"`
		Serial    int64  `json:"serial"`
	}
	if r.signedIdentity(req, &in) != nil || !canonicalUUID(in.Operation) || in.ExpiresAt <= time.Now().Unix() || in.ExpiresAt > time.Now().Add(time.Minute).Unix() {
		http.Error(w, "unauthorized identity request", 401)
		return
	}
	id := r.identity
	id.mu.Lock()
	defer id.mu.Unlock()
	r.mu.Lock()
	serial := r.credentialSerial
	r.mu.Unlock()
	if in.Serial < 1 || in.Serial < serial {
		http.Error(w, "stale identity request", 409)
		return
	}
	key, uid, e := id.fields()
	if e != nil {
		http.Error(w, "identity projection unavailable", 409)
		return
	}
	if id.operation != in.Operation || id.private == nil || id.actorUID != uid || id.instanceKey != key || time.Since(id.issued) > 2*time.Minute {
		private, e := rsa.GenerateKey(rand.Reader, 2048)
		if e != nil {
			http.Error(w, "identity unavailable", 503)
			return
		}
		nonce := make([]byte, 32)
		if _, e = rand.Read(nonce); e != nil {
			http.Error(w, "identity unavailable", 503)
			return
		}
		id.private = private
		id.nonce = base64.RawURLEncoding.EncodeToString(nonce)
		id.operation = in.Operation
		id.actorUID = uid
		id.instanceKey = key
		id.issued = time.Now()
	}
	public, _ := x509.MarshalPKIXPublicKey(&id.private.PublicKey)
	writeJSON(w, map[string]any{"protocol": "EAP_IDENTITY_V1", "operation": id.operation, "nonce": id.nonce, "publicKey": base64.RawURLEncoding.EncodeToString(public), "actorUid": uid, "instanceKey": key})
}
func (r *runner) identityInstall(w http.ResponseWriter, req *http.Request) {
	var in struct {
		Protocol    string `json:"protocol"`
		Operation   string `json:"operation"`
		Nonce       string `json:"nonce"`
		InstanceKey string `json:"instanceKey"`
		AgentID     string `json:"agentId"`
		ActorUID    string `json:"actorUid"`
		Epoch       string `json:"identityEpoch"`
		Revision    string `json:"revision"`
		ExpiresAt   int64  `json:"expiresAt"`
		Ciphertext  string `json:"ciphertext"`
		Serial      int64  `json:"serial"`
	}
	if r.signedIdentity(req, &in) != nil {
		http.Error(w, "unauthorized identity request", 401)
		return
	}
	id := r.identity
	id.mu.Lock()
	defer id.mu.Unlock()
	key, uid, e := id.fields()
	if e != nil || id.private == nil || in.Protocol != "EAP_IDENTITY_V1" || in.Operation != id.operation || in.Nonce != id.nonce || in.InstanceKey != key || in.ActorUID != uid || id.actorUID != uid || id.instanceKey != key || in.AgentID != r.agentID || !canonicalUUID(in.Epoch) || !canonicalUUID(in.Revision) || in.ExpiresAt <= time.Now().Unix() || in.ExpiresAt > time.Now().Add(time.Hour).Unix() || time.Since(id.issued) > 2*time.Minute {
		http.Error(w, "identity does not match", 409)
		return
	}
	encrypted, e := base64.RawURLEncoding.DecodeString(in.Ciphertext)
	if e != nil {
		http.Error(w, "invalid credential", 400)
		return
	}
	token, e := rsa.DecryptOAEP(sha256.New(), rand.Reader, id.private, encrypted, nil)
	if e != nil || !regexp.MustCompile(`^eap_rt_[A-Za-z0-9_-]{43}$`).Match(token) {
		http.Error(w, "invalid credential", 400)
		return
	}
	r.mu.Lock()
	if in.Serial < 1 || in.Serial < r.credentialSerial || in.Serial == r.credentialSerial && (r.runtimeToken != string(token) || r.credentialEpoch != in.Epoch) {
		r.mu.Unlock()
		http.Error(w, "stale credential version", 409)
		return
	}
	r.credentialSerial = in.Serial
	r.credentialEpoch = in.Epoch
	r.runtimeKey = key
	r.runtimeToken = string(token)
	r.credentialExpiry = time.Unix(in.ExpiresAt, 0)
	r.credentialUID = uid
	r.mu.Unlock()
	mac := hmac.New(sha256.New, token)
	mac.Write([]byte("eap-delivery-v1|" + in.Operation + "|" + in.Nonce + "|" + key + "|" + in.Revision))
	writeJSON(w, map[string]any{"operation": in.Operation, "proof": base64.RawURLEncoding.EncodeToString(mac.Sum(nil))})
}
