"""CROSS_CHECK runner.

판정자가 아니라 요약자로 동작한다.
- Judge 금지: final_verdict를 만들지 않는다.
- 대신 summarize_reviews()가 suggested_action / agreement_state / consensus /
  divergence / blocking_issues / user_decision_required 만 생성한다.
- provider 실패/부분응답은 objection severity로 승격하지 않는다. 항상 status 축으로 관리.
- Claude는 advisory_only, GPT/Gemini는 full_reviewer. 집계 축은 full_reviewer.
- 병렬 실행 + run metadata (started_at / elapsed_ms / provider별 응답시간) 기록.

Degrade 카운트 기준 (CLAUDE.md GATE: CROSS_CHECK §E 와 동일):
  - 성공 리뷰어 총합 < 2           → INSUFFICIENT / needs_human_decision
  - 성공 full_reviewer == 0        → INSUFFICIENT / needs_human_decision
  - 성공 full_reviewer == 1 + advisory ≥ 1 → 요약 O, degraded=true, user_decision_required=true
  - 성공 full_reviewer ≥ 2 + 실패/partial 0 → 정상 요약, degraded=false
  - 성공 full_reviewer ≥ 2 + 실패/partial 존재 → 요약 O, degraded=true, user_decision_required=true
  - "성공 리뷰어 총합" 에는 advisory_only 도 카운트에 포함하되, 가중치 주축은 full_reviewer.

산출물 (run 디렉토리):
  reviews/<reviewer>.json
  summary.json
  summary.md
  run_meta.json
"""

from __future__ import annotations

import argparse
import concurrent.futures as futures
import dataclasses
import datetime as dt
import json
import re
import sys
import time
from pathlib import Path
from typing import Callable

try:
    import yaml  # type: ignore
except ImportError:
    yaml = None  # runner는 yaml 없이도 schema/summary 동작은 하되, packet 로드에서 에러

try:
    import jsonschema  # type: ignore
    from jsonschema import Draft202012Validator
except ImportError:
    jsonschema = None  # 없는 환경에서도 runner 는 돌아야 함 (스키마 검증은 스킵)
    Draft202012Validator = None  # type: ignore


# 프로젝트 루트 (이 파일 기준 상대 경로)
PROJECT_ROOT = Path(__file__).resolve().parents[1]
REVIEW_SCHEMA_PATH = PROJECT_ROOT / "templates" / "cross_check_review_schema.json"
GATE_LOG_PATH = PROJECT_ROOT / "09-gates" / "cross-check-log.md"
AUTO_APPEND_END_MARKER = "<!-- AUTO_APPEND_END -->"

# providers 서브패키지는 이 파일이 스크립트(`python tools/crosscheck_runner.py ...`)로
# 실행되든 pytest 에서 import 되든 모두 로드돼야 한다. 두 경로 모두를 지원.
_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))

from providers.base import BaseProvider  # noqa: E402
from providers import load_providers_from_env  # noqa: E402


# ---------------------------------------------------------------------------
# Stub providers (default for offline / regression tests)
#
# BaseProvider 는 tools/providers/base.py 에서 import 한다. 실제 adapter 는
# --real 플래그에서 load_providers_from_env() 가 로드한다. 이 Stub 들은
# 기존 PoC 회귀 및 --fail / --corrupt 경로 기본값으로 유지된다.
# ---------------------------------------------------------------------------


class StubClaudeProvider(BaseProvider):
    name = "claude-opus-4-6"
    model = "stub-claude-opus-4-6"
    reviewer_mode = "advisory_only"

    def review(self, packet: dict) -> dict:
        return _stub_review(self, packet, verdict="revise")


class StubGptProvider(BaseProvider):
    name = "gpt-4o"
    model = "stub-gpt-4o"
    reviewer_mode = "full_reviewer"

    def review(self, packet: dict) -> dict:
        return _stub_review(self, packet, verdict="revise")


