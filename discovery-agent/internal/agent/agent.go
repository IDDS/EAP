package agent

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/matrix-eap/discovery-agent/internal/autoattach"
	"github.com/matrix-eap/discovery-agent/internal/config"
	"github.com/matrix-eap/discovery-agent/internal/discovery"
	"github.com/matrix-eap/discovery-agent/internal/hostobserver"
	"github.com/matrix-eap/discovery-agent/internal/protocol"
	queuepkg "github.com/matrix-eap/discovery-agent/internal/queue"
	"github.com/matrix-eap/discovery-agent/internal/state"
)

const Version = "1.5.1"

type autoAttachReconcileStatus struct {
	CheckedAt                time.Time `json:"checked_at"`
	ContentCaptureEnabled    bool      `json:"content_capture_enabled"`
	CentralAutoAttachEnabled bool      `json:"central_auto_attach_enabled"`
	TargetsConfigured        int       `json:"targets_configured"`
	TargetsApplied           int       `json:"targets_applied"`
	TargetsAlreadyAttached   int       `json:"targets_already_attached"`
	Error                    string    `json:"error,omitempty"`
}

type Runtime struct {
	configPath string
	cfg        config.Config
	store      *state.Store
	state      state.State
	client     *protocol.Client
	queue      *queuepkg.Queue
}

func Open(configPath string) (*Runtime, error) {
	cfg, err := config.Load(configPath)
	if err != nil {
		return nil, err
	}
	_, _, _, _, fingerprint := discovery.HostMetadata()
	store := state.NewStore(cfg.StateDir)
	value, err := store.LoadOrCreate(fingerprint)
	if err != nil {
		return nil, err
	}
	client, err := protocol.NewClient(cfg.ServerURL, value.Credential, cfg.ProxyURL)
	if err != nil {
		return nil, err
	}
	queueDays, queueBytes := value.Policy.OfflineQueueDays, value.Policy.OfflineQueueMaxBytes
	if queueDays <= 0 {
		queueDays = 7
	}
	if queueBytes <= 0 {
		queueBytes = 100 * 1024 * 1024
	}
	queue, err := queuepkg.Open(cfg.StateDir, queueBytes, queueDays)
	if err != nil {
		return nil, err
	}
	return &Runtime{configPath: configPath, cfg: cfg, store: store, state: value, client: client, queue: queue}, nil
}

func (r *Runtime) Enroll(ctx context.Context, token string) error {
	if r.state.Credential != "" {
		return errors.New("agent is already enrolled")
	}
	token = firstNonBlank(token, os.Getenv("EAP_DISCOVERY_ENROLLMENT_TOKEN"), r.cfg.EnrollmentToken)
	if token == "" {
		return errors.New("enrollment token is required")
	}
	host, account, osName, arch, fingerprint := discovery.HostMetadata()
	response, err := r.client.Enroll(ctx, protocol.EnrollRequest{
		EnrollmentToken: token, InstallationID: r.state.InstallationID, MachineFingerprint: fingerprint,
		HostName: host, AccountName: account, OperatingSystem: osName, Architecture: arch,
		AgentVersion: Version, Capabilities: capabilities(),
	})
	if err != nil {
		return err
	}
	r.state.EndpointKey = response.EndpointKey
	r.state.Credential = response.Credential
	r.state.CredentialHint = response.CredentialHint
	r.state.MachineFingerprint = fingerprint
	r.state.Policy = response.Policy
	r.client.WithCredential(response.Credential)
	if err := r.store.Save(r.state); err != nil {
		return err
	}
	if err := r.store.SaveContentIdentity(state.ContentIdentity{
		EndpointKey: response.EndpointKey, Credential: response.ContentCredential,
		CredentialHint: response.ContentCredentialHint,
	}); err != nil {
		return err
	}
	if r.cfg.EnrollmentToken != "" {
		r.cfg.EnrollmentToken = ""
		_ = config.Save(r.configPath, r.cfg)
	}
	slog.Info("discovery agent enrolled", "endpoint_key", response.EndpointKey, "policy", response.Policy.PolicyVersion)
	return nil
}

