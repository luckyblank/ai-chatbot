"""Run the scenario acceptance cases against a deployed instance (Python standard library only).

Credentials are read from the ignored Docker env file and never printed. Test workflows
are created with unique IDs and deleted in finally; existing workflows are not edited.
"""
import argparse
import http.cookiejar
import json
from pathlib import Path
import urllib.request
import urllib.parse
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:15175')
    parser.add_argument('--env-file', default='.env.docker')
    parser.add_argument('--with-ai', action='store_true', help='Also make real knowledge/model provider requests')
    args = parser.parse_args()
    settings = {}
    for line in Path(args.env_file).read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            settings[key.strip()] = value.strip().strip('"').strip("'")
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    def request(path, body=None, method=None):
        headers = {'Content-Type': 'application/json'}
        for cookie in cookies:
            if cookie.name == 'AI_SERVICE_CSRF': headers['X-CSRF-Token'] = urllib.parse.unquote(cookie.value)
        req = urllib.request.Request(args.base_url.rstrip('/') + '/api/v1' + path,
            data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        with opener.open(req, timeout=90) as response:
            content = response.read().decode()
            if 'text/event-stream' in response.headers.get('Content-Type', ''):
                return [json.loads(line[5:].strip()) for line in content.splitlines() if line.startswith('data:')]
            return json.loads(content) if content else None
    request('/auth/login', {'username': settings.get('SERVICE_ADMIN_USERNAME', 'admin'), 'password': settings['SERVICE_ADMIN_PASSWORD']})
    templates = request('/workflows/templates')
    created = []
    passed = []
    skipped = []
    def check(condition, label):
        if not condition: raise AssertionError(label)
        passed.append(label)
        print('PASS', label)
    def create(template):
        payload = {key: template[key] for key in ['scenarioCode', 'description', 'nodes', 'edges']}
        payload.update(name='回归验证-' + template['name'] + '-' + uuid.uuid4().hex[:6], enabled=True)
        workflow = request('/workflows', payload)
        created.append(workflow['id'])
        return '/workflows/' + workflow['id']
    try:
        for template in templates:
            path = create(template)
            if template['scenarioCode'] == 'general':
                if args.with_ai:
                    ai = request('/system/ai-status')
                    if ai['enabled'] and ai['configured']:
                        run = request(path + '/runs', {'input': template['testCases'][0]['input']})
                        check(run['status'] == 'completed' and bool(run['output'].get('answer')), '通用问题处理 / 真实模型响应')
                    else: skipped.append('通用问题处理：AI 服务未配置')
                continue
            if template['scenarioCode'] == 'knowledge-research':
                invalid = request(path + '/runs', {'input': {'question': '退款条件'}})
                check(invalid['status'] == 'failed' and '未选择知识库' in invalid['steps'][-1]['error'], '知识检索 / 缺少知识库')
                if args.with_ai:
                    ai = request('/system/ai-status')
                    if ai['enabled'] and ai['configured']:
                        bases = request('/knowledge-bases')
                        ready = [base for base in bases if any(doc['status'] == 'READY' for doc in request('/knowledge-bases/' + base['id'] + '/documents'))]
                        if ready:
                            base = next((base for base in ready if any(term in base['name'] for term in ['售后', '电商'])), ready[0])
                            run = request(path + '/runs', {'knowledgeBaseId': base['id'], 'input': {'question': '退款申请有哪些条件？'}})
                            check(run['status'] == 'completed' and isinstance(run['output']['matches'], list), '知识检索 / 真实向量服务与文档')
                        else: skipped.append('知识检索：没有已索引文档')
                    else: skipped.append('知识检索：AI 服务未配置')
                continue
            for sample in template['testCases']:
                run = request(path + '/runs', {'input': sample['input']})
                check(run['status'] == sample['expectedStatus'] and any(edge.get('branch') == sample['expectedBranch'] for edge in run['traversedEdges']), template['name'] + ' / ' + sample['name'])
                if run['status'] == 'waiting':
                    request(path + '/runs/' + run['id'] + '/resume', {'approved': True})
                    done = request(path + '/runs/' + run['id'])
                    check(done['status'] == 'completed' and done['id'] == run['id'], template['name'] + ' / 同一运行审批通过')
                    rejected = request(path + '/runs', {'input': sample['input']})
                    request(path + '/runs/' + rejected['id'] + '/resume', {'approved': False})
                    stopped = request(path + '/runs/' + rejected['id'])
                    check(stopped['status'] == 'failed' and all(step['nodeId'] != 'done' for step in stopped['steps']), template['name'] + ' / 拒绝后停止')
            missing = request(path + '/runs', {'input': {'question': '缺少业务编号'}})
            check(missing['status'] == 'failed' and '缺少参数' in missing['steps'][-1]['error'], template['name'] + ' / 缺少必填输入')
    finally:
        for identifier in created: request('/workflows/' + identifier, method='DELETE')
    for reason in skipped: print('SKIP', reason)
    print(json.dumps({'passed': len(passed), 'skipped': skipped, 'cleanedTestWorkflows': len(created)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
