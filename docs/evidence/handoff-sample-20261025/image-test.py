import json,time
import pipeline as m
assert m.load('runtime-image-proof.json')['passed'] and not (m.ROOT/'closed.json').exists()
name='eap-handoff-image-test-20261025-r1'
cm={'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':name,'namespace':'eap'},'data':{'test_handoff_worker.py':(m.ROOT/'source/runtime/handoff-sample/test_handoff_worker.py').read_text()}}
m.put('probe-config-intent.json',cm);c=json.loads(m.run(['kubectl','create','-f','-','-o','json'],input=json.dumps(cm).encode()));m.put('probe-config-owned.json',{'name':name,'uid':c['metadata']['uid']})
pod={'apiVersion':'v1','kind':'Pod','metadata':{'name':name,'namespace':'eap'},'spec':{'nodeName':'ate-node2','restartPolicy':'Never','automountServiceAccountToken':False,'activeDeadlineSeconds':240,'containers':[{'name':'verify','image':m.load('runtime-image-proof.json')['image'],'command':['/usr/bin/python3','-m','unittest','discover','-s','/tests','-v'],'env':[{'name':'PYTHONPATH','value':'/opt/eap-handoff:/opt/eap-framework:/opt/eap-framework/site'},{'name':'PYTHONDONTWRITEBYTECODE','value':'1'},{'name':'LANGSMITH_TRACING','value':'false'},{'name':'LANGCHAIN_TRACING_V2','value':'false'}],'resources':{'requests':{'cpu':'100m','memory':'256Mi'},'limits':{'cpu':'2','memory':'512Mi'}},'volumeMounts':[{'name':'tests','mountPath':'/tests','readOnly':True}]}],'volumes':[{'name':'tests','configMap':{'name':name}}]}}
m.put('probe-pod-intent.json',pod);p=json.loads(m.run(['kubectl','create','-f','-','-o','json'],input=json.dumps(pod).encode()));m.put('probe-pod-owned.json',{'name':name,'uid':p['metadata']['uid']})
deadline=time.monotonic()+240
while True:
 current=m.kget('pod',name);assert current['metadata']['uid']==p['metadata']['uid']
 if current['status']['phase'] in ['Succeeded','Failed']:break
 assert time.monotonic()<deadline;time.sleep(3)
raw=m.run(['kubectl','-n','eap','logs',name]);(m.ROOT/'image-test.log').write_bytes(raw)
assert current['status']['phase']=='Succeeded' and b'Ran 13 tests' in raw and b'\nOK\n' in raw
m.put('image-test-proof.json',{'passed':True,'tests':13,'actualDigest':m.load('runtime-image-proof.json')['image'],'realChildProcesses':True})
m.cleanup('probe-pod')
m.run(['kubectl','delete','--raw','/api/v1/namespaces/eap/configmaps/'+name,'-f','-'],input=json.dumps({'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':c['metadata']['uid']}}).encode());m.put('probe-config-cleanup.json',{'name':name,'uid':c['metadata']['uid'],'deleteRequested':True})
print('ACTUAL_IMAGE_13_TESTS_PASSED',flush=True)
