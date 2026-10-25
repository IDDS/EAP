"""Independent, guarded clean-image batch; all old batches remain closed."""

import copy, hashlib, io, json, os, shutil, subprocess, sys, tarfile, time, urllib.request

from pathlib import Path

ROOT=Path('/root/eap-handoff-sample-20261025-r1')

OUT=ROOT/'evidence'

SRC=Path('/root/eap-src/deploy/delivery')

PREVIOUS=Path('/root/eap-product-20261022-r2')

REG='10.186.133.66:5001'

NS='eap'

KANIKO='gcr.m.daocloud.io/kaniko-project/executor@sha256:9e69fd4330ec887829c780f5126dd80edc663df6def362cd22e79bcdf00ac53f'

sys.path.insert(0,str(SRC))

import image_build, eapctl

def run(args, **kwargs):return subprocess.check_output(args,stderr=subprocess.PIPE,**kwargs)

def jrun(args):return json.loads(run(args))

def kget(kind,name):return jrun(['kubectl','-n',NS,'get',kind,name,'-o','json'])

def sha(b):return hashlib.sha256(b).hexdigest()

def digest(o):return sha(json.dumps(o,sort_keys=True).encode())

def put(name,data):
    assert not (ROOT/'closed.json').exists(),'Closed batch'
    with (OUT/name).open('x') as f:json.dump(data,f,indent=2);f.write('\n')

def load(name):return json.loads((OUT/name).read_text())

def http(url):
    q=urllib.request.Request(url,headers={'Accept':'application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json' if '/v2/' in url else '*/*'})
    with urllib.request.urlopen(q,timeout=60) as r:return r.read()

def registry(ref):
    repo,d=ref.split('/',1)[1].split('@');base='http://'+REG+'/v2/'+repo
    raw=http(base+'/manifests/'+d);assert 'sha256:'+sha(raw)==d
    m=json.loads(raw);raw=http(base+'/blobs/'+m['config']['digest']);assert 'sha256:'+sha(raw)==m['config']['digest']
    c=json.loads(raw);assert c['os']=='linux' and c['architecture']=='amd64'
    return m,c,base

def health():
    result={}
    for app in ['backend','frontend']:
        d=kget('deployment',app);pods=jrun(['kubectl','-n',NS,'get','pods','-l','app='+app,'-o','json'])['items']
        result[app]={'deploymentUid':d['metadata']['uid'],'image':d['spec']['template']['spec']['containers'][0]['image'],'specSha256':digest(d['spec']),
            'pods':[{'name':p['metadata']['name'],'uid':p['metadata']['uid'],'phase':p['status']['phase'],'containers':[{'ready':c['ready'],'restarts':c['restartCount'],'imageID':c['imageID']} for c in p['status'].get('containerStatuses',[])]} for p in pods]}
    s=kget('service','backend');url='http://'+s['spec']['clusterIP']+':8080'
    result['health']=json.loads(http(url+'/actuator/health'))['status']
    result['nodes']={n['metadata']['name']:{c['type']:c['status'] for c in n['status']['conditions']} for n in jrun(['kubectl','get','nodes','-o','json'])['items']}
    return result

def space():
    s=jrun(['kubectl','get','--raw','/api/v1/nodes/ate-node1/proxy/stats/summary'])
    c=jrun(['kubectl','get','--raw','/api/v1/nodes/ate-node1/proxy/configz'])['kubeletconfig']['evictionHard']
    out={}
    for key,stats in [('nodefs',s['node']['fs']),('imagefs',s['node']['runtime']['imageFs'])]:
        threshold=c[key+'.available'];assert threshold.endswith('%')
        margin=int(stats['availableBytes']-stats['capacityBytes']*float(threshold[:-1])/100)
        out[key]={'availableBytes':stats['availableBytes'],'capacityBytes':stats['capacityBytes'],'evictionThreshold':threshold,'marginBytes':margin}
        assert margin>1024**3,'Insufficient build headroom'
    return out

def protected():
    out={}
    for ns in ['eap','eap-auth','otel-system']:
        for kind in ['deployment','statefulset','service','configmap']:
            for x in jrun(['kubectl','-n',ns,'get',kind,'-o','json'])['items']:
                if ns=='eap' and kind=='deployment' and x['metadata']['name'] in ['backend','frontend']:continue
                out[ns+'/'+kind+'/'+x['metadata']['name']]={'uid':x['metadata']['uid'],'sha256':digest(x.get('spec',x.get('data',{})))}
        out[ns+'/secret-identities']=run(['kubectl','-n',ns,'get','secrets','-o',r'jsonpath={range .items[*]}{.metadata.name}{" "}{.metadata.uid}{" "}{.metadata.resourceVersion}{"\n"}{end}']).decode().splitlines()
    for key in ['browser-config','probe-config','probe-r2-config']:
        if (OUT/(key+'-owned.json')).exists():
            owned=load(key+'-owned.json');path='eap/configmap/'+owned['name']
            if path in out:
                assert out[path]['uid']==owned['uid'];out.pop(path)
    return out

