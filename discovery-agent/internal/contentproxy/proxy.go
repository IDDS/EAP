package contentproxy

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	mathrand "math/rand/v2"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/matrix-eap/discovery-agent/internal/agent"
	"github.com/matrix-eap/discovery-agent/internal/config"
	"github.com/matrix-eap/discovery-agent/internal/protocol"
	queuepkg "github.com/matrix-eap/discovery-agent/internal/queue"
	"github.com/matrix-eap/discovery-agent/internal/state"
)

type Proxy struct {
	cfg                  config.Config
	identity             state.ContentIdentity
	policy               protocol.Policy
	client               *protocol.Client
	reverse              *httputil.ReverseProxy
	queue                *queuepkg.Queue
	flushMu              sync.Mutex
	policyMu             sync.RWMutex
	instrumentationToken string
}

func Open(configPath string) (*Proxy, error) {
	cfg, err := config.Load(configPath)
	if err != nil {
		return nil, err
	}
	identity, err := state.NewStore(cfg.ContentStateDir).LoadContentIdentity()
	if err != nil {
		return nil, fmt.Errorf("load content proxy identity: %w", err)
	}
	if identity.Credential == "" {
		return nil, errors.New("content proxy credential is missing")
	}
	if cfg.ContentProxy.UpstreamURL == "" {
		return nil, errors.New("content_proxy.upstream_url is required")
	}
	upstream, err := url.Parse(cfg.ContentProxy.UpstreamURL)
	if err != nil {
		return nil, err
	}
	client, err := protocol.NewContentClient(cfg.ServerURL, identity.Credential, cfg.ProxyURL)
	if err != nil {
		return nil, err
	}
	policy, err := client.ContentPolicy(context.Background())
	if err != nil {
		return nil, fmt.Errorf("load content policy: %w", err)
	}
	if !policy.ContentCaptureEnabled {
		return nil, errors.New("content capture has not completed dual approval in EAP")
	}
	queueBytes, queueDays := policy.OfflineQueueMaxBytes, policy.OfflineQueueDays
	if queueBytes <= 0 {
		queueBytes = 100 * 1024 * 1024
	}
	if queueDays <= 0 {
		queueDays = 7
	}
	queue, err := queuepkg.Open(filepath.Join(cfg.ContentStateDir, "content"), queueBytes, queueDays)
	if err != nil {
		return nil, err
	}
	instrumentationToken := ""
	if tokenFile := strings.TrimSpace(cfg.ContentProxy.InstrumentationTokenFile); tokenFile != "" {
		token, readErr := os.ReadFile(tokenFile)
		if readErr != nil {
			return nil, fmt.Errorf("read instrumentation token: %w", readErr)
		}
		instrumentationToken = strings.TrimSpace(string(token))
		if instrumentationToken == "" {
			return nil, errors.New("instrumentation token file is empty")
		}
	}
	p := &Proxy{cfg: cfg, identity: identity, policy: policy, client: client, queue: queue, instrumentationToken: instrumentationToken}
	p.reverse = httputil.NewSingleHostReverseProxy(upstream)
	p.reverse.ModifyResponse = p.captureResponse
	p.reverse.ErrorHandler = func(writer http.ResponseWriter, request *http.Request, proxyErr error) {
		slog.Error("upstream model request failed", "error", proxyErr)
		http.Error(writer, "upstream model request failed", http.StatusBadGateway)
	}
	return p, nil
}

