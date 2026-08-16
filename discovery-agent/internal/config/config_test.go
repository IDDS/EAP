package config

import "testing"

func TestPlainHTTPRequiresExplicitTestOverride(t *testing.T) {
	cfg := Config{ServerURL: "http://eap.example", StateDir: "/tmp/eap-test"}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected plain HTTP to be rejected")
	}
	cfg.AllowInsecureHTTP = true
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected explicit test override: %v", err)
	}
}

func TestEnabledAutoAttachTargetRequiresCompleteAttribution(t *testing.T) {
	cfg := Config{
		ServerURL: "https://eap.example", StateDir: "/tmp/eap-test",
		AutoAttach: []AutoAttachTarget{{Enabled: true, Target: "boss-agent-backend"}},
	}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected incomplete enabled auto-attach target to be rejected")
	}
	cfg.AutoAttach[0] = AutoAttachTarget{
		Enabled: true, Target: "boss-agent-backend", Profile: "litellm", AgentID: 4,
		UsagePoolKey: "shared-deepseek-production", Provider: "deepseek",
		CollectorURL:             "http://host.docker.internal:8687",
		InstrumentationTokenFile: "/var/lib/eap-content-proxy/helloboss/instrumentation-token",
		ProxyInstance:            "helloboss",
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected complete target to be accepted: %v", err)
	}
}

func TestDisabledAutoAttachTargetMayBeAPlaceholder(t *testing.T) {
	cfg := Config{
		ServerURL: "https://eap.example", StateDir: "/tmp/eap-test",
		AutoAttach: []AutoAttachTarget{{Enabled: false}},
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected disabled placeholder to be ignored: %v", err)
	}
}

func TestHostObserverModeValidation(t *testing.T) {
	cfg := Config{ServerURL: "https://eap.example", StateDir: "/tmp/eap-test",
		HostObserver: HostObserverConfig{Mode: "kernel-magic", MaxObservations: 100}}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected unsupported host observer mode to be rejected")
	}
	cfg.HostObserver = HostObserverConfig{Mode: "auto", MaxObservations: 1000}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected automatic host observer mode: %v", err)
	}
}

func TestEbpfObserverValidation(t *testing.T) {
	cfg := Config{ServerURL: "https://eap.example", StateDir: "/tmp/eap-test",
		EbpfObserver: EbpfObserverConfig{Mode: "force", BatchIntervalSeconds: 30, MaxEvents: 1000}}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected unsupported eBPF observer mode to be rejected")
	}
	cfg.EbpfObserver = EbpfObserverConfig{Mode: "auto", BatchIntervalSeconds: 4, MaxEvents: 1000}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected too-small eBPF batch interval to be rejected")
	}
	cfg.EbpfObserver = EbpfObserverConfig{Mode: "auto", BatchIntervalSeconds: 30, MaxEvents: 1000}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected automatic eBPF observer config: %v", err)
	}
}
