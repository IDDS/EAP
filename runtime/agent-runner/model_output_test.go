package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

// Regression for the real three-Agent sample: transport success with truncated
// content must not advance the workflow or pollute later conversation history.
func TestOrdinaryTaskRejectsIncompleteModelOutput(t *testing.T) {
	for _, tc := range []struct{ name, finish, content, reason string }{
		{"truncated", "length", `{"facts":[`, "MODEL_OUTPUT_TRUNCATED"},
		{"empty", "stop", "  \n", "MODEL_OUTPUT_EMPTY"},
		{"missing-finish", "", "apparently complete", "MODEL_FINISH_UNCONFIRMED"},
		{"filtered", "content_filter", "partial", "MODEL_OUTPUT_FILTERED"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				if q.URL.Path != "/api/gateway/v1/chat/completions" {
					w.WriteHeader(200)
					return
				}
				calls.Add(1)
				writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": tc.finish, "message": map[string]any{"content": tc.content}}}})
			}))
			defer gateway.Close()
			r := &runner{handled: map[string]string{}, gateway: gateway.URL, taskToken: "synthetic-task-token", client: gateway.Client()}
			for range 2 {
				request := httptest.NewRequest(http.MethodPost, "/eap/task", strings.NewReader(`{"taskKey":"sample:incomplete","objective":"synthetic evidence"}`))
				request.Header.Set("Authorization", "Bearer synthetic-task-token")
				response := httptest.NewRecorder()
				r.task(response, request)
				var receipt struct{ Status, Output, ReasonCode string }
				if json.Unmarshal(response.Body.Bytes(), &receipt) != nil || receipt.Status != "FAILED" || receipt.Output != "" || receipt.ReasonCode != tc.reason {
					t.Errorf("incomplete output reported as success or wrong reason: %s", response.Body.String())
				}
			}
			if calls.Load() != 1 {
				t.Errorf("failed task replayed or contaminated history: calls=%d", calls.Load())
			}
		})
	}
}

func TestOrdinaryTaskCachesConcurrentFailureAndRejectsChangedIntent(t *testing.T) {
	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if strings.HasSuffix(q.URL.Path, "/chat/completions") {
			calls.Add(1)
			writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "length", "message": map[string]any{"content": "PRIVATE_PARTIAL"}}}})
		} else {
			var telemetry map[string]any
			json.NewDecoder(q.Body).Decode(&telemetry)
			raw, _ := json.Marshal(telemetry)
			if path := os.Getenv("EAP_RUNNER_OTLP_FIXTURE"); path != "" {
				if err := os.WriteFile(path, raw, 0600); err != nil {
					t.Error(err)
				}
			}
			for _, required := range []string{"MODEL_OUTPUT_TRUNCATED", "eap.model.finish_reason", "length", "eap.model.request.generation_limit", "2048", "DISABLED"} {
				if !strings.Contains(string(raw), required) {
					t.Errorf("missing safe observation %s", required)
				}
			}
			if strings.Contains(string(raw), "PRIVATE_PARTIAL") {
				t.Error("partial content leaked into telemetry")
			}
		}
	}))
	defer gateway.Close()
	r := &runner{gateway: gateway.URL, taskToken: "synthetic", client: gateway.Client(), maxTokens: 2048, thinkingMode: "DISABLED", model: "deepseek-flash"}
	invoke := func(objective string, expected int) {
		req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(`{"taskKey":"concurrent","objective":"`+objective+`"}`))
		req.Header.Set("Authorization", "Bearer synthetic")
		out := httptest.NewRecorder()
		r.task(out, req)
		if out.Code != expected || strings.Contains(out.Body.String(), "PRIVATE_PARTIAL") {
			t.Errorf("unexpected response: %d %s", out.Code, out.Body.String())
		}
	}
	var wg sync.WaitGroup
	for range 4 {
		wg.Add(1)
		go func() { defer wg.Done(); invoke("same", 200) }()
	}
	wg.Wait()
	invoke("changed", 409)
	if calls.Load() != 1 {
		t.Fatal("incomplete concurrent task was replayed or recorded in history")
	}
}

func TestOrdinaryOutputParserRejectsUnsupportedAndUnknownReceipts(t *testing.T) {
	for _, body := range []string{
		`{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[{}]}}]}`,
		`{"choices":[{"finish_reason":"stop","message":{"content":"text","function_call":{"name":"x"}}}]}`,
		`{"choices":[{"finish_reason":"stop","message":{"content":"text","refusal":"PRIVATE_REFUSAL"}}]}`,
		`{"choices":[{"finish_reason":"PRIVATE_ENUM","message":{"content":"text"}}]}`,
		`{"choices":[{"finish_reason":"stop","message":{"content":null}}]}`,
		`{"choices":[`, `{"choices":[]}`, `{"choices":[{},{}]}`,
	} {
		answer, err := completedModelOutput([]byte(body))
		if answer != "" || err == nil || strings.Contains(err.Error(), "PRIVATE") {
			t.Fatalf("unsafe output: %q %v", answer, err)
		}
	}
}

func TestModelControlsReachOrdinaryAndExperimentRequests(t *testing.T) {
	for _, mode := range []string{"", "PROVIDER_DEFAULT", "DISABLED"} {
		t.Run(mode, func(t *testing.T) {
			var calls atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				var payload map[string]any
				if json.NewDecoder(q.Body).Decode(&payload) != nil {
					t.Error("bad request")
				}
				if payload["max_tokens"] != float64(2048) {
					t.Error("max tokens not applied")
				}
				thinking, supplied := payload["thinking"]
				if supplied != (mode == "DISABLED") {
					t.Error("provider default was overridden or explicit control dropped")
				}
				if supplied && thinking.(map[string]any)["type"] != "disabled" {
					t.Error("wrong provider control")
				}
				calls.Add(1)
				writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": "complete"}}}})
			}))
			defer gateway.Close()
			r := &runner{gateway: gateway.URL, client: gateway.Client(), maxTokens: 2048, thinkingMode: mode, model: "deepseek-flash"}
			profile := r.experimentProfileValue()
			if (profile["thinkingMode"] == "DISABLED") != (mode == "DISABLED") {
				t.Fatal("profile does not freeze actual control")
			}
			if answer, err := r.askScoped("input", "trace", "task"); err != nil || answer != "complete" {
				t.Fatal("normal stop rejected", err)
			}
			if result := r.executeExperiment(experimentInput{Input: "input", MaxTokens: 2048}); result.Status != "SUCCEEDED" {
				t.Fatal("experiment failed")
			}
			if calls.Load() != 2 {
				t.Fatal("history or invocation count changed")
			}
		})
	}
	if validThinkingMode("other", "DISABLED") || validThinkingMode("deepseek-flash", "INVALID") {
		t.Fatal("unsupported thinking config accepted")
	}
}

func TestUnknownTaskReceiptIsNotRetried(t *testing.T) {
	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if strings.HasSuffix(q.URL.Path, "/chat/completions") {
			calls.Add(1)
			w.Write([]byte(`{"choices":[`))
		}
	}))
	defer gateway.Close()
	r := &runner{gateway: gateway.URL, client: gateway.Client(), taskToken: "synthetic"}
	for range 2 {
		req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(`{"taskKey":"unknown","objective":"input"}`))
		req.Header.Set("Authorization", "Bearer synthetic")
		out := httptest.NewRecorder()
		r.task(out, req)
		if !strings.Contains(out.Body.String(), `"status":"UNKNOWN"`) {
			t.Fatal(out.Body.String())
		}
	}
	if calls.Load() != 1 {
		t.Fatal("unknown outcome was retried")
	}
}