func (p *Proxy) Run(ctx context.Context) error {
	server := &http.Server{Addr: p.cfg.ContentProxy.Listen, Handler: p, ReadHeaderTimeout: 10 * time.Second}
	go p.flushLoop(ctx)
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	slog.Info("content proxy listening", "address", p.cfg.ContentProxy.Listen, "provider", p.cfg.ContentProxy.Provider, "version", agent.Version)
	err := server.ListenAndServe()
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (p *Proxy) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	if request.URL.Path == "/eap/content-proxy/health" {
		writer.Header().Set("Content-Type", "application/json")
		if p.currentPolicy().ContentCaptureEnabled {
			_, _ = writer.Write([]byte(`{"status":"UP","authorized":true}`))
		} else {
			writer.WriteHeader(http.StatusServiceUnavailable)
			_, _ = writer.Write([]byte(`{"status":"DOWN","authorized":false}`))
		}
		return
	}
	if request.URL.Path == "/eap/content-proxy/capture" {
		p.captureInstrumentation(writer, request)
		return
	}
	policy := p.currentPolicy()
	if !policy.ContentCaptureEnabled {
		http.Error(writer, "content capture authorization is disabled", http.StatusServiceUnavailable)
		return
	}
	traceID := firstNonBlank(request.Header.Get("X-EAP-Trace-Id"), randomID())
	request.Header.Set("X-EAP-Trace-Id", traceID)
	request.Header.Set("X-EAP-Endpoint-Key", p.identity.EndpointKey)
	manualRetain := truthy(request.Header.Get("X-EAP-Retain-Raw")) || strings.EqualFold(request.Header.Get("X-EAP-Business-Value"), "high")
	ctx := &captureContext{
		traceID:       traceID,
		model:         firstNonBlank(request.Header.Get(p.cfg.ContentProxy.ModelHeader)),
		usagePoolKey:  p.cfg.ContentProxy.UsagePoolKey,
		sampled:       manualRetain || sample(policy.RawContentSampleRate),
		captureReason: map[bool]string{true: "MANUAL_OR_HIGH_VALUE", false: "POLICY_SAMPLE"}[manualRetain],
		startedAt:     time.Now(),
		method:        request.Method,
		path:          request.URL.Path,
		agentID:       p.cfg.ContentProxy.AgentID,
	}
	request = request.WithContext(context.WithValue(request.Context(), contextKey{}, ctx))
	if request.Body != nil {
		request.Body = newCaptureReadCloser(request.Body, p.cfg.ContentProxy.MaxCaptureBytes, func(body []byte, truncated bool) {
			current := captureFrom(request.Context())
			model := firstNonBlank(modelName(body), current.model)
			current.model = model
			p.capture(context.WithoutCancel(request.Context()), protocol.ContentCapture{
				ObservationKey: "proxy-" + traceID + "-input",
				AgentID:        p.cfg.ContentProxy.AgentID,
				TraceID:        traceID,
				Direction:      "INPUT",
				Provider:       p.cfg.ContentProxy.Provider,
				ModelName:      model,
				Content:        string(body),
				Metadata: map[string]any{
					"method": current.method, "path": current.path, "truncated": truncated,
					"captureReason":         current.captureReason,
					"attributionMode":       attributionMode(p.cfg.ContentProxy.AgentID),
					"attributionConfidence": attributionConfidence(p.cfg.ContentProxy.AgentID),
				},
				RetainRaw:  current.sampled,
				Sampled:    current.sampled,
				ObservedAt: current.startedAt,
			})
		})
	}
	p.reverse.ServeHTTP(writer, request)
}

