"""Gemini adapter — reviewer_mode=full_reviewer.

Uses `google-generativeai`. The SDK emits a deprecation FutureWarning at import
time; swallow it here so it doesn't pollute smoke output.
"""

from __future__ import annotations

import os
import warnings

from .base import (
    BaseProvider,
    build_system_prompt,
    build_user_prompt,
    env_float,
    env_str,
)


class GeminiProvider(BaseProvider):
    reviewer_mode = "full_reviewer"

    def __init__(self, client=None) -> None:
        self.model = env_str("GEMINI_MODEL", "gemini-2.5-flash")
        self.name = self.model
        self.timeout_seconds = env_float("GEMINI_TIMEOUT_SEC", 60.0)

        if client is not None:
            self._client = client
            return

        with warnings.catch_warnings():
            warnings.simplefilter("ignore", FutureWarning)
            import google.generativeai as genai  # type: ignore

        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY not set")
        genai.configure(api_key=api_key)

        self._client = genai.GenerativeModel(
            model_name=self.model,
            system_instruction=build_system_prompt(self.reviewer_mode),
            generation_config={
                "response_mime_type": "application/json",
                "max_output_tokens": 1500,
            },
        )

    def review(self, packet: dict) -> str:
        self.last_usage = None
        resp = self._client.generate_content(
            build_user_prompt(packet),
            request_options={"timeout": self.timeout_seconds},
        )

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
