//go:build kagent

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func kagentTestReportReply(w http.ResponseWriter, sequence int) {
	fmt.Fprintf(w, `{"success":true,"data":{"streamKey":%q,"reportSequence":%d,"duplicate":false}}`, strings.Repeat("a", 64), sequence)
}

func kagentTestEventReply(t *testing.T, w http.ResponseWriter, req *http.Request) {
	t.Helper()
	var body struct {
		Events []kagentExecutionEvent `json:"events"`
	}
	if json.NewDecoder(req.Body).Decode(&body) != nil {
		t.Error("invalid event request")
		w.WriteHeader(400)
		return
	}
	fmt.Fprintf(w, `{"success":true,"data":{"streamKey":%q,"received":%d,"recorded":%d,"duplicates":0}}`, strings.Repeat("a", 64), len(body.Events), len(body.Events))
}

func kagentTestSnapshot(t *testing.T, r *runner, obs *kagentObservation) (*kagentJournalSnapshot, kagentReportHistory) {
	t.Helper()
	binding, ok := r.kagentObservationIdentity()
	if !ok {
		t.Fatal("identity missing")
	}
	snapshot, err := loadKagentSnapshot(obs.directory, binding)
	if err != nil {
		t.Fatal(err)
	}
	history, err := readKagentReportHistory(obs.directory, snapshot)
	if err != nil {
		t.Fatal(err)
	}
	return snapshot, history
}

func TestKagentReportSeparatesEventAndReportAcknowledgement(t *testing.T) {
	r := kagentInstalledIdentity(t)
	obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
	var events, reports atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Header.Get("X-EAP-Runtime-Key") != r.runtimeKey || req.Header.Get("X-EAP-Runtime-Token") == "" {
			t.Error("missing machine identity")
		}
		if req.URL.Path == "/api/runtime-access/v1/execution-events" {
			if events.Add(1) == 1 {
				w.WriteHeader(503)
				return
			}
			kagentTestEventReply(t, w, req)
			return
		}
		if req.URL.Path != "/api/runtime-access/v1/execution-delivery" {
			t.Error("wrong endpoint")
			w.WriteHeader(404)
			return
		}
		var report kagentDeliveryReport
		json.NewDecoder(req.Body).Decode(&report)
		n := reports.Add(1)
		if report.ProducerID != filepath.Base(obs.directory) || report.ReportSequence != int(n) || report.Attempts != int(n) || report.Total != 2 {
			t.Error("invalid metadata counters", report)
		}
		if n == 1 && (report.Acknowledged != 0 || report.Pending != 2 || report.State != "RETRYABLE_SERVER") {
			t.Error("unreceived events acknowledged", report)
		}
		if n == 2 && (report.Acknowledged != 2 || report.Pending != 0 || report.State != "ACKNOWLEDGED") {
			t.Error("receipt not reflected", report)
		}
		kagentTestReportReply(w, report.ReportSequence)
	}))
	defer s.Close()
	r.gateway = s.URL
	r.kagentDeliveryPass(context.Background())
	snapshot, history := kagentTestSnapshot(t, r, obs)
	if snapshot.Acknowledged != 0 || !history.Confirmed || history.Last.Acknowledged != 0 {
		t.Fatal("report receipt acknowledged event files")
	}
	r.kagentDeliveryPass(context.Background())
	r.kagentDeliveryPass(context.Background())
	snapshot, history = kagentTestSnapshot(t, r, obs)
	if snapshot.Acknowledged != 2 || !history.Confirmed || events.Load() != 2 || reports.Load() != 2 {
		t.Fatal("confirmed snapshot resent or counters wrong")
	}
}

