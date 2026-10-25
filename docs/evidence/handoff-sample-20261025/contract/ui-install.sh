#!/bin/sh
set -eu
[ "$(hostname)" = "eap-handoff-browser" ] || exit 64
mkdir /out/install-started
sed -i s/dl-cdn.alpinelinux.org/mirrors.ustc.edu.cn/g /etc/apk/repositories
timeout 300 apk add --no-cache chromium font-noto-cjk > /out/install.log 2>&1
timeout 180 npm install --prefix /work --cache=/work/npm-cache --no-audit --no-fund --registry=https://registry.npmmirror.com playwright-core@1.62.1 >> /out/install.log 2>&1
node --check /contract/browser.mjs > /out/syntax-check.log 2>&1
node --version > /out/node-version.txt
printf 'BROWSER_READY\n' > /out/ready
exec tail -f /dev/null
