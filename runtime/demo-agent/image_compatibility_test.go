package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

// The remote Job supplies digest-verified original binaries and the new artifacts.
// All gateway traffic terminates at this process's loopback fixture.
func compatibilityDir(t *testing.T) string {
	t.Helper()
	d := os.Getenv("EAP_COMPAT_DIR")
	if d == "" {
		t.Fatal("remote digest-verified compatibility artifacts required")
	}
	return d
}

func startProgram(t *testing.T, name, gateway string) (string, func()) {
	t.Helper()
	raw := make([]byte, 24)
	if _, err := rand.Read(raw); err != nil {
		t.Fatal(err)
	}
	token := hex.EncodeToString(raw)
	cmd := exec.Command(filepath.Join(compatibilityDir(t), name))
	// No inherited credentials, provider URLs, telemetry destinations or journals.
	cmd.Env = []string{"PATH=/usr/local/go/bin:/usr/bin:/bin", "EAP_TASK_TOKEN=" + token, "EAP_GATEWAY_TOKEN=" + token, "EAP_GATEWAY_URL=" + gateway, "EAP_AGENT_ID=999999", "EAP_TOOLS_ENABLED=false", "EAP_TOOL_JOURNAL_DIR=" + t.TempDir(), "EAP_EXPERIMENT_JOURNAL_DIR=" + t.TempDir()}
	if err := cmd.Start(); err != nil {
		t.Fatalf("%s failed to start", name)
	}
	stop := func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		client := &http.Client{Timeout: 200 * time.Millisecond}
		res, err := client.Get("http://127.0.0.1:80/readyz")
		if err == nil {
			res.Body.Close()
			if res.StatusCode == 200 {
				return token, stop
			}
		}
	}
	stop()
	t.Fatalf("%s readiness unavailable", name)
	return "", nil
}

func programRequest(t *testing.T, method, path, token string, body any) (int, map[string]any) {
	t.Helper()
	var data []byte
	if body != nil {
		data, _ = json.Marshal(body)
	}
	req, _ := http.NewRequest(method, "http://127.0.0.1:80"+path, bytes.NewReader(data))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := (&http.Client{Timeout: 3 * time.Second}).Do(req)
	if err != nil {
		t.Fatal("loopback program unavailable")
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	var value map[string]any
	_ = json.Unmarshal(raw, &value)
	return res.StatusCode, value
}

func TestOriginalAndAdaptedDemoKeepTaskAndSessionContract(t *testing.T) {
	var original []map[string]any
	for _, name := range []string{"original-demo-agent", "demo-agent"} {
		token, stop := startProgram(t, name, "")
		code, _ := programRequest(t, "POST", "/eap/task", "", map[string]any{"taskKey": "denied"})
		if code != 401 {
			stop()
			t.Fatal("unauthorized task accepted")
		}
		results := []map[string]any{}
		for _, key := range []string{"first", "first", "second"} {
			code, r := programRequest(t, "POST", "/eap/task", token, map[string]any{"protocol": "EAP_HTTP_TASK_V1", "taskKey": key, "nodeKey": "demo", "objective": "public " + key, "context": map[string]any{}})
			if code != 200 || r["status"] != "SUCCEEDED" {
				stop()
				t.Fatal("original task contract failed")
			}
			results = append(results, r)
		}
		code, r := programRequest(t, "GET", "/eap/state", token, nil)
		if code != 200 || r["tasksHandled"] != float64(2) || r["lastObjective"] != "public second" {
			stop()
			t.Fatal("original session state failed")
		}
		results = append(results, r)
		stop()
		if original == nil {
			original = results
		} else if !reflect.DeepEqual(original, results) {
			t.Fatal("adaptation changed original business response")
		}
	}
}

func TestRunnerMigrationPreservesDefaultsAndIsolatesTaskAndChatContext(t *testing.T) {
	var mu sync.Mutex
	requests := []map[string]any{}
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/chat/completions") {
			w.WriteHeader(204)
			return
		}
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			w.WriteHeader(400)
			return
		}
		mu.Lock()
		requests = append(requests, body)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"public fixture answer"}}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}`)
	}))
	defer fixture.Close()
	var originalRequests, originalResponses []map[string]any
	for _, name := range []string{"original-runner", "current-runner"} {
		mu.Lock()
		requests = nil
		mu.Unlock()
		token, stop := startProgram(t, name, fixture.URL)
		results := []map[string]any{}
		for _, key := range []string{"first", "first", "second"} {
			code, r := programRequest(t, "POST", "/eap/task", token, map[string]any{"taskKey": key, "objective": "public " + key, "context": map[string]any{"label": "public"}})
			if code != 200 || r["status"] != "SUCCEEDED" || r["output"] != "public fixture answer" {
				stop()
				t.Fatal("Runner task contract failed")
			}
			// The current runner adds an explicit completion reason. Check this
			// additive field before comparing the original business response.
			if name == "current-runner" {
				if r["finishReason"] != "stop" {
					stop()
					t.Fatal("Runner completion reason missing")
				}
				delete(r, "finishReason")
			}
			results = append(results, r)
		}
		code, r := programRequest(t, "POST", "/chat", token, map[string]any{"message": "public chat"})
		if code != 200 || r["reply"] != "public fixture answer" {
			stop()
			t.Fatal("Runner chat contract failed")
		}
		results = append(results, r)
		stop()
		mu.Lock()
		observed := append([]map[string]any(nil), requests...)
		mu.Unlock()
		if len(observed) != 3 {
			t.Fatal("duplicate task caused another fixture invocation")
		}
		for i, request := range observed {
			if request["model"] != "deepseek-flash" || request["max_tokens"] != float64(1024) {
				t.Fatal("default model/output limit changed")
			}
			messages, ok := request["messages"].([]any)
			expected := 2
			if name == "original-runner" {
				expected += 2 * i
			}
			if !ok || len(messages) != expected {
				t.Fatal("unexpected implicit conversation history")
			}
		}
		if originalRequests == nil {
			originalRequests = observed
			originalResponses = results
		} else {
			if !reflect.DeepEqual(originalRequests[0], observed[0]) || !reflect.DeepEqual(originalResponses[:3], results[:3]) {
				t.Fatal("Runner upgrade changed initial payload or task business response")
			}
			for i := range observed {
				before := originalRequests[i]["messages"].([]any)
				after := observed[i]["messages"].([]any)
				if !reflect.DeepEqual(before[0], after[0]) || !reflect.DeepEqual(before[len(before)-1], after[1]) {
					t.Fatal("Runner migration changed system prompt or current input")
				}
			}
			if originalResponses[3]["turns"] != float64(3) || results[3]["turns"] != float64(1) ||
				results[3]["conversationMode"] != "EXPLICIT_V1" || results[3]["reply"] != originalResponses[3]["reply"] {
				t.Fatal("Runner migration failed to replace implicit history with explicit chat context")
			}
		}
	}
}
