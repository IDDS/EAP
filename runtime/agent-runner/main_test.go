package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestTaskWaitsForAuthenticatedSpanBeforeTerminalReceipt(t *testing.T) {
	t.Setenv("EAP_OBSERVABILITY_TOKEN", "observation-test-token")
	exported := make(chan struct{})
	release := make(chan struct{})
	done := make(chan struct{})
	var modelTrace string
	var taskKey string
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == "/api/gateway/v1/chat/completions" {
			modelTrace = req.Header.Get("X-EAP-Trace-Id")
			writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": "handoff-result"}}}})
			return
		}
		if req.Header.Get("X-EAP-Observability-Token") != "observation-test-token" {
			t.Error("missing observation credential")
		}
		var body map[string]any
		if err := json.NewDecoder(req.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		resources := body["resourceSpans"].([]any)
		scope := resources[0].(map[string]any)["scopeSpans"].([]any)
		span := scope[0].(map[string]any)["spans"].([]any)[0].(map[string]any)
		if span["traceId"] != modelTrace {
			t.Error("model and agent span must share trace")
		}
		for _, attr := range span["attributes"].([]any) {
			a := attr.(map[string]any)
			if a["key"] == "eap.task.key" {
				taskKey = a["value"].(map[string]any)["stringValue"].(string)
			}
		}
		close(exported)
		<-release
		w.WriteHeader(http.StatusOK)
	}))
	defer gateway.Close()
	r := &runner{handled: map[string]string{}, gateway: gateway.URL, taskToken: "task-test-token", client: gateway.Client()}
	request := httptest.NewRequest(http.MethodPost, "/eap/task", strings.NewReader(`{"taskKey":"proof:1","objective":"test"}`))
	request.Header.Set("Authorization", "Bearer task-test-token")
	response := httptest.NewRecorder()
	go func() { r.task(response, request); close(done) }()
	select {
	case <-exported:
	case <-time.After(3 * time.Second):
		t.Fatal("span export not started")
	}
	select {
	case <-done:
		t.Error("task completed before span export")
	default:
	}
	close(release)
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("task did not finish")
	}
	if taskKey != "proof:1" || !strings.Contains(response.Body.String(), "handoff-result") || response.Code != 200 {
		t.Fatal("missing task evidence or output")
	}
}

func TestSpanFailureDoesNotRetryOrDiscardSuccessfulTask(t *testing.T) {
	var models atomic.Int32
	var exports atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == "/api/gateway/v1/chat/completions" {
			models.Add(1)
			writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": "completed"}}}})
		} else {
			exports.Add(1)
			w.WriteHeader(http.StatusServiceUnavailable)
		}
	}))
	defer gateway.Close()
	r := &runner{handled: map[string]string{}, gateway: gateway.URL, taskToken: "task-test-token", client: gateway.Client()}
	for range 2 {
		request := httptest.NewRequest(http.MethodPost, "/eap/task", strings.NewReader(`{"taskKey":"proof:2","objective":"test"}`))
		request.Header.Set("Authorization", "Bearer task-test-token")
		response := httptest.NewRecorder()
		r.task(response, request)
		if !strings.Contains(response.Body.String(), "SUCCEEDED") {
			t.Fatal("successful work lost on observation failure")
		}
	}
	if models.Load() != 1 || exports.Load() != 1 {
		t.Fatal("repeated task duplicated work")
	}
}

func TestIntegrationProofUsesOnlyScopedCredential(t *testing.T) {
	r := &runner{agentID: "71", runtimeKey: "eap-agents/outside", runtimeToken: "eap_rt_synthetic"}
	response := httptest.NewRecorder()
	r.integration(response, httptest.NewRequest(http.MethodPost, "/eap/integration", strings.NewReader(`{"nonce":"12345678-1234-1234-1234-123456789012"}`)))
	if response.Code != 200 || strings.Contains(response.Body.String(), r.runtimeToken) {
		t.Fatal("invalid or disclosed proof")
	}
	var body map[string]string
	if json.Unmarshal(response.Body.Bytes(), &body) != nil || body["protocol"] != "EAP_HTTP_TASK_V1" || body["instanceKey"] != r.runtimeKey || body["proof"] == "" {
		t.Fatal("missing binding proof")
	}
	req := httptest.NewRequest(http.MethodPost, "/eap/task", nil)
	req.Header.Set("Authorization", "Bearer legacy-platform-token")
	r.taskToken = "legacy-platform-token"
	if r.authorized(req) {
		t.Fatal("scoped runtime accepted legacy token")
	}
	req.Header.Set("Authorization", "Bearer "+r.runtimeToken)
	if !r.authorized(req) {
		t.Fatal("scoped runtime token rejected")
	}
}

