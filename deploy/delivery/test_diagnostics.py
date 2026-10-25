import copy
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import diagnostics as d
from test_eapctl import OLD, deployment

NOW = datetime(2026, 10, 25, 12, tzinfo=timezone.utc)
PRIVATE = 'SYNTHETIC_PRIVATE_VALUE_NOT_A_CREDENTIAL'


def fs():
    return {'time': NOW.isoformat(), 'capacityBytes': 100 * 1024 ** 3, 'availableBytes': 30 * 1024 ** 3,
            'inodes': 10000, 'inodesFree': 9000}


class Cluster:
    def __init__(self):
        self.calls = []; self.denied = set(); self.changed = False
        self.objects = {'namespace': {'metadata': {'uid': 'ns-original', 'annotations': {'private': PRIVATE}}},
                        'pods': {'items': [], 'metadata': {}}, 'replicasets': {'items': [], 'metadata': {}},
                        'persistentvolumeclaims': {'items': [{'metadata': {'name': 'data'}, 'status': {'phase': 'Bound', 'capacity': {'storage': '2Gi'}}}]},
                        'node': {'status': {'conditions': [{'type': k, 'status': 'True' if k == 'Ready' else 'False', 'message': PRIVATE} for k in ['Ready', 'MemoryPressure', 'DiskPressure', 'PIDPressure']]}},
                        'summary': {'node': {'nodeName': 'worker-1', 'fs': fs(), 'runtime': {'imageFs': fs()}}, 'pods': [{'private': PRIVATE}]},
                        'configz': {'kubeletconfig': {'evictionHard': {k + suffix: value for k in ['nodefs', 'imagefs'] for suffix, value in [('.available', '15%'), ('.inodesFree', '5%')]}, 'authentication': PRIVATE}}}
        for component in ['backend', 'frontend']:
            item = deployment(component); item['metadata']['uid'] = component + '-uid'; item['spec']['template']['spec']['containers'][0]['env'][0]['value'] = PRIVATE
            self.objects[component] = item
            self.objects['replicasets']['items'].append({'metadata': {'uid': component + '-rs', 'ownerReferences': [{'kind': 'Deployment', 'uid': component + '-uid', 'controller': True}]}})
            self.objects['pods']['items'].append({'metadata': {'name': component + '-pod', 'ownerReferences': [{'kind': 'ReplicaSet', 'uid': component + '-rs', 'controller': True}], 'annotations': {'private': PRIVATE}},
                'spec': {'nodeName': 'worker-1', 'containers': [{'command': [PRIVATE], 'env': [{'value': PRIVATE}]}]},
                'status': {'phase': 'Running', 'conditions': [{'type': 'Ready', 'status': 'True'}], 'containerStatuses': [{'name': component, 'ready': True, 'restartCount': 0, 'imageID': OLD, 'state': {'running': {}}, 'lastState': {'terminated': {'message': PRIVATE}}}]}})

    def __call__(self, namespace, args):
        self.calls.append(args)
        assert namespace == 'eap' and args[0] == 'get'
        if args[1] == '--raw': key = args[2].split('?')[0].rsplit('/', 1)[-1]
        elif args[1] == 'deployment': key = args[2]
        else: key = args[1]
        if key in self.denied: raise d.DeliveryError(PRIVATE)
        value = copy.deepcopy(self.objects[key])
        if self.changed and key == 'namespace' and sum(a[1] == 'namespace' for a in self.calls) > 1:
            value['metadata']['uid'] = 'replaced'
        return value


def collect(cluster=None, **kwargs):
    cluster = cluster or Cluster()
    return d.collect({'applications': {'backend': OLD, 'frontend': OLD}}, 'eap', read=cluster, now=NOW, **kwargs)