func (r *Runtime) Scan(ctx context.Context, dryRun bool, full bool) (discovery.Result, error) {
	result := discovery.Collect(r.cfg.Scan, r.state.Policy.AgentSignatures)
	if dryRun {
		data, _ := json.MarshalIndent(result, "", "  ")
		fmt.Println(string(data))
		return result, nil
	}
	if r.state.Credential == "" {
		return result, errors.New("agent is not enrolled")
	}
	snapshotType := "INCREMENTAL"
	if full {
		snapshotType = "FULL"
	}
	batch := protocol.ObservationBatch{
		BatchID: randomID(), ScanID: randomID(), SnapshotType: snapshotType,
		ObservedAt: result.FinishedAt, Observations: result.Observations,
	}
	if len(batch.Observations) > 0 || full {
		if _, err := r.client.Upload(ctx, batch); err != nil {
			r.state.LastError = err.Error()
			_ = r.store.Save(r.state)
			if queueErr := r.queue.Put(batch.BatchID, batch); queueErr != nil {
				return result, fmt.Errorf("upload: %v; queue: %w", err, queueErr)
			}
			return result, err
		}
	}
	r.state.LastScanAt = result.FinishedAt
	if full {
		r.state.LastFullSnapshotAt = result.FinishedAt
	}
	r.state.LastError = ""
	return result, r.store.Save(r.state)
}

func (r *Runtime) Heartbeat(ctx context.Context) error {
	if r.state.Credential == "" {
		return errors.New("agent is not enrolled")
	}
	stats := r.queue.Stats()
	autoAttachError := r.autoAttachError()
	if autoAttachError != "" {
		r.state.LastError = autoAttachError
	}
	_, _, _, _, fingerprint := discovery.HostMetadata()
	var lastScanAt *time.Time
	if !r.state.LastScanAt.IsZero() {
		value := r.state.LastScanAt
		lastScanAt = &value
	}
	response, err := r.client.Heartbeat(ctx, protocol.HeartbeatRequest{
		InstallationID: r.state.InstallationID, MachineFingerprint: fingerprint, AgentVersion: Version,
		PolicyVersion: r.state.Policy.PolicyVersion, RulesVersion: r.state.Policy.RulesVersion,
		QueueDepth: stats.Depth, QueueBytes: stats.Bytes, LastScanAt: lastScanAt,
		LastError: r.state.LastError, Capabilities: capabilities(),
	})
	if err != nil {
		r.state.LastError = err.Error()
		_ = r.store.Save(r.state)
		return err
	}
	r.state.Policy = response.Policy
	r.state.LastHeartbeatAt = time.Now()
	r.state.LastError = autoAttachError
	return r.store.Save(r.state)
}

func (r *Runtime) Flush(ctx context.Context) error {
	files, err := r.queue.Files()
	if err != nil {
		return err
	}
	for _, path := range files {
		var batch protocol.ObservationBatch
		if err := r.queue.Read(path, &batch); err != nil {
			slog.Error("discarding unreadable queue item", "error", err)
			_ = r.queue.Delete(path)
			continue
		}
		if _, err := r.client.Upload(ctx, batch); err != nil {
			return err
		}
		if err := r.queue.Delete(path); err != nil {
			return err
		}
	}
	return nil
}

