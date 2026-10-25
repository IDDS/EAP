"""Node-only client: EAP creates the workflow, dispatches tasks and records evidence.

One bounded run of the existing sample. This client never calls a provider or
Actor task endpoint, and never dispatches an individual plan step.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time
import urllib.parse

from prepare import Sample, require


def connection(sample, key):
    return sample.api('/registry/runtime-connection?' + urllib.parse.urlencode({'instanceKey': key}))


def future_seconds(text):
    return (datetime.fromisoformat(text.replace('Z', '+00:00')) - datetime.now(timezone.utc)).total_seconds()


def execute(sample):
    s, c = sample.state, sample.config
    require(s['phase'] == 'PUBLISHED_NOT_EXECUTED', 'Use observe for the original run; execution cannot be restarted')
    require(c['runtime'].get('maxTokens') == 1024 and c['runtime'].get('thinkingMode') == 'DISABLED', 'Unexpected v2 model controls')
    sample.preflight()
    sample.verify()
    require(c['proposedRunBudget'] == {'modelCalls': 3, 'outputTokensPerAgent': 1024,
                                     'outputTokensTotal': 3072, 'toolClaims': 0}, 'Unexpected budget')
    s['phase'] = 'PREPARING_ONE_EAP_RUN'
    s['runScope'] = c['proposedRunBudget']
    s['executionAuthority'] = '用户要求继续完成由 EAP 创建、调度、观测的样例；本次另建独立计划及三名 Agent，各1调用/1024输出/工具0，不重放原计划5，不改变其额度，不执行旧28调用优化验收。'
    sample.save()
    for role in c['agents']:
        key = role['key']
        agent_id = s['writes']['agent-' + key]['result']['id']
        dep = s['deployments'][key]
        sample.write('activate-' + key, '/agents/' + str(agent_id) + '/status', {'lifecycleStatus': 'ACTIVE'})
        status = connection(sample, dep['instanceKey'])
        # Only this fresh sample's original creation request may renew its lease.
        if status['connection'] != 'READY' or future_seconds(status['expiresAt']) < 540:
            original = dep['deploymentKey'].removeprefix('dep-')
            renewed = sample.write('renew-owned-' + key, '/agents/' + str(agent_id) + '/substrate/instances', {'requestKey': original})
            require(renewed['instanceKey'] == dep['instanceKey'], 'Original instance changed')
        try:
            profile = sample.api('/agent-experiments/profile', 'POST', {'agentId': agent_id, 'instanceKey': dep['instanceKey']})
            s.setdefault('profiles', {})[key] = profile
            sample.save()
            require(profile['imageRef'] == dep['imageRef'] and profile['deploymentVersion'] == dep['version'], 'Deployment changed')
            p = profile['profile']
            require(p['systemPrompt'] == role['systemPrompt'] and p['model'] == c['model'] and p['maxTokens'] == 1024,
                    'Prompt, model or maximum output differs')
            require(p.get('thinkingMode') == 'DISABLED' and dep['config'].get('thinkingMode') == 'DISABLED' and dep['config'].get('maxTokens') == 1024, 'Explicit model controls not frozen')
        finally:
            # Profile lookup wakes the Actor through EAP. Return it to sleep to
            # release capacity before examining the next dedicated instance.
            result = sample.api('/control/commands', 'POST', {
                'agentId': agent_id, 'runtimeInstanceKey': dep['instanceKey'], 'commandType': 'PAUSE'})
            s.setdefault('profilePause', {})[key] = result
            sample.save()
            require(result['status'] == 'ACKED', 'EAP profile cleanup not acknowledged')
        status = connection(sample, dep['instanceKey'])
        require(status['connection'] == 'READY' and future_seconds(status['expiresAt']) >= 360, 'Insufficient identity lease')
        s.setdefault('connectionsBefore', {})[key] = status
        sample.save()
        print('EAP_PROFILE_READY ' + key, flush=True)
    require(len({p['actorUid'] for p in s['profiles'].values()}) == 3, 'Expected three independent Actor UIDs')
    before = sample.api('/budgets')
    for account in before['accounts']:
        if account['scopeType'] != 'PLATFORM':
            continue
        bound = {'MODEL_CALLS': 3, 'OUTPUT_TOKENS': 3072, 'TOOL_CLAIMS': 0}[account['unit']]
        total = account['usedAmount'] + account['reservedAmount'] + account['marginAmount'] + bound
        require(not account['violated'] and (account['limitAmount'] is None or total <= account['limitAmount']), 'Insufficient platform budget')
    for role in c['agents']:
        key = role['key']
        agent_id = s['writes']['agent-' + key]['result']['id']
        for unit, cap in [('MODEL_CALLS', 1), ('OUTPUT_TOKENS', 1024)]:
            a = next(x for x in before['accounts'] if x['scopeType'] == 'AGENT' and x['scopeKey'] == str(agent_id) and x['unit'] == unit)
            require(a['limitAmount'] == 0 and a['revision'] == 1 and a['usedAmount'] == a['reservedAmount'] == 0, 'Original sample budget changed')
            sample.write('run-budget-' + key + '-' + unit, '/budgets/accounts', {
                'scopeType': 'AGENT', 'scopeKey': str(agent_id), 'unit': unit,
                'limitAmount': cap, 'marginAmount': 0, 'expectedRevision': a['revision']}, 'PUT')
    for dep in s['deployments'].values():
        status = connection(sample, dep['instanceKey'])
        require(status['connection'] == 'READY' and future_seconds(status['expiresAt']) >= 300, 'Lease no longer sufficient; no execution submitted')
    plan_id = s['writes']['plan']['result']['id']
    s['phase'] = 'EAP_EXECUTION_INTENT'
    sample.save()
    result = sample.write('execute-plan', '/orchestration/plans/' + str(plan_id) + '/executions', c['execution'])
    s['phase'] = 'EAP_EXECUTION_SUBMITTED'
    s['execution'] = result
    sample.save()
    print('EAP_EXECUTION_SUBMITTED ' + result['execution']['runKey'], flush=True)
    observe(sample)


def observe(sample):
    s = sample.state
    plan_id = s['writes']['plan']['result']['id']
    deadline = time.monotonic() + 420
    while True:
        result = sample.api('/orchestration/plans/' + str(plan_id) + '/execution')
        require(result is not None, 'No execution found; never resubmit automatically')
        s['execution'] = result
        sample.save()
        run = result['execution']
        if run['status'] not in ['QUEUED', 'RUNNING']:
            break
        require(time.monotonic() < deadline, 'Original execution still pending; use observe only')
        print('EAP_STATUS ' + run['status'] + ' steps=' + str(len(run['steps'])), flush=True)
        time.sleep(5)
    route = '/workflows/' + str(result['workflowId'])
    s['workflow'] = sample.api(route)
    s['outputs'] = sample.api(route + '/executions/' + run['runKey'] + '/outputs')
    s['planAfter'] = sample.api('/orchestration/plans/' + str(plan_id))
    s['budgetAfter'] = sample.api('/budgets')
    ids = [s['writes']['agent-' + r['key']]['result']['id'] for r in sample.config['agents']]
    s['observations'] = {}
    for role, agent_id in zip(sample.config['agents'], ids):
        key = role['key']
        query = '?agentId=' + str(agent_id) + '&limit=100'
        s['observations'][key] = {
            'modelCalls': sample.api('/telemetry/model-invocations' + query),
            'spans': sample.api('/observability/otlp/spans' + query),
            'content': sample.api('/observability/content' + query),
            'tools': sample.api('/observability/tool-invocations' + query),
            'instances': sample.api('/agents/' + str(agent_id) + '/substrate/instances'),
            'connection': connection(sample, s['deployments'][key]['instanceKey'])}
        sample.save()
    calls = [x for o in s['observations'].values() for x in o['modelCalls'] if x['observationSource'] == 'GATEWAY']
    owned_accounts = [x for x in s['budgetAfter']['accounts'] if x['scopeType'] == 'AGENT' and x['scopeKey'] in list(map(str, ids))]
    checks = {
        'planCompleted': s['planAfter']['plan']['status'] == 'COMPLETED' and run['status'] == 'SUCCEEDED',
        'threeDistinctAgents': len(set(ids)) == 3,
        'threeRuntimeStepsSucceeded': len([x for x in run['steps'] if x['agentId'] in ids and x['status'] == 'SUCCEEDED']) == 3,
        'threeNonemptyOutputs': len(s['outputs']) == 3 and all(isinstance(v, str) and bool(v.strip()) for v in s['outputs'].values()),
        'threeGatewayCalls': len(calls) == 3 and all(x['status'] == 'SUCCEEDED' for x in calls),
        'outputWithinBudget': all(x['outputTokens'] is not None for x in calls) and sum(x['outputTokens'] for x in calls) <= 3072,
        'noToolInvocations': all(not o['tools'] for o in s['observations'].values()),
        'allInstancesAsleep': all(len(o['instances']) == 1 and o['instances'][0]['state'] == 'SUSPENDED' for o in s['observations'].values()),
        'noOutstandingReservations': all(x['reservedAmount'] == 0 for x in owned_accounts),
        'threeAgentSpans': all(any(x['spanName'] == 'agent.task' for x in o['spans']) for o in s['observations'].values())}
    s['verification'] = {'teamId': s['writes']['team']['result']['id'], 'planId': plan_id, 'agentIds': ids,
                         'workflowId': result['workflowId'], 'runKey': run['runKey'], 'checks': checks,
                         'modelCalls': len(calls), 'inputTokens': sum(x['inputTokens'] or 0 for x in calls),
                         'outputTokens': sum(x['outputTokens'] or 0 for x in calls),
                         'reasoningTokens': sum(x['reasoningTokens'] or 0 for x in calls),
                         'toolClaims': sum(len(o['tools']) for o in s['observations'].values()),
                         'liveRunVerified': False, 'qualityReviewPending': True}
    s['phase'] = 'EAP_RUN_OBSERVED'
    sample.save()
    print(json.dumps(s['verification'], ensure_ascii=False), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['execute-once', 'observe'])
    args = parser.parse_args()
    try:
        sample = Sample(Path(__file__).parent)
        (execute if args.action == 'execute-once' else observe)(sample)
    except Exception as error:
        print('STOPPED: ' + (str(error) if type(error) is RuntimeError else type(error).__name__), file=sys.stderr)
        sys.exit(1)
