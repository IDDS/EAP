set -eu
export PATH=/usr/local/go/bin:$PATH GOTOOLCHAIN=local GOPROXY=off GOMAXPROCS=2 GOMEMLIMIT=900MiB GOCACHE=/cache/build GOMODCACHE=/cache/mod
sed -i s/dl-cdn.alpinelinux.org/mirrors.ustc.edu.cn/g /etc/apk/repositories
apk add --no-cache gcc musl-dev python3 > /out/install.log 2>&1
cp -R /src/runtime /work/runtime
ln -s /work/runtime/mcp-observer /opt/eap-mcp
ln -s /usr/bin/python3 /usr/local/bin/python3
export EAP_MCP_TEST_DIR=/work/runtime/mcp-observer
cd /work/runtime/agent-runner
CGO_ENABLED=1 go test -p 1 -count=1 -race -json ./... > /out/native.jsonl 2>&1
CGO_ENABLED=1 go test -p 1 -count=1 -race -tags framework -run '^TestFramework' -json ./... > /out/framework.jsonl 2>&1
CGO_ENABLED=1 go test -p 1 -count=1 -race -tags handoff -run '^TestHandoff' -json ./... > /out/handoff.jsonl 2>&1
echo RACE_PASSED
