import base64,datetime,json,re,shutil,tarfile,time
from pathlib import Path
import pipeline as m
import acceptance as a
from release_checks import discovery,collector
assert not (m.ROOT/'closed.json').exists()
for label in ['race-proof','handoff-r4','vet-r4','handoff-python-r5','observer-python-r4','tests-r5-complete','runtime-image-proof','image-test-proof','live-proof','browser-result']:
 assert m.load(label+'.json')['passed'],label
assert a.state.get('reclaimed') and a.state.get('observationVerified')
browser=m.load('browser-result.json');assert len(browser['cases'])==8 and not browser['displayFixtures']
assert not browser['unexpectedWrites'] and not browser['pageErrors'] and not browser['serverErrors']
for suite in ['native','framework','handoff']:
 rows=[json.loads(s) for s in (m.ROOT/'race-out'/(suite+'.jsonl')).read_text().splitlines() if s.startswith('{')]
 assert not any(x['Action'] in ['skip','fail'] for x in rows),suite
before=a.state['originalProtection'];now=a.original_rows()
for table,old in before.items():
 assert now[table]['columns']==old['columns'],table
 assert all(now[table]['rows'].get(k)==h for k,h in old['rows'].items()),table
assert m.plans()==m.load('plans-before.json') and m.protected()==m.load('protected-before.json')
assert discovery()==m.load('discovery-before.json') and collector()==m.load('collector-before.json')
counts={t:int(m.sql('select count(*) from '+t).strip()) for t in a.state['countsBefore']}
delta={t:counts[t]-v for t,v in a.state['countsBefore'].items()}
assert delta=={'agent_execution_events':14,'execution_delivery_reports':2,'execution_links':2,'model_invocation_events':0,'tool_invocation_events':1},delta
assert m.sql('select max(version) from flyway_schema_history where success=1').strip()==b'95'
assert m.sql('select count(*) from platform_project_binding where id=1 and project_key is null and bound_at is null').strip()==b'1'
agents={str(e['agentId']):e for e in a.state['frameworks'].values()}
budgets=a.api('/budgets')['accounts']
for account in budgets:
 if account['scopeType']=='AGENT' and account['scopeKey'] in agents:
  assert account['unit'] in ['MODEL_CALLS','OUTPUT_TOKENS','TOOL_CLAIMS']
m.put('original-records-proof.json',{'passed':True,'preservedRows':{t:len(x['rows']) for t,x in before.items()},'counts':counts,'deltas':delta,'oldUnknownAndPlansUnchanged':True,'platformToolClaimIncrementAllowed':1})
owners=[m.load(k+'-owned.json') for k in ['race-pod','image-pod','probe-pod','browser-pod']]
for x in owners:
 deadline=time.monotonic()+180
 while m.run(['kubectl','-n','eap','get','pod',x['name'],'--ignore-not-found','-o','name']).strip():
  assert time.monotonic()<deadline;time.sleep(3)
for k in ['probe-config','browser-config']:
 x=m.load(k+'-owned.json');assert not m.run(['kubectl','-n','eap','get','configmap',x['name'],'--ignore-not-found','-o','name']).strip()
m.put('cleanup.json',{'passed':True,'removedPods':4,'removedConfigMaps':2,'temporaryRemaining':0})
health=m.health();assert health==m.load('before.json') and health['health']=='UP'
assert all(c['ready'] and c['restarts']==0 for app in ['backend','frontend'] for p in health[app]['pods'] for c in p['containers'])
m.put('final-health.json',health);m.put('final-space.json',m.space())
baseline=json.loads((m.ROOT/'contract/baseline-source-manifest.json').read_text())
source=json.loads((m.ROOT/'contract/source-manifest-final.json').read_text());canonical=Path('/root/eap-src')
assert all(m.sha((canonical/n).read_bytes())==h for n,h in baseline.items())
changes={n:h for n,h in source.items() if baseline.get(n)!=h}
assert len(source)==1036 and len(changes)==7
assert all(m.sha((m.ROOT/'source'/n).read_bytes())==h for n,h in changes.items())
assert all(not (canonical/n).exists() for n in changes if n not in baseline)
completed=datetime.datetime.now(datetime.timezone.utc).isoformat()
receipt={'passed':True,'completedAt':completed,'releaseId':'private-pilot-20261025-execution-links-r1','schema':95,'applicationImagesUnchanged':True,
 'optionalSampleImage':m.load('runtime-image-proof.json')['image'],'defaultFrameworkImageUnchanged':True,
 'goRace':m.load('race-proof.json')['suites'],'goVet':True,'pythonSdkTests':80,'pythonHandoffTests':13,'actualImageHandoffTests':13,'liveRoleBrowserCases':8,'syntheticBrowserCases':0,
 'agentIds':{k:v['agentId'] for k,v in a.state['frameworks'].items()},'workflowId':a.state['workflowId'],'runKey':a.state['runKey'],'taskStages':6,
 'codeExecutions':1,'sum':42,'modelCalls':0,'newEvents':14,'newLogicalRuns':5,'newToolClaims':1,'newCodeRegistrations':2,'confirmedLinks':2,'duplicateLinkReports':4,
 'finalInstances':'SUSPENDED','finalConnections':'REVOKED','finalAgents':'PAUSED','originalRecordsPreserved':True,'totalExecutionEvents':counts['agent_execution_events'],'totalDeliveryReports':counts['execution_delivery_reports'],
 'canonicalInputs':1036,'changedFiles':7,'temporaryPodsRemoved':4,'temporaryConfigMapsRemoved':2,'temporaryRemaining':0,
 'backendFrontendMySqlRetested':False,'kernelRetested':False,'localApplicationTests':False,'gitCommitOrPush':False,'productionReady':False,
 'limits':['Fixed public three-number sum only; not an arbitrary-code execution sandbox','Late reports are made by the owning runtime observer, after the Python child exits','Sample workflow outputs include the public code body; observation APIs store metadata only','No automatic arbitrary-framework instrumentation, general source archive, unknown-task replay, compensation or long-duration capacity proof']}
