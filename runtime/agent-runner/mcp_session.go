package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
)

// This configuration is supplied by trusted image code, never task/model text.
type mcpLaunchConfig struct {
	Python, Relay, Server string
	Tools, Command        []string
	AllowHTTP             bool
	// Pinned by trusted host code, never inferred from a process's stderr.
	Observer string
}

type mcpLease struct {
	InstanceKey string `json:"instanceKey"`
	AgentID     string `json:"agentId"`
	ActorUID    string `json:"actorUid"`
	Token       string `json:"token"`
	ExpiresAt   int64  `json:"expiresAt"`
	Sequence    int64  `json:"sequence"`
}

// Deliberately no String/JSON logging of lease or child process arguments.
func (r *runner) mcpLeaseSnapshot() (mcpLease, bool) {
	r.mu.Lock()
	lease := mcpLease{r.runtimeKey, r.agentID, r.credentialUID, r.runtimeToken, r.credentialExpiry.Unix(), r.credentialSerial}
	id := r.identity
	r.mu.Unlock()
	if id == nil || lease.Sequence < 1 || lease.ExpiresAt <= time.Now().Unix() ||
		!regexp.MustCompile(`^eap_rt_[A-Za-z0-9_-]{43}$`).MatchString(lease.Token) {
		return mcpLease{}, false
	}
	key, uid, err := id.fields()
	return lease, err == nil && key == lease.InstanceKey && uid == lease.ActorUID
}

type mcpExportSummary struct {
	Submitted           int  `json:"submitted"`
	Accepted            int  `json:"accepted"`
	Rejected            int  `json:"rejected"`
	Unknown             int  `json:"unknown"`
	Unavailable         int  `json:"unavailable"`
	QueueDropped        int  `json:"queueDropped"`
	ShutdownDropped     int  `json:"shutdownDropped"`
	InvalidLeaseUpdates int  `json:"invalidLeaseUpdates"`
	WorkerStopped       bool `json:"workerStopped"`
}

// Optional fixed diagnostics for the dedicated HTTP fixture. Older receipts stay valid.
// Nested timings overlap; their sum is not a task duration. No strings or arbitrary keys.
type mcpHTTPStartup map[string]int64

func (s mcpHTTPStartup) valid() bool {
	if s == nil {
		return true
	}
	keys := []string{"version", "observerImportMs", "identityReadyMs", "exporterStartMs",
		"serverSpawnMs", "serverReadyWaitMs", "observerBeforeReadyMs",
		"serverImportMs", "serverBindMs", "serverFqdnMs", "serverBeforeReadyMs"}
	if len(s) != len(keys) || s["version"] != 1 {
		return false
	}
	for _, key := range keys {
		v, ok := s[key]
		if !ok || v < 0 || v > 60000 {
			return false
		}
	}
	for _, key := range keys[1:6] {
		if s[key] > s["observerBeforeReadyMs"] {
			return false
		}
	}
	return s["serverFqdnMs"] <= s["serverBindMs"] &&
		s["serverBindMs"] <= s["serverBeforeReadyMs"] &&
		s["serverImportMs"] <= s["serverBeforeReadyMs"]
}

type mcpRelaySummary struct {
	Observer         string           `json:"observer"`
	Degraded         bool             `json:"degraded"`
	ObserverFailures int              `json:"observerFailures"`
	DroppedFrames    int              `json:"droppedFrames"`
	DeliveryFailures int              `json:"deliveryFailures"`
	DroppedEvents    int              `json:"droppedEvents"`
	ExportFailures   int              `json:"exportFailures"`
	ChildExitTimeout bool             `json:"childExitTimeout"`
	RuntimeExport    mcpExportSummary `json:"runtimeExport"`
	HTTPStartup      mcpHTTPStartup   `json:"httpStartup,omitempty"`
}

type mcpBoundedBuffer struct {
	data []byte
	over bool
}

func (b *mcpBoundedBuffer) Write(p []byte) (int, error) {
	if len(b.data)+len(p) > 8192 {
		b.over = true
	} else {
		b.data = append(b.data, p...)
	}
	return len(p), nil
}

type observedMCP struct {
	Stdin    io.WriteCloser
	Stdout   io.ReadCloser
	cmd      *exec.Cmd
	stderr   mcpBoundedBuffer
	owner    *runner
	mu       sync.Mutex
	writer   *os.File
	pinned   mcpLease
	serial   int64
	observer string
}

