package main
import("errors";"net/http";"bytes";"encoding/json";"net/http/httptest";"os";"path/filepath";"testing";"time")
func fixture(t *testing.T)*axRunner{return &axRunner{dir:t.TempDir(),key:"ax-test",engine:&runner{taskToken:"test-only"}}}
func post(a *axRunner,body string)*httptest.ResponseRecorder{q:=httptest.NewRequest("POST","/eap/task",bytes.NewBufferString(body));q.Header.Set("Authorization","Bearer test-only");w:=httptest.NewRecorder();a.start(w,q);return w}
func get(a *axRunner)*httptest.ResponseRecorder{q:=httptest.NewRequest("GET","/eap/result",nil);q.Header.Set("Authorization","Bearer test-only");w:=httptest.NewRecorder();a.result(w,q);return w}
func TestDurableReplayAndConflict(t *testing.T){a:=fixture(t);body:=`{"taskKey":"ax-test","objective":"synthetic","mode":"ECHO"}`;if post(a,body).Code!=202{t.Fatal("dispatch")};for i:=0;i<100&&get(a).Code!=200;i++{time.Sleep(time.Millisecond*10)};raw:=get(a).Body.String();var result axReceipt;if json.Unmarshal([]byte(raw),&result)!=nil||result.Status!="SUCCEEDED"||result.Output!="synthetic"{t.Fatal(raw)};b:=fixture(t);b.dir=a.dir;if post(b,body).Code!=202||get(b).Body.String()!=raw{t.Fatal("restart replay changed receipt")};if post(b,`{"taskKey":"ax-test","objective":"changed","mode":"ECHO"}`).Code!=409{t.Fatal("missing conflict")}}
func TestRestartWithIntentNeverReexecutes(t *testing.T){a:=fixture(t);if err:=os.WriteFile(filepath.Join(a.dir,"started"),[]byte("intent"),0600);err!=nil{t.Fatal(err)};var result axReceipt;if json.Unmarshal(get(a).Body.Bytes(),&result)!=nil||result.Status!="EXECUTION_UNKNOWN"{t.Fatal("must remain unknown")}}
func TestRejectsIdentityAndMissingAuth(t *testing.T){a:=fixture(t);if post(a,`{"taskKey":"wrong","objective":"x","mode":"ECHO"}`).Code!=400{t.Fatal("wrong key")};q:=httptest.NewRequest("POST","/eap/task",nil);w:=httptest.NewRecorder();a.start(w,q);if w.Code!=401{t.Fatal("missing auth")};if _,err:=os.Stat(filepath.Join(a.dir,"started"));!os.IsNotExist(err){t.Fatal("unauthorized write")}}
func TestFailureHasExplicitReceipt(t *testing.T){a:=fixture(t);post(a,`{"taskKey":"ax-test","objective":"failure","mode":"FAIL"}`);for i:=0;i<100&&get(a).Code!=200;i++{time.Sleep(time.Millisecond*10)};var result axReceipt;_ = json.Unmarshal(get(a).Body.Bytes(),&result);if result.Status!="FAILED"||result.ExitCode!=1{t.Fatal("no failure receipt")}}

func TestModel429NeedsAdmissionProofToBeKnownFailure(t *testing.T) {
    for _, proof := range []bool{true,false} {
        gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,q *http.Request){if proof{w.Header().Set("X-EAP-Admission-Outcome","REJECTED")};w.WriteHeader(429);writeJSON(w,map[string]any{"error":map[string]any{"code":"budget_exhausted"}})}))
        r:=&runner{gateway:gateway.URL,client:gateway.Client()}
        _,err:=r.ask("public sample","trace")
        if err==nil||errors.Is(err,errModelOutcomeUnknown)==proof{t.Error("upstream status alone was treated as proof of no dispatch")};gateway.Close()
    }
}
