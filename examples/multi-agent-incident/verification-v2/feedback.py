"""Record the reviewed complete outputs in EAP's feedback center. No model calls."""
import json
from pathlib import Path
import sys

from prepare import Sample, require


def main():
    sample = Sample(Path(__file__).parent)
    s = sample.state
    require(s['verification'].get('liveRunVerified') and not s['verification'].get('humanReviewPending'), 'Review actual outputs first')
    results = []
    for role in sample.config['agents']:
        key = role['key']
        agent_id = s['writes']['agent-' + key]['result']['id']
        calls = [x for x in s['observations'][key]['modelCalls'] if x['observationSource'] == 'GATEWAY']
        require(len(calls) == 1, 'Expected exactly one original call')
        call = calls[0]
        result = sample.write('quality-feedback-' + key, '/learning-feedbacks', {
            'agentId': agent_id, 'feedbackType': 'QUALITY', 'rating': 3 if key == 'reviewer' else 4, 'traceId': call['traceId'],
            'summary': '虚构事件 DEMO-INC-001 的独立完整输出验收，Plan ' + str(s['verification']['planId'])
                       + '。本步骤输出完整，原结果交接及 finish_reason=stop 已核对；未执行修复。'
                       + '这只证明本次样例通过，不代表稳定质量或生产有效性。'
                       + ('质量观察：上游已明确相关性不等于因果，复核员却称已纠正过度推断；单时刻 CPU 数据只能说未见饱和，不应表述为排除饱和。最终报告未沿用该排除结论。' if key == 'reviewer' else ''),
            'improvementAction': '保留本次输出、Trace 与预算回执；后续真实场景需要各自证据和验收，不自动触发优化或再次调用模型。',
            'requestKey': sample.config['code'] + '-quality-' + key})
        rows = sample.api('/learning-feedbacks?agentId=' + str(agent_id))
        require(any(x['id'] == result['id'] and x['traceId'] == call['traceId'] for x in rows), 'Feedback not visible in EAP')
        results.append({'id': result['id'], 'agentId': agent_id, 'traceId': call['traceId'], 'status': result['status']})
    s['verification']['feedbacks'] = results
    s['verification']['feedbackRecordedInEap'] = True
    sample.save()
    print(json.dumps({'feedbacks': results, 'modelCallsAdded': 0}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('STOPPED: ' + (str(error) if type(error) is RuntimeError else type(error).__name__), file=sys.stderr)
        sys.exit(1)
