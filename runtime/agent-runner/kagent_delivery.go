//go:build kagent

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"github.com/google/uuid"
)

type kagentJournalBinding struct {
	RuntimeKey string `json:"runtimeKey"`
	ActorUID   string `json:"actorUid"`
	Epoch      string `json:"identityEpoch"`
}

func (r *runner) kagentObservationIdentity() (kagentJournalBinding, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	b := kagentJournalBinding{r.runtimeKey, r.credentialUID, r.credentialEpoch}
	if r.identity == nil || r.runtimeToken == "" || !canonicalUUID(b.ActorUID) || !canonicalUUID(b.Epoch) || !time.Now().Before(r.credentialExpiry) {
		return b, false
	}
	key, uid, err := r.identity.fields()
	return b, err == nil && key == b.RuntimeKey && uid == b.ActorUID
}

func (r *runner) kagentObservationState(state string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.engineHealth = map[string]any{"state": state, "observedAt": time.Now().UTC().Format(time.RFC3339Nano)}
}

func kagentDeliveryState(r *runner) map[string]any {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[string]any{"protocol": "EAP_KAGENT_EXECUTION_V1", "state": "NOT_RUN", "maxEventsPerJournal": 64, "maxJournals": 256,
		"pollIntervalSeconds": 30, "maxAttemptsPerJournal": 6, "maxReportSnapshots": 16, "maxReportAttempts": 3, "requiresOriginalIdentity": true}
	for k, v := range r.engineHealth {
		out[k] = v
	}
	return out
}

func privateKagentDirectory(path string) error {
	s, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !s.IsDir() || s.Mode().Perm() != 0700 {
		return errors.New("private directory required")
	}
	return nil
}

func kagentEventName(n int) string { return fmt.Sprintf("event-%02d.json", n) }

func writeKagentImmutable(dir, name string, value any) error {
	if err := privateKagentDirectory(dir); err != nil {
		return err
	}
	b, err := json.Marshal(value)
	if err != nil || len(b) > 32768 {
		return errors.New("journal encoding failed")
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return err
	}
	defer root.Close()
	f, err := root.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	_, err = f.Write(b)
	if err == nil {
		err = f.Sync()
	}
	closed := f.Close()
	if err != nil {
		return err
	}
	if closed != nil {
		return closed
	}
	d, err := root.Open(".")
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

func readKagentJSON(dir, name string, out any) error {
	if err := privateKagentDirectory(dir); err != nil {
		return err
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return err
	}
	defer root.Close()
	s, err := root.Lstat(name)
	if err != nil {
		return err
	}
	if !s.Mode().IsRegular() || s.Mode().Perm() != 0600 || s.Size() > 32768 {
		return errors.New("invalid journal file")
	}
	f, err := root.Open(name)
	if err != nil {
		return err
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, 32769))
	if err != nil || len(b) > 32768 {
		return errors.New("journal limit")
	}
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	if dec.Decode(out) != nil || dec.Decode(new(any)) != io.EOF {
		return errors.New("invalid journal content")
	}
	return nil
}

func kagentJournalEntries(dir string, limit int) ([]os.DirEntry, error) {
	if err := privateKagentDirectory(dir); err != nil {
		return nil, err
	}
	f, err := os.Open(dir)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	items, err := f.ReadDir(limit + 1)
	if err != nil && err != io.EOF {
		return nil, err
	}
	if len(items) > limit {
		return nil, errors.New("journal directory limit")
	}
	return items, nil
}

func (r *runner) createKagentJournal(binding kagentJournalBinding) (string, error) {
	if r.toolDir == "" {
		return "", errors.New("storage unavailable")
	}
	dir := filepath.Join(r.toolDir, "kagent-events")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	entries, err := kagentJournalEntries(dir, 256)
	if err != nil || len(entries) >= 256 {
		return "", errors.New("journal capacity reached")
	}
	path := filepath.Join(dir, uuid.NewString())
	if err = os.Mkdir(path, 0700); err != nil {
		return "", err
	}
	if err = writeKagentImmutable(path, "binding.json", binding); err != nil {
		return "", err
	}
	// Persist newly created directory entries as well as their child files.
	for _, parent := range []string{dir, r.toolDir} {
		f, err := os.Open(parent)
		if err != nil {
			return "", err
		}
		err = f.Sync()
		closed := f.Close()
		if err != nil {
			return "", err
		}
		if closed != nil {
			return "", closed
		}
	}
	return path, nil
}

