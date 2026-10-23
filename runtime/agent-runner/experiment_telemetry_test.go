package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestExperimentTelemetryOneMetadataSpanAfterDurableReceipt(t *testing.T) {
	var models, exports atomic.Int32
	var payload map[string]any
	r := &runner{experimentDir: t.TempDir(), runtimeKey: "space/owned", runtimeToken: "synthetic-runtime", agentID: "7", model: "test-model", system: "private-baseline", maxTokens: 128}
	in := experimentRequest(r)
	in.SystemPrompt, in.Input = "private-candidate", "private-sample"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Header.Get("X-EAP-Runtime-Key") != r.runtimeKey || req.Header.Get("X-EAP-Runtime-Token") != r.runtimeToken || req.Header.Get("Authorization") != "" || req.Header.Get("X-EAP-Gateway-Token") != "" {
			t.Error("wrong telemetry or model identity")
		}
		if req.URL.Path == "/api/runtime-access/v1/traces" {
			exports.Add(1)
			var receipt experimentJournal
			raw, err := os.ReadFile(filepath.Join(r.experimentDir, digest([]byte(in.TaskKey))+".json"))
			if err != nil || json.Unmarshal(raw, &receipt) != nil || receipt.Result.Status != "SUCCEEDED" {
				t.Error("export precedes durable receipt")
			}
			if json.NewDecoder(req.Body).Decode(&payload) != nil {
				t.Error("invalid OTLP")
			}
			return
		}
		models.Add(1)
		writeJSON(w, map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": "private-output"}, "finish_reason": "stop"}}, "usage": map[string]int{"prompt_tokens": 7, "completion_tokens": 3}})
	}))
	defer server.Close()
	r.gateway, r.client = server.URL, server.Client()
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if experimentCall(r, in, "synthetic-runtime").Code != 200 {
				t.Error("cached receipt unavailable")
			}
		}()
	}
	wg.Wait()
	if models.Load() != 1 || exports.Load() != 1 {
		t.Fatal("duplicate model spend or missing/duplicate export")
	}
	raw, _ := json.Marshal(payload)
	for _, secret := range []string{"private-baseline", "private-candidate", "private-sample", "private-output", "synthetic-runtime", "prompt_tokens", "completion_tokens"} {
		if strings.Contains(string(raw), secret) {
			t.Fatal("content or credential in telemetry")
		}
	}
	resource := payload["resourceSpans"].([]any)[0].(map[string]any)
	span := resource["scopeSpans"].([]any)[0].(map[string]any)["spans"].([]any)[0].(map[string]any)
	if span["traceId"] != in.TraceID || span["name"] != "agent.experiment" || len(span["spanId"].(string)) != 16 || span["startTimeUnixNano"] == nil || span["endTimeUnixNano"] == nil || span["status"].(map[string]any)["code"] != float64(1) {
		t.Fatal("missing correlated metadata")
	}
}

func TestExperimentTelemetryFailureDoesNotChangeOrReplayBusinessReceipt(t *testing.T) {
	for _, mode := range []string{"collector_failure", "redirect", "timeout", "unknown", "budget_rejected", "length", "empty"} {
		t.Run(mode, func(t *testing.T) {
			var models, exports, redirects atomic.Int32
			r := &runner{experimentDir: t.TempDir(), runtimeKey: "space/owned", runtimeToken: "synthetic-runtime", agentID: "7", model: "test-model", system: "baseline", maxTokens: 128}
			redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { redirects.Add(1) }))
			defer redirect.Close()
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				if req.URL.Path == "/api/runtime-access/v1/traces" {
					exports.Add(1)
					_ = json.NewDecoder(req.Body).Decode(new(any))
					switch mode {
					case "collector_failure":
						w.WriteHeader(500)
					case "redirect":
						http.Redirect(w, req, redirect.URL, 307)
					case "timeout":
						<-req.Context().Done()
					}
					return
				}
				models.Add(1)
				if mode == "unknown" {
					w.WriteHeader(503)
					return
				}
				if mode == "budget_rejected" {
					w.Header().Set("X-EAP-Admission-Outcome", "REJECTED")
					w.WriteHeader(429)
					writeJSON(w, map[string]any{"error": map[string]string{"code": "budget_exhausted"}})
					return
				}
				output, finish := "new output", "stop"
				if mode == "length" {
					finish = "length"
				}
				if mode == "empty" {
					output = ""
				}
				writeJSON(w, map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": output}, "finish_reason": finish}}})
			}))
			defer server.Close()
			r.gateway, r.client = server.URL, server.Client()
			started := time.Now()
			var original []byte
			for i := range 2 {
				response := experimentCall(r, experimentRequest(r), "synthetic-runtime")
				var result experimentResult
				if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &result) != nil {
					t.Fatal("receipt unavailable")
				}
				want := "SUCCEEDED"
				if mode == "unknown" {
					want = "UNKNOWN"
				}
				if mode == "budget_rejected" {
					want = "FAILED"
				}
				if result.Status != want {
					t.Fatal("telemetry changed business outcome")
				}
				if mode == "timeout" && result.LatencyMS >= 1000 {
					t.Fatal("export inflated model latency")
				}
				if i == 0 {
					original = append([]byte(nil), response.Body.Bytes()...)
				} else if string(original) != response.Body.String() {
					t.Fatal("receipt changed on replay")
				}
			}
			if models.Load() != 1 || exports.Load() != 1 || redirects.Load() != 0 || time.Since(started) > 4*time.Second {
				t.Fatal("unbounded or retried telemetry/model request")
			}
		})
	}
}

func TestExperimentTelemetryRejectsAndSavedUnknownNeverExport(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { requests.Add(1) }))
	defer server.Close()
	r := &runner{experimentDir: t.TempDir(), gateway: server.URL, client: server.Client(), runtimeKey: "space/owned", runtimeToken: "synthetic-runtime", agentID: "7", model: "test-model", system: "baseline", maxTokens: 128}
	in := experimentRequest(r)
	if experimentCall(r, in, "wrong").Code != 401 {
		t.Fatal("authentication missing")
	}
	bad := in
	bad.MaxTokens = 257
	if experimentCall(r, bad, "synthetic-runtime").Code != 400 {
		t.Fatal("unsafe limit accepted")
	}
	bad = in
	bad.ProfileDigest = strings.Repeat("b", 64)
	if experimentCall(r, bad, "synthetic-runtime").Code != 409 {
		t.Fatal("stale profile accepted")
	}
	raw, _ := json.Marshal(in)
	if saveExperiment(filepath.Join(r.experimentDir, digest([]byte(in.TaskKey))+".json"), experimentJournal{Fingerprint: digest(raw), Result: experimentResult{Status: "UNKNOWN", TraceID: in.TraceID}}) != nil {
		t.Fatal("intent persistence failed")
	}
	if experimentCall(r, in, "synthetic-runtime").Code != 200 || requests.Load() != 0 {
		t.Fatal("replayed unknown intent generated evidence or spend")
	}
}
