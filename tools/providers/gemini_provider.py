"""Gemini adapter — reviewer_mode=full_reviewer.

Uses `google-genai` (unified SDK).
"""

from __future__ import annotations

import os
from typing import Any

from .base import (
    BaseProvider,
    build_system_prompt,
    build_user_prompt,
    env_float,
    env_str,
)


class GeminiProvider(BaseProvider):
    reviewer_mode = "full_reviewer"

    def __init__(self, client: Any = None) -> None:
        self.model = env_str("GEMINI_MODEL", "gemini-2.5-flash")
        self.name = self.model
        self.timeout_seconds = env_float("GEMINI_TIMEOUT_SEC", 60.0)

        if client is not None:
            self._client = client
            return

        from google import genai  # type: ignore
        from google.genai import types  # type: ignore

        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY not set")

        self._client = genai.Client(api_key=api_key)
        self._types = types

    def _build_config(self):
        types = self._types
        return types.GenerateContentConfig(
            system_instruction=build_system_prompt(self.reviewer_mode),
            response_mime_type="application/json",
            max_output_tokens=1500,
            http_options=types.HttpOptions(
                timeout=int(self.timeout_seconds * 1000),
            ),
        )

    def review(self, packet: dict) -> str:
        self.last_usage = None

        try:
            if hasattr(self, "_types"):
                resp = self._client.models.generate_content(
                    model=self.model,
                    contents=build_user_prompt(packet),
                    config=self._build_config(),
                )
            else:
                resp = self._client.generate_content(
                    model=self.model,
                    contents=build_user_prompt(packet),
                )
        except Exception as exc:
            import httpx

            if isinstance(exc, httpx.TimeoutException):
                raise TimeoutError(str(exc)) from exc
            raise

        usage_obj = getattr(resp, "usage_metadata", None)
        if usage_obj is not None:
            input_tokens = int(getattr(usage_obj, "prompt_token_count", 0) or 0)
            output_tokens = int(getattr(usage_obj, "candidates_token_count", 0) or 0)
            total_tokens = int(
                getattr(usage_obj, "total_token_count", input_tokens + output_tokens)
                or (input_tokens + output_tokens)
            )
            self.last_usage = {
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "total_tokens": total_tokens,
            }

        return getattr(resp, "text", "") or ""
