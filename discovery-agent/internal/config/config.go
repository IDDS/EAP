package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

type Config struct {
	ServerURL         string             `json:"server_url"`
	EnrollmentToken   string             `json:"enrollment_token,omitempty"`
	AllowInsecureHTTP bool               `json:"allow_insecure_http"`
	StateDir          string             `json:"state_dir"`
	ContentStateDir   string             `json:"content_state_dir"`
	ProxyURL          string             `json:"proxy_url,omitempty"`
	Scan              ScanConfig         `json:"scan"`
	ContentProxy      ContentConfig      `json:"content_proxy"`
	AutoAttach        []AutoAttachTarget `json:"auto_attach,omitempty"`
	HostObserver      HostObserverConfig `json:"host_observer"`
	EbpfObserver      EbpfObserverConfig `json:"ebpf_observer"`
}

type ScanConfig struct {
	Processes bool     `json:"processes"`
	Docker    bool     `json:"docker"`
	Ports     bool     `json:"ports"`
	Files     bool     `json:"files"`
	Roots     []string `json:"roots"`
	MaxFiles  int      `json:"max_files"`
	MaxDepth  int      `json:"max_depth"`
}

type ContentConfig struct {
	Listen                   string `json:"listen"`
	UpstreamURL              string `json:"upstream_url"`
	Provider                 string `json:"provider"`
	UsagePoolKey             string `json:"usage_pool_key,omitempty"`
	ModelHeader              string `json:"model_header,omitempty"`
	AgentID                  int64  `json:"agent_id,omitempty"`
	MaxCaptureBytes          int64  `json:"max_capture_bytes"`
	InstrumentationTokenFile string `json:"instrumentation_token_file,omitempty"`
}

type AutoAttachTarget struct {
	Enabled                  bool   `json:"enabled"`
	Target                   string `json:"target"`
	Profile                  string `json:"profile"`
	AgentID                  int64  `json:"agent_id"`
	UsagePoolKey             string `json:"usage_pool_key"`
	Provider                 string `json:"provider,omitempty"`
	CollectorURL             string `json:"collector_url"`
	InstrumentationTokenFile string `json:"instrumentation_token_file"`
	ProxyInstance            string `json:"proxy_instance"`
}

type HostObserverConfig struct {
	Mode            string `json:"mode"`
	MaxObservations int    `json:"max_observations"`
}

type EbpfObserverConfig struct {
	Mode                 string `json:"mode"`
	BatchIntervalSeconds int    `json:"batch_interval_seconds"`
	MaxEvents            int    `json:"max_events"`
}

func DefaultPath() string {
	if value := strings.TrimSpace(os.Getenv("EAP_DISCOVERY_AGENT_CONFIG")); value != "" {
		return value
	}
	if runtime.GOOS == "windows" {
		return filepath.Join(os.Getenv("ProgramData"), "EAP", "DiscoveryAgent", "config.json")
	}
	return "/etc/eap-discovery-agent/config.json"
}

func Load(path string) (Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return Config{}, fmt.Errorf("read config: %w", err)
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return Config{}, fmt.Errorf("parse config: %w", err)
	}
	applyDefaults(&cfg)
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func Save(path string, cfg Config) error {
	applyDefaults(&cfg)
	if err := cfg.Validate(); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o600)
}