class StubGeminiProvider(BaseProvider):
    name = "gemini-1.5-pro"
    model = "stub-gemini-1.5-pro"
    reviewer_mode = "full_reviewer"

    def review(self, packet: dict) -> dict:
        return _stub_review(self, packet, verdict="adopt")


def _stub_review(provider: BaseProvider, packet: dict, verdict: str) -> dict:
    """실제 API가 없는 환경에서도 runner 흐름 검증이 가능하도록 하는 스텁."""
    return {
        "reviewer": provider.name,
        "reviewer_mode": provider.reviewer_mode,
        "review_status": "ok",
        "verdict": verdict,
        "confidence": 0.6,
        "agreement_points": [
            f"{packet.get('decision_key', '?')} 의 문제 정의에는 동의",
        ],
        "objections": [
            {
                "point": "엣지케이스 TTL 만료와 call state 전환 순서 검토 필요",
                "severity": "high",
            }
        ],
        "edge_cases": [
            "TTL 만료 직후 수신 콜이 들어오면 상태 머신이 모호해질 수 있음",
        ],
        "recommended_option": "current" if verdict != "reject" else "ALT-1",
        "requires_human_decision": True,
        "blocking_questions": [
            "duplicate guardian action 예방 로직이 current_proposal에 포함되어 있는가?",
        ],
    }


# ---------------------------------------------------------------------------
# Run config
# ---------------------------------------------------------------------------


@dataclasses.dataclass
class ReviewResult:
    provider: str
    reviewer_mode: str
    status: str          # ok | failed | partial
    response: dict | None
    error: str | None
    started_at: str
    finished_at: str
    elapsed_ms: int
    cost_estimate: float | None = None  # provider가 알면 채우고 모르면 None
    failure_reason: str | None = None   # parse_error | schema_mismatch | timeout | exception
    schema_errors: list[str] | None = None  # 스키마 불일치 메시지 (partial 일 때)


def _now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def _new_run_id() -> str:
    return dt.datetime.now().strftime("%Y%m%d-%H%M%S")


# ---------------------------------------------------------------------------
# Schema validation
# ---------------------------------------------------------------------------


_REVIEW_VALIDATOR = None


def _load_review_validator():
    """cross_check_review_schema.json 기반 validator 로드. jsonschema 미설치면 None."""
    global _REVIEW_VALIDATOR
    if _REVIEW_VALIDATOR is not None:
        return _REVIEW_VALIDATOR
    if Draft202012Validator is None:
        return None
    if not REVIEW_SCHEMA_PATH.exists():
        return None
    with REVIEW_SCHEMA_PATH.open("r", encoding="utf-8") as f:
        schema = json.load(f)
    _REVIEW_VALIDATOR = Draft202012Validator(schema)
    return _REVIEW_VALIDATOR


def parse_and_validate_review(raw: object) -> tuple[dict | None, str | None, list[str]]:
    """provider 응답을 파싱하고 스키마 검증.

    반환: (response_dict, failure_reason, schema_errors)
      - response_dict: 파싱 성공 시 dict, 실패 시 None
      - failure_reason:
          None               → 완전히 OK
          "parse_error"      → JSON 파싱 자체 실패 (review_status=failed 후보)
          "schema_mismatch"  → 파싱은 됐으나 스키마 불일치 (review_status=partial 후보)
      - schema_errors: 스키마 검증 에러 메시지 리스트 (schema_mismatch 일 때만 의미)
    """
    # 1) 파싱
    if isinstance(raw, (dict, list)):
        parsed = raw
    elif isinstance(raw, (str, bytes, bytearray)):
        try:
            parsed = json.loads(raw)
        except (json.JSONDecodeError, ValueError) as exc:
            return None, "parse_error", [f"json decode: {exc}"]
    else:
        return None, "parse_error", [f"unsupported response type: {type(raw).__name__}"]

    if not isinstance(parsed, dict):
        return None, "parse_error", ["response root is not a JSON object"]

    # 2) 스키마 검증
    validator = _load_review_validator()
    if validator is None:
        # jsonschema 없음 — 스키마 검증 스킵, 파싱만 통과했으므로 OK 로 간주
        return parsed, None, []

    errors = sorted(validator.iter_errors(parsed), key=lambda e: e.path)
    if errors:
        messages = [
            f"{'/'.join(str(p) for p in e.absolute_path) or '<root>'}: {e.message}"
            for e in errors
        ]
        return parsed, "schema_mismatch", messages
    return parsed, None, []


