package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"time"
)

// One bounded export follows the durable business receipt. Cached receipts and
// uncertain intents never export or execute again. A crash/export failure leaves
// an observable gap; telemetry must not become a reason to retry model spend.
func (r *runner) experimentSpan(in experimentInput, result experimentResult, start, end time.Time) {
	r.mu.Lock()
	key, token := r.runtimeKey, r.runtimeToken
	r.mu.Unlock()
	if key == "" || token == "" || r.gateway == "" {
		return // Only the instance-scoped telemetry ingress is supported here.
	}
	attribute := func(k, v string) map[string]any {
		return map[string]any{"key": k, "value": map[string]string{"stringValue": v}}
	}
	code := 2
	if result.Status == "SUCCEEDED" {
		code = 1
	}
	// Fixed metadata only: no messages, prompts, outputs, usage or credentials.
	span := map[string]any{
		"traceId": in.TraceID,
		"spanId":  digest([]byte("eap-experiment-span-v1\x00" + r.agentID + "\x00" + key + "\x00" + in.TaskKey + "\x00" + in.TraceID))[:16],
		"name":    "agent.experiment", "kind": 1,
		"startTimeUnixNano": strconv.FormatInt(start.UnixNano(), 10),
		"endTimeUnixNano":   strconv.FormatInt(end.UnixNano(), 10),
		"status":            map[string]any{"code": code},
		"attributes":        []any{attribute("eap.agent.id", r.agentID), attribute("agent.runtime", "substrate"), attribute("eap.runtime.instance", key), attribute("eap.task.key", in.TaskKey), attribute("eap.experiment.status", result.Status)},
	}
	spans := []any{span}
	if in.EmitDiagnostic {
		spans = append(spans, map[string]any{
			"traceId": in.TraceID,
			"spanId":  digest([]byte("eap-optional-diagnostic-v1\x00" + r.agentID + "\x00" + key + "\x00" + in.TaskKey + "\x00" + in.TraceID))[:16],
			"name":    "agent.diagnostic", "kind": 1,
			"startTimeUnixNano": strconv.FormatInt(start.UnixNano(), 10),
			"endTimeUnixNano":   strconv.FormatInt(end.UnixNano(), 10),
		})
	}
	body, err := json.Marshal(map[string]any{"resourceSpans": []any{map[string]any{
		"resource":   map[string]any{"attributes": []any{attribute("service.name", "eap-agent-runner"), attribute("eap.agent.id", r.agentID)}},
		"scopeSpans": []any{map[string]any{"scope": map[string]string{"name": "eap-agent-runner"}, "spans": spans}},
	}}})
	if err != nil {
		return
	}
	req, err := http.NewRequest(http.MethodPost, r.gateway+"/api/runtime-access/v1/traces", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-EAP-Runtime-Key", key)
	req.Header.Set("X-EAP-Runtime-Token", token)
	client := *r.client
	client.Timeout = 2 * time.Second
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	response, err := client.Do(req)
	if err != nil {
		return
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
}
