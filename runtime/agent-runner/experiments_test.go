package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func experimentFixture(t *testing.T, status int) (*runner, *atomic.Int32, func()) {
	t.Helper()
	calls := new(atomic.Int32)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		calls.Add(1)
		if status != 200 {
			w.WriteHeader(status)
			return
		}
		var body struct {
			Messages    []message `json:"messages"`
			Temperature int       `json:"temperature"`
		}
		if json.NewDecoder(req.Body).Decode(&body) != nil || len(body.Messages) != 2 || body.Messages[0].Content != "candidate" || body.Messages[1].Content != "sample" {
			t.Error("not an isolated frozen candidate")
		}
		if req.Header.Get("X-EAP-Gateway-Token") != "gateway-test" || req.Header.Get("X-EAP-Trace-Id") != strings.Repeat("a", 32) {
			t.Error("missing attribution")
		}
		writeJSON(w, map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": "new output"}, "finish_reason": "stop"}}, "usage": map[string]int{"prompt_tokens": 7, "completion_tokens": 3}})
	}))
	r := &runner{experimentDir: t.TempDir(), gateway: server.URL, gwToken: "gateway-test", taskToken: "task-test", agentID: "7", model: "test-model", system: "baseline", maxTokens: 1024, client: server.Client()}
	return r, calls, server.Close
}
func experimentCall(r *runner, in experimentInput, token string) *httptest.ResponseRecorder {
	raw, _ := json.Marshal(in)
	req := httptest.NewRequest("POST", "/eap/experiments/run", bytes.NewReader(raw))
	req.Header.Set("Authorization", "Bearer "+token)
	out := httptest.NewRecorder()
	r.experiment(out, req)
	return out
}
func experimentRequest(r *runner) experimentInput {
	return experimentInput{TaskKey: "experiment:test:1", TraceID: strings.Repeat("a", 32), ProfileDigest: r.experimentProfileDigest(), SystemPrompt: "candidate", Input: "sample", MaxTokens: 128}
}
func TestExperimentsAreIsolatedAndConcurrentReplaysSpendOnce(t *testing.T) {
	r, calls, closeServer := experimentFixture(t, 200)
	defer closeServer()
	in := experimentRequest(r)
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			out := experimentCall(r, in, "task-test")
			if out.Code != 200 || !strings.Contains(out.Body.String(), "new output") {
				t.Error("missing replayable receipt")
			}
		}()
	}
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatal("duplicate model spend or history mutation")
	}
	in.Input = "different"
	if experimentCall(r, in, "task-test").Code != 409 || calls.Load() != 1 {
		t.Fatal("different payload replay accepted")
	}
}
func TestExperimentUnknownIntentIsNotRetriedAfterRestart(t *testing.T) {
	r, calls, closeServer := experimentFixture(t, 200)
	defer closeServer()
	in := experimentRequest(r)
	raw, _ := json.Marshal(in)
	path := filepath.Join(r.experimentDir, digest([]byte(in.TaskKey))+".json")
	if saveExperiment(path, experimentJournal{Fingerprint: digest(raw), Result: experimentResult{Status: "UNKNOWN", TraceID: in.TraceID}}) != nil {
		t.Fatal("failed to persist intent")
	}
	out := experimentCall(r, in, "task-test")
	if out.Code != 200 || !strings.Contains(out.Body.String(), "UNKNOWN") || calls.Load() != 0 {
		t.Fatal("uncertain intent replayed model call")
	}
}
func TestExperimentUpstreamFailureIsUnknownAndNotFreeRetry(t *testing.T) {
	r, calls, closeServer := experimentFixture(t, 503)
	defer closeServer()
	for range 2 {
		out := experimentCall(r, experimentRequest(r), "task-test")
		if out.Code != 200 || !strings.Contains(out.Body.String(), "UNKNOWN") {
			t.Fatal("failure incorrectly completed")
		}
	}
	if calls.Load() != 1 {
		t.Fatal("upstream spend retried")
	}
}
func TestExperimentRejectsUnauthorizedStaleProfileAndUnsafeLimits(t *testing.T) {
	r, calls, closeServer := experimentFixture(t, 200)
	defer closeServer()
	in := experimentRequest(r)
	if experimentCall(r, in, "wrong").Code != 401 {
		t.Fatal("authentication missing")
	}
	in.MaxTokens = 257
	if experimentCall(r, in, "task-test").Code != 400 {
		t.Fatal("limit missing")
	}
	in.MaxTokens = 128
	in.ProfileDigest = strings.Repeat("b", 64)
	if experimentCall(r, in, "task-test").Code != 409 {
		t.Fatal("profile mismatch ignored")
	}
	in = experimentRequest(r)
	r.experimentDir = ""
	if experimentCall(r, in, "task-test").Code != 404 {
		t.Fatal("runtime without a durable experiment journal admitted")
	}
	if calls.Load() != 0 {
		t.Fatal("rejected requests spent models")
	}
}

func TestBudgetAdmissionProofSeparatesRejectionFromUnknownUpstream429(t *testing.T) {
	for _, rejected := range []bool{true, false} {
		r, _, closeFixture := experimentFixture(t, 200)
		closeFixture()
		var calls atomic.Int32
		gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
			calls.Add(1)
			if rejected {
				w.Header().Set("X-EAP-Admission-Outcome", "REJECTED")
			}
			w.WriteHeader(429)
			writeJSON(w, map[string]any{"error": map[string]any{"code": "budget_exhausted"}})
		}))
		r.gateway = gateway.URL
		r.client = gateway.Client()
		for range 2 {
			var result experimentResult
			_ = json.Unmarshal(experimentCall(r, experimentRequest(r), "task-test").Body.Bytes(), &result)
			if rejected && (result.Status != "FAILED" || result.ReasonCode != "BUDGET_EXHAUSTED") {
				t.Error("proven rejection became unknown")
			}
			if !rejected && result.Status != "UNKNOWN" {
				t.Error("upstream error incorrectly freed uncertainty")
			}
		}
		if calls.Load() != 1 {
			t.Error("rejected or unknown request retried")
		}
		gateway.Close()
	}
}