func TestKagentReportLostReceiptFreezesBodyAcrossNewEventsAndRenewal(t *testing.T) {
	r := kagentInstalledIdentity(t)
	obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
	var bodies [][]byte
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == "/api/runtime-access/v1/execution-events" {
			kagentTestEventReply(t, w, req)
			return
		}
		body, _ := io.ReadAll(req.Body)
		bodies = append(bodies, body)
		var report kagentDeliveryReport
		json.Unmarshal(body, &report)
		if len(bodies) == 1 {
			conn, _, err := w.(http.Hijacker).Hijack()
			if err != nil {
				t.Error(err)
				return
			}
			conn.Close()
			return
		}
		if req.Header.Get("X-EAP-Runtime-Token") != "renewed-report-fixture" {
			t.Error("renewed lease not used")
		}
		kagentTestReportReply(w, report.ReportSequence)
	}))
	defer s.Close()
	r.gateway = s.URL
	r.kagentDeliveryPass(context.Background())
	obs.emit(kagentExecutionEvent{Kind: "RUN_STARTED", RunID: toolID("later", "run"), DefinitionKey: "later"})
	r.mu.Lock()
	r.runtimeToken = "renewed-report-fixture"
	r.mu.Unlock()
	// Re-read all state from the original files; no in-memory report acknowledgement.
	snapshot, history := kagentTestSnapshot(t, r, obs)
	if history.Confirmed || history.Attempts != 1 || history.Last.Total != 2 || len(snapshot.Events) != 3 {
		t.Fatal("lost receipt state")
	}
	r.kagentDeliveryPass(context.Background())
	r.kagentDeliveryPass(context.Background())
	r.kagentDeliveryPass(context.Background())
	if len(bodies) != 3 || !reflect.DeepEqual(bodies[0], bodies[1]) {
		t.Fatal("unconfirmed report intent changed")
	}
	var final kagentDeliveryReport
	json.Unmarshal(bodies[2], &final)
	if final.ReportSequence != 2 || final.Total != 3 || final.Acknowledged != 3 || final.Attempts != 2 {
		t.Fatal("new snapshot did not advance after receipt", final)
	}
	filepath.WalkDir(obs.directory, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			t.Fatal(err)
		}
		if !d.IsDir() {
			raw, _ := os.ReadFile(path)
			if strings.Contains(string(raw), "renewed-report-fixture") {
				t.Fatal("token persisted")
			}
		}
		return nil
	})
}

func TestKagentReportFailureDoesNotStopEventDelivery(t *testing.T) {
	for _, mode := range []string{"missing-duplicate", "null-duplicate", "wrong-sequence", "wrong-stream", "unknown-field", "oversized", "redirect", "unauthorized", "conflict", "throttled", "server"} {
		t.Run(mode, func(t *testing.T) {
			r := kagentInstalledIdentity(t)
			obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
			var calls atomic.Int32
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				if req.URL.Path == "/api/runtime-access/v1/execution-events" {
					kagentTestEventReply(t, w, req)
					return
				}
				calls.Add(1)
				switch mode {
				case "redirect":
					http.Redirect(w, req, "/forbidden", 302)
				case "unauthorized":
					w.WriteHeader(401)
				case "conflict":
					w.WriteHeader(409)
				case "throttled":
					w.WriteHeader(429)
				case "server":
					w.WriteHeader(503)
				case "oversized":
					fmt.Fprint(w, strings.Repeat("x", 4097))
				default:
					data := map[string]any{"streamKey": strings.Repeat("a", 64), "reportSequence": 1, "duplicate": false}
					switch mode {
					case "missing-duplicate":
						delete(data, "duplicate")
					case "null-duplicate":
						data["duplicate"] = nil
					case "wrong-sequence":
						data["reportSequence"] = 2
					case "wrong-stream":
						data["streamKey"] = strings.Repeat("b", 64)
					case "unknown-field":
						data["received"] = 2
					}
					json.NewEncoder(w).Encode(map[string]any{"success": true, "data": data})
				}
			}))
			defer s.Close()
			r.gateway = s.URL
			r.kagentDeliveryPass(context.Background())
			obs.emit(kagentExecutionEvent{Kind: "RUN_STARTED", RunID: toolID("later", "run"), DefinitionKey: "later"})
			for i := 0; i < 7; i++ {
				r.kagentDeliveryPass(context.Background())
			}
			snapshot, history := kagentTestSnapshot(t, r, obs)
			expected := int32(1)
			if mode == "throttled" || mode == "server" {
				expected = 3
			}
			if calls.Load() != expected || snapshot.Acknowledged != 3 || history.Confirmed {
				t.Fatal("report retry or event independence", calls.Load(), snapshot, history)
			}
			if expected == 1 && !history.Halted {
				t.Fatal("permanent report error not persisted")
			}
		})
	}
}

