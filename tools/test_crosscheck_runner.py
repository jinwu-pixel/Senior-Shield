"""crosscheck_runner.py CLI 회귀 테스트.

기존 stub/fail/corrupt 경로와 gate log append idempotency 를 검증한다.
실제 네트워크 호출 없음.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

import crosscheck_runner as cr


PROJECT_ROOT = Path(__file__).resolve().parents[1]
PACKET_PATH = PROJECT_ROOT / "templates" / "packets" / "ss-ttl-expiry-policy.yaml"


def _latest_run_dir(out_root: Path) -> Path:
    sub = out_root / "SS-TTL-EXPIRY-POLICY"
    runs = sorted(p for p in sub.iterdir() if p.is_dir())
    assert runs, f"no run dir under {sub}"
    return runs[-1]


def _read_summary(run_dir: Path) -> dict:
    return json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))


def _read_run_meta(run_dir: Path) -> dict:
    return json.loads((run_dir / "run_meta.json").read_text(encoding="utf-8"))


def test_cli_normal_stubs(tmp_path):
    rc = cr.main(
        [
            str(PACKET_PATH),
            "--out",
            str(tmp_path),
            "--no-gate-log",
        ]
    )
    assert rc == 0

    run_dir = _latest_run_dir(tmp_path)
    summary = _read_summary(run_dir)

    assert summary["degraded"] is False
    assert summary["successful_full_reviewer_count"] == 2
    assert summary["successful_advisory_count"] == 1
    assert summary["failed_reviewer_count"] == 0
    assert summary["agreement_state"] in (
        "STRONG_CONSENSUS",
        "WEAK_CONSENSUS",
        "MATERIAL_CONFLICT",
    )

    meta = _read_run_meta(run_dir)
    assert meta["provider_mode"] == "stubs"
    # Providers 엔트리는 최소 필드만: name/model/status/elapsed_ms/usage/cost_estimate.
    for entry in meta["providers"]:
        assert set(entry.keys()) == {
            "name",
            "model",
            "status",
            "elapsed_ms",
            "usage",
            "cost_estimate",
        }
    # Stub provider 는 usage 를 채우지 않으므로 None 이어야 한다.
    assert all(entry["usage"] is None for entry in meta["providers"])
    assert all(entry["cost_estimate"] is None for entry in meta["providers"])
    # totals 필드가 존재하고, stub 경로에서는 전부 None / 0.
    assert "totals" in meta
    assert meta["totals"]["input_tokens"] is None
    assert meta["totals"]["cost_usd"] is None
    assert meta["totals"]["providers_with_usage"] == 0
    assert meta["totals"]["providers_with_cost"] == 0
    # 비용 추정치의 출처 투명성
    assert meta["totals"]["pricing_version"] is not None
    assert meta["totals"]["pricing_last_updated"] is not None
    # raw response / schema_errors 는 run_meta 에는 없어야 한다.
    assert "schema_errors" not in json.dumps(meta)


def test_cli_fail_provider_degrades(tmp_path):
    rc = cr.main(
        [
            str(PACKET_PATH),
            "--out",
            str(tmp_path),
            "--no-gate-log",
            "--fail",
            "gemini-1.5-pro",
        ]
    )
    assert rc == 0
    run_dir = _latest_run_dir(tmp_path)
    summary = _read_summary(run_dir)
    assert summary["degraded"] is True
    assert summary["user_decision_required"] is True
    assert "gemini-1.5-pro" in summary["failed_reviewers"]


def test_cli_corrupt_provider_becomes_partial(tmp_path):
    rc = cr.main(
        [
            str(PACKET_PATH),
            "--out",
            str(tmp_path),
            "--no-gate-log",
            "--corrupt",
            "gemini-1.5-pro",
        ]
    )
    assert rc == 0
    run_dir = _latest_run_dir(tmp_path)
    summary = _read_summary(run_dir)
    assert summary["degraded"] is True
    assert summary["reviewer_statuses"]["gemini-1.5-pro"] == "partial"
    # partial 은 blocking issues 로 승격되지 않는다.
    for b in summary["blocking_issues"]:
        assert b.get("source") != "gemini-1.5-pro"

    # schema_errors 는 reviews/ 쪽에 남아있어야 한다.
    review_json = json.loads(
        (run_dir / "reviews" / "gemini-1.5-pro.json").read_text(encoding="utf-8")
    )
    assert review_json["failure_reason"] == "schema_mismatch"
    assert len(review_json["schema_errors"]) >= 3


def test_cli_all_full_failed_insufficient(tmp_path):
    rc = cr.main(
        [
            str(PACKET_PATH),
            "--out",
            str(tmp_path),
            "--no-gate-log",
            "--fail",
            "gpt-4o",
            "--fail",
            "gemini-1.5-pro",
        ]
    )
    assert rc == 0
    run_dir = _latest_run_dir(tmp_path)
    summary = _read_summary(run_dir)
    assert summary["agreement_state"] == "INSUFFICIENT"
    assert summary["suggested_action"] == "needs_human_decision"
    assert summary["degraded"] is True
    assert summary["user_decision_required"] is True


def test_gate_log_append_idempotent(tmp_path, monkeypatch):
    """동일 run_id 로 두 번 append 하면 두 번째는 스킵되어야 한다."""
    # gate log 파일을 임시 경로로 교체.
    fake_log = tmp_path / "gate-log.md"
    fake_log.write_text(
        "# gate log\n\n<!-- AUTO_APPEND_END -->\n", encoding="utf-8"
    )
    monkeypatch.setattr(cr, "GATE_LOG_PATH", fake_log)

    summary = {
        "run_id": "20260415-000001",
        "decision_key": "TEST",
        "packet_version": 1,
        "agreement_state": "STRONG_CONSENSUS",
        "degraded": False,
        "suggested_action": "adopt",
        "user_decision_required": False,
        "failed_reviewers": [],
        "supersedes_run_id": None,
        "note": "",
    }
    cr.append_gate_log(summary, artifact_path=tmp_path / "artifact")
    first = fake_log.read_text(encoding="utf-8")
    assert "20260415-000001" in first

    cr.append_gate_log(summary, artifact_path=tmp_path / "artifact")
    second = fake_log.read_text(encoding="utf-8")
    # Idempotent: 같은 run_id 가 두 번 나타나지 않아야 한다.
    assert second.count("20260415-000001") == 1