# ---------------------------------------------------------------------------
# Packet loading + validation
# ---------------------------------------------------------------------------


REQUIRED_PACKET_FIELDS = [
    "decision_key",
    "packet_version",
    "domain",
    "change_type",
    "decision_required",
    "current_proposal",
    "invariants",
    "severity_guide",
    "review_questions",
    "required_output",
]


def load_packet(path: Path) -> dict:
    if yaml is None:
        raise RuntimeError("PyYAML 이 필요합니다. `pip install pyyaml`")
    with path.open("r", encoding="utf-8") as f:
        packet = yaml.safe_load(f)
    if not isinstance(packet, dict):
        raise ValueError(f"packet 이 dict 가 아님: {path}")
    return packet


def validate_packet(packet: dict) -> list[str]:
    missing = [k for k in REQUIRED_PACKET_FIELDS if k not in packet]
    issues = []
    if missing:
        issues.append(f"필수 필드 누락: {missing}")
    sev = packet.get("severity_guide")
    if isinstance(sev, dict):
        needed = {"critical", "high", "medium", "low"}
        if not needed.issubset(sev.keys()):
            issues.append(f"severity_guide 는 {needed} 전부를 정의해야 함")
    return issues


# ---------------------------------------------------------------------------
# Parallel execution
# ---------------------------------------------------------------------------


def run_reviewers(
    providers: list[BaseProvider],
    packet: dict,
) -> list[ReviewResult]:
    """provider별 timeout 유지. 병렬 실행. 예외는 status=failed 로 담는다."""

    results: list[ReviewResult] = []

    def _call(p: BaseProvider) -> ReviewResult:
        started = time.monotonic()
        started_iso = _now_iso()
        try:
            raw = p.review(packet)
            elapsed_ms = int((time.monotonic() - started) * 1000)

            parsed, failure_reason, schema_errors = parse_and_validate_review(raw)

            if failure_reason == "parse_error":
                return ReviewResult(
                    provider=p.name,
                    reviewer_mode=p.reviewer_mode,
                    status="failed",
                    response=None,
                    error="; ".join(schema_errors) or "parse error",
                    started_at=started_iso,
                    finished_at=_now_iso(),
                    elapsed_ms=elapsed_ms,
                    failure_reason="parse_error",
                    schema_errors=schema_errors,
                )
            if failure_reason == "schema_mismatch":
                # partial — 파싱은 됐으나 스키마 불일치.
                # 집계에서는 objection 으로 승격 금지, 실패 메타로 기록.
                return ReviewResult(
                    provider=p.name,
                    reviewer_mode=p.reviewer_mode,
                    status="partial",
                    response=parsed,
                    error="; ".join(schema_errors[:3]),
                    started_at=started_iso,
                    finished_at=_now_iso(),
                    elapsed_ms=elapsed_ms,
                    failure_reason="schema_mismatch",
                    schema_errors=schema_errors,
                )

            # OK — 파싱/검증 모두 통과.
            # provider 가 review_status 를 명시했으면 존중 (ok/failed/partial).
            status = (parsed or {}).get("review_status", "ok")
            return ReviewResult(
                provider=p.name,
                reviewer_mode=p.reviewer_mode,
                status=status,
                response=parsed,
                error=None,
                started_at=started_iso,
                finished_at=_now_iso(),
                elapsed_ms=elapsed_ms,
            )
        except Exception as exc:  # pragma: no cover — provider 실제 호출 경로
            elapsed_ms = int((time.monotonic() - started) * 1000)
            return ReviewResult(
                provider=p.name,
                reviewer_mode=p.reviewer_mode,
                status="failed",
                response=None,
                error=f"{type(exc).__name__}: {exc}",
                started_at=started_iso,
                finished_at=_now_iso(),
                elapsed_ms=elapsed_ms,
                failure_reason="exception",
            )

    with futures.ThreadPoolExecutor(max_workers=max(1, len(providers))) as pool:
        future_map = {pool.submit(_call, p): p for p in providers}
        for p, fut in zip(providers, future_map):  # noqa: B905
            pass
        for fut in futures.as_completed(future_map):
            p = future_map[fut]
            try:
                results.append(fut.result(timeout=p.timeout_seconds))
            except Exception as exc:
                results.append(
                    ReviewResult(
                        provider=p.name,
                        reviewer_mode=p.reviewer_mode,
                        status="failed",
                        response=None,
                        error=f"TIMEOUT_OR_EXEC: {exc}",
                        started_at=_now_iso(),
                        finished_at=_now_iso(),
                        elapsed_ms=int(p.timeout_seconds * 1000),
                        failure_reason="timeout",
                    )
                )

    # provider 순서를 원본 순으로 정렬 (as_completed 는 도착 순이기 때문)
    order = {p.name: i for i, p in enumerate(providers)}
    results.sort(key=lambda r: order.get(r.provider, 99))
    return results


