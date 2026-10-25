//go:build framework && !kagent

package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestFrameworkBridgePreservesTaskTraceAndPendingObservation(t *testing.T) {
	r := &runner{runtimeKey: "eap-agents/test", runtimeToken: "test-private-marker", gateway: "http://eap.invalid", toolDir: t.TempDir()}
	j := &toolJournal{CallID: toolID(r.runtimeKey, "one"), TraceID: strings.Repeat("1", 32)}
	result, err := r.frameworkSumWith([]float64{20, 22}, j, func(ctx context.Context, raw []byte) ([]byte, error) {
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > 30*time.Second {
			t.Fatal("missing execution deadline")
		}
		var input frameworkInput
		json.Unmarshal(raw, &input)
		if input.Credential != r.runtimeToken || input.TraceID != j.TraceID || input.CallID != j.CallID || input.Framework != frameworkEngine {
			t.Fatal("incorrect task attribution")
		}
		return json.Marshal(frameworkOutput{Sum: 42, Count: 2, Observation: toolObservation{Framework: frameworkEngine, ProducerID: toolID("producer", "one"), Events: 12, Pending: 12, DeliveryState: "RETRY_EXHAUSTED", DeliveryAttempts: 3}})
	})
	if err != nil || result != 42 || j.Observation == nil || j.Observation.Pending != 12 {
		t.Fatal("upload outage changed successful tool result")
	}
	raw, _ := json.Marshal(j)
	if strings.Contains(string(raw), "test-private-marker") {
		t.Fatal("credential leaked to journal")
	}
}

func TestFrameworkBridgeRejectsUnconfirmedOrForeignResult(t *testing.T) {
	r := &runner{toolDir: t.TempDir()}
	j := &toolJournal{CallID: toolID("test", "one"), TraceID: strings.Repeat("1", 32)}
	valid := frameworkOutput{Sum: 42, Count: 2, Observation: toolObservation{Framework: frameworkEngine, ProducerID: toolID("producer", "one"), StreamKey: strings.Repeat("a", 64), Events: 12, DeliveryState: "ACKNOWLEDGED", DeliveryAttempts: 1}}
	for _, name := range []string{"process-error", "wrong-sum", "wrong-framework", "unconfirmed-stream", "trailing-json", "too-large", "unknown-delivery-state", "false-delivery-success", "excessive-attempts", "old-event-count"} {
		t.Run(name, func(t *testing.T) {
			_, err := r.frameworkSumWith([]float64{20, 22}, j, func(context.Context, []byte) ([]byte, error) {
				out := valid
				switch name {
				case "process-error":
					return nil, errors.New("private-worker-failure")
				case "old-event-count":
					out.Observation.Events = 9
				case "wrong-sum":
					out.Sum = 41
				case "unknown-delivery-state":
					out.Observation.DeliveryState = "invented"
				case "false-delivery-success":
					out.Observation.Pending = 12
					out.Observation.StreamKey = ""
				case "excessive-attempts":
					out.Observation.DeliveryAttempts = 4
				case "wrong-framework":
					out.Observation.Framework = "foreign"
				case "unconfirmed-stream":
					out.Observation.StreamKey = ""
				case "too-large":
					return []byte(strings.Repeat("x", 16385)), nil
				}
				raw, _ := json.Marshal(out)
				if name == "trailing-json" {
					raw = append(raw, []byte("{}")...)
				}
				return raw, nil
			})
			if err == nil || strings.Contains(err.Error(), "private-worker-failure") || j.Observation != nil {
				t.Fatal("unconfirmed result accepted or error leaked")
			}
		})
	}
}

