"""PoC node only: prepare a visible sample without spending model budget.

Use the existing node-side PKCE helper; credentials stay in memory. Each write
has a persisted intent before dispatch. An unconfirmed intent stops subsequent
writes and is never replayed automatically. No execution or budget uplift API.
"""
import argparse
import base64
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import time
import urllib.parse
import urllib.request


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


class Sample:
    def __init__(self, root):
        self.root = root.resolve()
        require(str(self.root).startswith('/root/eap-sample-incident-'), 'Run only in the dedicated remote sample directory')
        self.lock = (self.root / 'operator.lock').open('a')
        fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.config = json.loads((self.root / 'sample.json').read_text())
        self.state_path = self.root / 'state.json'
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else {
            'sampleSha256': digest(self.config), 'writes': {}, 'phase': 'NEW'
        }
        require(self.state['sampleSha256'] == digest(self.config), 'Sample changed; original intent must be retained')
        spec = importlib.util.spec_from_file_location('poc_auth', '/root/eap-verify-jwt.py')
        self.auth = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.auth)
        self.base = self.auth.service('eap', 'frontend')
        self.kc = self.auth.service('eap-auth', 'keycloak')
        self.access_token = None
        self.login_time = 0

    def save(self):
        temp = self.state_path.with_suffix('.tmp')
        with temp.open('w') as stream:
            json.dump(self.state, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        temp.replace(self.state_path)

    def api(self, path, method='GET', payload=None):
        if self.access_token is None or time.monotonic() - self.login_time > 120:
            raw = json.loads(self.auth.kubectl('-n', 'eap-auth', 'get', 'secret', 'eap-test-users', '-o', 'json'))['data']
            self.access_token = self.auth.login(self.kc, 'admin', base64.b64decode(raw['admin']).decode())['access_token']
            self.login_time = time.monotonic()
        try:
            code, _, body = self.auth.http(
                urllib.request.build_opener(self.auth.NoRedirect()), self.base + '/api' + path,
                method, None if payload is None else json.dumps(payload, ensure_ascii=False).encode(),
                {'Authorization': 'Bearer ' + self.access_token, 'Content-Type': 'application/json'})
        except Exception:
            raise RuntimeError('Transport outcome unconfirmed: ' + method + ' ' + path) from None
        require(code == 200, 'HTTP ' + str(code) + ': ' + method + ' ' + path + '; response suppressed')
        return json.loads(body)['data']

    def write(self, name, path, payload, method='POST'):
        fingerprint = digest([method, path, payload])
        old = self.state['writes'].get(name)
        if old:
            require(old['fingerprint'] == fingerprint, 'Changed request: ' + name)
            require(old['status'] == 'CONFIRMED', 'Unconfirmed write; query original objects before any recovery: ' + name)
            return old['result']
        require(all(x['status'] == 'CONFIRMED' for x in self.state['writes'].values()), 'Earlier unconfirmed write prevents dispatch')
        self.state['writes'][name] = {'fingerprint': fingerprint, 'path': path, 'method': method, 'status': 'INTENT'}
        self.save()
        result = self.api(path, method, payload)
        self.state['writes'][name].update(status='CONFIRMED', result=result)
        self.save()
        print('CONFIRMED ' + name, flush=True)
        return result

    def preflight(self):
        caps = self.api('/platform/me')['capabilities']
        require(all(caps.get(k) for k in ['deployAgents', 'runWorkflows', 'manageBudgets']), 'Required /me capabilities unavailable')
        runtime = self.api('/agents/substrate/capabilities')
        require(all(runtime.get(k) for k in ['enabled', 'runnerAvailable', 'instanceIdentityAvailable']), 'Runtime unavailable')
        require(self.api('/workflows/execution-status')['enabled'], 'Workflow execution unavailable')
        budget = self.api('/budgets')
        require(budget['enabled'], 'Budget enforcement must be enabled')
        platform = [x for x in budget['accounts'] if x['scopeType'] == 'PLATFORM']
        require(len(platform) == 3, 'Expected three platform budget accounts')
        if 'platformBefore' not in self.state:
            self.state['platformBefore'] = platform
            prefix = self.config['code']
            require(not any(x['code'].startswith(prefix) for x in self.api('/agents')), 'Sample code already exists outside this journal')
            require(not any(x['code'] == prefix for x in self.api('/orchestration/teams')), 'Team code already exists outside this journal')
            self.save()
        require(digest(platform) == digest(self.state['platformBefore']), 'Platform budget changed; inspect before continuing')
        print('PREFLIGHT_OK', flush=True)

    def prepare(self):
        self.preflight()
        c = self.config
        require(len(c['agents']) == 3 and len(c['contextSummary']) <= 1024, 'Invalid sample definition')
        members = []
        for role in c['agents']:
            key = role['key']
            agent = self.write('agent-' + key, '/agents', {
                'code': c['code'] + '-' + key, 'name': role['name'], 'description': role['responsibility'],
                'ownerDepartment': 'EAP样例', 'ownerUser': 'sample-operator', 'category': '合成故障分析', 'riskLevel': 'LOW'})
            require(agent['lifecycleStatus'] == 'DRAFT', 'New sample must remain a draft')
            members.append((role, agent['id']))
            for unit in ['MODEL_CALLS', 'OUTPUT_TOKENS', 'TOOL_CLAIMS']:
                self.write('budget-' + key + '-' + unit, '/budgets/accounts', {
                    'scopeType': 'AGENT', 'scopeKey': str(agent['id']), 'unit': unit,
                    'limitAmount': 0, 'marginAmount': 0, 'expectedRevision': 0}, 'PUT')
        team = self.write('team', '/orchestration/teams', {k: c[k] for k in ['code', 'name', 'objective', 'coordinationMode', 'workspacePolicy']})
        for role, agent_id in members:
            self.write('member-' + role['key'], '/orchestration/teams/' + str(team['id']) + '/members', {
                'agentId': agent_id, 'role': role['role'], 'responsibility': role['responsibility']})
        plan = self.write('plan', '/orchestration/plans', {
            'teamId': team['id'], 'goal': c['goal'], 'contextSummary': c['contextSummary']})
        for order, (role, agent_id) in enumerate(members, 1):
            step = self.write('step-' + role['key'], '/orchestration/plans/' + str(plan['id']) + '/steps', {
                'stepOrder': order, 'title': role['title'], 'requiredCapability': role['capability'], 'executionBackend': 'RUNTIME'})
            self.write('assignment-' + role['key'], '/orchestration/steps/' + str(step['id']) + '/assignment', {
                'assignedAgentId': agent_id, 'status': 'ASSIGNED'})
        self.state['phase'] = 'PREPARED_NOT_EXECUTED'
        self.save()
        self.verify()

    def publish(self):
        self.preflight()
        self.verify()
        for role in self.config['agents']:
            key = role['key']
            agent_id = self.state['writes']['agent-' + key]['result']['id']
            route = '/agents/' + str(agent_id) + '/substrate/deployments'
            payload = dict(self.config['runtime'], systemPrompt=role['systemPrompt'], model=self.config['model'],
                           requestKey=self.config['code'] + '-' + key + '-v1')
            dep = self.write('deployment-' + key, route, payload)
            deadline = time.monotonic() + 360
            while True:
                matches = [x for x in self.api(route) if x['deploymentKey'] == dep['deploymentKey']]
                require(len(matches) == 1, 'Original deployment missing')
                current = matches[0]
                self.state.setdefault('deployments', {})[key] = current
                self.save()
                if current['status'] == 'READY':
                    break
                require(current['status'] not in ['FAILED', 'BLOCKED', 'WAITING_APPROVAL'], 'Deployment stopped: ' + current['status'])
                require(time.monotonic() < deadline, 'Original deployment still pending; no retry or new version')
                time.sleep(3)
            require('@sha256:' in current['imageRef'], 'Runtime must use an immutable image')
            instances = self.api('/agents/' + str(agent_id) + '/substrate/instances')
            require(len(instances) == 1 and instances[0]['instanceKey'] == current['instanceKey'], 'Unexpected instance inventory')
            require(instances[0]['state'] == 'SUSPENDED', 'Published sample must be asleep')
            self.state.setdefault('instances', {})[key] = instances[0]
            self.save()
            print('PUBLISHED_ASLEEP ' + key, flush=True)
        self.state['phase'] = 'PUBLISHED_NOT_EXECUTED'
        self.save()
        self.verify()

    def verify(self):
        team_id = self.state['writes']['team']['result']['id']
        plan_id = self.state['writes']['plan']['result']['id']
        detail = self.api('/orchestration/plans/' + str(plan_id))
        require(detail['plan']['status'] == 'READY' and detail['plan']['executionRunKey'] is None, 'Sample execution state changed')
        require(detail['plan']['contextSummary'] == self.config['contextSummary'], 'Incident input changed')
        ids = [self.state['writes']['agent-' + r['key']]['result']['id'] for r in self.config['agents']]
        require(len(set(ids)) == 3, 'Expected independent agents')
        steps = detail['steps']
        require([x['assignedAgentId'] for x in steps] == ids and [x['stepOrder'] for x in steps] == [1, 2, 3], 'Step order or assignment mismatch')
        require(all(x['status'] == 'ASSIGNED' and x['executionBackend'] == 'RUNTIME' for x in steps), 'Unexpected step status')
        members = self.api('/orchestration/teams/' + str(team_id) + '/members')
        require(sorted(x['agentId'] for x in members) == sorted(ids), 'Team membership mismatch')
        budget = self.api('/budgets')
        require(digest([x for x in budget['accounts'] if x['scopeType'] == 'PLATFORM']) == digest(self.state['platformBefore']), 'Platform budget changed')
        accounts = [x for x in budget['accounts'] if x['scopeType'] == 'AGENT' and x['scopeKey'] in list(map(str, ids))]
        require(len(accounts) == 9 and all(x['limitAmount'] == 0 and x['usedAmount'] == 0 and x['reservedAmount'] == 0 for x in accounts), 'Expected nine unused zero-budget accounts')
        require(all(self.api('/agents/' + str(i))['lifecycleStatus'] == 'DRAFT' for i in ids), 'Sample agents must stay DRAFT')
        require(self.api('/orchestration/plans/' + str(plan_id) + '/execution') is None, 'Unexpected original execution')
        self.state['verification'] = {'teamId': team_id, 'planId': plan_id, 'agentIds': ids,
                                      'stepIds': [x['id'] for x in steps], 'phase': self.state['phase'],
                                      'modelCalls': 0, 'toolClaims': 0, 'liveRunVerified': False,
                                      'drafts': True, 'zeroBudgets': True, 'platformBudgetsUnchanged': True}
        self.save()
        print(json.dumps(self.state['verification'], ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['preflight', 'prepare', 'publish', 'verify'])
    args = parser.parse_args()
    sample = Sample(Path(__file__).parent)
    getattr(sample, args.action)()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Avoid tracebacks or transport URLs that could contain authentication data.
        print('STOPPED: ' + (str(error) if type(error) is RuntimeError else type(error).__name__), file=sys.stderr)
        sys.exit(1)
