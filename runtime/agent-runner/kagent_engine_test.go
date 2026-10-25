//go:build kagent

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
)

func TestKagentActualEngineAndExplicitHistory(t *testing.T) {
	var count atomic.Int32
	history := []message{{"user", "earlier"}, {"assistant", "recorded answer"}}
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		count.Add(1)
		if req.URL.Path != "/api/runtime-access/v1/chat/completions" || req.Header.Get("X-EAP-Runtime-Key") != "isolated-runtime" || req.Header.Get("X-EAP-Runtime-Token") != "synthetic-lease" || req.Header.Get("X-EAP-Task-Key") != "original-task" || req.Header.Get("X-EAP-Trace-Id") != "original-trace" {
			t.Error("original EAP identity/attribution lost")
		}
		var payload struct {
			Messages  []message         `json:"messages"`
			MaxTokens int               `json:"max_tokens"`
			Tools     json.RawMessage   `json:"tools"`
			Thinking  map[string]string `json:"thinking"`
		}
		if json.NewDecoder(req.Body).Decode(&payload) != nil {
			t.Error("invalid model payload")
		}
		expected := append([]message{{"system", "original system"}}, history...)
		expected = append(expected, message{"user", "current"})
		if !reflect.DeepEqual(payload.Messages, expected) || payload.MaxTokens != 128 || len(payload.Tools) != 0 || payload.Thinking["type"] != "disabled" {
			t.Errorf("unexpected bounded input: %+v", payload)
		}
		io.WriteString(w, `{"choices":[{"finish_reason":"stop","message":{"content":"verified answer"}}]}`)
	}))
	defer s.Close()
	r := &runner{gateway: s.URL, client: s.Client(), runtimeKey: "isolated-runtime", runtimeToken: "synthetic-lease", model: "deepseek-flash", maxTokens: 128, thinkingMode: "DISABLED", system: "original system"}
	answer, err := r.askContext(context.Background(), "current", "original-trace", "original-task", history, nil)
	if err != nil || answer != "verified answer" || count.Load() != 1 {
		t.Fatalf("answer=%q err=%v calls=%d", answer, err, count.Load())
	}
	if runnerFramework != "KAGENT_ADK" || runnerEngineVersion != kagentVersion {
		t.Fatal("actual Kagent build not selected")
	}
	if r.modelOutputAttributes(nil)["service.version"] != "kagent/v1.0.0-alpha14.673d181ec269" {
		t.Fatal("framework version missing from allowed trace metadata")
	}
}

func TestKagentPreservesProviderBoundariesWithoutRetry(t *testing.T) {
	cases := []struct {
		name, body, code string
		status           int
		unknown          bool
	}{
		{"truncated", `{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}`, "MODEL_OUTPUT_TRUNCATED", 200, false},
		{"tool-call", `{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[{}]}}]}`, "MODEL_TOOL_CALL_UNSUPPORTED", 200, false},
		{"unknown-finish", `{"choices":[{"finish_reason":"future","message":{"content":"looks complete"}}]}`, "MODEL_FINISH_UNCONFIRMED", 200, false},
		{"empty", `{"choices":[{"finish_reason":"stop","message":{"content":" "}}]}`, "MODEL_OUTPUT_EMPTY", 200, false},
		{"ambiguous-receipt", `{"choices":[]}`, "", 200, true},
		{"server-error", `{}`, "", 503, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var count atomic.Int32
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				count.Add(1)
				w.WriteHeader(c.status)
				io.WriteString(w, c.body)
			}))
			defer s.Close()
			r := &runner{gateway: s.URL, client: s.Client(), model: "synthetic", system: "system", maxTokens: 32}
			answer, err := r.askContext(context.Background(), "input", "trace", "task", nil, nil)
			if answer != "" || err == nil || count.Load() != 1 {
				t.Fatalf("answer=%q err=%v calls=%d", answer, err, count.Load())
			}
			var output *modelOutputError
			if c.unknown {
				if !errors.Is(err, errModelOutcomeUnknown) {
					t.Fatalf("lost UNKNOWN: %v", err)
				}
			} else if !errors.As(err, &output) || output.code != c.code {
				t.Fatalf("lost provider boundary %s: %v", c.code, err)
			}
		})
	}
}

func TestKagentTransportRejectsExtraCallAndForgedPayload(t *testing.T) {
	var count atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		count.Add(1)
		io.WriteString(w, `{"choices":[{"finish_reason":"stop","message":{"content":"answer"}}]}`)
	}))
	defer s.Close()
	r := &runner{gateway: s.URL, client: s.Client(), model: "synthetic", maxTokens: 32}
	messages := []message{{"system", "system"}, {"user", "original"}}
	guard := &kagentTransport{runner: r, messages: messages}
	body := `{"model":"synthetic","messages":[{"role":"system","content":"system"},{"role":"user","content":"original"}]}`
	for i := 0; i < 2; i++ {
		req, _ := http.NewRequest(http.MethodPost, "https://eap-model.invalid/v1/chat/completions", strings.NewReader(body))
		res, err := guard.RoundTrip(req)
		if i == 0 {
			if err != nil {
				t.Fatal(err)
			}
			res.Body.Close()
		} else if err == nil || !errors.Is(guard.err, errModelOutcomeUnknown) {
			t.Fatal("second call was not blocked as unknown")
		}
	}
	if count.Load() != 1 {
		t.Fatalf("duplicate gateway call: %d", count.Load())
	}
	for _, changed := range []string{strings.Replace(body, "original", "modified", 1), strings.Replace(body, `"model":`, `"tools":[{}],"model":`, 1)} {
		guard = &kagentTransport{runner: r, messages: messages}
		req, _ := http.NewRequest(http.MethodPost, "https://eap-model.invalid/v1/chat/completions", strings.NewReader(changed))
		if _, err := guard.RoundTrip(req); err == nil {
			t.Fatal("modified data accepted")
		}
	}
	if count.Load() != 1 {
		t.Fatal("rejected data reached gateway")
	}
}

func TestKagentCancellationAndTaskIsolation(t *testing.T) {
	var count atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		count.Add(1)
		var body struct {
			Messages []message `json:"messages"`
		}
		json.NewDecoder(req.Body).Decode(&body)
		if len(body.Messages) != 2 {
			t.Error("implicit conversation history leaked")
		}
		fmt.Fprint(w, `{"choices":[{"finish_reason":"stop","message":{"content":"answer"}}]}`)
	}))
	defer s.Close()
	r := &runner{gateway: s.URL, client: s.Client(), model: "synthetic", maxTokens: 32}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := r.askContext(ctx, "canceled", "trace", "canceled", nil, nil); err == nil || count.Load() != 0 {
		t.Fatal("canceled request dispatched")
	}
	for _, key := range []string{"task-a", "task-b"} {
		if _, err := r.askContext(context.Background(), key, "trace", key, nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	if count.Load() != 2 {
		t.Fatalf("calls=%d", count.Load())
	}
}
