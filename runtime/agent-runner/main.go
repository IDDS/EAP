// eap-agent-runner is EAP's generic configuration-driven agent (deployment source A) and the first
// real LLM agent on Substrate. Behaviour comes from environment variables: a system prompt and a model.
// Every model call goes through the EAP governance gateway, which holds the provider key; the agent
// only holds a gateway token. Each request uses explicit context; actors never share hidden history.
package main

import (
	"bytes"
	"context"
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

// Optional engines are selected at build time. Request data and environment cannot
// replace the model transport or opt out of EAP identity, admission and receipts.
var runnerFramework = "EAP_NATIVE"
var runnerEngineVersion = ""
var generateModel = (*runner).gatewayCompletion
var serveTask = (*runner).task
var configureEngine = func(*runner) {}

type message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type runner struct {
	mcpMu            sync.Mutex
	mcpSessions      map[*observedMCP]struct{}
	mcpRegistry      *mcpRegistry
	identity         *runtimeIdentity
	credentialExpiry time.Time
	credentialUID    string
	credentialSerial int64
	credentialEpoch  string
	experimentMu     sync.Mutex
	experimentDir    string
	mu               sync.Mutex
	taskMu           cancellableMutex
	conversationMu   cancellableMutex
	taskReceipts     map[string]modelTaskReceipt
	toolMu           sync.Mutex
	toolDir          string
	toolsEnabled     bool
	sumExecutor      func([]float64, *toolJournal) (float64, error)
	kagentToolMode   string
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
	thinkingMode     string
	client           *http.Client
}

const maxHistory = 40

func main() {
	maxTokens, _ := strconv.Atoi(env("AGENT_MAX_TOKENS", "1024"))
	if maxTokens < 1 || maxTokens > 8192 {
		log.Fatal("invalid max tokens")
	}
	r := &runner{
		experimentDir:  env("EAP_EXPERIMENT_JOURNAL_DIR", "/data/eap-experiments"),
		handled:        map[string]string{},
		toolDir:        env("EAP_TOOL_JOURNAL_DIR", "/data/eap-tool-journal"),
		toolsEnabled:   os.Getenv("EAP_TOOLS_ENABLED") == "true",
		kagentToolMode: os.Getenv("EAP_KAGENT_TOOL_MODE"),
		gateway:        os.Getenv("EAP_GATEWAY_URL"),
		gwToken:        os.Getenv("EAP_GATEWAY_TOKEN"),
		taskToken:      os.Getenv("EAP_TASK_TOKEN"),
		runtimeKey:     os.Getenv("EAP_RUNTIME_KEY"),
		runtimeToken:   os.Getenv("EAP_RUNTIME_TOKEN"),
		pairingToken:   os.Getenv("EAP_PAIRING_TOKEN"),
		agentID:        os.Getenv("EAP_AGENT_ID"),
		model:          env("AGENT_MODEL", "deepseek-flash"),
		system:         env("AGENT_SYSTEM_PROMPT", "你是一个乐于助人的企业助手，回答简洁准确。"),
		maxTokens:      maxTokens,
		thinkingMode:   env("AGENT_THINKING_MODE", "PROVIDER_DEFAULT"),
		client:         &http.Client{Timeout: 110 * time.Second},
	}
	if !validThinkingMode(r.model, r.thinkingMode) {
		log.Fatal("invalid thinking mode for model")
	}
	if runnerFramework != "EAP_NATIVE" {
		// Experiments have a separate, frozen native-generation contract.
		r.experimentDir = ""
	}
	var identityError error
	r.identity, identityError = newRuntimeIdentity(os.Getenv("EAP_IDENTITY_PUBLIC_KEY"), os.Getenv("EAP_IDENTITY_DIRECTORY"))
	if identityError != nil {
		log.Fatal("invalid runtime identity configuration")
	}
	var manifestError error
	r.mcpRegistry, manifestError = loadMCPRegistry(mcpManifestPath)
	if manifestError != nil {
		log.Fatal("invalid MCP service manifest")
	}
	configureEngine(r)
	http.HandleFunc("/eap/identity/challenge", r.identityChallenge)
	http.HandleFunc("/eap/identity/install", r.identityInstall)
	http.HandleFunc("/readyz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	http.HandleFunc("/eap/experiments/profile", r.experimentProfile)
	http.HandleFunc("/eap/experiments/run", r.experiment)
	http.HandleFunc("/eap/task", func(w http.ResponseWriter, req *http.Request) { serveTask(r, w, req) })
	http.HandleFunc("/chat", r.chat)
	http.HandleFunc("/eap/state", r.state)
	http.HandleFunc("/eap/integration", r.integration)
	http.HandleFunc("/eap/integration/configure", r.configureIntegration)
	http.HandleFunc("/eap/mcp/profile", r.mcpProfile)
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
		TraceID   string         `json:"traceId"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, req.Body, 65536)).Decode(&t); err != nil || t.TaskKey == "" {
		http.Error(w, "bad task", http.StatusBadRequest)
		return
	}
	if t.TraceID != "" && !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(t.TraceID) {
		http.Error(w, "bad trace", http.StatusBadRequest)
		return
	}
	if input, ok := t.Context["eapTool"]; ok {
		r.guardedTask(w, t.TaskKey, t.Objective, input)
		return
	}
	// A task receipt survives suspend/resume with actor memory, including failed/unknown
	// outcomes. Gateway task identity remains the durable no-replay boundary after restart.
	if err := r.taskMu.LockContext(req.Context()); err != nil {
		writeJSON(w, modelTaskReceipt{TaskKey: t.TaskKey, Status: "FAILED", ReasonCode: "MODEL_NOT_DISPATCHED"})
		return
	}
	defer r.taskMu.Unlock()
	if !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	frozen, _ := json.Marshal(t)
	fingerprint := digest(frozen)
	if receipt, found := r.taskReceipts[t.TaskKey]; found {
		if receipt.Fingerprint != fingerprint {
			http.Error(w, "task identity conflict", http.StatusConflict)
			return
		}
		writeJSON(w, receipt)
		return
	}
	r.mu.Lock()
	answer, done := r.handled[t.TaskKey]
	r.mu.Unlock()
	receipt := modelTaskReceipt{TaskKey: t.TaskKey, Status: "SUCCEEDED", Output: answer, TraceID: t.TraceID, Fingerprint: fingerprint}
	if !done {
		prompt := t.Objective
		if len(t.Context) > 0 {
			ctx, _ := json.Marshal(t.Context)
			prompt += "\n上下文：" + string(ctx)
		}
		var err error
		start := time.Now()
		traceID := t.TraceID
		if traceID == "" {
			traceID = randomHex(16)
		}
		answer, err = r.askContext(req.Context(), prompt, traceID, t.TaskKey, nil, func() bool { return r.authorized(req) })
		attrs := r.modelOutputAttributes(err)
		attrs["eap.task.key"] = t.TaskKey
		receipt.Output = answer
		receipt.FinishReason = attrs["eap.model.finish_reason"]
		if err != nil {
			log.Printf("task %s failed: %v", t.TaskKey, err)
			receipt.Status, receipt.Output = "FAILED", ""
			receipt.ReasonCode = attrs["eap.model.response_state"]
			if errors.Is(err, errModelOutcomeUnknown) {
				receipt.Status = "UNKNOWN"
			}
		} else {
			r.mu.Lock()
			if r.handled == nil {
				r.handled = map[string]string{}
			}
			r.handled[t.TaskKey] = answer
			r.mu.Unlock()
		}
		r.span(traceID, "agent.task", start, err, attrs)
	}
	if r.taskReceipts == nil {
		r.taskReceipts = map[string]modelTaskReceipt{}
	}
	r.taskReceipts[t.TaskKey] = receipt
	writeJSON(w, receipt)
}

// chat accepts only the caller's explicit, visible history; it never reads actor-global memory.
func (r *runner) chat(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodPost || !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var in struct {
		Message string    `json:"message"`
		TraceID string    `json:"traceId"`
		History []message `json:"history"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, req.Body, 524288)).Decode(&in); err != nil || in.Message == "" || !validChatHistory(in.History) {
		http.Error(w, "bad message", http.StatusBadRequest)
		return
	}
	if in.TraceID != "" && !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(in.TraceID) {
		http.Error(w, "bad trace", http.StatusBadRequest)
		return
	}
	start := time.Now()
	traceID := in.TraceID
	if traceID == "" {
		traceID = randomHex(16)
	}
	answer, err := r.askContext(req.Context(), in.Message, traceID, "", in.History, func() bool { return r.authorized(req) })
	end := time.Now()
	if in.TraceID != "" {
		r.spanAt(traceID, "agent.turn", start, end, err, r.modelOutputAttributes(err))
	} else {
		go r.spanAt(traceID, "agent.turn", start, end, err, r.modelOutputAttributes(err))
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	result := map[string]any{"reply": answer, "turns": len(in.History)/2 + 1, "conversationMode": "EXPLICIT_V1"}
	if in.TraceID != "" {
		result["traceId"] = in.TraceID
	}
	writeJSON(w, result)
}

func (r *runner) state(w http.ResponseWriter, req *http.Request) {
	if !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	r.mu.Lock()
	handled := len(r.handled)
	r.mu.Unlock()
	writeJSON(w, map[string]any{"turns": 0, "conversationMode": "EXPLICIT_V1", "tasksHandled": handled, "model": r.model, "tools": r.toolStats(), "runnerFramework": runnerFramework, "engineVersion": runnerEngineVersion, "kagentToolMode": r.kagentToolMode})
}

// Internal compatibility helpers are stateless as well.
func (r *runner) ask(user, traceID string) (string, error) { return r.askScoped(user, traceID, "") }

func (r *runner) askScoped(user, traceID, taskKey string) (string, error) {
	return r.askContext(context.Background(), user, traceID, taskKey, nil, nil)
}

func (r *runner) askContext(ctx context.Context, user, traceID, taskKey string, history []message, authorized func() bool) (string, error) {
	if err := r.conversationMu.LockContext(ctx); err != nil {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	defer r.conversationMu.Unlock()
	if ctx.Err() != nil || authorized != nil && !authorized() {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	msgs := append([]message{{Role: "system", Content: r.system}}, history...)
	msgs = append(msgs, message{Role: "user", Content: user})
	return generateModel(r, ctx, msgs, traceID, taskKey)
}

// Shared gate for native and framework-backed models. Only this method sends a
// model request, with the original EAP identity and task/trace attribution.
func (r *runner) gatewayCompletion(ctx context.Context, msgs []message, traceID, taskKey string) (string, error) {
	payload := map[string]any{"model": r.model, "messages": msgs, "max_tokens": r.maxTokens}
	raw, err := r.gatewayResponse(ctx, payload, traceID, taskKey)
	if err != nil {
		return "", err
	}
	return completedModelOutput(raw)
}

func (r *runner) gatewayResponse(ctx context.Context, payload map[string]any, traceID, taskKey string) ([]byte, error) {
	r.applyThinkingMode(payload)
	body, _ := json.Marshal(payload)
	r.mu.Lock()
	runtimeKey, runtimeToken := r.runtimeKey, r.runtimeToken
	r.mu.Unlock()
	modelPath := "/api/gateway/v1/chat/completions"
	if runtimeKey != "" {
		if runtimeToken == "" {
			return nil, fmt.Errorf("runtime integration is not configured")
		}
		modelPath = "/api/runtime-access/v1/chat/completions"
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.gateway+modelPath, bytes.NewReader(body))
	if err != nil {
		return nil, err
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
	if ctx.Err() != nil {
		return nil, &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	resp, err := r.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: gateway unavailable", errModelOutcomeUnknown)
	}
	defer resp.Body.Close()
	raw, readErr := io.ReadAll(io.LimitReader(resp.Body, (4<<20)+1))
	if readErr != nil || len(raw) > 4<<20 {
		return nil, fmt.Errorf("%w: incomplete gateway response", errModelOutcomeUnknown)
	}
	if resp.StatusCode != http.StatusOK {
		var rejected struct {
			Error struct {
				Code string `json:"code"`
			} `json:"error"`
		}
		_ = json.Unmarshal(raw, &rejected)
		if resp.Header.Get("X-EAP-Admission-Outcome") != "REJECTED" {
			return nil, fmt.Errorf("%w: gateway HTTP %d", errModelOutcomeUnknown, resp.StatusCode)
		}
		return nil, fmt.Errorf("gateway HTTP %d", resp.StatusCode)
	}
	return raw, nil
}

// span exports best effort with a bounded timeout. Tasks wait for export before their terminal
// response so an immediate actor suspension cannot freeze an unstarted background export. Chat exports asynchronously.
// Its name and attributes deliberately avoid gen_ai.* model keys: the gateway already records the model call,
// and EAP would count a model-shaped span a second time.
func (r *runner) span(traceID, name string, start time.Time, failure error, extra map[string]string) {
	r.spanAt(traceID, name, start, time.Now(), failure, extra)
}

// Preserve the execution boundary even when a chat export runs later.
func (r *runner) spanAt(traceID, name string, start, end time.Time, failure error, extra map[string]string) {
	r.spanAtID(traceID, randomHex(8), name, start, end, failure, extra)
}

func (r *runner) spanAtID(traceID, spanID, name string, start, end time.Time, failure error, extra map[string]string) {
	if r.gateway == "" {
		return
	}
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
		status = map[string]any{"code": 2}
	}
	payload := map[string]any{"resourceSpans": []any{map[string]any{
		"resource": map[string]any{"attributes": []any{
			map[string]any{"key": "service.name", "value": map[string]any{"stringValue": "eap-agent-runner"}},
			map[string]any{"key": "eap.agent.id", "value": map[string]any{"stringValue": r.agentID}},
		}},
		"scopeSpans": []any{map[string]any{
			"scope": map[string]any{"name": "eap-agent-runner"},
			"spans": []any{map[string]any{
				"traceId": traceID, "spanId": spanID, "name": name, "kind": 2,
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
		if (r.identity != nil || runtimeKey != "" || runtimeToken != "") && (runtimeKey == "" || runtimeToken == "") {
			return
		}
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
		client := http.Client{}
		if r.client != nil {
			client = *r.client
		}
		client.Timeout = 2 * time.Second
		client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
		resp, err := client.Do(req)
		if err != nil {
			log.Print("otlp export failed")
			return
		}
		defer resp.Body.Close()
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
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