func (r *Runtime) Run(ctx context.Context) error {
	if r.state.Credential == "" {
		if err := r.Enroll(ctx, ""); err != nil {
			return err
		}
	}
	ctx, cancel := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer cancel()
	_ = r.Flush(ctx)
	_ = r.Heartbeat(ctx)
	if _, err := r.Scan(ctx, false, true); err != nil {
		slog.Warn("initial scan queued", "error", err)
	}

	heartbeatFailures, scanFailures := 0, 0
	heartbeatTimer := time.NewTimer(jittered(durationSeconds(r.state.Policy.HeartbeatIntervalSeconds, 60)))
	scanTimer := time.NewTimer(jittered(durationSeconds(r.state.Policy.ScanIntervalSeconds, 300)))
	defer heartbeatTimer.Stop()
	defer scanTimer.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-heartbeatTimer.C:
			if err := r.Heartbeat(ctx); err != nil {
				heartbeatFailures++
				slog.Warn("heartbeat failed", "error", err)
			} else {
				heartbeatFailures = 0
				_ = r.Flush(ctx)
			}
			heartbeatTimer.Reset(backoff(durationSeconds(r.state.Policy.HeartbeatIntervalSeconds, 60), heartbeatFailures))
		case <-scanTimer.C:
			result, err := r.Scan(ctx, false, true)
			if err != nil {
				scanFailures++
				slog.Warn("scan upload failed", "observations", len(result.Observations), "error", err)
			} else {
				scanFailures = 0
				slog.Info("scan complete", "observations", len(result.Observations), "full", true)
			}
			scanTimer.Reset(backoff(durationSeconds(r.state.Policy.ScanIntervalSeconds, 300), scanFailures))
		}
	}
}

func (r *Runtime) Diagnose(ctx context.Context) error {
	result := map[string]any{"version": Version, "goos": runtime.GOOS, "goarch": runtime.GOARCH, "state_dir": r.cfg.StateDir, "server_url": r.cfg.ServerURL, "enrolled": r.state.Credential != ""}
	if r.state.Credential != "" {
		policy, err := r.client.Policy(ctx)
		result["server_reachable"] = err == nil
		if err != nil {
			result["server_error"] = err.Error()
		} else {
			result["policy_version"] = policy.PolicyVersion
		}
	}
	data, _ := json.MarshalIndent(result, "", "  ")
	fmt.Println(string(data))
	return nil
}

func (r *Runtime) Attach(ctx context.Context, options autoattach.Options) error {
	plan, err := autoattach.BuildPlan(ctx, options)
	if err != nil {
		return err
	}
	data, _ := json.MarshalIndent(plan, "", "  ")
	fmt.Println(string(data))
	if !options.Apply {
		return nil
	}
	if r.state.Credential == "" {
		return errors.New("agent must be enrolled before automatic attachment")
	}
	policy, err := r.client.Policy(ctx)
	if err != nil {
		return fmt.Errorf("load central auto-attach policy: %w", err)
	}
	if !policy.ContentCaptureEnabled {
		return errors.New("content capture has not completed central authorization")
	}
	if !policy.ContentAutoAttachEnabled {
		return errors.New("central policy has not enabled automatic workload attachment")
	}
	allowed := false
	for _, profile := range policy.ContentAutoAttachProfiles {
		if strings.EqualFold(profile, plan.Profile) {
			allowed = true
			break
		}
	}
	if !allowed {
		return fmt.Errorf("auto-attach profile %q is not allowed by central policy", plan.Profile)
	}
	return autoattach.Apply(ctx, plan)
}