type kagentDeliveryReceipt struct {
	StreamKey  string `json:"streamKey"`
	Received   int    `json:"received"`
	Recorded   int    `json:"recorded"`
	Duplicates int    `json:"duplicates"`
}

func runKagentDelivery(ctx context.Context, r *runner) {
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			r.kagentDeliveryPass(ctx)
		case <-r.observationWake:
			r.kagentDeliveryPass(ctx)
		}
	}
}

func (r *runner) kagentDeliveryPass(ctx context.Context) {
	if ctx.Err() != nil {
		return
	}
	r.observationDeliveryMu.Lock()
	defer r.observationDeliveryMu.Unlock()
	binding, ok := r.kagentObservationIdentity()
	if !ok {
		r.kagentObservationState("IDENTITY_UNAVAILABLE")
		return
	}
	dir := filepath.Join(r.toolDir, "kagent-events")
	entries, err := kagentJournalEntries(dir, 256)
	if os.IsNotExist(err) {
		r.kagentObservationState("NO_JOURNALS")
		return
	}
	if err != nil {
		r.kagentObservationState("STORAGE_UNAVAILABLE")
		return
	}
	pass, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	processed, issues := 0, 0
	for _, entry := range entries {
		if processed >= 8 || pass.Err() != nil {
			break
		}
		if !entry.IsDir() || !canonicalUUID(entry.Name()) {
			issues++
			continue
		}
		sent, ok := r.deliverKagentJournal(pass, filepath.Join(dir, entry.Name()), binding)
		reported, reportOK := r.reportKagentJournal(pass, filepath.Join(dir, entry.Name()), binding)
		if sent || reported {
			processed++
		}
		if !reportOK || !ok {
			issues++
		}
	}
	state := "PASS_FINISHED"
	if issues > 0 {
		state = "PASS_WITH_ISSUES"
	}
	r.mu.Lock()
	r.engineHealth = map[string]any{"state": state, "processed": processed, "issues": issues, "observedAt": time.Now().UTC().Format(time.RFC3339Nano)}
	r.mu.Unlock()
}