func TestKagentReportIdentityCannotTakeOverJournal(t *testing.T) {
	for _, mode := range []string{"epoch", "uid", "expired", "manual"} {
		t.Run(mode, func(t *testing.T) {
			r := kagentInstalledIdentity(t)
			obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
			binding, _ := r.kagentObservationIdentity()
			var calls atomic.Int32
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { calls.Add(1); w.WriteHeader(503) }))
			defer s.Close()
			r.gateway = s.URL
			switch mode {
			case "epoch":
				r.credentialEpoch = toolID("foreign", "epoch")
			case "uid":
				r.credentialUID = toolID("foreign", "uid")
			case "expired":
				r.credentialExpiry = time.Now().Add(-time.Second)
			case "manual":
				r.identity = nil
			}
			r.reportKagentJournal(context.Background(), obs.directory, binding)
			if calls.Load() != 0 {
				t.Fatal("old journal sent under changed identity")
			}
		})
	}
}

func TestKagentReportCaptureContinuesDuringSlowResponse(t *testing.T) {
	r := kagentInstalledIdentity(t)
	obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
	entered, release := make(chan struct{}), make(chan struct{})
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == "/api/runtime-access/v1/execution-events" {
			kagentTestEventReply(t, w, req)
			return
		}
		close(entered)
		<-release
		w.WriteHeader(503)
	}))
	defer s.Close()
	defer close(release)
	r.gateway = s.URL
	done := make(chan struct{})
	go func() { r.kagentDeliveryPass(context.Background()); close(done) }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("report not reached")
	}
	captured := make(chan struct{})
	go func() {
		obs.mu.Lock()
		obs.emit(kagentExecutionEvent{Kind: "RUN_STARTED", RunID: toolID("later", "run"), DefinitionKey: "later"})
		obs.mu.Unlock()
		close(captured)
	}()
	select {
	case <-captured:
	case <-time.After(time.Second):
		t.Fatal("report network blocked capture")
	}
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("report request unbounded")
	}
}

func TestKagentReportUnknownBusinessRemainsIncomplete(t *testing.T) {
	r := kagentInstalledIdentity(t)
	obs := executeObservedTree(t, r, observedLeaf(t, "root", errModelOutcomeUnknown), errModelOutcomeUnknown)
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == "/api/runtime-access/v1/execution-events" {
			kagentTestEventReply(t, w, req)
			return
		}
		kagentTestReportReply(w, 1)
	}))
	defer s.Close()
	r.gateway = s.URL
	r.kagentDeliveryPass(context.Background())
	snapshot, history := kagentTestSnapshot(t, r, obs)
	// CaptureComplete concerns journal I/O; graph completeness requires terminal events.
	if len(snapshot.Events) != 1 || snapshot.Events[0].Kind != "RUN_STARTED" || !history.Confirmed || history.Last.State != "ACKNOWLEDGED" {
		t.Fatal("delivery receipt changed business coverage")
	}
}

func TestKagentReportLegacyHaltAndEmptyJournal(t *testing.T) {
	for _, mode := range []string{"legacy-halt", "empty"} {
		t.Run(mode, func(t *testing.T) {
			r := kagentInstalledIdentity(t)
			binding, _ := r.kagentObservationIdentity()
			dir, err := r.createKagentJournal(binding)
			if err != nil {
				t.Fatal(err)
			}
			if mode == "legacy-halt" {
				event := kagentExecutionEvent{Sequence: 1, EventID: toolID("fixture", "event"), RunID: toolID("fixture", "run"), Kind: "RUN_STARTED"}
				if writeKagentImmutable(dir, kagentEventName(1), event) != nil || writeKagentImmutable(dir, "halted.json", map[string]bool{"halted": true}) != nil {
					t.Fatal("fixture journal")
				}
			}
			var report kagentDeliveryReport
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				if req.URL.Path != "/api/runtime-access/v1/execution-delivery" {
					t.Error("halted/empty events sent")
					w.WriteHeader(400)
					return
				}
				json.NewDecoder(req.Body).Decode(&report)
				kagentTestReportReply(w, report.ReportSequence)
			}))
			defer s.Close()
			r.gateway = s.URL
			r.kagentDeliveryPass(context.Background())
			expected := "EMPTY"
			if mode == "legacy-halt" {
				expected = "BLOCKED_TRANSPORT"
			}
			if report.State != expected || report.Attempts != 0 || report.Acknowledged != 0 {
				t.Fatal("legacy state lost", report)
			}
		})
	}
}

