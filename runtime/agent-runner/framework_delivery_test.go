//go:build framework && !kagent

package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func frameworkInstalledIdentity(t *testing.T) *runner {
	t.Helper()
	r, signing, uid := identityFixture(t)
	reply := httptest.NewRecorder()
	r.identityChallenge(reply, signedRequest(signing, challengeBody(toolID("operation", "delivery"))))
	if reply.Code != 200 {
		t.Fatal("challenge failed")
	}
	var challenge map[string]string
	if json.Unmarshal(reply.Body.Bytes(), &challenge) != nil {
		t.Fatal("invalid challenge")
	}
	reply = httptest.NewRecorder()
	r.identityInstall(reply, signedRequest(signing, installBody(t, r, challenge, uid, "eap_rt_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")))
	if reply.Code != 200 {
		t.Fatal("identity installation failed")
	}
	r.toolDir = t.TempDir()
	r.gateway = "http://eap.invalid"
	return r
}

func TestFrameworkDeliveryUsesInstalledIdentityAndFreshCredentialOnly(t *testing.T) {
	r := frameworkInstalledIdentity(t)
	var calls []frameworkResendInput
	run := func(ctx context.Context, raw []byte) ([]byte, error) {
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > 25*time.Second {
			t.Fatal("missing resend deadline")
		}
		var in frameworkResendInput
		if json.Unmarshal(raw, &in) != nil {
			t.Fatal("invalid resend input")
		}
		if in.ActorUID != r.credentialUID || in.Epoch != r.credentialEpoch || in.RuntimeKey != r.runtimeKey ||
			in.Directory != filepath.Join(r.toolDir, "execution-events") || in.ExpiresAt != r.credentialExpiry.Unix() {
			t.Fatal("wrong delivery binding")
		}
		calls = append(calls, in)
		return nil, nil
	}
	r.frameworkDeliveryPass(context.Background(), run)
	r.mu.Lock()
	r.runtimeToken = "rotated-synthetic-credential"
	r.mu.Unlock()
	r.frameworkDeliveryPass(context.Background(), run)
	if len(calls) != 2 || calls[0].Credential == calls[1].Credential || calls[1].Credential != r.runtimeToken {
		t.Fatal("stale credential reused")
	}
	if len(r.handled) != 1 || r.handled["existing-task"] != "existing-result" {
		t.Fatal("business receipts changed")
	}
	if _, err := os.Stat(filepath.Join(r.toolDir, "execution-events")); !os.IsNotExist(err) {
		t.Fatal("pass created journal storage")
	}
}

func TestFrameworkDeliverySkipsManualExpiredMissingOrChangedIdentity(t *testing.T) {
	for _, name := range []string{"manual", "expired", "uid", "key", "missing", "epoch", "cancelled"} {
		t.Run(name, func(t *testing.T) {
			r := frameworkInstalledIdentity(t)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			switch name {
			case "manual":
				r.identity = nil
			case "expired":
				r.credentialExpiry = time.Now().Add(-time.Second)
			case "uid":
				r.credentialUID = toolID("other", "uid")
			case "key":
				r.runtimeKey = "eap-agents/foreign"
			case "missing":
				r.runtimeToken = ""
			case "epoch":
				r.credentialEpoch = ""
			case "cancelled":
				cancel()
			}
			r.frameworkDeliveryPass(ctx, func(context.Context, []byte) ([]byte, error) {
				t.Fatal("invalid identity reached worker")
				return nil, nil
			})
		})
	}
}

func TestFrameworkDeliveryLoopStopsAndCancelsInFlightPass(t *testing.T) {
	r := frameworkInstalledIdentity(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ticks := make(chan time.Time, 1)
	started, done := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(done)
		r.frameworkDeliveryLoop(ctx, ticks, func(pass context.Context, _ []byte) ([]byte, error) {
			close(started)
			<-pass.Done()
			return nil, pass.Err()
		})
	}()
	ticks <- time.Now()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("pass never started")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("pass did not stop")
	}
}