func TestFrameworkGovernedExecutionIsNotRepeatedAfterSuccessOrUnknown(t *testing.T) {
	for _, uncertain := range []bool{false, true} {
		t.Run(map[bool]string{false: "success-with-pending-events", true: "unknown"}[uncertain], func(t *testing.T) {
			var checks, claims, executions atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				var body map[string]any
				json.NewDecoder(q.Body).Decode(&body)
				id, _ := body["callId"].(string)
				switch q.URL.Path {
				case "/api/runtime-access/v1/tools/check":
					checks.Add(1)
					writeJSON(w, map[string]any{"data": guardView{id, "APPROVED"}})
				case "/api/runtime-access/v1/tools/claim":
					claims.Add(1)
					writeJSON(w, map[string]any{"data": map[string]any{"execute": true, "call": guardView{id, "CLAIMED"}}})
				case "/api/runtime-access/v1/tools/receipt":
					writeJSON(w, map[string]any{"data": guardView{id, "SUCCEEDED"}})
				case "/api/runtime-access/v1/traces":
					writeJSON(w, map[string]any{})
				default:
					t.Error("unexpected API")
					w.WriteHeader(500)
				}
			}))
			defer gateway.Close()
			dir := t.TempDir()
			makeRunner := func() *runner {
				return &runner{runtimeKey: "eap-agents/test", runtimeToken: "fixture", gateway: gateway.URL, client: gateway.Client(), toolDir: dir, toolsEnabled: true, sumExecutor: func(numbers []float64, j *toolJournal) (float64, error) {
					executions.Add(1)
					if uncertain {
						return 0, errors.New("uncertain")
					}
					j.Observation = &toolObservation{Framework: frameworkEngine, ProducerID: toolID("producer", "one"), Events: 12, Pending: 12, DeliveryState: "RETRY_EXHAUSTED", DeliveryAttempts: 3}
					return calculateSum(numbers), nil
				}}
			}
			input := `{"taskKey":"fixed","objective":"calculate","context":{"eapTool":{"name":"calculate_sum","arguments":{"numbers":[20,22]}}}}`
			r := makeRunner()
			var group sync.WaitGroup
			for i := 0; i < 4; i++ {
				group.Add(1)
				go func() {
					defer group.Done()
					reply := frameworkRequest(r, input)
					if reply.Code != 200 {
						t.Error("task rejected")
					}
				}()
			}
			group.Wait()
			reply := frameworkRequest(makeRunner(), input)
			want := "SUCCEEDED"
			if uncertain {
				want = "EXECUTION_UNKNOWN"
			}
			if !strings.Contains(reply.Body.String(), want) || executions.Load() != 1 || checks.Load() != 1 || claims.Load() != 1 {
				t.Fatal("duplicate execution or lost original outcome")
			}
			if frameworkRequest(makeRunner(), strings.Replace(input, "[20,22]", "[20,21]", 1)).Code != 409 {
				t.Fatal("changed arguments accepted")
			}
		})
	}
}

func TestFrameworkTaskRejectsModelsUnsupportedToolsAndUnauthenticatedRequests(t *testing.T) {
	r := &runner{runtimeKey: "eap-agents/test", runtimeToken: "fixture"}
	for _, body := range []string{`{"taskKey":"x","objective":"call model"}`, `{"taskKey":"x","context":{"eapTool":{"name":"shell","arguments":{}}}}`} {
		req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer fixture")
		w := httptest.NewRecorder()
		frameworkTask(r, w, req)
		if w.Code != 400 {
			t.Fatal("unsupported task accepted")
		}
	}
	w := httptest.NewRecorder()
	frameworkTask(r, w, httptest.NewRequest("POST", "/eap/task", strings.NewReader(`{}`)))
	if w.Code != 401 {
		t.Fatal("unauthenticated task accepted")
	}
}

func TestFrameworkWorkerOutputIsBounded(t *testing.T) {
	var b boundedWorkerOutput
	if _, err := b.Write([]byte(strings.Repeat("x", 16384))); err != nil {
		t.Fatal(err)
	}
	if _, err := b.Write([]byte("x")); err == nil || b.Len() != 16384 {
		t.Fatal("worker output exceeded bound")
	}
}

func frameworkRequest(r *runner, input string) *httptest.ResponseRecorder {
	req := httptest.NewRequest("POST", "/eap/task", strings.NewReader(input))
	req.Header.Set("Authorization", "Bearer fixture")
	reply := httptest.NewRecorder()
	frameworkTask(r, reply, req)
	return reply
}
