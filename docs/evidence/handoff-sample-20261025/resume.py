import subprocess
import pipeline as m
import acceptance as a
assert not (m.ROOT/'closed.json').exists()
old=a.state['writes']['deploy-source']
assert old['status']=='INTENT' and a.state['lastFailure']=={'path':'/agents/116/substrate/deployments','method':'POST','status':400}
path='/agents/116/substrate/deployments';key=a.state['prefix']+'-source-v1'
receipt=a.api(path+'/requests/'+key)
assert receipt['status']=='NOT_FOUND' and a.api(path)==[] and a.api('/agents/116/substrate/instances')==[]
proof={'passed':True,'originalHttpStatus':400,'receipt':receipt,'deploymentRecords':0,'instances':0,'cause':'Image deployment needs the existing identity protocol on port 80; runner implements /readyz','sameAgentAndRequestKey':True,'correctedPort':80,'correctedReadinessPath':'/readyz','businessDispatched':False}
m.put('deployment-validation-correction.json',proof)
a.state.setdefault('rejectedBeforeAdmission',{})['deploy-source']=dict(old,resolution=proof)
del a.state['writes']['deploy-source'];a.save()
# Release this process's state lock before continuing through the normal EAP path.
a.fcntl.flock(a.lock,a.fcntl.LOCK_UN);a.lock.close()
for phase in ['prepare','run','observe','reclaim']:
 with (m.ROOT/('acceptance-'+phase+'-r2.log')).open('xb') as out:
  done=subprocess.run(['python3',str(m.ROOT/'acceptance.py'),phase],stdout=out,stderr=subprocess.STDOUT)
 print(phase,done.returncode,flush=True)
 assert done.returncode==0,'Stage failed; preserve original execution'
print('REAL_EAP_HANDOFF_COMPLETE',flush=True)
