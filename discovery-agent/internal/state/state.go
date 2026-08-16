package state

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"time"

	"github.com/matrix-eap/discovery-agent/internal/protocol"
)

type State struct {
	InstallationID     string          `json:"installation_id"`
	EndpointKey        string          `json:"endpoint_key,omitempty"`
	Credential         string          `json:"credential,omitempty"`
	CredentialHint     string          `json:"credential_hint,omitempty"`
	MachineFingerprint string          `json:"machine_fingerprint"`
	Policy             protocol.Policy `json:"policy"`
	LastScanAt         time.Time       `json:"last_scan_at,omitempty"`
	LastFullSnapshotAt time.Time       `json:"last_full_snapshot_at,omitempty"`
	LastHeartbeatAt    time.Time       `json:"last_heartbeat_at,omitempty"`
	LastError          string          `json:"last_error,omitempty"`
}

type Store struct{ dir string }

type ContentIdentity struct {
	EndpointKey    string `json:"endpoint_key"`
	Credential     string `json:"credential"`
	CredentialHint string `json:"credential_hint,omitempty"`
}

func NewStore(dir string) *Store             { return &Store{dir: dir} }
func (s *Store) Dir() string                 { return s.dir }
func (s *Store) StatePath() string           { return filepath.Join(s.dir, "state.json") }
func (s *Store) ContentIdentityPath() string { return filepath.Join(s.dir, "content-identity.json") }

func (s *Store) Load() (State, error) {
	data, err := os.ReadFile(s.StatePath())
	if err != nil {
		return State{}, err
	}
	var value State
	if err := json.Unmarshal(data, &value); err != nil {
		return State{}, err
	}
	return value, nil
}

func (s *Store) LoadOrCreate(fingerprint string) (State, error) {
	value, err := s.Load()
	if err == nil {
		return value, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return State{}, err
	}
	value = State{InstallationID: randomID(), MachineFingerprint: fingerprint}
	return value, s.Save(value)
}

func (s *Store) Save(value State) error {
	if err := os.MkdirAll(s.dir, 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	temp := s.StatePath() + ".tmp"
	if err := os.WriteFile(temp, append(data, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(temp, s.StatePath())
}

func (s *Store) SaveContentIdentity(value ContentIdentity) error {
	if err := os.MkdirAll(s.dir, 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(s.ContentIdentityPath(), append(data, '\n'), 0o600)
}

func (s *Store) LoadContentIdentity() (ContentIdentity, error) {
	data, err := os.ReadFile(s.ContentIdentityPath())
	if err != nil {
		return ContentIdentity{}, err
	}
	var value ContentIdentity
	if err := json.Unmarshal(data, &value); err != nil {
		return ContentIdentity{}, err
	}
	return value, nil
}

func randomID() string {
	data := make([]byte, 16)
	if _, err := rand.Read(data); err != nil {
		panic(err)
	}
	return hex.EncodeToString(data)
}
