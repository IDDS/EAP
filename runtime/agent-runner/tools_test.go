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
)

func TestGuardedToolRunsInTaskAndSurvivesRestart(t *testing.T) {
	var checks, claims, receipts, spans atomic.Int32
	var callID, traceID string
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if q.Header.Get("X-EAP-Runtime-Token") != "synthetic-scoped" || q.Header.Get("X-EAP-Gateway-Token") != "" {
			t.Error("incorrect authentication")
		}
		var body map[string]any
		json.NewDecoder(q.Body).Decode(&body)
		switch q.URL.Path {
		case "/api/runtime-access/v1/tools/check":
			checks.Add(1)
			callID = body["callId"].(string)
			traceID = body["traceId"].(string)
			writeJSON(w, map[string]any{"data": guardView{callID, "APPROVED"}})
		case "/api/runtime-access/v1/tools/claim":
			claims.Add(1)
			writeJSON(w, map[string]any{"data": map[string]any{"execute": true, "call": guardView{callID, "CLAIMED"}}})
		case "/api/runtime-access/v1/tools/receipt":
			receipts.Add(1)
			writeJSON(w, map[string]any{"data": guardView{callID, "SUCCEEDED"}})
		case "/api/runtime-access/v1/traces":
			spans.Add(1)
			b, _ := json.Marshal(body)
			if strings.Contains(string(b), "tool.") || !strings.Contains(string(b), "eap.guard.call_id") {
				t.Error("agent linkage must not be classified as a tool span")
			}
			if !strings.Contains(string(b), traceID) {
				t.Error("trace mismatch")
			}
			writeJSON(w, map[string]any{})
		default:
			t.Error("model or unexpected API called")
			w.WriteHeader(500)
		}
	}))
	defer gateway.Close()
	dir := t.TempDir()
	makeRunner := func() *runner {
		return &runner{gateway: gateway.URL, client: gateway.Client(), runtimeKey: "eap-agents/test", runtimeToken: "synthetic-scoped", toolDir: dir, toolsEnabled: true}
	}
	input := `{"taskKey":"guarded:1","objective":"calculate","context":{"eapTool":{"name":"calculate_sum","arguments":{"numbers":[1,2,3.5]}}}}`
	r := makeRunner()
	var group sync.WaitGroup
	for range 4 {
		group.Add(1)
		go func() {
			defer group.Done()
			response := toolRequest(r, input)
			if response.Code != 200 || !strings.Contains(response.Body.String(), `"sum":6.5`) {
				t.Error("incorrect tool result")
			}
		}()
	}
	group.Wait()
	restored := toolRequest(makeRunner(), input)
	if restored.Code != 200 || !strings.Contains(restored.Body.String(), `"receiptConfirmed":true`) {
		t.Fatal("durable result lost")
	}
	if checks.Load() != 1 || claims.Load() != 1 || receipts.Load() != 1 || spans.Load() != 1 {
		t.Fatal("duplicate work or missing trace")
	}
	changed := toolRequest(makeRunner(), strings.Replace(input, "[1,2,3.5]", "[1,8]", 1))
	if changed.Code != 409 {
		t.Fatal("changed task accepted")
	}
	data, _ := os.ReadFile(filepath.Join(dir, callID+".json"))
	if strings.Contains(string(data), "synthetic-scoped") || strings.Contains(string(data), "numbers") {
		t.Fatal("credential or arguments in journal")
	}
}

