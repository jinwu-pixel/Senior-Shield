"""실제 provider adapter smoke test.

이 스크립트는 provider 별로 딱 1회씩 최소 호출을 수행하고,
`09-gates/cross-check-runs/SS-SMOKE-PROBE/<run_id>/` 산출물을 생성한다.

보안 원칙:
  - API 키는 환경변수로만 주입. 코드에 하드코딩 금지.
  - 응답 원문 전체를 stdout 에 찍지 않는다. 상태 요약만 출력.
  - run_meta.json 의 providers 엔트리는 runner 가 이미 최소 필드만 남긴다.

실행:
  export ANTHROPIC_API_KEY=...
  export OPENAI_API_KEY=...
  export GEMINI_API_KEY=...
  # (선택) 모델/타임아웃 override
  export ANTHROPIC_MODEL=claude-opus-4-6
  export OPENAI_MODEL=gpt-4o-mini
  export GEMINI_MODEL=gemini-1.5-pro-latest

  python tools/smoke_providers.py

Exit code:
  0 = 정상 (전체 요약 생성)
  2 = packet 검증 실패
  3 = 활성 provider 0
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import crosscheck_runner as cr


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SMOKE_PACKET = PROJECT_ROOT / "templates" / "packets" / "smoke-probe.yaml"
DOTENV_PATH = PROJECT_ROOT / ".env"

SECRET_KEYS = ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY")
NON_SECRET_KEYS = (
    "ANTHROPIC_MODEL",
    "ANTHROPIC_TIMEOUT_SEC",
    "OPENAI_MODEL",
    "OPENAI_TIMEOUT_SEC",
    "GEMINI_MODEL",
    "GEMINI_TIMEOUT_SEC",
)


def load_dotenv_if_present(path: Path = DOTENV_PATH) -> None:
    """최소 .env 로더. KEY=VALUE 한 줄씩. 빈 줄/주석(#) 허용.

    - 파일이 없으면 조용히 리턴
    - 값은 선행 `export ` 허용, 양쪽 따옴표 제거
    - 이미 환경에 존재하면 덮어쓰지 않음 (셸 export 우선)
    - 시크릿 키 내용은 절대 출력하지 않음
    """
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip()
        if (value.startswith('"') and value.endswith('"')) or (
            value.startswith("'") and value.endswith("'")
        ):
            value = value[1:-1]
        if not key:
            continue
        if key not in SECRET_KEYS and key not in NON_SECRET_KEYS:
            # 알려진 키만 허용. 임의 env 주입 방지.
            continue
        os.environ.setdefault(key, value)


def main() -> int:
    if not SMOKE_PACKET.exists():
        print(f"[smoke] packet not found: {SMOKE_PACKET}", file=sys.stderr)
        return 2

    load_dotenv_if_present()

    present = [k for k in SECRET_KEYS if os.environ.get(k)]
    missing = [k for k in SECRET_KEYS if not os.environ.get(k)]
    for k in present:
        print(f"[smoke] key present: {k}", file=sys.stderr)
    for k in missing:
        print(f"[smoke] key MISSING: {k}", file=sys.stderr)
    if not present:
        print(
            "[smoke] no provider API keys present. "
            f"Set them in env or create {DOTENV_PATH} with "
            "ANTHROPIC_API_KEY=.../OPENAI_API_KEY=.../GEMINI_API_KEY=...",
            file=sys.stderr,
        )
        return 3

    # runner 의 main() 을 그대로 호출 — provider 선택/병렬/스키마/gate log 모두 재사용.
    argv = [
        str(SMOKE_PACKET),
        "--real",
        "--out",
        str(PROJECT_ROOT / "09-gates" / "cross-check-runs"),
    ]
    return cr.main(argv)


if __name__ == "__main__":
    sys.exit(main())
