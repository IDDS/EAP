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
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

const sumResponse = `{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"provider-call","type":"function","function":{"name":"calculate_sum","arguments":"{\"numbers\":[12,30]}"}}]}}]}`
const sumTrace = "1234567890abcdef1234567890abcdef"

func TestKagentActualToolCallbackAndDurableReplay(t *testing.T) {
	var model, checks, claims, receipts atomic.Int32
	var effectID string
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Header.Get("X-EAP-Runtime-Key") != "instance" || req.Header.Get("X-EAP-Runtime-Token") != "synthetic-lease" {
			t.Error("identity not preserved")
		}
		var body map[string]any
		json.NewDecoder(req.Body).Decode(&body)
		if strings.HasSuffix(req.URL.Path, "/chat/completions") {
			model.Add(1)
			if req.Header.Get("X-EAP-Task-Key") != "task-1" || req.Header.Get("X-EAP-Trace-Id") != sumTrace ||
				!equalJSON(body["tools"], kagentSumTools()) || body["parallel_tool_calls"] != false {
				t.Error("model scope or frozen tools lost")
			}
			io.WriteString(w, sumResponse)
			return
		}
		id, _ := body["callId"].(string)
		if id == "provider-call" || !canonicalUUID(id) {
			t.Error("provider ID became EAP authority")
		}
		switch req.URL.Path {
		case "/api/runtime-access/v1/tools/check":
			checks.Add(1)
			effectID = id
			if body["traceId"] != sumTrace || body["toolName"] != "calculate_sum" {
				t.Error("tool attribution lost")
			}
			fmt.Fprintf(w, `{"data":{"callId":%q,"state":"APPROVED"}}`, id)
		case "/api/runtime-access/v1/tools/claim":
			claims.Add(1)
			if id != effectID {
				t.Error("effect identity changed")
			}
			fmt.Fprintf(w, `{"data":{"execute":true,"call":{"callId":%q,"state":"CLAIMED"}}}`, id)
		case "/api/runtime-access/v1/tools/receipt":
			receipts.Add(1)
			if id != effectID || body["outcome"] != "SUCCEEDED" {
				t.Error("invalid receipt")
			}
			fmt.Fprintf(w, `{"data":{"callId":%q,"state":"SUCCEEDED"}}`, id)
		default:
			t.Errorf("unexpected request %s", req.URL.Path)
			w.WriteHeader(404)
		}
	}))
	defer s.Close()
	r := sumRunner(s, t.TempDir())
	answer, err := r.askContext(context.Background(), "sum 12 and 30", sumTrace, "task-1", nil, nil)
	var result map[string]any
	if err != nil || json.Unmarshal([]byte(answer), &result) != nil || result["sum"] != float64(42) || result["receiptConfirmed"] != true {
		t.Fatalf("unconfirmed actual SDK tool output: %q %v", answer, err)
	}
	// Simulate a new process with no in-memory task result.
	restarted := sumRunner(s, r.toolDir)
	replayed, err := restarted.askContext(context.Background(), "sum 12 and 30", sumTrace, "task-1", nil, nil)
	if err != nil || replayed != answer {
		t.Fatal("durable receipt not replayed", err)
	}
	if _, err := restarted.askContext(context.Background(), "changed", sumTrace, "task-1", nil, nil); err == nil {
		t.Fatal("changed invocation accepted")
	}
	if model.Load() != 1 || checks.Load() != 1 || claims.Load() != 1 || receipts.Load() != 1 {
		t.Fatalf("extra effects: model=%d check=%d claim=%d receipt=%d", model.Load(), checks.Load(), claims.Load(), receipts.Load())
	}
	if r.toolStats()["executed"] != 1 {
		t.Fatal("tool execution evidence missing")
	}
	if attrs := r.modelOutputAttributes(nil); attrs["eap.model.finish_reason"] != "" || attrs["eap.model.response_state"] != "KAGENT_COMPLETED" {
		t.Fatal("agent completion invented a provider finish", attrs)
	}
}

func sumRunner(s *httptest.Server, dir string) *runner {
	return &runner{gateway: s.URL, client: s.Client(), runtimeKey: "instance", runtimeToken: "synthetic-lease",
		system: "original system", model: "synthetic", maxTokens: 128, toolsEnabled: true, toolDir: dir, kagentToolMode: "CALCULATE_SUM_V1"}
}

