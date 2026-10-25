//go:build handoff && !framework && !kagent

package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func handoffFixture(t *testing.T) (*runner, map[string]any) {
	t.Helper()
	uid := toolID("fixture", "actor")
	epoch := toolID("fixture", "epoch")
	projection := t.TempDir()
	for n, v := range map[string]string{"atespace": "sample", "name": "target", "uid": uid} {
		if err := os.WriteFile(filepath.Join(projection, n), []byte(v), 0600); err != nil {
			t.Fatal(err)
		}
	}
	r := &runner{runtimeKey: "sample/target", runtimeToken: "fixture-secret", credentialUID: uid, credentialEpoch: epoch, credentialExpiry: time.Now().Add(time.Hour), identity: &runtimeIdentity{directory: projection}, toolDir: t.TempDir(), toolsEnabled: true}
	run := "wf-exec-" + toolID("fixture", "workflow")
	return r, map[string]any{"protocol": "EAP_HTTP_TASK_V1", "taskKey": run + ":2", "workflowRunKey": run, "nodeKey": "execute", "objective": "bounded sum", "context": map[string]any{
		"eapPreviousResults": map[string]any{"generate": map[string]any{"taskKey": run + ":1", "agentId": 1, "output": map[string]any{"session": "fixture"}}},
		"eapTool":            map[string]any{"name": "calculate_sum", "arguments": map[string]any{"numbers": []float64{10, 20, 12}}}}}
}
func handoffRequest(r *runner, task map[string]any, token string) *httptest.ResponseRecorder {
	raw, _ := json.Marshal(task)
	req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(string(raw)))
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	handoffTask(r, w, req)
	return w
}

func TestHandoffGovernedExecutionReplayAndChangedInput(t *testing.T) {
	oldRole, oldProcess := handoffRole, handoffProcess
	t.Cleanup(func() { handoffRole, handoffProcess = oldRole, oldProcess })
	handoffRole = "target"
	for _, uncertain := range []bool{false, true} {
		t.Run(map[bool]string{true: "unknown", false: "success"}[uncertain], func(t *testing.T) {
			r, task := handoffFixture(t)
			checks, claims, receipts, executions, statuses := 0, 0, 0, 0, 0
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				var body map[string]any
				json.NewDecoder(q.Body).Decode(&body)
				id, _ := body["callId"].(string)
				switch q.URL.Path {
				case "/api/runtime-access/v1/tools/check":
					checks++
					writeJSON(w, map[string]any{"data": guardView{id, "APPROVED"}})
				case "/api/runtime-access/v1/tools/claim":
					claims++
					writeJSON(w, map[string]any{"data": map[string]any{"execute": true, "call": guardView{id, "CLAIMED"}}})
				case "/api/runtime-access/v1/tools/receipt":
					receipts++
					writeJSON(w, map[string]any{"data": guardView{id, "SUCCEEDED"}})
				case "/api/runtime-access/v1/traces":
					writeJSON(w, map[string]any{})
				default:
					t.Error("unexpected outbound request")
					http.Error(w, "unexpected", 400)
				}
			}))
			defer gateway.Close()
			r.gateway = gateway.URL
			r.client = gateway.Client()
			handoffProcess = func(ctx context.Context, raw []byte) ([]byte, error) {
				var in handoffWorkerInput
				if json.Unmarshal(raw, &in) != nil {
					t.Fatal("invalid input")
				}
				if in.Credential != r.runtimeToken || in.ActorUID != r.credentialUID || in.IdentityEpoch != r.credentialEpoch {
					t.Fatal("identity not propagated")
				}
				if in.Action == "execute" {
					executions++
					if checks != 1 || claims != 1 || len(in.TraceID) != 32 {
						t.Fatal("execution before claim")
					}
					if uncertain {
						return nil, errors.New("private process failure")
					}
				}
				if in.Action == "status" {
					statuses++
				}
				return []byte(`{"sum":42,"count":3,"handoff":{"original":true}}`), nil
			}
			for i := 0; i < 2; i++ {
				w := handoffRequest(r, task, r.runtimeToken)
				want := "SUCCEEDED"
				if uncertain {
					want = "EXECUTION_UNKNOWN"
				}
				if w.Code != 200 || !strings.Contains(w.Body.String(), want) {
					t.Fatalf("invalid response: %d %s", w.Code, w.Body.String())
				}
			}
			if checks != 1 || claims != 1 || executions != 1 {
				t.Fatal("business replayed")
			}
			if uncertain && (receipts != 0 || statuses != 0) || !uncertain && (receipts != 1 || statuses != 2) {
				t.Fatal("incorrect finalization")
			}
			task["objective"] = "changed"
			if handoffRequest(r, task, r.runtimeToken).Code != 409 {
				t.Fatal("changed input not rejected")
			}
		})
	}
}

func TestHandoffRejectsUnauthenticatedWrongRoleAndMetadataTool(t *testing.T) {
	old := handoffRole
	t.Cleanup(func() { handoffRole = old })
	handoffRole = "source"
	r, task := handoffFixture(t)
	if handoffRequest(r, task, "").Code != 401 {
		t.Fatal("anonymous accepted")
	}
	if handoffRequest(r, task, r.runtimeToken).Code != 400 {
		t.Fatal("source executed target")
	}
	task["nodeKey"] = "offer"
	task["context"].(map[string]any)["eapPreviousResults"] = map[string]any{"execute": map[string]any{"taskKey": task["taskKey"], "agentId": 2, "output": map[string]any{"sum": 42}}}
	if handoffRequest(r, task, r.runtimeToken).Code != 400 {
		t.Fatal("metadata accepted tool")
	}
	r.credentialExpiry = time.Now().Add(-time.Second)
	if handoffRequest(r, task, r.runtimeToken).Code != 401 {
		t.Fatal("expired identity accepted")
	}
}

func TestHandoffProcessFailuresAreBoundedAndRedacted(t *testing.T) {
	old := handoffProcess
	t.Cleanup(func() { handoffProcess = old })
	for _, body := range []string{"null", "{}", "[]", `{"error":"private failure"}`, `{} {}`, strings.Repeat("x", 16385)} {
		handoffProcess = func(ctx context.Context, _ []byte) ([]byte, error) {
			deadline, ok := ctx.Deadline()
			if !ok || time.Until(deadline) > 40*time.Second {
				t.Fatal("missing timeout")
			}
			return []byte(body), nil
		}
		_, err := (&runner{}).callHandoff(context.Background(), handoffWorkerInput{})
		if err == nil || strings.Contains(err.Error(), "private failure") {
			t.Fatal("invalid result or private error accepted")
		}
	}
}
