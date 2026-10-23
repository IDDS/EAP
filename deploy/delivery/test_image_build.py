import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import image_build as build
from eapctl import DeliveryError


class ImageBuildTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / 'artifacts'
        self.source.mkdir()
        self.output = self.root / 'context'
        self.manifest = self.root / 'artifacts.json'
        self.lock = self.root / 'bases.json'
        self.lock.write_text(json.dumps({'contract': 'EAP_RUNTIME_BASES_V1', 'platform': 'linux/amd64',
            'applications': {'backend': 'registry/base/java@sha256:' + 'a' * 64,
                             'frontend': 'registry/base/nginx@sha256:' + 'b' * 64}}))

    def inputs(self, component='backend'):
        data = {'app.jar': b'fixed artifact'} if component == 'backend' else {
            'nginx.conf': b'fixed config', 'dist/index.html': b'index',
            'dist/version.json': b'{"buildId":"original"}', 'dist/assets/app.js': b'code'}
        for name, content in data.items():
            p = self.source / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(content)
        self.manifest.write_text(json.dumps({n: hashlib.sha256(b).hexdigest() for n, b in data.items()}))

    def prepare(self, component='backend'):
        return build.prepare(component, self.source, self.manifest, self.lock, self.output)

    def test_backend_uses_locked_base_and_same_artifact(self):
        self.inputs()
        result = self.prepare()
        self.assertTrue(result['passed'])
        self.assertFalse(result['testsVerified'])
        self.assertFalse(result['deployed'])
        self.assertEqual((self.source / 'app.jar').read_bytes(), (self.output / 'app.jar').read_bytes())
        self.assertTrue((self.output / 'Dockerfile').read_text().startswith('FROM registry/base/java@sha256:'))
        self.assertNotIn('apt-get', (self.output / 'Dockerfile').read_text())

    def test_frontend_preserves_exact_tree_and_version(self):
        self.inputs('frontend')
        result = self.prepare('frontend')
        self.assertEqual(result['artifactFiles'], 4)
        self.assertEqual((self.source / 'dist/version.json').read_bytes(), (self.output / 'dist/version.json').read_bytes())

    def test_context_is_deterministic_for_same_inputs(self):
        self.inputs()
        a = self.prepare()
        b = build.prepare('backend', self.source, self.manifest, self.lock, self.root / 'second')
        self.assertEqual(a['contextSha256'], b['contextSha256'])

    def test_refuses_changed_artifact_before_creating_output(self):
        self.inputs()
        (self.source / 'app.jar').write_bytes(b'changed')
        with self.assertRaises(DeliveryError): self.prepare()
        self.assertFalse(self.output.exists())

    def test_refuses_extra_and_missing_files(self):
        self.inputs()
        extra = self.source / 'private.env'
        extra.write_text('PRIVATE_VALUE')
        with self.assertRaises(DeliveryError): self.prepare()
        extra.unlink()
        (self.source / 'app.jar').unlink()
        with self.assertRaises(DeliveryError): self.prepare()

    def test_rejects_paths_and_malformed_hashes(self):
        for name in ['../app.jar', '/app.jar', 'dist/./app.js', 'dist//app.js', 'dist/a\nb', 'a\\b']:
            with self.subTest(name=name), self.assertRaises(DeliveryError): build.entries({name: 'a' * 64})
        with self.assertRaises(DeliveryError): build.entries({'app.jar': 'invalid'})

    def test_rejects_file_directory_and_root_symlinks(self):
        self.inputs()
        p = self.source / 'app.jar'
        p.unlink()
        (self.root / 'outside').write_text('fixed artifact')
        p.symlink_to(self.root / 'outside')
        with self.assertRaises(DeliveryError): self.prepare()
        p.unlink()
        p.write_text('fixed artifact')
        (self.source / 'linked').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(DeliveryError): self.prepare()
        (self.source / 'linked').unlink()
        (self.root / 'link').symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(DeliveryError): build.prepare('backend', self.root / 'link', self.manifest, self.lock, self.output)

    def test_rejects_mutable_base_or_platform_drift(self):
        self.inputs()
        original = json.loads(self.lock.read_text())
        data = json.loads(self.lock.read_text())
        data['applications']['backend'] = 'registry/base/java:latest'
        self.lock.write_text(json.dumps(data))
        with self.assertRaises(DeliveryError): self.prepare()
        original['platform'] = 'linux/arm64'
        self.lock.write_text(json.dumps(original))
        with self.assertRaises(DeliveryError): self.prepare()

    def test_will_not_overwrite_or_nest_context_in_artifacts(self):
        self.inputs()
        self.prepare()
        with self.assertRaises(DeliveryError): self.prepare()
        with self.assertRaises(DeliveryError): build.prepare('backend', self.source, self.manifest, self.lock, self.source / 'nested')

    def test_verification_rejects_added_content_and_dockerfile_changes(self):
        self.inputs()
        self.prepare()
        extra = self.output / 'extra'
        extra.write_text('PRIVATE_VALUE')
        with self.assertRaises(DeliveryError): build.verify(self.output, self.lock)
        extra.unlink()
        (self.output / 'Dockerfile').write_text('FROM untested:latest')
        receipt = json.loads((self.output / 'context.json').read_text())
        receipt['files']['Dockerfile'] = build.sha(self.output / 'Dockerfile')
        (self.output / 'context.json').write_text(json.dumps(receipt))
        with self.assertRaises(DeliveryError): build.verify(self.output, self.lock)

    def test_duplicate_json_keys_and_component_injection_rejected(self):
        self.manifest.write_text('{"app.jar":"a","app.jar":"b"}')
        with self.assertRaises(DeliveryError): build.read_json(self.manifest)
        with self.assertRaises(DeliveryError): build.payload('../backend', {'app.jar': 'a' * 64})

    def test_external_receipt_hash_rejects_artifact_and_receipt_rewrite(self):
        self.inputs()
        proof = self.prepare()
        self.assertTrue(build.verify(self.output, self.lock, proof['contextSha256'])['passed'])
        (self.output / 'app.jar').write_bytes(b'changed after preparation')
        receipt = json.loads((self.output / 'context.json').read_text())
        for field in ['files', 'artifacts']:
            receipt[field]['app.jar'] = build.sha(self.output / 'app.jar')
        (self.output / 'context.json').write_text(json.dumps(receipt))
        with self.assertRaises(DeliveryError): build.verify(self.output, self.lock, proof['contextSha256'])

    def test_byte_limit_and_frontend_required_files(self):
        self.inputs()
        with patch.object(build, 'MAX_BYTES', 2), self.assertRaises(DeliveryError): self.prepare()
        with self.assertRaises(DeliveryError): build.payload('frontend', {'dist/index.html': 'a' * 64})

    def test_cli_blocks_local_and_omits_file_contents(self):
        self.inputs()
        argv = ['image_build.py', '--runtime-bases', str(self.lock), 'prepare', 'backend',
                '--artifacts', str(self.source), '--artifact-manifest', str(self.manifest), '--output', str(self.output)]
        out = io.StringIO()
        with patch('sys.argv', argv), patch('sys.platform', 'darwin'), contextlib.redirect_stdout(out):
            self.assertEqual(build.main(), 2)
        self.assertFalse(json.loads(out.getvalue())['passed'])
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
