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
	"strconv"
	"strings"
	"time"
)

// Reports acknowledge only a snapshot. They never acknowledge event files.
type kagentDeliveryReport struct {
	SchemaVersion    int    `json:"schemaVersion"`
	ProducerID       string `json:"producerId"`
	Framework        string `json:"framework"`
	FrameworkVersion string `json:"frameworkVersion"`
	ReportSequence   int    `json:"reportSequence"`
	Total            int    `json:"total"`
	Acknowledged     int    `json:"acknowledged"`
	Pending          int    `json:"pending"`
	Attempts         int    `json:"attempts"`
	State            string `json:"state"`
}

type kagentReportReceipt struct {
	StreamKey      string `json:"streamKey"`
	ReportSequence int    `json:"reportSequence"`
	Duplicate      *bool  `json:"duplicate"`
}

type kagentReportHistory struct {
	Last      *kagentDeliveryReport
	Confirmed bool
	Attempts  int
	Stream    string
	Halted    bool
}

func kagentReportFromSnapshot(dir string, snapshot *kagentJournalSnapshot) kagentDeliveryReport {
	return kagentDeliveryReport{SchemaVersion: 1, ProducerID: filepath.Base(dir), Framework: "kagent-go-adk", FrameworkVersion: "2.5.0",
		Total: len(snapshot.Events), Acknowledged: snapshot.Acknowledged, Pending: len(snapshot.Events) - snapshot.Acknowledged, Attempts: snapshot.Attempt, State: snapshot.State}
}

func validKagentReport(report kagentDeliveryReport, producer string) bool {
	if report.SchemaVersion != 1 || report.ProducerID != producer || !canonicalUUID(producer) || report.Framework != "kagent-go-adk" || report.FrameworkVersion != "2.5.0" ||
		report.ReportSequence < 1 || report.ReportSequence > 16 || report.Total < 0 || report.Total > 64 || report.Acknowledged < 0 || report.Pending < 0 || report.Total != report.Acknowledged+report.Pending || report.Attempts < 0 || report.Attempts > 6 {
		return false
	}
	if report.Total == 0 {
		return report.State == "EMPTY"
	}
	if report.Pending == 0 {
		return report.State == "ACKNOWLEDGED"
	}
	return report.State == "PENDING" || report.State == "RETRY_EXHAUSTED" || validKagentDeliveryOutcome(report.State) && report.State != "ACKNOWLEDGED"
}

func validKagentReportReceipt(receipt kagentReportReceipt, sequence int, stream string) bool {
	return receipt.ReportSequence == sequence && receipt.Duplicate != nil && regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(receipt.StreamKey) && (stream == "" || receipt.StreamKey == stream)
}

// The bounded history is independently replayable after restart. An unconfirmed
// report freezes its original sequence and counts even if more events arrive.
func readKagentReportHistory(dir string, snapshot *kagentJournalSnapshot) (kagentReportHistory, error) {
	history := kagentReportHistory{Stream: snapshot.Stream}
	path := filepath.Join(dir, "delivery-reports")
	entries, err := kagentJournalEntries(path, 81)
	if os.IsNotExist(err) {
		return history, nil
	}
	if err != nil {
		return history, err
	}
	names := map[string]bool{}
	for _, entry := range entries {
		name := entry.Name()
		if !regexp.MustCompile(`^(halted|(?:report|ack)-(?:0[1-9]|1[0-6])|send-(?:0[1-9]|1[0-6])-[1-3])\.json$`).MatchString(name) {
			return history, errors.New("invalid report directory")
		}
		names[name] = true
	}
	if names["halted.json"] {
		var outcome kagentDeliveryOutcome
		if readKagentJSON(path, "halted.json", &outcome) != nil || !kagentDeliveryBlocked(outcome.State) {
			return history, errors.New("invalid report halt")
		}
		history.Halted = true
	}
	for seq := 1; seq <= 16; seq++ {
		name := fmt.Sprintf("report-%02d.json", seq)
		if !names[name] {
			continue
		}
		if history.Last == nil && seq != 1 || history.Last != nil && (history.Last.ReportSequence+1 != seq || !history.Confirmed) {
			return history, errors.New("report sequence gap")
		}
		var report kagentDeliveryReport
		if readKagentJSON(path, name, &report) != nil || report.ReportSequence != seq || !validKagentReport(report, filepath.Base(dir)) || report.Total > len(snapshot.Events) || report.Acknowledged > snapshot.Acknowledged || report.Attempts > snapshot.Attempt {
			return history, errors.New("invalid frozen report")
		}
		if history.Last != nil && (report.Total < history.Last.Total || report.Acknowledged < history.Last.Acknowledged || report.Attempts < history.Last.Attempts) {
			return history, errors.New("report counters regressed")
		}
		history.Last = &report
		history.Confirmed = false
		history.Attempts = 0
		body, _ := json.Marshal(report)
		for attempt := 1; attempt <= 3; attempt++ {
			file := fmt.Sprintf("send-%02d-%d.json", seq, attempt)
			if !names[file] {
				continue
			}
			var intent struct {
				BodySHA256 string `json:"bodySha256"`
			}
			if attempt != history.Attempts+1 || readKagentJSON(path, file, &intent) != nil || intent.BodySHA256 != digest(body) {
				return history, errors.New("invalid report attempt")
			}
			history.Attempts = attempt
		}
		ack := fmt.Sprintf("ack-%02d.json", seq)
		if names[ack] {
			var receipt kagentReportReceipt
			if history.Attempts == 0 || readKagentJSON(path, ack, &receipt) != nil || !validKagentReportReceipt(receipt, seq, history.Stream) {
				return history, errors.New("invalid report receipt")
			}
			history.Confirmed = true
			history.Stream = receipt.StreamKey
		}
	}
	for name := range names {
		if name == "halted.json" {
			continue
		}
		if !strings.HasPrefix(name, "send-") && !strings.HasPrefix(name, "ack-") {
			continue
		}
		parts := strings.Split(strings.TrimSuffix(name, ".json"), "-")
		seq, _ := strconv.Atoi(parts[1])
		if !names[fmt.Sprintf("report-%02d.json", seq)] {
			return history, errors.New("orphan report record")
		}
	}
	return history, nil
}