m.put('final-receipt.json',receipt)
files=[]
for directory in ['evidence','contract','race-out','ui-out']:
 for p in (m.ROOT/directory).rglob('*'):
  if p.is_file() and not p.is_symlink() and '__pycache__' not in p.parts and p.suffix in ['.json','.jsonl','.log','.py','.txt','.md','.sh','.yaml','.yml','.mjs','.png']:files.append(p)
files.extend(m.ROOT.glob('*.log'));files.extend(m.ROOT.glob('*.py'));files.append(m.ROOT/'acceptance-state.json')
patterns=set()
for ns in ['eap','eap-auth','otel-system']:
 for secret in m.jrun(['kubectl','-n',ns,'get','secrets','-o','json'])['items']:
  for key,encoded in secret.get('data',{}).items():
   raw=base64.b64decode(encoded)
   if not (re.search(r'password|passwd|token|secret|private|api.?key|credential|signing',key,re.I) or secret['metadata']['name']=='eap-test-users' or b'PRIVATE KEY' in raw) or len(raw)<8:continue
   patterns.update([raw,encoded.encode()])
   try:patterns.add(json.dumps(raw.decode())[1:-1].encode())
   except UnicodeDecodeError:pass
for p in files:
 body=p.read_bytes();assert not any(v in body for v in patterns),'Credential match in evidence'
 assert not re.search(rb'eyJ[A-Za-z0-9_-]{12,}\.eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}',body),'Token pattern in evidence'
m.put('credential-scan.json',{'passed':True,'filesScanned':len(files),'matches':0,'credentialValuesPersisted':False});files.append(m.OUT/'credential-scan.json')
m.put('source-promotion-intent.json',{'files':changes})
for n in changes:
 p=canonical/n;p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(m.ROOT/'source'/n,p)
assert all(m.sha((canonical/n).read_bytes())==h for n,h in source.items())
m.put('canonical-source-manifest.json',source);m.put('source-promotion-proof.json',{'passed':True,'canonicalInputs':len(source),'changes':changes})
files.extend(m.OUT/n for n in ['source-promotion-intent.json','canonical-source-manifest.json','source-promotion-proof.json'])
export=m.ROOT/'export';export.mkdir()
for p in files:
 q=export/p.relative_to(m.ROOT);q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)
closed={'closed':True,'completedAt':completed,'replayForbidden':True,'finalReceipt':'evidence/final-receipt.json'};(export/'closed.json').write_text(json.dumps(closed,indent=2)+'\n')
manifest={str(p.relative_to(export)):m.sha(p.read_bytes()) for p in export.rglob('*') if p.is_file()};(export/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
archive=m.ROOT/'evidence-export.tgz'
with tarfile.open(archive,'w:gz') as tar:
 for p in sorted(export.rglob('*')):
  if p.is_file():tar.add(p,arcname=str(p.relative_to(export)),recursive=False)
proof={'files':len(manifest)+1,'bytes':archive.stat().st_size,'sha256':m.sha(archive.read_bytes())};(m.ROOT/'export-proof.json').write_text(json.dumps(proof,indent=2)+'\n');(m.ROOT/'closed.json').write_text(json.dumps(closed,indent=2)+'\n')
print('HANDOFF_SAMPLE_BATCH_CLOSED',json.dumps(proof),flush=True)