# ---------------------------------------------------------------------------
# Summarize (NOT judge)
# ---------------------------------------------------------------------------


AGREEMENT_STATES = (
    "STRONG_CONSENSUS",      # full_reviewer 전원 adopt + objection 없음
    "WEAK_CONSENSUS",        # full_reviewer 전원 같은 방향이지만 objection 있거나 verdict ≠ adopt
    "MATERIAL_CONFLICT",     # full_reviewer 의견 갈림
    "INSUFFICIENT",          # 성공 full_reviewer 0명, 또는 전체 성공 1명 이하
)


def summarize_reviews(
    packet: dict,
    results: list[ReviewResult],
    run_id: str,
    related_runs: list[str] | None = None,
) -> dict:
    """판정이 아니라 요약만 생성한다.

    agreement_state 는 합의 형태만 표현하고, reviewer 실패 여부는 별도 degraded 필드로 분리한다.
    """

    reviewer_statuses = {r.provider: r.status for r in results}
    full_ok = [
        r for r in results
        if r.status == "ok" and r.reviewer_mode == "full_reviewer"
    ]
    advisory_ok = [
        r for r in results
        if r.status == "ok" and r.reviewer_mode == "advisory_only"
    ]
    # partial 과 failed 모두 "집계에서 빠지는" 리뷰. 단, gate log 에는 이름을 남긴다.
    degraded_reviewers = [r for r in results if r.status in ("failed", "partial")]

    successful_full_reviewer_count = len(full_ok)
    successful_advisory_count = len(advisory_ok)
    failed_reviewer_count = len(degraded_reviewers)
    ok_count = successful_full_reviewer_count + successful_advisory_count

    # Degrade 판정 축 (agreement_state 와 독립).
    # degraded=false 는 성공 full_reviewer 가 2명 이상이고 실패도 없어야만 성립한다.
    degraded = bool(degraded_reviewers) or successful_full_reviewer_count < 2

    note_parts: list[str] = []

    # Degrade 카운트 규칙:
    #   - 요약 생성 가능 조건: 성공 리뷰어 총 2명 이상 (advisory 카운트 포함)
    #   - 단, full_reviewer 가 0명이면 집계 가중치 원칙상 suggested_action 계산 불가 → INSUFFICIENT
    #   - full 1 + advisory 1 은 요약 생성 가능. 단 degraded=true, user_decision_required=true
    #   - degraded=false 는 성공 full_reviewer ≥ 2 일 때만
    if ok_count < 2 or successful_full_reviewer_count == 0:
        suggested_action = "needs_human_decision"
        agreement_state = "INSUFFICIENT"
        user_decision_required = True
        if successful_full_reviewer_count == 0:
            note_parts.append(
                "full_reviewer 가 하나도 성공하지 못했습니다. advisory 리뷰만으로는 판단하지 않음."
            )
        else:
            note_parts.append(
                f"성공 리뷰어가 {ok_count}명이라 요약 불가. 사용자 판단 필요."
            )
    else:
        full_verdicts = [r.response.get("verdict") for r in full_ok if r.response]
        if len(set(full_verdicts)) == 1:
            only = full_verdicts[0]
            has_any_objection = any(
                (r.response or {}).get("objections") for r in full_ok
            )
            if only == "adopt" and not has_any_objection:
                suggested_action = "adopt"
                agreement_state = "STRONG_CONSENSUS"
            elif only == "adopt":
                suggested_action = "adopt"
                agreement_state = "WEAK_CONSENSUS"
            elif only == "reject":
                suggested_action = "reject"
                agreement_state = "WEAK_CONSENSUS"
            else:
                suggested_action = "revise"
                agreement_state = "WEAK_CONSENSUS"
            user_decision_required = any(
                (r.response or {}).get("requires_human_decision")
                for r in full_ok + advisory_ok
            )
        else:
            suggested_action = "revise"
            agreement_state = "MATERIAL_CONFLICT"
            user_decision_required = True

    if degraded:
        if degraded_reviewers:
            names = ", ".join(f"{r.provider}({r.status})" for r in degraded_reviewers)
            note_parts.append(
                f"{names}; full_reviewer={successful_full_reviewer_count}, "
                f"advisory={successful_advisory_count} 로 축소 집계. "
                "suggested_action 은 참고값이며 user_decision_required 가 우선."
            )
        elif successful_full_reviewer_count < 2:
            note_parts.append(
                f"성공 full_reviewer 가 {successful_full_reviewer_count}명이라 degraded. "
                "suggested_action 은 참고값이며 user_decision_required 가 우선."
            )
        # degrade 시 human decision 강제
        user_decision_required = True

    # consensus / divergence / blocking_issues
    consensus_points = _consensus(full_ok)
    divergence_points = _divergence(full_ok)
    blocking_issues = _blocking_issues(full_ok, advisory_ok)

    return {
        "run_id": run_id,
        "decision_key": packet.get("decision_key"),
        "packet_version": packet.get("packet_version"),
        "supersedes_run_id": packet.get("supersedes_run_id"),
        "suggested_action": suggested_action,
        "agreement_state": agreement_state,
        "degraded": degraded,
        "user_decision_required": user_decision_required,
        "successful_full_reviewer_count": successful_full_reviewer_count,
        "successful_advisory_count": successful_advisory_count,
        "failed_reviewer_count": failed_reviewer_count,
        "reviewer_statuses": reviewer_statuses,
        "failed_reviewers": [r.provider for r in degraded_reviewers],
        "consensus_points": consensus_points,
        "divergence_points": divergence_points,
        "blocking_issues": blocking_issues,
        "note": " ".join(note_parts) if note_parts else "",
        "related_runs": related_runs or [],
    }


