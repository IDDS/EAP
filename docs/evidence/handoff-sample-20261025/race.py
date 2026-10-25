import json,time
import pipeline as m
assert not (m.ROOT/'closed.json').exists();m.space()
out=m.ROOT/'race-out';out.mkdir()
script="""set -eu
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
"""
(m.ROOT/'contract/race.sh').write_text(script)
pod=json.loads(open('/root/eap-ephemeral-code-20261025-r1/contract/go-pod-template.json').read())
pod['metadata']={'name':'eap-handoff-race-20261025-r1','namespace':'eap'};pod['spec']['activeDeadlineSeconds']=1200
pod['spec']['containers'][0]['command']=['sh','/contract/race.sh']
for v in pod['spec']['volumes']:
 if v['name'] in ['src','out','contract']:v['hostPath']['path']=str(m.ROOT/({'src':'source','out':'race-out','contract':'contract'}[v['name']]))
m.put('race-pod-intent.json',pod);p=json.loads(m.run(['kubectl','create','-f','-','-o','json'],input=json.dumps(pod).encode()));m.put('race-pod-owned.json',{'name':p['metadata']['name'],'uid':p['metadata']['uid']})
deadline=time.monotonic()+1200
while True:
 current=m.kget('pod',p['metadata']['name']);assert current['metadata']['uid']==p['metadata']['uid']
 if current['status']['phase'] in ['Succeeded','Failed']:break
 assert time.monotonic()<deadline;time.sleep(4)
(m.ROOT/'race-pod.log').write_bytes(m.run(['kubectl','-n','eap','logs',p['metadata']['name']]))
assert current['status']['phase']=='Succeeded','Race test failed; original pod and logs retained'
proof={}
for name in ['native','framework','handoff']:
 rows=[json.loads(s) for s in (out/(name+'.jsonl')).read_text().splitlines() if s.startswith('{')]
 assert not any(r['Action']=='fail' for r in rows)
 tests=[r['Test'] for r in rows if r['Action']=='pass' and 'Test' in r]
 proof[name]={'topLevelTests':sum('/' not in t for t in tests),'includingSubtests':len(tests)}
m.put('race-proof.json',{'passed':True,'suites':proof});m.cleanup('race-pod');print('RACE_PASSED',proof,flush=True)
