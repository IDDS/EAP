VERSION ?= 1.0.0
OUT ?= dist

.PHONY: test build linux package clean

test:
	go test ./...

build:
	mkdir -p $(OUT)
	go build -trimpath -ldflags "-s -w" -o $(OUT)/eap-discovery-agent ./cmd/eap-discovery-agent
	go build -trimpath -ldflags "-s -w" -o $(OUT)/eap-content-proxy ./cmd/eap-content-proxy

linux:
	mkdir -p $(OUT)
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o $(OUT)/eap-discovery-agent-linux-amd64 ./cmd/eap-discovery-agent
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o $(OUT)/eap-content-proxy-linux-amd64 ./cmd/eap-content-proxy
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o $(OUT)/eap-discovery-agent-linux-arm64 ./cmd/eap-discovery-agent
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o $(OUT)/eap-content-proxy-linux-arm64 ./cmd/eap-content-proxy

package: linux
	cp packaging/install.sh packaging/uninstall.sh packaging/systemd/* $(OUT)/
	cp ebpf/eap-connect.bt ebpf/eap_ebpf_observer.py $(OUT)/

clean:
	rm -rf $(OUT)
