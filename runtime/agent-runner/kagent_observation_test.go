//go:build kagent

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	adkagent "google.golang.org/adk/v2/agent"
	"google.golang.org/adk/v2/agent/workflowagents/parallelagent"
	"google.golang.org/adk/v2/agent/workflowagents/sequentialagent"
	adkrunner "google.golang.org/adk/v2/runner"
	"google.golang.org/adk/v2/session"
	"google.golang.org/genai"
)

func kagentInstalledIdentity(t *testing.T) *runner {
	t.Helper()
	r, signing, uid := identityFixture(t)
	reply := httptest.NewRecorder()
	r.identityChallenge(reply, signedRequest(signing, challengeBody(toolID("observation", "delivery"))))
	var challenge map[string]string
	if reply.Code != 200 || json.Unmarshal(reply.Body.Bytes(), &challenge) != nil {
		t.Fatal("challenge failed")
	}
	reply = httptest.NewRecorder()
	r.identityInstall(reply, signedRequest(signing, installBody(t, r, challenge, uid, "eap_rt_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")))
	if reply.Code != 200 {
		t.Fatal("installation failed")
	}
	r.toolDir = t.TempDir()
	r.client = &http.Client{}
	return r
}

func observedLeaf(t *testing.T, name string, runErr error) adkagent.Agent {
	t.Helper()
	a, err := adkagent.New(adkagent.Config{Name: name, Run: func(ctx adkagent.InvocationContext) iter.Seq2[*session.Event, error] {
		return func(yield func(*session.Event, error) bool) {
			if runErr != nil {
				yield(nil, runErr)
				return
			}
			event := session.NewEvent(ctx, ctx.InvocationID())
			event.Content = genai.NewContentFromText("private fixture output", genai.RoleModel)
			yield(event, nil)
		}
	}})
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func executeObservedTree(t *testing.T, r *runner, root adkagent.Agent, expected error) *kagentObservation {
	t.Helper()
	obs := r.newKagentObservation(root, sumTrace)
	if obs == nil {
		t.Fatal("observer unavailable", kagentDeliveryState(r))
	}
	sessions := session.InMemoryService()
	executor, err := adkrunner.New(adkrunner.Config{AppName: "observation-test", Agent: root, SessionService: sessions, PluginConfig: adkrunner.PluginConfig{Plugins: obs.plugins()}})
	if err != nil {
		t.Fatal(err)
	}
	s, err := sessions.Create(context.Background(), &session.CreateRequest{AppName: "observation-test", UserID: "test"})
	if err != nil {
		t.Fatal(err)
	}
	var finalErr error
	for _, err := range executor.Run(context.Background(), "test", s.Session.ID(), genai.NewContentFromText("private fixture input", genai.RoleUser), adkagent.RunConfig{}) {
		if err != nil {
			finalErr = err
			break
		}
	}
	if expected == nil && finalErr != nil || expected != nil && !errors.Is(finalErr, expected) {
		t.Fatal("unexpected execution result", finalErr)
	}
	obs.finish(finalErr)
	return obs
}

func readObservedEvents(t *testing.T, obs *kagentObservation) []kagentExecutionEvent {
	t.Helper()
	var out []kagentExecutionEvent
	for i := 1; i <= obs.sequence; i++ {
		var event kagentExecutionEvent
		if readKagentJSON(obs.directory, kagentEventName(i), &event) != nil {
			t.Fatal("missing event")
		}
		out = append(out, event)
	}
	return out
}

func TestKagentObservationActualSequentialParallelChildren(t *testing.T) {
	r := kagentInstalledIdentity(t)
	parallel, err := parallelagent.New(parallelagent.Config{AgentConfig: adkagent.Config{Name: "parallel", SubAgents: []adkagent.Agent{observedLeaf(t, "left", nil), observedLeaf(t, "right", nil)}}})
	if err != nil {
		t.Fatal(err)
	}
	root, err := sequentialagent.New(sequentialagent.Config{AgentConfig: adkagent.Config{Name: "root", SubAgents: []adkagent.Agent{parallel, observedLeaf(t, "tail", nil)}}})
	if err != nil {
		t.Fatal(err)
	}
	obs := executeObservedTree(t, r, root, nil)
	events := readObservedEvents(t, obs)
	if len(events) != 10 || obs.broken {
		t.Fatalf("events=%d incomplete=%v", len(events), obs.broken)
	}
	ids := map[string]string{}
	ends := map[string]bool{}
	for _, e := range events {
		if e.ProcessID != os.Getpid() || e.TraceID != sumTrace {
			t.Fatal("same-process context lost")
		}
		if e.Kind == "RUN_STARTED" {
			if ids[e.DefinitionKey] != "" {
				t.Fatal("duplicate definition invocation")
			}
			ids[e.DefinitionKey] = e.RunID
		} else {
			if e.Outcome != "SUCCEEDED" {
				t.Fatal("wrong outcome")
			}
			ends[e.RunID] = true
		}
	}
	for _, e := range events {
		if e.Kind == "RUN_STARTED" {
			parent := obs.parents[e.DefinitionKey]
			if parent != "" && e.ParentRunID != ids[parent] || !ends[e.RunID] {
				t.Fatal("actual child relation lost", e)
			}
		}
	}
	if len(ends) != 5 {
		t.Fatal("logical executions conflated with PID")
	}
}

func TestKagentObservationRepeatedChildGetsNewRun(t *testing.T) {
	r := kagentInstalledIdentity(t)
	child := observedLeaf(t, "child", nil)
	root, err := adkagent.New(adkagent.Config{Name: "root", SubAgents: []adkagent.Agent{child}, Run: func(ctx adkagent.InvocationContext) iter.Seq2[*session.Event, error] {
		return func(yield func(*session.Event, error) bool) {
			for i := 0; i < 2; i++ {
				for event, err := range child.Run(ctx) {
					if !yield(event, err) {
						return
					}
				}
			}
		}
	}})
	if err != nil {
		t.Fatal(err)
	}
	obs := executeObservedTree(t, r, root, nil)
	events := readObservedEvents(t, obs)
	ids := map[string]bool{}
	for _, e := range events {
		if e.Kind == "RUN_STARTED" && e.DefinitionKey == "child" {
			ids[e.RunID] = true
		}
	}
	if len(ids) != 2 || len(events) != 6 {
		t.Fatal("reused definition collapsed logical executions")
	}
}

func TestKagentObservationUnknownLeavesExplicitGap(t *testing.T) {
	r := kagentInstalledIdentity(t)
	root := observedLeaf(t, "root", errModelOutcomeUnknown)
	obs := executeObservedTree(t, r, root, errModelOutcomeUnknown)
	events := readObservedEvents(t, obs)
	if len(events) != 1 || events[0].Kind != "RUN_STARTED" {
		t.Fatal("UNKNOWN turned into a terminal outcome")
	}
}

func TestKagentObservationDeliveryLostReceiptRenewalAndReopen(t *testing.T) {
	r := kagentInstalledIdentity(t)
	obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
	var calls atomic.Int32
	var bodies [][]byte
	var mu sync.Mutex
	s := kagentEventTestServer(t, strings.Repeat("a", 64), http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		body, _ := io.ReadAll(req.Body)
		mu.Lock()
		bodies = append(bodies, body)
		mu.Unlock()
		n := calls.Add(1)
		if req.URL.Path != "/api/runtime-access/v1/execution-events" || req.Header.Get("X-EAP-Runtime-Key") != r.runtimeKey {
			t.Error("incorrect delivery channel")
		}
		if n == 1 {
			w.WriteHeader(503)
			return
		}
		if req.Header.Get("X-EAP-Runtime-Token") != "renewed-synthetic-lease" {
			t.Error("renewal not used")
		}
		fmt.Fprintf(w, `{"success":true,"data":{"streamKey":%q,"received":2,"recorded":0,"duplicates":2}}`, strings.Repeat("a", 64))
	}))
	defer s.Close()
	r.gateway = s.URL
	r.kagentDeliveryPass(context.Background())
	// New observer process state, same original installation and immutable files.
	r.mu.Lock()
	r.runtimeToken = "renewed-synthetic-lease"
	r.mu.Unlock()
	r.kagentDeliveryPass(context.Background())
	r.kagentDeliveryPass(context.Background())
	if calls.Load() != 2 || !reflect.DeepEqual(bodies[0], bodies[1]) {
		t.Fatal("metadata replay changed intent or repeated confirmed batch")
	}
	filepath.WalkDir(obs.directory, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			t.Fatal(err)
		}
		if !d.IsDir() {
			raw, _ := os.ReadFile(path)
			for _, secret := range []string{"synthetic-lease", "private fixture", "credential", "private fixture output"} {
				if strings.Contains(string(raw), secret) {
					t.Fatal("sensitive data persisted")
				}
			}
		}
		return nil
	})
}

