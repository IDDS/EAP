package queue

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type Queue struct {
	dir      string
	key      []byte
	maxBytes int64
	maxAge   time.Duration
}

type Stats struct {
	Depth int64
	Bytes int64
}

func Open(stateDir string, maxBytes int64, maxDays int) (*Queue, error) {
	queueDir := filepath.Join(stateDir, "queue")
	if err := os.MkdirAll(queueDir, 0o700); err != nil {
		return nil, err
	}
	key, err := loadKey(filepath.Join(stateDir, "queue.key"))
	if err != nil {
		return nil, err
	}
	return &Queue{dir: queueDir, key: key, maxBytes: maxBytes, maxAge: time.Duration(maxDays) * 24 * time.Hour}, nil
}

func (q *Queue) Put(id string, value any) error {
	id = safeName(id)
	plain, err := json.Marshal(value)
	if err != nil {
		return err
	}
	block, err := aes.NewCipher(q.key)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return err
	}
	sealed := gcm.Seal(nonce, nonce, plain, []byte(id))
	name := fmt.Sprintf("%020d-%s.q", time.Now().UnixNano(), id)
	if err := os.WriteFile(filepath.Join(q.dir, name), sealed, 0o600); err != nil {
		return err
	}
	return q.prune()
}

func (q *Queue) Files() ([]string, error) {
	entries, err := os.ReadDir(q.dir)
	if err != nil {
		return nil, err
	}
	files := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".q") {
			files = append(files, filepath.Join(q.dir, entry.Name()))
		}
	}
	sort.Strings(files)
	return files, nil
}

func (q *Queue) Read(path string, target any) error {
	sealed, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	block, err := aes.NewCipher(q.key)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	if len(sealed) < gcm.NonceSize() {
		return errors.New("invalid queue item")
	}
	nonce, ciphertext := sealed[:gcm.NonceSize()], sealed[gcm.NonceSize():]
	id := queueID(filepath.Base(path))
	plain, err := gcm.Open(nil, nonce, ciphertext, []byte(id))
	if err != nil {
		return err
	}
	return json.Unmarshal(plain, target)
}

func (q *Queue) Delete(path string) error { return os.Remove(path) }

func (q *Queue) Stats() Stats {
	files, _ := q.Files()
	var result Stats
	for _, path := range files {
		if info, err := os.Stat(path); err == nil {
			result.Depth++
			result.Bytes += info.Size()
		}
	}
	return result
}

func (q *Queue) prune() error {
	files, err := q.Files()
	if err != nil {
		return err
	}
	stats := q.Stats()
	cutoff := time.Now().Add(-q.maxAge)
	for _, path := range files {
		info, err := os.Stat(path)
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) || (q.maxBytes > 0 && stats.Bytes > q.maxBytes) {
			_ = os.Remove(path)
			stats.Bytes -= info.Size()
			stats.Depth--
		}
	}
	return nil
}

func loadKey(path string) ([]byte, error) {
	if data, err := os.ReadFile(path); err == nil {
		if len(data) != 32 {
			return nil, errors.New("invalid queue key")
		}
		return data, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	key := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, key); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, key, 0o600); err != nil {
		return nil, err
	}
	return key, nil
}

func safeName(value string) string {
	value = strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' {
			return r
		}
		return '-'
	}, value)
	if len(value) > 64 {
		value = value[:64]
	}
	return value
}

func queueID(name string) string {
	name = strings.TrimSuffix(name, ".q")
	parts := strings.SplitN(name, "-", 2)
	if len(parts) == 2 {
		return parts[1]
	}
	return name
}