func (s *observedMCP) update(lease mcpLease, valid bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.writer == nil {
		return
	}
	invalidate := func() { _ = s.writer.Close(); s.writer = nil }
	if !valid || lease.InstanceKey != s.pinned.InstanceKey || lease.AgentID != s.pinned.AgentID || lease.ActorUID != s.pinned.ActorUID {
		invalidate()
		return
	}
	if lease.Sequence <= s.serial {
		return
	}
	raw, err := json.Marshal(lease)
	if err != nil || len(raw) > 2047 {
		invalidate()
		return
	}
	// Go's pipe deadline bounds delivery while identity install holds its lock.
	if s.writer.SetWriteDeadline(time.Now().Add(50*time.Millisecond)) != nil {
		invalidate()
		return
	}
	n, err := s.writer.Write(append(raw, '\n'))
	if err != nil || n != len(raw)+1 {
		invalidate()
		return
	}
	s.serial = lease.Sequence
}

func (r *runner) refreshMCPLeases() {
	r.mcpMu.Lock()
	defer r.mcpMu.Unlock()
	lease, valid := r.mcpLeaseSnapshot()
	for s := range r.mcpSessions {
		s.update(lease, valid)
	}
}

func (r *runner) startObservedMCP(ctx context.Context, cfg mcpLaunchConfig, traceID, parentID string) (*observedMCP, error) {
	failed := errors.New("MCP observer unavailable")
	observer := cfg.Observer
	if observer == "" {
		observer = "eap-mcp-stdio-observer"
	}
	if observer != "eap-mcp-stdio-observer" && observer != "eap-mcp-http-observer" {
		return nil, failed
	}
	if ctx.Err() != nil || !filepath.IsAbs(cfg.Python) || !filepath.IsAbs(cfg.Relay) || len(cfg.Command) == 0 ||
		!filepath.IsAbs(cfg.Command[0]) || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(traceID) || strings.Trim(traceID, "0") == "" ||
		!regexp.MustCompile(`^[a-f0-9]{16}$`).MatchString(parentID) || strings.Trim(parentID, "0") == "" {
		return nil, failed
	}
	r.mcpMu.Lock()
	defer r.mcpMu.Unlock()
	lease, valid := r.mcpLeaseSnapshot()
	if !valid || len(r.mcpSessions) >= 16 {
		return nil, failed
	}
	read, write, err := os.Pipe()
	if err != nil {
		return nil, failed
	}
	defer read.Close()
	args := []string{cfg.Relay, "--server", cfg.Server, "--runtime-url", r.gateway,
		"--runtime-credentials-fd", "3", "--trace-id", traceID, "--parent-span-id", parentID}
	for _, tool := range cfg.Tools {
		args = append(args, "--tool", tool)
	}
	if cfg.AllowHTTP {
		args = append(args, "--allow-insecure-runtime-http")
	}
	args = append(append(args, "--"), cfg.Command...)
	cmd := exec.CommandContext(ctx, cfg.Python, args...)
	cmd.ExtraFiles = []*os.File{read}
	cmd.Env = []string{}
	for _, entry := range os.Environ() {
		if !strings.HasPrefix(entry, "EAP_") {
			cmd.Env = append(cmd.Env, entry)
		}
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error { return syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL) }
	cmd.WaitDelay = 2 * time.Second
	s := &observedMCP{cmd: cmd, owner: r, writer: write, observer: observer,
		pinned: mcpLease{InstanceKey: lease.InstanceKey, AgentID: lease.AgentID, ActorUID: lease.ActorUID}}
	cmd.Stderr = &s.stderr
	s.update(lease, true)
	if s.writer == nil {
		return nil, failed
	}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		write.Close()
		return nil, failed
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		stdin.Close()
		write.Close()
		return nil, failed
	}
	s.Stdin, s.Stdout = stdin, stdout
	if cmd.Start() != nil {
		stdin.Close()
		stdout.Close()
		write.Close()
		return nil, failed
	}
	if r.mcpSessions == nil {
		r.mcpSessions = map[*observedMCP]struct{}{}
	}
	r.mcpSessions[s] = struct{}{}
	return s, nil
}

// Call exactly once after draining Stdout. Never persist raw stderr on failure.
func (s *observedMCP) finish() (mcpRelaySummary, error) {
	err := s.cmd.Wait()
	s.owner.mcpMu.Lock()
	delete(s.owner.mcpSessions, s)
	s.owner.mcpMu.Unlock()
	s.mu.Lock()
	if s.writer != nil {
		s.writer.Close()
		s.writer = nil
	}
	s.mu.Unlock()
	var summary mcpRelaySummary
	if err != nil || s.stderr.over || json.Unmarshal(s.stderr.data, &summary) != nil ||
		summary.Observer != s.observer || !summary.HTTPStartup.valid() {
		return mcpRelaySummary{}, errors.New("MCP process or observation receipt unconfirmed")
	}
	return summary, nil
}
