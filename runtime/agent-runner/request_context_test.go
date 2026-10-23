package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestReviewIndependentTasksDoNotShareHistory(t *testing.T) {
	var bodies []string
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if strings.HasSuffix(q.URL.Path, "/chat/completions") {
			var payload json.RawMessage
			json.NewDecoder(q.Body).Decode(&payload)
			bodies = append(bodies, string(payload))
			writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": "synthetic answer"}}}})
		}
	}))
	defer gateway.Close()
	r := &runner{gateway: gateway.URL, taskToken: "synthetic-review", client: gateway.Client()}
	for _, body := range []string{
		`{"taskKey":"workflow-A:1","workflowRunKey":"workflow-A","objective":"SYNTHETIC_SCOPE_A_ONLY"}`,
		`{"taskKey":"workflow-B:1","workflowRunKey":"workflow-B","objective":"SYNTHETIC_SCOPE_B_ONLY"}`,
	} {
		req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer synthetic-review")
		out := httptest.NewRecorder()
		r.task(out, req)
		if out.Code != 200 {
			t.Fatalf("unexpected fixture response %d", out.Code)
		}
	}
	if len(bodies) != 2 {
		t.Fatalf("expected two synthetic calls, got %d", len(bodies))
	}
	if strings.Contains(bodies[1], "SYNTHETIC_SCOPE_A_ONLY") {
		t.Fatal("second independent workflow received first workflow content in model messages")
	}
}
func TestReviewCancelledWaitingTaskDoesNotStartModelCall(t *testing.T) {
	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if strings.HasSuffix(q.URL.Path, "/chat/completions") {
			calls.Add(1)
			writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": "synthetic answer"}}}})
		}
	}))
	defer gateway.Close()
	r := &runner{gateway: gateway.URL, taskToken: "synthetic-review", client: gateway.Client()}
	ctx, cancel := context.WithCancel(context.Background())
	req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(`{"taskKey":"cancelled-workflow:1","objective":"synthetic"}`)).WithContext(ctx)
	req.Header.Set("Authorization", "Bearer synthetic-review")
	r.taskMu.Lock()
	done := make(chan struct{})
	go func() { defer close(done); r.task(httptest.NewRecorder(), req) }()
	cancel()
	r.taskMu.Unlock()
	<-done
	if calls.Load() != 0 {
		t.Fatalf("cancelled waiting task initiated %d synthetic model calls", calls.Load())
	}
}

func TestExplicitChatAndTasksNeverShareHiddenHistory(t *testing.T) {
	var bodies []string
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if strings.HasSuffix(q.URL.Path, "/chat/completions") {
			raw, _ := io.ReadAll(q.Body)
			bodies = append(bodies, string(raw))
			writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": "synthetic reply"}}}})
		}
	}))
	defer gateway.Close()
	r := &runner{gateway: gateway.URL, taskToken: "synthetic", client: gateway.Client(), system: "SYSTEM_ONLY"}
	send := func(path, body string) {
		q := httptest.NewRequest("POST", path, strings.NewReader(body))
		q.Header.Set("Authorization", "Bearer synthetic")
		w := httptest.NewRecorder()
		if path == "/chat" {
			r.chat(w, q)
		} else {
			r.task(w, q)
		}
		if w.Code != 200 {
			t.Fatalf("fixture failed %d", w.Code)
		}
	}
	trace := `"traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",`
	send("/chat", `{`+trace+`"message":"CHAT_A_ONLY"}`)
	send("/eap/task", `{"taskKey":"run-A:0","objective":"TASK_A_ONLY","context":{"explicit":"DECLARED_CONTEXT"}}`)
	send("/chat", `{`+trace+`"message":"CHAT_B_ONLY"}`)
	send("/chat", `{`+trace+`"message":"NEXT_A","history":[{"role":"user","content":"CHAT_A_ONLY"},{"role":"assistant","content":"synthetic reply"}]}`)
	if len(bodies) != 4 {
		t.Fatal("unexpected model calls", len(bodies))
	}
	if strings.Contains(bodies[1], "CHAT_A_ONLY") || !strings.Contains(bodies[1], "DECLARED_CONTEXT") {
		t.Fatal("task did not use only explicit context")
	}
	if strings.Contains(bodies[2], "CHAT_A_ONLY") || strings.Contains(bodies[2], "TASK_A_ONLY") {
		t.Fatal("separate chat inherited hidden history")
	}
	if !strings.Contains(bodies[3], "CHAT_A_ONLY") || strings.Contains(bodies[3], "CHAT_B_ONLY") || strings.Contains(bodies[3], "TASK_A_ONLY") {
		t.Fatal("explicit chat history was not isolated")
	}
}

