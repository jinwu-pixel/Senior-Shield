# CROSS_CHECK Summary — SS-SMOKE-PROBE

> **주의.** CROSS_CHECK 는 자동 승인 게이트가 아니다.
> `suggested_action` 은 권고값이며, 다음 gate 로 자동 진행하지 않는다.
> 
> **DEGRADED MODE.** 하나 이상의 reviewer 가 실패/부분응답으로 축소 집계됨.
> 이 상태에서는 `suggested_action` 은 단순 **참고값**이고,
> `user_decision_required=true` 가 항상 우선한다.
> 사람 판단 없이 다음 단계로 진행 금지.

- run_id: `20260415-170839`
- packet_version: `1`
- supersedes_run_id: `None`
- agreement_state: **INSUFFICIENT**
- degraded: **True**
- suggested_action: **needs_human_decision** (권고일 뿐, 자동 승인 아님)
- user_decision_required: **True**

## Decision
smoke probe — 실제 provider 3사 최소 호출 검증

## Reviewer Status
- claude-opus-4-6: ok
- gpt-4o-mini: partial
- gemini-1.5-pro-latest: failed

## Consensus

## Divergence

## Blocking Issues

> full_reviewer 가 하나도 성공하지 못했습니다. advisory 리뷰만으로는 판단하지 않음. gpt-4o-mini(partial), gemini-1.5-pro-latest(failed); full_reviewer=0, advisory=1 로 축소 집계. suggested_action 은 참고값이며 user_decision_required 가 우선.

## Related Runs
