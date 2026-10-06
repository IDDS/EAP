// eap-agent-runner is EAP's generic configuration-driven agent (deployment source A) and the first
// real LLM agent on Substrate. Behaviour comes from environment variables: a system prompt and a model.
// Every model call goes through the EAP governance gateway, which holds the provider key; the agent
// only holds a gateway token. Conversation history lives in process memory, so a Substrate
// suspend/resume keeps the session without any external store.
package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"sync"
	"time"
)

var errModelOutcomeUnknown = errors.New("模型执行结果未知，请核对原调用")

type message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type runner struct {
	identity         *runtimeIdentity
	credentialExpiry time.Time
	credentialUID    string
	credentialSerial int64
	credentialEpoch  string
	experimentMu     sync.Mutex
	experimentDir    string
	mu               sync.Mutex
	toolMu           sync.Mutex
	toolDir          string
	toolsEnabled     bool
	history          []message
	handled          map[string]string // taskKey -> answer, for idempotent task delivery
	gateway          string
	gwToken          string
	taskToken        string
	runtimeKey       string
	runtimeToken     string
	pairingToken     string
	agentID          string
	model            string
	system           string
	maxTokens        int
	client           *http.Client
}

const maxHistory = 40

func main() {
	maxTokens, _ := strconv.Atoi(env("AGENT_MAX_TOKENS", "1024"))
	if maxTokens < 1 || maxTokens > 8192 {
		log.Fatal("invalid max tokens")
	}
	r := &runner{
		experimentDir: env("EAP_EXPERIMENT_JOURNAL_DIR", "/data/eap-experiments"),
		handled:       map[string]string{},
		toolDir:       env("EAP_TOOL_JOURNAL_DIR", "/data/eap-tool-journal"),
		toolsEnabled:  os.Getenv("EAP_TOOLS_ENABLED") == "true",
		gateway:       os.Getenv("EAP_GATEWAY_URL"),
		gwToken:       os.Getenv("EAP_GATEWAY_TOKEN"),
		taskToken:     os.Getenv("EAP_TASK_TOKEN"),
		runtimeKey:    os.Getenv("EAP_RUNTIME_KEY"),
		runtimeToken:  os.Getenv("EAP_RUNTIME_TOKEN"),
		pairingToken:  os.Getenv("EAP_PAIRING_TOKEN"),
		agentID:       os.Getenv("EAP_AGENT_ID"),
		model:         env("AGENT_MODEL", "deepseek-flash"),
		system:        env("AGENT_SYSTEM_PROMPT", "你是一个乐于助人的企业助手，回答简洁准确。"),
		maxTokens:     maxTokens,
		client:        &http.Client{Timeout: 110 * time.Second},
	}
	var identityError error
	r.identity, identityError = newRuntimeIdentity(os.Getenv("EAP_IDENTITY_PUBLIC_KEY"), os.Getenv("EAP_IDENTITY_DIRECTORY"))
	if identityError != nil {
		log.Fatal("invalid runtime identity configuration")
	}
	http.HandleFunc("/eap/identity/challenge", r.identityChallenge)
	http.HandleFunc("/eap/identity/install", r.identityInstall)
	http.HandleFunc("/readyz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	http.HandleFunc("/eap/experiments/profile", r.experimentProfile)
	http.HandleFunc("/eap/experiments/run", r.experiment)
	http.HandleFunc("/eap/task", r.task)
	http.HandleFunc("/chat", r.chat)
	http.HandleFunc("/eap/state", r.state)
	http.HandleFunc("/eap/integration", r.integration)
	http.HandleFunc("/eap/integration/configure", r.configureIntegration)
	log.Fatal(http.ListenAndServe(":80", nil))
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func (r *runner) authorized(req *http.Request) bool {
	r.mu.Lock()
	token := r.taskToken
	if r.runtimeKey != "" {
		token = r.runtimeToken
	}
	validLease := r.identity == nil || !r.credentialExpiry.IsZero() && time.Now().Before(r.credentialExpiry)
	uid, key := r.credentialUID, r.runtimeKey
	r.mu.Unlock()
	if r.identity != nil {
		projectedKey, projectedUID, err := r.identity.fields()
		validLease = validLease && err == nil && key == projectedKey && uid == projectedUID
	}
	return validLease && token != "" && hmac.Equal([]byte(req.Header.Get("Authorization")), []byte("Bearer "+token))
}

// task implements EAP_HTTP_TASK_V1: answer only after the work is done, with the matching taskKey.
func (r *runner) task(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodPost || !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var t struct {
		TaskKey   string         `json:"taskKey"`
		Objective string         `json:"objective"`
		Context   map[string]any `json:"context"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, req.Body, 65536)).Decode(&t); err != nil || t.TaskKey == "" {
		http.Error(w, "bad task", http.StatusBadRequest)
		return
	}
	if input, ok := t.Context["eapTool"]; ok {
		r.guardedTask(w, t.TaskKey, t.Objective, input)
		return
	}
	r.mu.Lock()
	answer, done := r.handled[t.TaskKey]
	r.mu.Unlock()
	status := "SUCCEEDED"
	if !done {
		prompt := t.Objective
		if len(t.Context) > 0 {
			ctx, _ := json.Marshal(t.Context)
			prompt += "\n上下文：" + string(ctx)
		}
		var err error
		start := time.Now()
		traceID := randomHex(16)
		answer, err = r.askScoped(prompt, traceID, t.TaskKey)
		r.span(traceID, "agent.task", start, err, map[string]string{"eap.task.key": t.TaskKey})
		if err != nil {
			log.Printf("task %s failed: %v", t.TaskKey, err)
			status, answer = "FAILED", ""
			if errors.Is(err, errModelOutcomeUnknown) {
				status = "UNKNOWN"
			}
		} else {
			r.mu.Lock()
			r.handled[t.TaskKey] = answer
			r.mu.Unlock()
		}
	}
	writeJSON(w, map[string]any{"taskKey": t.TaskKey, "status": status, "output": answer})
}

// chat is the console trial-run entry: one user turn in the actor's ongoing conversation.
func (r *runner) chat(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodPost || !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var in struct {
		Message string `json:"message"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, req.Body, 16384)).Decode(&in); err != nil || in.Message == "" {
		http.Error(w, "bad message", http.StatusBadRequest)
		return
	}
	start := time.Now()
	traceID := randomHex(16)
	answer, err := r.ask(in.Message, traceID)
	go r.span(traceID, "agent.turn", start, err, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	r.mu.Lock()
	turns := len(r.history) / 2
	r.mu.Unlock()
	writeJSON(w, map[string]any{"reply": answer, "turns": turns})
}

func (r *runner) state(w http.ResponseWriter, req *http.Request) {
	if !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	r.mu.Lock()
	turns, handled := len(r.history)/2, len(r.handled)
	r.mu.Unlock()
	writeJSON(w, map[string]any{"turns": turns, "tasksHandled": handled, "model": r.model, "tools": r.toolStats()})
}

// ask appends one exchange to the history; a failed call leaves the history unchanged.
func (r *runner) ask(user, traceID string) (string, error) { return r.askScoped(user, traceID, "") }

func (r *runner) askScoped(user, traceID, taskKey string) (string, error) {
	r.mu.Lock()
	msgs := append([]message{{Role: "system", Content: r.system}}, r.history...)
	r.mu.Unlock()
	msgs = append(msgs, message{Role: "user", Content: user})
	body, _ := json.Marshal(map[string]any{"model": r.model, "messages": msgs, "max_tokens": r.maxTokens})
	r.mu.Lock()
	runtimeKey, runtimeToken := r.runtimeKey, r.runtimeToken
	r.mu.Unlock()
	modelPath := "/api/gateway/v1/chat/completions"
	if runtimeKey != "" {
		if runtimeToken == "" {
			return "", fmt.Errorf("runtime integration is not configured")
		}
		modelPath = "/api/runtime-access/v1/chat/completions"
	}
	req, err := http.NewRequest(http.MethodPost, r.gateway+modelPath, bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	if runtimeKey != "" {
		req.Header.Set("X-EAP-Runtime-Key", runtimeKey)
		req.Header.Set("X-EAP-Runtime-Token", runtimeToken)
	} else {
		req.Header.Set("X-EAP-Gateway-Token", r.gwToken)
	}
	if r.agentID != "" {
		req.Header.Set("X-EAP-Agent-Id", r.agentID)
	}
	// Links the gateway's model-call record to this agent's trace.
	req.Header.Set("X-EAP-Trace-Id", traceID)
	if taskKey != "" {
		req.Header.Set("X-EAP-Task-Key", taskKey)
	}
	resp, err := r.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("%w: gateway unavailable", errModelOutcomeUnknown)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode != http.StatusOK {
		var rejected struct {
			Error struct {
				Code string `json:"code"`
			} `json:"error"`
		}
		_ = json.Unmarshal(raw, &rejected)
		if resp.Header.Get("X-EAP-Admission-Outcome") != "REJECTED" {
			return "", fmt.Errorf("%w: gateway HTTP %d", errModelOutcomeUnknown, resp.StatusCode)
		}
		return "", fmt.Errorf("gateway HTTP %d", resp.StatusCode)
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || len(out.Choices) == 0 {
		return "", fmt.Errorf("%w: no complete choices", errModelOutcomeUnknown)
	}
	answer := out.Choices[0].Message.Content
	r.mu.Lock()
	r.history = append(r.history, message{Role: "user", Content: user}, message{Role: "assistant", Content: answer})
	if len(r.history) > maxHistory {
		r.history = r.history[len(r.history)-maxHistory:]
	}
	r.mu.Unlock()
	return answer, nil
}

// span exports best effort with a bounded timeout. Tasks wait for export before their terminal
// response so an immediate actor suspension cannot freeze an unstarted background export. Chat exports asynchronously.
// Its name and attributes deliberately avoid gen_ai.* model keys: the gateway already records the model call,
// and EAP would count a model-shaped span a second time.
func (r *runner) span(traceID, name string, start time.Time, failure error, extra map[string]string) {
	if r.gateway == "" {
		return
	}
	end := time.Now()
	attrs := []map[string]any{
		{"key": "eap.agent.id", "value": map[string]any{"stringValue": r.agentID}},
		{"key": "agent.runtime", "value": map[string]any{"stringValue": "substrate"}},
		{"key": "agent.duration_ms", "value": map[string]any{"intValue": end.Sub(start).Milliseconds()}},
	}
	for k, v := range extra {
		attrs = append(attrs, map[string]any{"key": k, "value": map[string]any{"stringValue": v}})
	}
	status := map[string]any{"code": 1}
	if failure != nil {
		status = map[string]any{"code": 2, "message": failure.Error()}
	}
	payload := map[string]any{"resourceSpans": []any{map[string]any{
		"resource": map[string]any{"attributes": []any{
			map[string]any{"key": "service.name", "value": map[string]any{"stringValue": "eap-agent-runner"}},
			map[string]any{"key": "eap.agent.id", "value": map[string]any{"stringValue": r.agentID}},
		}},
		"scopeSpans": []any{map[string]any{
			"scope": map[string]any{"name": "eap-agent-runner"},
			"spans": []any{map[string]any{
				"traceId": traceID, "spanId": randomHex(8), "name": name, "kind": 2,
				"startTimeUnixNano": strconv.FormatInt(start.UnixNano(), 10),
				"endTimeUnixNano":   strconv.FormatInt(end.UnixNano(), 10),
				"attributes":        attrs, "status": status,
			}},
		}},
	}}}
	body, _ := json.Marshal(payload)
	func() {
		r.mu.Lock()
		runtimeKey, runtimeToken := r.runtimeKey, r.runtimeToken
		r.mu.Unlock()
		tracePath := "/api/observability/otlp/v1/traces"
		if runtimeKey != "" {
			tracePath = "/api/runtime-access/v1/traces"
		}
		req, err := http.NewRequest(http.MethodPost, r.gateway+tracePath, bytes.NewReader(body))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		if runtimeKey != "" {
			req.Header.Set("X-EAP-Runtime-Key", runtimeKey)
			req.Header.Set("X-EAP-Runtime-Token", runtimeToken)
		} else if token := os.Getenv("EAP_OBSERVABILITY_TOKEN"); token != "" {
			req.Header.Set("X-EAP-Observability-Token", token)
		}
		resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
		if err != nil {
			log.Printf("otlp export failed: %v", err)
			return
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			log.Printf("otlp export HTTP %d", resp.StatusCode)
		}
	}()
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

// integration proves possession of the installed per-instance credential without receiving it.
// This endpoint never executes a model or business task.
func (r *runner) integration(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	var input struct {
		Nonce string `json:"nonce"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, req.Body, 1024)).Decode(&input) != nil || !regexp.MustCompile(`^[a-zA-Z0-9_-]{16,64}$`).MatchString(input.Nonce) {
		http.Error(w, "invalid challenge", http.StatusBadRequest)
		return
	}
	r.mu.Lock()
	key, token := r.runtimeKey, r.runtimeToken
	r.mu.Unlock()
	if key == "" || token == "" || r.agentID == "" {
		http.Error(w, "integration not configured", http.StatusConflict)
		return
	}
	mac := hmac.New(sha256.New, []byte(token))
	mac.Write([]byte(input.Nonce + "|" + r.agentID + "|" + key + "|EAP_HTTP_TASK_V1"))
	writeJSON(w, map[string]any{"nonce": input.Nonce, "agentId": r.agentID, "instanceKey": key, "protocol": "EAP_HTTP_TASK_V1", "proof": base64.RawURLEncoding.EncodeToString(mac.Sum(nil))})
}

// Optional owner-only memory configuration, disabled unless an independent pairing token is set.
// Owners may instead inject EAP_RUNTIME_TOKEN through their own secret configuration channel.
func (r *runner) configureIntegration(w http.ResponseWriter, req *http.Request) {
	if r.identity != nil || req.Method != http.MethodPost || r.pairingToken == "" || !hmac.Equal([]byte(req.Header.Get("Authorization")), []byte("Bearer "+r.pairingToken)) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var input struct {
		Token string `json:"token"`
	}
	if r.runtimeKey == "" || r.agentID == "" || json.NewDecoder(http.MaxBytesReader(w, req.Body, 1024)).Decode(&input) != nil || !regexp.MustCompile(`^eap_rt_[A-Za-z0-9_-]{43}$`).MatchString(input.Token) {
		http.Error(w, "invalid configuration", http.StatusBadRequest)
		return
	}
	r.mu.Lock()
	r.runtimeToken = input.Token
	r.mu.Unlock()
	writeJSON(w, map[string]any{"configured": true})
}
