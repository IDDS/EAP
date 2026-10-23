package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"testing"
)

func TestMCPFreshProcessCannotReusePriorInstallOrRotatedCredential(t *testing.T) {
	for _, replacement := range []bool{false, true} {
		name := "same-uid-process-restart"
		if replacement {
			name = "replacement-uid"
		}
		t.Run(name, func(t *testing.T) {
			original, signing, uid := identityFixture(t)
			mcpInstall(t, original, signing, uid, 1)
			oldToken := original.runtimeToken
			challenge := httptest.NewRecorder()
			original.identityChallenge(challenge, signedRequest(signing, challengeBody("5f65331b-ee14-4ce0-a128-af7a10a0f8a1")))
			var response map[string]string
			if challenge.Code != 200 || json.Unmarshal(challenge.Body.Bytes(), &response) != nil {
				t.Fatal("original challenge unavailable")
			}
			oldInstall := installBody(t, original, response, uid, oldToken)
			// Simulate a fresh process with only the trusted image/projection, not
			// the previous process's RSA key, nonce or installed credential.
			fresh := &runner{agentID: original.agentID,
				identity: &runtimeIdentity{trust: original.identity.trust, directory: original.identity.directory}}
			if replacement {
				uid = "508f73ba-82e7-4324-a143-a0c3fce02ed0"
				if os.WriteFile(fresh.identity.directory+"/uid", []byte(uid), 0600) != nil {
					t.Fatal("projection update")
				}
			}
			request := httptest.NewRequest("GET", "/eap/mcp/profile", nil)
			request.Header.Set("Authorization", "Bearer "+oldToken)
			rec := httptest.NewRecorder()
			fresh.mcpProfile(rec, request)
			if rec.Code != 401 {
				t.Fatal("fresh process accepted a credential before delivery")
			}
			rec = httptest.NewRecorder()
			fresh.identityInstall(rec, signedRequest(signing, oldInstall))
			if rec.Code != 409 || fresh.runtimeToken != "" {
				t.Fatal("old encrypted install accepted without fresh challenge")
			}
			mcpInstall(t, fresh, signing, uid, 2)
			rec = httptest.NewRecorder()
			fresh.identityInstall(rec, signedRequest(signing, oldInstall))
			if rec.Code != 409 || fresh.authorized(request) {
				t.Fatal("prior install or rotated credential survived replacement")
			}
			request.Header.Set("Authorization", "Bearer "+fresh.runtimeToken)
			if !fresh.authorized(request) {
				t.Fatal("new installed identity unavailable")
			}
			lease, valid := fresh.mcpLeaseSnapshot()
			if !valid || lease.ActorUID != uid || lease.Sequence != 2 || lease.Token == oldToken {
				t.Fatal("MCP lease did not use the new installed identity")
			}
		})
	}
}
