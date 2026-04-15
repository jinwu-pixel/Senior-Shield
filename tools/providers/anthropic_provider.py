"""Claude (Anthropic) adapter — reviewer_mode=advisory_only."""

from __future__ import annotations

from .base import (
    BaseProvider,
    SYSTEM_PROMPT,
    build_user_prompt,
    env_float,
    env_str,
    strip_json_fence,
)


class AnthropicProvider(BaseProvider):
    reviewer_mode = "advisory_only"

    def __init__(self, client=None) -> None:
        self.model = env_str("ANTHROPIC_MODEL", "claude-opus-4-6")
        self.name = self.model
        self.timeout_seconds = env_float("ANTHROPIC_TIMEOUT_SEC", 60.0)

        if client is not None:
            self._client = client
            return

        # Defer import so the module loads even if SDK is absent.
        import anthropic  # type: ignore

        self._client = anthropic.Anthropic(timeout=self.timeout_seconds)

    def review(self, packet: dict) -> str:
        msg = self._client.messages.create(
            model=self.model,
            max_tokens=1500,
            system=SYSTEM_PROMPT,
            messages=[{"role": "user", "content": build_user_prompt(packet)}],
        )
        # response.content is a list of ContentBlock; pick text blocks only.
        parts: list[str] = []
        for block in getattr(msg, "content", []) or []:
            text = getattr(block, "text", None)
            if text:
                parts.append(text)
        raw = "\n".join(parts).strip()
        return strip_json_fence(raw)
