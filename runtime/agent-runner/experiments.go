package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const experimentProtocol = "EAP_STATELESS_PROMPT_V1"

type experimentInput struct {
	EmitDiagnostic bool   `json:"emitDiagnostic,omitempty"`
	TaskKey        string `json:"taskKey"`
	TraceID        string `json:"traceId"`
	ProfileDigest  string `json:"profileDigest"`
	SystemPrompt   string `json:"systemPrompt"`
	Input          string `json:"input"`
	MaxTokens      int    `json:"maxTokens"`
}
type experimentResult struct {
	Status       string          `json:"status"`
	ReasonCode   string          `json:"reasonCode,omitempty"`
	TraceID      string          `json:"traceId"`
	Output       string          `json:"output"`
	FinishReason string          `json:"finishReason,omitempty"`
	Usage        json.RawMessage `json:"usage,omitempty"`
	LatencyMS    int64           `json:"latencyMs"`
}
type experimentJournal struct {
	Fingerprint string           `json:"fingerprint"`
	Result      experimentResult `json:"result"`
}

// Controlled task tools are independent of experiments: experiments build two fresh
// messages, expose no tool definitions, and reject provider tool-call receipts.
func (r *runner) experimentProfileValue() map[string]any {
	profile := map[string]any{"protocol": experimentProtocol, "agentId": r.agentID, "model": r.model, "systemPrompt": r.system, "maxTokens": r.maxTokens, "context": "EMPTY", "tools": "DISABLED", "temperature": 0}
	// Preserve legacy/default profile digests; explicit overrides must be frozen in the profile.
	if r.thinkingMode == "DISABLED" {
		profile["thinkingMode"] = r.thinkingMode
	}
	return profile
}
func (r *runner) experimentProfileDigest() string {
	b, _ := json.Marshal(r.experimentProfileValue())
	return digest(b)
}
func (r *runner) experimentProfile(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	if !r.authorized(req) {
		http.Error(w, "unauthorized", 401)
		return
	}
	if r.experimentDir == "" {
		http.Error(w, "experiments unavailable", 404)
		return
	}
	p := r.experimentProfileValue()
	p["profileDigest"] = r.experimentProfileDigest()
	p["diagnosticsProtocol"] = "EAP_OPTIONAL_DIAGNOSTIC_V1"
	writeJSON(w, p)
}
func saveExperiment(path string, j experimentJournal) error {
	b, err := json.Marshal(j)
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
	dir, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}
