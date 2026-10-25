//go:build framework && !kagent

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"time"
)

// Fixed by the image build, never selected by task input or environment.
var frameworkEngine = "langgraph"

func init() {
	runnerFramework = "FRAMEWORK_OBSERVATION_POC"
	runnerEngineVersion = "1"
	serveTask = frameworkTask
	configureEngine = func(r *runner) { r.sumExecutor = r.frameworkSum }
	runEngine = runFrameworkDelivery
	engineState = frameworkDeliveryState
	generateModel = func(*runner, context.Context, []message, string, string) (string, error) {
		return "", errors.New("model execution is unavailable in this fixed framework template")
	}
}

func frameworkTask(r *runner, w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodPost || !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var task struct {
		TaskKey   string                     `json:"taskKey"`
		Objective string                     `json:"objective"`
		Context   map[string]json.RawMessage `json:"context"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, req.Body, 65536)).Decode(&task) != nil || task.TaskKey == "" || len(task.TaskKey) > 256 {
		http.Error(w, "invalid task", 400)
		return
	}
	var input toolInput
	decoder := json.NewDecoder(bytes.NewReader(task.Context["eapTool"]))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&input) != nil || input.Name != "calculate_sum" || input.Arguments == nil {
		http.Error(w, "this template only accepts the governed calculate_sum tool", 400)
		return
	}
	r.guardedTask(w, task.TaskKey, task.Objective, input)
}

type frameworkInput struct {
	Framework        string                     `json:"framework"`
	Numbers          []float64                  `json:"numbers"`
	CallID           string                     `json:"callId"`
	TraceID          string                     `json:"traceId"`
	RuntimeKey       string                     `json:"runtimeKey"`
	Credential       string                     `json:"credential"`
	Gateway          string                     `json:"gateway"`
	Directory        string                     `json:"directory"`
	DeliveryIdentity *frameworkDeliveryIdentity `json:"deliveryIdentity"`
}
type frameworkOutput struct {
	Sum         float64         `json:"sum"`
	Count       int             `json:"count"`
	Observation toolObservation `json:"observation"`
}

type boundedWorkerOutput struct{ bytes.Buffer }

func (b *boundedWorkerOutput) Write(p []byte) (int, error) {
	if b.Len()+len(p) > 16384 {
		return 0, errors.New("worker output limit")
	}
	return b.Buffer.Write(p)
}

func runFrameworkWorker(ctx context.Context, input []byte) ([]byte, error) {
	return runFrameworkProcess(ctx, "/opt/eap-framework/worker.py", input)
}

func runFrameworkProcess(ctx context.Context, script string, input []byte) ([]byte, error) {
	cmd := exec.CommandContext(ctx, "/usr/bin/python3", "-s", script)
	cmd.Env = []string{"PATH=/usr/bin:/bin", "HOME=/tmp", "PYTHONPATH=/opt/eap-framework:/opt/eap-framework/site", "PYTHONDONTWRITEBYTECODE=1", "LANGSMITH_TRACING=false", "LANGCHAIN_TRACING_V2=false", "OTEL_SDK_DISABLED=true"}
	cmd.Stdin = bytes.NewReader(input)
	var output boundedWorkerOutput
	cmd.Stdout, cmd.Stderr = &output, io.Discard
	cmd.WaitDelay = 2 * time.Second
	if cmd.Run() != nil {
		return nil, errors.New("framework execution not confirmed")
	}
	return output.Bytes(), nil
}

func (r *runner) frameworkSum(numbers []float64, journal *toolJournal) (float64, error) {
	return r.frameworkSumWith(numbers, journal, runFrameworkWorker)
}

func (r *runner) frameworkSumWith(numbers []float64, journal *toolJournal, run func(context.Context, []byte) ([]byte, error)) (float64, error) {
	if frameworkEngine != "langgraph" && frameworkEngine != "autogen-core" || !canonicalUUID(journal.CallID) || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(journal.TraceID) {
		return 0, errors.New("invalid framework configuration")
	}
	directory := filepath.Join(r.toolDir, "execution-events")
	if os.MkdirAll(directory, 0700) != nil {
		return 0, errors.New("observation storage unavailable")
	}
	r.mu.Lock()
	key, credential := r.runtimeKey, r.runtimeToken
	binding := r.frameworkDeliveryIdentityLocked()
	r.mu.Unlock()
	if !r.frameworkDeliveryProjectionMatches(key, binding) {
		binding = nil
	}
	body, err := json.Marshal(frameworkInput{frameworkEngine, numbers, journal.CallID, journal.TraceID, key, credential, r.gateway, directory, binding})
	if err != nil {
		return 0, errors.New("invalid framework input")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	raw, err := run(ctx, body)
	if err != nil {
		return 0, errors.New("framework execution not confirmed")
	}
	var output frameworkOutput
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if len(raw) > 16384 || decoder.Decode(&output) != nil || decoder.Decode(new(any)) != io.EOF || output.Count != len(numbers) || math.IsNaN(output.Sum) || math.IsInf(output.Sum, 0) || output.Sum != calculateSum(numbers) {
		return 0, errors.New("invalid framework result")
	}
	o := output.Observation
	switch o.BackgroundDeliveryState {
	case "", "NOT_ENROLLED", "WAITING", "COMPLETED", "BLOCKED", "CANCELLED", "UNAVAILABLE":
	default:
		return 0, errors.New("invalid background enrollment state")
	}
	if binding != nil && (o.BackgroundDeliveryState == "" || o.BackgroundDeliveryState == "NOT_ENROLLED") {
		return 0, errors.New("missing background enrollment outcome")
	}
	validDelivery := o.DeliveryState == "ACKNOWLEDGED" && o.Pending == 0 && o.DeliveryAttempts >= 1
	if o.Pending == o.Events {
		switch o.DeliveryState {
		case "RETRY_EXHAUSTED", "BLOCKED_AUTH", "BLOCKED_CONFLICT", "BLOCKED_REQUEST", "BLOCKED_RECEIPT", "BLOCKED_TRANSPORT":
			validDelivery = o.DeliveryAttempts >= 1
		case "UNAVAILABLE":
			validDelivery = o.DeliveryAttempts == 0
		}
	}
	if !validDelivery || o.DeliveryAttempts > 3 {
		return 0, errors.New("invalid framework delivery status")
	}
	if o.Framework != frameworkEngine || !canonicalUUID(o.ProducerID) || o.Events != 12 || (o.Pending != 0 && o.Pending != o.Events) || (o.Pending == 0 && !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(o.StreamKey)) || (o.Pending != 0 && o.StreamKey != "") {
		return 0, errors.New("invalid framework observation receipt")
	}
	journal.Observation = &o
	return output.Sum, nil
}
