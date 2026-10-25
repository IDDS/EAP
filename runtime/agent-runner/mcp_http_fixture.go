//go:build mcphttpfixture && !mcpfixture && !kagent

package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

const mcpHTTPProbeVersion = "EAP_MCP_HTTP_PROBE_V1"

func init() {
	runnerFramework, runnerEngineVersion = "EAP_MCP_HTTP_OBSERVATION_FIXTURE", "1"
	serveTask = (*runner).mcpHTTPFixtureTask
	generateModel = func(*runner, context.Context, []message, string, string) (string, error) {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
}

type mcpHTTPReceipt struct {
	TaskKey     string             `json:"taskKey"`
	TraceID     string             `json:"traceId"`
	Status      string             `json:"status"`
	ReasonCode  string             `json:"reasonCode"`
	Fingerprint string             `json:"fingerprint"`
	Output      json.RawMessage    `json:"output,omitempty"`
	Diagnostic  *mcpHTTPDiagnostic `json:"diagnostic,omitempty"`
}

// Fixed host phases and validated READY timings only; no child error text or leases.
type mcpHTTPDiagnostic struct {
	Stage            string         `json:"stage"`
	DeadlineExceeded bool           `json:"deadlineExceeded"`
	StartMS          int64          `json:"startMs"`
	ReadyMS          int64          `json:"readyMs"`
	ControlMS        int64          `json:"controlMs"`
	ProofMS          int64          `json:"proofMs"`
	ExitMS           int64          `json:"exitMs"`
	Startup          mcpHTTPStartup `json:"startup,omitempty"`
}

func (d mcpHTTPDiagnostic) attributes() map[string]string {
	attrs := map[string]string{
		"eap.runtime.stage":             d.Stage,
		"eap.runtime.deadline_exceeded": strconv.FormatBool(d.DeadlineExceeded),
		"eap.runtime.observer_start_ms": strconv.FormatInt(d.StartMS, 10),
		"eap.runtime.ready_wait_ms":     strconv.FormatInt(d.ReadyMS, 10),
		"eap.runtime.control_write_ms":  strconv.FormatInt(d.ControlMS, 10),
		"eap.runtime.proof_read_ms":     strconv.FormatInt(d.ProofMS, 10),
		"eap.runtime.exit_wait_ms":      strconv.FormatInt(d.ExitMS, 10),
	}
	if d.Startup != nil && d.Startup.valid() {
		for key, suffix := range map[string]string{
			"version": "version", "observerImportMs": "observer_import_ms", "identityReadyMs": "identity_ready_ms",
			"exporterStartMs": "exporter_start_ms", "serverSpawnMs": "server_spawn_ms",
			"serverReadyWaitMs": "server_ready_wait_ms", "observerBeforeReadyMs": "observer_before_ready_ms",
			"serverImportMs": "server_import_ms", "serverBindMs": "server_bind_ms",
			"serverFqdnMs": "server_fqdn_ms", "serverBeforeReadyMs": "server_before_ready_ms",
		} {
			attrs["eap.runtime.startup."+suffix] = strconv.FormatInt(d.Startup[key], 10)
		}
	}
	return attrs
}

// Legacy READY may omit startup; a supplied object must be complete and bounded.
// Retain it before sending control, independently of the final child summary.
func parseMCPHTTPReady(raw []byte) (mcpHTTPStartup, bool) {
	if validateMCPJSON(json.NewDecoder(bytes.NewReader(raw)), 0) != nil ||
		!mcpExactFields(raw, []string{"version", "phase"}, []string{"startup"}) {
		return nil, false
	}
	var state struct {
		Version string         `json:"version"`
		Phase   string         `json:"phase"`
		Startup mcpHTTPStartup `json:"startup"`
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &state) != nil || json.Unmarshal(raw, &fields) != nil ||
		state.Version != mcpHTTPProbeVersion || state.Phase != "READY" || !state.Startup.valid() {
		return nil, false
	}
	if rawStartup, present := fields["startup"]; present {
		var values map[string]json.RawMessage
		if state.Startup == nil || json.Unmarshal(rawStartup, &values) != nil {
			return nil, false
		}
		for _, value := range values {
			if bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
				return nil, false
			}
		}
	}
	return state.Startup, true
}

