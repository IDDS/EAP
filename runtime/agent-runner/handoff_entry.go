//go:build handoff && !framework && !kagent

package main

import (
	"context"
	"errors"
	"net/http"
)

// Fixed by the sample image build. No task-provided role or executable.
var handoffRole = "source"

func init() {
	runnerFramework, runnerEngineVersion = "EAP_CODE_HANDOFF_SAMPLE", "1"
	serveTask = handoffTask
	generateModel = func(*runner, context.Context, []message, string, string) (string, error) {
		return "", errors.New("model execution is unavailable in this sample")
	}
}

func handoffTask(r *runner, w http.ResponseWriter, req *http.Request) {
	handoffTaskForRole(r, w, req, handoffRole)
}
