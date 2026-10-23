// AX task runner: authenticated async execution and durable receipts. Model calls use the EAP gateway.
package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
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
	mu                                                       sync.Mutex
	identity                                                 *runtimeIdentity
	runtimeKey, runtimeToken, credentialUID, credentialEpoch string
	credentialExpiry                                         time.Time
	credentialSerial                                         int64
	taskKey                                                  string
	history                                                  []message
	handled                                                  map[string]string // taskKey -> answer, for idempotent task delivery
	gateway                                                  string
	gwToken                                                  string
	taskToken                                                string
	agentID                                                  string
	model                                                    string
	system                                                   string
	maxTokens                                                int
	client                                                   *http.Client
}

const maxHistory = 40

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// ask appends one exchange to the history; a failed call leaves the history unchanged.
func (r *runner) ask(user, traceID string) (string, error) {
	r.mu.Lock()
	msgs := append([]message{{Role: "system", Content: r.system}}, r.history...)
	r.mu.Unlock()
	msgs = append(msgs, message{Role: "user", Content: user})
	body, _ := json.Marshal(map[string]any{"model": r.model, "messages": msgs, "max_tokens": r.maxTokens})
	path := "/api/gateway/v1/chat/completions"
	key, token, identityError := r.machineCredentials()
	if r.identity != nil {
		if identityError != nil {
			return "", identityError
		}
		path = "/api/runtime-access/v1/chat/completions"
	}
	req, err := http.NewRequest(http.MethodPost, r.gateway+path, bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	if r.identity != nil {
		req.Header.Set("X-EAP-Runtime-Key", key)
		req.Header.Set("X-EAP-Runtime-Token", token)
	} else {
		req.Header.Set("X-EAP-Gateway-Token", r.gwToken)
		if r.agentID != "" {
			req.Header.Set("X-EAP-Agent-Id", r.agentID)
		}
	}
	// Links the gateway's model-call record to this agent's trace.
	req.Header.Set("X-EAP-Trace-Id", traceID)
	taskKey := r.taskKey
	if taskKey == "" && r.identity == nil {
		taskKey = os.Getenv("EAP_AX_TASK_KEY")
	}
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
	r.spanAt(traceID, name, start, time.Now(), failure, extra)
}

func (r *runner) spanAt(traceID, name string, start, end time.Time, failure error, extra map[string]string) {
	if r.gateway == "" {
		return
	}
	attrs := []map[string]any{
		{"key": "eap.agent.id", "value": map[string]any{"stringValue": r.agentID}},
		{"key": "agent.runtime", "value": map[string]any{"stringValue": "ax"}},
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
				"traceId": traceID, "spanId": randomHex(8), "name": name, "kind": 2,
				"startTimeUnixNano": strconv.FormatInt(start.UnixNano(), 10),
				"endTimeUnixNano":   strconv.FormatInt(end.UnixNano(), 10),
				"attributes":        attrs, "status": status,
			}},
		}},
	}}}
	body, _ := json.Marshal(payload)
	func() {
		path := "/api/observability/otlp/v1/traces"
		key, token, identityError := r.machineCredentials()
		if r.identity != nil {
			if identityError != nil {
				return
			}
			path = "/api/runtime-access/v1/traces"
		}
		req, err := http.NewRequest(http.MethodPost, r.gateway+path, bytes.NewReader(body))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		if r.identity != nil {
			req.Header.Set("X-EAP-Runtime-Key", key)
			req.Header.Set("X-EAP-Runtime-Token", token)
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

// AX owns this sandbox. The golden snapshot never executes a business task: only the
// authenticated dispatch endpoint starts work after EAP observes AX readiness.
type axRunner struct {
	mu       sync.Mutex
	engine   *runner
	dir, key string
	running  bool
}
type axInput struct {
	TraceID   string `json:"traceId,omitempty"`
	TaskKey   string `json:"taskKey"`
	Objective string `json:"objective"`
	Mode      string `json:"mode"`
}
type axReceipt struct {
	TaskKey  string `json:"taskKey"`
	Status   string `json:"status"`
	Output   string `json:"output,omitempty"`
	TraceID  string `json:"traceId,omitempty"`
	ExitCode int    `json:"exitCode"`
}

func main() {
	r := &runner{handled: map[string]string{}, gateway: os.Getenv("EAP_GATEWAY_URL"), gwToken: os.Getenv("EAP_GATEWAY_TOKEN"), taskToken: os.Getenv("EAP_TASK_TOKEN"), agentID: os.Getenv("EAP_AGENT_ID"), model: "deepseek-flash", system: "你是企业任务执行助手。准确、简洁地完成任务。", maxTokens: 1024, client: &http.Client{Timeout: 110 * time.Second}}
	r.taskKey = os.Getenv("EAP_AX_TASK_KEY")
	if err := r.configureIdentity(); err != nil {
		log.Fatal("task instance identity configuration invalid")
	}
	a := &axRunner{engine: r, dir: "/workspace/.eap", key: os.Getenv("EAP_AX_TASK_KEY")}
	if a.key == "" || r.identity == nil && r.taskToken == "" {
		log.Fatal("AX task identity or credential missing")
	}
	if err := os.MkdirAll(a.dir, 0700); err != nil {
		log.Fatal("workspace unavailable")
	}
	http.HandleFunc("/healthz", func(w http.ResponseWriter, q *http.Request) { w.WriteHeader(200) })
	http.HandleFunc("/readyz", func(w http.ResponseWriter, q *http.Request) { w.WriteHeader(200) })
	http.HandleFunc("/eap/task", a.start)
	http.HandleFunc("/eap/result", a.result)
	http.HandleFunc("/eap/identity/challenge", r.identityChallenge)
	http.HandleFunc("/eap/identity/install", r.identityInstall)
	log.Fatal(http.ListenAndServe(":80", nil))
}
func (a *axRunner) auth(q *http.Request) bool {
	if a.engine.identity != nil {
		_, token, err := a.engine.machineCredentials()
		return err == nil && a.engine.taskKey == a.key && subtle.ConstantTimeCompare([]byte(q.Header.Get("Authorization")), []byte("Bearer "+token)) == 1
	}
	return a.engine.taskToken != "" && subtle.ConstantTimeCompare([]byte(q.Header.Get("Authorization")), []byte("Bearer "+a.engine.taskToken)) == 1
}
func (a *axRunner) start(w http.ResponseWriter, q *http.Request) {
	if q.Method != "POST" || !a.auth(q) {
		http.Error(w, "unauthorized", 401)
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, q.Body, 30000))
	if err != nil {
		http.Error(w, "too large", 413)
		return
	}
	var in axInput
	if json.Unmarshal(raw, &in) != nil || in.TaskKey != a.key || len(in.Objective) == 0 || len(in.Objective) > 24000 || (in.Mode != "LLM" && in.Mode != "ECHO" && in.Mode != "FAIL") {
		http.Error(w, "invalid task", 400)
		return
	}
	if in.TraceID != "" && (!regexp.MustCompile(`^[a-fA-F0-9]{32}$`).MatchString(in.TraceID) || in.TraceID == "00000000000000000000000000000000") {
		http.Error(w, "invalid trace", 400)
		return
	}
	canonical, _ := json.Marshal(in)
	sum := sha256.Sum256(canonical)
	fingerprint := hex.EncodeToString(sum[:])
	a.mu.Lock()
	defer a.mu.Unlock()
	marker := filepath.Join(a.dir, "started")
	if old, err := os.ReadFile(marker); err == nil {
		if string(old) != fingerprint {
			http.Error(w, "task parameters changed", 409)
			return
		}
		w.WriteHeader(202)
		return
	} else if !os.IsNotExist(err) {
		http.Error(w, "workspace unavailable", 503)
		return
	}
	// Durable at-most-once intent. A restart with this marker but no receipt is UNKNOWN.
	f, err := os.OpenFile(marker, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		http.Error(w, "cannot reserve task", 503)
		return
	}
	_, err = f.WriteString(fingerprint)
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		http.Error(w, "cannot persist task", 503)
		return
	}
	if err = syncDirectory(a.dir); err != nil {
		http.Error(w, "cannot persist task", 503)
		return
	}
	a.running = true
	go a.execute(in)
	w.WriteHeader(202)
}
func (a *axRunner) execute(in axInput) {
	trace := in.TraceID
	if trace == "" {
		trace = randomHex(16)
	}
	receipt := axReceipt{TaskKey: a.key, Status: "SUCCEEDED", ExitCode: 0, TraceID: trace}
	start := time.Now()
	var failure error
	switch in.Mode {
	case "ECHO":
		receipt.Output = in.Objective
	case "FAIL":
		receipt.Status = "FAILED"
		receipt.ExitCode = 1
		failure = fmt.Errorf("controlled failure")
	case "LLM":
		receipt.Output, failure = a.engine.ask(in.Objective, receipt.TraceID)
		if failure != nil {
			receipt.Status = "FAILED"
			receipt.ExitCode = 1
			if errors.Is(failure, errModelOutcomeUnknown) {
				receipt.Status = "EXECUTION_UNKNOWN"
			}
		}
	}
	if len(receipt.Output) > 16000 {
		receipt.Output = ""
		receipt.Status = "FAILED"
		receipt.ExitCode = 1
		failure = fmt.Errorf("result exceeds limit")
	}
	// Errors sent to the telemetry system contain no provider response or task content.
	if failure != nil {
		failure = fmt.Errorf("AX command failed")
	}
	a.engine.span(receipt.TraceID, "ax.task", start, failure, map[string]string{"eap.task.key": a.key, "ax.task.key": a.key})
	raw, _ := json.Marshal(receipt)
	a.mu.Lock()
	defer a.mu.Unlock()
	path := filepath.Join(a.dir, "result.json")
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err == nil {
		_, err = f.Write(raw)
		if err == nil {
			err = f.Sync()
		}
		_ = f.Close()
	}
	if err == nil {
		err = os.Rename(tmp, path)
	}
	if err == nil {
		err = syncDirectory(a.dir)
	}
	if err != nil {
		log.Print("AX receipt persistence failed")
	}
	a.running = false
}
func (a *axRunner) result(w http.ResponseWriter, q *http.Request) {
	if q.Method != "GET" || !a.auth(q) {
		http.Error(w, "unauthorized", 401)
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	raw, err := os.ReadFile(filepath.Join(a.dir, "result.json"))
	if err == nil {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(raw)
		return
	}
	if !os.IsNotExist(err) {
		http.Error(w, "workspace unavailable", 503)
		return
	}
	if a.running {
		w.WriteHeader(202)
		return
	}
	if _, err = os.Stat(filepath.Join(a.dir, "started")); err == nil {
		writeJSON(w, axReceipt{TaskKey: a.key, Status: "EXECUTION_UNKNOWN", ExitCode: -1})
		return
	}
	w.WriteHeader(404)
}