def _consensus(full_ok: list[ReviewResult]) -> list[str]:
    if len(full_ok) < 2:
        return []
    sets = [
        set((r.response or {}).get("agreement_points") or [])
        for r in full_ok
    ]
    common = set.intersection(*sets) if sets else set()
    return sorted(common)


def _divergence(full_ok: list[ReviewResult]) -> list[dict]:
    out = []
    for r in full_ok:
        resp = r.response or {}
        if (resp.get("objections") or []):
            out.append(
                {
                    "reviewer": r.provider,
                    "verdict": resp.get("verdict"),
                    "objections": resp.get("objections"),
                    "recommended_option": resp.get("recommended_option"),
                }
            )
    return out


def _blocking_issues(
    full_ok: list[ReviewResult],
    advisory_ok: list[ReviewResult],
) -> list[dict]:
    """full_reviewer objection 중 high/critical + advisory 의 invariants/canon 지적."""
    out: list[dict] = []
    for r in full_ok:
        for obj in ((r.response or {}).get("objections") or []):
            if obj.get("severity") in ("critical", "high"):
                out.append({"source": r.provider, **obj})
    for r in advisory_ok:
        for obj in ((r.response or {}).get("objections") or []):
            # advisory 는 invariants/canon 관련일 때만 blocking 후보로 승격
            if obj.get("severity") == "critical":
                out.append({"source": r.provider, "advisory": True, **obj})
    return out