func TestFrameworkWorkerCapturesBindingBeforeIdentityCanChange(t *testing.T) {
	r := frameworkInstalledIdentity(t)
	original := r.credentialEpoch
	j := &toolJournal{CallID: toolID("call", "delivery"), TraceID: strings.Repeat("1", 32)}
	_, err := r.frameworkSumWith([]float64{42}, j, func(_ context.Context, raw []byte) ([]byte, error) {
		var in frameworkInput
		if json.Unmarshal(raw, &in) != nil || in.DeliveryIdentity == nil || in.DeliveryIdentity.Epoch != original {
			t.Fatal("binding not captured")
		}
		r.mu.Lock()
		r.credentialEpoch = toolID("new", "epoch")
		r.mu.Unlock()
		if in.DeliveryIdentity.Epoch != original {
			t.Fatal("original binding mutated")
		}
		return json.Marshal(frameworkOutput{Sum: 42, Count: 1, Observation: toolObservation{Framework: frameworkEngine, ProducerID: toolID("p", "delivery"), Events: 12, Pending: 12, DeliveryState: "RETRY_EXHAUSTED", DeliveryAttempts: 3, BackgroundDeliveryState: "WAITING"}})
	})
	if err != nil || j.Observation == nil {
		t.Fatal("valid task result rejected")
	}
	raw, _ := json.Marshal(j)
	if strings.Contains(string(raw), original) || strings.Contains(string(raw), r.runtimeToken) {
		t.Fatal("identity leaked into task receipt")
	}
}

func TestFrameworkDeliveryDiagnosticsRejectMalformedOutputAndHidePrivateErrors(t *testing.T) {
	r := frameworkInstalledIdentity(t)
	for _, raw := range []string{`{"state":"PASS_FINISHED","processed":1,"scanned":1,"issues":0,"credential":"private"}`,
		`{"state":"PASS_FINISHED","processed":9,"scanned":9,"issues":0}`, `{"state":"PASS_FINISHED","processed":true,"scanned":1,"issues":0}`,
		`{"state":"PASS_FINISHED","processed":1,"scanned":0,"issues":0}`, `{"state":"PASS_FINISHED","processed":0,"scanned":1,"issues":1}`,
		`{"state":"PASS_FINISHED","processed":0,"scanned":0,"issues":0}{}`, `null`, `private-exception`} {
		r.frameworkDeliveryPass(context.Background(), func(context.Context, []byte) ([]byte, error) { return []byte(raw), nil })
		status := frameworkDeliveryState(r)
		if status["state"] != "PASS_UNCONFIRMED" || status["processed"] != nil {
			t.Fatal("bad worker output accepted")
		}
		text, _ := json.Marshal(status)
		if strings.Contains(string(text), "private") {
			t.Fatal("raw output leaked")
		}
	}
	r.frameworkDeliveryPass(context.Background(), func(context.Context, []byte) ([]byte, error) { return nil, errors.New("private-exception") })
	if frameworkDeliveryState(r)["state"] != "PASS_UNCONFIRMED" {
		t.Fatal("failure hidden")
	}
}

func TestFrameworkDeliveryDiagnosticsRequireAuthenticationAndAreReadOnly(t *testing.T) {
	r := frameworkInstalledIdentity(t)
	r.frameworkDeliveryPass(context.Background(), func(context.Context, []byte) ([]byte, error) {
		return []byte(`{"state":"PASS_WITH_ISSUES","processed":1,"scanned":3,"issues":1}`), nil
	})
	for _, authorized := range []bool{false, true} {
		req := httptest.NewRequest("GET", "/eap/state", nil)
		if authorized {
			req.Header.Set("Authorization", "Bearer "+r.runtimeToken)
		}
		response := httptest.NewRecorder()
		r.state(response, req)
		if !authorized {
			if response.Code != 401 {
				t.Fatal("diagnostics exposed without authentication")
			}
			continue
		}
		var result map[string]any
		if json.Unmarshal(response.Body.Bytes(), &result) != nil {
			t.Fatal("invalid diagnostic response")
		}
		delivery := result["executionDelivery"].(map[string]any)
		if delivery["state"] != "PASS_WITH_ISSUES" || delivery["processed"] != float64(1) || delivery["issues"] != float64(1) {
			t.Fatal("diagnostics lost")
		}
		if strings.Contains(response.Body.String(), r.runtimeToken) || strings.Contains(response.Body.String(), r.credentialUID) || len(r.handled) != 1 {
			t.Fatal("read changed or disclosed identity")
		}
	}
}
