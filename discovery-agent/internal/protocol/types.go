package protocol

import "time"

type Envelope[T any] struct {
	Success bool   `json:"success"`
	Data    T      `json:"data"`
	Message string `json:"message"`
	TraceID string `json:"traceId"`
}

type Policy struct {
	PolicyVersion               string           `json:"policyVersion"`
	RulesVersion                string           `json:"rulesVersion"`
	HeartbeatIntervalSeconds    int              `json:"heartbeatIntervalSeconds"`
	ScanIntervalSeconds         int              `json:"scanIntervalSeconds"`
	FullSnapshotIntervalSeconds int              `json:"fullSnapshotIntervalSeconds"`
	OfflineQueueDays            int              `json:"offlineQueueDays"`
	OfflineQueueMaxBytes        int64            `json:"offlineQueueMaxBytes"`
	RequireHTTPS                bool             `json:"requireHttps"`
	EnabledDetectors            []string         `json:"enabledDetectors"`
	AgentSignatures             []AgentSignature `json:"agentSignatures"`
	ContentCaptureEnabled       bool             `json:"contentCaptureEnabled"`
	ContentAutoAttachEnabled    bool             `json:"contentAutoAttachEnabled"`
	ContentAutoAttachProfiles   []string         `json:"contentAutoAttachProfiles"`
	RawContentSampleRate        float64          `json:"rawContentSampleRate"`
	RawContentRetentionDays     int              `json:"rawContentRetentionDays"`
}

type AgentSignature struct {
	Product         string   `json:"product"`
	Vendor          string   `json:"vendor"`
	Framework       string   `json:"framework,omitempty"`
	ProcessMarkers  []string `json:"processMarkers"`
	ContentMarkers  []string `json:"contentMarkers"`
	PackageNames    []string `json:"packageNames"`
	HomeDirectories []string `json:"homeDirectories"`
	ManifestNames   []string `json:"manifestNames"`
}

type EnrollRequest struct {
	EnrollmentToken    string   `json:"enrollmentToken"`
	InstallationID     string   `json:"installationId"`
	MachineFingerprint string   `json:"machineFingerprint"`
	HostName           string   `json:"hostName"`
	AccountName        string   `json:"accountName,omitempty"`
	OperatingSystem    string   `json:"operatingSystem,omitempty"`
	Architecture       string   `json:"architecture,omitempty"`
	AgentVersion       string   `json:"agentVersion"`
	Capabilities       []string `json:"capabilities"`
}

type EnrollResponse struct {
	EndpointKey           string    `json:"endpointKey"`
	Credential            string    `json:"credential"`
	CredentialHint        string    `json:"credentialHint"`
	ContentCredential     string    `json:"contentCredential"`
	ContentCredentialHint string    `json:"contentCredentialHint"`
	Policy                Policy    `json:"policy"`
	EnrolledAt            time.Time `json:"enrolledAt"`
}

type HeartbeatRequest struct {
	InstallationID     string     `json:"installationId"`
	MachineFingerprint string     `json:"machineFingerprint"`
	AgentVersion       string     `json:"agentVersion"`
	PolicyVersion      string     `json:"policyVersion,omitempty"`
	RulesVersion       string     `json:"rulesVersion,omitempty"`
	QueueDepth         int64      `json:"queueDepth"`
	QueueBytes         int64      `json:"queueBytes"`
	LastScanAt         *time.Time `json:"lastScanAt,omitempty"`
	LastError          string     `json:"lastError,omitempty"`
	Capabilities       []string   `json:"capabilities"`
}

type HeartbeatResponse struct {
	EndpointKey               string    `json:"endpointKey"`
	Status                    string    `json:"status"`
	FingerprintReviewRequired bool      `json:"fingerprintReviewRequired"`
	Policy                    Policy    `json:"policy"`
	ServerTime                time.Time `json:"serverTime"`
}

type Relationship struct {
	RelationType string `json:"relationType"`
	Target       string `json:"target"`
	Confidence   int    `json:"confidence"`
}

