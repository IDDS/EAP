package main

import (
	"context"
	"sync"
)

// Zero-value ready; a canceled waiter never owns the gate, even when both select arms are ready.
type cancellableMutex struct {
	once sync.Once
	gate chan struct{}
}

func (m *cancellableMutex) LockContext(ctx context.Context) error {
	m.once.Do(func() { m.gate = make(chan struct{}, 1) })
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case m.gate <- struct{}{}:
		if err := ctx.Err(); err != nil {
			m.Unlock()
			return err
		}
		return nil
	}
}

func (m *cancellableMutex) Lock()   { _ = m.LockContext(context.Background()) }
func (m *cancellableMutex) Unlock() { <-m.gate }

func validChatHistory(history []message) bool {
	if len(history) > maxHistory || len(history)%2 != 0 {
		return false
	}
	bytes := 0
	for i, entry := range history {
		role := "user"
		if i%2 == 1 {
			role = "assistant"
		}
		if entry.Role != role || entry.Content == "" {
			return false
		}
		bytes += len(entry.Content)
	}
	return bytes <= 65536
}
