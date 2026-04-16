"""CROSS_CHECK provider 비용 추정.

주의:
  - 가격은 벤더 공시가를 기준으로 한 **근사치**다. 실제 과금과 다를 수 있다
    (프로모션, 커밋 가격, 캐시 할인, 리전 차등 등 반영 안 함).
  - 정확한 과금 추적이 필요하면 벤더 invoice 를 소스로 써라.
  - 이 모듈의 목적은 "이번 run 이 대략 얼마 들었나" 수준의 overhead 지표다.

단위:
  - 모든 가격은 **USD / 1,000,000 tokens** (input, output).
  - estimate_cost_usd() 반환값도 USD (소수점 6자리 반올림).

업데이트 기준일: 2026-04-16
"""

from __future__ import annotations

PRICING_VERSION = "1"
PRICING_LAST_UPDATED = "2026-04-16"

# (input_rate, output_rate) per 1M tokens in USD
_PRICE_TABLE: dict[str, tuple[float, float]] = {
    # Anthropic
    "claude-opus-4-6": (15.00, 75.00),
    "claude-opus-4": (15.00, 75.00),
    "claude-sonnet-4-6": (3.00, 15.00),
    "claude-sonnet-4-5": (3.00, 15.00),
    "claude-haiku-4-5": (0.80, 4.00),
    # OpenAI
    "gpt-4o": (2.50, 10.00),
    "gpt-4o-mini": (0.15, 0.60),
    "gpt-4.1": (2.00, 8.00),
    "gpt-4.1-mini": (0.40, 1.60),
    # Google
    "gemini-2.5-pro": (1.25, 5.00),
    "gemini-2.5-flash": (0.075, 0.30),
    "gemini-1.5-pro": (1.25, 5.00),
    "gemini-1.5-flash": (0.075, 0.30),
}


def estimate_cost_usd(
    model: str,
    input_tokens: int,
    output_tokens: int,
) -> float | None:
    """모델 id 기반 비용 추정. 테이블에 없는 모델이면 None.

    name 축 fallback: 일부 벤더는 버전 suffix (-latest, -preview, -20260416) 를
    붙인다. prefix 매칭으로 한 번 더 시도한다.
    """
    rates = _PRICE_TABLE.get(model)
    if rates is None:
        # prefix 매칭: 가장 긴 매칭 우선
        best: tuple[float, float] | None = None
        best_len = 0
        for key, value in _PRICE_TABLE.items():
            if model.startswith(key) and len(key) > best_len:
                best = value
                best_len = len(key)
        rates = best
    if rates is None:
        return None
    in_rate, out_rate = rates
    cost = (input_tokens * in_rate + output_tokens * out_rate) / 1_000_000
    return round(cost, 6)
