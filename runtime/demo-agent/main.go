// eap-demo-agent is a minimal EAP_HTTP_TASK_V1 runtime used to verify the EAP x Substrate integration.
// It keeps per-actor session memory (task count and last objective) in process memory only, so that
// state surviving a suspend/resume proves the snapshot, not a database, carried it.
package main

import (
	"crypto/hmac"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"sync"
	"time"
)

type task struct {
	Protocol  string `json:"protocol"`
	TaskKey   string `json:"taskKey"`
	Objective string `json:"objective"`
	NodeKey   string `json:"nodeKey"`
	Context   struct {
		SleepSeconds int `json:"sleepSeconds"`
	} `json:"context"`
}

var (
	mu            sync.Mutex
	handled       = map[string]bool{}
	count         int
	lastObjective string
)

// runner owns only the identity lease; business session state remains unchanged.
type runner struct {
	mu               sync.Mutex
	identity         *runtimeIdentity
	credentialExpiry time.Time
	credentialUID    string
	credentialSerial int64
	credentialEpoch  string
	runtimeKey       string
	runtimeToken     string
	agentID          string
	taskToken        string
}

func main() {
	r := &runner{agentID: os.Getenv("EAP_AGENT_ID"), taskToken: os.Getenv("EAP_TASK_TOKEN")}
	var err error
	r.identity, err = newRuntimeIdentity(os.Getenv("EAP_IDENTITY_PUBLIC_KEY"), os.Getenv("EAP_IDENTITY_DIRECTORY"))
	if err != nil {
		log.Fatal("invalid runtime identity configuration")
	}
	log.Fatal(http.ListenAndServe(":80", routes(r)))
}

func routes(r *runner) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/eap/identity/challenge", r.identityChallenge)
	mux.HandleFunc("/eap/identity/install", r.identityInstall)
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	mux.HandleFunc("/sleep", func(w http.ResponseWriter, req *http.Request) {
		if r.identity != nil && !r.authorized(req) {
			http.Error(w, "unauthorized", 401)
			return
		}
		n, _ := strconv.Atoi(req.URL.Query().Get("s"))
		time.Sleep(time.Duration(n) * time.Second)
		_, _ = w.Write([]byte("slept " + strconv.Itoa(n) + "s\n"))
	})
	mux.HandleFunc("/eap/state", func(w http.ResponseWriter, req *http.Request) {
		if r.identity != nil && !r.authorized(req) {
			http.Error(w, "unauthorized", 401)
			return
		}
		mu.Lock()
		defer mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]any{"tasksHandled": count, "lastObjective": lastObjective})
	})
	mux.HandleFunc("/eap/task", func(w http.ResponseWriter, req *http.Request) {
		if req.Method != http.MethodPost || !r.authorized(req) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		var t task
		if err := json.NewDecoder(http.MaxBytesReader(w, req.Body, 65536)).Decode(&t); err != nil || t.TaskKey == "" {
			http.Error(w, "bad task", http.StatusBadRequest)
			return
		}
		// Test hook for long-task timeout measurements: simulate work of the requested duration.
		time.Sleep(time.Duration(t.Context.SleepSeconds) * time.Second)
		mu.Lock()
		if !handled[t.TaskKey] { // Idempotent per taskKey, as the protocol requires.
			handled[t.TaskKey] = true
			count++
			lastObjective = t.Objective
		}
		n := count
		mu.Unlock()
		log.Printf("task completed tasksHandled=%d", n)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"taskKey": t.TaskKey, "status": "SUCCEEDED", "tasksHandled": n})
	})
	return mux
}

func (r *runner) authorized(req *http.Request) bool {
	r.mu.Lock()
	token, key, uid := r.taskToken, r.runtimeKey, r.credentialUID
	valid := r.identity == nil || !r.credentialExpiry.IsZero() && time.Now().Before(r.credentialExpiry)
	if r.identity != nil {
		token = r.runtimeToken
	}
	r.mu.Unlock()
	if r.identity != nil {
		projectedKey, projectedUID, err := r.identity.fields()
		valid = valid && err == nil && key == projectedKey && uid == projectedUID
	}
	return valid && token != "" && hmac.Equal([]byte(req.Header.Get("Authorization")), []byte("Bearer "+token))
}
func canonicalUUID(s string) bool {
	return regexp.MustCompile(`^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$`).MatchString(s)
}
func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
