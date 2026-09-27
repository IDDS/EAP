// eap-agent-runner is EAP's generic configuration-driven agent (deployment source A) and the first
// real LLM agent on Substrate. Behaviour comes from environment variables: a system prompt and a model.
// Every model call goes through the EAP governance gateway, which holds the provider key; the agent
// only holds a gateway token. Conversation history lives in process memory, so a Substrate
// suspend/resume keeps the session without any external store.
package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"
)

type message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type runner struct {
	mu        sync.Mutex
	history   []message
	handled   map[string]string // taskKey -> answer, for idempotent task delivery
	gateway   string
	gwToken   string
	taskToken string
	agentID   string
	model     string
	system    string
	maxTokens int
	client    *http.Client
}

const maxHistory = 40

func main() {
	maxTokens, _ := strconv.Atoi(env("AGENT_MAX_TOKENS", "1024"))
	r := &runner{
		handled:   map[string]string{},
		gateway:   os.Getenv("EAP_GATEWAY_URL"),
		gwToken:   os.Getenv("EAP_GATEWAY_TOKEN"),
		taskToken: os.Getenv("EAP_TASK_TOKEN"),
		agentID:   os.Getenv("EAP_AGENT_ID"),
		model:     env("AGENT_MODEL", "deepseek-flash"),
		system:    env("AGENT_SYSTEM_PROMPT", "你是一个乐于助人的企业助手，回答简洁准确。"),
		maxTokens: maxTokens,
		client:    &http.Client{Timeout: 110 * time.Second},
	}
	http.HandleFunc("/readyz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	http.HandleFunc("/eap/task", r.task)
	http.HandleFunc("/chat", r.chat)
	http.HandleFunc("/eap/state", r.state)
	log.Fatal(http.ListenAndServe(":80", nil))
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func (r *runner) authorized(req *http.Request) bool {
	return r.taskToken != "" && req.Header.Get("Authorization") == "Bearer "+r.taskToken
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
		answer, err = r.ask(prompt, traceID)
		r.span(traceID, "agent.task", start, err, map[string]string{"eap.task.key": t.TaskKey})
		if err != nil {
			log.Printf("task %s failed: %v", t.TaskKey, err)
			status, answer = "FAILED", ""
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
	r.span(traceID, "agent.turn", start, err, nil)
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
	defer r.mu.Unlock()
	writeJSON(w, map[string]any{"turns": len(r.history) / 2, "tasksHandled": len(r.handled), "model": r.model})
}

// ask appends one exchange to the history; a failed call leaves the history unchanged.
func (r *runner) ask(user, traceID string) (string, error) {
	r.mu.Lock()
	msgs := append([]message{{Role: "system", Content: r.system}}, r.history...)
	r.mu.Unlock()
	msgs = append(msgs, message{Role: "user", Content: user})
	body, _ := json.Marshal(map[string]any{"model": r.model, "messages": msgs, "max_tokens": r.maxTokens})
	req, err := http.NewRequest(http.MethodPost, r.gateway+"/api/gateway/v1/chat/completions", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-EAP-Gateway-Token", r.gwToken)
	if r.agentID != "" {
		req.Header.Set("X-EAP-Agent-Id", r.agentID)
	}
	// Links the gateway's model-call record to this agent's trace.
	req.Header.Set("X-EAP-Trace-Id", traceID)
	resp, err := r.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("gateway unreachable: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("gateway HTTP %d: %.200s", resp.StatusCode, raw)
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || len(out.Choices) == 0 {
		return "", fmt.Errorf("gateway returned no choices")
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

// span reports one agent-level span to EAP over OTLP/HTTP JSON, best effort and off the request path.
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
	go func() {
		req, err := http.NewRequest(http.MethodPost, r.gateway+"/api/observability/otlp/v1/traces", bytes.NewReader(body))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		if token := os.Getenv("EAP_OBSERVABILITY_TOKEN"); token != "" {
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
