package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestScopedEffectSingleCommitReplayAndDurableResult(t *testing.T) {
	var posts atomic.Int32
	root := "00000000-0000-4000-8000-000000000001"
	grant := "00000000-0000-4000-8000-000000000002"
	op := "00000000-0000-4000-8000-000000000003"
	effect := "00000000-0000-4000-8000-000000000004"
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if strings.HasSuffix(q.URL.Path, "/traces") {
			var payload map[string]any
			json.NewDecoder(q.Body).Decode(&payload)
			raw, _ := json.Marshal(payload)
			if !strings.Contains(string(raw), digest([]byte(op))[:32]) || !strings.Contains(string(raw), op) {
				t.Error("operation identity or raw trace lost")
			}
			w.WriteHeader(200)
			return
		}
		if q.URL.Path != "/api/runtime-access/v1/tools/scoped/execute" || q.Header.Get("X-EAP-Runtime-Token") != "synthetic-scoped" {
			t.Error("unexpected tool endpoint or authentication")
		}
		posts.Add(1)
		var body map[string]any
		json.NewDecoder(q.Body).Decode(&body)
		if body["operationId"] != op {
			t.Error("operation identity changed")
		}
		value := int64(3)
		writeJSON(w, map[string]any{"data": scopedEffect{RootID: root, OperationID: op, GrantID: grant, ResourceID: "poc_test", Delta: 3, State: "COMMITTED", EffectID: effect, CounterValue: &value, Source: "TOOL_STORE_VERIFIED", TraceID: digest([]byte("eap-agents/test|trace|" + digest([]byte(op))[:32]))[:32]}})
	}))
	defer gateway.Close()
	dir := t.TempDir()
	makeRunner := func() *runner {
		return &runner{gateway: gateway.URL, client: gateway.Client(), runtimeKey: "eap-agents/test", runtimeToken: "synthetic-scoped", toolDir: dir, toolsEnabled: true}
	}
	input := `{"taskKey":"scoped:1","objective":"counter","context":{"eapTool":{"name":"controlled_counter_increment","arguments":{"grantId":"` + grant + `","operationId":"` + op + `","resourceId":"poc_test","delta":3}}}}`
	for range 4 {
		response := toolRequest(makeRunner(), input)
		if response.Code != 200 || !strings.Contains(response.Body.String(), `"counterValue":3`) || !strings.Contains(response.Body.String(), "TOOL_STORE_VERIFIED") {
			t.Fatal("lost store proof")
		}
	}
	if posts.Load() != 1 {
		t.Fatal("replayed store commit")
	}
	if makeRunner().toolStats()["storeVerified"] != 1 {
		t.Fatal("store evidence not distinguished")
	}
}

func TestScopedUnknownRejectedAndForgedEvidenceNeverRetry(t *testing.T) {
	for _, mode := range []string{"unknown", "rejected", "forged-source", "wrong-operation", "wrong-trace", "lost-reply"} {
		t.Run(mode, func(t *testing.T) {
			var posts atomic.Int32
			grant := "00000000-0000-4000-8000-000000000002"
			op := "00000000-0000-4000-8000-000000000003"
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				if strings.HasSuffix(q.URL.Path, "/traces") {
					w.WriteHeader(200)
					return
				}
				posts.Add(1)
				if mode == "lost-reply" {
					w.WriteHeader(503)
					return
				}
				v := int64(3)
				e := scopedEffect{RootID: "00000000-0000-4000-8000-000000000001", OperationID: op, GrantID: grant, ResourceID: "poc_test", Delta: 3, State: "COMMITTED", EffectID: "00000000-0000-4000-8000-000000000004", CounterValue: &v, Source: "TOOL_STORE_VERIFIED", TraceID: digest([]byte("eap-agents/test|trace|" + digest([]byte(op))[:32]))[:32]}
				switch mode {
				case "unknown":
					e.State = "UNKNOWN"
					e.Source = "UNKNOWN"
				case "rejected":
					e.State = "REJECTED"
					e.Source = "TOOL_STORE_NO_COMMIT"
					e.Reason = "GRANT_REVOKED"
				case "forged-source":
					e.Source = "RUNTIME_REPORTED"
				case "wrong-trace":
					e.TraceID = "11111111111111111111111111111111"
				case "wrong-operation":
					e.OperationID = e.EffectID
				}
				writeJSON(w, map[string]any{"data": e})
			}))
			defer gateway.Close()
			r := &runner{gateway: gateway.URL, client: gateway.Client(), runtimeKey: "eap-agents/test", runtimeToken: "synthetic-scoped", toolDir: t.TempDir(), toolsEnabled: true}
			input := `{"taskKey":"scoped:failure","objective":"counter","context":{"eapTool":{"name":"controlled_counter_increment","arguments":{"grantId":"` + grant + `","operationId":"` + op + `","resourceId":"poc_test","delta":3}}}}`
			for range 3 {
				response := toolRequest(r, input)
				if strings.Contains(response.Body.String(), `"status":"SUCCEEDED"`) {
					t.Fatal("untrusted proof became success")
				}
			}
			if posts.Load() != 1 {
				t.Fatal("uncertain effect retried")
			}
		})
	}
}
