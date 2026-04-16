"""CROSS_CHECK provider registry.

`load_providers_from_env()` reads environment variables and returns the list of
providers whose API keys are present and whose SDK imports succeed. Missing
keys/SDKs are **graceful skip**: the function logs the reason via `logger` and
continues.

Currently wired providers:
  - ANTHROPIC_API_KEY → AnthropicProvider (advisory_only)
  - OPENAI_API_KEY    → OpenAIProvider    (full_reviewer)
  - GEMINI_API_KEY    → GeminiProvider    (full_reviewer)
"""

from __future__ import annotations

import os
from typing import Callable

from .base import (
    BaseProvider,
    build_system_prompt,
    build_user_prompt,
    strip_json_fence,
    env_float,
    env_str,
)


_PROVIDER_SPECS = (
    ("ANTHROPIC_API_KEY", "Anthropic"),
    ("OPENAI_API_KEY", "OpenAI"),
    ("GEMINI_API_KEY", "Gemini"),
)


def load_providers_from_env(
    logger: Callable[[str], None] | None = None,
) -> list[BaseProvider]:
    providers: list[BaseProvider] = []
    log = logger or (lambda _msg: None)

    # Anthropic
    if os.environ.get("ANTHROPIC_API_KEY"):
        try:
            from .anthropic_provider import AnthropicProvider

            providers.append(AnthropicProvider())
        except ImportError as exc:
            log(f"[providers] skip Anthropic: SDK import failed — {exc}")
        except Exception as exc:  # pragma: no cover
            log(f"[providers] skip Anthropic: init failed — {type(exc).__name__}")
    else:
        log("[providers] skip Anthropic: ANTHROPIC_API_KEY not set")

    # OpenAI
    if os.environ.get("OPENAI_API_KEY"):
        try:
            from .openai_provider import OpenAIProvider

            providers.append(OpenAIProvider())
        except ImportError as exc:
            log(f"[providers] skip OpenAI: SDK import failed — {exc}")
        except Exception as exc:  # pragma: no cover
            log(f"[providers] skip OpenAI: init failed — {type(exc).__name__}")
    else:
        log("[providers] skip OpenAI: OPENAI_API_KEY not set")

    # Gemini
    if os.environ.get("GEMINI_API_KEY"):
        try:
            from .gemini_provider import GeminiProvider

            providers.append(GeminiProvider())
        except ImportError as exc:
            log(f"[providers] skip Gemini: SDK import failed — {exc}")
        except Exception as exc:  # pragma: no cover
            log(f"[providers] skip Gemini: init failed — {type(exc).__name__}")
    else:
        log("[providers] skip Gemini: GEMINI_API_KEY not set")

    return providers


__all__ = [
    "BaseProvider",
    "build_system_prompt",
    "build_user_prompt",
    "strip_json_fence",
    "env_float",
    "env_str",
    "load_providers_from_env",
]
