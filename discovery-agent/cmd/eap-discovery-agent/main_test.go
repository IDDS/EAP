package main

import "testing"

func TestCommandMayAppearBeforeOrBetweenFlags(t *testing.T) {
	command, args := commandAndArgs([]string{"--config", "/tmp/config.json", "scan", "--dry-run"})
	if command != "scan" {
		t.Fatalf("unexpected command %q", command)
	}
	if len(args) != 3 {
		t.Fatalf("unexpected flags %#v", args)
	}
}

func TestReconcileAttachmentsIsRecognizedAsCommand(t *testing.T) {
	command, args := commandAndArgs([]string{"--config", "/tmp/config.json", "reconcile-attachments"})
	if command != "reconcile-attachments" {
		t.Fatalf("unexpected command %q", command)
	}
	if len(args) != 2 {
		t.Fatalf("unexpected flags %#v", args)
	}
}

func TestObserveHostIsRecognizedAsCommand(t *testing.T) {
	command, args := commandAndArgs([]string{"observe-host", "--dry-run"})
	if command != "observe-host" {
		t.Fatalf("unexpected command %q", command)
	}
	if len(args) != 1 {
		t.Fatalf("unexpected flags %#v", args)
	}
}
