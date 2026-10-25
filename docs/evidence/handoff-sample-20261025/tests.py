import json,os,subprocess,sys
import pipeline as m
assert not (m.ROOT/'closed.json').exists()
env=os.environ.copy(); env['PATH']='/usr/local/go/bin:'+env['PATH'];env['GOTOOLCHAIN']='local';env['GOPROXY']='off'
agent=m.ROOT/'source/runtime/agent-runner';sample=m.ROOT/'source/runtime/handoff-sample'
# Formatting is performed on the remote candidate, then returned for local text synchronization.
subprocess.run(['/usr/local/go/bin/gofmt','-w',str(agent/'handoff_engine.go'),str(agent/'handoff_engine_test.go')],check=True)
proof={}
commands=[('native',['/usr/local/go/bin/go','test','-json','./...'],agent),
 ('framework',['/usr/local/go/bin/go','test','-json','-tags','framework','./...'],agent),
 ('handoff',['/usr/local/go/bin/go','test','-json','-tags','handoff','./...'],agent),
 ('handoff-race',['/usr/local/go/bin/go','test','-race','-json','-tags','handoff','-run','TestHandoff','./...'],agent),
 ('vet',['/usr/local/go/bin/go','vet','-tags','handoff','./...'],agent)]
for name,cmd,cwd in commands:
 with (m.ROOT/(name+'-r1.log')).open('xb') as out:
  done=subprocess.run(cmd,cwd=cwd,env=env,stdout=out,stderr=subprocess.STDOUT)
 rows=[]
 for line in (m.ROOT/(name+'-r1.log')).read_text().splitlines():
  try: rows.append(json.loads(line))
  except ValueError: pass
 tests=[r for r in rows if r.get('Action')=='pass' and 'Test' in r]
 proof[name]={'passed':done.returncode==0,'topLevelTests':sum('/' not in r['Test'] for r in tests),'includingSubtests':len(tests)}
 m.put(name+'-r1.json',proof[name]);print(name,proof[name],flush=True)
 assert done.returncode==0, name+' failed'
python='/root/eap-framework-adapters-20261024-r1/venv/bin/python'
env['PYTHONPATH']=str(sample)+':'+str(m.ROOT/'source/runtime/execution-observer')
for name,path in [('handoff-python',sample),('observer-python',m.ROOT/'source/runtime/execution-observer')]:
 with (m.ROOT/(name+'-r1.log')).open('xb') as out:
  done=subprocess.run([python,str(m.ROOT/'python-suite.py'),str(path),'test_*.py',str(m.OUT/(name+'-r1.json'))],cwd=path,env=env,stdout=out,stderr=subprocess.STDOUT)
 print(name,done.returncode,flush=True); assert done.returncode==0,name+' failed'
for role in ['source','target']:
 subprocess.run(['/usr/local/go/bin/go','build','-trimpath','-tags','handoff','-ldflags','-s -w -X main.handoffRole='+role,'-o',str(m.ROOT/'binaries'/('eap-handoff-'+role)),'.'],cwd=agent,env=dict(env,CGO_ENABLED='0'),check=True)
m.put('tests-r1-complete.json',{'passed':True,'go':proof,'binaries':{p.name:m.sha(p.read_bytes()) for p in (m.ROOT/'binaries').iterdir()}})
print('TESTS_AND_BINARIES_READY',flush=True)
