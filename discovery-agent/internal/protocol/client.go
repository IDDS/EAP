package protocol

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Client struct {
	baseURL    string
	credential string
	authHeader string
	http       *http.Client
}

func NewClient(baseURL, credential, proxyURL string) (*Client, error) {
	return newClient(baseURL, credential, proxyURL, "X-EAP-Agent-Credential")
}

func NewContentClient(baseURL, credential, proxyURL string) (*Client, error) {
	return newClient(baseURL, credential, proxyURL, "X-EAP-Content-Credential")
}

func newClient(baseURL, credential, proxyURL, authHeader string) (*Client, error) {
	parsed, err := url.Parse(strings.TrimRight(baseURL, "/"))
	if err != nil || parsed.Host == "" {
		return nil, errors.New("invalid EAP server URL")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	if strings.TrimSpace(proxyURL) != "" {
		proxy, err := url.Parse(proxyURL)
		if err != nil {
			return nil, fmt.Errorf("invalid proxy URL: %w", err)
		}
		transport.Proxy = http.ProxyURL(proxy)
	}
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"), credential: credential, authHeader: authHeader,
		http: &http.Client{Timeout: 30 * time.Second, Transport: transport},
	}, nil
}

func (c *Client) WithCredential(value string) { c.credential = value }

func (c *Client) Enroll(ctx context.Context, request EnrollRequest) (EnrollResponse, error) {
	return post[EnrollRequest, EnrollResponse](ctx, c, "/api/discovery/v1/agent/enroll", request, false)
}

func (c *Client) Heartbeat(ctx context.Context, request HeartbeatRequest) (HeartbeatResponse, error) {
	return post[HeartbeatRequest, HeartbeatResponse](ctx, c, "/api/discovery/v1/agent/heartbeat", request, true)
}

func (c *Client) Policy(ctx context.Context) (Policy, error) {
	var envelope Envelope[Policy]
	if err := c.do(ctx, http.MethodGet, "/api/discovery/v1/agent/policy", nil, true, &envelope); err != nil {
		return Policy{}, err
	}
	if !envelope.Success {
		return Policy{}, errors.New(envelope.Message)
	}
	return envelope.Data, nil
}

func (c *Client) ContentPolicy(ctx context.Context) (Policy, error) {
	var envelope Envelope[Policy]
	if err := c.do(ctx, http.MethodGet, "/api/discovery/v1/agent/content-policy", nil, true, &envelope); err != nil {
		return Policy{}, err
	}
	if !envelope.Success {
		return Policy{}, errors.New(envelope.Message)
	}
	return envelope.Data, nil
}

func (c *Client) Upload(ctx context.Context, request ObservationBatch) (BatchResponse, error) {
	return post[ObservationBatch, BatchResponse](ctx, c, "/api/discovery/v1/agent/observations", request, true)
}

func (c *Client) UploadHostObservations(ctx context.Context, request HostObservationRequest) (HostObservationResponse, error) {
	return post[HostObservationRequest, HostObservationResponse](ctx, c, "/api/discovery/v1/agent/host-observations", request, true)
}

func (c *Client) Capture(ctx context.Context, request ContentCapture) error {
	_, err := post[ContentCapture, map[string]any](ctx, c, "/api/discovery/v1/agent/content", request, true)
	return err
}

func (c *Client) Uninstall(ctx context.Context) error {
	var envelope Envelope[any]
	return c.do(ctx, http.MethodPost, "/api/discovery/v1/agent/uninstall", []byte("{}"), true, &envelope)
}

func post[Req any, Res any](ctx context.Context, c *Client, path string, payload Req, auth bool) (Res, error) {
	var zero Res
	body, err := json.Marshal(payload)
	if err != nil {
		return zero, err
	}
	var envelope Envelope[Res]
	if err := c.do(ctx, http.MethodPost, path, body, auth, &envelope); err != nil {
		return zero, err
	}
	if !envelope.Success {
		return zero, errors.New(envelope.Message)
	}
	return envelope.Data, nil
}

func (c *Client) do(ctx context.Context, method, path string, body []byte, auth bool, target any) error {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, reader)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "eap-discovery-agent/1.0.0")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if auth {
		if c.credential == "" {
			return errors.New("machine credential is missing")
		}
		req.Header.Set(c.authHeader, c.credential)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	data, err := io.ReadAll(io.LimitReader(res.Body, 8*1024*1024))
	if err != nil {
		return err
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		var apiError Envelope[any]
		if json.Unmarshal(data, &apiError) == nil && apiError.Message != "" {
			return errors.New(apiError.Message)
		}
		return fmt.Errorf("EAP returned HTTP %d", res.StatusCode)
	}
	if err := json.Unmarshal(data, target); err != nil {
		return fmt.Errorf("decode EAP response: %w", err)
	}
	return nil
}
