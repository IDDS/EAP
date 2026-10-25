import io,json,tarfile,subprocess,time,re
import pipeline as m
assert not (m.ROOT/'closed.json').exists()
assert m.load('tests-r5-complete.json')['passed'] and m.load('race-proof.json')['passed']
m.space()
base=m.REG+'/eap-framework-runner@sha256:155c6cf52557a95b8d3c2c5ffaead590cd9ae2c14e272c4fafc8903f8a0cf529'
bm,bc,_=m.registry(base)
files={n:m.ROOT/'binaries'/n for n in ['eap-handoff-source','eap-handoff-target']}
files.update({n:m.ROOT/'source/runtime/handoff-sample'/n for n in ['Dockerfile','handoff_worker.py']})
files.update({n:m.ROOT/'source/runtime/execution-observer'/n for n in ['eap_execution.py','eap_links.py']})
expected={n:m.sha(p.read_bytes()) for n,p in files.items()};m.put('image-context-manifest.json',expected)
archive=m.ROOT/'image-context.tgz'
with tarfile.open(archive,'w:gz') as tar:
 for n,p in files.items():tar.add(p,arcname=n,recursive=False)
name='eap-handoff-image-20261025-r1';mount=[{'name':'shared','mountPath':'/shared'}]
pod={'apiVersion':'v1','kind':'Pod','metadata':{'name':name,'namespace':'eap'},'spec':{'nodeName':'ate-node2','restartPolicy':'Never','automountServiceAccountToken':False,'activeDeadlineSeconds':1200,'volumes':[{'name':'shared','emptyDir':{}}],
 'initContainers':[{'name':'stage','image':'docker.m.daocloud.io/library/node@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402','command':['sh','-c','mkdir /shared/context && while [ ! -f /shared/start ]; do sleep 1; done'],'volumeMounts':mount}],
 'containers':[{'name':'build','image':m.KANIKO,'args':['--context=dir:///shared/context','--dockerfile=Dockerfile','--destination='+m.REG+'/eap-handoff-sample:20261025-r1','--build-arg=FRAMEWORK_BASE='+base,'--insecure','--insecure-pull','--single-snapshot','--digest-file=/dev/termination-log'],'resources':{'requests':{'cpu':'100m','memory':'256Mi'},'limits':{'cpu':'2','memory':'1536Mi'}},'volumeMounts':mount}]}}
m.put('image-pod-intent.json',pod);p=json.loads(m.run(['kubectl','create','-f','-','-o','json'],input=json.dumps(pod).encode()));m.put('image-pod-owned.json',{'name':name,'uid':p['metadata']['uid']})
deadline=time.monotonic()+180
while True:
 p=m.kget('pod',name)
 if any('running' in c['state'] for c in p['status'].get('initContainerStatuses',[])):break
 assert time.monotonic()<deadline;time.sleep(2)
with archive.open('rb') as source:subprocess.run(['kubectl','-n','eap','exec','-i',name,'-c','stage','--','tar','xzf','-','-C','/shared/context'],stdin=source,check=True)
raw=m.run(['kubectl','-n','eap','exec',name,'-c','stage','--','sha256sum']+['/shared/context/'+n for n in sorted(expected)]).decode().splitlines()
assert {r.split()[1].removeprefix('/shared/context/'):r.split()[0] for r in raw}==expected
m.run(['kubectl','-n','eap','exec',name,'-c','stage','--','touch','/shared/start']);print('IMAGE_BUILD_STARTED',flush=True)
deadline=time.monotonic()+1100
while True:
 p=m.kget('pod',name);assert p['metadata']['uid']==m.load('image-pod-owned.json')['uid']
 if p['status']['phase'] in ['Succeeded','Failed']:break
 assert time.monotonic()<deadline;time.sleep(4)
(m.ROOT/'image-build.log').write_bytes(m.run(['kubectl','-n','eap','logs',name,'-c','build']))
assert p['status']['phase']=='Succeeded'
d=p['status']['containerStatuses'][0]['state']['terminated']['message'].strip();assert re.fullmatch(r'sha256:[a-f0-9]{64}',d)
image=m.REG+'/eap-handoff-sample@'+d;manifest,config,url=m.registry(image)
assert manifest['layers'][:-1]==bm['layers']
assert config['config']['Entrypoint']==['/usr/local/bin/eap-handoff-source']
raw=m.http(url+'/blobs/'+manifest['layers'][-1]['digest']);assert 'sha256:'+m.sha(raw)==manifest['layers'][-1]['digest']
payload={('usr/local/bin/'+n if n.startswith('eap-handoff-') else 'opt/eap-handoff/'+n):h for n,h in expected.items() if n!='Dockerfile'}
with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
 actual={x.name.lstrip('./'):m.sha(tar.extractfile(x).read()) for x in tar.getmembers() if x.isfile() and x.name.lstrip('./') in payload}
assert actual==payload
m.put('runtime-image-proof.json',{'passed':True,'image':image,'baseImage':base,'payload':actual,'compressedBytes':sum(x['size'] for x in manifest['layers']),'defaultFrameworkImageChanged':False})
m.cleanup('image-pod');print('HANDOFF_IMAGE_READY',image,flush=True)