func (c Config) Validate() error {
	if strings.TrimSpace(c.ServerURL) == "" {
		return errors.New("server_url is required")
	}
	if strings.HasPrefix(strings.ToLower(c.ServerURL), "http://") && !c.AllowInsecureHTTP {
		return errors.New("plain HTTP is disabled; use HTTPS or set allow_insecure_http only for testing")
	}
	if c.StateDir == "" {
		return errors.New("state_dir is required")
	}
	if c.ContentStateDir == "" {
		if runtime.GOOS == "windows" {
			c.ContentStateDir = filepath.Join(os.Getenv("ProgramData"), "EAP", "ContentProxy", "state")
		} else {
			c.ContentStateDir = "/var/lib/eap-content-proxy"
		}
	}
	for index, target := range c.AutoAttach {
		if !target.Enabled {
			continue
		}
		prefix := fmt.Sprintf("auto_attach[%d]", index)
		if strings.TrimSpace(target.Target) == "" {
			return fmt.Errorf("%s.target is required", prefix)
		}
		if target.AgentID <= 0 {
			return fmt.Errorf("%s.agent_id must be greater than zero", prefix)
		}
		if strings.TrimSpace(target.UsagePoolKey) == "" {
			return fmt.Errorf("%s.usage_pool_key is required", prefix)
		}
		if strings.TrimSpace(target.CollectorURL) == "" {
			return fmt.Errorf("%s.collector_url is required", prefix)
		}
		if !filepath.IsAbs(strings.TrimSpace(target.InstrumentationTokenFile)) {
			return fmt.Errorf("%s.instrumentation_token_file must be absolute", prefix)
		}
		if strings.TrimSpace(target.ProxyInstance) == "" {
			return fmt.Errorf("%s.proxy_instance is required", prefix)
		}
	}
	if c.HostObserver.Mode != "" && c.HostObserver.Mode != "auto" && c.HostObserver.Mode != "off" {
		return errors.New("host_observer.mode must be auto or off")
	}
	if c.HostObserver.MaxObservations != 0 && (c.HostObserver.MaxObservations < 1 || c.HostObserver.MaxObservations > 1000) {
		return errors.New("host_observer.max_observations must be between 1 and 1000")
	}
	if c.EbpfObserver.Mode != "" && c.EbpfObserver.Mode != "auto" && c.EbpfObserver.Mode != "off" {
		return errors.New("ebpf_observer.mode must be auto or off")
	}
	if c.EbpfObserver.BatchIntervalSeconds != 0 && (c.EbpfObserver.BatchIntervalSeconds < 5 || c.EbpfObserver.BatchIntervalSeconds > 300) {
		return errors.New("ebpf_observer.batch_interval_seconds must be between 5 and 300")
	}
	if c.EbpfObserver.MaxEvents != 0 && (c.EbpfObserver.MaxEvents < 1 || c.EbpfObserver.MaxEvents > 1000) {
		return errors.New("ebpf_observer.max_events must be between 1 and 1000")
	}
	return nil
}

func applyDefaults(c *Config) {
	c.ServerURL = strings.TrimRight(strings.TrimSpace(c.ServerURL), "/")
	if c.StateDir == "" {
		if runtime.GOOS == "windows" {
			c.StateDir = filepath.Join(os.Getenv("ProgramData"), "EAP", "DiscoveryAgent", "state")
		} else {
			c.StateDir = "/var/lib/eap-discovery-agent"
		}
	}
	if c.Scan.MaxFiles == 0 {
		c.Scan.MaxFiles = 3000
	}
	if c.Scan.MaxDepth == 0 {
		c.Scan.MaxDepth = 6
	}
	if !c.Scan.Processes && !c.Scan.Docker && !c.Scan.Ports && !c.Scan.Files {
		c.Scan.Processes, c.Scan.Docker, c.Scan.Ports = true, true, true
	}
	if c.ContentProxy.Listen == "" {
		c.ContentProxy.Listen = "127.0.0.1:8686"
	}
	if c.ContentProxy.Provider == "" {
		c.ContentProxy.Provider = "openai-compatible"
	}
	if c.ContentProxy.MaxCaptureBytes == 0 {
		c.ContentProxy.MaxCaptureBytes = 4 * 1024 * 1024
	}
	if c.HostObserver.Mode == "" {
		c.HostObserver.Mode = "auto"
	}
	if c.HostObserver.MaxObservations == 0 {
		c.HostObserver.MaxObservations = 1000
	}
	if c.EbpfObserver.Mode == "" {
		c.EbpfObserver.Mode = "auto"
	}
	if c.EbpfObserver.BatchIntervalSeconds == 0 {
		c.EbpfObserver.BatchIntervalSeconds = 30
	}
	if c.EbpfObserver.MaxEvents == 0 {
		c.EbpfObserver.MaxEvents = 1000
	}
}
