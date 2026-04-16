"""CROSS_CHECK provider base + shared helpers.

Runner 는 BaseProvider 인터페이스만 의존한다. 각 adapter 는 `review(packet)` 에서
provider SDK 를 호출해 **JSON 문자열** (권장) 또는 dict 를 반환한다.
파싱/스키마 검증은 runner 의 `parse_and_validate_review()` 가 중앙에서 담당한다.

원칙:
  - JSON 전용 응답 모드가 있으면 그것을 사용 (OpenAI, Gemini).
  - 없으면 system prompt 로 JSON 강제 + markdown fence 제거 (Anthropic).
  - 예외는 그대로 raise. runner 가 failure_reason='exception' 로 감싼다.
  - 키/응답 원문을 로그에 남기지 말 것.
  - 가중치 규칙은 adapter 가 아니라 runner summarize_reviews() 에서 유지된다.
"""

from __future__ import annotations

import json
import os
import re
from typing import Any


class BaseProvider:
    """모든 CROSS_CHECK provider adapter 의 공통 인터페이스.

    `last_usage` 는 review() 가 성공 직후 채우는 선택적 필드.
    스키마: {"input_tokens": int, "output_tokens": int, "total_tokens": int}
    provider SDK 응답에서 토큰 메타가 없으면 None 으로 둔다. runner 가
    getattr(p, "last_usage", None) 로 읽는다.

    주의: total_tokens 는 provider SDK 반환값을 그대로 사용한다.
    일부 provider (예: Gemini) 는 thinking token, candidate expansion 등을
    포함해 input_tokens + output_tokens 보다 클 수 있다. 이는 정상이다.
    """

    name: str = "base"
    reviewer_mode: str = "full_reviewer"  # advisory_only | full_reviewer
    model: str = "unknown"
    timeout_seconds: float = 60.0
    last_usage: dict | None = None

    def review(self, packet: dict) -> Any:
        raise NotImplementedError


_VALID_REVIEWER_MODES = ("advisory_only", "full_reviewer")


def build_system_prompt(reviewer_mode: str) -> str:
    """Provider 별 reviewer_mode 를 주입한 system prompt 를 생성한다.

    이전에는 상수 prompt 안에 "If you are a Claude model, return advisory_only"
    같은 자기 식별 로직이 있었다. Gemini 가 이 문구를 읽고 자기를 Claude 로
    오인해 `reviewer="Claude"` 로 응답하는 bleed 가 관측됐다 (2026-04-15).
    이제는 각 adapter 가 자기 reviewer_mode 를 주입하므로 provider 가
    identity 를 추측할 여지가 없다.
    """
    if reviewer_mode not in _VALID_REVIEWER_MODES:
        raise ValueError(
            f"reviewer_mode must be one of {_VALID_REVIEWER_MODES}, got {reviewer_mode!r}"
        )
    return (
        "You are a reviewer for a CROSS_CHECK gate. "
        "Respond ONLY with a single JSON object matching the schema described in "
        "`required_output.fields`. No prose, no markdown fences, no commentary. "
        "All field names must match the schema exactly. "
        "Use only severities defined in `severity_guide`. "
        f'Always return `reviewer_mode="{reviewer_mode}"`. '
        'Always return `review_status="ok"`. '
        "Field types (strict): "
        "`confidence` is a number between 0.0 and 1.0 (NOT a string like \"high\"); "
        '`verdict` is one of ["adopt","revise","reject"]; '
        "`requires_human_decision` is boolean; "
        "`agreement_points`, `objections`, `edge_cases`, `blocking_questions` are arrays (use [] if empty); "
        "`objections` items are objects with fields severity/point/why/remediation; "
        '`recommended_option` is a string id from alternatives or "current". '
        "Keep the entire response under 1500 tokens."
    )


_PACKET_FIELDS_FOR_PROMPT = (
    "decision_key",
    "packet_version",
    "domain",
    "change_type",
    "decision_required",
    "context",
    "current_proposal",
    "alternatives",
    "invariants",
    "non_goals",
    "risks_if_wrong",
    "evaluation_criteria",
    "severity_guide",
    "review_questions",
    "required_output",
)


def build_user_prompt(packet: dict) -> str:
    """Packet 의 결정-관련 필드만 뽑아 JSON 으로 전달."""
    payload = {k: packet.get(k) for k in _PACKET_FIELDS_FOR_PROMPT if k in packet}
    return (
        "Review the following decision packet and return a single JSON object "
        "matching required_output.fields.\n\n"
        + json.dumps(payload, ensure_ascii=False, indent=2)
    )


_FENCE_RE = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.DOTALL)


def strip_json_fence(text: str) -> str:
    """JSON 전용 모드 없는 provider 용. 불필요한 markdown fence 제거."""
    if not isinstance(text, str):
        return text
    stripped = text.strip()
    match = _FENCE_RE.match(stripped)
    if match:
        return match.group(1).strip()
    return stripped


def env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def env_str(name: str, default: str) -> str:
    return os.environ.get(name, default)
