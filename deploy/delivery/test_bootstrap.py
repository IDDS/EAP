import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import bootstrap as b

ID = 'c01adba4-ad1d-462d-9e5c-5f4567f31234'
NS_UID = 'a57e2248-9539-4388-8726-2c7366f12345'


def config():
    return {'contract': 'EAP_INSTALL_V1', 'profile': 'CONTROL_PLANE_BOOTSTRAP', 'installId': ID, 'namespace': 'eap-customer',
        'databaseCheckImage': 'registry/mysql@sha256:' + 'a' * 64,
        'database': {'host': 'mysql.data.svc', 'port': 3306, 'name': 'customer_eap', 'secretName': 'eap-database',
                     'usernameKey': 'username', 'passwordKey': 'password', 'sslMode': 'REQUIRED', 'dedicated': True},
        'auth': {'issuer': 'https://login.example/realms/eap', 'jwkSetUri': 'http://keycloak.auth.svc:8080/realms/eap/protocol/openid-connect/certs',
                 'audience': 'eap-api', 'clientId': 'eap-console', 'consoleOrigin': 'https://agents.example'}}


class FakeCluster:
    def __init__(self):
        self.objects = {}; self.calls = []; self.writes = []; self.keys = ['username', 'password']
        self.namespace_uid = NS_UID; self.failed_db = False; self.ambiguous = False

    def __call__(self, args, data=None):
        self.calls.append((args, copy.deepcopy(data)))
        if args[:2] == ['get', 'namespace']:
            return json.dumps({'metadata': {'uid': self.namespace_uid, 'labels': {b.LABEL: ID}}, 'status': {'phase': 'Active'}})
        action = args[2]
        if action == 'get':
            kind, name = args[3:5]
            if kind.lower() == 'secret':
                assert 'go-template=' in args[-1], 'Secret data must not be requested'
                return '\n'.join(self.keys)
            obj = self.objects.get((kind.lower(), name))
            return json.dumps(obj) if obj else ''
        if action == 'create':
            if '--dry-run=server' in args: return 'server validated'
            item = copy.deepcopy(data); key = (item['kind'].lower(), item['metadata']['name'])
            if key in self.objects: raise b.Error('Already exists')
            item['metadata'].update({'uid': 'uid-' + str(len(self.writes)), 'generation': 1})
            if item['kind'] == 'Job': item['status'] = {'failed': 1} if self.failed_db else {'succeeded': 1}
            if item['kind'] == 'Deployment': item['status'] = {'observedGeneration': 1, 'replicas': 1, 'updatedReplicas': 1, 'readyReplicas': 1, 'availableReplicas': 1}
            self.objects[key] = item; self.writes.append(item)
            if self.ambiguous: raise b.Error('Response lost after create')
            return json.dumps(item)
        raise AssertionError(args)


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.config = self.root / 'config.json'; self.config.write_text(json.dumps(config()))
        self.bundle = self.root / 'bundle'; self.receipt = self.root / 'receipt.json'
        self.sha = b.render(self.config, Path(__file__).with_name('release.json'), self.bundle)['bundleSha256']

    def test_configuration_refuses_unknown_fields_and_embedded_credentials(self):
        c = config(); c['database']['password'] = 'PRIVATE_VALUE'
        with self.assertRaises(b.Error): b.configuration(c)
        c = config(); c['auth']['issuer'] = 'https://user:PRIVATE_VALUE@login.example'
        with self.assertRaises(b.Error): b.configuration(c)
        c = config(); c['auth']['jwkSetUri'] += '?token=PRIVATE_VALUE'
        with self.assertRaises(b.Error): b.configuration(c)

    def project_config(self):
        c = config(); c.update(contract='EAP_INSTALL_V2', profile=b.PROJECT_PROFILE, projectKey='alpha', namespace='eap-alpha')
        c['database']['name'] = 'eap_alpha'
        c['auth'].update(audience='eap-alpha-api', clientId='eap-alpha-console')
        return c

    def test_project_profile_requires_dedicated_names_and_exact_contract(self):
        self.assertEqual(b.configuration(self.project_config())['projectKey'], 'alpha')
        for section, key, value in [(None, 'contract', 'EAP_INSTALL_V1'), (None, 'namespace', 'eap-beta'),
                (None, 'projectKey', 'Alpha'), (None, 'projectKey', 'alpha_beta'), ('database', 'name', 'eap_beta'),
                ('auth', 'audience', 'eap-api'), ('auth', 'clientId', 'eap-console')]:
            c = self.project_config(); (c if section is None else c[section])[key] = value
            with self.subTest(key=key), self.assertRaises(b.Error): b.configuration(c)

    def test_project_resources_pin_binding_and_keep_runtime_disabled(self):
        release = json.loads(Path(__file__).with_name('release.json').read_text()); release['schema'] = 93
        objects, _ = b.resources(self.project_config(), release)
        self.assertTrue(all(o['metadata']['labels'][b.PROJECT_LABEL] == 'alpha' for o in objects))
        env = {e['name']: e.get('value') for e in objects[3]['spec']['template']['spec']['containers'][0]['env']}
        self.assertEqual(env['EAP_SECURITY_PROJECT_KEY'], 'alpha')
        self.assertEqual(env['EAP_SECURITY_INITIALIZE_PROJECT_BINDING'], 'true')
        self.assertTrue(all(env[name] == 'false' for name in b.DISABLED))
        release['schema'] = 92
        with self.assertRaises(b.Error): b.resources(self.project_config(), release)

    def test_project_namespace_requires_its_own_label_before_any_writes(self):
        c = self.project_config(); cluster = FakeCluster()
        with patch.object(b, 'kubectl', cluster), self.assertRaises(b.Error): b.namespace(c, NS_UID)
        self.assertEqual(cluster.writes, [])
        def project_namespace(args, data=None):
            value = json.loads(cluster(args, data)); value['metadata']['labels'][b.PROJECT_LABEL] = 'alpha'
            return json.dumps(value)
        with patch.object(b, 'kubectl', project_namespace): self.assertEqual(b.namespace(c, NS_UID)['metadata']['uid'], NS_UID)

    def test_public_urls_require_https_except_loopback(self):
        for value in ['http://public.example', 'https://a.example/#x', 'https://a.example/?x=1', 'ftp://a.example', 'https://a.example/ bad']:
            with self.subTest(value=value), self.assertRaises(b.Error): b.url(value)
        self.assertEqual(b.url('http://localhost:8180/realms/eap'), 'http://localhost:8180/realms/eap')
        with self.assertRaises(b.Error): b.url('https://app.example/', origin=True)

    def test_namespace_and_database_input_cannot_inject_sql_or_shell(self):
        for section, key, value in [('database', 'name', 'eap; DROP TABLE x'), ('database', 'host', 'a;env'), ('database', 'port', True), ('database', 'sslMode', 'PREFERRED')]:
            c = config(); c[section][key] = value
            with self.subTest(key=key), self.assertRaises(b.Error): b.configuration(c)
        for value in ['default', 'kube-system', 'a.b', 'a' * 64]:
            c = config(); c['namespace'] = value
            with self.subTest(value=value), self.assertRaises(b.Error): b.configuration(c)

    def test_profile_dedicated_database_and_fixed_images_required(self):
        for key, value in [('profile', 'PRODUCTION'), ('databaseCheckImage', 'mysql:latest')]:
            c = config(); c[key] = value
            with self.assertRaises(b.Error): b.configuration(c)
        c = config(); c['database']['dedicated'] = False
        with self.assertRaises(b.Error): b.configuration(c)

    def test_render_has_jwt_secret_references_and_no_runtime_permissions(self):
        c, release, objects, job = b.bundle(self.bundle, self.sha)
        self.assertEqual([o['kind'] for o in objects], ['ServiceAccount', 'Service', 'Service', 'Deployment', 'Deployment'])
        backend = objects[3]['spec']; container = backend['template']['spec']['containers'][0]
        env = {x['name']: x for x in container['env']}
        self.assertEqual(env['EAP_SECURITY_MODE']['value'], 'JWT')
        self.assertTrue(all(env[name]['value'] == 'false' for name in b.DISABLED))
        self.assertIn('secretKeyRef', env['EAP_DB_PASSWORD']['valueFrom'])
        self.assertNotIn('allowPublicKeyRetrieval', env['EAP_DB_URL']['value'])
        self.assertFalse(backend['template']['spec']['automountServiceAccountToken'])
        self.assertEqual(backend['strategy'], {'type': 'Recreate'})
        self.assertEqual(job['spec']['backoffLimit'], 0)
        self.assertNotIn('DROP', job['spec']['template']['spec']['containers'][0]['command'][-1])

    def test_bundle_refuses_tamper_extra_files_symlink_and_reuse(self):
        p = self.bundle / 'resources.json'; original = p.read_bytes(); p.write_text('{}')
        with self.assertRaises(b.Error): b.bundle(self.bundle, self.sha)
        p.write_bytes(original); (self.bundle / 'extra').write_text('PRIVATE_VALUE')
        with self.assertRaises(b.Error): b.bundle(self.bundle, self.sha)
        (self.bundle / 'extra').unlink(); (self.root / 'linked').symlink_to(self.bundle, target_is_directory=True)
        with self.assertRaises(b.Error): b.bundle(self.root / 'linked', self.sha)
        with self.assertRaises(FileExistsError): b.render(self.config, Path(__file__).with_name('release.json'), self.bundle)

    def test_preflight_reads_only_secret_key_names_and_dry_runs(self):
        cluster = FakeCluster()
        with patch.object(b, 'kubectl', cluster): result = b.preflight(self.bundle, self.sha, NS_UID)
        self.assertTrue(result['passed']); self.assertFalse(result['databaseEmptyVerified'])
        self.assertEqual(cluster.writes, [])
        self.assertEqual(len([args for args, data in cluster.calls if '--dry-run=server' in args]), 1)
        template = next(args[-1] for args, data in cluster.calls if args[-1].startswith('go-template='))
        self.assertNotIn('\n', template)
        self.assertIn(r'{{"\n"}}', template)

    def test_preflight_refuses_missing_secret_keys(self):
        cluster = FakeCluster(); cluster.keys = ['username']
        with patch.object(b, 'kubectl', cluster), self.assertRaises(b.Error): b.preflight(self.bundle, self.sha, NS_UID)
        self.assertEqual(cluster.writes, [])

    def test_namespace_uid_drift_stops_before_resource_reads(self):
        cluster = FakeCluster(); cluster.namespace_uid = 'different'
        with patch.object(b, 'kubectl', cluster), self.assertRaises(b.Error): b.preflight(self.bundle, self.sha, NS_UID)
        self.assertEqual(len(cluster.calls), 1)

    def test_existing_application_never_adopted(self):
        cluster = FakeCluster(); cluster.objects[('deployment', 'backend')] = {'metadata': {'name': 'backend'}}
        with patch.object(b, 'kubectl', cluster), self.assertRaises(b.Error): b.install(self.bundle, self.sha, NS_UID, self.receipt)
        self.assertFalse(self.receipt.exists()); self.assertEqual(cluster.writes, [])

    def test_nonempty_or_unreachable_database_blocks_all_app_creates(self):
        cluster = FakeCluster(); cluster.failed_db = True
        with patch.object(b, 'kubectl', cluster), self.assertRaises(b.Error): b.install(self.bundle, self.sha, NS_UID, self.receipt)
        self.assertEqual([o['kind'] for o in cluster.writes], ['Job'])
        self.assertEqual(json.loads(self.receipt.read_text())['stage'], 'DATABASE_CHECK_FAILED')

    def test_ambiguous_create_retains_intent_and_never_retries(self):
        cluster = FakeCluster(); cluster.ambiguous = True
        with patch.object(b, 'kubectl', cluster), self.assertRaises(b.Error): b.install(self.bundle, self.sha, NS_UID, self.receipt)
        self.assertEqual(len(cluster.writes), 1)
        self.assertEqual(json.loads(self.receipt.read_text())['resources'][0]['status'], 'INTENT_RECORDED')
        with patch.object(b, 'kubectl') as call, self.assertRaises(b.Error): b.install(self.bundle, self.sha, NS_UID, self.receipt)
        call.assert_not_called()

    def test_success_still_requires_acceptance_and_restart_does_not_repeat_db_gate(self):
        cluster = FakeCluster()
        with patch.object(b, 'kubectl', cluster):
            result = b.install(self.bundle, self.sha, NS_UID, self.receipt)
            status = b.status(self.bundle, self.sha, NS_UID)
        self.assertEqual(len(cluster.writes), 6)
        self.assertEqual(result['stage'], 'APPLICATIONS_CREATED'); self.assertFalse(result['installationComplete'])
        self.assertTrue(status['applicationChecksPassed']); self.assertFalse(status['runtimeEnabled'])
        backend = cluster.objects[('deployment', 'backend')]
        self.assertNotIn('initContainers', backend['spec']['template']['spec'])

    def test_receipt_must_be_outside_bundle(self):
        with patch.object(b, 'kubectl') as call, self.assertRaises(b.Error):
            b.install(self.bundle, self.sha, NS_UID, self.bundle / 'receipt.json')
        call.assert_not_called()

    def test_cli_errors_are_sanitized_and_local_execution_blocked(self):
        out = io.StringIO()
        with patch('sys.argv', ['bootstrap.py', 'render', '--config', str(self.config), '--output', str(self.root / 'new')]), patch('sys.platform', 'darwin'), contextlib.redirect_stdout(out):
            self.assertEqual(b.main(), 2)
        self.assertFalse(json.loads(out.getvalue())['passed'])
        with patch('sys.platform', 'linux'), patch.object(b.subprocess, 'run', side_effect=b.subprocess.CalledProcessError(1, ['kubectl'], stderr='PRIVATE_VALUE')):
            with self.assertRaises(b.Error) as error: b.kubectl(['get', 'pods'])
        self.assertNotIn('PRIVATE_VALUE', str(error.exception))


if __name__ == '__main__': unittest.main()
