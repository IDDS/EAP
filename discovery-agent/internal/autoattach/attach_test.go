package autoattach

import (
	"strings"
	"testing"
)

func TestRenderOverrideContainsOnlyScopedInstrumentationConfiguration(t *testing.T) {
	plan := Plan{
		ComposeService: "backend", InstrumentationDirectory: "/var/lib/eap/instrumentation",
		InstrumentationTokenFile: "/var/lib/eap/token", CollectorURL: "http://host.docker.internal:8687",
		Provider: "deepseek", AgentID: 4, UsagePoolKey: "shared-deepseek-production", PythonPath: "/opt/eap-observability",
	}
	document := renderOverride(plan)
	for _, expected := range []string{
		"/opt/eap-observability", "EAP_CONTENT_AUTOINSTRUMENT", "host.docker.internal:host-gateway",
		"EAP_INSTRUMENTATION_VERSION", "shared-deepseek-production", "/var/run/eap/instrumentation-token",
	} {
		if !strings.Contains(document, expected) {
			t.Fatalf("override is missing %q: %s", expected, document)
		}
	}
	for _, forbidden := range []string{"API_KEY", "DEEPSEEK_API_KEY", "OPENAI_API_KEY"} {
		if strings.Contains(document, forbidden) {
			t.Fatalf("override exposes provider credential key %q", forbidden)
		}
	}
}

func TestAttachedStateRequiresMatchingScopedAttribution(t *testing.T) {
	plan := Plan{
		CollectorURL: "http://host.docker.internal:8687", Provider: "deepseek",
		AgentID: 4, UsagePoolKey: "shared-deepseek-production",
	}
	environment := []string{
		"PYTHONPATH=/opt/eap-observability:/app",
		"EAP_CONTENT_AUTOINSTRUMENT=true",
		"EAP_INSTRUMENTATION_VERSION=" + instrumentationVersion,
		"EAP_INSTRUMENTATION_URL=http://host.docker.internal:8687",
		"EAP_INSTRUMENTATION_TOKEN_FILE=/var/run/eap/instrumentation-token",
		"EAP_INSTRUMENTATION_PROVIDER=deepseek",
		"EAP_INSTRUMENTATION_AGENT_ID=4",
		"EAP_INSTRUMENTATION_USAGE_POOL_KEY=shared-deepseek-production",
	}
	if !isAttached(environment, plan) {
		t.Fatal("expected matching instrumentation to be idempotent")
	}
	environment[len(environment)-1] = "EAP_INSTRUMENTATION_USAGE_POOL_KEY=wrong-pool"
	if isAttached(environment, plan) {
		t.Fatal("expected attribution change to require restart")
	}
}

func TestMergedPythonPathDoesNotDuplicateInstrumentationDirectory(t *testing.T) {
	value := mergedPythonPath("/opt/eap-observability:/app:/opt/eap-observability")
	if value != "/opt/eap-observability:/app" {
		t.Fatalf("unexpected PYTHONPATH %q", value)
	}
}

func TestSplitComposeFilesSupportsDockerLabelFormats(t *testing.T) {
	files := splitComposeFiles("/srv/compose.yml,/srv/compose.prod.yml")
	if len(files) != 2 || files[0] != "/srv/compose.yml" || files[1] != "/srv/compose.prod.yml" {
		t.Fatalf("unexpected compose files: %#v", files)
	}
}

func TestWithoutFileRemovesPreviouslyAppliedOverride(t *testing.T) {
	files := withoutFile([]string{"/srv/compose.yml", "/srv/docker-compose.eap-observability.yml"}, "/srv/docker-compose.eap-observability.yml")
	if len(files) != 1 || files[0] != "/srv/compose.yml" {
		t.Fatalf("unexpected compose files: %#v", files)
	}
}

func TestResolveCollectorURLUsesContainerNetworkGateway(t *testing.T) {
	document := dockerInspect{}
	document.NetworkSettings.Networks = map[string]struct {
		Gateway string `json:"Gateway"`
	}{"boss_default": {Gateway: "172.18.0.1"}}
	if got := resolveCollectorURL("", document); got != "http://172.18.0.1:8687" {
		t.Fatalf("expected Docker gateway collector URL, got %q", got)
	}
}

func TestResolveCollectorURLKeepsExplicitConfiguration(t *testing.T) {
	document := dockerInspect{}
	document.NetworkSettings.Networks = map[string]struct {
		Gateway string `json:"Gateway"`
	}{"boss_default": {Gateway: "172.18.0.1"}}
	if got := resolveCollectorURL("https://collector.example/eap/", document); got != "https://collector.example/eap" {
		t.Fatalf("expected explicit collector URL, got %q", got)
	}
}

func TestResolveCollectorURLFallsBackForMissingGateway(t *testing.T) {
	if got := resolveCollectorURL("", dockerInspect{}); got != "http://host.docker.internal:8687" {
		t.Fatalf("expected portable collector fallback, got %q", got)
	}
}
