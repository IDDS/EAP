import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import eapctl

OLD = 'registry.example/eap@sha256:' + 'a' * 64
NEW = 'registry.example/eap@sha256:' + 'b' * 64


def deployment(name='backend'):
    return {'metadata': {'name': name, 'uid': 'original-uid', 'resourceVersion': '42', 'generation': 3},
            'spec': {'replicas': 1, 'strategy': {'type': 'RollingUpdate'}, 'template': {'spec': {'containers': [
                {'name': name, 'image': OLD, 'env': [{'name': 'PRIVATE_PASSWORD', 'value': 'PRIVATE_VALUE'},
                                                  {'name': 'DB', 'valueFrom': {'secretKeyRef': {'name': 'eap-db', 'key': 'password'}}}]}]}}},
            'status': {'observedGeneration': 3, 'replicas': 1, 'updatedReplicas': 1, 'readyReplicas': 1, 'availableReplicas': 1}}


class DeliveryTests(unittest.TestCase):
    def test_mutable_or_malformed_image_rejected(self):
        for value in ['repo:latest', 'repo@sha256:abc', OLD + '\n', None]:
            with self.assertRaises(eapctl.DeliveryError): eapctl.image(value)

    def test_inspection_omits_environment_values_and_distinguishes_generation_and_image(self):
        d=deployment(); result=eapctl.application_facts(d,OLD)
        self.assertTrue(result['ready']); self.assertTrue(result['imageMatches'])
        self.assertNotIn('PRIVATE_',json.dumps(result)); self.assertEqual(result['secretReferences'],['eap-db'])
        d['status']['replicas']=2
        self.assertFalse(eapctl.application_facts(d,OLD)['ready'])
        d['status']['replicas']=1
        d['status']['observedGeneration']=2
        result=eapctl.application_facts(d,NEW); self.assertFalse(result['ready']); self.assertFalse(result['imageMatches'])

    def test_upgrade_plan_tests_identity_version_and_image_without_applying(self):
        d=deployment(); original=copy.deepcopy(d); plan=eapctl.upgrade_plan(d,'backend',OLD,NEW)
        self.assertEqual(d,original)
        self.assertEqual([p['path'] for p in plan['patch'][:3]],['/metadata/uid','/metadata/resourceVersion','/spec/template/spec/containers/0/image'])
        self.assertEqual(plan['patch'][-1]['value'],{'type':'Recreate'})
        self.assertNotIn('PRIVATE_',json.dumps(plan)); self.assertEqual(plan['execution'],'REVIEW_REQUIRED_NOT_APPLIED')

    def test_plan_rejects_drift_and_noop_does_not_restart(self):
        with self.assertRaises(eapctl.DeliveryError): eapctl.upgrade_plan(deployment(),'backend',NEW,OLD)
        self.assertEqual(eapctl.upgrade_plan(deployment(),'backend',OLD,OLD)['patch'],[])
        plan=eapctl.upgrade_plan(deployment('frontend'),'frontend',OLD,NEW)
        self.assertEqual(len(plan['patch']),4)

    def test_source_verification_rejects_tampering_traversal_and_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'source';root.mkdir(); f=root/'input';f.write_text('original')
            manifest=Path(tmp)/'manifest.json';sha=hashlib.sha256(f.read_bytes()).hexdigest()
            manifest.write_text(json.dumps({'input':sha}))
            self.assertEqual(eapctl.verify_source(root,manifest)['verifiedFiles'],1)
            f.write_text('changed')
            with self.assertRaises(eapctl.DeliveryError): eapctl.verify_source(root,manifest)
            manifest.write_text(json.dumps({'../outside':sha}))
            with self.assertRaises(eapctl.DeliveryError): eapctl.verify_source(root,manifest)
            (root/'link').symlink_to(f);manifest.write_text(json.dumps({'link':sha}))
            with self.assertRaises(eapctl.DeliveryError): eapctl.verify_source(root,manifest)

    def test_cluster_failures_do_not_disclose_command_output(self):
        with patch('eapctl.sys.platform','linux'),patch('eapctl.subprocess.run',side_effect=eapctl.subprocess.CalledProcessError(1,['kubectl'],stderr='PRIVATE_VALUE')):
            with self.assertRaises(eapctl.DeliveryError) as error: eapctl.kubectl('eap',['get','deployment'])
            self.assertNotIn('PRIVATE_VALUE',str(error.exception))

    def test_manifest_does_not_allow_unverified_production_claim(self):
        data=json.loads(Path(__file__).with_name('release.json').read_text());data['productionReady']=True
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'release.json';path.write_text(json.dumps(data))
            with self.assertRaises(eapctl.DeliveryError): eapctl.manifest(path)


if __name__=='__main__': unittest.main()
