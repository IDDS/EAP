package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"time"
)

// Only this explicit, side-effect-free tool is exposed by the reference runner.
// User/model text cannot select a shell, URL or filesystem path.
type toolInput struct {
	Name      string         `json:"name"`
	Arguments map[string]any `json:"arguments"`
}
type toolJournal struct {
	Fingerprint      string           `json:"fingerprint"`
	CallID           string           `json:"callId"`
	TraceID          string           `json:"traceId"`
	Status           string           `json:"status"`
	GuardState       string           `json:"guardState"`
	Executed         bool             `json:"executed"`
	ReceiptConfirmed bool             `json:"receiptConfirmed"`
	Output           any              `json:"output,omitempty"`
	EffectSource     string           `json:"effectSource,omitempty"`
	Observation      *toolObservation `json:"observation,omitempty"`
}
type toolObservation struct {
	Framework               string `json:"framework"`
	ProducerID              string `json:"producerId"`
	StreamKey               string `json:"streamKey,omitempty"`
	Events                  int    `json:"events"`
	Pending                 int    `json:"pending"`
	DeliveryState           string `json:"deliveryState,omitempty"`
	DeliveryAttempts        int    `json:"deliveryAttempts,omitempty"`
	BackgroundDeliveryState string `json:"backgroundDeliveryState,omitempty"`
}
type guardView struct {
	CallID string `json:"callId"`
	State  string `json:"state"`
}

