package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestFrozenOriginalTraceSurvivesReceiptAndReplay(t *testing.T) {
	a := fixture(t)
	body := `{"taskKey":"ax-test","objective":"PUBLIC_TRACE_FIXTURE","mode":"ECHO","traceId":"1234567890abcdef1234567890abcdef"}`
	if post(a, body).Code != 202 {
		t.Fatal("initial dispatch rejected")
	}
	for i := 0; i < 100 && get(a).Code != 200; i++ {
		time.Sleep(10 * time.Millisecond)
	}
	raw := get(a).Body.Bytes()
	var result axReceipt
	if json.Unmarshal(raw, &result) != nil || result.TraceID != "1234567890abcdef1234567890abcdef" {
		t.Fatal("platform trace replaced")
	}
	b := fixture(t)
	b.dir = a.dir
	if post(b, body).Code != 202 || get(b).Body.String() != string(raw) {
		t.Fatal("original trace/receipt changed")
	}
	if post(b, strings.Replace(body, "1234567890abcdef1234567890abcdef", "b234567890abcdef1234567890abcdef", 1)).Code != 409 {
		t.Fatal("changed trace bypassed original intent")
	}
}

func TestInvalidTraceCannotPersistIntent(t *testing.T) {
	for _, trace := range []string{"not-a-trace", "00000000000000000000000000000000", "PRIVATE_CONTENT"} {
		a := fixture(t)
		body := `{"taskKey":"ax-test","objective":"x","mode":"ECHO","traceId":"` + trace + `"}`
		if post(a, body).Code != 400 {
			t.Fatal("invalid trace accepted")
		}
		if _, err := os.Stat(filepath.Join(a.dir, "started")); !os.IsNotExist(err) {
			t.Fatal("invalid trace created intent")
		}
	}
}

func TestLegacyCanonicalIntentWithoutTraceStaysByteCompatible(t *testing.T) {
	raw, _ := json.Marshal(axInput{TaskKey: "ax-test", Objective: "PUBLIC_FIXTURE", Mode: "ECHO"})
	if string(raw) != `{"taskKey":"ax-test","objective":"PUBLIC_FIXTURE","mode":"ECHO"}` {
		t.Fatalf("legacy canonical parameters changed: %s", raw)
	}
}