def integrity():
    old=load('integrity-before.json') if (OUT/'integrity-before.json').exists() else None
    out={}
    for table in ['workflow_executions','tool_invocation_events','budget_accounts','budget_reservations','model_invocation_events','runtime_work_claims','runtime_work_actors','eap_chat_submissions','observation_operations','observation_operation_events','observation_runtime_placements']:
        cols=old[table]['columns'] if old else [r.split(b'\t')[0].decode() for r in sql('show columns from '+table).splitlines()]
        boundColumn='operation_id' if table=='observation_operation_events' else 'id'
        upper=old[table]['maxId'] if old else (int(sql('select coalesce(max('+boundColumn+'),0) from '+table).strip()) if table in ['workflow_executions','observation_operations','observation_operation_events'] else None)
        rows=sorted(sql('select '+','.join(cols)+' from '+table+(' where '+boundColumn+'<='+str(upper) if upper is not None else '')).splitlines())
        out[table]={'columns':cols,'maxId':upper,'rows':len(rows),'sha256':sha(b'\n'.join(rows))}
    return out

def cleanup(key):
    x=load(key+'-owned.json');p=kget('pod',x['name']);assert p['metadata']['uid']==x['uid']
    put(key+'-cleanup-intent.json',x)
    run(['kubectl','delete','--raw','/api/v1/namespaces/eap/pods/'+x['name'],'-f','-'],input=json.dumps({'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':x['uid']}}).encode())
    put(key+'-cleanup.json',dict(x,deleteRequested=True))

def sql(query):
    assert all(c not in query for c in ['"','$','`'])
    command='MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -N "$MYSQL_DATABASE" -e "'+query+'"'
    return run(['kubectl','-n',NS,'exec','deployment/mysql','--','sh','-c',command])

def plans():
    old=load('plans-before.json') if (OUT/'plans-before.json').exists() else None
    result={}
    for t in ['agent_teams','agent_team_members','agent_plans','agent_plan_steps']:
        cols=old[t]['columns'] if old else [r.split(b'\t')[0].decode() for r in sql('show columns from '+t).splitlines()]
        upper=old[t]['maxId'] if old else int(sql('select coalesce(max(id),0) from '+t).strip())
        rows=sorted(sql('select '+','.join(cols)+' from '+t+' where id<='+str(upper)).splitlines())
        result[t]={'columns':cols,'maxId':upper,'rows':len(rows),'sha256':sha(b'\n'.join(rows))}
    return result

def build(app):
    proof=load(app+'-context-proof.json');image_build.verify(ROOT/(app+'-context'),SRC/'runtime-bases.json',proof['contextSha256'])
    s=jrun(['kubectl','get','--raw','/api/v1/nodes/ate-node2/proxy/stats/summary']);assert s['node']['fs']['availableBytes']>20*1024**3
    name='eap-execution-links-image-'+app+'-20261025-r1'
    mount=[{'name':'shared','mountPath':'/shared'}]
    pod={'apiVersion':'v1','kind':'Pod','metadata':{'name':name,'namespace':NS},'spec':{'nodeName':'ate-node2','automountServiceAccountToken':False,'restartPolicy':'Never','activeDeadlineSeconds':1200,'volumes':[{'name':'shared','emptyDir':{}}],
      'initContainers':[{'name':'stage','image':'docker.m.daocloud.io/library/node@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402','command':['sh','-c','mkdir /shared/context && while [ ! -f /shared/start ]; do sleep 1; done'],'volumeMounts':mount}],
      'containers':[{'name':'build','image':KANIKO,'args':['--context=dir:///shared/context','--dockerfile=Dockerfile','--destination='+REG+'/eap-'+app+':execution-links-20261025-r1','--insecure','--insecure-pull','--single-snapshot','--digest-file=/dev/termination-log'],'resources':{'requests':{'cpu':'100m','memory':'256Mi'},'limits':{'cpu':'2','memory':'1536Mi'}},'volumeMounts':mount}]}}
    put(app+'-build-request.json',pod);x=json.loads(run(['kubectl','create','-f','-','-o','json'],input=json.dumps(pod).encode()));put(app+'-image-owned.json',{'name':name,'uid':x['metadata']['uid']});print('IMAGE_POD_CREATED',app,flush=True)

