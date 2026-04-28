<!--
  본 template은 모든 PR에 자동 노출된다.
  S2 / CTA semantics 관련 PR만 "S2 / CTA Semantics Checklist" 섹션을 채운다. 무관 PR은 N/A 1개만 체크하고 넘어가면 된다.
  상세 정의 / traceability / C1~C3 회귀 규칙은 .github/S2_REVIEW_CHECKLIST.md 참조.
-->

## Summary

<!-- 1~3줄로 변경 의도와 범위 요약 -->

## General checks

- [ ] 빌드 통과 (compile error 0)
- [ ] 기존 테스트 회귀 0건
- [ ] AndroidManifest 변경 없음 또는 변경 사유 명시
- [ ] 새 권한 / foreground service / background service 추가 없음 또는 사유 명시
- [ ] CLAUDE.md 제품 원칙 준수 (자동 메시지 / 외부 연락 자동화 / 감시성 기능 추가 0건)

## S2 / CTA Semantics Checklist

> Fill this section only if this PR touches S2 REC-REFIRE debounce, CTA dismiss/safe-confirm behavior, RiskSessionTracker α debounce, overlay/cooldown dismissal, or `UPGRADE_TRIGGERS`.
> If unrelated, check N/A and skip the rest of this section.
> Detailed definitions, C1/C2/C3 regression rules, and Step 2/3 traceability live in [`.github/S2_REVIEW_CHECKLIST.md`](./S2_REVIEW_CHECKLIST.md).

- [ ] **N/A** — this PR does not touch S2 / CTA semantics.

For S2 / CTA related PRs (do not check N/A above; check each item below):

- [ ] `"일단 닫기"` remains dismiss-only.
- [ ] safe-confirm remains a separate dedicated flow.
- [ ] REC-REFIRE suppression remains S2 orchestration-layer debounce, not CTA behavior.
- [ ] TTL boundary remains `(now - lastFiredAt) > S2_REC_REFIRE_TTL_MS`.
- [ ] non-in-call dismiss does not create call-safe effects.
- [ ] in-call safe-confirm is the only path that may apply safe-confirm side effects.
- [ ] α/S2 debounce remains separate: no shared TTL constant, state, function, or test class.
- [ ] `UPGRADE_TRIGGERS` single truth source is not changed here unless this PR is explicitly scoped for that follow-up.
