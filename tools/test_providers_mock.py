"""Provider adapter mock 기반 테스트.

실제 네트워크 호출은 절대 발생하지 않는다. 각 adapter 에 fake client 를 주입해서
다음을 검증한다:
  - happy path: valid JSON string 을 돌려주는지
  - malformed (fence+prose): Anthropic 은 fence 제거, 나머지는 parse_error 경로
  - timeout/exception: review() 가 예외를 그대로 raise 하는지 (runner 가 감싸서 failed 처리)
  - runner 가 stub 기반에서 기존 summary/degrade 규칙을 유지하는지 회귀 확인

Runner 는 `parse_and_validate_review()` 로 파싱/스키마 검증을 중앙화하므로,
adapter 는 JSON 문자열을 그대로 흘려주기만 하면 된다.
"""

from __future__ import annotations

import json

import pytest

# conftest.py 가 sys.path 에 tools/ 를 넣어준다.
from providers.anthropic_provider import AnthropicProvider
from providers.gemini_provider import GeminiProvider
from providers.openai_provider import OpenAIProvider

import crosscheck_runner as cr


# ---------------------------------------------------------------------------
# Shared fixtures
# ---------------------------------------------------------------------------


VALID_REVIEW = {
    "reviewer": "mock",
    "reviewer_mode": "full_reviewer",
    "review_status": "ok",
    "verdict": "adopt",
    "confidence": 0.8,
    "agreement_points": ["ok"],
    "objections": [],
    "edge_cases": [],
    "recommended_option": "current",
    "requires_human_decision": False,
    "blocking_questions": [],
}


def _valid_json() -> str:
    return json.dumps(VALID_REVIEW, ensure_ascii=False)


def _fenced_json() -> str:
    return "```json\n" + _valid_json() + "\n```"


@pytest.fixture
def tiny_packet():
    return {
        "decision_key": "MOCK-PACKET",
        "packet_version": 1,
        "domain": "senior_shield",
        "change_type": "state_machine_rule",
        "decision_required": "mock",
        "current_proposal": "mock",
        "invariants": ["mock"],
        "severity_guide": {"critical": "", "high": "", "medium": "", "low": ""},
        "review_questions": ["mock?"],
        "required_output": {"format": "json", "fields": []},
    }


# ---------------------------------------------------------------------------
# Anthropic adapter
# ---------------------------------------------------------------------------


class _FakeAnthropicContent:
    def __init__(self, text: str) -> None:
        self.type = "text"
        self.text = text


class _FakeAnthropicUsage:
    def __init__(self, input_tokens: int, output_tokens: int) -> None:
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens


class _FakeAnthropicMessage:
    def __init__(self, text: str, usage: _FakeAnthropicUsage | None = None) -> None:
        self.content = [_FakeAnthropicContent(text)]
        self.usage = usage


class _FakeAnthropicClient:
    def __init__(
        self,
        text: str,
        raise_exc: Exception | None = None,
        usage: _FakeAnthropicUsage | None = None,
    ) -> None:
        self._text = text
        self._raise = raise_exc
        self._usage = usage
        self.messages = self  # so that client.messages.create(...) works

    def create(self, **kwargs):
        if self._raise:
            raise self._raise
        return _FakeAnthropicMessage(self._text, usage=self._usage)


def test_anthropic_happy(tiny_packet):
    p = AnthropicProvider(client=_FakeAnthropicClient(_valid_json()))
    out = p.review(tiny_packet)
    assert isinstance(out, str)
    parsed = json.loads(out)
    assert parsed["verdict"] == "adopt"
    assert p.reviewer_mode == "advisory_only"


def test_anthropic_strips_fence(tiny_packet):
    p = AnthropicProvider(client=_FakeAnthropicClient(_fenced_json()))
    out = p.review(tiny_packet)
    # Must parse as JSON — fence must have been removed.
    parsed = json.loads(out)
    assert parsed["verdict"] == "adopt"


def test_anthropic_timeout_raises(tiny_packet):
    p = AnthropicProvider(client=_FakeAnthropicClient("", raise_exc=TimeoutError("slow")))
    with pytest.raises(TimeoutError):
        p.review(tiny_packet)


# ---------------------------------------------------------------------------
# OpenAI adapter
# ---------------------------------------------------------------------------


