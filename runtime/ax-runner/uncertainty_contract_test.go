package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

// Also runs against the frozen legacy source to reproduce its noncanonical
// UNKNOWN receipt; all provider calls here terminate in a local test server.
func TestModelUncertainReceiptMatchesBackendContract(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if q.URL.Path == "/api/gateway/v1/chat/completions" {
			w.WriteHeader(503)
		} else {
			w.WriteHeader(200)
		}
	}))
	defer server.Close()
	a := &axRunner{dir: t.TempDir(), key: "ax-test", engine: &runner{gateway: server.URL, client: server.Client()}}
	a.execute(axInput{TaskKey: a.key, Objective: "synthetic", Mode: "LLM"})
	raw, _ := os.ReadFile(a.dir + "/result.json")
	var receipt axReceipt
	json.Unmarshal(raw, &receipt)
	if receipt.Status != "EXECUTION_UNKNOWN" {
		t.Fatalf("backend requires EXECUTION_UNKNOWN, received %s", receipt.Status)
	}
}
