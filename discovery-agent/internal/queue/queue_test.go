package queue

import (
	"path/filepath"
	"testing"
)

func TestQueueEncryptsAndReadsSanitizedIdentifier(t *testing.T) {
	q, err := Open(t.TempDir(), 1024*1024, 7)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{"message": "sensitive payload"}
	if err := q.Put("trace/input:1", want); err != nil {
		t.Fatal(err)
	}
	files, err := q.Files()
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 1 {
		t.Fatalf("expected one queue file, got %d", len(files))
	}
	if filepath.Ext(files[0]) != ".q" {
		t.Fatalf("unexpected queue file %s", files[0])
	}
	var got map[string]string
	if err := q.Read(files[0], &got); err != nil {
		t.Fatal(err)
	}
	if got["message"] != want["message"] {
		t.Fatalf("unexpected payload %#v", got)
	}
}
