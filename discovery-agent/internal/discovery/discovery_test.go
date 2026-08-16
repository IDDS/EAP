package discovery

import (
	"encoding/json"
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