# ---------------------------------------------------------------------------
# Output writing
# ---------------------------------------------------------------------------


def write_outputs(
    run_dir: Path,
    packet: dict,
    results: list[ReviewResult],
    summary: dict,
    run_meta: dict,
) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    reviews_dir = run_dir / "reviews"
    reviews_dir.mkdir(exist_ok=True)

    for r in results:
        payload = {
            "provider": r.provider,
            "reviewer_mode": r.reviewer_mode,
            "review_status": r.status,
            "started_at": r.started_at,
            "finished_at": r.finished_at,
            "elapsed_ms": r.elapsed_ms,
            "error": r.error,
            "failure_reason": r.failure_reason,
            "schema_errors": r.schema_errors,
            "response": r.response,
        }
        (reviews_dir / f"{r.provider}.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    (run_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (run_dir / "summary.md").write_text(
        _render_summary_md(packet, summary),
        encoding="utf-8",
    )
    (run_dir / "run_meta.json").write_text(
        json.dumps(run_meta, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def append_gate_log(summary: dict, artifact_path: Path) -> None:
    """09-gates/cross-check-log.md 의 AUTO_APPEND_END 마커 앞에 한 줄 append.

    동일한 run_id 가 이미 있으면 idempotent 하게 스킵한다.
    """
    if not GATE_LOG_PATH.exists():
        raise FileNotFoundError(f"gate log not found: {GATE_LOG_PATH}")

    failed_reviewers = summary.get("failed_reviewers") or []
    failed_cell = ", ".join(failed_reviewers) if failed_reviewers else "-"
    rel_artifact = str(artifact_path).replace("\\", "/")
    if rel_artifact.startswith(str(PROJECT_ROOT).replace("\\", "/") + "/"):
        rel_artifact = rel_artifact[len(str(PROJECT_ROOT).replace("\\", "/")) + 1 :]
    supersedes = summary.get("supersedes_run_id") or "-"
    note = (summary.get("note") or "").replace("|", "/").replace("\n", " ")
    if len(note) > 120:
        note = note[:117] + "..."

    row = (
        f"| {_now_iso()} "
        f"| {summary.get('decision_key')} "
        f"| {summary.get('packet_version')} "
        f"| {summary.get('run_id')} "
        f"| {summary.get('agreement_state')} "
        f"| {str(summary.get('degraded')).lower()} "
        f"| {summary.get('suggested_action')} "
        f"| {summary.get('user_decision_required')} "
        f"| {failed_cell} "
        f"| {rel_artifact}/ "
        f"| {supersedes} "
        f"| - "
        f"| {note} |"
    )

    content = GATE_LOG_PATH.read_text(encoding="utf-8")

    # Idempotent: 같은 run_id 가 이미 기록돼 있으면 스킵
    if f"| {summary.get('run_id')} |" in content:
        return

    if AUTO_APPEND_END_MARKER not in content:
        raise RuntimeError(
            f"gate log 에 {AUTO_APPEND_END_MARKER} 마커가 없습니다. 수동으로 추가하세요."
        )

    new_content = content.replace(
        AUTO_APPEND_END_MARKER,
        f"{row}\n{AUTO_APPEND_END_MARKER}",
    )
    GATE_LOG_PATH.write_text(new_content, encoding="utf-8")


def _render_summary_md(packet: dict, summary: dict) -> str:
    lines: list[str] = []
    lines.append(f"# CROSS_CHECK Summary — {summary.get('decision_key')}")
    lines.append("")
    lines.append("> **주의.** CROSS_CHECK 는 자동 승인 게이트가 아니다.")
    lines.append("> `suggested_action` 은 권고값이며, 다음 gate 로 자동 진행하지 않는다.")
    if summary.get("degraded"):
        lines.append("> ")
        lines.append("> **DEGRADED MODE.** 하나 이상의 reviewer 가 실패/부분응답으로 축소 집계됨.")
        lines.append("> 이 상태에서는 `suggested_action` 은 단순 **참고값**이고,")
        lines.append("> `user_decision_required=true` 가 항상 우선한다.")
        lines.append("> 사람 판단 없이 다음 단계로 진행 금지.")
    lines.append("")
    lines.append(f"- run_id: `{summary.get('run_id')}`")
    lines.append(f"- packet_version: `{summary.get('packet_version')}`")
    lines.append(f"- supersedes_run_id: `{summary.get('supersedes_run_id')}`")
    lines.append(f"- agreement_state: **{summary.get('agreement_state')}**")
    lines.append(f"- degraded: **{summary.get('degraded')}**")
    lines.append(f"- suggested_action: **{summary.get('suggested_action')}** (권고일 뿐, 자동 승인 아님)")
    lines.append(f"- user_decision_required: **{summary.get('user_decision_required')}**")
    lines.append("")
    lines.append(f"## Decision")
    lines.append(str(packet.get("decision_required", "")))
    lines.append("")
    lines.append(f"## Reviewer Status")
    for k, v in (summary.get("reviewer_statuses") or {}).items():
        lines.append(f"- {k}: {v}")
    lines.append("")
    lines.append("## Consensus")
    for c in summary.get("consensus_points") or []:
        lines.append(f"- {c}")
    lines.append("")
    lines.append("## Divergence")
    for d in summary.get("divergence_points") or []:
        lines.append(f"- **{d.get('reviewer')}** → {d.get('verdict')} ({d.get('recommended_option')})")
        for o in d.get("objections") or []:
            lines.append(f"  - [{o.get('severity')}] {o.get('point')}")
    lines.append("")
    lines.append("## Blocking Issues")
    for b in summary.get("blocking_issues") or []:
        tag = "advisory" if b.get("advisory") else b.get("source")
        lines.append(f"- [{b.get('severity')}] ({tag}) {b.get('point')}")
    lines.append("")
    if summary.get("note"):
        lines.append(f"> {summary.get('note')}")
    lines.append("")
    lines.append("## Related Runs")
    for rel in summary.get("related_runs") or []:
        lines.append(f"- {rel}")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


DEFAULT_PROVIDERS: list[Callable[[], BaseProvider]] = [
    StubClaudeProvider,
    StubGptProvider,
    StubGeminiProvider,
]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="CROSS_CHECK runner (summarizer, not judge)")
    parser.add_argument("packet", type=Path, help="packet yaml 경로")
    parser.add_argument("--out", type=Path, default=Path("09-gates/cross-check-runs"))
    parser.add_argument(
        "--fail",
        action="append",
        default=[],
        help="테스트용: 예외 발생 시뮬레이션할 provider name (반복 가능)",
    )
    parser.add_argument(
        "--corrupt",
        action="append",
        default=[],
        help="테스트용: 스키마 불일치 응답을 반환할 provider name (반복 가능)",
    )
    parser.add_argument(
        "--related",
        action="append",
        default=[],
        help="관련 과거 run_id (반복 가능)",
    )
    parser.add_argument(
        "--no-gate-log",
        action="store_true",
        help="gate log 자동 append 를 끈다 (dry-run 용)",
    )
    parser.add_argument(
        "--real",
        action="store_true",
        help="실제 provider SDK 를 사용한다 (환경변수 기반 factory).",
    )
    parser.add_argument(
        "--stubs",
        action="store_true",
        help="Stub provider 강제 사용 (기본. --real 가 있으면 무시).",
    )
    args = parser.parse_args(argv)
    args.provider_mode = "real" if args.real else "stubs"

    packet = load_packet(args.packet)
    issues = validate_packet(packet)
    if issues:
        print("[packet validation] 실패:", file=sys.stderr)
        for i in issues:
            print(" -", i, file=sys.stderr)
        return 2

    run_id = _new_run_id()
    started = time.monotonic()
    started_iso = _now_iso()

    providers: list[BaseProvider] = []
    if args.real:
        base_providers = load_providers_from_env(
            logger=lambda msg: print(msg, file=sys.stderr),
        )
        if not base_providers:
            print(
                "[runner] --real 요청이지만 활성 provider 가 없습니다. "
                "환경변수(ANTHROPIC_API_KEY/OPENAI_API_KEY/GEMINI_API_KEY)를 확인하세요.",
                file=sys.stderr,
            )
            return 3
    else:
        base_providers = [factory() for factory in DEFAULT_PROVIDERS]

    for p in base_providers:
        if p.name in args.fail:
            providers.append(_FailingProvider(p))
        elif p.name in args.corrupt:
            providers.append(_CorruptProvider(p))
        else:
            providers.append(p)

    results = run_reviewers(providers, packet)
    summary = summarize_reviews(packet, results, run_id, related_runs=args.related)

    # run_meta.json 의 providers 엔트리는 최소 필드만 남긴다.
    # raw 응답/키 관련 정보 일체 금지. 디버깅용 failure_reason / schema_errors 는
    # reviews/<provider>.json 에 분리 기록돼 있다.
    provider_meta_map: dict[str, str] = {p.name: getattr(p, "model", p.name) for p in providers}
    run_meta = {
        "run_id": run_id,
        "decision_key": packet.get("decision_key"),
        "packet_version": packet.get("packet_version"),
        "packet_path": str(args.packet),
        "started_at": started_iso,
        "finished_at": _now_iso(),
        "elapsed_ms": int((time.monotonic() - started) * 1000),
        "schema_validation_enabled": jsonschema is not None,
        "provider_mode": args.provider_mode,
        "providers": [
            {
                "name": r.provider,
                "model": provider_meta_map.get(r.provider, r.provider),
                "status": r.status,
                "elapsed_ms": r.elapsed_ms,
                "cost_estimate": r.cost_estimate,
            }
            for r in results
        ],
    }

    run_dir = args.out / packet.get("decision_key", "UNKNOWN") / run_id
    write_outputs(run_dir, packet, results, summary, run_meta)

    # Gate log append — 실제로 파일에 한 줄 쓴다
    if not args.no_gate_log:
        try:
            append_gate_log(
                summary=summary,
                artifact_path=run_dir,
            )
        except Exception as exc:
            print(f"[runner] gate log append 실패: {exc}", file=sys.stderr)

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n[runner] 산출물: {run_dir}", file=sys.stderr)
    return 0


class _FailingProvider(BaseProvider):
    """--fail 테스트용 래퍼. 실제 provider 인터페이스를 유지하면서 예외만 던진다."""

    def __init__(self, wrapped: BaseProvider) -> None:
        self.name = wrapped.name
        self.model = getattr(wrapped, "model", wrapped.name)
        self.reviewer_mode = wrapped.reviewer_mode
        self.timeout_seconds = wrapped.timeout_seconds

    def review(self, packet: dict) -> dict:
        raise RuntimeError("simulated provider failure")


class _CorruptProvider(BaseProvider):
    """--corrupt 테스트용. 파싱은 되지만 스키마에 불일치하는 응답을 반환."""

    def __init__(self, wrapped: BaseProvider) -> None:
        self.name = wrapped.name
        self.model = getattr(wrapped, "model", wrapped.name)
        self.reviewer_mode = wrapped.reviewer_mode
        self.timeout_seconds = wrapped.timeout_seconds

    def review(self, packet: dict) -> dict:
        # 필수 필드 누락 + confidence 범위 위반 + verdict enum 위반
        return {
            "reviewer": self.name,
            "reviewer_mode": self.reviewer_mode,
            "review_status": "ok",
            "verdict": "maybe",   # enum: adopt|revise|reject 아님
            "confidence": 9.9,    # 0.0~1.0 범위 위반
            # agreement_points / objections / edge_cases / recommended_option /
            # requires_human_decision / blocking_questions 필드 누락
        }


if __name__ == "__main__":
    sys.exit(main())
