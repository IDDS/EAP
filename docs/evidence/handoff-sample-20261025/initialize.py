import json, shutil
from pathlib import Path
import pipeline as m
from release_checks import discovery, collector

assert not m.OUT.exists() and not (m.ROOT/'closed.json').exists()
baseline=json.loads((m.ROOT/'contract/baseline-source-manifest.json').read_text())
candidate=json.loads((m.ROOT/'contract/source-manifest.json').read_text())
assert all(m.sha((Path('/root/eap-src')/n).read_bytes())==h for n,h in baseline.items())
health=m.health(); assert health['health']=='UP'
assert health['backend']['image'].endswith('f93d311d41828341a70a919812f983f675aada0e5e3cf4a028c1d2d12642b88c')
assert health['frontend']['image'].endswith('6b096481c862f9af3770492be7a011ab856762ba2d95344504d142144aeac33f')
space=m.space();m.OUT.mkdir();(m.ROOT/'source').mkdir();(m.ROOT/'binaries').mkdir()
for n,h in candidate.items():
 if not n.startswith('runtime/'): continue
 p=m.ROOT/'changes'/n if (m.ROOT/'changes'/n).exists() else Path('/root/eap-src')/n
 assert m.sha(p.read_bytes())==h,n
 q=m.ROOT/'source'/n;q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)
for name,value in [('before',health),('space-before',space),('protected-before',m.protected()),('plans-before',m.plans()),('discovery-before',discovery()),('collector-before',collector())]:m.put(name+'.json',value)
m.put('source-proof.json',{'passed':True,'baselineInputs':len(baseline),'candidateInputs':len(candidate),'changes':{n:h for n,h in candidate.items() if baseline.get(n)!=h}})
print('HANDOFF_BATCH_INITIALIZED',flush=True)
