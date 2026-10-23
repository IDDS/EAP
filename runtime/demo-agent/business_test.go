package main

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"sync"
	"testing"
)

func TestConcurrentDuplicateTasksKeepOriginalSessionCount(t *testing.T) {
	mu.Lock()
	handled = map[string]bool{}
	count = 0
	lastObjective = ""
	mu.Unlock()
	r := &runner{taskToken: "synthetic-task"}
	h := routes(r)
	var wg sync.WaitGroup
	for i := 0; i < 30; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			req := httptest.NewRequest("POST", "/eap/task", bytes.NewBufferString(`{"taskKey":"same-key","objective":"public objective"}`))
			req.Header.Set("Authorization", "Bearer synthetic-task")
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			var response map[string]any
			if json.Unmarshal(rec.Body.Bytes(), &response) != nil || response["tasksHandled"] != float64(1) || response["status"] != "SUCCEEDED" {
				t.Error("duplicate task changed original result")
			}
		}()
	}
	wg.Wait()
	mu.Lock()
	defer mu.Unlock()
	if count != 1 || lastObjective != "public objective" || len(handled) != 1 {
		t.Fatal("original session count changed")
	}
}

func TestV1ProtectsStateAndSleepWithoutChangingReadiness(t *testing.T) {
	r, _, _ := identityFixture(t)
	h := routes(r)
	for _, path := range []string{"/eap/state", "/sleep?s=0", "/eap/task"} {
		req := httptest.NewRequest("GET", path, nil)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != 401 {
			t.Fatalf("uninstalled identity accessed %s", path)
		}
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/readyz", nil))
	if rec.Code != 200 {
		t.Fatal("readiness requires credential")
	}
}
