# S2 reviewer checklist

본 문서는 **S2 REC-REFIRE debounce / dismiss / safe-confirm CTA / α arm** 인근 코드를 변경하는 PR의 reviewer 운영 규칙이다.

본 문서는 **새 정책을 도입하지 않는다.** Step 2 (`investigations/2026-04-24-cta-semantics/03_step2_design.md`) + Step 3 (`investigations/2026-04-24-cta-semantics/04_step3_impl_plan.md`)에서 잠긴 결정을 review 단계에 재고정하는 용도이며, 코드 / 테스트 / guardrail의 대체가 아니다.

- PR1 (#5) — Implement S2 REC-REFIRE debounce gate
- PR2 (#6) — Add isolation guardrails for S2 debounce regression
- PR3 (이 문서) — Document S2 regression checklist and PR review rules

---

## 1. 본 문서의 책임 정의

산출:
- C1/C2/C3 회귀 규칙 (§3)
- 7개 불변식 (§2) — Step 2/3 잠금에서 도출
- follow-up tracking 1건 (§4) — `UPGRADE_TRIGGERS` 3중 참조
- traceability 표 (§5) — Step 2/3 잠금 9개 ↔ PR1/PR2/PR3 배치

산출하지 않음:
- 새 정책 / 새 결정 / 새 신호 / 새 UI 문구
- 새 동작 코드 / 새 단위 테스트 / 새 guardrail
- `UPGRADE_TRIGGERS` 단일 truth source 통합 (별도 follow-up — §4)

---

## 2. 7개 불변식

S2 인근 PR의 reviewer는 본 7개 불변식 위반이 추가되지 않았는지 확인한다.

### 불변 1 — `"일단 닫기"`는 dismiss-only

`"일단 닫기"` 계열 CTA 핸들러는 overlay/cooldown UI를 닫는 것 외의 부수효과를 가지지 않는다.

금지 호출 (dismiss 핸들러 본문 내):
- `clearTelebankingAnchor`
- `markCurrentCallConfirmedSafe`
- `refreshAnchorHotNow`
- `clearCurrentRiskEvent`
- `snoozeForCall`
- 기타 safe-confirm 계열 부수효과

dismiss-only 본문 위치:
- `RiskOverlayManager.dismiss` — PR2-G6 guardrail 적용
- `BankingCooldownManager.dismiss` / `BankingCooldownManager.dismissIfShowing` — PR2-G6-B guardrail 적용

### 불변 2 — safe-confirm은 별도 전용 흐름

safe-confirm 계열 효과는 safe-confirm 전용 핸들러로만 진입한다.

safe-confirm 진입점:
- 홈 `"안전 확인했어요"` → `HomeViewModel.confirmSafe`
- Warning `"안전 확인 — 위험하지 않습니다"` → `WarningViewModel.confirmSafe`
- 통화 중 팝업 보조 `"통화 경고 닫기"` → in-call safe-confirm 경로 (불변 6 참조)

safe-confirm과 dismiss-only 흐름은 호출 그래프에서 합쳐지지 않는다 — `performSafeCtaSideEffects` 호출은 위 진입점 외에서 발생하지 않는다.

### 불변 3 — REC-REFIRE 억제는 CTA가 아니라 S2 orchestration-layer debounce

REC-REFIRE 패턴 (`{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}` scope 내 동일 signal 재emit) 억제는 monitor → coordinator → S2 게이트 layer에서 결정된다. CTA 핸들러 (Home/Warning ViewModel, RiskOverlayManager, BankingCooldownManager)에서 REC-REFIRE 억제 로직을 직접 처리하지 않는다.

reviewer 확인:
- CTA 핸들러 본문에 `lastFiredAt` / `snapshot` / `S2_REC_REFIRE_TTL_MS` 직접 참조 0건
- CTA 클릭이 S2 게이트 입력면 (this_tick.signals / snapshot / lastFiredAt / wake-up entry)에 영향 0건 → C1 회귀 규칙으로 보강

### 불변 4 — TTL 경계는 `(now - lastFiredAt) > S2_REC_REFIRE_TTL_MS`

S2 게이트 TTL 만료 판정은 정확히 다음 식이다.

```
(now - lastFiredAt) > S2_REC_REFIRE_TTL_MS    // = 30_000L
```

- 정확히 30,000ms 일치 시점 = **미만료** (TTL 유지 → snapshot 비교로 진행)
- 30,001ms부터 만료 (재발동 가능)

금지 변형:
- `>= 30_000L` / `>= S2_REC_REFIRE_TTL_MS`
- 다른 상수값 (`60_000L` / α 상수 재사용 / 새 magic number)

본 식은 PR2-G5 guardrail이 source-text 수준에서 자동 탐지한다. reviewer는 PR diff에서 식이 통째로 다른 형태로 재작성되지 않았는지 확인한다.

### 불변 5 — non-in-call dismiss는 call-safe 효과를 만들지 않음

통화 중이 아닌 경로에서 dismiss CTA가 클릭되었을 때, 호출-안전(call-safe) 계열 부수효과는 발생하지 않는다.

금지 호출 (non-in-call dismiss 경로 내):
- `markCurrentCallConfirmedSafe`
- `snoozeForCall`
- call-safe 계열 anchor / 세션 상태 변경

본 불변은 불변 1의 "dismiss-only" 일반 규칙을 통화 상태 축으로 구체화한 형태다. 통화 중 dismiss는 통화 종료 effect와 결합될 수 있으나, 그 결합도 safe-confirm 부수효과까지 확장되지 않는다.

### 불변 6 — in-call safe-confirm만 safe-confirm 계열 효과를 허용

`markCurrentCallConfirmedSafe` / `snoozeForCall` 같은 통화 안전 표시 부수효과는 통화 중 safe-confirm 경로에서만 호출된다.

허용되는 진입점:
- 통화 중 팝업 보조 `"통화 경고 닫기"` (in-call safe-confirm)
- 통화 중 Warning `"안전 확인"` (in-call safe-confirm)

금지되는 경로:
- 통화 외 dismiss
- 홈 `"안전 확인했어요"` (홈은 통화 외 컨텍스트이므로 통화 안전 부수효과 미발생 — 홈 safe-confirm은 별도의 anchor / risk event clear 효과만 가짐)

### 불변 7 — α debounce와 S2 debounce는 상수/상태/함수/테스트 클래스 공용화 금지

α (`RiskSessionTracker`)와 S2 (`S2RecRefireDebounce`)는 5축 disjoint 공존 (Step 2 §5)이다. 다음 형태의 공용화는 금지된다.

금지 형태:
- 상수: `ALPHA_TTL_MS`와 `S2_REC_REFIRE_TTL_MS`를 단일 상수로 통합 (값이 같아져도 통합 금지)
- 상태: 두 layer가 같은 변수를 공유 (예: 단일 `lastResetAt`로 두 debounce를 표현)
- 함수: 두 layer가 같은 base class / sealed hierarchy / 단일 helper로 묶임
- 테스트 클래스: `RiskSessionTrackerAlphaTest`와 `S2RecRefireDebounceTest`를 단일 클래스로 통합

허용되는 공유:
- `UPGRADE_TRIGGERS` set의 의미 일치 (현재 3중 참조 — §4 follow-up)

본 불변은 PR2-G1~G4 격리 guardrail이 source-text 수준에서 자동 탐지한다. reviewer는 PR diff에서 두 layer를 새로운 추상화로 묶는 시도가 없는지 확인한다.

---

## 3. C1/C2/C3 회귀 규칙

Step 2 §6.6에서 잠근 회귀 규칙. **CTA 핸들러에 새 부수효과를 추가하거나 monitor signal 시퀀스를 변경하는 PR**에서 본 3축이 추가로 깨지지 않는지 확인한다.

| 규칙 | 의미 | 확인 방법 |
|---|---|---|
| **C1** | S2 입력면 직접 접근 없음 | CTA 핸들러 본문에 S2 게이트 입력 변수 (`this_tick.signals` / `snapshot` / `lastFiredAt` / S2 wake-up entry) 직접 read/write 0건 |
| **C2** | scope 영향 없음 | CTA 부수효과가 다음 tick `this_tick.signals ∩ scope` 결과를 바꾸지 않음. scope = `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}` |
| **C3** | escape delta 영향 없음 | CTA 부수효과가 다음 tick `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS`의 원소 집합을 바꾸지 않음 |

C1은 컴파일/grep 차원에서 빠르게 확인 가능. C2/C3은 의미 차원이며, CTA 부수효과 함수 (`clearTelebankingAnchor` / `markCurrentCallConfirmedSafe` / `snoozeForCall` / `refreshAnchorHotNow` / `clearCurrentRiskEvent` / 기타)가 monitor가 다음 tick에서 emit하는 signal set에 영향을 주는지를 reviewer가 확인한다.

새 CTA 부수효과가 추가되면 본 표 자리(C2/C3)에 위반 여부 1줄 메모를 PR description에 남긴다.

---

## 4. follow-up tracking — `UPGRADE_TRIGGERS` 단일화

Step 3 §11.1 follow-up. 본 PR3에서 **수행하지 않는다**.

현재 동일 의미 set이 3곳에 분산되어 있다:

| 참조 위치 | 정의 | 의미 |
|---|---|---|
| `RiskSessionTracker.kt:28-32` | `private val UPGRADE_TRIGGERS` | α arm escape |
| `DefaultRiskDetectionCoordinator.kt:79-83` | `private val UPGRADE_TRIGGERS` | same-call snooze upgrade trigger |
| `S2RecRefireDebounce.kt` | `internal val S2_UPGRADE_TRIGGERS` | S2 REC-REFIRE debounce escape |

PR3가 **수행하지 않는 것**:
- 단일 truth source 통합
- 통합 위치 결정
- 컴파일/런타임 동등성 guard 도입

PR3가 **수행하는 것** (drift 방지 운영 규칙):
- 본 set의 어느 한 원소가 변경되어야 하는 PR이 발생할 경우, **변경 PR이 3곳을 동시에 갱신**하고 Step 3 §11.1 표를 함께 갱신한다.
- PR template `S2 / CTA Semantics Checklist`의 마지막 항목 (`UPGRADE_TRIGGERS single truth source is not changed here unless this PR is explicitly scoped for that follow-up`)이 본 의무를 review-단계에 노출한다. set 원소 변경 PR은 본 항목을 체크 해제 상태로 두지 못한다 — 변경 PR은 명시적으로 follow-up scope임을 PR description에 선언하고 §11.1 표를 함께 갱신한다.
- 단일화 자체는 별도 follow-up (F-UT1 / F-UT2 / F-UT3 — Step 3 §11.1 참조)으로 분리한다.

---

## 5. Step 2/3 잠금 traceability

각 잠금이 어느 PR에서 어떤 형태로 반영됐는지를 본 표에서 추적한다. 본 PR3 commit 시점 기준 broken link 0건.

| Step 2/3 잠금 | PR1 (#5) — S2 gate | PR2 (#6) — guardrails | PR3 (본 문서) — review rules |
|---|---|---|---|
| #1 S2 layer + pure function | ● `S2RecRefireDebounce.kt`, `shouldSuppressS2RecRefire`, Coordinator collect 게이트 | | |
| #2 N=30,000ms / M 미도입 | ● `S2_REC_REFIRE_TTL_MS = 30_000L` | | |
| #3 scope set | ● `S2_REC_REFIRE_SCOPE = {REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}` | | |
| #4 escape predicate + 즉시 재무장 | ● `(this_tick.signals \ snapshot.signals) ∩ S2_UPGRADE_TRIGGERS ≠ ∅` + `s2RecRefireStateAfterFiring` | | |
| #5 α/S2 5축 disjoint 공존 | ● α 상수와 별도 + 분리 클래스 | | ◐ 불변 7 (§2) |
| #6 CTA → S2 입력 0건 + C1/C2/C3 | ◐ PR1-T4/T5/T6 불변 회귀 검증 | | ● C1/C2/C3 (§3) + 불변 1/2/3 (§2) |
| #7 Q2 분리 + 격리 4규칙 | ◐ `S2RecRefireDebounceTest` 신설 | ● PR2-G1~G4 격리 자동화 | ◐ 불변 7 (§2) |
| #C7-1 TTL `>` 연산자 | ● `(now - lastFiredAt) > 30_000L` | ◐ PR2-G5 변형 탐지 | ● 불변 4 (§2) |
| #C7-2 자동화 범위 | | ● 규칙 1/2/3 자동 + 규칙 4 승격 | |
| 전제 — `"일단 닫기"` dismiss-only | | ● PR2-G6 + G6-B | ● 불변 1 + 5 (§2) |
| 전제 — safe-confirm 별도 흐름 | | | ● 불변 2 + 6 (§2) |
| §11.1 — `UPGRADE_TRIGGERS` 3중 참조 | (drift 방지 의무 명시) | | ● follow-up tracking (§4) |

범례: ● 주 반영, ◐ 부분 반영

각 잠금이 최소 1개 PR에 ● 표시로 등록 — 누락 0건.

---

## 6. reviewer 운영 규칙

S2 인근 PR을 review할 때:

1. PR description 또는 변경 파일 목록에서 S2 인근 여부 판단:
   - 다음 식별자가 등장하면 본 체크리스트 적용 — `S2_REC_REFIRE_TTL_MS` / `S2RecRefireDebounce` / `S2RecRefireDebounceState` / `shouldSuppressS2RecRefire` / `s2RecRefireStateAfterFiring` / `S2RecRefireDebounceTest`
   - CTA 핸들러 변경 시 — `confirmSafe` / `RiskOverlayManager.dismiss` / `BankingCooldownManager.dismiss` / `performSafeCtaSideEffects` / `clearTelebankingAnchor` / `markCurrentCallConfirmedSafe` / `snoozeForCall`
   - α 변경 시 — `RiskSessionTracker` / `ALPHA_TTL_MS` / `lastResetAt` / `lastResetSignals`

2. PR template `S2 / CTA Semantics Checklist`의 N/A 게이트 + 8개 체크박스를 확인.
   - S2 / CTA 무관 PR: `N/A` 1개만 체크하고 나머지는 비워둔다.
   - S2 / CTA 관련 PR: `N/A` 체크 금지. 8개 체크박스 모두 위반 0건 확인.
   - 8개 체크박스는 본 문서 §2의 7 불변식 + §4 follow-up tracking 1건과 일대일 대응한다.
     1. `"일단 닫기" remains dismiss-only` — 불변 1
     2. `safe-confirm remains a separate dedicated flow` — 불변 2
     3. `REC-REFIRE suppression remains S2 orchestration-layer debounce, not CTA behavior` — 불변 3
     4. `TTL boundary remains (now - lastFiredAt) > S2_REC_REFIRE_TTL_MS` — 불변 4
     5. `non-in-call dismiss does not create call-safe effects` — 불변 5
     6. `in-call safe-confirm is the only path that may apply safe-confirm side effects` — 불변 6
     7. `α/S2 debounce remains separate: no shared TTL constant, state, function, or test class` — 불변 7
     8. `UPGRADE_TRIGGERS single truth source is not changed here unless this PR is explicitly scoped for that follow-up` — §4 follow-up tracking
   - C1/C2/C3 회귀 규칙 (§3)은 PR template에는 별도 체크박스로 노출하지 않는다. 새 CTA 부수효과 / monitor signal 시퀀스 변경을 포함하는 PR은 reviewer가 본 문서 §3 C1/C2/C3 표를 직접 대조하고, PR description에 C2/C3 영향 1줄 메모를 남긴다.

3. 위반 의심 시 본 문서의 해당 §2 / §3 / §4 항목으로 이동하여 정의 / 금지 호출 / 금지 형태를 reviewer가 직접 대조.

4. PR2 guardrail이 source-text 수준에서 자동 탐지하는 항목 (PR2-G1~G6 + G6-B)은 CI 결과로 1차 검증되며, reviewer는 검사 회피 (예: 의도적 fixture 디렉터리 외 우회 / lint 규칙 비활성화)가 PR에 포함되지 않았는지를 확인한다.

5. 새 정책 / 새 결정이 본 PR로 도입되어야 한다면, 그것은 본 체크리스트의 책임이 아니다 — Step 2 / Step 3를 재오픈하는 별도 design 작업으로 분리한다.

---

## 7. 변경 추적

본 문서가 갱신되어야 하는 경우:

- Step 2 / Step 3 잠금이 변경되면 → §2 불변식, §5 traceability 표 갱신
- PR1 / PR2 / PR3 commit 후 식별자 / 파일 위치가 변경되면 → §5 traceability 표 갱신
- `UPGRADE_TRIGGERS` 단일화 follow-up이 진행되면 → §4 갱신 (통합 위치만 가리키도록)
- 새 CTA 부수효과가 추가되면 → §3 C2/C3 영향 1줄 메모를 PR description에 남기고 (위반이 아니라면) 본 문서는 갱신 불요

본 문서는 **review 운영의 single source of truth**가 아니라 Step 2 / Step 3 문서의 review-단계 reflection이다. Step 2 / Step 3 문서가 우선이며, 본 문서는 그 위에서 reviewer가 사용할 형태로 정리한 것이다.
