import json,sys,unittest
suite=unittest.defaultTestLoader.discover(sys.argv[1],pattern=sys.argv[2])
result=unittest.TextTestRunner(verbosity=2).run(suite)
proof={'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),
       'skipped':len(result.skipped),'failedCases':[x.id() for x,_ in result.failures+result.errors],
       'passed':result.wasSuccessful() and not result.skipped and result.testsRun>0}
with open(sys.argv[3],'x') as f:json.dump(proof,f,indent=2);f.write('\n')
print(json.dumps(proof),flush=True)
sys.exit(0 if proof['passed'] else 1)
