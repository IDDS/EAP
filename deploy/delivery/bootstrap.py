"""Remote-only, create-only installation of the JWT control plane on an empty DB.

External MySQL/IdP and namespace/Secret provisioning remain operator prerequisites.
No Secret values are read, no existing resource is adopted, and writes are never retried.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urlsplit
import uuid

import eapctl
from image_build import read_json, files

Error = eapctl.DeliveryError
HERE = Path(__file__).resolve().parent
LABEL = 'eap.io/bootstrap-id'
PROFILE = 'CONTROL_PLANE_BOOTSTRAP'
PROJECT_PROFILE = 'PROJECT_CONTROL_PLANE_BOOTSTRAP'
PROJECT_LABEL = 'eap.io/project'
DISABLED = [
    'EAP_SUBSTRATE_ENABLED', 'EAP_SUBSTRATE_BUILD_ENABLED', 'EAP_GATEWAY_ENABLED',
    'EAP_AX_ENABLED', 'EAP_CONTROL_DISPATCH_ENABLED', 'EAP_WORKFLOW_EXECUTION_ENABLED',
    'EAP_OPTIMIZATION_ENABLED', 'EAP_DISCOVERY_ENABLED', 'EAP_DISCOVERY_PROTOCOL_ENABLED',
    'EAP_MANAGEMENT_CONNECTOR_SCHEDULER_ENABLED', 'EAP_AGENTOPS_SIGNAL_SYNC_ENABLED',
    'EAP_EVALUATION_ASYNC_WORKER_ENABLED', 'EAP_OTLP_GRPC_ENABLED',
]
DB_CHECK = '''set -eu
if ! count=$(mysql --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --database="$DB_NAME" --ssl-mode="$DB_SSL_MODE" --connect-timeout=10 --get-server-public-key -Nse 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()' 2>/dev/null); then
  echo DATABASE_UNAVAILABLE
  exit 20
fi
if [ "$count" != "0" ]; then
  echo DATABASE_NOT_EMPTY
  exit 21
fi
echo DATABASE_EMPTY
'''


def exact(obj, keys):
    if not isinstance(obj, dict) or set(obj) != set(keys):
        raise Error('配置字段缺失或包含不支持字段；不得填写凭据值')


def dns(value):
    if not isinstance(value, str) or len(value) > 253 or any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', part) for part in value.split('.')):
        raise Error('DNS 名称无效')
    return value


def url(value, internal=False, origin=False):
    if not isinstance(value, str) or len(value) > 1024 or any(c.isspace() for c in value):
        raise Error('URL 无效')
    p = urlsplit(value)
    if p.username or p.password or p.query or p.fragment or not p.hostname or p.port == 0:
        raise Error('URL 不允许凭据、查询参数或片段')
    loopback = p.hostname in {'localhost', '127.0.0.1', '::1'}
    cluster = p.hostname.endswith(('.svc', '.svc.cluster.local'))
    if p.scheme != 'https' and not (p.scheme == 'http' and (loopback or internal and cluster)):
        raise Error('公开地址需要 HTTPS；仅回环和明确的集群后通道允许 HTTP')
    if origin and p.path:
        raise Error('控制台 origin 不能含路径或尾斜杠')
    return value


def configuration(data):
    project = isinstance(data, dict) and data.get('profile') == PROJECT_PROFILE
    exact(data, ['contract', 'profile', 'installId', 'namespace', 'database', 'auth', 'databaseCheckImage'] + (['projectKey'] if project else []))
    if data['contract'] != ('EAP_INSTALL_V2' if project else 'EAP_INSTALL_V1') or data['profile'] not in {PROFILE, PROJECT_PROFILE}:
        raise Error('当前只支持 JWT 控制台初始化阶段')
    if str(uuid.UUID(data['installId'])) != data['installId']:
        raise Error('安装标识必须为规范 UUID')
    namespace = dns(data['namespace'])
    if '.' in namespace or len(namespace) > 63 or namespace == 'default' or namespace.startswith('kube-'):
        raise Error('必须使用独立命名空间')
    eapctl.image(data['databaseCheckImage'])
    db = data['database']
    exact(db, ['host', 'port', 'name', 'secretName', 'usernameKey', 'passwordKey', 'sslMode', 'dedicated'])
    dns(db['host']); dns(db['secretName'])
    if type(db['port']) is not int or not 1 <= db['port'] <= 65535 or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', db['name']):
        raise Error('数据库名称或端口无效')
    if db['dedicated'] is not True or db['sslMode'] not in {'REQUIRED', 'DISABLED'}:
        raise Error('必须确认独立数据库并指定传输模式')
    for key in ['usernameKey', 'passwordKey']:
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,253}', db[key]):
            raise Error('Secret 键名无效')
    if db['usernameKey'] == db['passwordKey']:
        raise Error('数据库用户名和密码必须引用不同的键')
    auth = data['auth']
    exact(auth, ['issuer', 'jwkSetUri', 'audience', 'clientId', 'consoleOrigin'])
    url(auth['issuer']); url(auth['jwkSetUri'], internal=True); url(auth['consoleOrigin'], origin=True)
    for key in ['audience', 'clientId']:
        if not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', auth[key]):
            raise Error('OIDC clientId 或 audience 无效')
    if project:
        key = data['projectKey']
        if not isinstance(key, str) or not re.fullmatch(r'[a-z][a-z0-9-]{0,47}', key):
            raise Error('项目标识无效')
        if (namespace != 'eap-' + key or db['name'] != 'eap_' + key.replace('-', '_')
                or auth['audience'] != 'eap-' + key + '-api' or auth['clientId'] != 'eap-' + key + '-console'):
            raise Error('项目安装必须使用项目专属 namespace、数据库、audience 和 clientId')
    return copy.deepcopy(data)


def env(name, value):
    return {'name': name, 'value': str(value)}


def secret_env(name, db, key):
    return {'name': name, 'valueFrom': {'secretKeyRef': {'name': db['secretName'], 'key': db[key]}}}


def resources(config, release):
    c = configuration(config); ns = c['namespace']; identity = c['installId']; db = c['database']; auth = c['auth']
    def meta(name):
        return {'name': name, 'namespace': ns, 'labels': {LABEL: identity, **({PROJECT_LABEL: c['projectKey']} if 'projectKey' in c else {})}}
    def service(name, port):
        return {'apiVersion': 'v1', 'kind': 'Service', 'metadata': meta(name), 'spec': {
            'type': 'ClusterIP', 'selector': {'app': name, LABEL: identity}, 'ports': [{'name': 'http', 'port': port, 'targetPort': port}]}}
    backend_env = [env('EAP_DB_URL', 'jdbc:mysql://' + db['host'] + ':' + str(db['port']) + '/' + db['name'] + '?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&sslMode=' + db['sslMode'] + ('&allowPublicKeyRetrieval=true' if db['sslMode'] == 'DISABLED' else '')),
        secret_env('EAP_DB_USERNAME', db, 'usernameKey'), secret_env('EAP_DB_PASSWORD', db, 'passwordKey'),
        env('EAP_SECURITY_MODE', 'JWT'), env('EAP_SECURITY_ISSUER_URI', auth['issuer']),
        env('EAP_SECURITY_JWK_SET_URI', auth['jwkSetUri']), env('EAP_SECURITY_AUDIENCE', auth['audience']),
        env('EAP_SECURITY_ROLES_CLAIM', 'roles'), env('EAP_SECURITY_OIDC_CLIENT_ID', auth['clientId']),
        env('EAP_SECURITY_OIDC_SCOPES', 'openid profile'), env('EAP_SECURITY_ALLOWED_ORIGINS', auth['consoleOrigin']),
        env('EAP_BUDGET_ENABLED', 'true')] + [env(name, 'false') for name in DISABLED]
    if 'projectKey' in c:
        if release['schema'] < 93:
            raise Error('项目部署需要 Schema 93 或更新的匹配镜像')
        backend_env += [env('EAP_SECURITY_PROJECT_KEY', c['projectKey']), env('EAP_SECURITY_INITIALIZE_PROJECT_BINDING', 'true')]
        backend_env += [env('EAP_SUBSTRATE_DEPLOY_ATESPACE', 'eap-' + c['projectKey'] + '-agents'),
                        env('EAP_SUBSTRATE_INVENTORY_ATESPACES', 'eap-' + c['projectKey'] + '-ax'),
                        env('EAP_AX_ATESPACE', 'eap-' + c['projectKey'] + '-ax'),
                        env('EAP_SUBSTRATE_BUILD_NAMESPACE', 'eap-' + c['projectKey'] + '-builds')]
    apps = []
    for name, port in [('backend', 8080), ('frontend', 80)]:
        container = {'name': name, 'image': eapctl.image(release['applications'][name]), 'ports': [{'containerPort': port}],
            'resources': {'requests': {'cpu': '100m', 'memory': '256Mi' if name == 'backend' else '32Mi'},
                          'limits': {'cpu': '2' if name == 'backend' else '1', 'memory': '1536Mi' if name == 'backend' else '128Mi'}},
            'securityContext': {'allowPrivilegeEscalation': False},
            'readinessProbe': {'httpGet': {'path': '/actuator/health' if name == 'backend' else '/index.html', 'port': port, 'scheme': 'HTTP'},
                               'periodSeconds': 2, 'timeoutSeconds': 1, 'successThreshold': 2, 'failureThreshold': 2}}
        if name == 'backend':
            container['env'] = backend_env
            container['securityContext'].update({'runAsUser': 10001, 'runAsNonRoot': True, 'capabilities': {'drop': ['ALL']}})
            container['startupProbe'] = {'httpGet': {'path': '/actuator/health', 'port': port}, 'periodSeconds': 5, 'timeoutSeconds': 2, 'failureThreshold': 90}
        spec = {'replicas': 1, 'minReadySeconds': 5, 'selector': {'matchLabels': {'app': name, LABEL: identity}},
            'strategy': {'type': 'Recreate'} if name == 'backend' else {'type': 'RollingUpdate', 'rollingUpdate': {'maxSurge': 1, 'maxUnavailable': 0}},
            'template': {'metadata': {'labels': {'app': name, LABEL: identity}}, 'spec': {
                'serviceAccountName': 'eap-bootstrap', 'automountServiceAccountToken': False,
                'securityContext': {'seccompProfile': {'type': 'RuntimeDefault'}}, 'containers': [container]}}}
        apps.append({'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': meta(name), 'spec': spec})
    sa = {'apiVersion': 'v1', 'kind': 'ServiceAccount', 'metadata': meta('eap-bootstrap'), 'automountServiceAccountToken': False}
    job = {'apiVersion': 'batch/v1', 'kind': 'Job', 'metadata': meta('eap-db-check-' + identity), 'spec': {
        'backoffLimit': 0, 'activeDeadlineSeconds': 90, 'template': {'metadata': {'labels': {LABEL: identity}}, 'spec': {
            'automountServiceAccountToken': False, 'restartPolicy': 'Never', 'containers': [{'name': 'check', 'image': c['databaseCheckImage'],
                'command': ['sh', '-c', DB_CHECK], 'env': [env('DB_HOST', db['host']), env('DB_PORT', db['port']), env('DB_NAME', db['name']), env('DB_SSL_MODE', db['sslMode']),
                    secret_env('DB_USER', db, 'usernameKey'), secret_env('MYSQL_PWD', db, 'passwordKey')],
                'resources': {'requests': {'cpu': '50m', 'memory': '32Mi'}, 'limits': {'cpu': '1', 'memory': '256Mi'}},
                'securityContext': {'allowPrivilegeEscalation': False}}]}}}}
    return [sa, service('backend', 8080), service('frontend', 80)] + apps, job


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + '\n')


def render(config_path, release_path, output):
    c = configuration(read_json(config_path)); release = eapctl.manifest(release_path)
    objects, job = resources(c, release)
    root = Path(output); root.mkdir()  # refuse reuse, including symlinks
    write_json(root / 'install.json', {'configuration': c, 'release': release})
    write_json(root / 'resources.json', {'apiVersion': 'v1', 'kind': 'List', 'items': objects})
    write_json(root / 'database-check.json', job)
    write_json(root / 'bundle.json', {'contract': 'EAP_INSTALL_BUNDLE_V1', 'files': files(root)})
    return {'passed': True, 'profile': c['profile'], 'bundleSha256': hashlib.sha256((root / 'bundle.json').read_bytes()).hexdigest(),
            'installationComplete': False, 'notice': '仅生成安装包；尚未检查依赖或执行安装'}


def bundle(path, expected):
    root = Path(path)
    if not isinstance(expected, str) or not re.fullmatch(r'[0-9a-f]{64}', expected) or root.is_symlink():
        raise Error('安装包摘要无效')
    if hashlib.sha256((root / 'bundle.json').read_bytes()).hexdigest() != expected:
        raise Error('安装包外部摘要不匹配')
    receipt = read_json(root / 'bundle.json'); actual = files(root); actual.pop('bundle.json', None)
    if receipt.get('contract') != 'EAP_INSTALL_BUNDLE_V1' or receipt.get('files') != actual or set(actual) != {'install.json', 'resources.json', 'database-check.json'}:
        raise Error('安装包文件发生变化')
    data = read_json(root / 'install.json'); c = configuration(data['configuration']); release = data['release']
    # The release travels inside the externally hashed package.
    if release.get('contract') != 'EAP_DELIVERY_V1' or release.get('productionReady') is not False or type(release.get('schema')) is not int:
        raise Error('发布合同无效')
    objects, job = resources(c, release)
    if read_json(root / 'resources.json') != {'apiVersion': 'v1', 'kind': 'List', 'items': objects} or read_json(root / 'database-check.json') != job:
        raise Error('资源与安装合同不匹配')
    return c, release, objects, job


def kubectl(args, data=None):
    if sys.platform != 'linux':
        raise Error('只能在远程 Linux 运维节点执行')
    try:
        result = subprocess.run(['kubectl', '--request-timeout=15s'] + args,
            input=json.dumps(data).encode() if data is not None else None, capture_output=True, check=True, timeout=20)
        return result.stdout.decode()
    except (OSError, subprocess.SubprocessError):
        raise Error('集群请求失败；未输出可能含凭据的错误正文；写请求不得直接重试') from None


def namespace(c, uid):
    if not isinstance(uid, str) or not re.fullmatch(r'[a-f0-9-]{36}', uid):
        raise Error('命名空间 UID 无效')
    n = json.loads(kubectl(['get', 'namespace', c['namespace'], '-o', 'json']))
    if n['metadata']['uid'] != uid or n['metadata'].get('labels', {}).get(LABEL) != c['installId'] or n.get('status', {}).get('phase') != 'Active' or n['metadata'].get('deletionTimestamp'):
        raise Error('命名空间身份或安装标识不匹配')
    if 'projectKey' in c and n['metadata'].get('labels', {}).get(PROJECT_LABEL) != c['projectKey']:
        raise Error('命名空间未绑定当前项目')
    return n


def preflight(root, expected, uid):
    c, release, objects, job = bundle(root, expected); namespace(c, uid); ns = c['namespace']
    for resource in objects + [job]:
        present = kubectl(['-n', ns, 'get', resource['kind'], resource['metadata']['name'], '--ignore-not-found', '-o', 'json'])
        if present.strip():
            raise Error('安装对象已经存在；禁止采用旧对象或重放安装')
    # Project only keys; Secret data values never enter this process.
    keys = kubectl(['-n', ns, 'get', 'secret', c['database']['secretName'], '-o', r'go-template={{range $k, $v := .data}}{{$k}}{{"\n"}}{{end}}']).splitlines()
    if not {c['database']['usernameKey'], c['database']['passwordKey']} <= set(keys):
        raise Error('数据库 Secret 缺少所需键')
    kubectl(['-n', ns, 'create', '--dry-run=server', '-f', '-', '-o', 'name'], {'apiVersion': 'v1', 'kind': 'List', 'items': objects + [job]})
    return {'passed': True, 'namespaceUid': uid, 'profile': c['profile'], 'bundleSha256': expected,
            'resourceCount': len(objects), 'databaseEmptyVerified': False, 'installationComplete': False,
            'unverified': ['数据库连接和空库由安装前 Job 检查', 'IdP 与真实登录', 'HTTP 与 Schema', '运行调度/模型接入', '备份和生产运维']}


def install(root, expected, uid, receipt_path):
    c, release, objects, job = bundle(root, expected)
    receipt = Path(receipt_path)
    if receipt.exists() or receipt.is_symlink() or Path(root).resolve() in receipt.resolve().parents:
        raise Error('必须使用安装包之外的新回执路径；不得覆盖或重试已有回执')
    preflight(root, expected, uid)
    state = {'contract': 'EAP_INSTALL_RECEIPT_V1', 'namespace': c['namespace'], 'namespaceUid': uid,
             'installId': c['installId'], 'bundleSha256': expected, 'profile': c['profile'], 'resources': [], 'stage': 'PREFLIGHT_PASSED'}
    with receipt.open('x') as f:
        json.dump(state, f, indent=2)
    def save():
        temporary = receipt.with_name(receipt.name + '.tmp')
        with temporary.open('x') as f:
            json.dump(state, f, indent=2); f.write('\n')
        temporary.replace(receipt)
    def create(resource):
        namespace(c, uid)
        record = {'kind': resource['kind'], 'name': resource['metadata']['name'], 'status': 'INTENT_RECORDED'}
        state['resources'].append(record); save()  # persist before a possibly ambiguous write
        created = json.loads(kubectl(['-n', c['namespace'], 'create', '-f', '-', '-o', 'json'], resource))
        if created['metadata']['labels'].get(LABEL) != c['installId']:
            raise Error('创建响应的安装身份不匹配')
        record.update({'uid': created['metadata']['uid'], 'status': 'CREATED'}); save()
        return created
    created = create(job); deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        current = json.loads(kubectl(['-n', c['namespace'], 'get', 'job', job['metadata']['name'], '-o', 'json']))
        if current['metadata']['uid'] != created['metadata']['uid']:
            raise Error('数据库检查 Job 身份变化')
        if current.get('status', {}).get('failed', 0) or any(x['type'] == 'Failed' and x['status'] == 'True' for x in current.get('status', {}).get('conditions', [])):
            state['stage'] = 'DATABASE_CHECK_FAILED'; save(); raise Error('数据库连接失败或非空；应用尚未创建')
        if current.get('status', {}).get('succeeded') == 1:
            state['stage'] = 'DATABASE_EMPTY_VERIFIED'; save(); break
        time.sleep(2)
    else:
        state['stage'] = 'DATABASE_CHECK_UNKNOWN'; save(); raise Error('数据库检查超时；先检查原 Job，不重试安装')
    for resource in objects:
        create(resource)
    state['stage'] = 'APPLICATIONS_CREATED'; state['installationComplete'] = False
    state['notice'] = '资源已创建；继续验证 HTTP、Schema、真实登录及持久化；运行调度尚未接入'
    save()
    return state


def status(root, expected, uid):
    c, release, objects, job = bundle(root, expected); namespace(c, uid)
    apps = {}
    for name in ['backend', 'frontend']:
        d = json.loads(kubectl(['-n', c['namespace'], 'get', 'deployment', name, '-o', 'json']))
        if d['metadata'].get('labels', {}).get(LABEL) != c['installId']:
            raise Error('应用不属于本次安装')
        apps[name] = eapctl.application_facts(d, release['applications'][name])
    return {'profile': c['profile'], 'applications': apps, 'applicationChecksPassed': all(x['ready'] and x['imageMatches'] for x in apps.values()),
            'installationComplete': False, 'runtimeEnabled': False,
            'unverified': ['数据库 Schema 和持久化', 'HTTP 与真实登录', 'Substrate/网关/调度接入', '生产交付']}


def main():
    parser = argparse.ArgumentParser(description='远程首次安装 JWT 控制台；不安装数据库/IdP/Substrate，不采用已有应用')
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('render'); p.add_argument('--config', required=True); p.add_argument('--release', default=str(HERE / 'release.json')); p.add_argument('--output', required=True)
    for command in ['preflight', 'install', 'status']:
        p = sub.add_parser(command); p.add_argument('--bundle', required=True); p.add_argument('--expected-bundle-sha256', required=True); p.add_argument('--namespace-uid', required=True)
        if command == 'install': p.add_argument('--receipt', required=True)
    args = parser.parse_args()
    try:
        if sys.platform != 'linux': raise Error('仅在远程 Linux 执行')
        if args.command == 'render': result = render(args.config, args.release, args.output)
        else:
            params = [args.bundle, args.expected_bundle_sha256, args.namespace_uid]
            if args.command == 'install': params.append(args.receipt)
            result = {'preflight': preflight, 'install': install, 'status': status}[args.command](*params)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 2 if args.command == 'status' and not result['applicationChecksPassed'] else 0
    except (Error, OSError, ValueError, KeyError, TypeError):
        print(json.dumps({'passed': False, 'error': '安装检查失败；核对配置和原安装回执。未输出配置、Secret 或集群错误正文；不要直接重试写入。'}, ensure_ascii=False))
        return 2


if __name__ == '__main__':
    sys.exit(main())
