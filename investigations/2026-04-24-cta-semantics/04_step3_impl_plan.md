# Step 3 — 구현/테스트 계획 (PR 시퀀스 설계)

작성일: 2026-04-27
대상 브랜치: `investigation/cta-semantics-step1` (main=9a50b98 base)
선행 산출물: `01_step1_semantics.md`, `02_step1_5_recrefire_linkage.md`, `03_step2_design.md`
본 문서 범위: Step 2가 잠근 9개 결정(#1~#7 + #C7-1/#C7-2)을 코드에 옮기는 PR 시퀀스 설계
범위 외: 새 정책/의미 결정, 신규 신호 추가, UI 문구 최종, 코드 자체

---

## 1. 본 문서의 책임 정의

본 문서는 **"무엇을 구현할지"가 아니라 "Step 2의 9개 잠금을 어떤 PR 순서로 코드에 옮길지"**에 집중한다. Step 2는 결정 완료 상태이므로 새 정책 논쟁을 열지 않는다 — 잠금된 항목의 의미를 변경하지 않는 범위 안에서만 PR 시퀀스 설계가 허용된다.

본 문서가 산출하는 것:
- 채택된 PR 분할 축 (D안 — B안 보정형)
- 각 PR별 scope / non-goals / tests / merge conditions / traceability
- Step 2 9개 잠금의 PR 배치 매트릭스
- PR 간 의존성과 순서

본 문서가 산출하지 않는 것:
- 코드 (별도 코드 단계)
- 새 결정 (Step 2가 닫혔으므로)
- 코드 단계 자율 재량 항목 (네이밍 일부, 파일 위치 일부 등)

## 2. Step 2 잠금 (PR 배치의 입력값, 변경 불가)

| ID | 잠금 내용 | 출처 |
|---|---|---|
| **#1** | 억제 계층 = S2 (orchestration-layer debounce, Coordinator collect 블록 내부 게이트). pure function 추출 가능 | §1.6 |
| **#2** | N = 30,000ms, M 미도입 | §2.4 |
| **#3** | scope = `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}` | §3.5 |
| **#4** | escape predicate = `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS ≠ ∅`. escape 후 즉시 snapshot 재무장 | §4.4 |
| **#5** | α/S2 5축 disjoint 공존. `UPGRADE_TRIGGERS` set 1개만 공유 (truth source 1개) | §5.5 |
| **#6** | CTA → S2 결정 입력면 0건 (직접·간접). C1/C2/C3 회귀 규칙 | §6.6 |
| **#7** | 테스트 표면 Q2 — α 기존 보존, S2RecRefireDebounceTest 신설. 격리 4 정적 규칙 | §7.4 |
| **#C7-1** | TTL 경계 = `(now - lastFiredAt) > 30_000L`. 30,000ms 정확 일치는 미만료, 30,001ms부터 만료 | §7.6.5 |
| **#C7-2** | 정적 규칙 1/2/3 자동 (grep), 규칙 4 수동(PR 체크리스트) → 식별자 확정 후 자동화 승격 | §7.6.5 |

전제 (계속 유효):
- `"일단 닫기"` = dismiss-only 불변
- safe-confirm = 별도 전용 흐름 (홈 `"안전 확인했어요"`, Warning `"안전 확인"`)
- REC-REFIRE 억제 = CTA가 아닌 monitor/orchestrator 축의 debounce

## 3. 채택 안 — D안 (B안 보정형 3 PR 분할)

### 3.1. 채택 근거

Step 2의 잠금 대상은 **S2 REC-REFIRE debounce gate라는 단일 동작 책임**으로 정리되었으므로 PR 분할은 **동작 단위**가 맞다. 그러나 기존 B안은 PR1에 S2 gate 구현 전체를 넣고 `S2RecRefireDebounceTest`를 PR2로 미루어 "동작 PR마다 검증 표면이 자체 완결"이라는 설명과 실제 구성이 충돌한다. 런타임 동작 변경은 **같은 PR 안에서 핵심 단위 테스트로 증명**되어야 한다. 따라서 PR1은 구현 + 핵심 테스트를 함께 포함한다.

### 3.2. 분할 구조 요약

```
PR1 — Implement S2 REC-REFIRE debounce gate with unit coverage
       (#1 + #2 + #3 + #4 + #5 + #C7-1 + S2RecRefireDebounceTest 핵심 케이스)

PR2 — Add isolation guardrails for S2 debounce regression
       (#7 격리 4규칙 + #C7-2 자동화 가능 항목 + 금지 패턴 탐지)

PR3 — Document S2 regression checklist and PR review rules
       (#6 C1/C2/C3 + 불변식 체크리스트 + traceability 표)
```

PR 순서 의존성: PR1 → PR2 → PR3 (PR2의 규칙 4 자동화 승격은 PR1의 식별자 확정에 의존, PR3의 traceability는 PR1/PR2의 실제 코드 위치에 의존).

## 4. 버린 안 (재논쟁 금지)

| 안 | 형태 | 거부 사유 |
|---|---|---|
| **A안** — 변경 표면별 4~5 PR 분할 | 상수+set / 게이트 변수+predicate / pure function 추출+테스트 / grep 자동화 / 체크리스트 | PR1/PR2가 독립 동작 의미 없는 "준비 PR"이 됨. 분할 비용이 결과 대비 큼. 단순성 원칙 위반. |
| **B안** — 동작 단위 3 PR 분할 (원형) | S2 게이트 전체 / 테스트 표면 / 회귀 체크리스트 | 방향은 맞으나 테스트가 PR2로 밀려 PR1이 검증 없는 behavior 변경이 됨. 동작 변경과 테스트의 같은-PR 원칙 위반. **D안으로 보정 채택**. |
| **C안** — Walking skeleton 점진 확장 | 게이트+TTL만 (escape stub) → escape 추가 → 격리 자동화 | PR1이 escape=false stub으로 의도적 incomplete behavior를 merge하게 됨. Step 2 §4.4 "escape 후 즉시 재무장" 불변과 일시적 충돌. 정합 위험. |

본 문서는 D안만을 기둥으로 채택하며, 위 3개 안은 코드 단계 진행 중 재제안되지 않는다.

---

## 5. PR1 — Implement S2 REC-REFIRE debounce gate with unit coverage

### 5.0. PR1 식별자 naming guideline (불변, PR2 guardrail 의존)

PR2 guardrail이 PR1 식별자에 grep 룰로 의존하므로, PR1 착수 전 식별자 prefix 원칙을 본 절에서 잠근다. 코드 단계는 본 절을 자율 재량으로 변경할 수 없다.

**원칙: S2 prefix 강제. α debounce와 S2 debounce는 상수/상태/함수 이름에서 분리되어야 한다.**

권장 식별자:

| 종류 | 식별자 | 비고 |
|---|---|---|
| TTL 상수 | `S2_REC_REFIRE_TTL_MS = 30_000L` | `REC_REFIRE_TTL_MS`처럼 prefix 없는 이름 금지 |
| pure decision function | `shouldSuppressS2RecRefire(...)` | boolean 반환, 가짜 clock + signal set 입력 |
| gate/evaluation function | `evaluateS2RecRefireGate(...)` 또는 `applyS2RecRefireDebounce(...)` | 둘 중 하나 선택 (코드 단계 자율) |
| state holder | `S2RecRefireDebounceState` | `lastFiredAt`/`snapshot` 등 state 변수의 캡슐 |
| test class | `S2RecRefireDebounceTest` | §7 잠금 — α의 `RiskSessionTrackerAlphaTest`와 분리 유지 |

금지 변형:
- `RecRefireTtlMs` / `RecRefireDebounceState` / `RecRefireDebounceTest` 등 S2 prefix 누락 형태
- `DebounceTtlMs` / `DebounceState` 등 일반화 이름 (α와 구분 불가능)
- `OrchestrationDebounceTtlMs` 등 prefix가 layer 표현으로 약화된 형태 (α도 RiskSessionTracker layer에서 동작하므로 layer만으로는 분리 부족)

리팩터링 금지:
- "비슷한 debounce니까 공용화"하는 형태의 상수/상태/함수 통합 금지
- α와 S2가 같은 base class 또는 같은 sealed hierarchy로 묶이는 형태 금지
- 상수가 같은 값(현재 α=60s, S2=30s로 다르지만 향후 같아질 가능성 포함)이 되더라도 truth source 통합 금지

본 naming guideline은 Step 2 §5(α/S2 5축 disjoint 공존)의 코드 단계 표현 규칙이다. 본 절이 깨지면 PR2 guardrail의 grep 대상이 모호해지고 #5 잠금의 의미가 약화된다. **Step 2 정책은 재오픈하지 않으며**, 본 naming guideline은 잠금 상태 그대로 PR1 코드에 반영된다.

### 5.1. Scope

Step 2 잠금 #1 + #2 + #3 + #4 + #5 + #C7-1을 코드에 반영하고, 그 동작을 같은 PR 안에서 핵심 단위 테스트로 증명한다.

**구현 항목:**
- S2 debounce 상수 추가
  - `S2_REC_REFIRE_TTL_MS = 30_000L`
  - α 상수(`ALPHA_TTL_MS=60_000L`)와 별도 상수로 둔다 (#2 + #5 축 3 잠금)
- REC-REFIRE 관련 set 정의
  - scope set: `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}` (#3 잠금)
  - `UPGRADE_TRIGGERS` 참조 (truth source 1개 유지, #5 잠금) — 본 PR에서 공용화 위치를 정할지 또는 기존 `RiskSessionTracker.kt:28-32` 정의를 그대로 참조할지는 코드 단계 자율 재량 (단 truth source 1개 원칙 위배 금지)
- orchestration layer에 debounce state 변수 추가
  - `lastFiredAt` 계열 (액션 발동 시각)
  - `snapshot` (액션 발동 시점의 `this_tick.signals ∩ scope`)
  - 필요 시 `lastFiredKey` / `lastSuppressedKey` 계열 (코드 단계 자율 재량)
- S2 gate predicate 추가
  - escape: `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS ≠ ∅` (#4 잠금)
  - TTL 만료: `(now - lastFiredAt) > 30_000L` (#C7-1 잠금)
  - escape 후 동작: 액션 발동 + snapshot 즉시 재무장 (§4.4 불변)
- pure function 추출 (#1 R3 잠금)
  - 위 predicate를 가짜 clock + 가짜 signal set으로 결정성 검증 가능한 형태로

**TTL 경계 잠금 (불변, #C7-1):**
- 식: `(now - lastFiredAt) > 30_000L`
- 정확히 30,000ms 일치 시점은 **미만료** (TTL 유지 → snapshot 비교로 진행)
- 30,001ms부터 만료 (재발동 가능)
- 본 식 외의 변형(`>= 30_000L` 등)은 PR1에서 허용되지 않음

### 5.2. Non-goals

- 격리 4규칙의 **자동화 검사** (PR2)
- C1/C2/C3 회귀 규칙의 **체크리스트 운영화** (PR3)
- §7.4의 G1/G2/G3/G4 **모든** 단위 테스트 케이스 (PR1은 핵심 케이스만 — 5.3 참조)
- 실기 시나리오 F-1~F-4 검증 (별도 작업선)
- `UPGRADE_TRIGGERS` set 공용화 위치 결정 (truth source 1개만 유지하면 코드 단계 자유)
- 새 신호 추가 / 새 정책 결정 (Step 2 닫힘)

### 5.3. Tests (필수 — PR1 merge gate)

PR1은 다음 6개 핵심 케이스를 **반드시** 포함한다 (`S2RecRefireDebounceTest.kt` 신설 또는 그에 준하는 위치).

| ID | 케이스 | 기대 |
|---|---|---|
| **PR1-T1** | same REC-REFIRE within TTL suppress | snapshot=`{REMOTE_CONTROL}` 활성 상태에서 동일 signal 재emit → suppress (액션 미발동) |
| **PR1-T2** | TTL boundary at exactly 30_000L does not expire | `(now - lastFiredAt) == 30_000L` 시점 동일 signal → suppress (TTL 미만료) |
| **PR1-T3** | TTL boundary after 30_001L expires | `(now - lastFiredAt) == 30_001L` 시점 동일 signal → 재발동 (TTL 만료) |
| **PR1-T4** | dismiss-only CTA does not trigger safe-confirm effects | `"일단 닫기"` 핸들러는 `clearTelebankingAnchor`/`markCurrentCallConfirmedSafe`/`refreshAnchorHotNow`/`clearCurrentRiskEvent` 호출 0건 (불변 검증) |
| **PR1-T5** | safe-confirm remains a separate dedicated flow | safe-confirm 핸들러(`HomeViewModel.confirmSafe` / `WarningViewModel.confirmSafe`)와 dismiss 핸들러는 호출 그래프상 분리되어 있음 (불변 검증) |
| **PR1-T6** | REC-REFIRE suppression is orchestration-layer debounce, not CTA behavior | S2 게이트는 CTA 핸들러 호출 없이도 monitor signal 시퀀스만으로 suppress/release 동작 결정 (#1 + #6 통합 검증) |

**케이스 분류:**
- PR1-T1/T2/T3은 **gate 동작 검증** (§7.4 G1/G2/G3 케이스 일부 — C-E3, C-T2, C-T3 매핑)
- PR1-T4/T5/T6은 **불변 회귀 검증** (Step 2의 3대 전제가 코드에 유지되는지)

§7.4의 나머지 케이스(C-T1, C-E1, C-E2, C-E4, C-E5, C-E6, C-R1, C-R2, C-C1, C-C2, C-C3)는 PR1 merge gate가 아니다 — PR1 안에 함께 추가하는 것은 권장되지만 강제하지 않는다. PR1 merge 후 follow-up 단위 테스트 추가 PR로 확장 가능.

테스트 작성 규약:
- 모든 입력은 **set + 가짜 clock** (RiskLevel/AlertState/HomeStatus 같은 상위 표면을 기대 결과로 사용 금지 — §7.4 판정 단위 못박음)
- 테스트 이름과 기대값은 #C7-1 식과 정확히 일치 (예: `suppresses_at_exactly_30000ms`, `releases_at_30001ms`)
- C-T2를 "경계 자체에서 만료"로 해석하지 않음

### 5.4. Merge conditions

PR1 merge가 허용되는 조건:
1. 위 PR1-T1~T6 6건이 모두 GREEN
2. 기존 `RiskSessionTrackerAlphaTest.kt` 기존 케이스 회귀 0건 (α 분리 보존)
3. 빌드 통과 (compile error 0)
4. `ALPHA_TTL_MS`와 `S2_REC_REFIRE_TTL_MS`가 별도 상수로 존재 (공용화 금지 — #2 + #5 축 3)
5. `(now - lastFiredAt) > 30_000L` 형태가 코드에 정확히 존재 (#C7-1)

**RED 조건 (merge 차단, 사용자 잠금):**
- S2 behavior 변경이 있는데 T1~T6 중 하나라도 누락됨 — **테스트 없는 behavior 변경은 허용하지 않는다**
- TTL 조건이 `(now - lastFiredAt) > 30_000L`이 아님
- TTL 식이 `>= 30_000L` 또는 `>= S2_REC_REFIRE_TTL_MS` 형태로 바뀜 (#C7-1 위반)
- dismiss-only CTA가 safe-confirm 효과를 호출함 (불변 위반)
- REC-REFIRE 억제가 CTA layer로 들어감 (#1 위반)
- α debounce와 S2 debounce가 상수/상태/함수에서 공용화됨 (#5 + §5.0 naming guideline 위반)

### 5.5. Traceability (PR1 → Step 2 잠금)

| Step 2 잠금 | PR1 반영 위치 |
|---|---|
| #1 — S2 layer + pure function | orchestration layer 게이트 변수 + pure function 추출 |
| #2 — N=30,000ms, M 미도입 | `S2_REC_REFIRE_TTL_MS = 30_000L` 상수, M 관련 코드 0건 |
| #3 — scope set | scope set 정의 위치(코드 단계 자유) |
| #4 — escape predicate + 즉시 재무장 | predicate 식 + 액션 발동 직후 snapshot 갱신 |
| #5 — α/S2 5축 disjoint | α 상수와 별도 + UPGRADE_TRIGGERS truth source 1개 |
| #C7-1 — TTL `>` 연산자 | predicate의 TTL 식 |
| #6 — CTA → S2 입력 0건 | PR1-T4/T5/T6 불변 검증으로 부분 반영 (전체는 PR3 체크리스트로 보강) |
| #7 — Q2 2클래스 분리 | `S2RecRefireDebounceTest` 신설(PR1) + α 보존(기존). 정적 규칙 자동화는 PR2 |
| #C7-2 — 자동화 범위 | PR1 범위 외 (PR2) |

### 5.6. PR1 follow-up 분리 원칙 (불변)

§7.4 G1~G4 전체 11건(C-T1, C-E1, C-E2, C-E4, C-E5, C-E6, C-R1, C-R2, C-C1, C-C2, C-C3) 중 PR1 merge gate에 포함되지 않은 케이스의 PR1 follow-up 분리는 **조건부**로 허용된다.

**원칙:**
- **PR1-T1~T6는 PR1 merge gate의 핵심 테스트다.**
- **§7.4 G1~G4 전체 11건은 확장 coverage / follow-up regression coverage다.**
- **G1~G4 중 어떤 케이스도 아래 핵심 보증의 유일한 테스트가 되어서는 안 된다:**
  1. TTL boundary: exactly 30_000L does not expire
  2. TTL boundary: 30_001L expires
  3. dismiss-only CTA does not trigger safe-confirm effects
  4. safe-confirm remains a separate dedicated flow
  5. REC-REFIRE suppression is orchestration-layer debounce, not CTA behavior
  6. S2 debounce is separate from α debounce
  7. same REC-REFIRE within TTL suppress
  8. REC-REFIRE after TTL may re-fire

**PR1은 T1~T6만으로도 behavior merge가 가능한 수준이어야 한다. G1~G4 11건은 PR1의 핵심 증명을 대체하지 않는다.**

8개 핵심 보증 ↔ T1~T6 매핑:

| 핵심 보증 | PR1 T# | 비고 |
|---|---|---|
| 1. TTL boundary 30,000L not expire | T2 | C-T2와 동일 입력 |
| 2. TTL boundary 30,001L expires | T3 | C-T3와 동일 입력 |
| 3. dismiss-only CTA no safe-confirm effects | T4 | 불변 회귀 |
| 4. safe-confirm separate flow | T5 | 불변 회귀 |
| 5. REC-REFIRE = orchestration, not CTA | T6 | 불변 회귀 |
| 6. S2 debounce separate from α | §5.0 식별자 분리 + §5.4 merge condition #4 (α/S2 상수 별도) + `S2RecRefireDebounceTest` ≠ `RiskSessionTrackerAlphaTest` | 구조적 분리. 추가 runtime assertion 불요 — 단 PR1-T6 안에서 "S2 게이트는 α의 `lastResetAt`/`lastResetSignals`을 read하지 않음"을 확인할 것 |
| 7. same REC-REFIRE within TTL suppress | T1 | C-E3와 동일 입력 |
| 8. REC-REFIRE after TTL may re-fire | T3 | TTL 만료 후 재발동 — T3가 release 방향까지 커버 |

위 매핑이 보증되지 않으면(예: 핵심 보증 6의 구조적 분리 검증이 누락되거나, T2/T3가 boundary가 아닌 다른 시점만 검증한다면) PR1 merge gate는 **불충분**이며 G1~G4 11건으로 보강하는 것은 정합 위반.

**Follow-up PR 운영:**
- G1~G4의 PR1 미포함 11건은 PR1 merge 후 1개 이상의 follow-up PR로 추가 가능
- follow-up PR은 PR1의 핵심 보증을 약화시키지 않는다 (단순 coverage 확장 용도)
- follow-up PR은 PR2/PR3 merge 순서를 막지 않는다 (병렬 진행 허용)

---

## 6. PR2 — Add isolation guardrails for S2 debounce regression

### 6.1. Scope

Step 2 잠금 #7 + #C7-2를 운영화한다. PR1이 만든 S2 동작 표면이 회귀하지 않도록 격리 4규칙을 자동화 가능한 범위에서 검사 인프라로 옮긴다.

**구현 항목:**
- 격리 4규칙 운영화 (#7 §7.4)
  - 규칙 1: S2 클래스에 `RiskSessionTracker` import 금지
  - 규칙 2: α 클래스에 S2 게이트 함수 import 금지
  - 규칙 3: S2 클래스에 CTA 핸들러 호출 0건
  - 규칙 4: α 클래스에 S2 wake-up 함수 호출 0건
- grep / static guardrail 추가 (#C7-2)
  - 규칙 1/2/3 → 자동 (grep 또는 간단 정적 검사)
  - 규칙 4 → PR1에서 식별자가 확정되었으므로 본 PR에서 자동화로 승격
  - 가벼운 형태로 시작 (정교한 lint 인프라 미도입 — 단순성 원칙)
- 금지 패턴 탐지 (negative test 또는 grep 룰):
  - dismiss-only CTA가 safe-confirm 효과를 호출하는 경로 (불변 위반)
  - CTA layer에서 REC-REFIRE debounce를 직접 처리하는 경로 (#1 위반)
  - TTL 조건이 `>= 30_000L` 또는 `>= S2_REC_REFIRE_TTL_MS`로 바뀌는 경로 (#C7-1 위반)
  - safe-confirm 전용 흐름과 dismiss-only 흐름이 다시 결합되는 경로 (전제 위반)

### 6.2. Non-goals

- C1/C2/C3 회귀 규칙의 PR 체크리스트 운영 (PR3)
- 정교한 lint 인프라 도입 (예: ktlint/detekt 커스텀 룰 풀스택) — 가벼운 grep으로 시작
- 실기 시나리오 F-1~F-4 (별도 작업선)
- 기존 `S2RecRefireDebounceTest`에 새 동작 케이스 추가 (PR1 follow-up 별도 PR)

### 6.3. Tests (필수 — PR2 merge gate)

PR2는 guardrail이 **실제로 위반을 감지하는지** 증명해야 한다. 단순 grep 스크립트 추가로 끝나면 RED.

| ID | 케이스 | 기대 |
|---|---|---|
| **PR2-G1** | 규칙 1 위반 시뮬레이션 | S2 클래스에 `import com.example.seniorshield.monitoring.session.RiskSessionTracker`를 추가한 가짜 fixture에서 guardrail이 실패 |
| **PR2-G2** | 규칙 2 위반 시뮬레이션 | α 클래스에 S2 게이트 함수 import 추가한 가짜 fixture에서 guardrail이 실패 |
| **PR2-G3** | 규칙 3 위반 시뮬레이션 | S2 클래스 본문에 `HomeViewModel.confirmSafe` / `WarningViewModel.confirmSafe` / `RiskOverlayManager.dismiss` / `performSafeCtaSideEffects` 중 하나의 호출을 추가한 fixture에서 guardrail이 실패 |
| **PR2-G4** | 규칙 4 위반 시뮬레이션 | α 클래스에 PR1에서 확정된 S2 wake-up 함수 호출을 추가한 fixture에서 guardrail이 실패 |
| **PR2-G5** | TTL 변형 위반 탐지 | `(now - lastFiredAt) >= 30_000L` 형태로 식을 변형한 fixture에서 guardrail이 실패 |
| **PR2-G6** | dismiss/safe-confirm 결합 위반 탐지 | dismiss 경로에 safe-confirm 부수효과 함수 호출을 추가한 fixture에서 guardrail이 실패 |

**Fixture 운영 방식**: 위 케이스들은 실제 코드를 위반 상태로 만드는 게 아니라, guardrail 자체의 단위 테스트(예: 가짜 입력 문자열에 대한 grep 매칭 검증) 또는 별도 디렉터리의 violation fixture로 구성. 운영 중인 코드를 깨뜨리지 않는다.

### 6.4. Merge conditions

PR2 merge가 허용되는 조건:
1. PR2-G1~G6 6건이 모두 위반 fixture에서 실패하고 정상 코드에서 통과
2. PR1의 PR1-T1~T6은 여전히 GREEN (회귀 0건)
3. 빌드 통과
4. guardrail 인프라가 가벼운 형태 (CI 추가 시간 ≤ 1분 또는 동등 기준 — 단순성 원칙)

**RED 조건 (merge 차단):**
- guardrail이 위반 fixture에서도 통과해버리면 RED — **단순 문서/grep 장식으로 끝내지 않는다**
- 정교한 lint 인프라(예: 새 ktlint 커스텀 룰 풀 도입)가 PR2에 포함되면 RED (단순성 원칙 위반, follow-up 분리)
- 규칙 4 자동화가 누락된 채 PR3로 미뤄지면 RED (#C7-2 승격 의무)

### 6.5. Traceability (PR2 → Step 2 잠금)

| Step 2 잠금 | PR2 반영 위치 |
|---|---|
| #7 — 격리 4 정적 규칙 | PR2-G1~G4 guardrail |
| #C7-2 — 자동화 범위 | 규칙 1/2/3 자동 + 규칙 4 자동 승격 |
| #C7-1 — TTL `>` 연산자 | PR2-G5 변형 위반 탐지 |
| 전제 — dismiss vs safe-confirm 분리 | PR2-G6 결합 위반 탐지 |

---

## 7. PR3 — Document S2 regression checklist and PR review rules

### 7.1. Scope

Step 2 잠금 #6의 C1/C2/C3 회귀 규칙을 PR 체크리스트로 운영화한다. 본 PR은 코드/테스트가 아니라 **리뷰 운영 규칙**이며, PR1/PR2에서 이미 잠긴 불변식을 리뷰 단계에 재고정하는 용도다.

**구현 항목:**
- C1~C3 회귀 규칙 PR 체크리스트 반영 (#6 §6.6)
  - C1: S2 입력면 직접 접근 없음
  - C2: scope 영향 없음
  - C3: escape 집합 delta 영향 없음
- 리뷰어 확인 항목 명시
- 다음 불변식을 체크리스트에 고정 (PR 템플릿 또는 `.github/PULL_REQUEST_TEMPLATE.md` 준하는 위치):
  - `"일단 닫기"`는 dismiss-only
  - safe-confirm은 별도 전용 흐름
  - REC-REFIRE 억제는 CTA가 아니라 S2 orchestration-layer debounce
  - TTL 경계는 `(now - lastFiredAt) > 30_000L`
  - non-in-call dismiss는 call-safe 효과를 만들지 않음
  - in-call safe-confirm만 safe-confirm 계열 효과를 허용
  - α debounce와 S2 debounce는 상수/상태/함수/테스트 클래스 공용화 금지 (Step 2 §5 + §5.0 naming guideline + §10 자율 재량 불가 항목의 review-단계 재고정)
- follow-up tracking (drift 방지) 항목을 체크리스트에 함께 고정:
  - `UPGRADE_TRIGGERS` 3중 참조 (§11.1) — 단일화는 별도 follow-up이며 PR3에서 수행하지 않는다. set 변경 PR이 발생하면 3곳 동시 갱신 + §11.1 표 갱신 의무를 review 단계에서 확인
- traceability 표 — Step 2의 9개 잠금(#1~#7 + #C7-1/#C7-2)이 각 PR에 어떻게 배치됐는지 명시

### 7.2. Non-goals

- 새 동작 코드 추가 (PR1)
- 새 guardrail 인프라 추가 (PR2)
- 새 단위 테스트 추가 (PR1 follow-up 별도)
- 새 정책/의미 결정 (Step 2 닫힘)

### 7.3. Tests (필수 — PR3 merge gate)

PR3는 코드를 추가하지 않으므로 단위 테스트 케이스가 없다. 대신 다음 검증으로 merge gate를 구성한다:

| ID | 검증 | 기대 |
|---|---|---|
| **PR3-V1** | 체크리스트가 PR 템플릿에 등록 | 새 PR 작성 시 체크리스트 항목이 표시됨 |
| **PR3-V2** | C1/C2/C3 항목이 #6 §6.6 회귀 규칙 표와 일대일 대응 | 본 문서 §2 + `03_step2_design.md` §6.6 표와 동일 의미 |
| **PR3-V3** | traceability 표가 PR1/PR2의 실제 코드 위치를 정확히 가리킴 | merge 시점에 broken link 0건 |
| **PR3-V4** | 7개 불변식이 본 문서 §2 전제 + Step 2 §5 / §5.0 / §10과 일치 | 누락 0건, 변형 0건 |
| **PR3-V5** | follow-up tracking (`UPGRADE_TRIGGERS` §11.1)이 체크리스트 표면에 등장 | review-단계 drift 방지 의무가 PR template에 노출됨 |

### 7.4. Merge conditions

PR3 merge가 허용되는 조건:
1. PR3-V1~V4 4건 모두 통과
2. PR1/PR2 모두 merge 완료된 상태 (의존성)
3. 체크리스트가 다음 PR에서 실제 사용 가능한 형태 (단순 텍스트 파일 OK, 정교한 PR action 미요구)

**RED 조건 (merge 차단):**
- 체크리스트가 코드/테스트를 대체하는 것처럼 표현되면 RED — **체크리스트는 PR1/PR2에서 잠긴 불변식의 리뷰 운영 재고정 용도**
- traceability 표가 잠금 9개 중 누락 발생 시 RED
- 새 정책/결정이 본 PR에서 도입되면 RED (Step 2 재오픈 금지)

### 7.5. Traceability (PR3 → Step 2 잠금)

| Step 2 잠금 | PR3 반영 위치 |
|---|---|
| #6 — C1/C2/C3 회귀 규칙 | 체크리스트 §3 (C1/C2/C3) + 불변식 1/2/3 (§2) |
| 전제 — `"일단 닫기"` dismiss-only | 불변식 1 + 5 (§2) |
| 전제 — safe-confirm 별도 흐름 | 불변식 2 + 6 (§2) |
| 전제 — REC-REFIRE 억제 = orchestration-layer | 불변식 3 (§2) |
| #C7-1 — TTL `>` | 불변식 4 (§2) |
| §1.5 §3 invariant 3대 — non-in-call dismiss vs in-call safe-confirm | 불변식 5 + 6 (§2) |
| #5 + §5.0 + §10 — α/S2 공용화 금지 | 불변식 7 (§2) |
| §11.1 — `UPGRADE_TRIGGERS` 3중 참조 | follow-up tracking (§4) |
| 전체 잠금 9개 | traceability 표 (#1~#7 + #C7-1/#C7-2) §5 |

### 7.6. PR3 commit 결과

PR3 산출 위치:
- `.github/PULL_REQUEST_TEMPLATE.md` — 모든 PR 자동 노출. S2 reviewer checklist 6개 체크박스 + 일반 체크 + UPGRADE_TRIGGERS follow-up 1줄
- `.github/S2_REVIEW_CHECKLIST.md` — S2 인근 PR 적용. 7 불변식 (§2), C1/C2/C3 (§3), follow-up tracking (§4), traceability 표 (§5), reviewer 운영 규칙 (§6)
- 본 문서 §7.1 / §7.3 / §7.5 갱신 — 불변식 6→7개, V4 갱신 + V5 추가, traceability 표에 #5/§5.0/§10 + §11.1 행 추가

merge gate 충족 (PR3 commit 시점):
- **PR3-V1** — `.github/PULL_REQUEST_TEMPLATE.md`로 GitHub PR 작성 시 자동 노출 ✓
- **PR3-V2** — C1/C2/C3가 `S2_REVIEW_CHECKLIST.md` §3 표에 일대일 대응 ✓
- **PR3-V3** — traceability 표가 PR1 (#5) 식별자 (`S2_REC_REFIRE_TTL_MS` / `S2RecRefireDebounce` / `shouldSuppressS2RecRefire` / `s2RecRefireStateAfterFiring` / `S2RecRefireDebounceTest`) + PR2 (#6) guardrail ID (`PR2-G1~G6 + G6-B`) + α 식별자 (`RiskSessionTracker` / `ALPHA_TTL_MS`)를 정확히 가리킴, broken link 0건 ✓
- **PR3-V4** — 7 불변식이 본 문서 §2 전제 + Step 2 §5 / §5.0 naming guideline / §10 자율 재량 불가 항목과 일치 ✓
- **PR3-V5** — `S2_REVIEW_CHECKLIST.md` §4 + PR template 마지막 체크박스에서 `UPGRADE_TRIGGERS` follow-up tracking 노출 ✓

---

## 8. 통합 Traceability 매트릭스

| Step 2 잠금 | PR1 | PR2 | PR3 |
|---|:---:|:---:|:---:|
| #1 S2 layer | ● | | |
| #2 N=30,000ms | ● | | |
| #3 scope set | ● | | |
| #4 escape predicate | ● | | |
| #5 α/S2 disjoint | ● | | |
| #6 CTA → S2 0건 + C1/C2/C3 | ◐ (PR1-T4/T5/T6) | | ● (체크리스트) |
| #7 Q2 분리 + 격리 4규칙 | ◐ (S2RecRefireDebounceTest 신설) | ● (격리 자동화) | |
| #C7-1 TTL `>` | ● | ◐ (PR2-G5 변형 탐지) | ◐ (불변식 4) |
| #C7-2 자동화 범위 | | ● | |

범례: ● 주 반영, ◐ 부분 반영

각 잠금이 최소 1개 PR에 ● 표시로 등록됨 — 누락 0건.

## 9. PR 간 의존성과 순서

```
PR1 (S2 gate + 핵심 테스트)
  ↓ S2 wake-up 함수 식별자 확정
  ↓ S2RecRefireDebounceTest 위치 확정
PR2 (격리 자동화 + 금지 패턴 탐지)
  ↓ 실제 코드 위치 확정
PR3 (체크리스트 + traceability)
```

**역순 merge 금지:**
- PR2를 PR1 없이 merge → guardrail이 검증할 대상 코드 없음 (RED)
- PR3을 PR1/PR2 없이 merge → traceability 표가 broken link (RED)

**병렬 작성은 허용**되지만 merge는 순서대로:
- PR2 작성을 PR1 review 중에 시작 가능 (PR1 식별자 가설 위에서)
- PR3 작성을 PR1/PR2 review 중에 시작 가능

## 10. 코드 단계 자율 재량 범위

본 문서가 결정하지 않은 항목은 코드 단계 자율 재량이다 (단 Step 2 잠금 의미 변경 금지):

| 항목 | 자율 재량 |
|---|---|
| `S2_REC_REFIRE_TTL_MS` 상수 위치 (어느 파일 어느 object) | ✓ |
| `UPGRADE_TRIGGERS` 공용화 위치 (단 truth source 1개 유지) | ✓ |
| scope set 정의 위치 (Coordinator 내부 또는 인접 파일) | ✓ |
| pure function 추출의 클래스/파일 분리 형태 | ✓ |
| `lastFiredKey` / `lastSuppressedKey` 같은 보조 변수 도입 여부 | ✓ |
| guardrail의 grep 도구 선택 (단순 grep / awk / 가벼운 ktlint 룰) | ✓ |
| PR 템플릿 위치 (`.github/PULL_REQUEST_TEMPLATE.md` 또는 동등 위치) | ✓ |
| 보조 함수 이름 — `evaluateS2RecRefireGate` vs `applyS2RecRefireDebounce` 중 택일 (S2 prefix 유지 조건) | ✓ |

자율 재량 **불가** 항목 (Step 2 잠금 + §5.0 naming guideline 그대로):
- TTL 식 `(now - lastFiredAt) > 30_000L` — `>=`로 변경 금지
- N=30,000ms 값 — 다른 값 변경 금지
- scope set 원소 — 추가/삭제 금지
- escape predicate 식 형태 — 변경 금지
- α 상수와 S2 상수 통합 금지
- α 클래스와 S2 클래스 통합 금지
- **S2 prefix 식별자 원칙** (§5.0) — 상수 `S2_REC_REFIRE_TTL_MS`, state holder `S2RecRefireDebounceState`, test class `S2RecRefireDebounceTest`, pure function `shouldSuppressS2RecRefire`. prefix 누락/일반화/layer-only 형태 금지
- **공용화 리팩터링 금지** — α와 S2를 같은 base class/sealed hierarchy/단일 상수로 묶는 형태 금지

## 11. 미해결 follow-up (본 문서 범위 외)

- §7.4의 G1~G4 전체 단위 테스트 케이스 (PR1 핵심 6건 외 11건) → §5.6 분리 원칙에 따라 follow-up PR로 운영. PR1 핵심 보증 8건은 T1~T6 안에서 단독 충족되어야 하며 follow-up 11건이 핵심 보증의 유일한 테스트가 되는 것은 금지
- 실기 시나리오 F-1~F-4 검증 인프라 → 별도 검증 세션 작업선
- `UPGRADE_TRIGGERS` 3중 참조 단일화 → §11.1 follow-up tracking 참조
- guardrail의 false positive 정밀도 보강 → 운영 중 점진 보강
- 정교한 lint 인프라 (ktlint/detekt 커스텀 룰 풀스택) 도입 검토 → 별도 작업선

### 11.1. UPGRADE_TRIGGERS 3중 참조 follow-up tracking

PR1 commit 시점에 동일 의미 set이 다음 3곳에 분산되어 있다:

| 참조 위치 | 정의 | 의미 |
|---|---|---|
| `RiskSessionTracker.kt:28-32` | `private val UPGRADE_TRIGGERS` | α arm escape |
| `DefaultRiskDetectionCoordinator.kt:79-83` | `private val UPGRADE_TRIGGERS` | same-call snooze upgrade trigger |
| `S2RecRefireDebounce.kt` | `internal val S2_UPGRADE_TRIGGERS` | S2 REC-REFIRE debounce escape |

**PR1 범위 결정 (불변):**
- PR1에서는 S2 REC-REFIRE debounce 구현 범위를 유지하기 위해 **trigger set 단일화를 수행하지 않는다**.
- PR1이 보장하는 것은 세 위치의 **의미 일치(원소 동일)** 뿐이다.
- 본 복제는 **의도된 임시 상태**이며 **조용한 drift는 허용되지 않는다** — 어느 한쪽 원소가 변경되면 같은 PR에서 세 곳 모두 동시에 동일 변경되어야 한다.

**Follow-up 추적 (PR2 이후 항목, 본 PR1 commit 범위 외):**
- **F-UT1**: 단일 truth source 통합 — 세 위치 중 하나로 통합 또는 별도 공용 파일로 추출. 위치 결정은 코드 단계 자율 재량 (§10 자율 재량 표 유지).
- **F-UT2**: equivalence guard 도입 — 통합 시점까지 drift를 막기 위한 컴파일/런타임 동등성 검증. 후보: (a) 통합 시 자동 해소, (b) PR2 이전에라도 단순 단위 테스트로 세 set의 동등성을 자동검증, (c) PR2 grep 룰로 정의 위치 추적.
- **F-UT3**: α/S2 분리 원칙 보존 — 통합 작업이 진행되더라도 set 통합 ≠ debounce layer 통합. α(`RiskSessionTracker`)와 S2(`S2RecRefireDebounce`)의 5축 disjoint 공존(Step 2 §5)은 통합 후에도 그대로 유지되어야 한다. 즉 set만 공유, 상수/상태/함수/테스트 클래스의 이름은 계속 분리.

**PR1 commit 시점 운영 규칙 (drift 방지):**
- 본 set 변경이 필요한 PR이 발생할 경우, 변경 PR에서 세 위치를 동시에 갱신하고 §11.1 표를 함께 갱신한다.
- F-UT1 통합 PR이 merge되면 본 §11.1는 통합 위치만 가리키도록 갱신한다.

### 11.2. PR2 guardrail baseline 식별자 (불변, PR2 grep 안정성)

PR2 grep/static guardrail 작성 시 **흔들리지 않는 baseline**으로 다음 식별자 5종이 PR1 코드에 등장하며 본 §11.2에서 잠긴다. PR1 이후 이름 변경은 본 §11.2 갱신을 동반해야 하며, **임의의 rename은 PR2 자동화의 grep 안정성을 깬다**.

| 종류 | baseline 식별자 | 위치 |
|---|---|---|
| TTL 상수 | `S2_REC_REFIRE_TTL_MS` | `S2RecRefireDebounce.kt` |
| pure decision function | `shouldSuppressS2RecRefire` | `S2RecRefireDebounce.kt` |
| state holder | `S2RecRefireDebounceState` | `S2RecRefireDebounce.kt` |
| 발동 후 재무장 helper | `s2RecRefireStateAfterFiring` | `S2RecRefireDebounce.kt` |
| 테스트 클래스 | `S2RecRefireDebounceTest` | `S2RecRefireDebounceTest.kt` |

**PR2 자동화의 baseline 운영 규칙:**
- PR2의 grep 룰은 위 5개 식별자를 정확 일치 매칭으로 사용한다 (부분 일치 false positive 회피).
- 본 §11.2 baseline에 없는 식별자(예: 보조 함수 `evaluateS2RecRefireGate` / `applyS2RecRefireDebounce` — §10 자율 재량으로 두 이름 중 택일)는 PR2 grep 안정성에 의존하지 않는다. 코드 단계에서 자유 재량.
- 본 §11.2 baseline 5종 중 어느 것이라도 PR1 이후 이름 변경이 필요한 경우, **변경 PR이 §11.2 표를 동시에 갱신**한 뒤에야 PR2 grep 룰을 그 변경에 맞춰 업데이트할 수 있다.
- 본 baseline 잠금은 식별자의 의미·위치 변경 자체를 금지하지 않는다 — 다만 grep 안정성이 깨지지 않도록 **변경의 가시성**을 확보한다.

## 12. Step 3 작성 종료

본 문서는 Step 2가 잠근 9개 결정의 PR 시퀀스 설계를 D안 — B안 보정형으로 확정한다. 본 문서 이후의 출력은 PR1 코드 작성 단계의 입력이 된다. 코드 단계는 본 문서가 결정한 PR 분할과 merge condition을 그대로 따르며, 자율 재량은 §10 표 범위에 한정된다.

다음 액션:
1. 본 문서 검토 승인
2. PR1 작성 착수 (코드 단계 첫 진입)
3. PR1 review 중 PR2 병렬 작성 가능
4. PR1 merge 후 PR2 merge → PR3 merge 순서
