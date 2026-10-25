//go:build kagent

package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const kagentSumDescription = "Calculate the sum of 1 to 100 finite numbers. EAP authorization is required. Return the confirmed result directly."

func kagentSumSchema() map[string]any {
	return map[string]any{"type": "object", "additionalProperties": false, "required": []string{"numbers"},
		"properties": map[string]any{"numbers": map[string]any{"type": "array", "minItems": 1, "maxItems": 100,
			"items": map[string]any{"type": "number", "minimum": -1e12, "maximum": 1e12}}}}
}

func kagentSumTools() []any {
	return []any{map[string]any{"type": "function", "function": map[string]any{
		"name": "calculate_sum", "description": kagentSumDescription, "parameters": kagentSumSchema()}}}
}

func equalJSON(a, b any) bool {
	x, ex := json.Marshal(a)
	y, ey := json.Marshal(b)
	return ex == nil && ey == nil && string(x) == string(y)
}

func (t *kagentTransport) validTools(raw json.RawMessage) bool {
	if !t.toolsEnabled {
		return len(raw) == 0
	}
	var value any
	return json.Unmarshal(raw, &value) == nil && equalJSON(value, kagentSumTools())
}

// Check the provider's raw finish and complete function arguments BEFORE the SDK
// can normalize them. Exactly one fixed, read-only function is permitted.
func (t *kagentTransport) acceptToolResponse(raw []byte) ([]byte, error) {
	var body struct {
		Choices []struct {
			Finish  string `json:"finish_reason"`
			Message struct {
				Content *string         `json:"content"`
				Refusal *string         `json:"refusal"`
				Legacy  json.RawMessage `json:"function_call"`
				Calls   []struct {
					ID       string `json:"id"`
					Type     string `json:"type"`
					Function struct {
						Name      string `json:"name"`
						Arguments string `json:"arguments"`
					} `json:"function"`
				} `json:"tool_calls"`
			} `json:"message"`
		} `json:"choices"`
	}
	if json.Unmarshal(raw, &body) != nil || len(body.Choices) != 1 {
		return nil, errModelOutcomeUnknown
	}
	c := body.Choices[0]
	if c.Finish != "tool_calls" {
		answer, err := completedModelOutput(raw)
		if err != nil {
			return nil, err
		}
		t.answer = answer
		return json.Marshal(map[string]any{"id": "eap-admitted", "object": "chat.completion", "model": t.runner.model,
			"choices": []any{map[string]any{"index": 0, "finish_reason": "stop", "message": map[string]any{"role": "assistant", "content": answer}}}})
	}
	fail := func() ([]byte, error) {
		return nil, &modelOutputError{code: "MODEL_TOOL_CALL_REJECTED", finish: "tool_calls"}
	}
	if len(c.Message.Calls) != 1 || c.Message.Refusal != nil && strings.TrimSpace(*c.Message.Refusal) != "" ||
		len(c.Message.Legacy) != 0 && string(c.Message.Legacy) != "null" {
		return fail()
	}
	call := c.Message.Calls[0]
	if call.Type != "function" || call.Function.Name != "calculate_sum" ||
		!regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`).MatchString(call.ID) || len(call.Function.Arguments) > 8192 {
		return fail()
	}
	var args map[string]any
	if json.Unmarshal([]byte(call.Function.Arguments), &args) != nil {
		return fail()
	}
	if _, err := sumNumbers(args); err != nil {
		return fail()
	}
	// Re-encode only validated data; arbitrary provider fields never enter ADK.
	canonical, _ := json.Marshal(args)
	t.providerCallID, t.toolArguments = call.ID, args
	return json.Marshal(map[string]any{"id": "eap-admitted", "object": "chat.completion", "model": t.runner.model,
		"choices": []any{map[string]any{"index": 0, "finish_reason": "tool_calls", "message": map[string]any{
			"role": "assistant", "tool_calls": []any{map[string]any{"id": call.ID, "type": "function",
				"function": map[string]any{"name": "calculate_sum", "arguments": string(canonical)}}}}}}})
}

type kagentInvocationReceipt struct {
	Fingerprint string `json:"fingerprint"`
	Status      string `json:"status"`
	Output      string `json:"output,omitempty"`
	Code        string `json:"code,omitempty"`
	Finish      string `json:"finish,omitempty"`
}

// Freeze intent before the model call, including chat. A restart or uncertain
// write cannot turn the same task/trace into a new model or tool execution.
func (r *runner) generateKagentTool(ctx context.Context, messages []message, traceID, taskKey string) (string, error) {
	r.mu.Lock()
	key, token := r.runtimeKey, r.runtimeToken
	r.mu.Unlock()
	if !r.toolsEnabled || key == "" || token == "" || r.toolDir == "" ||
		!regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(traceID) || len(taskKey) > 256 || ctx.Err() != nil {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	r.toolMu.Lock()
	defer r.toolMu.Unlock()
	scope := "task:" + taskKey
	if taskKey == "" {
		scope = "trace:" + traceID
	}
	id := toolID(key, "kagent-sum-v1:"+scope)
	dir := filepath.Join(r.toolDir, "kagent-invocations")
	path := filepath.Join(dir, id+".json")
	frozen, _ := json.Marshal([]any{messages, traceID, taskKey, r.model, r.maxTokens, r.thinkingMode, r.kagentToolMode})
	receipt := kagentInvocationReceipt{Fingerprint: digest(frozen), Status: "UNKNOWN"}
	restore := func(saved kagentInvocationReceipt) (string, error) {
		switch saved.Status {
		case "SUCCEEDED":
			if saved.Output != "" {
				return saved.Output, nil
			}
		case "FAILED":
			if saved.Code != "" {
				return "", &modelOutputError{code: saved.Code, finish: saved.Finish}
			}
		}
		return "", errModelOutcomeUnknown
	}
	if raw, err := os.ReadFile(path); err == nil {
		var saved kagentInvocationReceipt
		if json.Unmarshal(raw, &saved) != nil {
			return "", errModelOutcomeUnknown
		}
		if saved.Fingerprint != receipt.Fingerprint {
			return "", &modelOutputError{code: "KAGENT_INVOCATION_CONFLICT"}
		}
		return restore(saved)
	} else if !os.IsNotExist(err) {
		return "", errModelOutcomeUnknown
	}
	if os.MkdirAll(dir, 0700) != nil || savePrivateJournal(path, receipt) != nil {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	answer, err := generateKagentOnce(r, ctx, messages, traceID, taskKey, true)
	if err == nil {
		receipt.Status, receipt.Output = "SUCCEEDED", answer
	} else if !errors.Is(err, errModelOutcomeUnknown) {
		receipt.Status, receipt.Code = "FAILED", "KAGENT_REQUEST_FAILED"
		var output *modelOutputError
		if errors.As(err, &output) {
			receipt.Code, receipt.Finish = output.code, output.finish
		}
	}
	if savePrivateJournal(path, receipt) != nil {
		return "", errModelOutcomeUnknown
	}
	return restore(receipt)
}

// Runs inside the actual ADK function callback. The model's call ID is never an
// EAP authority or effect ID. check/claim/receipt use the original runtime and
// trace; no callback is repeated when a receipt or cancellation is uncertain.
func (t *kagentTransport) executeSum(ctx context.Context, args map[string]any) (map[string]any, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	fail := func(err error) (map[string]any, error) {
		t.err = err
		return nil, errors.New("EAP tool result not confirmed")
	}
	if !t.toolsEnabled || !t.sent.Load() || t.toolUsed || t.providerCallID == "" || !equalJSON(args, t.toolArguments) {
		return fail(errModelOutcomeUnknown)
	}
	t.toolUsed = true
	if ctx.Err() != nil {
		return fail(&modelOutputError{code: "KAGENT_TOOL_CANCELED"})
	}
	numbers, err := sumNumbers(args)
	if err != nil {
		return fail(&modelOutputError{code: "MODEL_TOOL_CALL_REJECTED"})
	}
	t.runner.mu.Lock()
	key := t.runner.runtimeKey
	t.runner.mu.Unlock()
	scope := "task:" + t.taskKey
	if t.taskKey == "" {
		scope = "trace:" + t.traceID
	}
	id := toolID(key, "kagent-sum-v1:"+scope)
	path := filepath.Join(t.runner.toolDir, id+".json")
	raw, _ := json.Marshal(args)
	j := &toolJournal{Fingerprint: digest(raw), CallID: id, TraceID: t.traceID, Status: "EXECUTION_UNKNOWN", GuardState: "CHECK_UNCONFIRMED"}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		return fail(errModelOutcomeUnknown)
	}
	if saveTool(path, j) != nil {
		return fail(errModelOutcomeUnknown)
	}
	start := time.Now()
	var checked guardView
	if t.runner.guardPostContext(ctx, "check", map[string]any{"callId": id, "toolName": "calculate_sum", "toolKind": "TOOL",
		"traceId": t.traceID, "arguments": args}, &checked) != nil || checked.CallID != id {
		return fail(errModelOutcomeUnknown)
	}
	j.GuardState = checked.State
	if checked.State != "APPROVED" {
		j.Status = "FAILED"
		if saveTool(path, j) != nil {
			return fail(errModelOutcomeUnknown)
		}
		return fail(&modelOutputError{code: "KAGENT_TOOL_NOT_APPROVED"})
	}
	if ctx.Err() != nil {
		return fail(&modelOutputError{code: "KAGENT_TOOL_CANCELED"})
	}
	j.GuardState = "CLAIM_UNCONFIRMED"
	if saveTool(path, j) != nil {
		return fail(errModelOutcomeUnknown)
	}
	var claim struct {
		Execute bool      `json:"execute"`
		Call    guardView `json:"call"`
	}
	if t.runner.guardPostContext(ctx, "claim", map[string]any{"callId": id}, &claim) != nil || claim.Call.CallID != id {
		return fail(errModelOutcomeUnknown)
	}
	if !claim.Execute || claim.Call.State != "CLAIMED" {
		// A pre-existing/unknown claim is never evidence that this tool did not run.
		return fail(errModelOutcomeUnknown)
	}
	if ctx.Err() != nil {
		return fail(errModelOutcomeUnknown)
	}
	j.Output = map[string]any{"sum": calculateSum(numbers), "count": len(numbers)}
	j.Executed, j.Status, j.GuardState = true, "EXECUTION_UNKNOWN", "RECEIPT_UNCONFIRMED"
	if saveTool(path, j) != nil {
		return fail(errModelOutcomeUnknown)
	}
	output, _ := json.Marshal(j.Output)
	var confirmed guardView
	if t.runner.guardPostContext(ctx, "receipt", map[string]any{"callId": id, "outcome": "SUCCEEDED",
		"durationMs": time.Since(start).Milliseconds(), "outputDigest": digest(output)}, &confirmed) != nil ||
		confirmed.CallID != id || confirmed.State != "SUCCEEDED" {
		return fail(errModelOutcomeUnknown)
	}
	j.ReceiptConfirmed, j.Status, j.GuardState = true, "SUCCEEDED", "SUCCEEDED"
	if saveTool(path, j) != nil {
		return fail(errModelOutcomeUnknown)
	}
	t.toolResult = map[string]any{"tool": "calculate_sum", "callId": id, "sum": calculateSum(numbers), "count": len(numbers), "receiptConfirmed": true}
	value, _ := json.Marshal(t.toolResult)
	t.answer = string(value)
	return t.toolResult, nil
}