func TestKagentObservationRejectsChangedIdentityAndInvalidReceipts(t *testing.T) {
	for _, mode := range []string{"epoch", "uid", "expired", "manual", "redirect", "unauthorized", "bad-receipt", "oversized-receipt", "server-error"} {
		t.Run(mode, func(t *testing.T) {
			r := kagentInstalledIdentity(t)
			executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
			var calls atomic.Int32
			s := kagentEventTestServer(t, strings.Repeat("a", 64), http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				calls.Add(1)
				switch mode {
				case "redirect":
					http.Redirect(w, req, "/forbidden", 302)
				case "unauthorized":
					w.WriteHeader(401)
				case "server-error":
					w.WriteHeader(503)
				case "oversized-receipt":
					fmt.Fprint(w, strings.Repeat("x", 32769))
				default:
					fmt.Fprint(w, `{"success":true,"data":{"streamKey":"bad","received":2,"recorded":2,"duplicates":0}}`)
				}
			}))
			defer s.Close()
			r.gateway = s.URL
			switch mode {
			case "epoch":
				r.credentialEpoch = toolID("foreign", "epoch")
			case "uid":
				r.credentialUID = toolID("foreign", "uid")
			case "expired":
				r.credentialExpiry = time.Now().Add(-time.Second)
			case "manual":
				r.identity = nil
			}
			for i := 0; i < 8; i++ {
				r.kagentDeliveryPass(context.Background())
			}
			expected := int32(0)
			if mode == "redirect" || mode == "unauthorized" || mode == "bad-receipt" || mode == "oversized-receipt" {
				expected = 1
			}
			if mode == "server-error" {
				expected = 6
			}
			if calls.Load() != expected {
				t.Fatal("identity/retry boundary", calls.Load(), expected)
			}
		})
	}
}

