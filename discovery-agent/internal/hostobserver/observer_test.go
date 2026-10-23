package hostobserver

import "testing"

func TestParseLinuxSS(t *testing.T) {
	records := parseSS(`ESTAB 0 0 172.18.0.2:51234 104.18.1.1:443 users:(("boss-agent",pid=123,fd=7))`)
	if len(records) != 1 {
		t.Fatalf("unexpected records: %#v", records)
	}
	if records[0].ProcessName != "boss-agent" || records[0].ProcessID != 123 || records[0].RemotePort != 443 {
		t.Fatalf("unexpected record: %#v", records[0])
	}
}

func TestParseMacLsof(t *testing.T) {
	records := parseLsof("p321\nccodex\nn127.0.0.1:51000->1.2.3.4:443\n")
	if len(records) != 1 || records[0].ProcessName != "codex" || records[0].ProcessID != 321 {
		t.Fatalf("unexpected records: %#v", records)
	}
}

func TestParseWindowsNetstatAndTasklist(t *testing.T) {
	processes := parseTasklist("\"agent.exe\",\"456\",\"Console\",\"1\",\"10,000 K\"\n")
	records := parseNetstat("TCP 10.0.0.2:51000 1.2.3.4:443 ESTABLISHED 456\n", processes)
	if len(records) != 1 || records[0].ProcessName != "agent.exe" || records[0].ProcessID != 456 {
		t.Fatalf("unexpected records: %#v", records)
	}
}

func TestSplitEndpointSupportsIPv6(t *testing.T) {
	host, port, ok := splitEndpoint("[2001:db8::1]:443")
	if !ok || host != "2001:db8::1" || port != 443 {
		t.Fatalf("unexpected endpoint %q %d %t", host, port, ok)
	}
}

func TestAgentFilterAvoidsUnrelatedNetworkProcesses(t *testing.T) {
	if looksAgent("postgres database") {
		t.Fatal("infrastructure process should not be treated as an Agent")
	}
	if !looksAgent("boss-agent-backend python") {
		t.Fatal("Agent workload should be recognized")
	}
	if looksAgent("/usr/local/qcloud/tat_agent/tat_agent") {
		t.Fatal("cloud management agent should be ignored")
	}
	if looksAgent("/usr/local/bin/eap-discovery-agent") {
		t.Fatal("EAP collector should not observe itself as an Agent")
	}
}