func TestRuntimeConfigurationRequiresIndependentOwnerCredential(t *testing.T) {
	r := &runner{agentID: "71", runtimeKey: "eap-agents/outside", pairingToken: "synthetic-owner"}
	for _, auth := range []string{"", "Bearer invalid", "Bearer synthetic-owner"} {
		request := httptest.NewRequest(http.MethodPost, "/eap/integration/configure", strings.NewReader(`{"token":"eap_rt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}`))
		request.Header.Set("Authorization", auth)
		response := httptest.NewRecorder()
		r.configureIntegration(response, request)
		if auth == "Bearer synthetic-owner" {
			if response.Code != 200 {
				t.Fatal("owner config rejected")
			}
		} else if response.Code != 401 {
			t.Fatal("unauthorized config accepted")
		}
		if strings.Contains(response.Body.String(), "eap_rt_") {
			t.Fatal("response disclosed credential")
		}
	}
}

func TestScopedModelAndTelemetryNeverSendPlatformCredentials(t *testing.T) {
	var models, spans atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Header.Get("X-EAP-Runtime-Key") != "eap-agents/outside" || req.Header.Get("X-EAP-Runtime-Token") != "scoped-test-token" {
			t.Error("missing scoped identity")
		}
		if req.Header.Get("X-EAP-Gateway-Token") != "" || req.Header.Get("X-EAP-Observability-Token") != "" {
			t.Error("platform credential sent")
		}
		if req.URL.Path == "/api/runtime-access/v1/chat/completions" {
			models.Add(1)
			writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": "scoped-result"}}}})
		} else if req.URL.Path == "/api/runtime-access/v1/traces" {
			spans.Add(1)
			w.WriteHeader(http.StatusOK)
		} else {
			t.Error("unexpected path")
		}
	}))
	defer gateway.Close()
	r := &runner{handled: map[string]string{}, agentID: "71", runtimeKey: "eap-agents/outside", runtimeToken: "scoped-test-token", gwToken: "legacy-must-not-send", gateway: gateway.URL, client: gateway.Client()}
	req := httptest.NewRequest(http.MethodPost, "/eap/task", strings.NewReader(`{"taskKey":"scoped:1","objective":"test"}`))
	req.Header.Set("Authorization", "Bearer scoped-test-token")
	response := httptest.NewRecorder()
	r.task(response, req)
	if response.Code != 200 || !strings.Contains(response.Body.String(), "SUCCEEDED") || models.Load() != 1 || spans.Load() != 1 {
		t.Fatal("scoped task did not close loop")
	}
}

func TestBudgetRecordedIntentCarriesTaskIdentityAndReturnsUnknown(t *testing.T) {
	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == "/api/gateway/v1/chat/completions" {
			calls.Add(1)
			if req.Header.Get("X-EAP-Task-Key") != "proof:budget" {
				t.Error("missing stable budget task identity")
			}
			w.WriteHeader(http.StatusConflict)
			writeJSON(w, map[string]any{"error": map[string]any{"code": "budget_invocation_recorded"}})
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer gateway.Close()
	r := &runner{handled: map[string]string{}, gateway: gateway.URL, taskToken: "task-test-token", client: gateway.Client()}
	req := httptest.NewRequest(http.MethodPost, "/eap/task", strings.NewReader(`{"taskKey":"proof:budget","objective":"public test"}`))
	req.Header.Set("Authorization", "Bearer task-test-token")
	response := httptest.NewRecorder()
	r.task(response, req)
	if !strings.Contains(response.Body.String(), `"UNKNOWN"`) || calls.Load() != 1 {
		t.Fatal("recorded uncertain intent was misreported or retried")
	}
}

func TestModel429NeedsAdmissionProofToBeKnownFailure(t *testing.T) {
	for _, proof := range []bool{true, false} {
		gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
			if proof {
				w.Header().Set("X-EAP-Admission-Outcome", "REJECTED")
			}
			w.WriteHeader(429)
			writeJSON(w, map[string]any{"error": map[string]any{"code": "budget_exhausted"}})
		}))
		r := &runner{gateway: gateway.URL, client: gateway.Client()}
		_, err := r.askScoped("public sample", "trace", "proof:task")
		if err == nil || errors.Is(err, errModelOutcomeUnknown) == proof {
			t.Error("upstream status alone was treated as proof of no dispatch")
		}
		gateway.Close()
	}
}
