import subprocess,time
import pipeline as m
assert not (m.ROOT/'closed.json').exists()
deadline=time.monotonic()+1200
while not (m.OUT/'race-proof.json').exists():
 assert time.monotonic()<deadline
 if (m.ROOT/'race-pod.log').exists() and b'RACE_PASSED' not in (m.ROOT/'race-pod.log').read_bytes():raise AssertionError('Race failed; inspect retained logs')
 time.sleep(4)
assert m.load('race-proof.json')['passed'] and m.load('tests-r5-complete.json')['passed']
for name,args in [('image',[]),('image-test',[]),('acceptance',['prepare']),('acceptance',['run']),('acceptance',['observe']),('acceptance',['reclaim'])]:
 log=m.ROOT/(name+('-'+args[0] if args else '')+'-chain.log')
 with log.open('xb') as out:done=subprocess.run(['python3',str(m.ROOT/(name+'.py'))]+args,stdout=out,stderr=subprocess.STDOUT)
 print(name,args,done.returncode,flush=True)
 assert done.returncode==0,'Stage failed; inspect original intent, never redispatch unknown business'
print('REAL_HANDOFF_EAP_CLOSED_LOOP_PASSED',flush=True)