class _FakeOpenAIMessage:
    def __init__(self, content: str) -> None:
        self.content = content


class _FakeOpenAIChoice:
    def __init__(self, content: str) -> None:
        self.message = _FakeOpenAIMessage(content)


class _FakeOpenAIUsage:
    def __init__(self, prompt_tokens: int, completion_tokens: int) -> None:
        self.prompt_tokens = prompt_tokens
        self.completion_tokens = completion_tokens
        self.total_tokens = prompt_tokens + completion_tokens


class _FakeOpenAIResponse:
    def __init__(self, content: str, usage: _FakeOpenAIUsage | None = None) -> None:
        self.choices = [_FakeOpenAIChoice(content)]
        self.usage = usage


class _FakeOpenAICompletions:
    def __init__(
        self,
        content: str,
        raise_exc: Exception | None = None,
        usage: _FakeOpenAIUsage | None = None,
    ) -> None:
        self._content = content
        self._raise = raise_exc
        self._usage = usage

    def create(self, **kwargs):
        if self._raise:
            raise self._raise
        return _FakeOpenAIResponse(self._content, usage=self._usage)


class _FakeOpenAIClient:
    def __init__(
        self,
        content: str,
        raise_exc: Exception | None = None,
        usage: _FakeOpenAIUsage | None = None,
    ) -> None:
        self.chat = self
        self.completions = _FakeOpenAICompletions(content, raise_exc, usage)


def test_openai_happy(tiny_packet):
    p = OpenAIProvider(client=_FakeOpenAIClient(_valid_json()))
    out = p.review(tiny_packet)
    parsed = json.loads(out)
    assert parsed["verdict"] == "adopt"
    assert p.reviewer_mode == "full_reviewer"


def test_openai_malformed_returns_raw(tiny_packet):
    """malformed 는 adapter 가 raw 를 그대로 내보내고 runner 가 parse_error 로 처리해야 한다."""
    p = OpenAIProvider(client=_FakeOpenAIClient("this is not json"))
    out = p.review(tiny_packet)
    assert out == "this is not json"

    parsed, reason, errs = cr.parse_and_validate_review(out)
    assert parsed is None
    assert reason == "parse_error"
    assert errs  # non-empty


def test_openai_timeout_raises(tiny_packet):
    p = OpenAIProvider(client=_FakeOpenAIClient("", raise_exc=TimeoutError("slow")))
    with pytest.raises(TimeoutError):
        p.review(tiny_packet)


# ---------------------------------------------------------------------------
# Gemini adapter
# ---------------------------------------------------------------------------


class _FakeGeminiUsageMetadata:
    def __init__(self, prompt_token_count: int, candidates_token_count: int) -> None:
        self.prompt_token_count = prompt_token_count
        self.candidates_token_count = candidates_token_count
        self.total_token_count = prompt_token_count + candidates_token_count


class _FakeGeminiResponse:
    def __init__(
        self,
        text: str,
        usage_metadata: _FakeGeminiUsageMetadata | None = None,
    ) -> None:
        self.text = text
        self.usage_metadata = usage_metadata


class _FakeGeminiClient:
    def __init__(
        self,
        text: str,
        raise_exc: Exception | None = None,
        usage_metadata: _FakeGeminiUsageMetadata | None = None,
    ) -> None:
        self._text = text
        self._raise = raise_exc
        self._usage_metadata = usage_metadata

    def generate_content(self, *, model=None, contents=None, **kwargs):
        if self._raise:
            raise self._raise
        return _FakeGeminiResponse(self._text, usage_metadata=self._usage_metadata)

    @property
    def models(self):
        return self


def test_gemini_happy(monkeypatch, tiny_packet):
    # GEMINI_API_KEY 없이 생성자 에러 나는 경로를 피하기 위해 client 주입.
    p = GeminiProvider(client=_FakeGeminiClient(_valid_json()))
    out = p.review(tiny_packet)
    parsed = json.loads(out)
    assert parsed["verdict"] == "adopt"
    assert p.reviewer_mode == "full_reviewer"


