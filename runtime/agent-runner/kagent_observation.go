//go:build kagent

package main

import (
	"errors"
	"os"
	"regexp"
	"sync"
	"time"

	"github.com/google/uuid"
	adkagent "google.golang.org/adk/v2/agent"
	"google.golang.org/adk/v2/plugin"
	"google.golang.org/adk/v2/tool"
	"google.golang.org/genai"
)

// One observer belongs to one ADK invocation. Names are registered from the
// actual, trusted agent tree, never inferred from PID, timestamps or model text.
// The bounded adapter supports sequential/parallel child calls, not transfers,
// recursive agent tools, arbitrary workflow nodes or resumed invocations.
type kagentObservation struct {
	mu                                 sync.Mutex
	runner                             *runner
	directory, trace, invocation, root string
	parents                            map[string]string
	active                             map[string]*kagentObservedRun
	runs                               []*kagentObservedRun
	sequence                           int
	broken                             bool
}

type kagentObservedRun struct {
	id, name, branch string
	returned         bool
	tools            map[string]string
}

type kagentExecutionEvent struct {
	EventID       string `json:"eventId"`
	Sequence      int    `json:"sequence"`
	Kind          string `json:"kind"`
	RunID         string `json:"runId"`
	TraceID       string `json:"traceId"`
	OccurredAt    string `json:"occurredAt"`
	ProcessID     int    `json:"processId"`
	DefinitionKey string `json:"definitionKey,omitempty"`
	ParentRunID   string `json:"parentRunId,omitempty"`
	ToolCallID    string `json:"toolCallId,omitempty"`
	Outcome       string `json:"outcome,omitempty"`
}

var kagentDefinition = regexp.MustCompile(`^[A-Za-z0-9_.:/@+-]{1,64}$`)

func kagentAgentTree(root adkagent.Agent) (map[string]string, bool) {
	parents := map[string]string{}
	var visit func(adkagent.Agent, string) bool
	visit = func(a adkagent.Agent, parent string) bool {
		if a == nil || len(parents) >= 16 || !kagentDefinition.MatchString(a.Name()) {
			return false
		}
		if _, exists := parents[a.Name()]; exists {
			return false
		}
		parents[a.Name()] = parent
		for _, child := range a.SubAgents() {
			if !visit(child, a.Name()) {
				return false
			}
		}
		return true
	}
	return parents, visit(root, "")
}

func (r *runner) newKagentObservation(root adkagent.Agent, trace string) *kagentObservation {
	parents, ok := kagentAgentTree(root)
	if !ok || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(trace) || trace == "00000000000000000000000000000000" {
		r.kagentObservationState("CAPTURE_UNAVAILABLE")
		return nil
	}
	r.observationMu.Lock()
	defer r.observationMu.Unlock()
	binding, ok := r.kagentObservationIdentity()
	if !ok {
		r.kagentObservationState("IDENTITY_UNAVAILABLE")
		return nil
	}
	directory, err := r.createKagentJournal(binding)
	if err != nil {
		r.kagentObservationState("STORAGE_UNAVAILABLE")
		return nil
	}
	return &kagentObservation{runner: r, directory: directory, trace: trace, root: root.Name(), parents: parents, active: map[string]*kagentObservedRun{}}
}

// Must hold o.mu. Each event reaches a private, fsynced immutable file before
// its callback returns. Credentials, prompts, arguments and results never enter it.
func (o *kagentObservation) emit(event kagentExecutionEvent) {
	if o.broken {
		return
	}
	if o.sequence >= 64 {
		o.fail()
		return
	}
	event.EventID, event.Sequence = uuid.NewString(), o.sequence+1
	event.TraceID, event.OccurredAt, event.ProcessID = o.trace, time.Now().UTC().Format(time.RFC3339Nano), os.Getpid()
	o.runner.observationMu.Lock()
	err := writeKagentImmutable(o.directory, kagentEventName(event.Sequence), event)
	o.runner.observationMu.Unlock()
	if err != nil {
		o.fail()
		return
	}
	o.sequence++
}

func (o *kagentObservation) fail() {
	o.broken = true
	o.runner.kagentObservationState("CAPTURE_INCOMPLETE")
	// A marker prevents the background sender from reporting a complete capture.
	_ = writeKagentImmutable(o.directory, "incomplete.json", map[string]bool{"incomplete": true})
}