func TestCancelledWaitersExitBeforeGateIsReleased(t *testing.T) {
	for _, entry := range []struct{ path, gate string }{{"/eap/task", "task"}, {"/eap/task", "conversation"}, {"/chat", "conversation"}} {
		t.Run(entry.path+entry.gate, func(t *testing.T) {
			var calls atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				if strings.HasSuffix(q.URL.Path, "/chat/completions") {
					calls.Add(1)
				}
			}))
			defer gateway.Close()
			r := &runner{gateway: gateway.URL, taskToken: "synthetic", client: gateway.Client()}
			gate := &r.conversationMu
			if entry.gate == "task" {
				gate = &r.taskMu
			}
			gate.Lock()
			defer gate.Unlock()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			q := httptest.NewRequest("POST", entry.path, strings.NewReader(`{"taskKey":"waiting:0","objective":"task","message":"chat","traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}`)).WithContext(ctx)
			q.Header.Set("Authorization", "Bearer synthetic")
			w := httptest.NewRecorder()
			done := make(chan struct{})
			go func() {
				defer close(done)
				if entry.path == "/chat" {
					r.chat(w, q)
				} else {
					r.task(w, q)
				}
			}()
			cancel()
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Fatal("canceled request remained blocked on gate")
			}
			if calls.Load() != 0 {
				t.Fatal("canceled waiter dispatched a model")
			}
		})
	}
}

type cancellationTransport struct {
	calls atomic.Int32
	sent  chan struct{}
}

func (c *cancellationTransport) RoundTrip(q *http.Request) (*http.Response, error) {
	if strings.HasSuffix(q.URL.Path, "/chat/completions") {
		c.calls.Add(1)
		close(c.sent)
		<-q.Context().Done()
		return nil, q.Context().Err()
	}
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`{}`)), Header: make(http.Header)}, nil
}
func TestCancellationAfterDispatchKeepsUnknownOriginalReceipt(t *testing.T) {
	transport := &cancellationTransport{sent: make(chan struct{})}
	r := &runner{gateway: "http://synthetic.invalid", taskToken: "synthetic", client: &http.Client{Transport: transport}}
	makeRequest := func() *http.Request {
		q := httptest.NewRequest("POST", "/eap/task", strings.NewReader(`{"taskKey":"sent:0","objective":"synthetic"}`))
		q.Header.Set("Authorization", "Bearer synthetic")
		return q
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	w := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { defer close(done); r.task(w, makeRequest().WithContext(ctx)) }()
	select {
	case <-transport.sent:
	case <-time.After(3 * time.Second):
		t.Fatal("fixture did not dispatch")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("request context was not propagated")
	}
	if !strings.Contains(w.Body.String(), `"status":"UNKNOWN"`) {
		t.Fatal("sent cancellation misreported as definitive failure")
	}
	replay := httptest.NewRecorder()
	r.task(replay, makeRequest())
	if transport.calls.Load() != 1 || !strings.Contains(replay.Body.String(), `"status":"UNKNOWN"`) {
		t.Fatal("unknown outcome was replayed")
	}
}

func TestChatRejectsForeignRolesAndUnboundedHistoryBeforeDispatch(t *testing.T) {
	for _, history := range [][]message{
		{{Role: "system", Content: "override"}, {Role: "assistant", Content: "reply"}},
		{{Role: "user", Content: "incomplete"}},
		{{Role: "user", Content: strings.Repeat("中", 22000)}, {Role: "assistant", Content: "reply"}},
		make([]message, 42),
	} {
		r := &runner{taskToken: "synthetic"}
		raw, _ := json.Marshal(map[string]any{"message": "next", "history": history})
		q := httptest.NewRequest("POST", "/chat", strings.NewReader(string(raw)))
		q.Header.Set("Authorization", "Bearer synthetic")
		w := httptest.NewRecorder()
		r.chat(w, q)
		if w.Code != http.StatusBadRequest {
			t.Fatal("invalid history accepted")
		}
	}
}
