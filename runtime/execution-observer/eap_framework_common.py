"""Explicit, version-pinned framework boundaries. No global monkey patching."""
import inspect
from functools import wraps
from importlib.metadata import PackageNotFoundError, version
from typing import get_type_hints

from eap_execution import _slug


def require_framework(journal, distribution, expected_version):
    try:
        installed = version(distribution)
    except PackageNotFoundError:
        raise ValueError("framework dependency is unavailable") from None
    if installed != expected_version:
        raise ValueError("framework version is not in the validated adapter contract")
    if (journal.framework, journal.version) != (distribution, expected_version):
        raise ValueError("journal framework identity differs")


def preserve_annotations(wrapper, function):
    # Framework dispatch resolves annotations in the wrapper module otherwise.
    try:
        hints = get_type_hints(function)
    except (NameError, TypeError):
        raise ValueError("framework callable annotations must be resolvable") from None
    signature = inspect.signature(function)
    wrapper.__annotations__ = hints
    wrapper.__signature__ = signature.replace(parameters=[
        p.replace(annotation=hints.get(p.name, p.annotation)) for p in signature.parameters.values()],
        return_annotation=hints.get("return", signature.return_annotation))
    return wrapper


def observed_callable(function, context):
    if inspect.isgeneratorfunction(function) or inspect.isasyncgenfunction(function):
        raise ValueError("streaming callables require a separate lifecycle adapter")
    if not inspect.isfunction(function) and not inspect.ismethod(function):
        raise ValueError("adapter requires a declared function or bound method")
    if inspect.iscoroutinefunction(function):
        @wraps(function)
        async def async_call(*args, **kwargs):
            with context():
                return await function(*args, **kwargs)
        return preserve_annotations(async_call, function)

    @wraps(function)
    def sync_call(*args, **kwargs):
        with context():
            result = function(*args, **kwargs)
            if inspect.isawaitable(result):
                if inspect.iscoroutine(result):
                    result.close()
                raise TypeError("declare async callables with async def")
            return result
    return preserve_annotations(sync_call, function)


def tool_callable(journal, definition_key, function):
    key = _slug(definition_key, 64)
    return observed_callable(function, lambda: journal.tool(key))