func TestKagentReportRejectsCorruptHistory(t *testing.T) {
	for _, mode := range []string{"orphan-send", "orphan-ack", "sequence-gap", "attempt-gap", "hash", "counter-regression", "unknown-file", "symlink", "permissions"} {
		t.Run(mode, func(t *testing.T) {
			r := kagentInstalledIdentity(t)
			obs := executeObservedTree(t, r, observedLeaf(t, "root", nil), nil)
			binding, _ := r.kagentObservationIdentity()
			snapshot, err := loadKagentSnapshot(obs.directory, binding)
			if err != nil {
				t.Fatal(err)
			}
			dir := filepath.Join(obs.directory, "delivery-reports")
			if err = os.Mkdir(dir, 0700); err != nil {
				t.Fatal(err)
			}
			report := kagentReportFromSnapshot(obs.directory, snapshot)
			report.ReportSequence = 1
			if mode == "sequence-gap" {
				report.ReportSequence = 2
			}
			if mode == "counter-regression" {
				report.Acknowledged = 1
				report.Pending = 1
			}
			name := fmt.Sprintf("report-%02d.json", report.ReportSequence)
			if mode != "orphan-send" && mode != "orphan-ack" {
				if writeKagentImmutable(dir, name, report) != nil {
					t.Fatal("report fixture")
				}
			}
			body, _ := json.Marshal(report)
			switch mode {
			case "orphan-send", "hash":
				writeKagentImmutable(dir, "send-01-1.json", map[string]string{"bodySha256": "wrong"})
			case "orphan-ack":
				duplicate := false
				writeKagentImmutable(dir, "ack-01.json", kagentReportReceipt{StreamKey: strings.Repeat("a", 64), ReportSequence: 1, Duplicate: &duplicate})
			case "attempt-gap":
				writeKagentImmutable(dir, "send-01-2.json", map[string]string{"bodySha256": digest(body)})
			case "unknown-file":
				writeKagentImmutable(dir, "unexpected.json", map[string]string{})
			case "symlink":
				os.Symlink(filepath.Join(dir, name), filepath.Join(dir, "send-01-1.json"))
			case "permissions":
				os.Chmod(filepath.Join(dir, name), 0644)
			}
			if _, err = readKagentReportHistory(obs.directory, snapshot); err == nil {
				t.Fatal("corrupt history accepted", mode)
			}
		})
	}
}

func TestKagentReportSnapshotHistoryIsBounded(t *testing.T) {
	r := kagentInstalledIdentity(t)
	binding, _ := r.kagentObservationIdentity()
	dir, err := r.createKagentJournal(binding)
	if err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		var report kagentDeliveryReport
		json.NewDecoder(req.Body).Decode(&report)
		calls.Add(1)
		kagentTestReportReply(w, report.ReportSequence)
	}))
	defer s.Close()
	r.gateway = s.URL
	for i := 1; i <= 18; i++ {
		event := kagentExecutionEvent{Sequence: i, EventID: toolID("fixture", fmt.Sprint(i)), RunID: toolID("fixture", "run"), Kind: "RUN_STARTED"}
		if writeKagentImmutable(dir, kagentEventName(i), event) != nil {
			t.Fatal("event fixture")
		}
		r.reportKagentJournal(context.Background(), dir, binding)
	}
	files, err := os.ReadDir(filepath.Join(dir, "delivery-reports"))
	if err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 16 || len(files) != 48 {
		t.Fatal("unbounded report history", calls.Load(), len(files))
	}
}