def stage(app):
    x=load(app+'-image-owned.json');p=kget('pod',x['name']);assert p['metadata']['uid']==x['uid']
    assert 'running' in p['status']['initContainerStatuses'][0]['state'],'Init not ready'
    proof=load(app+'-context-proof.json');image_build.verify(ROOT/(app+'-context'),SRC/'runtime-bases.json',proof['contextSha256'])
    expected=image_build.files(ROOT/(app+'-context'));put(app+'-stage-intent.json',{'uid':x['uid'],'files':expected})
    memory=io.BytesIO()
    with tarfile.open(fileobj=memory,mode='w:gz',compresslevel=1) as t:
        for n in expected:t.add(ROOT/(app+'-context')/n,arcname=n,recursive=False)
    run(['kubectl','-n',NS,'exec','-i',x['name'],'-c','stage','--','tar','xzf','-','-C','/shared/context'],input=memory.getvalue(),timeout=180)
    actual=run(['kubectl','-n',NS,'exec',x['name'],'-c','stage','--','sha256sum']+['/shared/context/'+n for n in sorted(expected)],timeout=60).decode().splitlines()
    assert {line.split()[1].removeprefix('/shared/context/'):line.split()[0] for line in actual}==expected
    put(app+'-staged-proof.json',{'passed':True,'files':len(expected),'contextSha256':proof['contextSha256']})
    run(['kubectl','-n',NS,'exec',x['name'],'-c','stage','--','touch','/shared/start']);print('IMAGE_BUILD_STARTED',app,flush=True)

def verify_image(app):
    owned=load(app+'-image-owned.json');p=kget('pod',owned['name']);assert p['metadata']['uid']==owned['uid']
    if p['status']['phase']!='Succeeded':
        print('BUILD_STATE',app,p['status']['phase'],flush=True);return
    (OUT/(app+'-build.log')).write_bytes(run(['kubectl','-n',NS,'logs',owned['name'],'-c','build']))
    d=p['status']['containerStatuses'][0]['state']['terminated']['message'].strip();assert __import__('re').fullmatch('sha256:[a-f0-9]{64}',d);ref=REG+'/eap-'+app+'@'+d;m,c,b=registry(ref)
    old=load('original-images.json')[app];bm,bc,bb=registry(old['base'])
    assert m['layers'][:-1]==bm['layers'],'Must only inherit clean runtime layers'
    assert c['config']==old['config']['config'],'Runtime config changed'
    expected=json.loads((ROOT/(app+'-artifacts.json')).read_text())
    expected={('app/eap.jar' if n=='app.jar' else 'etc/nginx/conf.d/default.conf' if n=='nginx.conf' else 'usr/share/nginx/html/'+n.removeprefix('dist/')):v for n,v in expected.items()}
    raw=http(b+'/blobs/'+m['layers'][-1]['digest']);assert 'sha256:'+sha(raw)==m['layers'][-1]['digest']
    with tarfile.open(fileobj=io.BytesIO(raw)) as t:
        members=t.getmembers();actual={x.name.lstrip('./'):sha(t.extractfile(x).read()) for x in members if x.isfile()}
    assert all(actual.get(n)==v for n,v in expected.items())
    whiteouts=[]
    if app=='frontend':
        whiteouts=[n for n in actual if n.startswith('usr/share/nginx/html/') and Path(n).name.startswith('.wh.')]
        assert set(whiteouts)<= {'usr/share/nginx/html/.wh.50x.html'}
        assert all(actual[n]==sha(b'') for n in whiteouts)
        assert {n:v for n,v in actual.items() if n.startswith('usr/share/nginx/html/') and n not in whiteouts}=={n:v for n,v in expected.items() if n.startswith('usr/share/nginx/html/')}
    compressed=sum(x['size'] for x in m['layers'])
    put(app+'-image-proof.json',{'passed':True,'image':ref,'baseImage':old['base'],'layers':len(m['layers']),'previousLayers':len(old['manifest']['layers']),'compressedBytes':compressed,'previousCompressedBytes':old['compressedBytes'],'reductionPercent':round((1-compressed/old['compressedBytes'])*100,3),'runtimeConfigUnchanged':True,'payloadFilesVerified':len(expected),'deletionMarkers':whiteouts,'manifest':m,'config':c})
    delete={'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':owned['uid']}}
    run(['kubectl','delete','--raw','/api/v1/namespaces/'+NS+'/pods/'+owned['name'],'-f','-'],input=json.dumps(delete).encode())
    put(app+'-image-cleanup.json',{'name':owned['name'],'uid':owned['uid'],'deleteRequested':True})
    print('IMAGE_VERIFIED',app,ref,'layers',len(m['layers']),'bytes',compressed,flush=True)
if __name__=="__main__":
 assert not (ROOT/"closed.json").exists()
 action,app=sys.argv[1:];assert app=="frontend"
 {"build":build,"stage":stage,"verify-image":verify_image}[action](app)
