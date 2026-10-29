//go:build kagent

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/kagent-dev/kagent/go/adk/pkg/models"
	"github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/option"
	adkagent "google.golang.org/adk/v2/agent"
	"google.golang.org/adk/v2/agent/llmagent"
	adkmodel "google.golang.org/adk/v2/model"
	adkrunner "google.golang.org/adk/v2/runner"
	"google.golang.org/adk/v2/session"
	"google.golang.org/adk/v2/tool"
	"google.golang.org/adk/v2/tool/functiontool"
	"google.golang.org/genai"
)

const kagentVersion = "v1.0.0-alpha14+673d181ec269"

func init() {
	runnerFramework = "KAGENT_ADK"
	runnerEngineVersion = kagentVersion
	generateModel = generateKagent
	runEngine = runKagentDelivery
	engineState = kagentDeliveryState
	configureEngine = func(r *runner) { r.observationWake = make(chan struct{}, 1) }
}

// This is an EAP-owned execution adapter, not a Kagent controller or Session
// implementation. Kagent's actual Go ADK OpenAI model adapter and the ADK runner
// execute a fresh, text-only turn. No tool, subagent, compaction, memory or SDK
// retry may create extra model calls outside the original EAP admission.
func generateKagent(r *runner, ctx context.Context, messages []message, traceID, taskKey string) (string, error) {
	if r.kagentToolMode == "CALCULATE_SUM_V1" {
		return r.generateKagentTool(ctx, messages, traceID, taskKey)
	}
	if r.kagentToolMode != "" && r.kagentToolMode != "NONE" {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	return generateKagentOnce(r, ctx, messages, traceID, taskKey, false)
}

func generateKagentOnce(r *runner, ctx context.Context, messages []message, traceID, taskKey string, toolsEnabled bool) (result string, resultErr error) {
	if len(messages) < 2 || messages[0].Role != "system" {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	guard := &kagentTransport{runner: r, messages: messages, traceID: traceID, taskKey: taskKey, toolsEnabled: toolsEnabled}
	limit := int64(r.maxTokens)
	model := &models.OpenAIModel{
		Config: &models.OpenAIConfig{Model: r.model, MaxTokens: &limit},
		Client: openai.NewClient(option.WithAPIKey(""), option.WithBaseURL("https://eap-model.invalid/v1/"),
			option.WithMaxRetries(0), option.WithHTTPClient(&http.Client{Transport: guard})),
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	var registered []tool.Tool
	if toolsEnabled {
		sum, err := functiontool.New(functiontool.Config{Name: "calculate_sum", Description: kagentSumDescription},
			func(callCtx adkagent.Context, args map[string]any) (map[string]any, error) {
				callCtx.Actions().SkipSummarization = true
				return guard.executeSum(ctx, args)
			})
		if err != nil {
			return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
		}
		registered = []tool.Tool{sum}
	}
	agent, err := llmagent.New(llmagent.Config{
		Name: "eap_kagent", Model: model, Tools: registered,
		BeforeModelCallbacks: []llmagent.BeforeModelCallback{func(_ adkagent.Context, request *adkmodel.LLMRequest) (*adkmodel.LLMResponse, error) {
			request.Contents = nil
			for _, item := range messages[1:] {
				var role genai.Role = genai.RoleUser
				if item.Role == "assistant" {
					role = genai.RoleModel
				} else if item.Role != "user" {
					return nil, &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
				}
				request.Contents = append(request.Contents, genai.NewContentFromText(item.Content, role))
			}
			request.Config = &genai.GenerateContentConfig{SystemInstruction: genai.NewContentFromText(messages[0].Content, genai.RoleUser)}
			if toolsEnabled {
				request.Config.Tools = []*genai.Tool{{FunctionDeclarations: []*genai.FunctionDeclaration{{
					Name: "calculate_sum", Description: kagentSumDescription, ParametersJsonSchema: kagentSumSchema(),
				}}}}
			}
			return nil, nil
		}},
	})
	if err != nil {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	observation := r.newKagentObservation(agent, traceID)
	defer func() {
		if failure := recover(); failure != nil {
			observation.finish(errModelOutcomeUnknown)
			panic(failure)
		}
		observation.finish(resultErr)
	}()
	sessions := session.InMemoryService()
	executor, err := adkrunner.New(adkrunner.Config{AppName: "eap", Agent: agent, SessionService: sessions,
		PluginConfig: adkrunner.PluginConfig{Plugins: observation.plugins()}})
	if err != nil {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	conversation, err := sessions.Create(ctx, &session.CreateRequest{AppName: "eap", UserID: "invocation"})
	if err != nil {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	var answer strings.Builder
	var runErr error
	for event, err := range executor.Run(ctx, "invocation", conversation.Session.ID(),
		genai.NewContentFromText(messages[len(messages)-1].Content, genai.RoleUser), adkagent.RunConfig{}) {
		if err != nil {
			runErr = err
			break
		}
		if event != nil && event.Content != nil && event.IsFinalResponse() {
			for _, part := range event.Content.Parts {
				if toolsEnabled && part != nil && part.FunctionResponse != nil {
					guard.mu.Lock()
					matched := guard.toolResult != nil && part.FunctionResponse.Name == "calculate_sum" &&
						part.FunctionResponse.ID == guard.providerCallID && equalJSON(part.FunctionResponse.Response, guard.toolResult)
					if matched {
						answer.WriteString(guard.answer)
					}
					guard.mu.Unlock()
					if !matched {
						runErr = errors.New("unconfirmed ADK tool result")
					}
					continue
				}
				if part == nil || part.FunctionCall != nil || part.FunctionResponse != nil || part.InlineData != nil {
					runErr = errors.New("unsupported ADK result")
					break
				}
				answer.WriteString(part.Text)
			}
		}
	}
	guard.mu.Lock()
	defer guard.mu.Unlock()
	if guard.err != nil {
		return "", guard.err // Preserve UNKNOWN/truncation/refusal semantics before SDK normalization.
	}
	if !guard.sent.Load() {
		return "", &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
	}
	if runErr != nil || answer.String() != guard.answer || answer.Len() == 0 {
		return "", errModelOutcomeUnknown
	}
	return answer.String(), nil
}

type kagentTransport struct {
	runner           *runner
	messages         []message
	traceID, taskKey string
	sent             atomic.Bool
	mu               sync.Mutex
	answer           string
	err              error
	toolsEnabled     bool
	providerCallID   string
	toolArguments    map[string]any
	toolResult       map[string]any
	toolUsed         bool
}

// The SDK's synthetic URL is never resolved. Check its exact converted message
// sequence, then invoke the existing EAP transport once. Provider credentials,
// instance leases and caller-supplied headers never enter the SDK.
func (t *kagentTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	reject := func() (*http.Response, error) {
		if t.err == nil {
			if t.sent.Load() {
				t.err = errModelOutcomeUnknown
			} else {
				t.err = &modelOutputError{code: "MODEL_NOT_DISPATCHED"}
			}
		}
		return nil, errors.New("EAP rejected model transport")
	}
	if req.Method != http.MethodPost || req.URL.String() != "https://eap-model.invalid/v1/chat/completions" || req.Body == nil {
		return reject()
	}
	defer req.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(req.Body, (4<<20)+1))
	if err != nil || len(raw) > 4<<20 {
		return reject()
	}
	var payload struct {
		Model    string          `json:"model"`
		Messages []message       `json:"messages"`
		Stream   bool            `json:"stream"`
		Tools    json.RawMessage `json:"tools"`
	}
	// Pinned alpha14's genaiContentsToOpenAIMessages emits ordinary assistant
	// text as user text. Verify that exact SDK projection, but forward the frozen
	// ORIGINAL messages below so provider history never inherits this upstream bug.
	expected := append([]message(nil), t.messages...)
	for i := range expected {
		if expected[i].Role == "assistant" {
			expected[i].Role = "user"
		}
	}
	if len(expected) > 0 && expected[0].Role == "system" {
		expected[0].Content = strings.TrimSpace(expected[0].Content)
		if expected[0].Content == "" {
			expected = expected[1:]
		}
	}
	if json.Unmarshal(raw, &payload) != nil || payload.Model != t.runner.model || payload.Stream || !t.validTools(payload.Tools) || !reflect.DeepEqual(payload.Messages, expected) {
		return reject()
	}
	if !t.sent.CompareAndSwap(false, true) {
		t.err = errModelOutcomeUnknown
		return reject()
	}
	if t.toolsEnabled {
		body := map[string]any{"model": t.runner.model, "messages": t.messages, "max_tokens": t.runner.maxTokens,
			"tools": kagentSumTools(), "tool_choice": "auto", "parallel_tool_calls": false}
		raw, err := t.runner.gatewayResponse(req.Context(), body, t.traceID, t.taskKey)
		if err != nil {
			t.err = err
			return reject()
		}
		response, err := t.acceptToolResponse(raw)
		if err != nil {
			t.err = err
			return reject()
		}
		return &http.Response{StatusCode: http.StatusOK, Header: http.Header{"Content-Type": []string{"application/json"}}, Body: io.NopCloser(bytes.NewReader(response)), Request: req}, nil
	}
	t.answer, t.err = t.runner.gatewayCompletion(req.Context(), t.messages, t.traceID, t.taskKey)
	if t.err != nil {
		return reject()
	}
	response, _ := json.Marshal(map[string]any{"id": "eap-admitted", "object": "chat.completion", "model": t.runner.model,
		"choices": []any{map[string]any{"index": 0, "finish_reason": "stop", "message": map[string]any{"role": "assistant", "content": t.answer}}}})
	return &http.Response{StatusCode: http.StatusOK, Header: http.Header{"Content-Type": []string{"application/json"}}, Body: io.NopCloser(bytes.NewReader(response)), Request: req}, nil
}