func TestKagentToolRefusalUnknownAndCancellationNeverReplay(t *testing.T) {
	for _, mode := range []string{"denied", "ask", "check-lost", "claim-lost", "claim-duplicate", "receipt-lost", "receipt-wrong", "cancel-after-check"} {
		t.Run(mode, func(t *testing.T) {
			var models, claims, receipts atomic.Int32
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				var body map[string]any
				json.NewDecoder(req.Body).Decode(&body)
				id, _ := body["callId"].(string)
				switch {
				case strings.HasSuffix(req.URL.Path, "completions"):
					models.Add(1)
					io.WriteString(w, sumResponse)
				case strings.HasSuffix(req.URL.Path, "/check"):
					state := "APPROVED"
					if mode == "denied" {
						state = "DENIED"
					}
					if mode == "ask" {
						state = "WAITING_APPROVAL"
					}
					if mode == "check-lost" {
						w.WriteHeader(503)
						return
					}
					if mode == "cancel-after-check" {
						cancel()
					}
					fmt.Fprintf(w, `{"data":{"callId":%q,"state":%q}}`, id, state)
				case strings.HasSuffix(req.URL.Path, "/claim"):
					claims.Add(1)
					if mode == "claim-lost" {
						w.WriteHeader(503)
						return
					}
					fmt.Fprintf(w, `{"data":{"execute":%t,"call":{"callId":%q,"state":"CLAIMED"}}}`, mode != "claim-duplicate", id)
				case strings.HasSuffix(req.URL.Path, "/receipt"):
					receipts.Add(1)
					if mode == "receipt-lost" {
						w.WriteHeader(503)
						return
					}
					if mode == "receipt-wrong" {
						id = "another-call"
					}
					fmt.Fprintf(w, `{"data":{"callId":%q,"state":"SUCCEEDED"}}`, id)
				default:
					t.Error("unexpected path", req.URL.Path)
				}
			}))
			defer s.Close()
			r := sumRunner(s, t.TempDir())
			answer, err := r.askContext(ctx, "input", sumTrace, "task", nil, nil)
			if answer != "" || err == nil {
				t.Fatalf("unconfirmed result escaped: %q %v", answer, err)
			}
			unknown := mode != "denied" && mode != "ask" && mode != "cancel-after-check"
			if unknown && !errors.Is(err, errModelOutcomeUnknown) {
				t.Fatalf("lost UNKNOWN: %v", err)
			}
			if mode == "receipt-lost" || mode == "receipt-wrong" {
				stats := r.toolStats()
				if stats["executed"] != 1 || stats["unknown"] != 1 {
					t.Fatalf("unconfirmed effect not visible: %+v", stats)
				}
			}
			oldClaims, oldReceipts := claims.Load(), receipts.Load()
			restarted := sumRunner(s, r.toolDir)
			if answer, err := restarted.askContext(context.Background(), "input", sumTrace, "task", nil, nil); answer != "" || err == nil {
				t.Fatal("failed/unknown receipt was replayed as success")
			}
			if models.Load() != 1 || oldClaims != claims.Load() || oldReceipts != receipts.Load() {
				t.Fatal("uncertain work repeated")
			}
			if (mode == "denied" || mode == "ask" || mode == "check-lost" || mode == "cancel-after-check") && oldClaims != 0 {
				t.Fatal("unapproved claim")
			}
		})
	}
}

func TestKagentRejectsUnboundedToolResponsesBeforeADK(t *testing.T) {
	cases := []string{
		strings.Replace(sumResponse, `"calculate_sum"`, `"shell"`, 1),
		strings.Replace(sumResponse, `[12,30]`, `[]`, 1),
		strings.Replace(sumResponse, `[12,30]`, `[1000000000001]`, 1),
		strings.Replace(sumResponse, `"finish_reason":"tool_calls"`, `"finish_reason":"length"`, 1),
		strings.Replace(sumResponse, `"finish_reason":"tool_calls"`, `"finish_reason":"unknown"`, 1),
		strings.Replace(sumResponse, `"id":"provider-call"`, `"id":""`, 1),
		`{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[]}}]}`,
		`{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[{},{}]}}]}`,
		strings.Replace(sumResponse, `\"numbers\":[12,30]`, `\"numbers\":[12,30],\"url\":\"https://invalid\"`, 1),
	}
	for i, response := range cases {
		t.Run(fmt.Sprint(i), func(t *testing.T) {
			var count atomic.Int32
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				count.Add(1)
				if !strings.HasSuffix(req.URL.Path, "completions") {
					t.Error("invalid tool reached authorization")
				}
				io.WriteString(w, response)
			}))
			defer s.Close()
			r := sumRunner(s, t.TempDir())
			if answer, err := r.askContext(context.Background(), "input", sumTrace, "task", nil, nil); err == nil || answer != "" {
				t.Fatalf("invalid tool accepted: %s", response)
			}
			if count.Load() != 1 {
				t.Fatal("extra request", count.Load())
			}
		})
	}
}

func TestKagentToolIntentBeforeDispatchAndSafeTextFallback(t *testing.T) {
	var count atomic.Int32
	var dir string
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		count.Add(1)
		files, _ := filepath.Glob(filepath.Join(dir, "kagent-invocations", "*.json"))
		if len(files) != 1 {
			t.Error("model dispatched before durable intent")
		}
		raw, _ := os.ReadFile(files[0])
		if !strings.Contains(string(raw), `"status":"UNKNOWN"`) || strings.Contains(string(raw), "original system") || strings.Contains(string(raw), "synthetic-lease") {
			t.Error("invalid intent or sensitive data stored")
		}
		io.WriteString(w, `{"choices":[{"finish_reason":"stop","message":{"content":"no tool needed"}}]}`)
	}))
	defer s.Close()
	dir = t.TempDir()
	r := sumRunner(s, dir)
	disabled := sumRunner(s, dir)
	disabled.toolsEnabled = false
	if _, err := disabled.askContext(context.Background(), "input", sumTrace, "task", nil, nil); err == nil || count.Load() != 0 {
		t.Fatal("disabled tools dispatched")
	}
	answer, err := r.askContext(context.Background(), "input", sumTrace, "", nil, nil)
	if err != nil || answer != "no tool needed" || count.Load() != 1 {
		t.Fatal(answer, err)
	}
	if _, err := sumRunner(s, dir).askContext(context.Background(), "input", sumTrace, "", nil, nil); err != nil || count.Load() != 1 {
		t.Fatal("chat trace was not durable")
	}
}