func (r *Runtime) ReconcileAttachments(ctx context.Context) error {
	status := autoAttachReconcileStatus{CheckedAt: time.Now().UTC()}
	for _, target := range r.cfg.AutoAttach {
		if target.Enabled {
			status.TargetsConfigured++
		}
	}
	writeStatus := func(err error) error {
		if err != nil {
			status.Error = err.Error()
		}
		if writeErr := r.writeAutoAttachStatus(status); writeErr != nil {
			if err != nil {
				return errors.Join(err, writeErr)
			}
			return writeErr
		}
		data, _ := json.MarshalIndent(status, "", "  ")
		fmt.Println(string(data))
		return err
	}
	if os.Geteuid() != 0 {
		return writeStatus(errors.New("automatic attachment reconciliation must run as root"))
	}
	if r.state.Credential == "" {
		return writeStatus(errors.New("agent must be enrolled before attachment reconciliation"))
	}
	policy, err := r.client.Policy(ctx)
	if err != nil {
		return writeStatus(fmt.Errorf("load central auto-attach policy: %w", err))
	}
	status.ContentCaptureEnabled = policy.ContentCaptureEnabled
	status.CentralAutoAttachEnabled = policy.ContentAutoAttachEnabled
	if !policy.ContentCaptureEnabled || !policy.ContentAutoAttachEnabled || status.TargetsConfigured == 0 {
		return writeStatus(nil)
	}
	var failures []error
	for _, target := range r.cfg.AutoAttach {
		if !target.Enabled {
			continue
		}
		plan, buildErr := autoattach.BuildPlan(ctx, autoattach.Options{
			Target: target.Target, Profile: target.Profile, AgentID: target.AgentID,
			UsagePoolKey: target.UsagePoolKey, Provider: target.Provider, CollectorURL: target.CollectorURL,
			InstrumentationTokenFile: target.InstrumentationTokenFile, ProxyInstance: target.ProxyInstance, Apply: true,
		})
		if buildErr != nil {
			failures = append(failures, fmt.Errorf("target %s: %w", target.Target, buildErr))
			continue
		}
		if !profileAllowed(policy.ContentAutoAttachProfiles, plan.Profile) {
			failures = append(failures, fmt.Errorf("target %s: profile %q is not allowed by central policy", target.Target, plan.Profile))
			continue
		}
		if applyErr := autoattach.Apply(ctx, plan); applyErr != nil {
			failures = append(failures, fmt.Errorf("target %s: %w", target.Target, applyErr))
			continue
		}
		if plan.AlreadyAttached {
			status.TargetsAlreadyAttached++
		} else {
			status.TargetsApplied++
		}
	}
	return writeStatus(errors.Join(failures...))
}

func (r *Runtime) ObserveHost(ctx context.Context, dryRun bool) error {
	if r.cfg.HostObserver.Mode == "off" {
		data, _ := json.MarshalIndent(map[string]any{"mode": "off", "observations": 0}, "", "  ")
		fmt.Println(string(data))
		return nil
	}
	result := hostobserver.Collect(ctx, r.cfg.HostObserver.MaxObservations)
	if dryRun {
		data, _ := json.MarshalIndent(result, "", "  ")
		fmt.Println(string(data))
		return nil
	}
	if r.state.Credential == "" {
		return errors.New("agent must be enrolled before host observation upload")
	}
	if len(result.Observations) == 0 {
		data, _ := json.MarshalIndent(map[string]any{
			"sensorType": result.SensorType, "observations": 0, "warnings": result.Warnings,
		}, "", "  ")
		fmt.Println(string(data))
		return nil
	}
	response, err := r.client.UploadHostObservations(ctx, protocol.HostObservationRequest{
		SensorType: result.SensorType, CollectorVersion: "eap-discovery-agent/" + Version,
		Observations: result.Observations,
	})
	if err != nil {
		return err
	}
	data, _ := json.MarshalIndent(map[string]any{
		"sensorType": result.SensorType, "observations": len(result.Observations),
		"warnings": result.Warnings, "server": response,
	}, "", "  ")
	fmt.Println(string(data))
	return nil
}