def test_gemini_schema_mismatch_via_runner(tiny_packet):
    """Adapter 가 JSON 을 반환해도 스키마 불일치면 runner 가 schema_mismatch 로 분류해야 한다."""
    bad = json.dumps({"reviewer": "gemini", "verdict": "maybe", "confidence": 9.9})
    p = GeminiProvider(client=_FakeGeminiClient(bad))
    out = p.review(tiny_packet)
    parsed, reason, errs = cr.parse_and_validate_review(out)
    assert reason == "schema_mismatch"
    assert any("verdict" in e or "maybe" in e for e in errs)


def test_gemini_exception_raises(tiny_packet):
    p = GeminiProvider(client=_FakeGeminiClient("", raise_exc=RuntimeError("boom")))
    with pytest.raises(RuntimeError):
        p.review(tiny_packet)


def test_gemini_httpx_timeout_converted(tiny_packet):
    """httpx.TimeoutException → standard TimeoutError 변환 확인."""
    import httpx

    p = GeminiProvider(
        client=_FakeGeminiClient("", raise_exc=httpx.ReadTimeout("read timed out")),
    )
    with pytest.raises(TimeoutError, match="read timed out"):
        p.review(tiny_packet)


# ---------------------------------------------------------------------------
# End-to-end: pipeline with mixed fake providers — verifies runner keeps its
# summary/degrade rules intact when real-style adapters are wired in.
# ---------------------------------------------------------------------------


class _FakeProvider(cr.BaseProvider):
    def __init__(self, name: str, reviewer_mode: str, raw) -> None:
        self.name = name
        self.model = name
        self.reviewer_mode = reviewer_mode
        self.timeout_seconds = 5.0
        self._raw = raw

    def review(self, packet: dict):
        if isinstance(self._raw, Exception):
            raise self._raw
        return self._raw


def _make_review(verdict: str, objections=None) -> str:
    body = {
        "reviewer": "fake",
        "reviewer_mode": "full_reviewer",
        "review_status": "ok",
        "verdict": verdict,
        "confidence": 0.7,
        "agreement_points": [],
        "objections": objections or [],
        "edge_cases": [],
        "recommended_option": "current",
        "requires_human_decision": False,
        "blocking_questions": [],
    }
    return json.dumps(body, ensure_ascii=False)


def test_pipeline_full2_no_failure_is_not_degraded(tiny_packet):
    providers = [
        _FakeProvider("claude", "advisory_only", _make_review("revise")),
        _FakeProvider("gpt", "full_reviewer", _make_review("adopt")),
        _FakeProvider("gemini", "full_reviewer", _make_review("adopt")),
    ]
    results = cr.run_reviewers(providers, tiny_packet)
    summary = cr.summarize_reviews(tiny_packet, results, run_id="t")
    assert summary["degraded"] is False
    assert summary["successful_full_reviewer_count"] == 2
    assert summary["agreement_state"] in ("STRONG_CONSENSUS", "WEAK_CONSENSUS")
    assert summary["suggested_action"] == "adopt"


def test_pipeline_full1_plus_advisory1_forces_degraded(tiny_packet):
    providers = [
        _FakeProvider("claude", "advisory_only", _make_review("adopt")),
        _FakeProvider("gpt", "full_reviewer", _make_review("adopt")),
        _FakeProvider("gemini", "full_reviewer", RuntimeError("timeout")),
    ]
    results = cr.run_reviewers(providers, tiny_packet)
    summary = cr.summarize_reviews(tiny_packet, results, run_id="t")
    assert summary["degraded"] is True
    assert summary["user_decision_required"] is True
    assert summary["successful_full_reviewer_count"] == 1
    assert summary["successful_advisory_count"] == 1
    assert summary["failed_reviewer_count"] == 1


def test_pipeline_schema_mismatch_becomes_partial_not_objection(tiny_packet):
    bad = json.dumps({"reviewer": "gpt", "verdict": "maybe", "confidence": 5})
    providers = [
        _FakeProvider("claude", "advisory_only", _make_review("adopt")),
        _FakeProvider("gpt", "full_reviewer", bad),
        _FakeProvider("gemini", "full_reviewer", _make_review("adopt")),
    ]
    results = cr.run_reviewers(providers, tiny_packet)
    statuses = {r.provider: r.status for r in results}
    assert statuses["gpt"] == "partial"
    # summary: partial 은 blocking_issues 로 승격되지 않아야 한다
    summary = cr.summarize_reviews(tiny_packet, results, run_id="t")
    assert summary["degraded"] is True
    for b in summary["blocking_issues"]:
        assert b.get("source") != "gpt"


