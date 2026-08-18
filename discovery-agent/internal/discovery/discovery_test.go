package discovery

import (
	"encoding/json"
	"github.com/matrix-eap/discovery-agent/internal/protocol"
	"testing"
)

func TestExtractsMCPServerNamesWithoutUploadingConfiguration(t *testing.T) {
	names := extractNames(`{"mcpServers":{"filesystem":{"command":"npx"},"postgres":{"command":"uvx"}}}`, []string{"mcpServers"})
	if len(names) != 2 || names[0] != "filesystem" || names[1] != "postgres" {
		t.Fatalf("unexpected names %#v", names)
	}
}

func TestDockerServiceEndpointsUsePublishedHostPorts(t *testing.T) {
	var document dockerInspect
	err := json.Unmarshal([]byte(`{"NetworkSettings":{"Ports":{"8090/tcp":[{"HostIp":"::","HostPort":"8090"}]}}}`), &document)
	if err != nil {
		t.Fatal(err)
	}
	endpoints := dockerServiceEndpoints(document, "VM-0-15-ubuntu")
	if len(endpoints) != 1 || endpoints[0] != "tcp://VM-0-15-ubuntu:8090" {
		t.Fatalf("unexpected endpoints %#v", endpoints)
	}
}

func TestDockerInfrastructureClassification(t *testing.T) {
	if !dockerInfrastructureServices["frontend"] || !dockerInfrastructureServices["mysql"] {
		t.Fatal("expected common supporting services to be classified as infrastructure")
	}
	if dockerInfrastructureServices["backend"] || !dockerBackendServices["backend"] {
		t.Fatal("backend must remain eligible for agent workload detection")
	}
}

func TestShellsAndAdministrativeCommandsAreNotAgentProcesses(t *testing.T) {
	for _, name := range []string{"bash", "sh", "sudo", "ssh", "curl", "jq"} {
		if !ignoredProcessNames[name] {
			t.Fatalf("expected %s to be excluded from process discovery", name)
		}
	}
	if ignoredProcessNames["node"] || ignoredProcessNames["python"] {
		t.Fatal("agent runtimes must remain eligible for discovery")
	}
}

func TestCentralSignatureCatalogRecognizesHermes(t *testing.T) {
	catalogue := effectiveSignatures([]protocol.AgentSignature{{
		Product: "Hermes Agent", Vendor: "Nous Research", Framework: "Hermes Agent",
		ProcessMarkers: []string{"hermes-agent"}, HomeDirectories: []string{".hermes"},
	}})
	matched, ok := matchSignature("/opt/hermes-agent/bin/hermes gateway", catalogue)
	if !ok || matched.Product != "Hermes Agent" {
		t.Fatalf("unexpected signature %#v, matched=%v", matched, ok)
	}
}

func TestProfilesHaveStableDistinctIdentities(t *testing.T) {
	first := profileIdentity("python /home/alice/.hermes/profiles/research/config.yaml", "host-1")
	second := profileIdentity("python /home/alice/.hermes/profiles/support/config.yaml", "host-1")
	if first == second || first != "profile:host-1:hermes:research" || second != "profile:host-1:hermes:support" {
		t.Fatalf("unexpected identities %q and %q", first, second)
	}
}

func TestGenericAgentProcessRequiresExplicitAgentSignal(t *testing.T) {
	if !looksGenericAgentProcess("python /srv/custom/agent-worker.py") {
		t.Fatal("expected explicit agent worker to qualify as unknown agent")
	}
	if looksGenericAgentProcess("python app.py calling api.openai.com") {
		t.Fatal("model API flow alone must not qualify as an agent")
	}
}