func TestKagentObservationActualGovernedToolAndReplay(t *testing.T) {
	r := kagentInstalledIdentity(t)
	var model, tools atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if strings.HasSuffix(req.URL.Path, "/chat/completions") {
			model.Add(1)
			fmt.Fprint(w, sumResponse)
			return
		}
		var body map[string]any
		json.NewDecoder(req.Body).Decode(&body)
		id, _ := body["callId"].(string)
		switch req.URL.Path {
		case "/api/runtime-access/v1/tools/check":
			fmt.Fprintf(w, `{"data":{"callId":%q,"state":"APPROVED"}}`, id)
		case "/api/runtime-access/v1/tools/claim":
			tools.Add(1)
			fmt.Fprintf(w, `{"data":{"execute":true,"call":{"callId":%q,"state":"CLAIMED"}}}`, id)
		case "/api/runtime-access/v1/tools/receipt":
			fmt.Fprintf(w, `{"data":{"callId":%q,"state":"SUCCEEDED"}}`, id)
		default:
			t.Error("unexpected channel", req.URL.Path)
		}
	}))
	defer s.Close()
	r.gateway = s.URL
	r.model = "synthetic"
	r.system = "private fixture prompt"
	r.maxTokens = 128
	r.toolsEnabled = true
	r.kagentToolMode = "CALCULATE_SUM_V1"
	answer, err := r.askContext(context.Background(), "private fixture input", sumTrace, "observed-task", nil, nil)
	if err != nil || !strings.Contains(answer, `"sum":42`) {
		t.Fatal("tool failed", err)
	}
	replay, err := r.askContext(context.Background(), "private fixture input", sumTrace, "observed-task", nil, nil)
	if err != nil || replay != answer || model.Load() != 1 || tools.Load() != 1 {
		t.Fatal("business replayed")
	}
	entries, err := os.ReadDir(filepath.Join(r.toolDir, "kagent-events"))
	if err != nil || len(entries) != 1 {
		t.Fatal("observation duplicated", err)
	}
	dir := filepath.Join(r.toolDir, "kagent-events", entries[0].Name())
	var events []kagentExecutionEvent
	for i := 1; i <= 4; i++ {
		var e kagentExecutionEvent
		if readKagentJSON(dir, kagentEventName(i), &e) != nil {
			t.Fatal("missing lifecycle event", i)
		}
		events = append(events, e)
	}
	if events[0].Kind != "RUN_STARTED" || events[1].Kind != "TOOL_STARTED" || events[2].Kind != "TOOL_FINISHED" || events[3].Kind != "RUN_FINISHED" || events[1].ToolCallID != events[2].ToolCallID || events[2].Outcome != "SUCCEEDED" {
		t.Fatal("wrong actual callback order", events)
	}
}

