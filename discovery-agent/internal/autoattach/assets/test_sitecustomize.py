import importlib.util
import importlib.machinery
import importlib.resources
import pathlib
import sys
import tempfile
import unittest


class LoaderResourceTests(unittest.TestCase):
    def test_wrapped_loader_preserves_package_resources(self):
        original_path = list(sys.meta_path)
        try:
            spec = importlib.util.spec_from_file_location('eap_sitecustomize_test', pathlib.Path(__file__).with_name('sitecustomize.py'))
            instrument = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(instrument)
        finally:
            sys.meta_path[:] = original_path
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / '__init__.py').write_text('')
            (root / 'costs.json').write_text('{"offline":true}')
            loader = importlib.machinery.SourceFileLoader('eap_resource_fixture', str(root / '__init__.py'))
            wrapped = instrument._LiteLLMLoader(loader)
            spec = importlib.util.spec_from_file_location('eap_resource_fixture', root / '__init__.py', loader=wrapped, submodule_search_locations=[str(root)])
            module = importlib.util.module_from_spec(spec)
            sys.modules[module.__name__] = module
            try:
                wrapped.exec_module(module)
                self.assertEqual(importlib.resources.files(module).joinpath('costs.json').read_text(), '{"offline":true}')
            finally:
                sys.modules.pop(module.__name__, None)


if __name__ == '__main__':
    unittest.main()