func (r *runner) deliverKagentJournal(ctx context.Context, dir string, binding kagentJournalBinding) (sent bool, complete bool) {
	r.observationMu.Lock()
	locked := true
	defer func() {
		if locked {
			r.observationMu.Unlock()
		}
	}()
	snapshot, err := loadKagentSnapshot(dir, binding)
	if err != nil || snapshot.Halted || len(snapshot.Events) == 0 {
		return false, false
	}
	events, attempt, acked, pending, stream, captureComplete := snapshot.Events, snapshot.Attempt, snapshot.Acknowledged, snapshot.PendingBatch, snapshot.Stream, snapshot.CaptureComplete
	if acked == len(events) {
		return false, captureComplete
	}
	if attempt >= 6 {
		return false, false
	}
	count := len(events)
	if pending != 0 {
		count = pending
	}
	body, _ := json.Marshal(map[string]any{"schemaVersion": 1, "producerId": filepath.Base(dir), "framework": "kagent-go-adk", "frameworkVersion": "2.5.0", "events": events[:count]})
	if len(body) > 131072 {
		return false, false
	}
	current, ok := r.kagentObservationIdentity()
	if !ok || current != binding {
		return false, false
	}
	r.mu.Lock()
	// Recheck under the credential lock: renewal may not move an old journal to
	// another UID/epoch between projection verification and token selection.
	if r.runtimeKey != binding.RuntimeKey || r.credentialUID != binding.ActorUID || r.credentialEpoch != binding.Epoch || !time.Now().Before(r.credentialExpiry) {
		r.mu.Unlock()
		return false, false
	}
	token := r.runtimeToken
	r.mu.Unlock()
	u, err := url.Parse(r.gateway)
	if err != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path != "" && u.Path != "/" || u.Scheme != "http" && u.Scheme != "https" || r.client == nil {
		return false, false
	}
	u.Path = "/api/runtime-access/v1/execution-events"
	if writeKagentImmutable(dir, fmt.Sprintf("attempt-%02d.json", attempt+1), map[string]any{"bodySha256": digest(body), "count": count}) != nil {
		return false, false
	}
	outcome := "RETRYABLE_NETWORK"
	defer func() {
		// Serialization protects snapshots from seeing a partially written outcome.
		if !locked {
			r.observationMu.Lock()
			defer r.observationMu.Unlock()
		}
		_ = writeKagentImmutable(dir, fmt.Sprintf("result-%02d.json", attempt+1), kagentDeliveryOutcome{State: outcome})
	}()
	requestCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(requestCtx, http.MethodPost, u.String(), bytes.NewReader(body))
	if err != nil {
		return true, false
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-EAP-Runtime-Key", binding.RuntimeKey)
	req.Header.Set("X-EAP-Runtime-Token", token)
	client := *r.client
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	locked = false
	r.observationMu.Unlock()
	res, err := client.Do(req)
	if err != nil {
		return true, false
	}
	defer res.Body.Close()
	halt := func(state string) {
		outcome = state
		r.observationMu.Lock()
		defer r.observationMu.Unlock()
		_ = writeKagentImmutable(dir, "halted.json", struct {
			Halted bool   `json:"halted"`
			State  string `json:"state"`
		}{true, state})
	}
	if res.StatusCode != 200 {
		switch {
		case res.StatusCode == 401 || res.StatusCode == 403:
			halt("BLOCKED_AUTH")
		case res.StatusCode == 409:
			halt("BLOCKED_CONFLICT")
		case res.StatusCode == 429:
			outcome = "RETRYABLE_THROTTLED"
		case res.StatusCode >= 500 && res.StatusCode < 600:
			outcome = "RETRYABLE_SERVER"
		default:
			halt("BLOCKED_REQUEST")
		}
		return true, false
	}
	raw, err := io.ReadAll(io.LimitReader(res.Body, 32769))
	if err != nil {
		return true, false
	}
	if len(raw) > 32768 {
		halt("BLOCKED_RECEIPT")
		return true, false
	}
	var envelope struct {
		Success bool                  `json:"success"`
		Data    kagentDeliveryReceipt `json:"data"`
	}
	if json.Unmarshal(raw, &envelope) != nil || !envelope.Success || !validKagentReceipt(envelope.Data, count, stream) {
		halt("BLOCKED_RECEIPT")
		return true, false
	}
	r.observationMu.Lock()
	locked = true
	if writeKagentImmutable(dir, fmt.Sprintf("ack-%02d.json", attempt+1), envelope.Data) != nil {
		return true, false
	}
	outcome = "ACKNOWLEDGED"
	return true, captureComplete
}

type kagentJournalSnapshot struct {
	Events                              []kagentExecutionEvent
	Attempt, Acknowledged, PendingBatch int
	Stream, State                       string
	Halted, CaptureComplete             bool
}

type kagentDeliveryOutcome struct {
	State string `json:"state"`
}

func kagentDeliveryBlocked(state string) bool {
	switch state {
	case "BLOCKED_AUTH", "BLOCKED_CONFLICT", "BLOCKED_REQUEST", "BLOCKED_RECEIPT", "BLOCKED_TRANSPORT":
		return true
	}
	return false
}
func validKagentDeliveryOutcome(state string) bool {
	if kagentDeliveryBlocked(state) {
		return true
	}
	switch state {
	case "ACKNOWLEDGED", "RETRYABLE_NETWORK", "RETRYABLE_SERVER", "RETRYABLE_THROTTLED", "CANCELLED":
		return true
	}
	return false
}

// Caller holds observationMu. Shared by event delivery and independent status reporting.
func loadKagentSnapshot(dir string, binding kagentJournalBinding) (*kagentJournalSnapshot, error) {
	var saved kagentJournalBinding
	if readKagentJSON(dir, "binding.json", &saved) != nil || saved != binding {
		return nil, errors.New("invalid event journal")
	}
	entries, err := kagentJournalEntries(dir, 86)
	if err != nil {
		return nil, errors.New("invalid event journal")
	}
	names := map[string]bool{}
	captureComplete := true
	for _, entry := range entries {
		name := entry.Name()
		if name == "delivery-reports" && entry.IsDir() {
			continue
		}
		if !regexp.MustCompile(`^(binding|incomplete|halted|event-(0[1-9]|[1-5][0-9]|6[0-4])|attempt-0[1-6]|ack-0[1-6]|result-0[1-6])\.json$`).MatchString(name) {
			return nil, errors.New("invalid event journal")
		}
		names[name] = true
		if name == "incomplete.json" {
			captureComplete = false
		}
	}
	var events []kagentExecutionEvent
	for i := 1; i <= 64; i++ {
		if !names[kagentEventName(i)] {
			break
		}
		var event kagentExecutionEvent
		if readKagentJSON(dir, kagentEventName(i), &event) != nil || event.Sequence != i || !canonicalUUID(event.EventID) || !canonicalUUID(event.RunID) {
			return nil, errors.New("invalid event journal")
		}
		events = append(events, event)
	}
	for name := range names {
		if len(name) > 6 && name[:6] == "event-" && !containsKagentEvent(events, name) {
			return nil, errors.New("invalid event journal")
		}
	}
	attempt, acked, pending, stream := 0, 0, 0, ""
	state := "PENDING"
	for i := 1; i <= 6; i++ {
		a := fmt.Sprintf("attempt-%02d.json", i)
		ack := fmt.Sprintf("ack-%02d.json", i)
		if !names[a] {
			if names[ack] || names[fmt.Sprintf("result-%02d.json", i)] {
				return nil, errors.New("invalid event journal")
			}
			continue
		}
		if i != attempt+1 {
			return nil, errors.New("invalid event journal")
		}
		attempt = i
		state = "PENDING"
		if names[fmt.Sprintf("result-%02d.json", i)] {
			var outcome kagentDeliveryOutcome
			if readKagentJSON(dir, fmt.Sprintf("result-%02d.json", i), &outcome) != nil || !validKagentDeliveryOutcome(outcome.State) {
				return nil, errors.New("invalid event outcome")
			}
			state = outcome.State
			if kagentDeliveryBlocked(state) && names[fmt.Sprintf("attempt-%02d.json", i+1)] {
				return nil, errors.New("attempt after blocked outcome")
			}
		}
		var intent struct {
			BodySHA256 string `json:"bodySha256"`
			Count      int    `json:"count"`
		}
		if readKagentJSON(dir, a, &intent) != nil || intent.Count < 1 || intent.Count > len(events) {
			return nil, errors.New("invalid event journal")
		}
		original, _ := json.Marshal(map[string]any{"schemaVersion": 1, "producerId": filepath.Base(dir), "framework": "kagent-go-adk", "frameworkVersion": "2.5.0", "events": events[:intent.Count]})
		if digest(original) != intent.BodySHA256 {
			return nil, errors.New("invalid event journal")
		}
		if pending != 0 && intent.Count != pending {
			return nil, errors.New("invalid event journal")
		}
		if names[ack] {
			var receipt kagentDeliveryReceipt
			if readKagentJSON(dir, ack, &receipt) != nil || !validKagentReceipt(receipt, intent.Count, stream) || receipt.Received < acked {
				return nil, errors.New("invalid event journal")
			}
			acked, stream = receipt.Received, receipt.StreamKey
			pending = 0
		} else {
			pending = intent.Count
		}
	}

	halted := names["halted.json"]
	if halted {
		var reason struct {
			Halted bool   `json:"halted"`
			State  string `json:"state,omitempty"`
		}
		if readKagentJSON(dir, "halted.json", &reason) != nil || !reason.Halted || reason.State != "" && !kagentDeliveryBlocked(reason.State) {
			return nil, errors.New("invalid halt record")
		}
		state = reason.State
		if state == "" {
			state = "BLOCKED_TRANSPORT"
		}
	} else if kagentDeliveryBlocked(state) {
		halted = true
	} else if attempt >= 6 && acked < len(events) {
		state = "RETRY_EXHAUSTED"
	}
	if len(events) == 0 {
		state = "EMPTY"
	} else if acked == len(events) {
		state = "ACKNOWLEDGED"
	} else if state == "ACKNOWLEDGED" {
		state = "PENDING"
	}
	return &kagentJournalSnapshot{Events: events, Attempt: attempt, Acknowledged: acked, PendingBatch: pending, Stream: stream, State: state, Halted: halted, CaptureComplete: captureComplete}, nil
}

func containsKagentEvent(events []kagentExecutionEvent, name string) bool {
	for _, event := range events {
		if kagentEventName(event.Sequence) == name {
			return true
		}
	}
	return false
}

func validKagentReceipt(r kagentDeliveryReceipt, count int, stream string) bool {
	return count > 0 && count <= 64 && r.Received == count && r.Recorded >= 0 && r.Duplicates >= 0 && r.Recorded+r.Duplicates == count &&
		regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(r.StreamKey) && (stream == "" || stream == r.StreamKey)
}
