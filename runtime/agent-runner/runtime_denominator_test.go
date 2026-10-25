package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestPlatformTaskAndChatTraceCorrelatesModelSpanAndReceipt(t *testing.T) {
	for _, entry := range []string{"task", "chat"} {
		t.Run(entry, func(t *testing.T) {
			trace := strings.Repeat("a", 32)
			var models, spans atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				if strings.HasSuffix(req.URL.Path, "/chat/completions") {
					if req.Header.Get("X-EAP-Trace-Id") != trace {
						t.Error("original platform trace missing from model request")
					}
					models.Add(1)
					writeJSON(w, map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": "public-result"}}}})
					return
				}
				var body map[string]any
				if json.NewDecoder(req.Body).Decode(&body) != nil {
					t.Error("invalid telemetry")
				}
				raw, _ := json.Marshal(body)
				if !strings.Contains(string(raw), trace) || strings.Contains(string(raw), "private-input") || strings.Contains(string(raw), "public-result") {
					t.Error("uncorrelated or content-bearing telemetry")
				}
				spans.Add(1)
			}))
			defer gateway.Close()
			r := &runner{handled: map[string]string{}, gateway: gateway.URL, taskToken: "fixture-task", client: gateway.Client()}
			body := `{"taskKey":"platform-task","objective":"private-input","traceId":"` + trace + `"}`
			if entry == "chat" {
				body = `{"message":"private-input","traceId":"` + trace + `"}`
			}
			req := httptest.NewRequest(http.MethodPost, "/"+entry, strings.NewReader(body))
			req.Header.Set("Authorization", "Bearer fixture-task")
			out := httptest.NewRecorder()
			if entry == "task" {
				r.task(out, req)
			} else {
				r.chat(out, req)
			}
			var receipt map[string]any
			if json.Unmarshal(out.Body.Bytes(), &receipt) != nil || receipt["traceId"] != trace || out.Code != 200 || models.Load() != 1 || spans.Load() != 1 {
				t.Fatal("receipt returned without original trace, model or completed telemetry export")
			}
		})
	}
}

func TestInvalidPlatformTraceCannotSpendOrExport(t *testing.T) {
	for _, entry := range []string{"task", "chat"} {
		for _, trace := range []string{"wrong", strings.Repeat("0", 31), strings.Repeat("A", 32)} {
			t.Run(entry+trace, func(t *testing.T) {
				var calls atomic.Int32
				gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { calls.Add(1) }))
				defer gateway.Close()
				r := &runner{handled: map[string]string{}, gateway: gateway.URL, taskToken: "fixture-task", client: gateway.Client()}
				body := `{"taskKey":"platform-task","objective":"input","message":"input","traceId":"` + trace + `"}`
				req := httptest.NewRequest(http.MethodPost, "/"+entry, strings.NewReader(body))
				req.Header.Set("Authorization", "Bearer fixture-task")
				out := httptest.NewRecorder()
				if entry == "task" {
					r.task(out, req)
				} else {
					r.chat(out, req)
				}
				if out.Code != 400 || calls.Load() != 0 {
					t.Fatal("invalid trace was dispatched")
				}
			})
		}
	}
}