func (o *kagentObservation) current(ctx adkagent.Context) *kagentObservedRun {
	if o.invocation != ctx.InvocationID() {
		return nil
	}
	a := o.active[ctx.AgentName()]
	if a == nil || a.branch != ctx.Branch() {
		return nil
	}
	return a
}

func (o *kagentObservation) plugins() []*plugin.Plugin {
	if o == nil {
		return nil
	}
	p, _ := plugin.New(plugin.Config{Name: "eap_execution_observer_v1",
		BeforeAgentCallback: func(ctx adkagent.Context) (*genai.Content, error) {
			o.mu.Lock()
			defer o.mu.Unlock()
			if o.broken {
				return nil, nil
			}
			name := ctx.AgentName()
			parent, registered := o.parents[name]
			if !registered || o.active[name] != nil || ctx.InvocationID() == "" || o.invocation != "" && o.invocation != ctx.InvocationID() {
				o.fail()
				return nil, nil
			}
			o.invocation = ctx.InvocationID()
			parentID := ""
			if parent != "" {
				if o.active[parent] == nil {
					o.fail()
					return nil, nil
				}
				parentID = o.active[parent].id
			}
			run := &kagentObservedRun{id: uuid.NewString(), name: name, branch: ctx.Branch(), tools: map[string]string{}}
			o.active[name] = run
			o.runs = append(o.runs, run)
			o.emit(kagentExecutionEvent{Kind: "RUN_STARTED", RunID: run.id, DefinitionKey: name, ParentRunID: parentID})
			return nil, nil
		},
		AfterAgentCallback: func(ctx adkagent.Context) (*genai.Content, error) {
			o.mu.Lock()
			defer o.mu.Unlock()
			if run := o.current(ctx); run != nil {
				run.returned = true
				delete(o.active, run.name)
			}
			return nil, nil
		},
		BeforeToolCallback: func(ctx adkagent.Context, t tool.Tool, _ map[string]any) (map[string]any, error) {
			o.mu.Lock()
			defer o.mu.Unlock()
			if o.broken {
				return nil, nil
			}
			run := o.current(ctx)
			if run == nil || !kagentDefinition.MatchString(t.Name()) || ctx.FunctionCallID() == "" || run.tools[ctx.FunctionCallID()] != "" {
				o.fail()
				return nil, nil
			}
			id := uuid.NewString()
			run.tools[ctx.FunctionCallID()] = id
			o.emit(kagentExecutionEvent{Kind: "TOOL_STARTED", RunID: run.id, ToolCallID: id, DefinitionKey: t.Name()})
			return nil, nil
		},
		AfterToolCallback: func(ctx adkagent.Context, _ tool.Tool, _, _ map[string]any, toolErr error) (map[string]any, error) {
			o.mu.Lock()
			defer o.mu.Unlock()
			if run := o.current(ctx); run != nil && toolErr == nil {
				if id := run.tools[ctx.FunctionCallID()]; id != "" {
					o.emit(kagentExecutionEvent{Kind: "TOOL_FINISHED", RunID: run.id, ToolCallID: id, Outcome: "SUCCEEDED"})
					delete(run.tools, ctx.FunctionCallID())
				}
			}
			// An error may mean an uncertain external receipt, so leave a gap.
			return nil, nil
		},
	})
	return []*plugin.Plugin{p}
}

// Only the caller's verified result closes the root. ADK's after callback alone
// is not evidence of business success. UNKNOWN deliberately has no finish event.
func (o *kagentObservation) finish(err error) {
	if o == nil {
		return
	}
	o.mu.Lock()
	for i := len(o.runs) - 1; i >= 0; i-- {
		run := o.runs[i]
		outcome := ""
		if err == nil && (run.returned || run.name == o.root) {
			outcome = "SUCCEEDED"
		}
		if err != nil && !errors.Is(err, errModelOutcomeUnknown) && run.name == o.root {
			outcome = "FAILED"
		}
		if outcome != "" {
			o.emit(kagentExecutionEvent{Kind: "RUN_FINISHED", RunID: run.id, Outcome: outcome})
		}
	}
	broken := o.broken
	o.mu.Unlock()
	// Delivery is decoupled: the original business result never depends on HTTP.
	if !broken {
		o.runner.kagentObservationState("PENDING")
	}
	select {
	case o.runner.observationWake <- struct{}{}:
	default:
	}
}
