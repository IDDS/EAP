package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
)

type modelTaskReceipt struct {
	TaskKey      string `json:"taskKey"`
	Status       string `json:"status"`
	Output       string `json:"output"`
	TraceID      string `json:"traceId,omitempty"`
	ReasonCode   string `json:"reasonCode,omitempty"`
	FinishReason string `json:"finishReason,omitempty"`
	Fingerprint  string `json:"-"`
}

type modelOutputError struct{ code, finish string }

func (e *modelOutputError) Error() string { return e.code }

// Only a single, normally finished text response may enter task output or history.
// Provider content and arbitrary finish strings must never enter error messages/spans.
func completedModelOutput(raw []byte) (string, error) {
	var response struct {
		Choices []struct {
			Finish  string `json:"finish_reason"`
			Message struct {
				Content      *string           `json:"content"`
				ToolCalls    []json.RawMessage `json:"tool_calls"`
				FunctionCall json.RawMessage   `json:"function_call"`
				Refusal      *string           `json:"refusal"`
			} `json:"message"`
		} `json:"choices"`
	}
	if json.Unmarshal(raw, &response) != nil || len(response.Choices) != 1 {
		return "", fmt.Errorf("%w: no complete choice", errModelOutcomeUnknown)
	}
	c := response.Choices[0]
	finish := c.Finish
	switch finish {
	case "stop", "length", "content_filter", "tool_calls", "function_call":
	case "":
		finish = "missing"
	default:
		finish = "unrecognized"
	}
	fail := func(code string) (string, error) { return "", &modelOutputError{code, finish} }
	if c.Finish == "length" {
		return fail("MODEL_OUTPUT_TRUNCATED")
	}
	if c.Finish == "content_filter" || c.Message.Refusal != nil && strings.TrimSpace(*c.Message.Refusal) != "" {
		return fail("MODEL_OUTPUT_FILTERED")
	}
	if c.Finish == "tool_calls" || c.Finish == "function_call" || len(c.Message.ToolCalls) > 0 || len(c.Message.FunctionCall) > 0 && string(c.Message.FunctionCall) != "null" {
		return fail("MODEL_TOOL_CALL_UNSUPPORTED")
	}
	if c.Finish != "stop" {
		return fail("MODEL_FINISH_UNCONFIRMED")
	}
	if c.Message.Content == nil || strings.TrimSpace(*c.Message.Content) == "" {
		return fail("MODEL_OUTPUT_EMPTY")
	}
	return *c.Message.Content, nil
}

func validThinkingMode(model, mode string) bool {
	return mode == "" || mode == "PROVIDER_DEFAULT" || mode == "DISABLED" && (model == "deepseek-flash" || model == "deepseek-pro")
}
func (r *runner) applyThinkingMode(payload map[string]any) {
	if r.thinkingMode == "DISABLED" {
		payload["thinking"] = map[string]string{"type": "disabled"}
	}
}
func (r *runner) modelOutputAttributes(err error) map[string]string {
	mode := r.thinkingMode
	if mode == "" {
		mode = "PROVIDER_DEFAULT"
	}
	attrs := map[string]string{"eap.model.request.generation_limit": strconv.Itoa(r.maxTokens), "eap.model.request.thinking_mode": mode}
	if runnerFramework != "EAP_NATIVE" {
		// service.version is already in EAP's public trace metadata allowlist.
		attrs["service.version"] = "kagent/" + strings.ReplaceAll(runnerEngineVersion, "+", ".")
	}
	var outputErr *modelOutputError
	switch {
	case err == nil:
		if runnerFramework == "KAGENT_ADK" && r.kagentToolMode == "CALCULATE_SUM_V1" {
			// Agent completion may follow a confirmed function result. The
			// gateway owns the actual provider finish; do not invent "stop".
			attrs["eap.model.response_state"] = "KAGENT_COMPLETED"
		} else {
			attrs["eap.model.finish_reason"], attrs["eap.model.response_state"] = "stop", "COMPLETE"
		}
	case errors.As(err, &outputErr):
		attrs["eap.model.finish_reason"], attrs["eap.model.response_state"] = outputErr.finish, outputErr.code
	case errors.Is(err, errModelOutcomeUnknown):
		attrs["eap.model.response_state"] = "MODEL_OUTCOME_UNKNOWN"
	default:
		attrs["eap.model.response_state"] = "MODEL_REQUEST_FAILED"
	}
	return attrs
}