type Observation struct {
	Fingerprint      string         `json:"fingerprint"`
	Name             string         `json:"name"`
	Vendor           string         `json:"vendor"`
	Product          string         `json:"product"`
	Version          string         `json:"version,omitempty"`
	AssetType        string         `json:"assetType"`
	SourceType       string         `json:"sourceType"`
	SourceLocation   string         `json:"sourceLocation"`
	AccountName      string         `json:"accountName,omitempty"`
	ProcessName      string         `json:"processName,omitempty"`
	ExecutablePath   string         `json:"executablePath,omitempty"`
	Framework        string         `json:"framework,omitempty"`
	RuntimeStatus    string         `json:"runtimeStatus,omitempty"`
	Ports            []string       `json:"ports,omitempty"`
	Protocols        []string       `json:"protocols,omitempty"`
	ServiceEndpoints []string       `json:"serviceEndpoints,omitempty"`
	Skills           []string       `json:"skills,omitempty"`
	Plugins          []string       `json:"plugins,omitempty"`
	Tools            []string       `json:"tools,omitempty"`
	MCPServers       []string       `json:"mcpServers,omitempty"`
	Models           []string       `json:"models,omitempty"`
	Relationships    []Relationship `json:"relationships,omitempty"`
	SecuritySignals  map[string]any `json:"securitySignals,omitempty"`
	Confidence       int            `json:"confidence"`
	EvidenceType     string         `json:"evidenceType"`
}

type ObservationBatch struct {
	BatchID      string        `json:"batchId"`
	ScanID       string        `json:"scanId"`
	SnapshotType string        `json:"snapshotType"`
	ObservedAt   time.Time     `json:"observedAt"`
	Observations []Observation `json:"observations"`
}

type BatchResponse struct {
	EndpointKey       string    `json:"endpointKey"`
	BatchID           string    `json:"batchId"`
	Duplicate         bool      `json:"duplicate"`
	ObservationsCount int       `json:"observationsCount"`
	DiscoveredCount   int       `json:"discoveredCount"`
	CompletedAt       time.Time `json:"completedAt"`
}

type HostNetworkObservation struct {
	ProcessName    string `json:"processName,omitempty"`
	ProcessID      int64  `json:"processId,omitempty"`
	ExecutablePath string `json:"executablePath,omitempty"`
	ContainerID    string `json:"containerId,omitempty"`
	CgroupID       string `json:"cgroupId,omitempty"`
	WorkloadUID    string `json:"workloadUid,omitempty"`
	LocalAddress   string `json:"localAddress,omitempty"`
	LocalPort      int    `json:"localPort"`
	RemoteAddress  string `json:"remoteAddress,omitempty"`
	RemotePort     int    `json:"remotePort"`
	Transport      string `json:"transport,omitempty"`
	Direction      string `json:"direction,omitempty"`
	ProtocolHint   string `json:"protocolHint,omitempty"`
	Provider       string `json:"provider,omitempty"`
	ModelName      string `json:"modelName,omitempty"`
	TLSServerName  string `json:"tlsServerName,omitempty"`
	BytesSent      int64  `json:"bytesSent"`
	BytesReceived  int64  `json:"bytesReceived"`
	IdentityKey    string `json:"identityKey,omitempty"`
}

type HostObservationRequest struct {
	SensorType       string                   `json:"sensorType"`
	CollectorVersion string                   `json:"collectorVersion,omitempty"`
	Observations     []HostNetworkObservation `json:"observations"`
}

type HostObservationResponse struct {
	EndpointKey           string   `json:"endpointKey"`
	SensorType            string   `json:"sensorType"`
	ObservationsInspected int      `json:"observationsInspected"`
	AgentsDiscovered      int      `json:"agentsDiscovered"`
	MCPServersDiscovered  int      `json:"mcpServersDiscovered"`
	ObservationsRecorded  int      `json:"observationsRecorded"`
	Warnings              []string `json:"warnings"`
}

type ContentCapture struct {
	ObservationKey string         `json:"observationKey"`
	AgentID        int64          `json:"agentId,omitempty"`
	TaskID         int64          `json:"taskId,omitempty"`
	TraceID        string         `json:"traceId,omitempty"`
	SpanID         string         `json:"spanId,omitempty"`
	Direction      string         `json:"direction"`
	Provider       string         `json:"provider,omitempty"`
	ModelName      string         `json:"modelName,omitempty"`
	Content        string         `json:"content"`
	Sensitivity    string         `json:"sensitivity,omitempty"`
	DataCategory   string         `json:"dataCategory,omitempty"`
	Metadata       map[string]any `json:"metadata,omitempty"`
	RetainRaw      bool           `json:"retainRaw"`
	Sampled        bool           `json:"sampled"`
	ObservedAt     time.Time      `json:"observedAt"`
}
