package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"

	"github.com/matrix-eap/discovery-agent/internal/agent"
	"github.com/matrix-eap/discovery-agent/internal/autoattach"
	"github.com/matrix-eap/discovery-agent/internal/config"
)

func main() {
	configPath := flag.String("config", config.DefaultPath(), "configuration file")
	token := flag.String("token", "", "one-time enrollment token")
	dryRun := flag.Bool("dry-run", false, "print sanitized observations without upload")
	full := flag.Bool("full", false, "send a full snapshot")
	target := flag.String("target", "", "Docker container to auto-attach")
	profile := flag.String("profile", "auto", "auto-attach framework profile")
	agentID := flag.Int64("agent-id", 0, "managed EAP Agent ID for content attribution")
	usagePoolKey := flag.String("usage-pool-key", "", "non-secret shared provider usage pool alias")
	provider := flag.String("provider", "", "model provider label")
	collectorURL := flag.String("collector-url", "http://host.docker.internal:8687", "Agent-scoped content collector URL")
	instrumentationTokenFile := flag.String("instrumentation-token-file", "", "host path for the scoped instrumentation token")
	proxyInstance := flag.String("proxy-instance", "", "systemd content proxy template instance name")
	apply := flag.Bool("apply", false, "apply the generated Docker attachment and restart the target service")
	command, args := commandAndArgs(os.Args[1:])
	if err := flag.CommandLine.Parse(args); err != nil {
		fatal(err)
	}

	runtime, err := agent.Open(*configPath)
	if err != nil {
		fatal(err)
	}
	ctx := context.Background()
	switch command {
	case "enroll":
		err = runtime.Enroll(ctx, *token)
	case "run":
		err = runtime.Run(ctx)
	case "scan":
		_, err = runtime.Scan(ctx, *dryRun, *full)
	case "status":
		err = runtime.Status()
	case "diagnose":
		err = runtime.Diagnose(ctx)
	case "attach":
		err = runtime.Attach(ctx, autoattach.Options{
			Target: *target, Profile: *profile, AgentID: *agentID, UsagePoolKey: *usagePoolKey,
			Provider: *provider, CollectorURL: *collectorURL,
			InstrumentationTokenFile: *instrumentationTokenFile, ProxyInstance: *proxyInstance, Apply: *apply,
		})
	case "reconcile-attachments":
		err = runtime.ReconcileAttachments(ctx)
	case "observe-host":
		err = runtime.ObserveHost(ctx, *dryRun)
	case "uninstall":
		err = runtime.Uninstall(ctx)
	case "version":
		fmt.Println(agent.Version)
	default:
		err = fmt.Errorf("unknown command %q", command)
	}
	if err != nil {
		fatal(err)
	}
}

func commandAndArgs(args []string) (string, []string) {
	commands := map[string]bool{"enroll": true, "run": true, "scan": true, "status": true, "diagnose": true, "attach": true, "reconcile-attachments": true, "observe-host": true, "uninstall": true, "version": true}
	command := "run"
	filtered := make([]string, 0, len(args))
	found := false
	for _, value := range args {
		if !found && commands[value] {
			command, found = value, true
			continue
		}
		filtered = append(filtered, value)
	}
	return command, filtered
}

func fatal(err error) {
	slog.Error("discovery agent failed", "error", err)
	os.Exit(1)
}