// cfg is supplied by trusted image code; tests substitute bounded subprocesses.
func (r *runner) runMCPHTTPProbe(ctx context.Context, cfg mcpLaunchConfig, trace, parent string, sequence int64) (json.RawMessage, mcpRelaySummary, mcpHTTPDiagnostic, error) {
	d := mcpHTTPDiagnostic{Stage: "START_OBSERVER"}
	failed := errors.New("HTTP fixture observation unconfirmed")
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	mark := time.Now()
	session, err := r.startObservedMCP(ctx, cfg, trace, parent)
	d.StartMS = time.Since(mark).Milliseconds()
	if err != nil {
		d.DeadlineExceeded = errors.Is(ctx.Err(), context.DeadlineExceeded)
		return nil, mcpRelaySummary{}, d, failed
	}
	reader := bufio.NewReaderSize(session.Stdout, 4096)
	d.Stage, mark = "WAIT_READY", time.Now()
	ready, readErr := reader.ReadSlice('\n')
	d.ReadyMS = time.Since(mark).Milliseconds()
	startup, readyValid := parseMCPHTTPReady(ready)
	readyValid = readErr == nil && readyValid
	if readyValid {
		d.Startup = startup
	}
	var proof json.RawMessage
	var summary mcpRelaySummary
	// Freeze the first failed phase before cancellation/cleanup can obscure it.
	failedStage := ""
	if !readyValid {
		failedStage = d.Stage
		cancel()
	} else {
		d.Stage, mark = "SEND_CONTROL", time.Now()
		control, _ := json.Marshal(map[string]any{"version": mcpHTTPProbeVersion, "minimumCredentialSequence": sequence})
		n, sendErr := session.Stdin.Write(control)
		d.ControlMS = time.Since(mark).Milliseconds()
		if sendErr != nil || n != len(control) {
			failedStage = d.Stage
			cancel()
		}
	}
	session.Stdin.Close()
	if failedStage == "" {
		d.Stage = "READ_PROOF"
	}
	mark = time.Now()
	proof, readErr = io.ReadAll(io.LimitReader(reader, 4097))
	d.ProofMS = time.Since(mark).Milliseconds()
	if failedStage == "" && (readErr != nil || len(proof) > 4096 || ctx.Err() != nil) {
		failedStage = d.Stage
		cancel()
	}
	if failedStage == "" {
		d.Stage = "WAIT_EXIT"
	}
	mark = time.Now()
	summary, err = session.finish()
	d.ExitMS = time.Since(mark).Milliseconds()
	if failedStage == "" && (err != nil || ctx.Err() != nil) {
		failedStage = d.Stage
	}
	if failedStage == "" && !validMCPHTTPProof(proof) {
		failedStage = "VALIDATE_PROOF"
	}
	export := summary.RuntimeExport
	if failedStage == "" && (summary.Degraded || summary.ObserverFailures+summary.DeliveryFailures+
		summary.DroppedEvents+summary.DroppedFrames+summary.ExportFailures != 0 || summary.ChildExitTimeout ||
		export.Submitted != 6 || export.Accepted != 6 || export.Rejected+export.Unknown+export.Unavailable+
		export.QueueDropped+export.ShutdownDropped+export.InvalidLeaseUpdates != 0 || !export.WorkerStopped) {
		failedStage = "VERIFY_EXPORT"
	}
	d.DeadlineExceeded = errors.Is(ctx.Err(), context.DeadlineExceeded)
	if failedStage != "" {
		d.Stage = failedStage
		return nil, summary, d, failed
	}
	d.Stage = "COMPLETED"
	return proof, summary, d, nil
}

type mcpHTTPProof struct {
	Version                  string   `json:"version"`
	Transport                string   `json:"transport"`
	Synthetic                bool     `json:"synthetic"`
	ProtocolVersions         []string `json:"protocolVersions"`
	ProtocolOutcomes         []string `json:"protocolOutcomes"`
	HTTPRequests             int      `json:"httpRequests"`
	CredentialChannelsAbsent bool     `json:"credentialChannelsAbsent"`
}