func (r *runner) reportKagentJournal(ctx context.Context, dir string, binding kagentJournalBinding) (sent bool, complete bool) {
	if ctx.Err() != nil {
		return false, false
	}
	r.observationMu.Lock()
	locked := true
	defer func() {
		if locked {
			r.observationMu.Unlock()
		}
	}()
	snapshot, err := loadKagentSnapshot(dir, binding)
	if err != nil {
		return false, false
	}
	history, err := readKagentReportHistory(dir, snapshot)
	if err != nil || history.Halted {
		return false, false
	}
	desired := kagentReportFromSnapshot(dir, snapshot)
	if history.Last != nil && history.Confirmed {
		desired.ReportSequence = history.Last.ReportSequence
		if desired == *history.Last {
			return false, true
		}
	}
	path := filepath.Join(dir, "delivery-reports")
	if history.Last == nil || history.Confirmed {
		desired.ReportSequence = 1
		if history.Last != nil {
			desired.ReportSequence = history.Last.ReportSequence + 1
		}
		if !validKagentReport(desired, filepath.Base(dir)) {
			return false, false
		}
		if err = os.Mkdir(path, 0700); err != nil && !os.IsExist(err) {
			return false, false
		}
		if writeKagentImmutable(path, fmt.Sprintf("report-%02d.json", desired.ReportSequence), desired) != nil {
			return false, false
		}
		// Flush the new child directory's entry, without rewriting parent events.
		parent, err := os.Open(dir)
		if err != nil {
			return false, false
		}
		err = parent.Sync()
		closed := parent.Close()
		if err != nil || closed != nil {
			return false, false
		}
		history.Last = &desired
		history.Attempts = 0
	}
	if history.Attempts >= 3 {
		return false, false
	}
	body, _ := json.Marshal(history.Last)
	current, ok := r.kagentObservationIdentity()
	if !ok || current != binding {
		return false, false
	}
	r.mu.Lock()
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
	u.Path = "/api/runtime-access/v1/execution-delivery"
	if writeKagentImmutable(path, fmt.Sprintf("send-%02d-%d.json", history.Last.ReportSequence, history.Attempts+1), map[string]string{"bodySha256": digest(body)}) != nil {
		return false, false
	}
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
	response, err := client.Do(req)
	if err != nil {
		return true, false
	}
	defer response.Body.Close()
	halt := func(state string) {
		r.observationMu.Lock()
		defer r.observationMu.Unlock()
		_ = writeKagentImmutable(path, "halted.json", kagentDeliveryOutcome{State: state})
	}
	if response.StatusCode != 200 {
		switch {
		case response.StatusCode == 401 || response.StatusCode == 403:
			halt("BLOCKED_AUTH")
		case response.StatusCode == 409:
			halt("BLOCKED_CONFLICT")
		case response.StatusCode == 429 || response.StatusCode >= 500 && response.StatusCode < 600:
		default:
			halt("BLOCKED_REQUEST")
		}
		return true, false
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, 4097))
	if err != nil {
		return true, false
	}
	var envelope struct {
		Success bool            `json:"success"`
		Data    json.RawMessage `json:"data"`
	}
	var receipt kagentReportReceipt
	if len(raw) > 4096 || json.Unmarshal(raw, &envelope) != nil || !envelope.Success {
		halt("BLOCKED_RECEIPT")
		return true, false
	}
	decoder := json.NewDecoder(bytes.NewReader(envelope.Data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&receipt) != nil || decoder.Decode(new(any)) != io.EOF || !validKagentReportReceipt(receipt, history.Last.ReportSequence, history.Stream) {
		halt("BLOCKED_RECEIPT")
		return true, false
	}
	r.observationMu.Lock()
	locked = true
	if writeKagentImmutable(path, fmt.Sprintf("ack-%02d.json", history.Last.ReportSequence), receipt) != nil {
		return true, false
	}
	return true, true
}