func digest(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func toolID(key, task string) string {
	h := digest([]byte("eap-actor-tool-v1|" + key + "|" + task))
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

// Write intent before contacting claim. Any uncertain disk/network outcome stops execution.
// Journal files contain no credential or original arguments, and are private to the instance.
func saveTool(path string, j *toolJournal) error { return savePrivateJournal(path, j) }

func savePrivateJournal(path string, value any) error {
	b, err := json.Marshal(value)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if _, err = f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err = f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	if err = os.Rename(path+".tmp", path); err != nil {
		return err
	}
	d, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

func (r *runner) guardPost(action string, payload any, out any) error {
	return r.guardPostContext(context.Background(), action, payload, out)
}

func (r *runner) guardPostContext(ctx context.Context, action string, payload any, out any) error {
	r.mu.Lock()
	key, token := r.runtimeKey, r.runtimeToken
	r.mu.Unlock()
	u, err := url.Parse(r.gateway)
	if err != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "http" && u.Scheme != "https") || key == "" || token == "" {
		return errors.New("tool connection unavailable")
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return errors.New("invalid tool request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.gateway+"/api/runtime-access/v1/tools/"+action, bytes.NewReader(body))
	if err != nil {
		return errors.New("tool connection unavailable")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-EAP-Runtime-Key", key)
	req.Header.Set("X-EAP-Runtime-Token", token)
	client := *r.client
	client.Timeout = 15 * time.Second
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := client.Do(req)
	if err != nil {
		return errors.New("tool permission or receipt not confirmed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("tool channel HTTP %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 32769))
	if err != nil || len(raw) > 32768 {
		return errors.New("invalid tool response")
	}
	var envelope struct {
		Data json.RawMessage `json:"data"`
	}
	if json.Unmarshal(raw, &envelope) != nil || json.Unmarshal(envelope.Data, out) != nil {
		return errors.New("invalid tool response")
	}
	return nil
}

func sumNumbers(args map[string]any) ([]float64, error) {
	values, ok := args["numbers"].([]any)
	if !ok || len(args) != 1 || len(values) == 0 || len(values) > 100 {
		return nil, errors.New("numbers must contain 1 to 100 finite numbers")
	}
	numbers := make([]float64, len(values))
	for i, v := range values {
		n, ok := v.(float64)
		if !ok || math.IsNaN(n) || math.IsInf(n, 0) || math.Abs(n) > 1e12 {
			return nil, errors.New("invalid number")
		}
		numbers[i] = n
	}
	return numbers, nil
}
func calculateSum(numbers []float64) float64 {
	sum := float64(0)
	for _, n := range numbers {
		sum += n
	}
	return sum
}

func (r *runner) guardedTask(w http.ResponseWriter, task, objective string, input any) {
	r.guardedTaskWithExecutor(w, task, objective, input, r.sumExecutor)
}

// A fixed adapter may supply a request-scoped executor without changing shared runner state.
func (r *runner) guardedTaskWithExecutor(w http.ResponseWriter, task, objective string, input any, execute func([]float64, *toolJournal) (float64, error)) {
	if !r.toolsEnabled || r.runtimeKey == "" || r.toolDir == "" || len(task) > 256 {
		http.Error(w, "guarded tools unavailable", 400)
		return
	}
	raw, err := json.Marshal(input)
	if err != nil || len(raw) > 65536 {
		http.Error(w, "invalid tool input", 400)
		return
	}
	var in toolInput
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&in) != nil || (in.Name != "calculate_sum" && in.Name != "controlled_counter_increment") || in.Arguments == nil {
		http.Error(w, "unsupported tool", 400)
		return
	}
	// Serialize duplicate deliveries and keep the complete task result across process restart.
	r.toolMu.Lock()
	defer r.toolMu.Unlock()
	id := toolID(r.runtimeKey, task)
	path := filepath.Join(r.toolDir, id+".json")
	fingerprint := digest(append([]byte(objective+"\x00"), raw...))
	if previous, e := os.ReadFile(path); e == nil {
		var j toolJournal
		if json.Unmarshal(previous, &j) != nil {
			http.Error(w, "tool journal unavailable", 503)
			return
		}
		if j.Fingerprint != fingerprint {
			http.Error(w, "task input changed", 409)
			return
		}
		writeToolResult(w, task, &j)
		return
	} else if !os.IsNotExist(e) {
		http.Error(w, "tool journal unavailable", 503)
		return
	}
	if os.MkdirAll(r.toolDir, 0700) != nil {
		http.Error(w, "tool journal unavailable", 503)
		return
	}
	j := &toolJournal{Fingerprint: fingerprint, CallID: id, TraceID: randomHex(16), Status: "EXECUTION_UNKNOWN", GuardState: "CHECK_UNCONFIRMED"}
	if saveTool(path, j) != nil {
		http.Error(w, "tool journal unavailable", 503)
		return
	}
	start := time.Now()
	// Every exit after intent keeps a durable result; observations never cause a tool retry.
	defer func() {
		var failure error
		if j.Status != "SUCCEEDED" {
			failure = errors.New("tool task " + j.GuardState)
		}
		r.span(j.TraceID, "agent.task", start, failure, map[string]string{"eap.task.key": task, "eap.guard.call_id": j.CallID})
		writeToolResult(w, task, j)
	}()
	if in.Name == "controlled_counter_increment" {
		r.scopedCounter(j, path, in.Arguments)
		return
	}
	var checked guardView
	if r.guardPost("check", map[string]any{"callId": id, "toolName": in.Name, "toolKind": "TOOL", "traceId": j.TraceID, "arguments": in.Arguments}, &checked) != nil || checked.CallID != id {
		return
	}
	j.GuardState = checked.State
	if checked.State != "APPROVED" {
		j.Status = "FAILED"
		if saveTool(path, j) != nil {
			j.Status = "EXECUTION_UNKNOWN"
		}
		return
	}
	// Local allowlist and argument validation remain in force after the platform check.
	numbers, err := sumNumbers(in.Arguments)
	if err != nil {
		j.Status = "FAILED"
		j.GuardState = "INVALID_ARGUMENTS"
		if saveTool(path, j) != nil {
			j.Status = "EXECUTION_UNKNOWN"
		}
		return
	}
	j.GuardState = "CLAIM_UNCONFIRMED"
	if saveTool(path, j) != nil {
		return
	}
	var claim struct {
		Execute bool      `json:"execute"`
		Call    guardView `json:"call"`
	}
	if r.guardPost("claim", map[string]any{"callId": id}, &claim) != nil || claim.Call.CallID != id {
		return
	}
	if !claim.Execute || claim.Call.State != "CLAIMED" {
		j.GuardState = claim.Call.State
		j.Status = "FAILED"
		if saveTool(path, j) != nil {
			j.Status = "EXECUTION_UNKNOWN"
		}
		return
	}
	// Once the intent is saved, no path replays this callback, including a lost receipt.
	var result float64
	if execute != nil {
		result, err = execute(numbers, j)
		if err != nil {
			j.GuardState = "FRAMEWORK_EXECUTION_UNCONFIRMED"
			_ = saveTool(path, j)
			return
		}
	} else {
		result = calculateSum(numbers)
	}
	j.Executed = true
	j.Output = map[string]any{"sum": result, "count": len(in.Arguments["numbers"].([]any))}
	j.Status = "SUCCEEDED"
	j.GuardState = "CLAIMED"
	if saveTool(path, j) != nil {
		j.Status = "EXECUTION_UNKNOWN"
		j.Output = nil
		return
	}
	output, _ := json.Marshal(j.Output)
	var receipt guardView
	if r.guardPost("receipt", map[string]any{"callId": id, "outcome": "SUCCEEDED", "durationMs": time.Since(start).Milliseconds(), "outputDigest": digest(output)}, &receipt) == nil && receipt.CallID == id && receipt.State == "SUCCEEDED" {
		j.ReceiptConfirmed = true
		j.GuardState = "SUCCEEDED"
		if saveTool(path, j) != nil {
			j.ReceiptConfirmed = false
		}
	}
}
func writeToolResult(w http.ResponseWriter, task string, j *toolJournal) {
	result := map[string]any{"taskKey": task, "status": j.Status, "output": j.Output, "tool": map[string]any{"callId": j.CallID, "traceId": j.TraceID, "state": j.GuardState, "executed": j.Executed, "receiptConfirmed": j.ReceiptConfirmed}}
	if j.Observation != nil {
		result["observation"] = j.Observation
	}
	writeJSON(w, result)
}

// Metadata only; used to verify actor-side execution counts after suspend/resume.
func (r *runner) toolStats() map[string]any {
	r.toolMu.Lock()
	defer r.toolMu.Unlock()
	result := map[string]any{"enabled": r.toolsEnabled, "executed": 0, "unknown": 0, "failed": 0}
	if !r.toolsEnabled {
		return result
	}
	files, err := filepath.Glob(filepath.Join(r.toolDir, "*.json"))
	if err != nil {
		result["unavailable"] = true
		return result
	}
	for _, path := range files {
		raw, e := os.ReadFile(path)
		var j toolJournal
		if e != nil || json.Unmarshal(raw, &j) != nil {
			result["unavailable"] = true
			continue
		}
		if j.EffectSource == "TOOL_STORE_VERIFIED" {
			if result["storeVerified"] == nil {
				result["storeVerified"] = 0
			}
			result["storeVerified"] = result["storeVerified"].(int) + 1
		}
		if j.Executed {
			result["executed"] = result["executed"].(int) + 1
		}
		if j.Status == "EXECUTION_UNKNOWN" {
			result["unknown"] = result["unknown"].(int) + 1
		}
		if j.Status == "FAILED" {
			result["failed"] = result["failed"].(int) + 1
		}
	}
	return result
}

type scopedEffect struct {
	RootID       string `json:"rootId"`
	OperationID  string `json:"operationId"`
	GrantID      string `json:"grantId"`
	ResourceID   string `json:"resourceId"`
	Delta        int64  `json:"delta"`
	State        string `json:"state"`
	Reason       string `json:"reasonCode"`
	EffectID     string `json:"effectId"`
	CounterValue *int64 `json:"counterValue"`
	Source       string `json:"effectSource"`
	TraceID      string `json:"traceId"`
}

func canonicalUUID(s string) bool {
	if len(s) != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-' {
		return false
	}
	for i, c := range s {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			continue
		}
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

// Only one POST. Unknown delivery keeps the durable intent and never creates a new operation key.
// The authoritative operation can be queried via the machine API; general recovery is a separate contract.
func (r *runner) scopedCounter(j *toolJournal, path string, args map[string]any) {
	grant, gok := args["grantId"].(string)
	operation, ook := args["operationId"].(string)
	resource, rok := args["resourceId"].(string)
	delta, dok := args["delta"].(float64)
	validResource := len(resource) >= 5 && len(resource) <= 64 && len(resource) > 4 && resource[:4] == "poc_"
	for _, c := range resource {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			validResource = false
		}
	}
	if len(args) != 4 || !gok || !ook || !rok || !dok || !canonicalUUID(grant) || !canonicalUUID(operation) || !validResource || math.IsNaN(delta) || math.IsInf(delta, 0) || delta != math.Trunc(delta) || delta < 1 || delta > 100 {
		j.Status = "FAILED"
		j.GuardState = "INVALID_SCOPED_ARGUMENTS"
		if saveTool(path, j) != nil {
			j.Status = "EXECUTION_UNKNOWN"
		}
		return
	}
	j.CallID = operation
	j.TraceID = digest([]byte(operation))[:32]
	j.GuardState = "COMMIT_UNCONFIRMED"
	if saveTool(path, j) != nil {
		return
	}
	var effect scopedEffect
	if r.guardPost("scoped/execute", map[string]any{"grantId": grant, "operationId": operation, "resourceId": resource, "delta": int64(delta)}, &effect) != nil {
		return
	}
	if effect.GrantID != grant || effect.OperationID != operation || effect.ResourceID != resource || effect.Delta != int64(delta) || !canonicalUUID(effect.RootID) {
		return
	}
	if effect.State == "REJECTED" && effect.Source == "TOOL_STORE_NO_COMMIT" {
		j.Status = "FAILED"
		j.GuardState = effect.Reason
		j.EffectSource = effect.Source
		if saveTool(path, j) != nil {
			j.Status = "EXECUTION_UNKNOWN"
		}
		return
	}
	if effect.State != "COMMITTED" || effect.Source != "TOOL_STORE_VERIFIED" || !canonicalUUID(effect.EffectID) || effect.CounterValue == nil || effect.TraceID != digest([]byte(r.runtimeKey + "|trace|" + j.TraceID))[:32] {
		return
	}
	j.Status = "SUCCEEDED"
	j.GuardState = "TOOL_STORE_VERIFIED"
	j.EffectSource = effect.Source
	j.ReceiptConfirmed = true
	j.Executed = true
	// Keep the raw trace: runtime ingestion namespaces it exactly once.
	j.Output = map[string]any{"counterValue": *effect.CounterValue, "effectId": effect.EffectID, "rootId": effect.RootID, "operationId": operation, "evidenceSource": effect.Source}
	if saveTool(path, j) != nil {
		j.Status = "EXECUTION_UNKNOWN"
		j.Output = nil
		j.ReceiptConfirmed = false
	}
}
