"""Read-only EAP evidence review. Never submit or retry a model task."""
import hashlib
import json
from pathlib import Path
import sys

from prepare import Sample, require


def compact(value, sort=False):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=sort)


def sha(value):
    return hashlib.sha256(value.encode()).hexdigest()


def review(sample):
    s, c = sample.state, sample.config
    require(s['phase'] == 'EAP_RUN_OBSERVED', 'Observe the original EAP run first')
    team_id = s['writes']['team']['result']['id']
    plan_id = s['writes']['plan']['result']['id']
    members = sample.api('/orchestration/teams/' + str(team_id) + '/members')
    plan_context = {'planId': plan_id, 'teamId': team_id, 'goal': c['goal'],
                    'contextSummary': c['contextSummary'], 'coordinationMode': c['coordinationMode'],
                    'workspacePolicy': c['workspacePolicy'], 'members': members}
    steps = [x for x in s['execution']['execution']['steps'] if x['agentId'] is not None]
    checks = []
    for index, (role, step) in enumerate(zip(c['agents'], steps)):
        context = {'eapPlan': plan_context}
        output = s['outputs'][step['nodeKey']]
        if index:
            prev = steps[index - 1]
            context['eapPreviousResults'] = {prev['nodeKey']: {
                'taskKey': prev['taskKey'], 'agentId': prev['agentId'],
                'output': s['outputs'][prev['nodeKey']], 'outputSha256': prev['outputSha256']}}
        # Reconstruct Go encoding/json's sorted map output embedded in the user
        # message. Gateway hashes the decoded request reserialized by Jackson.
        context_json = compact(context, sort=True).replace('&', '\\u0026').replace('<', '\\u003c').replace('>', '\\u003e')
        request = {'max_tokens': 1024, 'messages': [
            {'role': 'system', 'content': role['systemPrompt']},
            {'role': 'user', 'content': role['title'] + '\n上下文：' + context_json}], 'model': c['model'], 'thinking': {'type': 'disabled'}}
        original = compact(request)
        observation = s['observations'][role['key']]
        observed = next(x for x in observation['content'] if x['direction'] == 'INPUT')
        traces = {x['traceId'] for x in observation['modelCalls'] if x['observationSource'] == 'GATEWAY'}
        span_match = any(x['traceId'] in traces and x['spanName'] == 'agent.task'
                         and json.loads(x['attributesJson']).get('eap.task.key') == step['taskKey'] for x in observation['spans'])
        try:
            parsed = json.loads(output) if index < 2 else None
            valid_json = isinstance(parsed, dict) if index < 2 else None
        except json.JSONDecodeError:
            valid_json = False
        checks.append({'role': role['key'], 'agentId': step['agentId'], 'taskKey': step['taskKey'],
                       'traceIds': sorted(traces), 'outputSha256Matches': sha(compact(output)) == step['outputSha256'],
                       'requestHashMatches': sha(original) == observed['contentHash'],
                       'reconstructedBytes': len(original.encode()), 'observedBytes': observed['contentBytes'],
                       'taskSpanAndGatewayLinked': span_match, 'structuredOutputComplete': valid_json,
                       'gatewayInputHash': observed['contentHash'], 'captureMode': observed['captureMode']})
    engineering = all(s['verification']['checks'].values()) and len(checks) == 3 and all(
        x['outputSha256Matches'] and x['requestHashMatches'] and x['taskSpanAndGatewayLinked'] for x in checks)
    final = s['outputs'][steps[-1]['nodeKey']]
    quality = {'analystJsonComplete': checks[0]['structuredOutputComplete'],
               'reviewerJsonComplete': checks[1]['structuredOutputComplete'],
               'reportAllSectionsPresent': all(x in final for x in ['## 影响', '## 已知证据', '## 待验证原因', '## 人工下一步', '## 信息缺口']),
               'reportMarkedSynthetic': '虚构演示' in final and '未执行修复' in final,
               'actualMaxAndThinkingFrozen': all(p['profile']['maxTokens'] == 1024 and p['profile'].get('thinkingMode') == 'DISABLED' for p in s['profiles'].values()),
               'providerFinishReasonAvailable': all(any(x['spanName'] == 'agent.task' and json.loads(x['attributesJson']).get('eap.model.finish_reason') == 'stop' and json.loads(x['attributesJson']).get('eap.model.request.thinking_mode') == 'DISABLED' for x in o['spans']) for o in s['observations'].values())}
    s['verification'].update(engineeringVerified=engineering, handoffEvidence=checks,
                             qualityChecks=quality, qualityReviewPending=False,
                             liveRunVerified=engineering and all(quality.values()),
                             result='AUTOMATED_CHECKS_PASS_HUMAN_REVIEW_PENDING' if engineering and all(quality.values()) else 'CONTENT_OR_EVIDENCE_INCOMPLETE')
    s['verification']['metadataObservation'] = 'Original spans retain finish_reason=stop and thinking_mode=DISABLED; max_tokens and output_status attribute names were masked by generic sanitization. Actual controls are independently verified by frozen profile and full model request hashes. Original records are retained.'
    s['verification']['humanReviewPending'] = True
    s['verification']['liveRunVerified'] = False
    sample.save()
    print(json.dumps(s['verification'], ensure_ascii=False), flush=True)


if __name__ == '__main__':
    try:
        review(Sample(Path(__file__).parent))
    except Exception as error:
        print('STOPPED: ' + (str(error) if type(error) is RuntimeError else type(error).__name__), file=sys.stderr)
        sys.exit(1)
