import http.client
import json
import threading
import time
import unittest
from unittest.mock import patch
import server
from adapter import ENGINE_VERSION, SearchResult, ContractError


class WorkerProtocolTests(unittest.TestCase):
    def setUp(self):
        self.release=threading.Event();self.started=threading.Event();self.contexts=[];self.receipts=[];self.searches=[]
        owner=self
        class Backend:
            def __init__(self,run_id,claim):
                if not isinstance(run_id,int) or isinstance(run_id,bool) or run_id<1 or claim!='a'*64:
                    raise ContractError('CLAIM_REJECTED')
                self.run_id=run_id
            def context(self):
                owner.contexts.append(self.run_id)
                return {'runId':self.run_id,'approved':True}
            def _request(self,path,body):
                owner.receipts.append((path,body))
                if owner.lost_seal:raise OSError('receipt unknown')
        def search(context,backend):
            self.searches.append(context['runId']);self.started.set();self.release.wait(3)
            if self.failed_search:raise ContractError('UNKNOWN')
            return SearchResult({'system_prompt':'candidate'},'b'*64,1,1,0,ENGINE_VERSION,None)
        self.failed_search=False;self.lost_seal=False
        self.patches=[patch.object(server,'HttpBackend',Backend),patch.object(server,'search',search)]
        for p in self.patches:p.start()
        class Handler(server.Handler):state=server.State()
        self.handler=Handler;self.http=server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.thread=threading.Thread(target=self.http.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):
        self.release.set();self.done();self.http.shutdown();self.http.server_close();self.thread.join(2)
        for p in reversed(self.patches):p.stop()
    def request(self,path='/run',body=None,claim='a'*64,method='POST'):
        conn=http.client.HTTPConnection('127.0.0.1',self.http.server_port,timeout=3)
        raw=json.dumps({'runId':1} if body is None else body)
        conn.request(method,path,body=raw if method=='POST' else None,headers={'X-EAP-Optimization-Claim':claim})
        response=conn.getresponse();result=(response.status,json.loads(response.read()));conn.close();return result
    def done(self):
        end=time.monotonic()+3
        while self.handler.state.active is not None and time.monotonic()<end:time.sleep(.01)
        self.assertIsNone(self.handler.state.active)
    def test_health_has_fixed_engine_and_no_startup_replay(self):
        status,body=self.request('/health',method='GET');self.assertEqual(status,200);self.assertEqual(body['engineVersion'],ENGINE_VERSION);self.assertEqual(self.searches,[]);self.assertEqual(self.contexts,[])
    def test_invalid_claim_or_extra_payload_cannot_start_search(self):
        for body,claim in [({'runId':1},'invalid'),({'runId':1,'holdout':[]},'a'*64),({'runId':True},'a'*64),({'runId':-1},'a'*64)]:
            self.assertEqual(self.request(body=body,claim=claim)[0],403)
        self.assertEqual(self.searches,[]);self.assertEqual(self.contexts,[])
    def test_original_intent_runs_once_and_other_run_waits(self):
        self.assertEqual(self.request()[0],202);self.assertTrue(self.started.wait(2))
        self.assertEqual(self.request()[1]['reasonCode'],'RUN_INTENT_RECORDED')
        self.assertEqual(self.request(body={'runId':2})[1]['reasonCode'],'WORKER_BUSY')
        self.release.set();self.done();self.assertEqual(self.request()[1]['reasonCode'],'RUN_INTENT_RECORDED')
        self.assertEqual(self.searches,[1]);self.assertEqual(self.contexts,[1]);self.assertEqual(len(self.receipts),1);self.assertEqual(self.receipts[0][0],'/search-result')
        self.assertEqual(self.receipts[0][1]['status'],'COMPLETED');self.assertNotIn('a'*64,repr(self.handler.state.completed))
    def test_unknown_search_stops_and_seal_response_loss_never_repeats_write(self):
        self.failed_search=True;self.lost_seal=True;self.assertEqual(self.request()[0],202);self.release.set();self.done()
        self.assertEqual(self.searches,[1]);self.assertEqual(len(self.receipts),1);self.assertEqual(self.receipts[0][1]['status'],'UNKNOWN');self.assertEqual(self.request()[0],409)


if __name__=='__main__':unittest.main()
