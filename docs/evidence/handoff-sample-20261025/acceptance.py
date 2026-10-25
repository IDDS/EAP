"""New bounded EAP-owned workloads only; all old execution intents remain untouched."""
import base64,decimal,fcntl,hashlib,importlib.util,json,os,time,urllib.parse,urllib.request
from pathlib import Path
import pipeline as m
from release_checks import discovery,collector

state_path=m.ROOT/'acceptance-state.json'
lock=(m.ROOT/'acceptance.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
assert not (m.ROOT/'closed.json').exists()
state=json.loads(state_path.read_text()) if state_path.exists() else {'writes':{},'frameworks':{},'prefix':'EAP-HANDOFF-20261025-R1'}
def save():
 p=state_path.with_suffix('.tmp')
 with p.open('w') as f:json.dump(state,f,ensure_ascii=False,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())
 p.replace(state_path)
if not state_path.exists():save()
spec=importlib.util.spec_from_file_location('auth','/root/eap-verify-jwt.py');auth=importlib.util.module_from_spec(spec);spec.loader.exec_module(auth)
base=auth.service('eap','frontend');kc=auth.service('eap-auth','keycloak');tokens={}
def api(path,method='GET',body=None,user='admin'):
 if user not in tokens or time.monotonic()-tokens[user][0]>120:
  data=json.loads(auth.kubectl('-n','eap-auth','get','secret','eap-test-users','-o','json'))['data']
  tokens[user]=(time.monotonic(),auth.login(kc,user,base64.b64decode(data[user]).decode())['access_token'])
 try:code,_,raw=auth.http(urllib.request.build_opener(auth.NoRedirect()),base+'/api'+path,method,None if body is None else json.dumps(body,ensure_ascii=False).encode(),{'Authorization':'Bearer '+tokens[user][1],'Content-Type':'application/json'})
 except Exception:raise RuntimeError('Unconfirmed transport: '+method+' '+path) from None
 if code!=200:
  state['lastFailure']={'path':path,'method':method,'status':code};save();raise RuntimeError('EAP API returned '+str(code)+' at '+path)
 return json.loads(raw)['data']
def write(name,path,body=None,method='POST'):
 fingerprint=m.digest([method,path,body]);old=state['writes'].get(name)
 if old:
  assert old['fingerprint']==fingerprint and old['status']=='CONFIRMED','Resolve original unconfirmed write; never issue a new intent'
  return old['result']
 assert all(x['status']=='CONFIRMED' for x in state['writes'].values())
 state['writes'][name]={'fingerprint':fingerprint,'path':path,'method':method,'status':'INTENT'};save()
 result=api(path,method,body);state['writes'][name].update(status='CONFIRMED',result=result);save();print('CONFIRMED',name,flush=True);return result

def original_rows():
 out={}
 tables=['execution_delivery_reports','agent_execution_events','workflow_executions','tool_invocation_events','budget_accounts','budget_reservations','model_invocation_events','runtime_work_claims','runtime_work_actors','eap_chat_submissions','observation_operations','observation_operation_events','observation_runtime_placements','agents']
 for table in tables:
  columns=[row.split(b'\t')[0].decode() for row in m.sql('show columns from '+table).splitlines()]
  keys=[row.decode() for row in m.sql("select COLUMN_NAME from information_schema.KEY_COLUMN_USAGE where TABLE_SCHEMA=DATABASE() and TABLE_NAME='"+table+"' and CONSTRAINT_NAME='PRIMARY' order by ORDINAL_POSITION").splitlines()]
  indices=[columns.index(k) for k in keys];assert indices
  clause=" where not (scope_type='PLATFORM' and unit='TOOL_CLAIMS')" if table=='budget_accounts' else ''
  rows=m.sql('select * from '+table+clause).splitlines();hashed={}
  for row in rows:
   fields=row.split(b'\t');key=m.sha(b'\t'.join(fields[i] for i in indices));assert key not in hashed;hashed[key]=m.sha(row)
  out[table]={'rows':hashed,'columns':columns,'primaryColumns':keys}
 # Preserve human work-item state while allowing automated observation timestamps to move.
 columns=['id','item_key','status','assignee','assigned_team','resolution_evidence_json','resolved_at','closed_at']
 available={r.split(b'\t')[0].decode() for r in m.sql('show columns from agent_work_items').splitlines()};columns=[c for c in columns if c in available]
 rows=m.sql('select '+','.join(columns)+' from agent_work_items').splitlines()
 out['agent_work_items']={'rows':{m.sha(r.split(b'\t')[0]):m.sha(r) for r in rows},'columns':columns}
 return out

def prepare():
 assert m.load('runtime-image-proof.json')['passed']
 if 'originalProtection' not in state:
  state['originalProtection']=original_rows()
  state['platformBefore']=[x for x in api('/budgets')['accounts'] if x['scopeType']=='PLATFORM']
  state['countsBefore']={t:int(m.sql('select count(*) from '+t).strip()) for t in ['agent_execution_events','execution_delivery_reports','execution_links','model_invocation_events','tool_invocation_events']}
  save()
 assert m.health()==m.load('before.json') and m.plans()==m.load('plans-before.json')
 caps=api('/platform/me')['capabilities'];assert all(caps.get(k) for k in ['deployAgents','runWorkflows','manageBudgets'])
 assert api('/agents/substrate/capabilities')['instanceIdentityAvailable'] and api('/workflows/execution-status')['enabled']
 image=m.load('runtime-image-proof.json')['image']
 for role,label in [('source','LangGraph 代码来源'),('target','AutoGen 临时执行器')]:
  a=write('agent-'+role,'/agents',{'code':state['prefix']+'-'+role,'name':label+' 跨实例交接样例','description':'EAP固定公开代码交接；独立短进程与双方确认，无模型调用','ownerDepartment':'EAP样例','ownerUser':'sample-operator','category':'执行观测','riskLevel':'LOW'})
  agent=a['id'];entry=state['frameworks'].setdefault(role,{'agentId':agent});save()
  for unit,limit in [('MODEL_CALLS',0),('OUTPUT_TOKENS',0),('TOOL_CLAIMS',1 if role=='target' else 0)]:write('budget-'+role+'-'+unit,'/budgets/accounts',{'scopeType':'AGENT','scopeKey':str(agent),'unit':unit,'limitAmount':limit,'marginAmount':0,'expectedRevision':0},'PUT')
  write('activate-'+role,'/agents/'+str(agent)+'/status',{'lifecycleStatus':'ACTIVE'})
  path='/agents/'+str(agent)+'/substrate/deployments'
  request={'sourceType':'IMAGE','image':image,'command':['/usr/local/bin/eap-handoff-'+role],'port':80,'readinessPath':'/readyz','env':{},'sandboxClass':'GVISOR','size':'MEDIUM','egressHosts':[],'requestKey':state['prefix']+'-'+role+'-v1'}
  d=write('deploy-'+role,path,request)
  deadline=time.monotonic()+600
  while True:
   current=next(x for x in api(path) if x['deploymentKey']==d['deploymentKey']);entry['deployment']=current;save()
   if current['status']=='READY':break
   assert current['status'] not in ['FAILED','BLOCKED','WAITING_APPROVAL'],'Original deployment stopped; inspect without new version'
   assert time.monotonic()<deadline;time.sleep(3)
  assert current['imageRef']==image and current['sourceType']=='IMAGE'
  replay=write('same-deployment-'+role,path,request);assert replay['deploymentKey']==current['deploymentKey'] and replay['version']==1
  instances=api('/agents/'+str(agent)+'/substrate/instances');assert len(instances)==1 and instances[0]['state']=='SUSPENDED'
  entry['instance']=instances[0];key=instances[0]['instanceKey'];q=urllib.parse.urlencode({'instanceKey':key})
  connection=api('/registry/runtime-connection?'+q);assert connection['connection']=='READY' and connection['automaticDelivery'];entry['initialConnection']=connection;save()
  policy=api('/registry/runtime-tools/policy?'+q)
  if role=='target':write('tool-policy-'+role,'/registry/runtime-tools/policy',{'instanceKey':key,'expectedRevision':policy['revision'],'enabled':True,'allowedTools':[{'name':'calculate_sum','kind':'TOOL'}],'claimLimit':1},'PUT')
 state['prepared']=True;save();print('TWO_EAP_HANDOFF_INSTANCES_PREPARED',flush=True)

def run():
 assert state.get('prepared')
 workflow=write('workflow','/workflows',{'code':state['prefix'],'name':'跨实例临时代码交接与进程退出后确认','description':'LangGraph生成公开固定代码，AutoGen受控执行；EAP调度双方确认与原意图重发，一次工具零模型'})
 path='/workflows/'+str(workflow['id']);state['workflowId']=workflow['id'];save()
 nodes=[('start','START',None),('generate','AGENT','source'),('execute','AGENT','target'),('offer','AGENT','source'),('confirm','AGENT','target'),('resend-source','AGENT','source'),('resend-target','AGENT','target'),('end','END',None)]
 for i,(key,kind,role) in enumerate(nodes):
  config={} if role is None else {'executionBackend':'RUNTIME','suspendAfterTask':False}
  if key=='execute':config.update(inputFrom=['generate'],eapTool={'name':'calculate_sum','arguments':{'numbers':[10,20,12]}})
  elif key in ['offer','confirm','resend-source','resend-target']:config['inputFrom']=['execute']
  write('node-'+key,path+'/nodes',{'nodeKey':key,'label':key,'nodeType':kind,'agentId':None if role is None else state['frameworks'][role]['agentId'],'positionX':100+i*220,'positionY':180,'configJson':json.dumps(config)})
 for left,right in zip(nodes,nodes[1:]):write('edge-'+left[0],path+'/edges',{'sourceNodeKey':left[0],'targetNodeKey':right[0],'conditionExpression':'always'})
 request={'objective':'在两个独立运行实例交接固定公开求和代码，执行进程退出后确认并重发原观测','context':{},'requestKey':state['prefix']+'-run'}
 result=write('workflow-run',path+'/executions',request);state['runKey']=result['runKey'];save()
 deadline=time.monotonic()+420
 while True:
  result=api(path+'/executions/'+state['runKey']);state['execution']=result;save()
  if result['status'] not in ['QUEUED','RUNNING']:break
  assert time.monotonic()<deadline;time.sleep(2)
 assert result['status']=='SUCCEEDED','Original workflow did not succeed; preserve unknown intent'
 state['outputs']=api(path+'/executions/'+state['runKey']+'/outputs');save()
 assert state['outputs']['execute']['sum']==42 and state['outputs']['execute']['count']==3
 for key,expected,duplicate in [('offer','PENDING_CONFIRMATION',False),('confirm','CONFIRMED',False),('resend-source','CONFIRMED',True),('resend-target','CONFIRMED',True)]:
  output=state['outputs'][key];assert output['businessReexecuted'] is False
  assert len(output['links'])==2 and all(x['state']==expected and x['duplicate'] is duplicate and x['executionPermissionGranted'] is False for x in output['links'])
 repeated=write('same-workflow-request',path+'/executions',request);assert repeated['runKey']==state['runKey']
 state['runVerified']=True;save();print('WORKFLOW_SUCCEEDED_ONE_TOOL_ZERO_MODELS',flush=True)

def observe():
 assert state.get('runVerified')
 for role,entry in state['frameworks'].items():
  expected=5 if role=='source' else 9;q=urllib.parse.urlencode({'agentId':entry['agentId']})
  events=api('/observability/agent-executions?'+q);assert len(events['items'])==expected and not events['hasMore']
  streams={e['streamKey'] for e in events['items']};assert len(streams)==1;stream=streams.pop();graph=api('/observability/agent-executions/'+stream+'/graph')
  assert graph['complete'] and graph['gaps']==[] and len(graph['runs'])==(2 if role=='source' else 3)
  assert all(r['state']=='SUCCEEDED' for r in graph['runs']) and len(graph['artifacts'])==1 and len(graph['tools'])==(0 if role=='source' else 1)
  links=api('/observability/agent-executions/'+stream+'/links');assert len(links['items'])==2 and not links['hasMore']
  assert {l['relation'] for l in links['items']}=={'DELEGATE','CODE_HANDOFF'}
  assert all(l['state']=='CONFIRMED' and l['executionPermissionGranted'] is False and l['sourceRuntimeKey']!=l['targetRuntimeKey'] for l in links['items'])
  reports=api('/observability/agent-executions/deliveries?'+q);assert len(reports['items'])==1
  assert reports['items'][0]['pending']==0 and reports['items'][0]['receivedEvents']==expected
  calls=api('/registry/runtime-tools?'+urllib.parse.urlencode({'instanceKey':entry['instance']['instanceKey']}))
  assert len(calls)==(0 if role=='source' else 1)
  if calls:assert calls[0]['state']=='SUCCEEDED'
  entry.update(streamKey=stream,events=events,graph=graph,links=links,deliveryReport=reports['items'][0],toolCalls=calls);save()
 source=state['frameworks']['source'];target=state['frameworks']['target']
 assert source['streamKey']!=target['streamKey'] and source['instance']['instanceKey']!=target['instance']['instanceKey']
 assert source['links']==target['links']
 assert {e['event']['traceId'] for e in source['events']['items']}.isdisjoint({e['event']['traceId'] for e in target['events']['items']})
 code=next(l for l in source['links']['items'] if l['relation']=='CODE_HANDOFF')
 assert code['artifactDigest']==source['graph']['artifacts'][0]['artifactDigest']==target['graph']['artifacts'][0]['artifactDigest']
 assert code['sourceRun']==source['graph']['artifacts'][0]['runId'] and code['targetRun']==target['graph']['artifacts'][0]['runId']
 roles=[]
 for user in ['viewer','operator','governance','admin']:
  assert api('/platform/me',user=user)['capabilities']['viewConsole']
  for entry in [source,target]:assert api('/observability/agent-executions/'+entry['streamKey']+'/links',user=user)==entry['links']
  roles.append({'user':user,'realConfirmedRelationsRead':2,'passed':True})
 state['roles']=roles;state['observationVerified']=True;save()
 print('14_EVENTS_5_RUNS_1_TOOL_2_CONFIRMED_LINKS',flush=True)

def reclaim():
 assert state.get('observationVerified')
 for role,entry in state['frameworks'].items():
  key=entry['instance']['instanceKey']
  assert set(m.sql("select state from runtime_work_claims where actor_key='"+key+"'").splitlines())=={b'DONE'}
  command=write('explicit-suspend-'+role,'/control/commands',{'agentId':entry['agentId'],'runtimeInstanceKey':key,'commandType':'PAUSE','issuedBy':'integration-verification'})
  deadline=time.monotonic()+120
  while command['status'] in ['QUEUED','DISPATCHED']:
   assert time.monotonic()<deadline;time.sleep(2);command=next(x for x in api('/control/commands') if x['commandKey']==command['commandKey'])
  assert command['status']=='ACKED';entry['explicitReclaim']=command;save()
  q=urllib.parse.urlencode({'instanceKey':key});policy=api('/registry/runtime-tools/policy?'+q)
  assert policy['claimedCount']==(1 if role=='target' else 0)
  if role=='target':write('disable-policy-'+role,'/registry/runtime-tools/policy',{'instanceKey':key,'expectedRevision':policy['revision'],'enabled':False,'allowedTools':policy['allowedTools'],'claimLimit':1},'PUT')
  write('revoke-'+role,'/registry/runtime-connection/revoke',{'instanceKey':key})
  write('pause-'+role,'/agents/'+str(entry['agentId'])+'/status',{'lifecycleStatus':'PAUSED'})
  entry['finalConnection']=api('/registry/runtime-connection?'+q);assert entry['finalConnection']['connection']=='REVOKED'
  entry['finalInstance']=api('/agents/'+str(entry['agentId'])+'/substrate/instances')[0];assert entry['finalInstance']['state']=='SUSPENDED'
  assert api('/observability/agent-executions/'+entry['streamKey']+'/links')==entry['links']
  assert api('/observability/agent-executions/'+entry['streamKey']+'/graph')['complete'];save()
 state['reclaimed']=True;save()
 m.put('live-proof.json',{'passed':True,'workflowId':state['workflowId'],'runKey':state['runKey'],'streams':[{'agentId':e['agentId'],'streamKey':e['streamKey'],'runs':len(e['graph']['runs'])} for e in state['frameworks'].values()],'links':state['frameworks']['source']['links'],'roles':state['roles'],'events':14,'tools':1,'models':0,'metadataResends':4,'reclaimed':True})
 print('EAP_RECLAIMED_AND_REVOKED_ORIGINAL_LINKS_RETAINED',flush=True)

if __name__=='__main__':
 import sys
 {'prepare':prepare,'run':run,'observe':observe,'reclaim':reclaim}[sys.argv[1]]()

