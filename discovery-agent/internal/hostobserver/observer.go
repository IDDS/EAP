package hostobserver

import (
	"context"
	"encoding/csv"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"

	"github.com/matrix-eap/discovery-agent/internal/protocol"
)

const SensorType = "PROCESS_NETWORK_SCAN"

var (
	ssProcessPattern = regexp.MustCompile(`users:\(\(\"([^\"]+)\",pid=([0-9]+)`)
	containerPattern = regexp.MustCompile(`(?i)([0-9a-f]{64})`)
	agentMarkers     = []string{
		"boss-agent", "helloboss", "agent-worker", "ai-agent", "agent.exe", "agent.py",
		"langchain", "langgraph", "crewai", "autogen",
		"semantic-kernel", "semantic_kernel", "openai-agents", "dify", "flowise", "langflow",
		"codex", "claude", "gemini", "copilot", "mastra",
	}
	ignoredMarkers = []string{
		"eap-discovery-agent", "eap-content-proxy", "tat_agent", "/qcloud/", "node_exporter",
		"telegraf", "otelcol", "opentelemetry-collector",
	}
)

type Result struct {
	SensorType   string                            `json:"sensorType"`
	Observations []protocol.HostNetworkObservation `json:"observations"`
	Warnings     []string                          `json:"warnings,omitempty"`
}

type socketRecord struct {
	ProcessName   string
	ProcessID     int64
	LocalAddress  string
	LocalPort     int
	RemoteAddress string
	RemotePort    int
}

type dockerWorkload struct {
	ID      string
	Name    string
	Image   string
	Project string
	Service string
}

func Collect(ctx context.Context, maxObservations int) Result {
	result := Result{SensorType: SensorType}
	if maxObservations <= 0 || maxObservations > 1000 {
		maxObservations = 1000
	}
	var records []socketRecord
	var err error
	switch runtime.GOOS {
	case "linux":
		var output []byte
		output, err = exec.CommandContext(ctx, "ss", "-Hntp").Output()
		if err == nil {
			records = parseSS(string(output))
		}
	case "darwin":
		var output []byte
		output, err = exec.CommandContext(ctx, "lsof", "-nP", "-iTCP", "-sTCP:ESTABLISHED", "-Fpcn").Output()
		if err == nil {
			records = parseLsof(string(output))
		}
	case "windows":
		var connections, tasks []byte
		connections, err = exec.CommandContext(ctx, "netstat", "-ano", "-p", "tcp").Output()
		if err == nil {
			tasks, _ = exec.CommandContext(ctx, "tasklist", "/FO", "CSV", "/NH").Output()
			records = parseNetstat(string(connections), parseTasklist(string(tasks)))
		}
	default:
		return Result{SensorType: SensorType, Warnings: []string{"host observer does not support " + runtime.GOOS}}
	}
	if err != nil {
		return Result{SensorType: SensorType, Warnings: []string{"host network snapshot unavailable: " + err.Error()}}
	}

	host, _ := os.Hostname()
	workloads := map[string]dockerWorkload{}
	if runtime.GOOS == "linux" {
		workloads = dockerWorkloads(ctx)
	}
	for _, record := range records {
		if len(result.Observations) >= maxObservations {
			result.Warnings = append(result.Warnings, fmt.Sprintf("host observations truncated to %d", maxObservations))
			break
		}
		observation, ok := enrich(record, host, workloads)
		if ok {
			result.Observations = append(result.Observations, observation)
		}
	}
	return result
}

func enrich(record socketRecord, host string, workloads map[string]dockerWorkload) (protocol.HostNetworkObservation, bool) {
	processName := record.ProcessName
	executable := ""
	cmdline := ""
	containerID := ""
	cgroupID := ""
	identity := ""
	if runtime.GOOS == "linux" && record.ProcessID > 0 {
		pid := strconv.FormatInt(record.ProcessID, 10)
		executable, _ = os.Readlink(filepath.Join("/proc", pid, "exe"))
		cmdline = strings.ReplaceAll(readText(filepath.Join("/proc", pid, "cmdline"), 8192), "\x00", " ")
		cgroup := readText(filepath.Join("/proc", pid, "cgroup"), 8192)
		if match := containerPattern.FindStringSubmatch(cgroup); len(match) == 2 {
			containerID = strings.ToLower(match[1])
			cgroupID = containerID
			if workload, found := workloads[containerID]; found {
				processName = firstNonBlank(workload.Name, processName)
				identity = dockerIdentity(host, workload)
				cmdline += " " + workload.Name + " " + workload.Image + " " + workload.Project + " " + workload.Service
			}
		}
	}
	signal := strings.ToLower(strings.Join([]string{processName, executable, cmdline}, " "))
	if !looksAgent(signal) {
		return protocol.HostNetworkObservation{}, false
	}
	if identity == "" {
		identity = "process:" + host + ":" + firstNonBlank(executable, processName)
	}
	protocolHint := "TCP"
	if record.RemotePort == 443 {
		protocolHint = "HTTPS"
	}
	return protocol.HostNetworkObservation{
		ProcessName: processName, ProcessID: record.ProcessID, ExecutablePath: executable,
		ContainerID: containerID, CgroupID: cgroupID, WorkloadUID: containerID,
		LocalAddress: record.LocalAddress, LocalPort: record.LocalPort,
		RemoteAddress: record.RemoteAddress, RemotePort: record.RemotePort,
		Transport: "TCP", Direction: "OUTBOUND", ProtocolHint: protocolHint, IdentityKey: identity,
	}, true
}

