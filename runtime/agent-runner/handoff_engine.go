//go:build (handoff || framework) && !kagent

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

var handoffProcess = runHandoffProcess

type handoffTaskInput struct {
	Protocol       string                     `json:"protocol"`
	TaskKey        string                     `json:"taskKey"`
	WorkflowRunKey string                     `json:"workflowRunKey"`
	NodeKey        string                     `json:"nodeKey"`
	Objective      string                     `json:"objective"`
	TraceID        string                     `json:"traceId"`
	Context        map[string]json.RawMessage `json:"context"`
}
type handoffWorkerInput struct {
	Role          string          `json:"role"`
	Action        string          `json:"action"`
	Session       string          `json:"session"`
	TaskKey       string          `json:"taskKey"`
	TraceID       string          `json:"traceId"`
	RuntimeKey    string          `json:"runtimeKey"`
	Credential    string          `json:"credential"`
	ActorUID      string          `json:"actorUid"`
	IdentityEpoch string          `json:"identityEpoch"`
	Gateway       string          `json:"gateway"`
	Directory     string          `json:"directory"`
	Numbers       []float64       `json:"numbers"`
	Previous      json.RawMessage `json:"previous"`
}

func strictHandoffJSON(raw []byte, out any) error {
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if d.Decode(out) != nil || d.Decode(new(any)) != io.EOF {
		return errors.New("invalid sample JSON")
	}
	return nil
}

// role is selected by the built-in runtime entry, never by workflow input.
func handoffTaskForRole(r *runner, w http.ResponseWriter, req *http.Request, role string) {
	if req.Method != http.MethodPost || !r.authorized(req) {
		http.Error(w, "unauthorized", 401)
		return
	}
	// The server envelope also has agent/node/config metadata; only the frozen sample fields are consumed.
	var task handoffTaskInput
	d := json.NewDecoder(http.MaxBytesReader(w, req.Body, 60000))
	if d.Decode(&task) != nil || d.Decode(new(any)) != io.EOF || task.Protocol != "EAP_HTTP_TASK_V1" || !strings.HasPrefix(task.WorkflowRunKey, "wf-exec-") || !canonicalUUID(strings.TrimPrefix(task.WorkflowRunKey, "wf-exec-")) || !strings.HasPrefix(task.TaskKey, task.WorkflowRunKey+":") || len(task.TaskKey) > 256 {
		http.Error(w, "invalid sample task", 400)
		return
	}
	allowed := role == "source" && (task.NodeKey == "generate" || task.NodeKey == "offer" || task.NodeKey == "resend-source") || role == "target" && (task.NodeKey == "execute" || task.NodeKey == "confirm" || task.NodeKey == "resend-target")
	if !allowed {
		http.Error(w, "unsupported sample phase", 400)
		return
	}
	if r.taskMu.LockContext(req.Context()) != nil {
		http.Error(w, "task cancelled", 503)
		return
	}
	defer r.taskMu.Unlock()
	if !r.authorized(req) {
		http.Error(w, "runtime identity changed", 401)
		return
	}
	var previous map[string]struct {
		TaskKey              string          `json:"taskKey"`
		AgentID              int64           `json:"agentId"`
		Output               json.RawMessage `json:"output"`
		OutputSHA256         string          `json:"outputSha256"`
		OriginalOutputSHA256 string          `json:"originalOutputSha256"`
	}
	if raw, ok := task.Context["eapPreviousResults"]; ok && strictHandoffJSON(raw, &previous) != nil {
		http.Error(w, "invalid upstream result", 400)
		return
	}
	key := ""
	switch task.NodeKey {
	case "execute":
		key = "generate"
	case "offer", "confirm", "resend-source", "resend-target":
		key = "execute"
	}
	var upstream json.RawMessage = json.RawMessage("null")
	if key != "" {
		v, ok := previous[key]
		if !ok || v.AgentID <= 0 || !strings.HasPrefix(v.TaskKey, task.WorkflowRunKey+":") || len(v.Output) == 0 {
			http.Error(w, "missing original upstream result", 400)
			return
		}
		upstream = v.Output
	} else if len(previous) != 0 {
		http.Error(w, "unexpected upstream result", 400)
		return
	}
	if task.NodeKey != "execute" {
		if _, ok := task.Context["eapTool"]; ok {
			http.Error(w, "metadata phase cannot invoke tools", 400)
			return
		}
	}
	r.mu.Lock()
	in := handoffWorkerInput{Role: role, Action: task.NodeKey, Session: strings.TrimPrefix(task.WorkflowRunKey, "wf-exec-"), TaskKey: task.TaskKey, TraceID: task.TraceID, RuntimeKey: r.runtimeKey, Credential: r.runtimeToken, ActorUID: r.credentialUID, IdentityEpoch: r.credentialEpoch, Gateway: r.gateway, Directory: filepath.Join(r.toolDir, "handoff-sample"), Previous: upstream}
	r.mu.Unlock()
	if in.ActorUID == "" || in.IdentityEpoch == "" || r.identity == nil {
		http.Error(w, "installed runtime identity required", 401)
		return
	}
	if task.NodeKey == "execute" {
		var tool toolInput
		if strictHandoffJSON(task.Context["eapTool"], &tool) != nil || tool.Name != "calculate_sum" {
			http.Error(w, "governed calculate_sum required", 400)
			return
		}
		numbers, err := sumNumbers(tool.Arguments)
		if err != nil || len(numbers) != 3 {
			http.Error(w, "sample requires three numbers", 400)
			return
		}
		capture := &handoffResponse{header: make(http.Header)}
		r.guardedTaskWithExecutor(capture, task.TaskKey, task.Objective+"|handoff="+digest(upstream), tool, func(numbers []float64, j *toolJournal) (float64, error) {
			in.Numbers = numbers
			in.TraceID = j.TraceID
			out, err := r.callHandoff(req.Context(), in)
			if err != nil {
				return 0, err
			}
			var value struct {
				Sum     float64         `json:"sum"`
				Count   int             `json:"count"`
				Handoff json.RawMessage `json:"handoff"`
			}
			if strictHandoffJSON(out, &value) != nil || value.Count != len(numbers) || value.Sum != calculateSum(numbers) || len(value.Handoff) == 0 {
				return 0, errors.New("invalid handoff result")
			}
			return value.Sum, nil
		})
		var result map[string]json.RawMessage
		if capture.status != 0 && capture.status != 200 {
			http.Error(w, "guarded task rejected", capture.status)
			return
		}
		if json.Unmarshal(capture.Bytes(), &result) != nil {
			http.Error(w, "tool result unavailable", 503)
			return
		}
		var status string
		json.Unmarshal(result["status"], &status)
		if status == "SUCCEEDED" {
			in.Action = "status"
			in.Numbers = nil
			out, err := r.callHandoff(req.Context(), in)
			if err != nil {
				http.Error(w, "original handoff result unavailable", 503)
				return
			}
			result["output"] = out
		}
		writeJSON(w, result)
		return
	}
	out, err := r.callHandoff(req.Context(), in)
	if err != nil {
		writeJSON(w, map[string]any{"taskKey": task.TaskKey, "status": "EXECUTION_UNKNOWN", "reasonCode": "HANDOFF_UNCONFIRMED"})
		return
	}
	writeJSON(w, map[string]any{"taskKey": task.TaskKey, "status": "SUCCEEDED", "output": json.RawMessage(out)})
}

