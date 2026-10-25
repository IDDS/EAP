package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// Synthetic HTTP fixtures only. The first four contracts reproduce the old exporter failures.
// No real provider, customer content, runtime credential or tool is used.
func TestTelemetryExportContractFailureDoesNotIncludeRawError(t *testing.T) {
	const marker = "PUBLIC_DIAGNOSTIC_ERROR_MARKER"
	received := make(chan map[string]any, 1)
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		requests.Add(1)
		var payload map[string]any
		if err := json.NewDecoder(req.Body).Decode(&payload); err != nil {
			t.Error("invalid trace payload")
		}
		received <- payload
	}))
	defer server.Close()
	r := &runner{gateway: server.URL, agentID: "7", client: server.Client()}
	r.span(strings.Repeat("1", 32), "agent.task", time.Now(), errors.New(marker), nil)
	if requests.Load() != 1 {
		t.Fatal("missing or duplicate telemetry request")
	}
	payload := <-received
	wire, err := json.Marshal(payload)
	if err != nil || strings.Contains(string(wire), marker) {
		t.Fatal("raw failure information entered telemetry")
	}
	resources, ok := payload["resourceSpans"].([]any)
	if !ok || len(resources) != 1 {
		t.Fatal("missing resource")
	}
	span := resources[0].(map[string]any)["scopeSpans"].([]any)[0].(map[string]any)["spans"].([]any)[0].(map[string]any)
	if span["status"].(map[string]any)["code"] != float64(2) {
		t.Fatal("failure status lost while removing raw content")
	}
}

func TestTelemetryExportContractNeverFollowsRedirect(t *testing.T) {
	var exports, redirected atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		redirected.Add(1)
	}))
	defer target.Close()
	collector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		exports.Add(1)
		_, _ = io.Copy(io.Discard, req.Body)
		http.Redirect(w, req, target.URL, http.StatusTemporaryRedirect)
	}))
	defer collector.Close()
	t.Setenv("EAP_OBSERVABILITY_TOKEN", "synthetic-test-only")
	r := &runner{gateway: collector.URL, agentID: "7", client: collector.Client()}
	r.span(strings.Repeat("2", 32), "agent.task", time.Now(), nil, nil)
	if exports.Load() != 1 || redirected.Load() != 0 {
		t.Fatal("collector redirect caused an extra request")
	}
}

func TestTelemetryExportContractTimeoutIsSeparateFromModelClient(t *testing.T) {
	var exports atomic.Int32
	collector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		exports.Add(1)
		_, _ = io.Copy(io.Discard, req.Body)
		<-req.Context().Done()
	}))
	defer collector.Close()
	modelClient := collector.Client()
	modelClient.Timeout = 110 * time.Second
	r := &runner{gateway: collector.URL, agentID: "7", client: modelClient}
	started := time.Now()
	r.span(strings.Repeat("3", 32), "agent.task", started, nil, nil)
	if elapsed := time.Since(started); elapsed > 4*time.Second {
		t.Fatal("telemetry exceeds its execution-independent bound")
	}
	if exports.Load() != 1 || modelClient.Timeout != 110*time.Second {
		t.Fatal("telemetry retried or changed the model execution timeout")
	}
}

func TestTelemetryExportContractTransportLogDoesNotIncludeRawURL(t *testing.T) {
	const marker = "public-transport-diagnostic-marker"
	var output bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&output)
	defer log.SetOutput(previous)
	r := &runner{gateway: "unsupported://" + marker, agentID: "7", client: &http.Client{Timeout: 110 * time.Second}}
	r.span(strings.Repeat("4", 32), "agent.task", time.Now(), nil, nil)
	if strings.Contains(output.String(), marker) || strings.Contains(output.String(), "unsupported://") {
		t.Fatal("raw request URL entered export log")
	}
}

func TestTelemetryExportContractDelayedExportKeepsExecutionBoundary(t *testing.T) {
	received := make(chan map[string]any, 1)
	collector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		var payload map[string]any
		if err := json.NewDecoder(req.Body).Decode(&payload); err != nil {
			t.Error("invalid trace payload")
		}
		received <- payload
	}))
	defer collector.Close()
	r := &runner{gateway: collector.URL, agentID: "7", client: collector.Client()}
	start := time.Now().Add(-time.Second)
	end := start.Add(17 * time.Millisecond)
	// The exporter starts after execution finished, as a background chat export can.
	r.spanAt(strings.Repeat("5", 32), "agent.turn", start, end, nil, nil)
	payload := <-received
	span := payload["resourceSpans"].([]any)[0].(map[string]any)["scopeSpans"].([]any)[0].(map[string]any)["spans"].([]any)[0].(map[string]any)
	if span["startTimeUnixNano"] != strconv.FormatInt(start.UnixNano(), 10) || span["endTimeUnixNano"] != strconv.FormatInt(end.UnixNano(), 10) {
		t.Fatal("background delay changed execution timestamps")
	}
	for _, entry := range span["attributes"].([]any) {
		attr := entry.(map[string]any)
		if attr["key"] == "agent.duration_ms" {
			if attr["value"].(map[string]any)["intValue"] != float64(17) {
				t.Fatal("background delay inflated execution duration")
			}
			return
		}
	}
	t.Fatal("execution duration missing")
}

func TestTelemetryExportContractMissingInstanceIdentityNeverDowngrades(t *testing.T) {
	var requests atomic.Int32
	collector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { requests.Add(1) }))
	defer collector.Close()
	t.Setenv("EAP_OBSERVABILITY_TOKEN", "synthetic-test-only")
	r := &runner{gateway: collector.URL, agentID: "7", client: collector.Client(), identity: &runtimeIdentity{}}
	r.span(strings.Repeat("6", 32), "agent.task", time.Now(), nil, nil)
	if requests.Load() != 0 {
		t.Fatal("missing instance identity used a legacy telemetry endpoint")
	}
}