func TestControlledTaskToolsDoNotLeakIntoIndependentExperiments(t *testing.T) {
	for _, test := range []struct {
		name             string
		providerToolCall bool
	}{
		{"plain_text", false},
		{"provider_tool_call", true},
	} {
		t.Run(test.name, func(t *testing.T) {
			var calls atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				if req.URL.Path == "/api/runtime-access/v1/traces" {
					w.WriteHeader(http.StatusOK)
					return
				}
				calls.Add(1)
				if req.URL.Path != "/api/runtime-access/v1/chat/completions" || req.Header.Get("X-EAP-Runtime-Key") != "space/owned" || req.Header.Get("X-EAP-Runtime-Token") != "runtime-test" || req.Header.Get("X-EAP-Gateway-Token") != "" {
					t.Error("experiment did not use its instance identity")
				}
				var body map[string]json.RawMessage
				if json.NewDecoder(req.Body).Decode(&body) != nil {
					t.Fatal("invalid model request")
				}
				var messages []message
				_ = json.Unmarshal(body["messages"], &messages)
				if len(messages) != 2 || messages[0].Content != "candidate" || messages[1].Content != "sample" || body["tools"] != nil || body["tool_choice"] != nil {
					t.Error("session history or task tools leaked into experiment")
				}
				msg := map[string]any{"content": "new output"}
				finish := "stop"
				if test.providerToolCall {
					msg["tool_calls"] = []any{map[string]any{"function": map[string]string{"name": "controlled_counter_increment"}}}
					finish = "tool_calls"
				}
				writeJSON(w, map[string]any{"choices": []any{map[string]any{"message": msg, "finish_reason": finish}}})
			}))
			defer gateway.Close()
			r := &runner{experimentDir: t.TempDir(), toolDir: t.TempDir(), toolsEnabled: true, runtimeKey: "space/owned", runtimeToken: "runtime-test", agentID: "7", model: "test-model", system: "baseline", maxTokens: 1024, client: gateway.Client(), gateway: gateway.URL}
			profileReq := httptest.NewRequest("GET", "/eap/experiments/profile", nil)
			profileReq.Header.Set("Authorization", "Bearer runtime-test")
			profile := httptest.NewRecorder()
			r.experimentProfile(profile, profileReq)
			if profile.Code != 200 || !strings.Contains(profile.Body.String(), `"tools":"DISABLED"`) || !strings.Contains(profile.Body.String(), `"context":"EMPTY"`) {
				t.Fatal("isolated profile unavailable")
			}
			in := experimentRequest(r)
			if experimentCall(r, in, "wrong").Code != 401 || calls.Load() != 0 {
				t.Fatal("unauthorized experiment spent models")
			}
			for range 2 {
				out := experimentCall(r, in, "runtime-test")
				var result experimentResult
				_ = json.Unmarshal(out.Body.Bytes(), &result)
				want := "SUCCEEDED"
				if test.providerToolCall {
					want = "UNKNOWN"
				}
				if out.Code != 200 || result.Status != want {
					t.Fatal("unexpected isolated experiment receipt")
				}
			}
			files, _ := filepath.Glob(filepath.Join(r.toolDir, "*.json"))
			if calls.Load() != 1 || len(files) != 0 {
				t.Fatal("duplicate spend, tool execution or history mutation")
			}
		})
	}
}

func TestExperimentKeepsEmptyTextReceiptAndRejectsNonTextProviderContent(t *testing.T) {
	for _, test := range []struct {
		name    string
		present bool
		content any
		finish  string
		known   bool
	}{
		{"empty_stop", true, "", "stop", true},
		{"empty_length", true, "", "length", true},
		{"missing", false, nil, "stop", false},
		{"null", true, nil, "length", false},
		{"object", true, map[string]string{"text": "invalid"}, "stop", false},
		{"array", true, []string{"invalid"}, "stop", false},
	} {
		t.Run(test.name, func(t *testing.T) {
			var calls atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				calls.Add(1)
				msg := map[string]any{"role": "assistant"}
				if test.present {
					msg["content"] = test.content
				}
				writeJSON(w, map[string]any{"choices": []any{map[string]any{"message": msg, "finish_reason": test.finish}}, "usage": map[string]int{"prompt_tokens": 7, "completion_tokens": 128}})
			}))
			defer gateway.Close()
			r := &runner{experimentDir: t.TempDir(), gateway: gateway.URL, gwToken: "gateway-test", taskToken: "task-test", agentID: "7", model: "test-model", system: "baseline", maxTokens: 1024, client: gateway.Client()}
			in := experimentRequest(r)
			for range 2 {
				response := experimentCall(r, in, "task-test")
				var body map[string]any
				if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &body) != nil {
					t.Fatal("receipt missing")
				}
				if test.known {
					output, present := body["output"].(string)
					if body["status"] != "SUCCEEDED" || !present || output != "" || body["finishReason"] != test.finish || body["usage"] == nil {
						t.Error("known empty text was omitted or became unknown")
					}
				} else if body["status"] != "UNKNOWN" {
					t.Error("missing or non-text provider content became a known result")
				}
			}
			if calls.Load() != 1 {
				t.Error("original receipt replay issued an extra provider call")
			}
		})
	}
}