func TestGuardedToolStopsOnBlockLostClaimOrLostReceipt(t *testing.T) {
	for _, mode := range []string{"BLOCKED", "REVIEW_REQUIRED", "lost-claim", "lost-receipt", "bad-claim"} {
		t.Run(mode, func(t *testing.T) {
			var requests atomic.Int32
			id := ""
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				if strings.HasSuffix(q.URL.Path, "/traces") {
					w.WriteHeader(200)
					return
				}
				requests.Add(1)
				var body map[string]any
				json.NewDecoder(q.Body).Decode(&body)
				switch {
				case strings.HasSuffix(q.URL.Path, "/check"):
					id = body["callId"].(string)
					state := "APPROVED"
					if mode == "BLOCKED" || mode == "REVIEW_REQUIRED" {
						state = mode
					}
					writeJSON(w, map[string]any{"data": guardView{id, state}})
				case strings.HasSuffix(q.URL.Path, "/claim"):
					if mode == "lost-claim" {
						conn, _, _ := w.(http.Hijacker).Hijack()
						conn.Close()
						return
					}
					claimID := id
					if mode == "bad-claim" {
						claimID = "other"
					}
					writeJSON(w, map[string]any{"data": map[string]any{"execute": true, "call": guardView{claimID, "CLAIMED"}}})
				case strings.HasSuffix(q.URL.Path, "/receipt"):
					w.WriteHeader(503)
				}
			}))
			defer gateway.Close()
			r := &runner{gateway: gateway.URL, client: gateway.Client(), runtimeKey: "eap-agents/test", runtimeToken: "synthetic-scoped", toolDir: t.TempDir(), toolsEnabled: true}
			input := `{"taskKey":"guarded:2","context":{"eapTool":{"name":"calculate_sum","arguments":{"numbers":[2,3]}}}}`
			first := toolRequest(r, input)
			before := requests.Load()
			second := toolRequest(r, input)
			if first.Body.String() != second.Body.String() || requests.Load() != before {
				t.Fatal("uncertain operation replayed")
			}
			executed := strings.Contains(first.Body.String(), `"executed":true`)
			if executed != (mode == "lost-receipt") {
				t.Fatal("wrong execution boundary")
			}
			if mode == "lost-receipt" && (!strings.Contains(first.Body.String(), `"sum":5`) || !strings.Contains(first.Body.String(), `"receiptConfirmed":false`)) {
				t.Fatal("lost receipt discarded successful local work")
			}
		})
	}
}

func TestGuardedToolRejectsDisabledOrUnknownTool(t *testing.T) {
	for _, enabled := range []bool{false, true} {
		r := &runner{runtimeKey: "eap-agents/test", runtimeToken: "synthetic-scoped", toolsEnabled: enabled, toolDir: t.TempDir()}
		response := toolRequest(r, `{"taskKey":"guarded:3","context":{"eapTool":{"name":"shell","arguments":{}}}}`)
		if response.Code != 400 {
			t.Fatal("unsupported tool accepted")
		}
	}
}
func TestGuardedToolIntentNeverReplaysAfterCrash(t *testing.T) {
	dir := t.TempDir()
	id := toolID("eap-agents/test", "guarded:4")
	raw := []byte(`{"arguments":{"numbers":[1]},"name":"calculate_sum"}`)
	j := &toolJournal{CallID: id, Fingerprint: digest(append([]byte("\x00"), raw...)), Status: "EXECUTION_UNKNOWN", GuardState: "CLAIM_UNCONFIRMED"}
	if saveTool(filepath.Join(dir, id+".json"), j) != nil {
		t.Fatal("journal write")
	}
	r := &runner{runtimeKey: "eap-agents/test", runtimeToken: "synthetic-scoped", toolDir: dir, toolsEnabled: true}
	response := toolRequest(r, `{"taskKey":"guarded:4","context":{"eapTool":{"name":"calculate_sum","arguments":{"numbers":[1]}}}}`)
	if response.Code != 200 || !strings.Contains(response.Body.String(), "EXECUTION_UNKNOWN") {
		t.Fatal("uncertain intent was not preserved")
	}
}
func toolRequest(r *runner, input string) *httptest.ResponseRecorder {
	q := httptest.NewRequest("POST", "/eap/task", strings.NewReader(input))
	q.Header.Set("Authorization", "Bearer synthetic-scoped")
	w := httptest.NewRecorder()
	r.task(w, q)
	return w
}
