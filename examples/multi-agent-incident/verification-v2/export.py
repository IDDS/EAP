"""Export the existing sample evidence only; no EAP state mutation."""
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import sys
import tarfile
import urllib.request

from prepare import Sample, digest, require


def main():
    sample = Sample(Path(__file__).parent)
    s = sample.state
    require(s['verification'].get('feedbackRecordedInEap'), 'EAP feedback must be recorded first')
    archive = sample.root / 'evidence.tgz'
    require(not archive.exists(), 'Original export already exists')
    budget = sample.api('/budgets')
    before = {x['unit']: x for x in s['platformBefore']}
    after = {x['unit']: x for x in budget['accounts'] if x['scopeType'] == 'PLATFORM'}
    for unit, delta in [('MODEL_CALLS', s['verification']['modelCalls']), ('OUTPUT_TOKENS', s['verification']['outputTokens']), ('TOOL_CLAIMS', 0)]:
        require(after[unit]['usedAmount'] - before[unit]['usedAmount'] == delta, 'Unexpected platform usage increment')
        for field in ['limitAmount', 'marginAmount', 'revision', 'reservedAmount', 'violated']:
            require(after[unit][field] == before[unit][field], 'Platform account configuration changed')
    app = {}
    pods = json.loads(sample.auth.kubectl('-n', 'eap', 'get', 'pods', '-o', 'json'))['items']
    for name in ['backend', 'frontend']:
        dep = json.loads(sample.auth.kubectl('-n', 'eap', 'get', 'deployment', name, '-o', 'json'))
        app[name] = {'uid': dep['metadata']['uid'], 'image': dep['spec']['template']['spec']['containers'][0]['image'],
                     'readyReplicas': dep['status'].get('readyReplicas', 0),
                     'pods': [{'name': p['metadata']['name'], 'uid': p['metadata']['uid'],
                               'containers': [{'ready': c['ready'], 'restarts': c['restartCount']} for c in p['status'].get('containerStatuses', [])]}
                              for p in pods if p['metadata']['name'].startswith(name + '-')]}
    expected = sample.config['expectedApplicationImages']
    require(all(app[k]['image'] == expected[k] and app[k]['readyReplicas'] == 1 for k in expected), 'Application deployment drift')
    with urllib.request.urlopen(sample.auth.service('eap', 'backend') + '/actuator/health', timeout=20) as response:
        health = json.load(response)['status']
    require(health == 'UP', 'Health check not UP')
    for role in sample.config['agents']:
        key = role['key']
        agent_id = s['writes']['agent-' + key]['result']['id']
        instances = sample.api('/agents/' + str(agent_id) + '/substrate/instances')
        require(len(instances) == 1 and instances[0]['state'] == 'SUSPENDED', 'Sample instance not asleep')
        require(instances[0]['instanceKey'] == s['deployments'][key]['instanceKey'], 'Original sample instance changed')
    s['verification']['platformIncrement'] = {'modelCalls': s['verification']['modelCalls'], 'outputTokens': s['verification']['outputTokens'], 'toolClaims': 0}
    s['verification']['originalPlatformLimitsPreserved'] = True
    s['verification']['health'] = health
    s['verification']['applicationDeployments'] = app
    s['verification']['diskAvailableGiB'] = round(shutil.disk_usage('/root').free / 1024**3, 2)
    s['verification']['modelSpendClosed'] = True
    s['verification']['moneyCostCaveat'] = '网关金额字段为 0；未核对供应商账单，不能解释为免费。'
    s['verification']['scriptOnlyUsesEapForCreationDispatchObservation'] = True
    sample.save()
    evidence = {
        'receipt.json': (json.dumps(s['verification'], ensure_ascii=False, indent=2) + '\n').encode(),
        'outputs.json': (json.dumps(s['outputs'], ensure_ascii=False, indent=2) + '\n').encode(),
        'eap-evidence.json': (json.dumps(s, ensure_ascii=False, indent=2) + '\n').encode()
    }
    for name in ['sample.json', 'prepare.py', 'run.py', 'review.py', 'feedback.py', 'export.py']:
        evidence['source/' + name] = (sample.root / name).read_bytes()
    # Inspect sensitive Secret values only in RAM. Public identity keys are not secrets.
    sensitive = []
    for ns in ['eap', 'eap-auth']:
        secrets = json.loads(sample.auth.kubectl('-n', ns, 'get', 'secrets', '-o', 'json'))['items']
        for item in secrets:
            for key, value in item.get('data', {}).items():
                if 'public' not in key.lower() and re.search(r'password|secret|token|signing|private|api.?key|deepseek', key, re.I):
                    decoded = base64.b64decode(value)
                    if len(decoded) >= 8:
                        sensitive.append(decoded)
    for content in evidence.values():
        require(not any(x in content for x in sensitive), 'Sensitive data found; export suppressed')
        require(not re.search(rb'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}', content), 'JWT found; export suppressed')
    evidence['manifest.json'] = (json.dumps({name: hashlib.sha256(raw).hexdigest() for name, raw in evidence.items()}, indent=2) + '\n').encode()
    with tarfile.open(archive, 'w:gz') as tar:
        for name, raw in evidence.items():
            info = tarfile.TarInfo(name)
            info.size = len(raw)
            info.mode = 0o600
            tar.addfile(info, io.BytesIO(raw))
    print(json.dumps({'archive': str(archive), 'bytes': archive.stat().st_size,
                      'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'files': len(evidence),
                      'health': health, 'sensitiveValueScanPassed': True, 'modelCallsAdded': 0}), flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('STOPPED: ' + (str(error) if type(error) is RuntimeError else type(error).__name__), file=sys.stderr)
        sys.exit(1)
