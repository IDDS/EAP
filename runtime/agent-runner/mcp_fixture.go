//go:build mcpfixture && !kagent

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
	"strings"
	"time"
)

func init() {
	runnerFramework, runnerEngineVersion = "EAP_MCP_OBSERVATION_FIXTURE", "1"
	serveTask = (*runner).mcpFixtureTask
	// This dedicated image cannot fall through to a paid model call.
	generateModel = func(*runner, context.Context, []message, string, string) (string, error) {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
}

type mcpFixtureReceipt struct {
	TaskKey     string          `json:"taskKey"`
	TraceID     string          `json:"traceId"`
	Status      string          `json:"status"`
	ReasonCode  string          `json:"reasonCode"`
	Fingerprint string          `json:"fingerprint"`
	Output      json.RawMessage `json:"output,omitempty"`
}

func (r *runner) mcpFixtureTask(w http.ResponseWriter, req *http.Request) {
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
		http.Error(w, "bad fixture task", 400)
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
	dir := filepath.Join(r.toolDir, "mcp-fixture-v1")
	path := filepath.Join(dir, digest([]byte(lease.InstanceKey+"|"+lease.ActorUID+"|"+input.TaskKey))+".json")
	receipt := mcpFixtureReceipt{TaskKey: input.TaskKey, TraceID: input.TraceID, Status: "UNKNOWN",
		ReasonCode: "MCP_FIXTURE_UNCONFIRMED", Fingerprint: digest(raw)}
	if prior, err := os.ReadFile(path); err == nil {
		var saved mcpFixtureReceipt
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
	session, err := r.startMCPService(ctx, "synthetic", input.TraceID, parent)
	var summary mcpRelaySummary
	if err == nil {
		initialize := []byte("{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"eap-fixture\",\"version\":\"1\"}}}\n")
		_, sendErr := session.Stdin.Write(initialize)
		reader := bufio.NewReaderSize(session.Stdout, 4096)
		initialized, initErr := reader.ReadSlice('\n')
		initialized = append([]byte(nil), initialized...)
		if sendErr != nil || initErr != nil || !validMCPInitialization(initialized) {
			cancel()
		}
		wire := []byte("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n" +
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"observation_probe\",\"arguments\":{\"mode\":\"success\"}}}\n" +
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"observation_probe\",\"arguments\":{\"mode\":\"failure\"}}}\n" +
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"observation_probe\",\"arguments\":{\"mode\":\"disconnect\"}}}\n")
		if ctx.Err() == nil {
			_, sendErr = session.Stdin.Write(wire)
		}
		session.Stdin.Close()
		reply, readErr := io.ReadAll(io.LimitReader(reader, 65537))
		reply = append(initialized, reply...)
		if readErr != nil || len(reply) > 65536 {
			cancel()
		}
		summary, err = session.finish()
		if sendErr != nil || initErr != nil || readErr != nil || len(reply) > 65536 || !validMCPFixtureReplies(reply) {
			err = errors.New("MCP synthetic protocol unconfirmed")
		}
	}
	export := summary.RuntimeExport
	if err == nil && !summary.Degraded && summary.ObserverFailures == 0 && export.Submitted == 3 &&
		export.Accepted == 3 && export.Rejected+export.Unknown+export.Unavailable+export.QueueDropped+export.ShutdownDropped == 0 &&
		export.WorkerStopped {
		receipt.Status, receipt.ReasonCode = "SUCCEEDED", "MCP_FIXTURE_COMPLETED"
		receipt.Output, _ = json.Marshal(map[string]any{"synthetic": true, "protocolOutcomes": []string{"SUCCEEDED", "FAILED", "UNKNOWN"}, "observation": summary,
			"mcpServices": r.mcpRegistry.profile()})
	} else {
		err = errors.New("MCP fixture observation unconfirmed")
	}
	// Intent remains UNKNOWN if final storage fails. Never re-execute it.
	if savePrivateJournal(path, receipt) != nil {
		receipt.Status, receipt.ReasonCode, receipt.Output = "UNKNOWN", "MCP_FIXTURE_UNCONFIRMED", nil
		err = errors.New("MCP fixture receipt unconfirmed")
	}
	r.spanAtID(input.TraceID, parent, "agent.task", start, time.Now(), err,
		map[string]string{"eap.task.key": input.TaskKey, "service.version": "eap-mcp-fixture/1"})
	writeJSON(w, receipt)
}

func validMCPFixtureReplies(raw []byte) bool {
	lines := bytes.Split(bytes.TrimSpace(raw), []byte("\n"))
	if len(lines) != 3 {
		return false
	}
	for i, line := range lines {
		var item map[string]json.RawMessage
		if json.Unmarshal(line, &item) != nil || string(item["jsonrpc"]) != `"2.0"` {
			return false
		}
		switch i {
		case 0:
			if !validMCPInitialization(line) {
				return false
			}
		case 1, 2:
			var result struct {
				Content []any `json:"content"`
				IsError bool  `json:"isError"`
			}
			expected := "1"
			if i == 2 {
				expected = "2"
			}
			if string(item["id"]) != expected || json.Unmarshal(item["result"], &result) != nil || result.Content == nil || result.IsError != (i == 2) {
				return false
			}
		}
	}
	return true
}

func validMCPInitialization(raw []byte) bool {
	var response struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      string          `json:"id"`
		Error   json.RawMessage `json:"error"`
		Result  struct {
			ProtocolVersion string `json:"protocolVersion"`
		} `json:"result"`
	}
	return json.Unmarshal(raw, &response) == nil && response.JSONRPC == "2.0" && response.ID == "init" &&
		len(response.Error) == 0 && response.Result.ProtocolVersion == "2025-06-18"
}
