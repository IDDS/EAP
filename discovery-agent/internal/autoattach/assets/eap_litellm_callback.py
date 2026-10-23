"""Zero-code LiteLLM telemetry callback for EAP.

This module is mounted read-only into an Agent container. It never reads provider
API keys and sends content only to the Agent-scoped host collector.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import threading
import urllib.request
import uuid
from datetime import datetime, timezone
from typing import Any


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}


def _as_dict(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    for method_name in ("model_dump", "dict"):
        method = getattr(value, method_name, None)
        if callable(method):
            try:
                result = method()
                if isinstance(result, dict):
                    return result
            except Exception:
                pass
    return {}


def _json_text(value: Any, max_chars: int) -> str:
    if isinstance(value, str):
        text = value
    else:
        text = json.dumps(value, ensure_ascii=False, default=str, separators=(",", ":"))
    return text[:max_chars]


def _without_sensitive_fields(value: Any) -> Any:
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            normalized = str(key).lower().replace("-", "_")
            if any(marker in normalized for marker in ("api_key", "authorization", "credential", "access_token", "secret_key")):
                continue
            if normalized == "_hidden_params":
                continue
            result[str(key)] = _without_sensitive_fields(item)
        return result
    if isinstance(value, list):
        return [_without_sensitive_fields(item) for item in value]
    return value


def _response_content(document: dict[str, Any]) -> str:
    choices = document.get("choices") or []
    parts: list[str] = []
    for choice in choices if isinstance(choices, list) else []:
        item = _as_dict(choice)
        message = _as_dict(item.get("message")) or _as_dict(item.get("delta"))
        content = message.get("content") if message else item.get("text")
        if isinstance(content, str):
            parts.append(content)
        elif content is not None:
            parts.append(_json_text(content, 1_000_000))
    return "".join(parts)


def _usage(document: dict[str, Any]) -> dict[str, Any]:
    usage = _as_dict(document.get("usage"))
    if usage:
        return usage
    hidden = _as_dict(document.get("_hidden_params"))
    return _as_dict(hidden.get("usage"))


def _duration_ms(start_time: Any, end_time: Any) -> int:
    try:
        return max(0, int((end_time - start_time).total_seconds() * 1000))
    except Exception:
        return 0


class EAPLiteLLMCallback:
    def __init__(self) -> None:
        self.collector_url = os.getenv("EAP_INSTRUMENTATION_URL", "").rstrip("/")
        self.token_file = os.getenv("EAP_INSTRUMENTATION_TOKEN_FILE", "/var/run/eap/instrumentation-token")
        self.provider = os.getenv("EAP_INSTRUMENTATION_PROVIDER", "").strip()
        self.max_chars = max(1024, int(os.getenv("EAP_INSTRUMENTATION_MAX_CHARS", "262144")))
        self.timeout_seconds = max(1.0, float(os.getenv("EAP_INSTRUMENTATION_TIMEOUT_SECONDS", "5")))
        self.token = self._read_token()

    @property
    def ready(self) -> bool:
        return bool(self.collector_url and self.token)

    def _read_token(self) -> str:
        try:
            with open(self.token_file, "r", encoding="utf-8") as handle:
                return handle.read().strip()
        except OSError:
            return ""

    def _trace_id(self, kwargs: dict[str, Any]) -> str:
        metadata = _as_dict(kwargs.get("metadata"))
        for value in (
            kwargs.get("litellm_call_id"),
            kwargs.get("litellm_trace_id"),
            kwargs.get("request_id"),
            metadata.get("trace_id"),
            metadata.get("eap_trace_id"),
        ):
            if value:
                return str(value)[:128]
        generated = uuid.uuid4().hex
        if isinstance(kwargs.get("metadata"), dict):
            kwargs["metadata"]["eap_trace_id"] = generated
        else:
            kwargs["metadata"] = {"eap_trace_id": generated}
        return generated

    def _observation_key(self, trace_id: str, direction: str) -> str:
        digest = hashlib.sha256(f"litellm|{trace_id}|{direction}".encode("utf-8")).hexdigest()
        return f"litellm-{digest}"

    def _submit(self, payload: dict[str, Any]) -> None:
        if not self.ready:
            return
        thread = threading.Thread(target=self._post, args=(payload,), daemon=True, name="eap-litellm-telemetry")
        thread.start()

    def _post(self, payload: dict[str, Any]) -> None:
        try:
            request = urllib.request.Request(
                self.collector_url + "/eap/content-proxy/capture",
                data=json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "X-EAP-Instrumentation-Token": self.token,
                    "User-Agent": "eap-litellm-autoinstrument/1.0",
                },
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=self.timeout_seconds):
                pass
        except Exception as error:
            # Never log payloads, credentials, URLs or raw exception text.
            logging.getLogger(__name__).warning("EAP capture delivery failed (%s)", type(error).__name__)
            return

    async def async_log_pre_api_call(self, model: str, messages: Any, kwargs: dict[str, Any]) -> None:
        self.log_pre_api_call(model, messages, kwargs)

    def log_pre_api_call(self, model: str, messages: Any, kwargs: dict[str, Any]) -> None:
        trace_id = self._trace_id(kwargs)
        self._submit({
            "observationKey": self._observation_key(trace_id, "INPUT"),
            "traceId": trace_id,
            "direction": "INPUT",
            "provider": self.provider,
            "modelName": str(model or kwargs.get("model") or "unknown"),
            "content": _json_text(messages, self.max_chars),
            "dataCategory": "MODEL_INPUT",
            "metadata": {"framework": "litellm", "captureMethod": "AUTO_INSTRUMENTATION"},
            "retainRaw": False,
            "sampled": False,
            "observedAt": datetime.now(timezone.utc).isoformat(),
        })

    async def async_log_success_event(self, kwargs: dict[str, Any], response_obj: Any, start_time: Any, end_time: Any) -> None:
        self.log_success_event(kwargs, response_obj, start_time, end_time)

    def log_success_event(self, kwargs: dict[str, Any], response_obj: Any, start_time: Any, end_time: Any) -> None:
        trace_id = self._trace_id(kwargs)
        document = _as_dict(response_obj)
        hidden = _as_dict(document.get("_hidden_params"))
        metadata: dict[str, Any] = {
            "framework": "litellm",
            "captureMethod": "AUTO_INSTRUMENTATION",
            "outcome": "SUCCEEDED",
            "latencyMs": _duration_ms(start_time, end_time),
            "usage": _usage(document),
        }
        cost = hidden.get("response_cost") or kwargs.get("response_cost")
        if cost is not None:
            metadata["moneyCost"] = cost
        content = _response_content(document) or _json_text(_without_sensitive_fields(document), self.max_chars)
        self._submit({
            "observationKey": self._observation_key(trace_id, "OUTPUT"),
            "traceId": trace_id,
            "direction": "OUTPUT",
            "provider": self.provider,
            "modelName": str(document.get("model") or kwargs.get("model") or "unknown"),
            "content": content[:self.max_chars],
            "dataCategory": "MODEL_OUTPUT",
            "metadata": metadata,
            "retainRaw": False,
            "sampled": False,
            "observedAt": datetime.now(timezone.utc).isoformat(),
        })

    async def async_log_failure_event(self, kwargs: dict[str, Any], response_obj: Any, start_time: Any, end_time: Any) -> None:
        self.log_failure_event(kwargs, response_obj, start_time, end_time)

    def log_failure_event(self, kwargs: dict[str, Any], response_obj: Any, start_time: Any, end_time: Any) -> None:
        trace_id = self._trace_id(kwargs)
        error = kwargs.get("exception") or response_obj or "model invocation failed"
        error_type = type(error).__name__
        self._submit({
            "observationKey": self._observation_key(trace_id, "OUTPUT"),
            "traceId": trace_id,
            "direction": "OUTPUT",
            "provider": self.provider,
            "modelName": str(kwargs.get("model") or "unknown"),
            "content": _json_text({"errorType": error_type}, self.max_chars),
            "dataCategory": "MODEL_OUTPUT",
            "metadata": {
                "framework": "litellm",
                "captureMethod": "AUTO_INSTRUMENTATION",
                "outcome": "FAILED",
                "latencyMs": _duration_ms(start_time, end_time),
                "errorType": error_type,
            },
            "retainRaw": False,
            "sampled": False,
            "observedAt": datetime.now(timezone.utc).isoformat(),
        })


def install(litellm_module: Any) -> bool:
    if not _truthy(os.getenv("EAP_CONTENT_AUTOINSTRUMENT")):
        return False
    try:
        from litellm.integrations.custom_logger import CustomLogger
    except Exception:
        CustomLogger = object

    class EAPLiteLLMCustomLogger(EAPLiteLLMCallback, CustomLogger):
        def __init__(self) -> None:
            if CustomLogger is not object:
                CustomLogger.__init__(self, turn_off_message_logging=False)
            EAPLiteLLMCallback.__init__(self)

    callback = EAPLiteLLMCustomLogger()
    if not callback.ready:
        return False
    callbacks = getattr(litellm_module, "callbacks", None)
    if not isinstance(callbacks, list):
        callbacks = []
        setattr(litellm_module, "callbacks", callbacks)
    if any(item.__class__.__name__ == callback.__class__.__name__ for item in callbacks):
        return True
    callbacks.append(callback)
    return True