func (r *Runtime) Status() error {
	stats := r.queue.Stats()
	result := map[string]any{
		"version": Version, "installation_id": r.state.InstallationID, "endpoint_key": r.state.EndpointKey,
		"credential_hint": r.state.CredentialHint, "enrolled": r.state.Credential != "",
		"policy_version": r.state.Policy.PolicyVersion, "rules_version": r.state.Policy.RulesVersion,
		"content_capture_enabled":      r.state.Policy.ContentCaptureEnabled,
		"content_auto_attach_enabled":  r.state.Policy.ContentAutoAttachEnabled,
		"content_auto_attach_profiles": r.state.Policy.ContentAutoAttachProfiles, "last_scan_at": r.state.LastScanAt,
		"auto_attach_targets_configured": len(r.cfg.AutoAttach),
		"host_observer_mode":             r.cfg.HostObserver.Mode,
		"ebpf_observer_mode":             r.cfg.EbpfObserver.Mode,
		"last_heartbeat_at":              r.state.LastHeartbeatAt, "last_error": r.state.LastError,
		"queue_depth": stats.Depth, "queue_bytes": stats.Bytes,
	}
	if status, err := r.loadAutoAttachStatus(); err == nil {
		result["auto_attach_reconcile"] = status
	}
	data, _ := json.MarshalIndent(result, "", "  ")
	fmt.Println(string(data))
	return nil
}

func profileAllowed(values []string, profile string) bool {
	for _, value := range values {
		if strings.EqualFold(value, profile) {
			return true
		}
	}
	return false
}

func (r *Runtime) autoAttachStatusPath() string {
	return filepath.Join(r.cfg.StateDir, "auto-attach-status.json")
}

func (r *Runtime) writeAutoAttachStatus(status autoAttachReconcileStatus) error {
	data, err := json.MarshalIndent(status, "", "  ")
	if err != nil {
		return err
	}
	path := r.autoAttachStatusPath()
	temp := path + ".tmp"
	if err := os.WriteFile(temp, append(data, '\n'), 0o644); err != nil {
		return err
	}
	return os.Rename(temp, path)
}

func (r *Runtime) loadAutoAttachStatus() (autoAttachReconcileStatus, error) {
	data, err := os.ReadFile(r.autoAttachStatusPath())
	if err != nil {
		return autoAttachReconcileStatus{}, err
	}
	var status autoAttachReconcileStatus
	if err := json.Unmarshal(data, &status); err != nil {
		return autoAttachReconcileStatus{}, err
	}
	return status, nil
}

func (r *Runtime) autoAttachError() string {
	status, err := r.loadAutoAttachStatus()
	if err != nil || strings.TrimSpace(status.Error) == "" {
		return ""
	}
	return "auto-attach: " + status.Error
}

func (r *Runtime) Uninstall(ctx context.Context) error {
	if r.state.Credential != "" {
		_ = r.client.Uninstall(ctx)
	}
	return os.RemoveAll(r.cfg.StateDir)
}

func capabilities() []string {
	values := []string{"PROCESS", "DOCKER", "PORTS", "FILES", "SKILLS", "PLUGINS", "MCP", "A2A", "CONTENT_PROXY", "AUTO_ATTACH", "HOST_OBSERVER", "PROCESS_NETWORK_SCAN"}
	if runtime.GOOS == "linux" {
		values = append(values, "EBPF")
	}
	return values
}
func randomID() string {
	value := make([]byte, 16)
	_, _ = rand.Read(value)
	return hex.EncodeToString(value)
}
func durationSeconds(value, fallback int) time.Duration {
	if value <= 0 {
		value = fallback
	}
	return time.Duration(value) * time.Second
}
func jittered(value time.Duration) time.Duration {
	if value <= 0 {
		return time.Second
	}
	spread := value / 10
	if spread <= 0 {
		return value
	}
	n, err := rand.Int(rand.Reader, new(big.Int).SetInt64(int64(spread*2)))
	if err != nil {
		return value
	}
	return value - spread + time.Duration(n.Int64())
}
func backoff(base time.Duration, failures int) time.Duration {
	if failures <= 0 {
		return jittered(base)
	}
	if failures > 6 {
		failures = 6
	}
	value := base * time.Duration(1<<failures)
	if value > 30*time.Minute {
		value = 30 * time.Minute
	}
	return jittered(value)
}
func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