func validMCPHTTPProof(raw []byte) bool {
	if validateMCPJSON(json.NewDecoder(bytes.NewReader(raw)), 0) != nil ||
		!mcpExactFields(raw, []string{"version", "transport", "synthetic", "protocolVersions",
			"protocolOutcomes", "httpRequests", "credentialChannelsAbsent"}, nil) {
		return false
	}
	var proof mcpHTTPProof
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	return dec.Decode(&proof) == nil && dec.Decode(new(any)) == io.EOF &&
		proof.Version == mcpHTTPProbeVersion && proof.Transport == "STREAMABLE_HTTP" &&
		proof.Synthetic && proof.CredentialChannelsAbsent && proof.HTTPRequests == 8 &&
		slices.Equal(proof.ProtocolVersions, []string{"2025-11-25", "2026-07-28"}) &&
		slices.Equal(proof.ProtocolOutcomes, []string{"SUCCEEDED", "FAILED", "UNKNOWN", "SUCCEEDED", "FAILED", "UNKNOWN"})
}

func (r *runner) mcpHTTPFixtureTask(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodPost || !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var input struct {
		TaskKey string `json:"taskKey"`
		TraceID string `json:"traceId"`
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, req.Body, 65536))
	if err != nil || json.Unmarshal(raw, &input) != nil || input.TaskKey == "" || len(input.TaskKey) > 256 ||
		!regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(input.TraceID) || strings.Trim(input.TraceID, "0") == "" {
		http.Error(w, "bad HTTP fixture task", 400)
		return
	}
	if r.taskMu.LockContext(req.Context()) != nil {
		http.Error(w, "fixture busy", 409)
		return
	}
	defer r.taskMu.Unlock()
	if !r.authorized(req) {
		http.Error(w, "unauthorized", 401)
		return
	}
	lease, valid := r.mcpLeaseSnapshot()
	if !valid {
		http.Error(w, "identity unavailable", 409)
		return
	}
	dir := filepath.Join(r.toolDir, "mcp-http-fixture-v1")
	path := filepath.Join(dir, digest([]byte(lease.InstanceKey+"|"+lease.ActorUID+"|"+input.TaskKey))+".json")
	receipt := mcpHTTPReceipt{TaskKey: input.TaskKey, TraceID: input.TraceID, Status: "UNKNOWN",
		ReasonCode: "MCP_HTTP_FIXTURE_UNCONFIRMED", Fingerprint: digest(raw)}
	if prior, err := os.ReadFile(path); err == nil {
		var saved mcpHTTPReceipt
		if json.Unmarshal(prior, &saved) != nil || saved.Fingerprint != receipt.Fingerprint {
			http.Error(w, "fixture identity conflict", 409)
			return
		}
		writeJSON(w, saved)
		return
	} else if !os.IsNotExist(err) {
		http.Error(w, "fixture journal unavailable", 503)
		return
	}
	if os.MkdirAll(dir, 0700) != nil || savePrivateJournal(path, receipt) != nil {
		http.Error(w, "fixture journal unavailable", 503)
		return
	}
	start, parent := time.Now(), randomHex(8)
	ctx, cancel := context.WithTimeout(req.Context(), 20*time.Second)
	defer cancel()
	// A fixed acceptance driver, not an arbitrary endpoint/command from a task.
	cfg := mcpLaunchConfig{Python: "/usr/local/bin/python3", Relay: "/opt/eap-mcp/http_actor_probe.py",
		Server: "eap-http-synthetic-mcp", Tools: []string{"observation_probe"},
		Command:   []string{"/usr/local/bin/python3", "/opt/eap-mcp/http_actor_server.py"},
		AllowHTTP: true, Observer: "eap-mcp-http-observer"}
	proof, summary, diagnostic, err := r.runMCPHTTPProbe(ctx, cfg, input.TraceID, parent, lease.Sequence)
	receipt.Diagnostic = &diagnostic
	if err == nil {
		receipt.Status, receipt.ReasonCode = "SUCCEEDED", "MCP_HTTP_FIXTURE_COMPLETED"
		receipt.Output, _ = json.Marshal(map[string]any{"synthetic": true, "httpProbe": proof, "observation": summary})
	}
	if savePrivateJournal(path, receipt) != nil {
		receipt.Status, receipt.ReasonCode, receipt.Output = "UNKNOWN", "MCP_HTTP_FIXTURE_UNCONFIRMED", nil
		err = errors.New("HTTP fixture receipt unconfirmed")
		diagnostic.Stage = "PERSIST_RECEIPT"
	}
	attrs := diagnostic.attributes()
	attrs["eap.task.key"], attrs["service.version"] = input.TaskKey, "eap-mcp-http-fixture/2"
	r.spanAtID(input.TraceID, parent, "agent.task", start, time.Now(), err,
		attrs)
	writeJSON(w, receipt)
}