class DiagnosticTests(unittest.TestCase):
    def test_healthy_owned_pods_and_fresh_disk_are_separate_from_production_acceptance(self):
        cluster = Cluster(); report = collect(cluster)
        self.assertEqual(report['status'], 'PASS'); self.assertFalse(report['productionReady'])
        self.assertIn('PVC实际使用量', report['unverified']); self.assertTrue(report['snapshotOnly'])
        self.assertFalse(report['volumes'][0]['usageVerified'])
        self.assertEqual(report['nodes']['worker-1'][0]['marginBytes'], 15 * 1024 ** 3)
        self.assertTrue(all(a[0] == 'get' for a in cluster.calls))
        self.assertFalse(any(word in json.dumps(cluster.calls) for word in ['secret', 'logs', 'exec', 'patch', 'delete']))

    def test_export_whitelist_omits_all_raw_config_and_free_text(self):
        report = collect(); text = json.dumps(report)
        self.assertNotIn(PRIVATE, text)
        for field in ['annotations', 'authentication', 'ownerReferences', 'lastState', 'environment', 'secretReferences']:
            self.assertNotIn(field, text)

    def test_label_spoofed_or_orphan_pod_cannot_satisfy_readiness(self):
        cluster = Cluster(); pod = cluster.objects['pods']['items'][0]
        pod['metadata']['ownerReferences'][0]['uid'] = 'unrelated'; pod['metadata']['labels'] = {'app': 'backend'}
        self.assertEqual(collect(cluster)['status'], 'FAIL')

    def test_actual_pod_digest_mismatch_fails_even_if_deployment_matches(self):
        cluster = Cluster(); cluster.objects['pods']['items'][0]['status']['containerStatuses'][0]['imageID'] = OLD[:-1] + 'b'
        report = collect(cluster)
        self.assertTrue(report['applications']['backend']['imageMatches']); self.assertEqual(report['status'], 'FAIL')

    def test_pending_and_container_crashes_are_distinct_from_restart_warning(self):
        cluster = Cluster(); status = cluster.objects['pods']['items'][0]['status']
        status['containerStatuses'][0]['restartCount'] = 2
        self.assertEqual(collect(cluster)['status'], 'WARN')
        status['phase'] = 'Pending'; status['containerStatuses'][0]['state'] = {'waiting': {'reason': 'CrashLoopBackOff', 'message': PRIVATE}}
        report = collect(cluster)
        self.assertEqual(report['status'], 'FAIL'); self.assertEqual(report['applications']['backend']['pods'][0]['reasons'], ['CrashLoopBackOff'])
        self.assertNotIn(PRIVATE, json.dumps(report))

    def test_deleted_or_unready_container_does_not_pass(self):
        for field in ['terminating', 'container']:
            cluster = Cluster(); pod = cluster.objects['pods']['items'][0]
            if field == 'terminating': pod['metadata']['deletionTimestamp'] = NOW.isoformat()
            else: pod['status']['containerStatuses'][0]['ready'] = False
            self.assertEqual(collect(cluster)['status'], 'FAIL')

    def test_read_failures_are_unknown_without_exception_body(self):
        for resource in ['pods', 'replicasets', 'persistentvolumeclaims', 'summary', 'configz', 'backend', 'namespace']:
            cluster = Cluster(); cluster.denied.add(resource)
            with self.subTest(resource=resource):
                report = collect(cluster); self.assertEqual(report['status'], 'UNKNOWN'); self.assertNotIn(PRIVATE, json.dumps(report))

    def test_truncated_listing_cannot_claim_missing_pods_or_green(self):
        for resource in ['pods', 'replicasets', 'persistentvolumeclaims']:
            cluster = Cluster(); cluster.objects[resource]['metadata'] = {'continue': PRIVATE}
            report = collect(cluster)
            self.assertEqual(report['status'], 'UNKNOWN'); self.assertNotIn(PRIVATE, json.dumps(report))

    def test_namespace_replacement_marks_snapshot_unknown(self):
        cluster = Cluster(); cluster.changed = True
        self.assertEqual(collect(cluster)['status'], 'UNKNOWN')

    def test_node_pressure_fails_and_missing_condition_is_unknown(self):
        cluster = Cluster(); conditions = cluster.objects['node']['status']['conditions']
        conditions[2]['status'] = 'True'; self.assertEqual(collect(cluster)['status'], 'FAIL')
        conditions.pop(2); self.assertEqual(collect(cluster)['status'], 'UNKNOWN')

    def test_disk_margin_uses_configured_percentage_or_absolute_threshold(self):
        stats = fs(); hard = {'imagefs.available': '29Gi', 'imagefs.inodesFree': '5%'}
        result = d.filesystem(stats, hard, 'imagefs', NOW, 2 * 1024 ** 3, 120)
        self.assertEqual(result['status'], 'WARN'); self.assertEqual(result['marginBytes'], 1024 ** 3)
        hard['imagefs.available'] = '30Gi'; self.assertEqual(d.filesystem(stats, hard, 'imagefs', NOW, 1024, 120)['status'], 'FAIL')
        hard['imagefs.available'] = '29.5%'; self.assertEqual(d.filesystem(stats, hard, 'imagefs', NOW, 1024, 120)['marginBytes'], 512 * 1024 ** 2)

    def test_inode_exhaustion_fails_even_with_plenty_of_bytes(self):
        cluster = Cluster(); cluster.objects['summary']['node']['fs']['inodesFree'] = 499
        self.assertEqual(collect(cluster)['status'], 'FAIL')

    def test_missing_threshold_inodes_and_containerfs_are_not_inferred(self):
        cluster = Cluster(); cluster.objects['configz']['kubeletconfig']['evictionHard'].pop('nodefs.available')
        self.assertEqual(collect(cluster)['status'], 'UNKNOWN')
        cluster = Cluster(); cluster.objects['summary']['node']['fs'].pop('inodesFree')
        self.assertEqual(collect(cluster)['status'], 'UNKNOWN')
        cluster = Cluster(); cluster.objects['summary']['node']['runtime']['containerFs'] = fs()
        self.assertEqual(collect(cluster)['status'], 'UNKNOWN')

    def test_stale_future_and_mismatched_node_stats_do_not_pass(self):
        for offset in [-121, 31]:
            cluster = Cluster(); cluster.objects['summary']['node']['fs']['time'] = (NOW + timedelta(seconds=offset)).isoformat()
            self.assertEqual(collect(cluster)['status'], 'UNKNOWN')
        cluster = Cluster(); cluster.objects['summary']['node']['nodeName'] = 'other-node'
        self.assertEqual(collect(cluster)['status'], 'UNKNOWN')

    def test_invalid_stats_values_are_unknown(self):
        for value in [True, -1, 101 * 1024 ** 3, '10Gi']:
            cluster = Cluster(); cluster.objects['summary']['node']['fs']['availableBytes'] = value
            self.assertEqual(collect(cluster)['status'], 'UNKNOWN')

    def test_byte_quantities_reject_unsupported_values_and_preserve_precision(self):
        self.assertEqual(d.quantity('1Gi'), 1024 ** 3); self.assertEqual(d.quantity('1G'), 10 ** 9)
        self.assertEqual(d.quantity('1e6'), 10 ** 6); self.assertEqual(d.quantity('1m'), Decimal('.001'))
        for value in ['1garbage', '-1Gi', 'NaN', '1e999999', '1Ki\n', True]:
            with self.subTest(value=value), self.assertRaises(d.DeliveryError): d.quantity(value)

    def test_invalid_parameters_do_not_read_cluster(self):
        for kwargs in [{'reserve_bytes': 0}, {'max_age': 0}]:
            cluster = Cluster()
            with self.assertRaises(d.DeliveryError): collect(cluster, **kwargs)
            self.assertEqual(cluster.calls, [])

    def test_bundle_uses_private_modes_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'bundle'; result = d.write_bundle(collect(), root)
            self.assertEqual(result['reportSha256'], d.sha((root / 'report.json').read_bytes()))
            self.assertEqual(root.stat().st_mode & 0o777, 0o700)
            self.assertEqual((root / 'report.json').stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError): d.write_bundle(collect(), root)

    def test_retention_keeps_newest_per_namespace_and_uses_recorded_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for ns in ['eap', 'eap-other']:
                for index, age in enumerate([1, 20, 30, 40]):
                    report = collect(); report['namespace'] = ns; report['createdAt'] = (NOW - timedelta(days=age)).isoformat()
                    d.write_bundle(report, root / (ns + '-' + str(index)))
            result = d.retention_plan(root, now=NOW, keep=2)
            self.assertEqual(len(result['candidates']), 4); self.assertEqual(result['protectedBundles'], 4)
            self.assertFalse(result['deleteExecuted']); self.assertEqual(len(list(root.iterdir())), 8)
            self.assertTrue(all('manifestSha256' in c and 'reportSha256' in c for c in result['candidates']))

    def test_retention_protects_tampered_extra_symlink_future_and_partial_bundles(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for index in range(5): d.write_bundle(collect(), root / str(index))
            (root/'0/report.json').write_text('{}')
            (root/'1/other').write_text(PRIVATE)
            (root/'2/report.json').unlink(); (root/'2/report.json').symlink_to(root/'0/report.json')
            (root/'3/manifest.json').unlink()
            report = collect(); report['createdAt'] = (NOW + timedelta(days=1)).isoformat(); d.write_bundle(report, root/'future')
            (root/'linked').symlink_to(root/'4', target_is_directory=True)
            result = d.retention_plan(root, now=NOW)
            self.assertEqual(result['unmanagedEntries'], 6); self.assertEqual(result['protectedBundles'], 1)
            self.assertEqual(result['candidates'], []); self.assertNotIn(PRIVATE, json.dumps(result))

    def test_cli_invalid_retention_parameters_return_safe_error_without_traceback(self):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('eapctl.py')), 'plan-diagnostic-retention', '--root', '/not-used', '--days', '0'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2); self.assertFalse(json.loads(result.stdout)['passed']); self.assertEqual(result.stderr, '')

    def test_retention_stops_enumerating_at_limit_without_scanning_remaining_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            def entries(_):
                for index in range(1001): yield Path(tmp) / str(index)
                raise AssertionError('unbounded directory enumeration')
            with patch.object(Path, 'iterdir', entries), self.assertRaises(d.DeliveryError):
                d.retention_plan(tmp, now=NOW)


if __name__ == '__main__': unittest.main()