def test_pipeline_all_full_failed_is_insufficient(tiny_packet):
    providers = [
        _FakeProvider("claude", "advisory_only", _make_review("adopt")),
        _FakeProvider("gpt", "full_reviewer", RuntimeError("x")),
        _FakeProvider("gemini", "full_reviewer", RuntimeError("x")),
    ]
    results = cr.run_reviewers(providers, tiny_packet)
    summary = cr.summarize_reviews(tiny_packet, results, run_id="t")
    assert summary["agreement_state"] == "INSUFFICIENT"
    assert summary["suggested_action"] == "needs_human_decision"
    assert summary["user_decision_required"] is True


# ---------------------------------------------------------------------------
# Usage / pricing (A-4)
# ---------------------------------------------------------------------------


from providers.pricing import estimate_cost_usd  # noqa: E402


def test_pricing_known_model():
    cost = estimate_cost_usd("gpt-4o-mini", 1000, 500)
    assert cost is not None
    assert isinstance(cost, float)
    assert cost > 0
    # 1000 * 0.15/1M + 500 * 0.60/1M = 0.00015 + 0.0003 = 0.00045
    assert abs(cost - 0.00045) < 1e-7


def test_pricing_unknown_model():
    assert estimate_cost_usd("no-such-model", 1000, 500) is None


def test_pricing_prefix_match():
    cost = estimate_cost_usd("gemini-2.5-flash-preview-04-17", 1000, 500)
    assert cost is not None


def test_anthropic_usage_extraction(tiny_packet):
    usage = _FakeAnthropicUsage(input_tokens=500, output_tokens=200)
    p = AnthropicProvider(client=_FakeAnthropicClient(_valid_json(), usage=usage))
    p.review(tiny_packet)
    assert p.last_usage is not None
    assert p.last_usage["input_tokens"] == 500
    assert p.last_usage["output_tokens"] == 200
    assert p.last_usage["total_tokens"] == 700


def test_openai_usage_extraction(tiny_packet):
    usage = _FakeOpenAIUsage(prompt_tokens=300, completion_tokens=100)
    p = OpenAIProvider(client=_FakeOpenAIClient(_valid_json(), usage=usage))
    p.review(tiny_packet)
    assert p.last_usage is not None
    assert p.last_usage["input_tokens"] == 300
    assert p.last_usage["output_tokens"] == 100
    assert p.last_usage["total_tokens"] == 400


def test_gemini_usage_extraction(tiny_packet):
    usage = _FakeGeminiUsageMetadata(prompt_token_count=800, candidates_token_count=400)
    p = GeminiProvider(client=_FakeGeminiClient(_valid_json(), usage_metadata=usage))
    p.review(tiny_packet)
    assert p.last_usage is not None
    assert p.last_usage["input_tokens"] == 800
    assert p.last_usage["output_tokens"] == 400
    assert p.last_usage["total_tokens"] == 1200


def test_no_usage_when_absent(tiny_packet):
    p = AnthropicProvider(client=_FakeAnthropicClient(_valid_json()))
    p.review(tiny_packet)
    assert p.last_usage is None


def test_pipeline_usage_in_review_result(tiny_packet):
    """Provider 가 last_usage 를 채우면 runner 가 ReviewResult 에 usage/cost 를 기록해야 한다."""

    class _UsageProvider(cr.BaseProvider):
        def __init__(self, name: str, reviewer_mode: str, raw: str) -> None:
            self.name = name
            self.model = "gpt-4o-mini"
            self.reviewer_mode = reviewer_mode
            self.timeout_seconds = 5.0
            self._raw = raw

        def review(self, packet: dict):
            self.last_usage = {
                "input_tokens": 1000,
                "output_tokens": 500,
                "total_tokens": 1500,
            }
            return self._raw

    providers = [
        _UsageProvider("gpt", "full_reviewer", _make_review("adopt")),
        _UsageProvider("gemini", "full_reviewer", _make_review("adopt")),
    ]
    results = cr.run_reviewers(providers, tiny_packet)
    for r in results:
        assert r.usage is not None
        assert r.usage["total_tokens"] == 1500
        assert r.cost_estimate is not None
        assert r.cost_estimate > 0
