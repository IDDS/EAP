package main

import (
	"errors"
	"os"
	"strings"
	"time"
)

func canonicalUUID(s string) bool {
	if len(s) != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-' {
		return false
	}
	for i, c := range s {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			continue
		}
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

func syncDirectory(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

func (r *runner) configureIdentity() error {
	mode, public, directory := os.Getenv("EAP_IDENTITY_MODE"), os.Getenv("EAP_IDENTITY_PUBLIC_KEY"), os.Getenv("EAP_IDENTITY_DIR")
	if mode == "" && public == "" && directory == "" {
		return nil
	}
	if mode != "PER_INSTANCE_V1" || public == "" || directory != "/run/eap-identity" || r.taskToken != "" || r.gwToken != "" {
		return errors.New("invalid instance identity configuration")
	}
	for _, name := range []string{"EAP_OBSERVABILITY_TOKEN", "GEMINI_API_KEY", "OPENAI_API_KEY", "DEEPSEEK_API_KEY", "SUBSTRATE_TOKEN_FILE"} {
		if os.Getenv(name) != "" {
			return errors.New("instance identity cannot use shared credentials")
		}
	}
	id, err := newRuntimeIdentity(public, directory)
	if err != nil {
		return err
	}
	r.identity = id
	return nil
}

// Every task, result, model and telemetry call rechecks the read-only actor UID.
// A new process starts without credentials; only signed controller delivery can
// install them. The durable task journal is independent of this memory state.
func (r *runner) machineCredentials() (string, string, error) {
	if r.identity == nil {
		return "", "", nil
	}
	key, uid, err := r.identity.fields()
	if err != nil {
		return "", "", errors.New("task instance identity unavailable")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if key != r.runtimeKey || uid != r.credentialUID || r.runtimeToken == "" || r.credentialExpiry.IsZero() || !time.Now().Before(r.credentialExpiry) || r.taskKey == "" || !strings.HasSuffix(key, "/"+r.taskKey) {
		return "", "", errors.New("task instance credential unavailable")
	}
	return key, r.runtimeToken, nil
}