func (p *Proxy) captureInstrumentation(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodPost {
		writer.Header().Set("Allow", http.MethodPost)
		http.Error(writer, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if p.instrumentationToken == "" || !constantTimeEqual(request.Header.Get("X-EAP-Instrumentation-Token"), p.instrumentationToken) {
		http.Error(writer, "instrumentation token is invalid", http.StatusUnauthorized)
		return
	}
	if !p.currentPolicy().ContentCaptureEnabled {
		http.Error(writer, "content capture authorization is disabled", http.StatusServiceUnavailable)
		return
	}
	defer request.Body.Close()
	var capture protocol.ContentCapture
	decoder := json.NewDecoder(io.LimitReader(request.Body, p.cfg.ContentProxy.MaxCaptureBytes+1))
	if err := decoder.Decode(&capture); err != nil {
		http.Error(writer, "invalid instrumentation payload", http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(capture.ObservationKey) == "" || strings.TrimSpace(capture.Direction) == "" || capture.Content == "" {
		http.Error(writer, "observationKey, direction and content are required", http.StatusBadRequest)
		return
	}
	capture.AgentID = p.cfg.ContentProxy.AgentID
	capture.Provider = firstNonBlank(capture.Provider, p.cfg.ContentProxy.Provider)
	if capture.Metadata == nil {
		capture.Metadata = map[string]any{}
	}
	capture.Metadata["attributionMode"] = attributionMode(capture.AgentID)
	capture.Metadata["attributionConfidence"] = attributionConfidence(capture.AgentID)
	if p.cfg.ContentProxy.UsagePoolKey != "" {
		capture.Metadata["usagePoolKey"] = p.cfg.ContentProxy.UsagePoolKey
	}
	if capture.ObservedAt.IsZero() {
		capture.ObservedAt = time.Now()
	}
	p.capture(context.WithoutCancel(request.Context()), capture)
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(http.StatusAccepted)
	_, _ = writer.Write([]byte(`{"accepted":true}`))
}

func constantTimeEqual(received, expected string) bool {
	left, right := []byte(strings.TrimSpace(received)), []byte(strings.TrimSpace(expected))
	if len(left) != len(right) {
		return false
	}
	return subtle.ConstantTimeCompare(left, right) == 1
}

func (p *Proxy) captureResponse(response *http.Response) error {
	if response.Body == nil {
		return nil
	}
	ctx := captureFrom(response.Request.Context())
	status := response.StatusCode
	contentType := response.Header.Get("Content-Type")
	response.Body = newCaptureReadCloser(response.Body, p.cfg.ContentProxy.MaxCaptureBytes, func(body []byte, truncated bool) {
		retainRaw := ctx.sampled || status >= 400
		p.capture(context.WithoutCancel(response.Request.Context()), protocol.ContentCapture{
			ObservationKey: "proxy-" + ctx.traceID + "-output",
			AgentID:        p.cfg.ContentProxy.AgentID,
			TraceID:        ctx.traceID,
			Direction:      "OUTPUT",
			Provider:       p.cfg.ContentProxy.Provider,
			ModelName:      firstNonBlank(responseModelName(body, contentType), ctx.model),
			Content:        string(body),
			Metadata:       responseMetadata(body, status, contentType, ctx, truncated),
			RetainRaw:      retainRaw,
			Sampled:        retainRaw,
			ObservedAt:     time.Now(),
		})
	})
	return nil
}

func responseMetadata(body []byte, status int, contentType string, ctx *captureContext, truncated bool) map[string]any {
	metadata := map[string]any{
		"httpStatus":            status,
		"contentType":           contentType,
		"latencyMs":             time.Since(ctx.startedAt).Milliseconds(),
		"method":                ctx.method,
		"path":                  ctx.path,
		"truncated":             truncated,
		"outcome":               map[bool]string{true: "SUCCEEDED", false: "FAILED"}[status >= 200 && status < 400],
		"captureReason":         ctx.captureReason,
		"attributionMode":       attributionMode(ctx.agentID),
		"attributionConfidence": attributionConfidence(ctx.agentID),
	}
	if ctx.usagePoolKey != "" {
		metadata["usagePoolKey"] = ctx.usagePoolKey
	}
	usageMetadata := map[string]any{}
	for _, document := range responseDocuments(body, contentType) {
		if usage, ok := document["usage"].(map[string]any); ok {
			mergeUsage(usageMetadata, usage)
		}
		if usage, ok := document["usageMetadata"].(map[string]any); ok {
			mergeUsage(usageMetadata, usage)
		}
		if message, ok := document["message"].(map[string]any); ok {
			if usage, ok := message["usage"].(map[string]any); ok {
				mergeUsage(usageMetadata, usage)
			}
		}
		if value, ok := document["id"].(string); ok && value != "" {
			metadata["providerRequestId"] = value
		}
		if cost, ok := firstNumber(document, "moneyCost", "totalCost", "total_cost", "cost"); ok {
			metadata["moneyCost"] = cost
		}
		if currency, ok := document["currency"].(string); ok && currency != "" {
			metadata["currency"] = currency
		}
		if count := toolCallCount(document); count > 0 {
			metadata["toolCallCount"] = count
		}
		if errorType := responseErrorType(document); errorType != "" {
			metadata["errorType"] = errorType
		}
	}
	if len(usageMetadata) > 0 {
		metadata["usage"] = usageMetadata
	}
	return metadata
}

func mergeUsage(target, source map[string]any) {
	for key, value := range source {
		if nested, ok := value.(map[string]any); ok {
			targetNested, _ := target[key].(map[string]any)
			if targetNested == nil {
				targetNested = map[string]any{}
				target[key] = targetNested
			}
			mergeUsage(targetNested, nested)
			continue
		}
		target[key] = value
	}
}

func responseDocuments(body []byte, contentType string) []map[string]any {
	var document map[string]any
	if json.Unmarshal(body, &document) == nil {
		return []map[string]any{document}
	}
	if !strings.Contains(strings.ToLower(contentType), "text/event-stream") && !bytes.Contains(body, []byte("data:")) {
		return nil
	}
	documents := make([]map[string]any, 0)
	for _, line := range bytes.Split(body, []byte("\n")) {
		line = bytes.TrimSpace(line)
		if !bytes.HasPrefix(line, []byte("data:")) {
			continue
		}
		payload := bytes.TrimSpace(bytes.TrimPrefix(line, []byte("data:")))
		if bytes.Equal(payload, []byte("[DONE]")) || len(payload) == 0 {
			continue
		}
		var event map[string]any
		if json.Unmarshal(payload, &event) == nil {
			documents = append(documents, event)
		}
	}
	return documents
}

func responseModelName(body []byte, contentType string) string {
	model := ""
	for _, document := range responseDocuments(body, contentType) {
		if value, ok := document["model"].(string); ok && value != "" {
			model = value
		}
		if message, ok := document["message"].(map[string]any); ok {
			if value, ok := message["model"].(string); ok && value != "" {
				model = value
			}
		}
	}
	return model
}

func firstNumber(document map[string]any, keys ...string) (any, bool) {
	for _, key := range keys {
		if value, ok := document[key]; ok {
			switch value.(type) {
			case float64, float32, int, int64, json.Number, string:
				return value, true
			}
		}
	}
	return nil, false
}

func toolCallCount(document map[string]any) int {
	count := 0
	choices, _ := document["choices"].([]any)
	for _, rawChoice := range choices {
		choice, _ := rawChoice.(map[string]any)
		for _, key := range []string{"message", "delta"} {
			message, _ := choice[key].(map[string]any)
			calls, _ := message["tool_calls"].([]any)
			count += len(calls)
		}
	}
	return count
}

func responseErrorType(document map[string]any) string {
	errorDocument, _ := document["error"].(map[string]any)
	for _, key := range []string{"type", "code"} {
		if value, ok := errorDocument[key].(string); ok && value != "" {
			return value
		}
	}
	return ""
}

func (p *Proxy) capture(ctx context.Context, content protocol.ContentCapture) {
	if len(content.Content) == 0 {
		content.Content = "{}"
	}
	if err := p.queue.Put(content.ObservationKey, content); err != nil {
		slog.Error("business content queue failed", "direction", content.Direction, "trace_id", content.TraceID, "error", err)
		return
	}
	go p.flush(context.WithoutCancel(ctx))
}

func (p *Proxy) flushLoop(ctx context.Context) {
	ticker := time.NewTicker(10 * time.Second)
	policyTicker := time.NewTicker(60 * time.Second)
	defer ticker.Stop()
	defer policyTicker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.flush(ctx)
		case <-policyTicker.C:
			if policy, err := p.client.ContentPolicy(ctx); err == nil {
				p.policyMu.Lock()
				p.policy = policy
				p.policyMu.Unlock()
				if !policy.ContentCaptureEnabled {
					slog.Warn("content capture authorization is disabled")
				}
			}
		}
	}
}

func (p *Proxy) currentPolicy() protocol.Policy {
	p.policyMu.RLock()
	defer p.policyMu.RUnlock()
	return p.policy
}

func (p *Proxy) flush(ctx context.Context) {
	p.flushMu.Lock()
	defer p.flushMu.Unlock()
	files, err := p.queue.Files()
	if err != nil {
		return
	}
	for _, path := range files {
		var content protocol.ContentCapture
		if err := p.queue.Read(path, &content); err != nil {
			_ = p.queue.Delete(path)
			continue
		}
		requestCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 20*time.Second)
		err := p.client.Capture(requestCtx, content)
		cancel()
		if err != nil {
			return
		}
		_ = p.queue.Delete(path)
	}
}

type contextKey struct{}

type captureContext struct {
	traceID       string
	model         string
	usagePoolKey  string
	sampled       bool
	startedAt     time.Time
	method        string
	path          string
	agentID       int64
	captureReason string
}

func captureFrom(ctx context.Context) *captureContext {
	value, _ := ctx.Value(contextKey{}).(*captureContext)
	if value == nil {
		return &captureContext{}
	}
	return value
}

type captureReadCloser struct {
	source    io.ReadCloser
	buffer    bytes.Buffer
	maxBytes  int64
	truncated bool
	complete  bool
	onDone    func([]byte, bool)
	once      sync.Once
}

func newCaptureReadCloser(source io.ReadCloser, maxBytes int64, onDone func([]byte, bool)) io.ReadCloser {
	return &captureReadCloser{source: source, maxBytes: maxBytes, onDone: onDone}
}

func (reader *captureReadCloser) Read(target []byte) (int, error) {
	n, err := reader.source.Read(target)
	if n > 0 {
		remaining := reader.maxBytes - int64(reader.buffer.Len())
		if remaining > 0 {
			captured := int64(n)
			if captured > remaining {
				captured = remaining
				reader.truncated = true
			}
			_, _ = reader.buffer.Write(target[:captured])
		} else {
			reader.truncated = true
		}
	}
	if errors.Is(err, io.EOF) {
		reader.complete = true
		reader.finish()
	}
	return n, err
}

func (reader *captureReadCloser) Close() error {
	err := reader.source.Close()
	if !reader.complete {
		reader.truncated = true
	}
	reader.finish()
	return err
}

func (reader *captureReadCloser) finish() {
	reader.once.Do(func() {
		reader.onDone(reader.buffer.Bytes(), reader.truncated)
	})
}

func modelName(body []byte) string {
	var document map[string]any
	if json.Unmarshal(body, &document) != nil {
		return ""
	}
	if value, ok := document["model"].(string); ok {
		return value
	}
	return ""
}

func sample(rate float64) bool {
	if rate <= 0 {
		return false
	}
	if rate >= 1 {
		return true
	}
	return mathrand.Float64() < rate
}

func randomID() string {
	value := make([]byte, 16)
	_, _ = rand.Read(value)
	return hex.EncodeToString(value)
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func truthy(value string) bool {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}

func attributionMode(agentID int64) string {
	if agentID > 0 {
		return "EXPLICIT"
	}
	return "ENDPOINT_INFERRED"
}

func attributionConfidence(agentID int64) int {
	if agentID > 0 {
		return 100
	}
	return 60
}
