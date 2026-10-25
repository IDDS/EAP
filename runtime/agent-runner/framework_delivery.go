//go:build framework && !kagent

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"path/filepath"
	"time"
)

func frameworkDeliveryState(r *runner) map[string]any {
	r.mu.Lock()
	defer r.mu.Unlock()
	state := map[string]any{"protocol": "EAP_BACKGROUND_DELIVERY_V1", "state": "NOT_RUN", "pollIntervalSeconds": 30,
		"maxCyclesPerJournal": 6, "maxJournalsPerPass": 8, "requiresOriginalIdentity": true}
	for key, value := range r.engineHealth {
		state[key] = value
	}
	return state
}

func (r *runner) recordFrameworkDelivery(state string, result map[string]any) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.engineHealth = map[string]any{"state": state, "observedAt": time.Now().UTC().Format(time.RFC3339Nano)}
	for key, value := range result {
		r.engineHealth[key] = value
	}
}

type frameworkDeliveryIdentity struct {
	ActorUID string `json:"actorUid"`
	Epoch    string `json:"identityEpoch"`
}

type frameworkResendInput struct {
	Framework  string `json:"framework"`
	RuntimeKey string `json:"runtimeKey"`
	Credential string `json:"credential"`
	Gateway    string `json:"gateway"`
	Directory  string `json:"directory"`
	ActorUID   string `json:"actorUid"`
	Epoch      string `json:"identityEpoch"`
	ExpiresAt  int64  `json:"expiresAt"`
}

// Must hold r.mu. Only the broker's signed installation can establish this binding.
// Environment/manual credentials cannot enroll journals in automatic delivery.
func (r *runner) frameworkDeliveryIdentityLocked() *frameworkDeliveryIdentity {
	if r.identity == nil || !canonicalUUID(r.credentialUID) || !canonicalUUID(r.credentialEpoch) ||
		r.runtimeToken == "" || !time.Now().Before(r.credentialExpiry) {
		return nil
	}
	return &frameworkDeliveryIdentity{r.credentialUID, r.credentialEpoch}
}

func (r *runner) frameworkDeliveryProjectionMatches(key string, binding *frameworkDeliveryIdentity) bool {
	if binding == nil || r.identity == nil {
		return false
	}
	projectedKey, projectedUID, err := r.identity.fields()
	return err == nil && projectedKey == key && projectedUID == binding.ActorUID
}

func runFrameworkDelivery(ctx context.Context, r *runner) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	r.frameworkDeliveryLoop(ctx, ticker.C, func(ctx context.Context, body []byte) ([]byte, error) {
		return runFrameworkProcess(ctx, "/opt/eap-framework/resend.py", body)
	})
}

// One subprocess at a time; a hard deadline also bounds scanning/storage/network stalls.
// Resumption never calls tools, task handlers, identity renewal or lifecycle APIs.
func (r *runner) frameworkDeliveryLoop(ctx context.Context, ticks <-chan time.Time, run func(context.Context, []byte) ([]byte, error)) {
	for {
		select {
		case <-ctx.Done():
			return
		case _, ok := <-ticks:
			if !ok || ctx.Err() != nil {
				return
			}
			r.frameworkDeliveryPass(ctx, run)
		}
	}
}

func (r *runner) frameworkDeliveryPass(ctx context.Context, run func(context.Context, []byte) ([]byte, error)) {
	if ctx.Err() != nil || frameworkEngine != "langgraph" && frameworkEngine != "autogen-core" {
		return
	}
	r.mu.Lock()
	binding := r.frameworkDeliveryIdentityLocked()
	key, credential, expiry := r.runtimeKey, r.runtimeToken, r.credentialExpiry
	r.mu.Unlock()
	if !r.frameworkDeliveryProjectionMatches(key, binding) {
		r.recordFrameworkDelivery("IDENTITY_UNAVAILABLE", nil)
		return
	}
	body, err := json.Marshal(frameworkResendInput{frameworkEngine, key, credential, r.gateway,
		filepath.Join(r.toolDir, "execution-events"), binding.ActorUID, binding.Epoch, expiry.Unix()})
	if err != nil {
		return
	}
	pass, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	raw, err := run(pass, body)
	var result map[string]any
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err != nil || len(raw) > 16384 || decoder.Decode(&result) != nil || decoder.Decode(new(any)) != io.EOF || len(result) != 4 {
		r.recordFrameworkDelivery("PASS_UNCONFIRMED", nil)
		return
	}
	state, ok := result["state"].(string)
	if !ok || state != "PASS_FINISHED" && state != "PASS_WITH_ISSUES" && state != "DIRECTORY_LIMIT" && state != "IDENTITY_UNAVAILABLE" && state != "NO_JOURNALS" {
		r.recordFrameworkDelivery("PASS_UNCONFIRMED", nil)
		return
	}
	counts := map[string]any{}
	for _, key := range []string{"processed", "scanned", "issues"} {
		number, ok := result[key].(json.Number)
		value, parseErr := number.Int64()
		if !ok || parseErr != nil || value < 0 || value > 4097 || key == "processed" && value > 8 {
			r.recordFrameworkDelivery("PASS_UNCONFIRMED", nil)
			return
		}
		counts[key] = value
	}
	if counts["issues"].(int64) > counts["scanned"].(int64) || counts["processed"].(int64) > counts["scanned"].(int64) {
		r.recordFrameworkDelivery("PASS_UNCONFIRMED", nil)
		return
	}
	if state == "PASS_FINISHED" && counts["issues"].(int64) != 0 || state == "PASS_WITH_ISSUES" && counts["issues"].(int64) == 0 ||
		(state == "DIRECTORY_LIMIT" || state == "IDENTITY_UNAVAILABLE" || state == "NO_JOURNALS") && counts["processed"].(int64) != 0 {
		r.recordFrameworkDelivery("PASS_UNCONFIRMED", nil)
		return
	}
	r.recordFrameworkDelivery(state, counts) // Only validated aggregate metadata; no raw worker output.
}
