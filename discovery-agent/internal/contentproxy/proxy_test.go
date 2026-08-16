package contentproxy

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/matrix-eap/discovery-agent/internal/config"
	"github.com/matrix-eap/discovery-agent/internal/protocol"
	queuepkg "github.com/matrix-eap/discovery-agent/internal/queue"
)

func TestCaptureReadCloserPreservesStreamAndBoundsCapture(t *testing.T) {
	var captured string
	var truncated bool
	reader := newCaptureReadCloser(io.NopCloser(strings.NewReader("0123456789")), 4, func(body []byte, wasTruncated bool) {
		captured, truncated = string(body), wasTruncated
	})
	forwarded, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	if string(forwarded) != "0123456789" {
		t.Fatalf("stream was changed: %q", forwarded)
	}
	if captured != "0123" || !truncated {
		t.Fatalf("unexpected capture %q truncated=%v", captured, truncated)
	}
}

func TestResponseMetadataNormalizesGeminiUsage(t *testing.T) {
	body := []byte(`{"model":"gemini-2.5-pro","usageMetadata":{"promptTokenCount":21,"candidatesTokenCount":8,"cachedContentTokenCount":5,"thoughtsTokenCount":3}}`)
	metadata := responseMetadata(body, 200, "application/json", &captureContext{
		startedAt: time.Now(), usagePoolKey: "shared-gemini-prod",
	}, false)
	usage, ok := metadata["usage"].(map[string]any)
	if !ok {
		t.Fatal("usage metadata was not captured")
	}
	if usage["promptTokenCount"] != float64(21) || usage["thoughtsTokenCount"] != float64(3) {
		t.Fatalf("unexpected usage metadata: %#v", usage)
	}
	if responseModelName(body, "application/json") != "gemini-2.5-pro" {
		t.Fatal("response model was not captured")
	}
	if metadata["usagePoolKey"] != "shared-gemini-prod" {
		t.Fatalf("usage pool key was not captured: %#v", metadata)
	}
}

func TestResponseMetadataReadsFinalSSEUsage(t *testing.T) {
	body := []byte("data: {\"id\":\"req-1\",\"model\":\"gpt-5\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n" +
		"data: {\"id\":\"req-1\",\"model\":\"gpt-5\",\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4}}\n\n" +
		"data: [DONE]\n\n")
	metadata := responseMetadata(body, 200, "text/event-stream", &captureContext{startedAt: time.Now()}, false)
	usage, ok := metadata["usage"].(map[string]any)
	if !ok || usage["prompt_tokens"] != float64(11) || usage["completion_tokens"] != float64(4) {
		t.Fatalf("unexpected SSE usage metadata: %#v", metadata)
	}
	if responseModelName(body, "text/event-stream") != "gpt-5" {
		t.Fatal("SSE response model was not captured")
	}
}

func TestResponseMetadataMergesAnthropicSSEUsage(t *testing.T) {
	body := []byte("event: message_start\n" +
		"data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-4\",\"usage\":{\"input_tokens\":17,\"cache_read_input_tokens\":6}}}\n\n" +
		"event: message_delta\n" +
		"data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}\n\n")
	metadata := responseMetadata(body, 200, "text/event-stream", &captureContext{startedAt: time.Now()}, false)
	usage, ok := metadata["usage"].(map[string]any)
	if !ok || usage["input_tokens"] != float64(17) || usage["output_tokens"] != float64(9) || usage["cache_read_input_tokens"] != float64(6) {
		t.Fatalf("unexpected Anthropic SSE usage metadata: %#v", metadata)
	}
	if responseModelName(body, "text/event-stream") != "claude-sonnet-4" {
		t.Fatal("Anthropic SSE response model was not captured")
	}
}

func TestInstrumentationCaptureUsesScopedTokenAndForcesAgentIdentity(t *testing.T) {
	received := make(chan protocol.ContentCapture, 1)
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get("X-EAP-Content-Credential") != "content-credential" {
			t.Errorf("content credential was not forwarded")
		}
		var payload protocol.ContentCapture
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Errorf("decode capture: %v", err)
		}
		received <- payload
		writer.Header().Set("Content-Type", "application/json")
		_, _ = writer.Write([]byte(`{"success":true,"data":{},"message":"ok","traceId":"test"}`))
	}))
	defer server.Close()

	client, err := protocol.NewContentClient(server.URL, "content-credential", "")
	if err != nil {
		t.Fatal(err)
	}
	queue, err := queuepkg.Open(t.TempDir(), 1024*1024, 1)
	if err != nil {
		t.Fatal(err)
	}
	proxy := &Proxy{
		cfg: config.Config{ContentProxy: config.ContentConfig{
			AgentID: 4, Provider: "deepseek", UsagePoolKey: "shared-deepseek-production", MaxCaptureBytes: 1024 * 1024,
		}},
		policy: protocol.Policy{ContentCaptureEnabled: true}, client: client, queue: queue,
		instrumentationToken: "scoped-token",
	}

	unauthorized := httptest.NewRequest(http.MethodPost, "/eap/content-proxy/capture", strings.NewReader(`{"observationKey":"obs-1"}`))
	unauthorizedResult := httptest.NewRecorder()
	proxy.ServeHTTP(unauthorizedResult, unauthorized)
	if unauthorizedResult.Code != http.StatusUnauthorized {
		t.Fatalf("expected unauthorized, got %d", unauthorizedResult.Code)
	}

	request := httptest.NewRequest(http.MethodPost, "/eap/content-proxy/capture", strings.NewReader(
		`{"observationKey":"obs-1","agentId":999,"direction":"INPUT","content":"hello","metadata":{}}`,
	))
	request.Header.Set("X-EAP-Instrumentation-Token", "scoped-token")
	result := httptest.NewRecorder()
	proxy.ServeHTTP(result, request)
	if result.Code != http.StatusAccepted {
		t.Fatalf("expected accepted, got %d: %s", result.Code, result.Body.String())
	}

	select {
	case capture := <-received:
		if capture.AgentID != 4 || capture.Provider != "deepseek" {
			t.Fatalf("identity was not forced: %#v", capture)
		}
		if capture.Metadata["usagePoolKey"] != "shared-deepseek-production" {
			t.Fatalf("usage pool missing: %#v", capture.Metadata)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("capture was not forwarded")
	}
}
