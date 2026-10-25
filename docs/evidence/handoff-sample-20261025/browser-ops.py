import json,subprocess,time,urllib.request
import pipeline as m
assert not (m.ROOT/'closed.json').exists()
name='eap-handoff-browser'
cm={'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':name,'namespace':'eap'},'data':{n:(m.ROOT/'contract'/n).read_text() for n in ['browser.mjs','ui-install.sh']}}
m.put('browser-config-intent.json',cm);x=json.loads(m.run(['kubectl','create','-f','-','-o','json'],input=json.dumps(cm).encode()));m.put('browser-config-owned.json',{'name':name,'uid':x['metadata']['uid']})
pod={'apiVersion':'v1','kind':'Pod','metadata':{'name':name,'namespace':'eap'},'spec':{'nodeName':'ate-node2','hostname':name,'restartPolicy':'Never','automountServiceAccountToken':False,'activeDeadlineSeconds':7200,'containers':[{'name':'browser','image':'docker.m.daocloud.io/library/node@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402','command':['sh','-c','sh /contract/ui-install.sh; printf "%s" "$?" > /out/install-exit-code; exec tail -f /dev/null'],'resources':{'requests':{'cpu':'100m','memory':'512Mi'},'limits':{'cpu':'2','memory':'1536Mi'}},'volumeMounts':[{'name':n,'mountPath':('/dev/shm' if n=='shm' else '/'+n),'readOnly':n=='contract'} for n in ['contract','out','work','shm']]}],'volumes':[{'name':'contract','configMap':{'name':name}}]+[{'name':n,'emptyDir':{'medium':'Memory','sizeLimit':'512Mi'} if n=='shm' else {}} for n in ['out','work','shm']]}}
m.put('browser-pod-intent.json',pod);x=json.loads(m.run(['kubectl','create','-f','-','-o','json'],input=json.dumps(pod).encode()));m.put('browser-pod-owned.json',{'name':name,'uid':x['metadata']['uid']})
deadline=time.monotonic()+600
while True:
 p=m.kget('pod',name);assert p['metadata']['uid']==x['metadata']['uid'] and p['status']['phase']!='Failed'
 if p['status']['phase']=='Running':
  ready=subprocess.run(['kubectl','-n','eap','exec',name,'--','test','-f','/out/ready'],capture_output=True)
  if ready.returncode==0:break
  failed=subprocess.run(['kubectl','-n','eap','exec',name,'--','test','-f','/out/install-exit-code'],capture_output=True);assert failed.returncode!=0,'Browser installer failed; inspect retained pod'
 assert time.monotonic()<deadline;time.sleep(3)
print('BROWSER_READY',flush=True)
deadline=time.monotonic()+3600
while not (m.OUT/'live-proof.json').exists():
 assert time.monotonic()<deadline;time.sleep(5)
assert m.health()==m.load('before.json')
data=m.jrun(['kubectl','-n','eap-auth','get','secret','eap-test-users','-o','json'])['data']
service=m.kget('service','frontend')
with urllib.request.urlopen('http://'+service['spec']['clusterIP']+'/version.json',timeout=20) as response:build=json.load(response)['buildId']
m.put('browser-run-intent.json',{'podUid':x['metadata']['uid'],'realRoleCases':8,'syntheticLinkCases':0,'businessWritesAllowed':False})
result=subprocess.run(['kubectl','-n','eap','exec','-i',name,'--','node','/contract/browser.mjs'],input=json.dumps({'data':data,'buildId':build,'live':m.load('live-proof.json')}).encode(),capture_output=True,timeout=600)
out=m.ROOT/'ui-out';out.mkdir();subprocess.run(['kubectl','-n','eap','cp',name+':/out/.',str(out)],capture_output=True,check=True,timeout=120)
report=json.loads((out/'ui-report.json').read_text());m.put('browser-result.json',report);assert result.returncode==0 and report['passed']
m.cleanup('browser-pod')
owned=m.load('browser-config-owned.json');current=m.kget('configmap',owned['name']);assert current['metadata']['uid']==owned['uid']
m.run(['kubectl','delete','--raw','/api/v1/namespaces/eap/configmaps/'+owned['name'],'-f','-'],input=json.dumps({'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':owned['uid']}}).encode());m.put('browser-config-cleanup.json',dict(owned,deleteRequested=True))
print('LINKS_BROWSER_PASSED',flush=True)