func (r *runner) experiment(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	if !r.authorized(req) {
		http.Error(w, "unauthorized", 401)
		return
	}
	if r.experimentDir == "" {
		http.Error(w, "experiments unavailable", 404)
		return
	}
	var in experimentInput
	decoder := json.NewDecoder(http.MaxBytesReader(w, req.Body, 16384))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&in) != nil || decoder.Decode(new(any)) != io.EOF ||
		!regexp.MustCompile(`^[a-zA-Z0-9_.:-]{8,96}$`).MatchString(in.TaskKey) || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(in.TraceID) ||
		len(in.SystemPrompt) > 8000 || strings.TrimSpace(in.SystemPrompt) == "" || len(in.Input) > 2000 || strings.TrimSpace(in.Input) == "" ||
		in.MaxTokens < 1 || in.MaxTokens > 256 || in.MaxTokens > r.maxTokens {
		http.Error(w, "invalid experiment", 400)
		return
	}
	r.experimentMu.Lock()
	defer r.experimentMu.Unlock()
	raw, _ := json.Marshal(in)
	fingerprint := digest(raw)
	path := filepath.Join(r.experimentDir, digest([]byte(in.TaskKey))+".json")
	if old, err := os.ReadFile(path); err == nil {
		var j experimentJournal
		if json.Unmarshal(old, &j) != nil {
			http.Error(w, "journal unavailable", 503)
			return
		}
		if j.Fingerprint != fingerprint {
			http.Error(w, "submission conflict", 409)
			return
		}
		writeJSON(w, j.Result)
		return
	} else if !os.IsNotExist(err) {
		http.Error(w, "journal unavailable", 503)
		return
	}
	if in.ProfileDigest != r.experimentProfileDigest() {
		http.Error(w, "profile changed", 409)
		return
	}
	if os.MkdirAll(r.experimentDir, 0700) != nil {
		http.Error(w, "journal unavailable", 503)
		return
	}
	files, err := filepath.Glob(filepath.Join(r.experimentDir, "*.json"))
	if err != nil || len(files) >= 256 {
		http.Error(w, "journal capacity reached", 429)
		return
	}
	j := experimentJournal{Fingerprint: fingerprint, Result: experimentResult{Status: "UNKNOWN", TraceID: in.TraceID}}
	// Durable uncertain intent precedes the model call. Recovery must never spend again.
	if saveExperiment(path, j) != nil {
		http.Error(w, "journal unavailable", 503)
		return
	}
	start := time.Now()
	j.Result = r.executeExperiment(in)
	end := time.Now()
	j.Result.LatencyMS = end.Sub(start).Milliseconds()
	if saveExperiment(path, j) != nil {
		http.Error(w, "receipt not persisted", 503)
		return
	}
	r.experimentSpan(in, j.Result, start, end)
	writeJSON(w, j.Result)
}
func (r *runner) executeExperiment(in experimentInput) experimentResult {
	result := experimentResult{Status: "UNKNOWN", TraceID: in.TraceID}
	payload := map[string]any{"model": r.model, "messages": []message{{Role: "system", Content: in.SystemPrompt}, {Role: "user", Content: in.Input}}, "max_tokens": in.MaxTokens, "temperature": 0, "stream": false}
	r.applyThinkingMode(payload)
	body, _ := json.Marshal(payload)
	r.mu.Lock()
	key, token := r.runtimeKey, r.runtimeToken
	r.mu.Unlock()
	path := "/api/gateway/v1/chat/completions"
	if key != "" {
		path = "/api/runtime-access/v1/chat/completions"
	}
	req, err := http.NewRequest(http.MethodPost, r.gateway+path, bytes.NewReader(body))
	if err != nil {
		return result
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-EAP-Agent-Id", r.agentID)
	req.Header.Set("X-EAP-Trace-Id", in.TraceID)
	if key != "" {
		req.Header.Set("X-EAP-Runtime-Key", key)
		req.Header.Set("X-EAP-Runtime-Token", token)
	} else {
		req.Header.Set("X-EAP-Gateway-Token", r.gwToken)
	}
	client := *r.client
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	response, err := client.Do(req)
	if err != nil {
		return result
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(response.Body, 65537))
	if err != nil || len(raw) > 65536 {
		return result
	}
	if response.StatusCode != 200 {
		if response.Header.Get("X-EAP-Admission-Outcome") == "REJECTED" {
			var rejected struct {
				Error struct {
					Code string `json:"code"`
				} `json:"error"`
			}
			if json.Unmarshal(raw, &rejected) == nil {
				result.Status = "FAILED"
				result.ReasonCode = "BUDGET_REQUEST_REJECTED"
				if rejected.Error.Code == "budget_exhausted" {
					result.ReasonCode = "BUDGET_EXHAUSTED"
				}
			}
		}
		return result
	}
	var output struct {
		Choices []struct {
			Message struct {
				Content   *string           `json:"content"`
				ToolCalls []json.RawMessage `json:"tool_calls"`
			} `json:"message"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
		Usage json.RawMessage `json:"usage"`
	}
	if json.Unmarshal(raw, &output) != nil || len(output.Choices) != 1 {
		return result
	}
	choice := output.Choices[0]
	if choice.Message.Content == nil || len(choice.Message.ToolCalls) > 0 || (choice.FinishReason != "stop" && choice.FinishReason != "length") {
		return result
	}
	result.Status = "SUCCEEDED"
	result.Output = *choice.Message.Content
	result.FinishReason = choice.FinishReason
	result.Usage = output.Usage
	return result
}