func parseSS(output string) []socketRecord {
	var result []socketRecord
	for _, line := range strings.Split(output, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 || !strings.EqualFold(fields[0], "ESTAB") {
			continue
		}
		localAddress, localPort, localOK := splitEndpoint(fields[3])
		remoteAddress, remotePort, remoteOK := splitEndpoint(fields[4])
		if !localOK || !remoteOK {
			continue
		}
		record := socketRecord{LocalAddress: localAddress, LocalPort: localPort, RemoteAddress: remoteAddress, RemotePort: remotePort}
		if match := ssProcessPattern.FindStringSubmatch(line); len(match) == 3 {
			record.ProcessName = match[1]
			record.ProcessID, _ = strconv.ParseInt(match[2], 10, 64)
		}
		result = append(result, record)
	}
	return result
}

func parseLsof(output string) []socketRecord {
	var result []socketRecord
	var pid int64
	process := ""
	for _, line := range strings.Split(output, "\n") {
		if len(line) < 2 {
			continue
		}
		switch line[0] {
		case 'p':
			pid, _ = strconv.ParseInt(line[1:], 10, 64)
		case 'c':
			process = line[1:]
		case 'n':
			parts := strings.SplitN(line[1:], "->", 2)
			if len(parts) != 2 {
				continue
			}
			localAddress, localPort, localOK := splitEndpoint(parts[0])
			remoteAddress, remotePort, remoteOK := splitEndpoint(strings.Fields(parts[1])[0])
			if localOK && remoteOK {
				result = append(result, socketRecord{ProcessName: process, ProcessID: pid,
					LocalAddress: localAddress, LocalPort: localPort, RemoteAddress: remoteAddress, RemotePort: remotePort})
			}
		}
	}
	return result
}

func parseNetstat(output string, processes map[int64]string) []socketRecord {
	var result []socketRecord
	for _, line := range strings.Split(output, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 || !strings.EqualFold(fields[0], "TCP") || !strings.EqualFold(fields[3], "ESTABLISHED") {
			continue
		}
		localAddress, localPort, localOK := splitEndpoint(fields[1])
		remoteAddress, remotePort, remoteOK := splitEndpoint(fields[2])
		pid, pidErr := strconv.ParseInt(fields[4], 10, 64)
		if !localOK || !remoteOK || pidErr != nil {
			continue
		}
		result = append(result, socketRecord{ProcessName: processes[pid], ProcessID: pid,
			LocalAddress: localAddress, LocalPort: localPort, RemoteAddress: remoteAddress, RemotePort: remotePort})
	}
	return result
}

func parseTasklist(output string) map[int64]string {
	result := map[int64]string{}
	reader := csv.NewReader(strings.NewReader(output))
	reader.FieldsPerRecord = -1
	for {
		row, err := reader.Read()
		if err != nil {
			break
		}
		if len(row) < 2 {
			continue
		}
		pid, err := strconv.ParseInt(strings.TrimSpace(row[1]), 10, 64)
		if err == nil {
			result[pid] = strings.TrimSpace(row[0])
		}
	}
	return result
}

func splitEndpoint(value string) (string, int, bool) {
	value = strings.TrimSpace(strings.TrimSuffix(value, ","))
	if host, port, err := net.SplitHostPort(value); err == nil {
		parsed, parseErr := strconv.Atoi(port)
		return strings.Trim(host, "[]"), parsed, parseErr == nil
	}
	index := strings.LastIndex(value, ":")
	if index <= 0 || index == len(value)-1 {
		return "", 0, false
	}
	port, err := strconv.Atoi(value[index+1:])
	if err != nil {
		return "", 0, false
	}
	return strings.Trim(value[:index], "[]"), port, true
}

func dockerWorkloads(ctx context.Context) map[string]dockerWorkload {
	format := `{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Label "com.docker.compose.project"}}\t{{.Label "com.docker.compose.service"}}`
	output, err := exec.CommandContext(ctx, "docker", "ps", "--no-trunc", "--format", format).Output()
	if err != nil {
		return map[string]dockerWorkload{}
	}
	result := map[string]dockerWorkload{}
	for _, line := range strings.Split(string(output), "\n") {
		fields := strings.Split(line, "\t")
		if len(fields) < 5 || len(fields[0]) != 64 {
			continue
		}
		workload := dockerWorkload{ID: strings.ToLower(fields[0]), Name: fields[1], Image: fields[2], Project: fields[3], Service: fields[4]}
		result[workload.ID] = workload
	}
	return result
}

func dockerIdentity(host string, workload dockerWorkload) string {
	return "docker:" + host + ":" + firstNonBlank(workload.Project, workload.Name) + ":" + firstNonBlank(workload.Service, workload.Name)
}

func looksAgent(signal string) bool {
	for _, marker := range ignoredMarkers {
		if strings.Contains(signal, marker) {
			return false
		}
	}
	for _, marker := range agentMarkers {
		if strings.Contains(signal, marker) {
			return true
		}
	}
	return false
}

func readText(path string, limit int64) string {
	file, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer file.Close()
	data := make([]byte, limit)
	count, _ := file.Read(data)
	return strings.TrimSpace(string(data[:count]))
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
