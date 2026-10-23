"""Install EAP LiteLLM instrumentation without importing LiteLLM at process startup."""

from __future__ import annotations

import importlib.abc
import importlib.machinery
import sys

from eap_litellm_callback import install


class _LiteLLMLoader(importlib.abc.Loader):
    def __init__(self, original_loader):
        self.original_loader = original_loader

    def __getattr__(self, name):
        # Preserve resource readers and loader APIs required by importlib.resources.
        return getattr(self.original_loader, name)

    def create_module(self, spec):
        creator = getattr(self.original_loader, "create_module", None)
        return creator(spec) if creator else None

    def exec_module(self, module):
        self.original_loader.exec_module(module)
        install(module)


class _LiteLLMFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname != "litellm":
            return None
        spec = importlib.machinery.PathFinder.find_spec(fullname, path)
        if spec is None or spec.loader is None:
            return spec
        sys.meta_path.remove(self)
        spec.loader = _LiteLLMLoader(spec.loader)
        return spec


if "litellm" in sys.modules:
    install(sys.modules["litellm"])
else:
    sys.meta_path.insert(0, _LiteLLMFinder())
