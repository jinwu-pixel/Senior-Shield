"""GPT (OpenAI) adapter — reviewer_mode=full_reviewer."""

from __future__ import annotations

from .base import (
    BaseProvider,
    build_system_prompt,
    build_user_prompt,
    env_float,
    env_str,
)


class OpenAIProvider(BaseProvider):
    reviewer_mode = "full_reviewer"

    def __init__(self, client=None) -> None:
        self.model = env_str("OPENAI_MODEL", "gpt-4o-mini")
        self.name = self.model
        self.timeout_seconds = env_float("OPENAI_TIMEOUT_SEC", 60.0)

        if client is not None:
            self._client = client
            return

        import openai  # type: ignore

        self._client = openai.OpenAI(timeout=self.timeout_seconds)

    def review(self, packet: dict) -> str:
        self.last_usage = None
        resp = self._client.chat.completions.create(
            model=self.model,
            max_tokens=1500,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": build_system_prompt(self.reviewer_mode)},
                {"role": "user", "content": build_user_prompt(packet)},
            ],
        )

        usage_obj = getattr(resp, "usage", None)
        if usage_obj is not None:
            input_tokens = int(getattr(usage_obj, "prompt_tokens", 0) or 0)
            output_tokens = int(getattr(usage_obj, "completion_tokens", 0) or 0)
            total_tokens = int(
                getattr(usage_obj, "total_tokens", input_tokens + output_tokens)
                or (input_tokens + output_tokens)
            )
            self.last_usage = {
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "total_tokens": total_tokens,
            }

        return resp.choices[0].message.content or ""