func (r *runner) callHandoff(parent context.Context, in handoffWorkerInput) ([]byte, error) {
	ctx, cancel := context.WithTimeout(parent, 40*time.Second)
	defer cancel()
	body, err := json.Marshal(in)
	if err != nil {
		return nil, errors.New("invalid sample request")
	}
	out, err := handoffProcess(ctx, body)
	var value map[string]json.RawMessage
	if err != nil || len(out) > 16384 || json.Unmarshal(out, &value) != nil || len(value) == 0 || value["error"] != nil {
		return nil, errors.New("sample phase unconfirmed")
	}
	return out, nil
}

type handoffResponse struct {
	bytes.Buffer
	header http.Header
	status int
}

func (w *handoffResponse) Header() http.Header  { return w.header }
func (w *handoffResponse) WriteHeader(code int) { w.status = code }
func (w *handoffResponse) Write(p []byte) (int, error) {
	if w.Len()+len(p) > 16384 {
		return 0, errors.New("sample output limit")
	}
	return w.Buffer.Write(p)
}
func runHandoffProcess(ctx context.Context, body []byte) ([]byte, error) {
	cmd := exec.CommandContext(ctx, "/usr/bin/python3", "-s", "/opt/eap-handoff/handoff_worker.py")
	cmd.Env = []string{"PATH=/usr/bin:/bin", "HOME=/tmp", "PYTHONPATH=/opt/eap-handoff:/opt/eap-framework:/opt/eap-framework/site", "PYTHONDONTWRITEBYTECODE=1", "LANGSMITH_TRACING=false", "LANGCHAIN_TRACING_V2=false", "OTEL_SDK_DISABLED=true"}
	cmd.Stdin = bytes.NewReader(body)
	out := &handoffResponse{}
	cmd.Stdout = out
	cmd.Stderr = io.Discard
	cmd.WaitDelay = 2 * time.Second
	if cmd.Run() != nil {
		return nil, errors.New("sample process unconfirmed")
	}
	return out.Bytes(), nil
}