func TestKagentObservationCapacityAndPrivateStorage(t *testing.T) {
	r := kagentInstalledIdentity(t)
	obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
	for i := 0; i < 70; i++ {
		obs.emit(kagentExecutionEvent{Kind: "RUN_STARTED", RunID: toolID("run", fmt.Sprint(i)), DefinitionKey: "test"})
	}
	if obs.sequence != 64 || !obs.broken {
		t.Fatal("unbounded journal")
	}
	if writeKagentImmutable(obs.directory, "event-01.json", map[string]bool{}) == nil {
		t.Fatal("immutable event overwritten")
	}
	if err := os.Chmod(obs.directory, 0755); err != nil {
		t.Fatal(err)
	}
	var e kagentExecutionEvent
	if readKagentJSON(obs.directory, "event-01.json", &e) == nil {
		t.Fatal("non-private journal accepted")
	}
}

func TestKagentObservationPendingBatchFrozenWhileNewEventsArrive(t *testing.T) {
	r := kagentInstalledIdentity(t)
	obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
	var bodies [][]byte
	s := kagentEventTestServer(t, strings.Repeat("b", 64), http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		body, _ := io.ReadAll(req.Body)
		bodies = append(bodies, body)
		if len(bodies) == 1 {
			conn, _, err := w.(http.Hijacker).Hijack()
			if err != nil {
				t.Error(err)
				return
			}
			conn.Close()
			return
		}
		var batch struct {
			Events []kagentExecutionEvent `json:"events"`
		}
		json.Unmarshal(body, &batch)
		fmt.Fprintf(w, `{"success":true,"data":{"streamKey":%q,"received":%d,"recorded":0,"duplicates":%d}}`, strings.Repeat("b", 64), len(batch.Events), len(batch.Events))
	}))
	defer s.Close()
	r.gateway = s.URL
	r.kagentDeliveryPass(context.Background())
	obs.emit(kagentExecutionEvent{Kind: "RUN_STARTED", RunID: toolID("later", "run"), DefinitionKey: "later"})
	r.kagentDeliveryPass(context.Background())
	r.kagentDeliveryPass(context.Background())
	if len(bodies) != 3 || !reflect.DeepEqual(bodies[0], bodies[1]) || reflect.DeepEqual(bodies[1], bodies[2]) {
		t.Fatal("unacknowledged batch mutated")
	}
}

func TestKagentObservationDeliveryDoesNotBlockCapture(t *testing.T) {
	r := kagentInstalledIdentity(t)
	obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
	entered, release := make(chan struct{}), make(chan struct{})
	s := kagentEventTestServer(t, strings.Repeat("a", 64), http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { close(entered); <-release; w.WriteHeader(503) }))
	defer s.Close()
	defer close(release)
	r.gateway = s.URL
	done := make(chan struct{})
	go func() { r.kagentDeliveryPass(context.Background()); close(done) }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("no delivery")
	}
	captured := make(chan struct{})
	go func() {
		obs.mu.Lock()
		obs.emit(kagentExecutionEvent{Kind: "RUN_STARTED", RunID: toolID("concurrent", "run"), DefinitionKey: "concurrent"})
		obs.mu.Unlock()
		close(captured)
	}()
	select {
	case <-captured:
	case <-time.After(time.Second):
		t.Fatal("HTTP blocked business callback")
	}
	// Request's bounded timeout ends the sender even if the peer remains stalled.
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("unbounded delivery")
	}
}

func TestKagentObservationStorageFailureDoesNotChangeBusiness(t *testing.T) {
	r := kagentInstalledIdentity(t)
	if err := os.WriteFile(filepath.Join(r.toolDir, "kagent-events"), []byte("occupied"), 0600); err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		calls.Add(1)
		fmt.Fprint(w, `{"choices":[{"finish_reason":"stop","message":{"content":"answer"}}]}`)
	}))
	defer s.Close()
	r.gateway = s.URL
	r.model = "synthetic"
	r.system = "system"
	r.maxTokens = 32
	answer, err := r.askContext(context.Background(), "input", sumTrace, "task", nil, nil)
	if answer != "answer" || err != nil || calls.Load() != 1 || kagentDeliveryState(r)["state"] != "STORAGE_UNAVAILABLE" {
		t.Fatal("observation changed business result", err)
	}
}

type kagentPanicTransport struct{ calls *atomic.Int32 }

