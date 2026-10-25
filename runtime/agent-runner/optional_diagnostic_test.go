package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestOptionalDiagnosticSharesTraceAndContainsOnlyFixedMetadata(t *testing.T) {
	for _, enabled := range []bool{false, true} {
		t.Run(map[bool]string{false: "legacy", true: "optional"}[enabled], func(t *testing.T) {
			var models, exports atomic.Int32
			var received map[string]any
			r := &runner{experimentDir: t.TempDir(), runtimeKey: "space/owned", runtimeToken: "synthetic-token", agentID: "7", model: "test-model", system: "PRIVATE_BASELINE", maxTokens: 128}
			in := experimentRequest(r)
			in.EmitDiagnostic = enabled
			in.Input = "PRIVATE_INPUT"
			in.SystemPrompt = "PRIVATE_CANDIDATE"
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				if q.URL.Path == "/api/runtime-access/v1/traces" {
					exports.Add(1)
					if json.NewDecoder(q.Body).Decode(&received) != nil {
						t.Error("bad telemetry")
					}
					return
				}
				models.Add(1)
				writeJSON(w, map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": "PRIVATE_OUTPUT"}, "finish_reason": "stop"}}})
			}))
			defer server.Close()
			r.gateway = server.URL
			r.client = server.Client()
			if experimentCall(r, in, "synthetic-token").Code != 200 || experimentCall(r, in, "synthetic-token").Code != 200 {
				t.Fatal("receipt unavailable")
			}
			if models.Load() != 1 || exports.Load() != 1 {
				t.Fatal("diagnostic caused duplicate spend/export")
			}
			resource := received["resourceSpans"].([]any)[0].(map[string]any)
			spans := resource["scopeSpans"].([]any)[0].(map[string]any)["spans"].([]any)
			want := 1
			if enabled {
				want = 2
			}
			if len(spans) != want {
				t.Fatal("wrong number of core/optional spans")
			}
			if enabled {
				diagnostic := spans[1].(map[string]any)
				if diagnostic["name"] != "agent.diagnostic" || diagnostic["traceId"] != in.TraceID || diagnostic["attributes"] != nil || diagnostic["status"] != nil {
					t.Fatal("untrusted diagnostic fields")
				}
			}
			raw, _ := json.Marshal(received)
			for _, marker := range []string{"PRIVATE_BASELINE", "PRIVATE_INPUT", "PRIVATE_CANDIDATE", "PRIVATE_OUTPUT", "synthetic-token"} {
				if strings.Contains(string(raw), marker) {
					t.Fatal("private content in diagnostic")
				}
			}
		})
	}
}

func TestLegacyExperimentCanonicalInputOmitsDiagnosticFlag(t *testing.T) {
	r := &runner{agentID: "7", model: "fixture", system: "baseline", maxTokens: 128}
	raw, _ := json.Marshal(experimentRequest(r))
	if strings.Contains(string(raw), "emitDiagnostic") {
		t.Fatal("old journal fingerprint changed")
	}
}
