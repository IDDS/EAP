package main

import (
	"context"
	"flag"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/matrix-eap/discovery-agent/internal/config"
	"github.com/matrix-eap/discovery-agent/internal/contentproxy"
)

func main() {
	configPath := flag.String("config", config.DefaultPath(), "configuration file")
	flag.Parse()
	proxy, err := contentproxy.Open(*configPath)
	if err != nil {
		slog.Error("content proxy failed", "error", err)
		os.Exit(1)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := proxy.Run(ctx); err != nil {
		slog.Error("content proxy stopped", "error", err)
		os.Exit(1)
	}
}