func (t kagentPanicTransport) RoundTrip(*http.Request) (*http.Response, error) {
	t.calls.Add(1)
	panic("synthetic transport panic")
}

func TestKagentObservationPanicNeverRecordsSuccess(t *testing.T) {
	r := kagentInstalledIdentity(t)
	var calls atomic.Int32
	r.gateway = "https://example.invalid"
	r.client = &http.Client{Transport: kagentPanicTransport{calls: &calls}}
	r.model = "synthetic"
	r.system = "system"
	r.maxTokens = 32
	// The pinned SDK converts a transport panic to an iterator error. EAP must
	// preserve UNKNOWN and must not record an invented successful completion.
	_, err := r.askContext(context.Background(), "input", sumTrace, "panic-task", nil, nil)
	if !errors.Is(err, errModelOutcomeUnknown) || calls.Load() != 1 {
		t.Fatal("panic boundary lost", err, calls.Load())
	}
	entries, err := os.ReadDir(filepath.Join(r.toolDir, "kagent-events"))
	if err != nil || len(entries) != 1 {
		t.Fatal("missing panic journal")
	}
	dir := filepath.Join(r.toolDir, "kagent-events", entries[0].Name())
	var e kagentExecutionEvent
	if readKagentJSON(dir, "event-01.json", &e) != nil || e.Kind != "RUN_STARTED" {
		t.Error("missing start")
	}
	if _, err := os.Stat(filepath.Join(dir, "event-02.json")); !os.IsNotExist(err) {
		t.Error("panic created terminal success")
	}
}

func TestKagentObservationUnknownToolReceiptKeepsToolAndRunOpen(t *testing.T) {
	r := kagentInstalledIdentity(t)
	var claims atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if strings.HasSuffix(req.URL.Path, "/chat/completions") {
			fmt.Fprint(w, sumResponse)
			return
		}
		var body map[string]any
		json.NewDecoder(req.Body).Decode(&body)
		id, _ := body["callId"].(string)
		switch req.URL.Path {
		case "/api/runtime-access/v1/tools/check":
			fmt.Fprintf(w, `{"data":{"callId":%q,"state":"APPROVED"}}`, id)
		case "/api/runtime-access/v1/tools/claim":
			claims.Add(1)
			fmt.Fprintf(w, `{"data":{"execute":true,"call":{"callId":%q,"state":"CLAIMED"}}}`, id)
		case "/api/runtime-access/v1/tools/receipt":
			w.WriteHeader(503)
		default:
			t.Error("unexpected channel")
		}
	}))
	defer s.Close()
	r.gateway = s.URL
	r.model = "synthetic"
	r.system = "system"
	r.maxTokens = 128
	r.toolsEnabled = true
	r.kagentToolMode = "CALCULATE_SUM_V1"
	for i := 0; i < 2; i++ {
		_, err := r.askContext(context.Background(), "input", sumTrace, "unknown-tool-task", nil, nil)
		if !errors.Is(err, errModelOutcomeUnknown) {
			t.Fatal("lost UNKNOWN", err)
		}
	}
	entries, err := os.ReadDir(filepath.Join(r.toolDir, "kagent-events"))
	if err != nil || len(entries) != 1 || claims.Load() != 1 {
		t.Fatal("unknown tool repeated")
	}
	dir := filepath.Join(r.toolDir, "kagent-events", entries[0].Name())
	for i, kind := range []string{"RUN_STARTED", "TOOL_STARTED"} {
		var e kagentExecutionEvent
		if readKagentJSON(dir, kagentEventName(i+1), &e) != nil || e.Kind != kind {
			t.Fatal("missing lifecycle start")
		}
	}
	if _, err := os.Stat(filepath.Join(dir, "event-03.json")); !os.IsNotExist(err) {
		t.Fatal("unknown receipt fabricated termination")
	}
}

// Existing event-delivery tests keep their own counters; report protocol behavior
// is exercised independently in kagent_delivery_report_test.go.
func kagentEventTestServer(t *testing.T, stream string, event http.Handler) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == "/api/runtime-access/v1/execution-delivery" {
			var report kagentDeliveryReport
			if json.NewDecoder(req.Body).Decode(&report) != nil {
				t.Error("invalid report request")
				w.WriteHeader(400)
				return
			}
			fmt.Fprintf(w, `{"success":true,"data":{"streamKey":%q,"reportSequence":%d,"duplicate":false}}`, stream, report.ReportSequence)
			return
		}
		event.ServeHTTP(w, req)
	}))
}
