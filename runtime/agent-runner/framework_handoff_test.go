//go:build framework && !kagent

package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

func frameworkHandoffRequest(r *runner, task map[string]any, token string) *httptest.ResponseRecorder {
	raw, _ := json.Marshal(task)
	req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(string(raw)))
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	frameworkTask(r, w, req)
	return w
}

func TestFrameworkHandoffUsesExplicitProtocolAndFixedFrameworkRole(t *testing.T) {
	oldEngine, oldProcess := frameworkEngine, handoffProcess
	t.Cleanup(func() { frameworkEngine, handoffProcess = oldEngine, oldProcess })
	for _, item := range []struct{ engine, action, role string }{
		{"langgraph", "generate", "source"}, {"autogen-core", "confirm", "target"},
	} {
		t.Run(item.engine, func(t *testing.T) {
			frameworkEngine = item.engine
			r, task := handoffFixture(t)
			task["nodeKey"] = item.action
			ctx := task["context"].(map[string]any)
			delete(ctx, "eapTool")
			if item.role == "source" {
				delete(ctx, "eapPreviousResults")
			} else {
				ctx["eapPreviousResults"] = map[string]any{"execute": map[string]any{"taskKey": task["taskKey"], "agentId": 2, "output": map[string]any{"sum": 42}}}
			}
			calls := 0
			handoffProcess = func(_ context.Context, raw []byte) ([]byte, error) {
				var in handoffWorkerInput
				if json.Unmarshal(raw, &in) != nil || in.Role != item.role || in.Action != item.action || in.ActorUID != r.credentialUID || in.IdentityEpoch != r.credentialEpoch {
					t.Fatal("wrong role, phase or original identity")
				}
				calls++
				return []byte(`{"businessReexecuted":false}`), nil
			}
			if frameworkHandoffRequest(r, task, r.runtimeToken).Code != 400 || calls != 0 {
				t.Fatal("node name silently enabled handoff")
			}
			ctx["eapHandoff"] = map[string]any{"protocol": "EAP_CODE_HANDOFF_V1"}
			if frameworkHandoffRequest(r, task, "").Code != 401 || calls != 0 {
				t.Fatal("handoff bypassed runtime authentication")
			}
			response := frameworkHandoffRequest(r, task, r.runtimeToken)
			if response.Code != 200 || !strings.Contains(response.Body.String(), "SUCCEEDED") || calls != 1 {
				t.Fatal("explicit handoff did not reach the shared implementation")
			}
			task["nodeKey"] = map[string]string{"source": "confirm", "target": "generate"}[item.role]
			if frameworkHandoffRequest(r, task, r.runtimeToken).Code != 400 || calls != 1 {
				t.Fatal("framework accepted the other role")
			}
		})
	}
}

func TestFrameworkHandoffRejectsUnknownMarkerWithoutToolFallback(t *testing.T) {
	oldEngine, oldProcess := frameworkEngine, handoffProcess
	t.Cleanup(func() { frameworkEngine, handoffProcess = oldEngine, oldProcess })
	frameworkEngine = "autogen-core"
	handoffProcess = func(context.Context, []byte) ([]byte, error) {
		t.Fatal("invalid request reached child process")
		return nil, nil
	}
	for _, marker := range []any{nil, true, "EAP_CODE_HANDOFF_V1", map[string]any{}, map[string]any{"protocol": "future"}, map[string]any{"protocol": "EAP_CODE_HANDOFF_V1", "role": "target"}, map[string]any{"protocol": "EAP_CODE_HANDOFF_V1", "command": "/bin/sh"}} {
		r, task := handoffFixture(t)
		task["context"].(map[string]any)["eapHandoff"] = marker
		if frameworkHandoffRequest(r, task, r.runtimeToken).Code != 400 {
			t.Fatal("unsupported marker was accepted or fell back to the normal tool path")
		}
	}
	r, task := handoffFixture(t)
	task["context"].(map[string]any)["eapHandoff"] = map[string]any{"protocol": "EAP_CODE_HANDOFF_V1"}
	frameworkEngine = "unknown"
	if frameworkHandoffRequest(r, task, r.runtimeToken).Code != 400 {
		t.Fatal("unknown framework accepted handoff")
	}
}

func TestFrameworkTaskRejectsTrailingOrOversizedEnvelope(t *testing.T) {
	r := &runner{runtimeKey: "test/runner", runtimeToken: "fixture"}
	for _, body := range []string{`{"taskKey":"x"} {}`, `{"taskKey":"x","objective":"` + strings.Repeat("x", 60001) + `"}`} {
		if frameworkRequest(r, body).Code != 400 {
			t.Fatal("invalid envelope reached an execution path")
		}
	}
}
