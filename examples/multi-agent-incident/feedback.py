"""Record the original incomplete outputs in EAP's feedback center. No model calls."""
import json
from pathlib import Path
import sys

from prepare import Sample, require


def main():
    sample = Sample(Path(__file__).parent)
    s = sample.state
    require(s['verification'].get('result') == 'ENGINEERING_PASS_CONTENT_INCOMPLETE', 'Review original evidence first')
    results = []
    for role in sample.config['agents']:
        key = role['key']
        agent_id = s['writes']['agent-' + key]['result']['id']
        calls = [x for x in s['observations'][key]['modelCalls'] if x['observationSource'] == 'GATEWAY']
        require(len(calls) == 1, 'Expected exactly one original call')
        call = calls[0]
        incomplete = 'JSON 未闭合' if key != 'reporter' else '简报在证据段中途结束，缺少后续章节'
        result = sample.write('quality-feedback-' + key, '/learning-feedbacks', {
            'agentId': agent_id, 'feedbackType': 'QUALITY', 'rating': 1, 'traceId': call['traceId'],
            'summary': '样例 DEMO-INC-001，Team 4 / Plan 5 / Workflow 29：平台派发与交接成功，但' + incomplete
                       + '。原调用输出 1024 Token，其中 reasoning ' + str(call['reasoningTokens'])
                       + '。内容验收未通过；实际供应商 finish_reason 未保留，未补跑。',
            'improvementAction': '为普通 Runner 增加模型思考模式与输出上限的受控配置；检查 finish_reason、空正文及结构完整性，'
                                 '不把截断当作任务成功。保留原结果；修复后另定新计划与预算验收，不能重放原计划。',
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
