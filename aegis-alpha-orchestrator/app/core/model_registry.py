"""P4-6: model registry — which model names may be used, on which endpoint, at what price.

Replaces the silent DeepSeek rewrite in ``LLMClient._resolve_model``: a requested model that is
unknown (or does not fit the configured endpoint) now falls back to the configured default with a
warning, and the decision is returned as a :class:`ModelResolution` so callers can put
``requestedModel`` / ``effectiveModel`` / ``modelFallbackReason`` into the trace.

The registry file is YAML (``app/core/models.yaml`` by default, ``AEGIS_ALPHA_MODELS_FILE`` to
override). Prices are optional; :meth:`ModelRegistry.estimate_cost` returns ``None`` unless every
price needed for the call is configured, so no number is ever invented.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

DEFAULT_MODELS_FILE = Path(__file__).with_name("models.yaml")
# Used only when no registry file can be loaded (keeps the pre-registry endpoint behaviour).
_LEGACY_DEEPSEEK_MODELS = ("deepseek-v4-flash", "deepseek-v4-pro")


@dataclass(frozen=True)
class ModelPricing:
    input_per_mtok: float | None = None
    output_per_mtok: float | None = None
    cache_read_per_mtok: float | None = None


@dataclass(frozen=True)
class ModelSpec:
    name: str
    provider: str = "openai-compatible"
    endpoint: str = ""
    supports_tools: bool = True
    context_window: int | None = None
    max_output_tokens: int | None = None
    pricing: ModelPricing = field(default_factory=ModelPricing)


@dataclass(frozen=True)
class ModelResolution:
    requested: str | None
    effective: str
    fallback_reason: str | None = None

    @property
    def fell_back(self) -> bool:
        return self.fallback_reason is not None

    def as_trace(self) -> dict[str, Any]:
        out: dict[str, Any] = {"requestedModel": self.requested, "effectiveModel": self.effective}
        if self.fallback_reason:
            out["modelFallbackReason"] = self.fallback_reason
        return out


def _opt_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _opt_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


class ModelRegistry:
    def __init__(
        self,
        models: dict[str, ModelSpec] | None = None,
        *,
        allow_unlisted: bool = False,
        endpoint_defaults: dict[str, str] | None = None,
        loaded: bool = True,
    ):
        self._models = dict(models or {})
        self.allow_unlisted = bool(allow_unlisted)
        self.endpoint_defaults = {str(k).lower(): str(v) for k, v in (endpoint_defaults or {}).items()}
        self.loaded = loaded

    # ---------------------------------------------------------------- loading
    @classmethod
    def from_mapping(cls, raw: dict[str, Any] | None) -> "ModelRegistry":
        raw = raw if isinstance(raw, dict) else {}
        models: dict[str, ModelSpec] = {}
        for name, spec in (raw.get("models") or {}).items():
            spec = spec if isinstance(spec, dict) else {}
            pricing = spec.get("pricing") if isinstance(spec.get("pricing"), dict) else {}
            models[str(name)] = ModelSpec(
                name=str(name),
                provider=str(spec.get("provider") or "openai-compatible"),
                endpoint=str(spec.get("endpoint") or ""),
                supports_tools=bool(spec.get("supportsTools", True)),
                context_window=_opt_int(spec.get("contextWindow")),
                max_output_tokens=_opt_int(spec.get("maxOutputTokens")),
                pricing=ModelPricing(
                    input_per_mtok=_opt_float(pricing.get("inputPerMTok")),
                    output_per_mtok=_opt_float(pricing.get("outputPerMTok")),
                    cache_read_per_mtok=_opt_float(pricing.get("cacheReadPerMTok")),
                ),
            )
        return cls(
            models,
            allow_unlisted=bool(raw.get("allowUnlisted", False)),
            endpoint_defaults=raw.get("endpointDefaults") if isinstance(raw.get("endpointDefaults"), dict) else {},
        )

    @classmethod
    def load(cls, path: str | Path | None = None) -> "ModelRegistry":
        target = Path(path) if path else DEFAULT_MODELS_FILE
        try:
            import yaml  # PyYAML: transitive dependency of langchain-core, pinned in constraints.txt

            with open(target, encoding="utf-8") as handle:
                return cls.from_mapping(yaml.safe_load(handle))
        except Exception as exc:  # noqa: BLE001
            logger.warning("Model registry not loaded from %s (%s); using legacy endpoint rules", target, type(exc).__name__)
            return cls.legacy()

    @classmethod
    def legacy(cls) -> "ModelRegistry":
        models = {name: ModelSpec(name=name, endpoint="deepseek") for name in _LEGACY_DEEPSEEK_MODELS}
        return cls(models, allow_unlisted=True, endpoint_defaults={"deepseek": "deepseek-v4-flash"}, loaded=False)

    # ---------------------------------------------------------------- queries
    def names(self) -> list[str]:
        return sorted(self._models)

    def get(self, name: str | None) -> ModelSpec | None:
        return self._models.get(name or "")

    def _endpoint_key(self, base_url: str | None) -> str | None:
        lowered = (base_url or "").lower()
        for key in self.endpoint_defaults:
            if key and key in lowered:
                return key
        return None

    def _fits(self, name: str, base_url: str | None) -> bool:
        spec = self.get(name)
        endpoint_key = self._endpoint_key(base_url)
        if spec is None:
            # Unlisted names only pass when allowed and the endpoint has no registered family.
            return self.allow_unlisted and endpoint_key is None
        # Only the known endpoint families are enforced (e.g. a DeepSeek URL only runs DeepSeek models);
        # a custom / proxy URL that matches no family accepts every listed model.
        return endpoint_key is None or spec.endpoint.lower() == endpoint_key

    def resolve(self, requested: str | None, *, default: str, base_url: str | None) -> ModelResolution:
        """Pick the model for one call.

        * empty request → the configured default (no fallback reason);
        * listed (or allowed unlisted) model that fits the endpoint → used as requested;
        * otherwise → the default, with ``fallback_reason`` ``unknown_model`` / ``endpoint_mismatch``.

        The configured default is itself checked against the endpoint; an endpoint family default
        (``endpointDefaults``) replaces it when it cannot run there.
        """
        requested_name = (requested or "").strip() or None
        safe_default, default_reason = self._safe_default(default, base_url)
        if requested_name is None:
            return ModelResolution(None, safe_default, default_reason)
        if requested_name == safe_default:
            return ModelResolution(requested_name, safe_default, None)
        if self._fits(requested_name, base_url):
            return ModelResolution(requested_name, requested_name, None)
        if self.get(requested_name) is None and not self.allow_unlisted:
            reason = "unknown_model"
        else:
            reason = "endpoint_mismatch"
        logger.warning(
            "Model %r not usable (%s); falling back to %r", requested_name, reason, safe_default
        )
        return ModelResolution(requested_name, safe_default, reason)

    def _safe_default(self, default: str, base_url: str | None) -> tuple[str, str | None]:
        default = (default or "").strip()
        endpoint_key = self._endpoint_key(base_url)
        if endpoint_key is None:
            return default, None
        spec = self.get(default)
        if spec is not None and spec.endpoint.lower() == endpoint_key:
            return default, None
        family_default = self.endpoint_defaults.get(endpoint_key) or default
        if family_default != default:
            logger.warning(
                "Configured model %r does not fit the %s endpoint; using %r", default, endpoint_key, family_default
            )
            return family_default, "default_endpoint_mismatch"
        return default, None

    def estimate_cost(self, model: str | None, usage: dict[str, Any] | None) -> float | None:
        """USD cost of one call from normalized usage, or ``None`` when any needed price is unset."""
        spec = self.get(model)
        if spec is None or not isinstance(usage, dict):
            return None
        price = spec.pricing
        input_tokens = int(usage.get("inputTokens") or 0)
        output_tokens = int(usage.get("outputTokens") or 0)
        cache_read = int(usage.get("cacheReadTokens") or 0)
        if price.input_per_mtok is None or price.output_per_mtok is None:
            return None
        if cache_read and price.cache_read_per_mtok is None:
            return None
        uncached_input = max(0, input_tokens - cache_read)
        cost = (
            uncached_input * price.input_per_mtok
            + output_tokens * price.output_per_mtok
            + cache_read * (price.cache_read_per_mtok or 0.0)
        ) / 1_000_000
        return round(cost, 8)


_REGISTRY_CACHE: dict[str, ModelRegistry] = {}


def get_model_registry(path: str | None = None) -> ModelRegistry:
    key = str(path or DEFAULT_MODELS_FILE)
    registry = _REGISTRY_CACHE.get(key)
    if registry is None:
        registry = ModelRegistry.load(path or None)
        _REGISTRY_CACHE[key] = registry
    return registry


def reset_model_registry_cache() -> None:
    _REGISTRY_CACHE.clear()
