# Step 2 — REC-REFIRE 억제 설계

- 일자: 2026-04-27
- 브랜치: `investigation/cta-semantics-step1` (base: `main` = `9a50b98`)
- 전제: Step 1 (`01_step1_semantics.md`) + Step 1.5 (`02_step1_5_recrefire_linkage.md`) 결론 그대로 계승
- 범위: REC-REFIRE 억제 메커니즘의 구현/설계 결정. Step 1.5 이월 7개 결정 포인트를 단순성 tie-breaker 기준 아래에서 차례로 확정.
- 금지: 코드 착수. 본 문서가 7개 포인트 모두 합의될 때까지 main/브랜치 코드 변경 0건 유지.
- 진행 방식: 결정 포인트 1개씩 작성 → 사용자 승인 → 다음 포인트.

---

## 결정 포인트 #1 — 억제 계층 (Suppression Layer)

### 1.1. 기본 가설: S2 (orchestration-layer debounce)

**S2 = Coordinator tick 진입부에서 "같은 signal 집합이 직전 동일 액션 발동 후 N초 내에 재emit되면 이번 tick의 액션 트리거(팝업 show, 쿨다운 trigger)는 한 번 더 거른다."**

세션/이벤트 의미는 건드리지 않는다. `accumulatedSignals`는 평소대로 누적되고, score/alertState 평가도 정상 진행된다. 억제는 **"이번 tick에서 사용자에게 새 modal surface를 띄울지 말지"** 결정 단계에만 작용한다.

위치: `DefaultRiskDetectionCoordinator.kt:160` `combine().collect { ... }` 블록 내부, `sessionTracker.update()` 직후 ~ 팝업/쿨다운 발동 분기 직전. 같은 블록 안에 이미 존재하는 `cooldownConsumedSessionId`(line 130, 222-226, 287-293) 및 `notifiedActiveThreats`(syncActiveThreats) 패턴과 **동일 layer**다.

**관찰 단위 (불변):** S2 debounce의 관찰 단위는 UI 액션(CTA 클릭, 오버레이 dismiss, 화면 전환)이 **아니라** trigger emission / coordinator 판단 tick이다. 사용자 입력 시점은 본 변수의 read 또는 write 입력이 아니다 — orchestration suppression 축과 CTA semantics 축이 다시 섞이는 것을 구조적으로 방지한다.

### 1.2. 왜 S2를 기본 가설로 두는가

**Step 1 / Step 1.5에서 이미 고정된 불변에서 S2가 자연 도출된다:**

1. **`"일단 닫기"` = dismiss-only 불변** (Step 1.5 §2.1, §2.2 규칙 1) — CTA 클릭 핸들러가 억제 상태를 쓰지 않아야 하므로, 억제 상태의 소유자는 CTA 외부에 있어야 한다.
2. **safe-confirm은 별도 전용 흐름** (Step 1.5 §2.1) — 세션-layer 억제(α arm)는 safe-confirm 경로 전용으로 이미 자리잡았으므로, REC-REFIRE는 그보다 얕은 layer에 살아야 한다.
3. **REC-REFIRE 억제 = CTA가 아닌 monitor/orchestrator 축의 debounce** (Step 1.5 §2.1) — 후보는 monitor 또는 orchestrator. monitor는 단일 signal 종류(예: app usage)에 갇혀 있고 REC-REFIRE는 "같은 signal 조합이 반복되는 사실"에 반응해야 하므로, 신호를 합치는 자리인 orchestrator가 자연 위치다.
4. **단순성 tie-breaker** (Step 1.5 §2.2 규칙 6) — 동일 보호 효과 시 기존 CTA semantics 유지 + 기존 layer 재사용 우선. Coordinator는 이미 `cooldownConsumedSessionId` / `notifiedActiveThreats` / α call-guard / snooze pre-filter를 모두 보유한 "행동 발동 게이트" 레이어다. **새 클래스/파일 없이** 기존 게이트 옆에 한 줄짜리 debounce를 추가할 수 있다.

대비:
- **S1 (action-layer)** = `BankingCooldownManager.triggerIfNotActive` 또는 팝업 show 직전 lastDismissedAt timestamp 기반 debounce. 단일 surface(쿨다운 또는 팝업)에 갇히고, 두 surface가 교차 등장하는 시나리오(쿨다운 dismiss → 같은 signal로 팝업 재시도)에서 일관성을 잃는다. 또한 dismiss 시각을 manager에 저장해 두는 순간, "방금 dismiss 후 N초"라는 의미가 manager state로 누설되어 CTA 핸들러가 그 timestamp를 갱신하게 되므로 **CTA semantics 드리프트 위험**이 발생한다 (Step 1.5 Q4 라벨-내부 충돌의 변종).
- **혼합** = S1+S2를 동시 두는 안. 두 layer가 같은 의미 변수("같은 signal 조합 재fire 빈도")를 중복 보유하면 truth source가 둘로 갈라져 디버그/테스트가 폭발적으로 복잡해진다. 단순성 tie-breaker 위반.

### 1.3. S2가 만족해야 할 요구사항

| ID | 요구 | 측정 지표 |
|---|---|---|
| R1 | **세션/이벤트 의미 미변경** | `RiskSessionTracker.update()` 시그니처 / 반환 / `accumulatedSignals` / `lastSignalAt` / `hasTrigger` 모두 현 main과 동일. score/alertState 평가도 동일. |
| R2 | **CTA 핸들러 비참조** | 억제 상태 변수가 `BankingCooldownManager` dismiss 핸들러 / `RiskOverlayManager` dismiss 핸들러 / `safeConfirm*` 핸들러 어느 곳에서도 read/write되지 않음. |
| R3 | **새 위협 emit 시 즉시 escape** | UPGRADE_TRIGGERS 신호(`REMOTE_CONTROL_APP_OPENED`, `BANKING_APP_OPENED_AFTER_REMOTE_APP`, `TELEBANKING_AFTER_SUSPICIOUS`) 또는 직전 억제 대상에 없던 새 TRIGGER 진입 시 억제 무력화. α 철학(`RiskSessionTracker.kt:97-98`) 계승. |
| R4 | **유한 TTL** | 60s 한도(α와 동일 시간축, Step 2 #2에서 확정). 이후 자동 만료. 영구 억제 금지. |
| R5 | **호출 횟수가 아니라 "같은 신호 집합이 반복되는가"가 판정 기준** | 직전 억제 발동 시 signal snapshot을 저장하고, 이번 tick signal이 그 snapshot의 부분집합이며 새 trigger 없을 때만 억제 적용. 시간만으로 무조건 N초 막는 형태 금지. |
| R6 | **단위 테스트로 결정성 검증 가능** | `RiskSessionTracker`의 `clock` 주입 패턴(line 55-56) 또는 동급의 가짜 clock으로 5s/30s/60s 경계가 단위 테스트로 검증 가능해야 함. 실기 의존성 0. |
| R7 | **α arm과 의미 분리 유지** | α arm = 세션-layer (current==null + safe-confirm 후 60s) — Step 1.5 §2.1에서 위치 고정. S2 debounce = action-layer (current!=null이거나 current==null 둘 다 + 직전 액션 발동 후 60s). 두 변수의 wake-up 조건이 disjoint. |

R5는 특히 중요하다. 단순 시간 throttle("60초간 무조건 묵음")은 사기범이 화면을 덮은 채 새 trigger를 emit하는 상황에서 사용자를 무방비 상태로 둔다. R3와 함께 묶어서, **"같은 정적 상황의 반복은 막고, 변화는 즉시 통과시킨다"**가 S2의 운영 정의다.

### 1.4. S2의 장점 (왜 이 layer가 적합한가)

1. **기존 Coordinator 게이트 패턴과 동질** — `cooldownConsumedSessionId`(`DefaultRiskDetectionCoordinator.kt:130, 287-293`)는 이미 "같은 세션에서 같은 surface(쿨다운)가 두 번 발동하지 않는다"는 S2와 같은 종류의 게이트다. `notifiedActiveThreats`도 마찬가지로 trigger-based 팝업의 중복 발동을 막는 게이트다. S2는 이 게이트군에 한 식구로 추가된다 → 새 추상 0개.
2. **CTA 격리 강제** — 변수의 read/write 위치가 Coordinator collect 블록 안에서만 일어나면 컴파일 차원에서 CTA 핸들러가 손댈 수 없다. R2를 구조적으로 보장.
3. **escape 경로가 이미 있는 패턴** — 이 블록은 이미 `upgradeTriggerPresent`(line 177-179) 체크를 보유한다. S2 escape는 같은 변수를 재사용하면 추가 코드가 한 줄 분기에 그친다.
4. **테스트 표면이 좁다** — Coordinator의 collect 블록은 유닛 테스트가 어렵지만(Flow combine + DI), S2 핵심 판정은 "snapshot + timestamp + clock 비교"의 순수 함수로 추출 가능 → 별도 작은 클래스/함수에서 단위 테스트 (R6).
5. **세션-layer α를 건드리지 않음** — α는 `RiskSessionTracker` 내부에 그대로 산다. S2가 추가돼도 α의 60s, signal snapshot, escape 조건 모두 무변경 (R7).

### 1.5. S2의 부적합 가능 사유 / 실패 조건

S2가 R1~R7을 만족 못 하거나 다음 조건이 관찰되면 S1 또는 혼합으로 후퇴.

| 실패 조건 ID | 설명 | 관찰 방법 |
|---|---|---|
| **F1** | **S2만으로 REC-REFIRE가 실제 재현 경로에서 안정적으로 억제되지 않음** — Coordinator 게이트 변수 1개로는 5s tick × 30s window 재emit 패턴 또는 surface 교차(쿨다운/팝업 번갈아 등장) 시나리오에서 누수가 발생한다고 logcat/실기가 보여주는 경우. | 실기 시나리오: REC-REFIRE 재현 경로(원격제어 앱 foreground 보유 + 30s window 재emit)에서 S2 gate가 활성인데도 사용자가 같은 modal surface가 반복 등장한다고 인지하는 사건. 단위 테스트로는 R5 snapshot 부분집합 판정이 실제 신호 시퀀스의 변동을 못 잡아내는 케이스. **현재까지 F1 반증 증거 없음** — 본 문서 작성 시점에 누수 시나리오 미관찰. 후퇴 방향: 혼합(S2 + 추가 surface-local debounce) 또는 S2 판정식 정교화. |
| **F2** | **escape 조건이 R3 단독으로 부족** — UPGRADE_TRIGGERS 외에도 PASSIVE 신호의 미세 변화(예: UNKNOWN_CALLER 단독 → UNKNOWN_CALLER + LONG_CALL_DURATION)가 사용자가 인지해야 할 변화인데 S2 snapshot 부분집합 판정이 그것까지 묶어버림. | Step 2 #3 (대상 signal 범위) 확정 후 단위 테스트로 검증. 실패 시 S2 안에서 snapshot 판정 식을 정교화하거나, 최후에 혼합으로 후퇴. |
| **F3** | **Coordinator collect 블록이 더 이상 한 식구로 안 받아줄 정도로 비대** — 본 시점에 collect 블록은 line 160~368 약 200줄. 추가 게이트 변수 1개(snapshot + timestamp + 판정식) 삽입은 약 20줄 증분 예상. 이를 초과해 50줄+ 늘어날 설계가 도출되면 별도 파일로 분리해야 한다는 신호. | Step 2 마지막에 가설 코드를 dry-run으로 라인 count. 50줄 미만이면 통과, 초과 시 분리 전략 (그래도 layer는 S2 유지, 위치만 외부 클래스). |
| **F4** | **R6 단위 테스트가 가짜 clock만으로는 결정성 보장 불가** — Coordinator 자체가 Flow combine 위에 살아 clock 주입이 어렵거나, 판정식을 Coordinator 외부로 추출했을 때 의미가 깨짐. | Step 2 #7 (테스트 전략)에서 가짜 clock + pure function 추출 시뮬레이션. 분리 가능 여부로 판단. 분리 가능하면 OK, 불가능하면 S1으로 후퇴(action-layer는 surface 단일이라 테스트가 더 쉬움). |
| **F5** | **R7 분리가 형식적이고 실질적으로 α와 동일 변수가 두 개 됨** — `lastResetSignals`와 S2의 snapshot이 같은 시점·같은 값을 가리키게 되어 truth source 이중화. | Step 2 #5 (α arm과의 관계) 확정 시 두 변수의 wake-up trigger와 update 시점이 명확히 disjoint한지 표로 검증. 동일하면 흡수(α 이름 그대로 + escape 조건 확장) 검토 — 이때도 layer는 그대로 S2적 의미만 추가, S1 후퇴는 아님. |

**F1 반증 증거 없음 (현재까지)** — 본 문서 작성 시점에 S2 누수 시나리오 미관찰, 따라서 S2 후퇴 근거 0건. 나머지 F2~F5는 Step 2 후속 결정 포인트(#2~#7)를 진행하면서 자연 검증된다. 모두 통과하면 S2 확정. 어느 하나라도 실패하면 해당 시점에 S1/혼합 평가를 본 섹션 끝에 부록으로 추가하고 계층 결정을 갱신한다.

### 1.6. #1 결정 (가설 형태로 고정)

- **억제 계층 = S2 (orchestration-layer debounce, Coordinator collect 블록 내부 게이트 변수 형태)**
- 위치 후보: `DefaultRiskDetectionCoordinator.kt` 또는 같은 의미를 가진 추출된 작은 클래스(F3에 따라 결정).
- α arm은 `RiskSessionTracker` 내부에 그대로 둔다 (R7).
- CTA 핸들러는 본 변수에 read/write 0건 (R2).
- Step 2 #2~#7 진행 중 F1~F5 발현 시 본 결정을 갱신한다.

이 결정은 Step 2 #2~#7의 **모든 후속 결정의 전제**다:
- #2 시간창 = "Coordinator tick 단위에서 측정되는 시간창"
- #3 대상 signal 범위 = "Coordinator combine 블록이 받는 callSignals + nonCallSignals 중 어느 부분집합이 snapshot에 들어가는가"
- #4 escape 조건 = "Coordinator 블록 안에서 이번 tick signal이 escape에 해당하는지 판정하는 식"
- #5 α arm과의 관계 = "Coordinator의 S2 변수와 RiskSessionTracker의 lastResetAt/lastResetSignals 변수가 disjoint한지의 정의"
- #6 CTA 클릭과의 연결 방식 = "S2 변수는 CTA에서 read/write 안 한다"가 R2로 이미 1.6 단계에 박혀 있음. #6에서는 "그럼에도 CTA 시점이 S2의 입력 신호 중 하나가 될 수 있는가"만 추가 결정.
- #7 테스트 전략 = "S2 판정식을 pure function으로 추출했을 때 가짜 clock으로 결정성 보장 가능한가"

---

## 결정 포인트 #2 — 시간창 N/M

**전제 (계속 유효):**
- `"일단 닫기"` = dismiss-only 불변
- safe-confirm = 별도 전용 흐름 (홈 `"안전 확인했어요"`, Warning `"안전 확인"`)
- REC-REFIRE 억제 = CTA가 아닌 monitor/orchestrator 축의 debounce
- #1 결정에 따라 layer는 S2(orchestration-layer, Coordinator collect 블록 내부 게이트)
- 관찰 단위는 trigger emission / coordinator 판단 tick (UI 액션 아님)

### 2.1. N과 M이 무엇을 가리키는가 (먼저 정의)

S2 debounce를 Coordinator collect 블록 안의 게이트 변수로 두면, 시간 파라미터는 잠재적으로 **두 종류**가 등장할 수 있다. 본 섹션은 둘이 실제 필요한지부터 결정한다.

| 기호 | 의미 후보 | 사용 시점 |
|---|---|---|
| **N** | **억제 TTL** — 게이트가 직전 액션 발동 시점부터 활성으로 유지되는 절대 시간. 만료 시 게이트 자동 해제, 다음 같은 signal 조합이 들어오면 액션 재발동 허용. | 게이트 진입 시 `now - lastFiredAt > N` 체크. |
| **M** | **재emit 판정 window** — "같은 signal 조합이 반복된다"고 판정할 때, 직전 발동 시 snapshot과 비교할 raw signal 관찰 window. coordinator tick 1회만 보면 5s 단위라 너무 거칠 수 있어, 다중 tick에 걸친 안정성을 요구할지 여부. | 게이트 활성 중 매 tick에서 "이번 tick signal이 snapshot 부분집합인가" 판정. M=tick 1개면 즉시 판정, M>tick 1개면 안정화 대기. |

α arm 선례는 **N만 사용**한다 (`ALPHA_TTL_MS=60s`, `RiskSessionTracker.kt:22, 88-104`). M에 해당하는 안정화 window는 두지 않고 매 tick `update()` 진입 시 즉시 부분집합 판정. 이 선택이 S2에도 그대로 유효한지 검증한 뒤 N/M 두 파라미터가 모두 필요한지 결정한다.

### 2.2. N/M이 만족해야 할 요구사항 (S2 R1~R7에서 propagate)

| ID | 요구 | 출처 | 측정 |
|---|---|---|---|
| **T1** | **유한 상한** — 시간 파라미터는 모두 유한해야 한다. 영구 억제 금지. | R4 | 정적 검증: 상수가 Long 또는 millis 정수, 0/Long.MAX 미사용. |
| **T2** | **N ≤ α TTL (60s)** — α arm은 safe-confirm 경로의 세션-layer 억제로 60s. S2 N이 그보다 길면 두 layer가 비대칭한 의미 시간축을 갖게 되어 디버깅/멘탈 모델이 갈라진다. | R7, 단순성 tie-breaker | 정적 검증: `S2_TTL_MS <= ALPHA_TTL_MS`. |
| **T3** | **N ≥ Coordinator tick 주기** — N이 tick 주기보다 짧으면 게이트가 사실상 작동 안 함(매 tick에서 만료된 채로 들어옴). app monitor 5s tick + call monitor의 tick은 비고정이지만 1s 단위. N은 최소 5s 이상 권장. | R5 (의미 있는 게이트가 되려면) | 정적 검증: `S2_TTL_MS >= 5_000L`. |
| **T4** | **N은 REC-REFIRE 자연 만료 window를 덮는다** — `RealAppUsageRiskMonitor.kt:23` `DETECTION_WINDOW_MS=30_000L`. 원격제어 앱이 한 번 foreground 된 후 30s 동안 매 5s tick마다 같은 signal이 emit된다. N이 이보다 짧으면 게이트가 자연 emit 종료 전에 만료되어 5s 단위로 액션이 재발동될 위험. | F1 회피 | 정적 검증: `S2_TTL_MS >= DETECTION_WINDOW_MS = 30_000L`. |
| **T5** | **M=1 (단일 tick) 우선** — 안정화 window를 두면 사용자 보호 시점이 M tick만큼 지연된다. 이미 게이트가 N 동안 막아주는 데 추가 안정화는 보호 효과를 크게 늘리지 못하면서 escape 응답을 늦춘다. M>1은 F2(escape 부족)가 관찰될 때만 도입. | R3 (escape 즉시성), 단순성 tie-breaker | 정적: M 변수 자체를 도입하지 않음(현 tick signal로 즉시 판정). 추후 도입 시 사유 명시 필수. |
| **T6** | **clock 주입 단위 결정성** — N은 가짜 clock으로 정확한 만료 경계가 단위 테스트되어야 함 (`RiskSessionTracker.clock` 패턴 재사용 가능). | R6 | 단위 테스트: T-1ms / T / T+1ms 경계 3건 PASS. |
| **T7** | **escape는 N과 무관하게 즉시** — 시간 만료 외에도 UPGRADE_TRIGGERS 또는 새 TRIGGER 출현 시 게이트 즉시 해제. N은 "변화 없는 정적 상황의 상한"이지 "위협을 못 보게 하는 시간"이 아니다. | R3 | 단위 테스트: N 만료 전이라도 UPGRADE 진입 시 액션 즉시 재발동. |

T2~T4가 N의 구간을 좁힌다: **30s ≤ N ≤ 60s**.

### 2.3. 후보 비교

T2~T4를 모두 만족하는 자연 후보는 **N=30s** 와 **N=60s** 두 점이다. 양 끝값을 비교한다.

#### N=30s (T4 하한)

- **장점**
  - REC-REFIRE 자연 만료 window(`DETECTION_WINDOW_MS=30s`)와 정확히 같은 길이. 원격제어 앱 foreground가 끝나 자연스럽게 raw signal 자체가 사라지는 시점에 게이트도 만료. 두 시간축이 같은 의미를 갖는다.
  - α(60s)보다 짧아 두 layer 간 시간 비대칭 없음(T2). 오히려 "S2가 짧고 α가 길다"는 위계가 의미와 일치 — S2는 라이브 재emit 억제, α는 safe-confirm 후 잔여 억제.
  - 사용자 보호 시점이 더 빠르게 회복 — N 만료 후 새 signal 들어오면 즉시 다시 알린다. 보수적 안전 마진.
- **단점**
  - 30s window 끝나는 정확한 ms에 새 raw signal이 한 번 더 emit되는 corner case에서 게이트가 막 만료되어 액션 1회 더 발동 가능. 실제로는 5s tick 단위라 그 corner case 가능성 작지만 0은 아님.

#### N=60s (T2 상한)

- **장점**
  - α와 시간축 일치 → 단일 상수 공용화 후보(`ALPHA_TTL_MS` 재사용 또는 별도 상수지만 같은 값). 단순성 tie-breaker 친화.
  - REC-REFIRE 자연 만료(30s) + 30s 마진 → 30s 직후 최후 emit까지 모두 흡수. 사용자 측 "조용한 시간" 더 길어 UX 피로 더 낮음.
- **단점**
  - α와 의미가 다른데 시간만 같으면 디버깅 시 두 게이트의 활성 사유 구분이 헷갈릴 수 있음(F5 변종). 다만 #5(α arm과의 관계)에서 변수 이름과 wake-up 조건을 명확히 분리하면 회피 가능.
  - 사용자 보호 회복이 30s 더 늦음 — escape 조건(T7)이 잘 작동하면 실제 위협 시 즉시 깨므로 큰 문제는 아니지만, escape가 fail하는 시나리오에서는 30s 더 잠잠.

#### 판정

**N = 30s 채택.** 근거:
1. T4 하한이자 REC-REFIRE 자연 만료 window와 같은 길이 — 두 시간축이 **같은 물리 현상에 대한 같은 시간**을 가리킨다. 의미 일관성 최상.
2. α(60s)와의 위계 분리: S2(30s, raw 재emit 억제) < α(60s, safe-confirm 후 잔여 억제). 시간 길이가 의미 차이를 자연스럽게 표현.
3. 단순성 tie-breaker는 "동일 보호 효과 시" 적용 — 60s가 30s보다 보호 효과를 늘리는 게 아니라 "조용한 시간을 늘릴 뿐"이고, 그 비용은 escape fail 시 사용자 노출 지연. 보호 효과가 동일하지 않으므로 60s 단순화 이득은 의미 없음.
4. N=30s에서 corner case(30s 정각 단일 추가 emit)는 5s tick 격자라 실현 가능성이 매우 낮고, 발생해도 액션 1회 발동은 사용자 안전 측면에서 false-positive보다 낫다.

**M 도입 안 함.** T5 결론대로 단일 tick 즉시 판정. M>1이 필요한 시나리오는 F2(escape 부족)가 관찰될 때 #2를 갱신.

### 2.4. #2 결정

- **N (S2 억제 TTL) = 30,000 ms (30s)**
- **M (안정화 window) = 미도입** (단일 tick에서 즉시 부분집합 판정)
- 상수명 후보: `S2_DEBOUNCE_TTL_MS` 또는 `ORCHESTRATION_DEBOUNCE_TTL_MS` (네이밍 최종은 #5/#6/#7 진행 중 또는 코드 단계에서 확정 — 본 문서 범위 밖)
- α 상수(`ALPHA_TTL_MS=60s`)와 **별도 상수**로 둔다. 같은 값이 되더라도 의미 축이 다르므로 공용화하지 않는다 (Step 1.5 §2.1 의미 축 분리 선언 보존).
- T1~T7 추후 검증 책임:
  - T1, T2, T3, T4 → 코드 단계 정적 검증 (assertion 또는 lint)
  - T5 → 본 결정으로 충족(M 미도입)
  - T6 → #7(테스트 전략)에서 가짜 clock 패턴 확정
  - T7 → #4(escape 조건)에서 구체화

### 2.5. 다음 결정 포인트 입력값

#2가 #3 이후에 미치는 영향:
- **#3 (대상 signal 범위)** — 30s 동안 막을 signal 집합의 성격. PASSIVE만? appSignals 전체? call signal 별도? "30s 동안 라이브 재emit이 일어나는 signal"이 자연 후보.
- **#4 (escape 조건)** — 30s 경과 외 즉시 escape 사유. T7에 따라 UPGRADE_TRIGGERS + 새 TRIGGER가 기본.
- **#5 (α arm과의 관계)** — α=60s vs S2=30s 시간 비대칭이 두 변수 분리 정당화의 한 축. wake-up 조건도 disjoint(α는 current==null 진입 시, S2는 액션 발동 직후).
- **#7 (테스트 전략)** — 가짜 clock으로 T-1ms / T=30,000 / T+1ms 경계 3건 + escape 즉시성 검증 케이스.

---

## 결정 포인트 #3 — 대상 signal 범위

**전제 (계속 유효):**
- `"일단 닫기"` = dismiss-only 불변
- safe-confirm = 별도 전용 흐름 (홈 `"안전 확인했어요"`, Warning `"안전 확인"`)
- REC-REFIRE 억제 = CTA가 아닌 monitor/orchestrator 축의 debounce
- #1 결정: layer = S2 (Coordinator collect 블록 내부 게이트)
- #2 결정: N = 30,000ms, M 미도입
- 관찰 단위: trigger emission / coordinator 판단 tick (UI 액션 아님)

본 결정 포인트의 책임은 **S2 게이트가 비교에 사용하는 signal snapshot에 어떤 signal을 담을 것인가**다. 이 정의는 동시에 "어떤 signal의 재emit이 게이트에 의해 억제 대상이 되는가"를 결정한다. **CTA 의미는 본 결정에 입력되지 않는다** — scope는 raw signal taxonomy + monitor cadence로만 정의된다.

**의미 못박음 (불변):** 본 scope는 **debounce의 억제 입력 집합 정의**다. 위험 표시 전체 taxonomy(SignalCategory, AlertState, RiskLevel)나 사용자 노출 우선순위를 재정의하는 문서가 아니다. scope에서 빠진 signal도 평가/누적/표시는 평소대로 진행되며, 본 결정의 영향은 오직 "S2 게이트가 modal surface 발동을 한 번 더 거를지 여부의 판정 입력"에만 한정된다.

### 3.1. "scope"가 가리키는 것 (먼저 정의)

S2 게이트는 직전 액션 발동 tick에서 관측된 signal 집합(`snapshot_scope`)을 기록하고, 이후 tick마다 다음 두 가지를 판정한다 (R5, T7 propagate):

1. `this_tick_scope ⊆ snapshot_scope` — 같은 정적 상황의 반복인가?
2. `this_tick_scope`에 `snapshot_scope` 밖의 새 trigger가 있는가? (#4에서 구체화)

(1)이 참이고 (2)가 거짓이면 액션 트리거 억제, 그 외엔 escape. **scope 정의 = 어떤 signal이 (1)/(2) 판정에 입력되는가**다. scope에 들어가지 않는 signal은 게이트 입장에서 "투명" — 누적/평가는 평소대로, 액션 발동 여부는 게이트와 무관하게 처리된다.

### 3.2. 현 코드의 signal taxonomy 검증

| Signal | Category | Source / cadence | REC-REFIRE 패턴? |
|---|---|---|---|
| `REMOTE_CONTROL_APP_OPENED` | TRIGGER | `RealAppUsageRiskMonitor` 5s poll × 30s window (`POLL_INTERVAL_MS=5_000L`, `DETECTION_WINDOW_MS=30_000L`) | **YES** — 원격제어 앱이 foreground에 있는 한 매 5s tick에 재emit |
| `BANKING_APP_OPENED_AFTER_REMOTE_APP` | TRIGGER | 동일 monitor, 같은 cadence (recent 30s 내 원격앱 + banking 동시 노출) | **YES** — 단순 사건 의미만이 아니라 remote app foreground 잔존 상태와 결합할 때 매 5s tick 동일 조건 재충족으로 재emit. app-derived 반복 재emit 경로의 두 번째 구성원이므로 scope 포함. |
| `SUSPICIOUS_APP_INSTALLED` | TRIGGER | `RealAppInstallRiskMonitor` broadcast → `SIGNAL_HOLD_MS=5_000L` 후 `emptyList()` 자동 reset | NO (one-shot) |
| `TELEBANKING_AFTER_SUSPICIOUS` | TRIGGER | call monitor (위험 세션 내 은행 ARS 발신 시 1회 emit) | NO (one-shot per call) |
| `UNKNOWN_CALLER` / `LONG_CALL_DURATION` / `UNVERIFIED_CALLER` / `REPEATED_UNKNOWN_CALLER` | PASSIVE | call monitor (통화 단위 cadence, 통화 종료 시 자연 소멸) | call-axis only — `CALL_DERIVED_SIGNALS` pre-filter (line 67-73) + same-call snooze가 이미 처리 |
| `REPEATED_CALL_THEN_LONG_TALK` | AMPLIFIER | call monitor | 동상 |
| `HIGH_RISK_DEVICE_ENVIRONMENT` | PASSIVE | device env monitor (느리게 갱신) | NO (modifier) |

REC-REFIRE를 **실제로 만드는** signal은 정확히 2개 — `REMOTE_CONTROL_APP_OPENED`, `BANKING_APP_OPENED_AFTER_REMOTE_APP`. 나머지는 cadence 또는 category 측면에서 게이트가 막아야 할 패턴을 만들지 않는다.

### 3.3. scope가 만족해야 할 요구사항

| ID | 요구 | 출처 | 측정 |
|---|---|---|---|
| **D1** | **REC-REFIRE source 포함 (필요조건)** — scope는 5s tick × 30s window 재emit 패턴을 만드는 signal을 반드시 포함해야 한다. 그렇지 않으면 게이트가 막을 대상이 없음. | F1, R5 | 정적: scope ⊇ {REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}. |
| **D2** | **call-axis 책임과 disjoint** — `CALL_DERIVED_SIGNALS` 및 same-call snooze 영역의 signal은 scope에 포함하지 않는다. call-axis는 `RiskSessionTracker`(α arm) + coordinator pre-filter(line 67-73)가 이미 통화 단위로 책임진다. S2가 손대면 두 axis의 truth source가 둘이 됨. | R7, F5, 사용자 directive | 정적: scope ∩ CALL_DERIVED_SIGNALS = ∅, scope ∌ TELEBANKING_AFTER_SUSPICIOUS (TELEBANKING은 escape 측이지 억제 측이 아님). |
| **D3** | **PASSIVE/AMPLIFIER 미포함** — 단독으로 modal surface를 발동할 자격이 없는 signal(`SignalCategory.kt:11` 정책)은 게이트 억제 대상에서 제외. PASSIVE 변동은 액션을 일으키지 않으므로 scope에 들어갈 이유 자체가 없음. | SignalCategory 정책, 단순성 tie-breaker | 정적: scope의 모든 원소 category == TRIGGER. |
| **D4** | **one-shot TRIGGER 미포함** — broadcast/세션 단위 1회 emit 후 자연 소멸하는 TRIGGER는 REC-REFIRE 패턴을 만들지 않음. 게이트가 막을 시나리오 부재. | F1 (실제 누수 시나리오 정의), 단순성 tie-breaker | 정적: scope ∌ {SUSPICIOUS_APP_INSTALLED, TELEBANKING_AFTER_SUSPICIOUS}. |
| **D5** | **escape 정의와 disjoint 가능** — scope 외 새 TRIGGER 출현 = escape. 한 signal이 "scope 내 + escape 트리거"가 되는 모순 금지. | R3, T7, #4 | 정적: scope ∩ (UPGRADE_TRIGGERS \ scope) = ∅ (자명). |
| **D6** | **기존 taxonomy로 표현 가능** — 새 enum/category 추가 금지. 기존 RiskSignal / SignalCategory / UPGRADE_TRIGGERS 만으로 정의 가능해야 함. | 단순성 tie-breaker, 구현 규칙 "불필요한 추가 금지" | 정적: scope 정의가 기존 set 연산(예: `UPGRADE_TRIGGERS - {TELEBANKING_AFTER_SUSPICIOUS}`) 또는 명시 set으로 표현 가능. |
| **D7** | **확장 시 동일 cadence 검증 통과 필요** — 미래에 새 TRIGGER signal이 추가될 때 scope 포함 여부는 "5s tick × 30s window 재emit 패턴이 있는가"로 자동 판정. 임의 추가 금지. | F1 회피, 본 결정의 결정성 보존 | 메타: 본 문서 §3.2 표 갱신 + D1 재검증 절차로 신호 단위 판정. |

D1~D4가 scope의 **유일 해**를 결정한다.

### 3.4. 후보 비교

D1~D4의 결합 결과 자연 후보는 4가지 — 더 넓은 후보는 D2~D4 위반으로 자체 탈락이지만 명시적으로 비교한다.

#### A. 최소 — `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}`

- **장점**
  - REC-REFIRE 정의에 정확히 매칭. 과억제 위험 0 (게이트가 막는 신호 외 모든 signal은 평소대로 평가).
  - D1~D6 전부 PASS, D7 자명.
  - call-axis와 완전 분리 → α arm / same-call snooze 변경 0건.
  - escape predicate(#4)이 "scope 외 새 TRIGGER 출현"으로 직관적 정의 가능.
- **단점**
  - 본 시점에 알려진 단점 없음. 미래 signal 추가 시 §3.2 표 재검토 필요(D7).

#### B. A + `SUSPICIOUS_APP_INSTALLED`

- **장점**: app-derived TRIGGER 일관 처리(외형적 단순성).
- **단점**: SUSPICIOUS_APP_INSTALLED는 broadcast → 5s hold → empty 자동 리셋(`RealAppInstallRiskMonitor.kt:64-73`)이라 REC-REFIRE 패턴이 구조적으로 없음. **추가 보호 효과 0**, 단순성 tie-breaker 위반(필요 없는 scope 확장). D4 위반.

#### C. 모든 TRIGGER (B + `TELEBANKING_AFTER_SUSPICIOUS`)

- **장점**: TRIGGER 일률 처리.
- **단점**: TELEBANKING_AFTER_SUSPICIOUS는 UPGRADE_TRIGGERS에 속해 **escape 측**으로 이미 의미가 박혀 있다(coordinator line 79-83 주석). 게이트 scope에 포함하면 escape predicate(#4)와 정면 충돌(D5 위반) — 같은 signal이 "억제 대상" + "escape 트리거" 동시 보유. F2 risk 직접 도입.

#### D. 모든 signal

- **장점**: 단일 변수로 표현.
- **단점**: PASSIVE/AMPLIFIER 포함 시 D3 위반. CALL_DERIVED_SIGNALS 포함 시 D2 위반(call-axis 책임 중첩). HIGH_RISK_DEVICE_ENVIRONMENT는 modifier로 표시되어 단독 액션 자격 없음 — scope 의미 자체에 부합 안 함. **명백 부적합**.

### 3.5. #3 결정

- **scope = `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}`**
- 표현 후보(코드 단계에서 확정, 본 문서 범위 밖):
  - 명시 set 상수 (예: `REC_REFIRE_REPEATABLE_TRIGGERS`)
  - 또는 기존 set 파생 — `UPGRADE_TRIGGERS - {TELEBANKING_AFTER_SUSPICIOUS}` (D6 친화, 다만 UPGRADE_TRIGGERS 의미 변경에 결합되는 위험은 인지)
  - 본 시점 권장: 의미가 다른 두 axis(억제 대상 vs escape 측)를 한 set의 차집합으로 묶지 말고 **별도 set 명시 정의**. 단순성 tie-breaker는 "동일 의미 시" 적용 — 두 set의 의미 축이 다르므로 이름 분리가 의미 일치성 측면에서 더 단순.
- snapshot 구성 시점: S2 게이트가 액션 트리거를 발동시키는 그 tick의 `appSignals` 중 위 scope에 속한 signal의 부분집합. 그 외 signal(call/install/deviceEnv/PASSIVE all)은 snapshot에 들어가지 않으며 게이트 판정에 사용되지 않는다.
- 부분집합 판정 식(가설, #4에서 확정):
  ```
  this_tick.appSignals ∩ scope ⊆ snapshot.appSignals ∩ scope
    AND no new UPGRADE_TRIGGER outside snapshot.appSignals
    → suppress
  else → escape
  ```
  call signal / install signal / deviceEnv signal은 본 식에 입력되지 않는다 (각 axis가 자체 책임).

### 3.6. D1~D7 재검증

| ID | 만족 | 근거 |
|---|---|---|
| D1 | ✓ | scope ⊇ {REMOTE_CONTROL, BANKING_AFTER_REMOTE} |
| D2 | ✓ | scope ∩ CALL_DERIVED_SIGNALS = ∅, TELEBANKING 미포함 |
| D3 | ✓ | scope 원소 모두 TRIGGER |
| D4 | ✓ | one-shot TRIGGER 2종 모두 미포함 |
| D5 | ✓ | UPGRADE_TRIGGERS \ scope = {TELEBANKING_AFTER_SUSPICIOUS} → escape predicate에서 새 trigger로 자연 분류 가능 |
| D6 | ✓ | 기존 RiskSignal enum 원소 2개 명시 set으로 표현 |
| D7 | ✓ | §3.2 표 + 5s/30s cadence 검증으로 신규 신호 자동 판정 절차 문서화 |

### 3.7. 다음 결정 포인트 입력값

- **#4 (escape 조건)** — "snapshot 외 새 TRIGGER" 정의가 본 scope 결정으로 자연 도출. 후보:
  - `(this_tick.signals ∩ UPGRADE_TRIGGERS) \ snapshot.signals ≠ ∅` → escape
  - 단독 `TELEBANKING_AFTER_SUSPICIOUS` 출현은 항상 escape(snapshot에 들어갈 수 없음 — D2/D4)
  - PASSIVE 변동은 escape 아님(D3 — 단독 액션 자격 없음)
- **#5 (α arm과 관계)** — α scope = 모든 누적 signal subset (`lastResetSignals`, `RiskSessionTracker.kt:97-98`), S2 scope = app-derived 2개. **scope subset + cadence(60s vs 30s) + wake-up 조건(safe-confirm vs 액션 발동) 모두 disjoint** → F5 검증 자연 통과 후보.
- **#6 (CTA 클릭과 연결)** — 본 결정으로 scope 정의에 CTA 입력이 0건임이 재확인됨(R2 보강). #6은 "CTA 시점이 게이트 read 또는 write의 입력이 될 수 있는가"만 추가 판정.
- **#7 (테스트 전략)** — 가짜 monitor가 위 2 signal의 5s/30s emit 시퀀스만 주입하면 S2 게이트 검증 충분. call/install/deviceEnv monitor는 stub만으로 OK. 가짜 clock + 위 2 signal 시퀀스 = 결정성 단위 테스트의 최소 표면.

---

## 결정 포인트 #4 — escape 조건

**전제 (계속 유효):**
- `"일단 닫기"` = dismiss-only 불변
- safe-confirm = 별도 전용 흐름 (홈 `"안전 확인했어요"`, Warning `"안전 확인"`)
- REC-REFIRE 억제 = CTA가 아닌 monitor/orchestrator 축의 debounce
- #1: layer = S2 (Coordinator collect 블록 내부 게이트)
- #2: N = 30,000ms, M 미도입
- #3: scope = `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}`
- 관찰 단위: trigger emission / coordinator 판단 tick (UI 액션 아님)

본 결정 포인트의 책임은 **"무엇이 현재 debounce를 무시하고 다시 사용자에게 보여야 하는가"의 식**을 정의하는 것이다. 이것은 "무엇이 위험한가"의 식이 아니다 — 위험 평가는 기존 evaluator/sessionTracker/alertStateResolver가 책임지고, 본 식은 오직 **S2 게이트가 자기 억제를 자발적으로 해제할 사유**만 정의한다.

### 4.1. "escape"가 가리키는 것 (먼저 정의)

S2 게이트가 활성인 상태에서 매 tick의 결정 분기는 다음 셋 중 하나로 귀결된다:

1. **suppress** — `this_tick.signals ∩ scope ⊆ snapshot.signals ∩ scope` AND escape predicate 거짓 → 액션 트리거 발동 안 함, 게이트 활성 유지
2. **escape** — escape predicate 참 → 게이트 즉시 해제, 액션 트리거 발동, 새 snapshot으로 게이트 재무장
3. **TTL 만료** — `now - lastFiredAt > N` → 게이트 자연 해제(escape는 아님), 다음 같은 신호가 들어오면 평소대로 처리

**escape predicate**는 (2)를 결정하는 식이다. (1)/(3)은 본 결정의 직접 책임이 아니며, predicate 거짓 + TTL 미만료 시 자동 적용된다.

**의미 못박음 (불변):** escape predicate는 **"억제를 즉시 깨야 하는 사건"의 정의**이지, **"위험 신호 일반의 정의"**가 아니다. 게이트가 자발적으로 침묵을 푸는 사유 외에는 들어가지 않는다 — 위험 표시 자체는 게이트와 무관하게 별도 평가/누적 경로가 책임진다.

**판정 단위 못박음 (불변):** escape의 판정 단위는 **RiskLevel/AlertState 변화가 아니라, snapshot 대비 이번 tick의 UPGRADE_TRIGGER 집합 delta**다. "위험도가 올랐다"는 escape 사유가 아니다 — UPGRADE_TRIGGER set의 새 원소 출현(set delta)만이 사유다. 이 분리를 통해 evaluator/alertStateResolver의 점수 변화나 레벨 상승이 escape 식에 미끄러져 들어오는 것을 구조적으로 차단한다.

### 4.2. escape predicate가 만족해야 할 요구사항

| ID | 요구 | 출처 | 측정 |
|---|---|---|---|
| **E1** | **TRIGGER만 escape 입력 자격** — 사용자 boundary는 modal surface 노출. PASSIVE/AMPLIFIER 단독 변동은 modal 발동 자격이 없으므로(`SignalCategory.kt:11`) escape 자격도 없음. PASSIVE 변동은 게이트와 무관하게 평소대로 평가된다. | D3, R3 | 정적: predicate가 받는 입력 set이 SignalCategory == TRIGGER인 원소만 포함. |
| **E2** | **결정성 — 단순 set 차집합으로 표현** — predicate는 `this_tick.signals` / `snapshot.signals` / 고정 set 1개의 set 연산만으로 결정. timestamp 비교, tick 번호 카운팅, 외부 상태 부수 입력 금지. | R6, T7, 단순성 tie-breaker | 정적: predicate body가 set 연산 단일 expression. |
| **E3** | **scope 내 새 원소 추가도 escape 자격** — snapshot=`{REMOTE_CONTROL}` 상태에서 `BANKING_APP_OPENED_AFTER_REMOTE_APP`가 새로 합류하면 정적 상황의 변화이므로 escape이어야 한다. F2(escape 부족) 회피 측면에서 핵심. | F2, R5 (snapshot 부분집합 위반은 escape) | 단위 테스트: snapshot subset 위반 케이스 escape 발동. |
| **E4** | **scope 외 UPGRADE_TRIGGER 출현 escape 자격** — TELEBANKING_AFTER_SUSPICIOUS는 scope에 들어가지 않지만(D2/D4) UPGRADE_TRIGGER이며 단독 발현 시 즉시 사용자 알림 가치가 있다. snapshot에 없던 UPGRADE의 새 출현은 escape. | RiskSessionTracker α 정책(line 28-32) 일관성, AlertState 규칙(`reference_alert_state_rules.md` "TELEBANKING_AFTER_SUSPICIOUS 단독 → CRITICAL forced") | 단위 테스트: snapshot에 TELEBANKING 없음 + this_tick에 TELEBANKING → escape. |
| **E5** | **α arm escape와 의미 일치** — α는 이미 UPGRADE_TRIGGERS \ armed snapshot을 escape 정의로 사용 중(`RiskSessionTracker.kt:28-32, 97-98`). S2도 동일 set을 의미 입력으로 채택해 두 layer의 escape 정책을 한 set으로 통일. 새 UPGRADE 추가 시 한 곳만 갱신. | R7(α와 의미 정합), 단순성 tie-breaker(D6 친화) | 정적: S2 escape predicate 입력 set == α UPGRADE_TRIGGERS. |
| **E6** | **escape ≠ 영구 비활성화** — escape 발동 시 즉시 액션 트리거 후 **새 snapshot으로 게이트 재무장**. 게이트가 죽고 다시 안 살아나는 게 아니라 "새 sample 기준으로 다시 N초 침묵"으로 전환. | T7, F1 회피 | 단위 테스트: escape 직후 같은 신호 재emit은 다시 suppress. |
| **E7** | **call-axis와 정책 일치** — TELEBANKING_AFTER_SUSPICIOUS escape 자격은 coordinator의 same-call snooze 정책(line 65-66 주석 "상위 trigger로 분류되어 snooze를 해제한다")과 동일 의미. 두 axis가 같은 signal에 같은 정책 적용. | F5 회피, axis 의미 일관성 | 정적: snooze pre-filter의 escape 조건과 S2 escape 조건이 TELEBANKING에 대해 동일 결과. |
| **E8** | **scope 외 비-UPGRADE TRIGGER는 escape 미자격** — `SUSPICIOUS_APP_INSTALLED`는 TRIGGER이지만 UPGRADE_TRIGGERS에 미포함(line 79-83). install axis가 자체 책임지므로 S2 escape 입력으로 끌고 들어오지 않음. | D4, axis 책임 분리 | 정적: S2 escape input ∩ {SUSPICIOUS_APP_INSTALLED} = ∅. |

E1+E2+E5가 predicate의 형식을 결정한다 — UPGRADE_TRIGGERS와의 set 차집합 단일 expression. E3/E4는 출력 동치성 검증 조건이다.

### 4.3. 후보 비교

E1~E8을 만족할 수 있는 자연 후보는 4가지.

#### P1 — `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS ≠ ∅`

"snapshot에 없던 UPGRADE_TRIGGER가 이번 tick에 새로 출현했는가"

- **장점**
  - α arm escape와 정확히 동일 식 (`RiskSessionTracker.kt:97-98`의 의미 그대로). E5 PASS.
  - scope 내 새 원소 추가도 자동으로 잡힘 — `UPGRADE_TRIGGERS ⊇ scope`이므로 snapshot=REMOTE 단독에서 BANKING_AFTER_REMOTE 추가 시 (this_tick \ snapshot) ∩ UPGRADE에 BANKING_AFTER_REMOTE 포함 → escape. E3 PASS.
  - scope 외 TELEBANKING 단독 출현 → snapshot에 미존재 → escape. E4 PASS.
  - PASSIVE/AMPLIFIER는 UPGRADE_TRIGGERS 외 → 자연 무관. E1 PASS.
  - SUSPICIOUS_APP_INSTALLED는 UPGRADE_TRIGGERS 외 → escape 자격 자체가 정의로 배제. E8 PASS.
  - set 차집합 단일 식, 결정성 자명. E2 PASS.
  - escape 후 새 snapshot 갱신은 이 식 자체엔 없으나 §4.4 결정에서 같이 명시. E6 행동은 별도.
- **단점**
  - α와 같은 set을 공유하므로 UPGRADE_TRIGGERS 변경 시 두 layer가 같이 영향받음 — 다만 이건 "한 곳만 갱신하면 됨"의 다른 면이며 의미상 정합 (E5의 의도 그 자체).

#### P2 — `TELEBANKING_AFTER_SUSPICIOUS ∈ (this_tick.signals \ snapshot.signals)` (TELEBANKING 단독)

"TELEBANKING이 새로 출현했을 때만 escape"

- **장점**: 가장 좁은 escape. 과escape 위험 0.
- **단점**: E3 위반 — snapshot=REMOTE 단독에서 BANKING_AFTER_REMOTE가 추가되어도 escape 안 함. 정적 상황 변화에 둔감. F2 risk 직접 도입.
- **단점**: E5 위반 — α는 3개 모두 escape인데 S2는 1개만 → 두 layer escape 의미 분기. axis 일관성 깨짐.

#### P3 — `(this_tick.signals ∩ TRIGGER) ≠ (snapshot.signals ∩ TRIGGER)` (모든 TRIGGER 변동)

"TRIGGER 카테고리 set의 변화 자체"

- **장점**: 단순 명료.
- **단점**: SUSPICIOUS_APP_INSTALLED 출현도 escape으로 끌고 옴 — install axis가 자체 책임지는 신호인데 S2 escape이 끼어들면 axis 책임 중첩(E8 위반).
- **단점**: TRIGGER set 정의가 RiskSignal enum에 implicit하므로 set 변경 시 영향 범위가 더 넓음. UPGRADE_TRIGGERS는 명시 set이라 통제성 더 좋음.

#### P4 — P1을 분해 명시 ("scope 내 새 원소" + "scope 외 UPGRADE 새 원소" 두 갈래)

predicate:
```
(this_tick.signals \ snapshot.signals) ∩ scope ≠ ∅
  OR
(this_tick.signals \ snapshot.signals) ∩ (UPGRADE_TRIGGERS \ scope) ≠ ∅
```

- **장점**: 가독성 — scope 안/밖 의도가 코드에 명시.
- **단점**: 출력 동치 — `scope ⊂ UPGRADE_TRIGGERS`이므로 두 갈래의 OR은 P1 단일 식과 정확히 같은 결과 집합. 식만 길고 결정 동일. 단순성 tie-breaker는 동치 표현 중 짧은 쪽 우선 → P1.

### 4.4. #4 결정

- **escape predicate (P1 채택):**
  ```
  (this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS ≠ ∅
  ```
  여기서 `UPGRADE_TRIGGERS = {REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP, TELEBANKING_AFTER_SUSPICIOUS}` — `RiskSessionTracker.kt:28-32` 및 `DefaultRiskDetectionCoordinator.kt:79-83` 두 곳에 이미 정의된 동일 set. **신설 set 0개**.
- **escape 발동 후 동작 (E6 명시):**
  1. 게이트 해제 (`lastFiredAt`/snapshot 무효화)
  2. 액션 트리거 발동 (정상 알림 경로 그대로)
  3. **액션 발동 직후** 게이트 재무장 — 새 snapshot = `this_tick.signals ∩ scope` (scope 외 signal은 snapshot에 들어가지 않음, #3 결정 그대로)
  - **snapshot write-order 못박음 (불변):** escape 직후 새 snapshot으로 재무장하는 순서가 식의 결정성과 분리될 수 없는 입력이다. 이 순서가 어긋나면 동일 UPGRADE_TRIGGER의 **지속 존재**만으로 다음 tick의 차집합에도 그 원소가 잡혀 매 tick escape가 재발한다(loop). 따라서 코드 단계의 호출 순서는 `escape 판정 → 액션 발동 → snapshot=this_tick.signals ∩ scope 즉시 갱신`이 단일 임계 경로다.
  - 즉 escape는 "한 번 깨우고 다시 N초 침묵"으로 자동 전환. 영구 비활성 아님.
- **set 표현 권장:** S2 코드는 `UPGRADE_TRIGGERS` 상수를 직접 참조 (코드 단계에서 import 위치만 결정). 두 layer가 같은 의미를 공유하므로 set의 truth source 1개 유지 — 단순성 tie-breaker 부합.
- **대안 표기 P4는 미채택** — 출력 동치 + 식 길이만 증가. 다만 코드 주석으로 "이 OR는 scope 내 새 원소와 scope 외 UPGRADE 새 원소를 모두 포함"의 의도를 명시하는 것은 권장(가독성, 코드 단계 자유 재량).

### 4.5. E1~E8 재검증

| ID | 만족 | 근거 |
|---|---|---|
| E1 | ✓ | UPGRADE_TRIGGERS 모든 원소 category == TRIGGER |
| E2 | ✓ | set 차집합 + set 교집합 단일 expression. timestamp/tick number 입력 0 |
| E3 | ✓ | UPGRADE_TRIGGERS ⊇ scope이므로 scope 내 새 원소 추가가 자동 escape |
| E4 | ✓ | TELEBANKING ∈ UPGRADE_TRIGGERS, scope 외라도 차집합에 포함 |
| E5 | ✓ | α arm UPGRADE escape 식과 동일 형식, 동일 set |
| E6 | ✓ | §4.4 발동 후 동작 명시 — escape 후 즉시 재무장 |
| E7 | ✓ | snooze pre-filter의 "TELEBANKING은 snooze 해제" 정책과 결과 일치 (TELEBANKING 출현 시 두 axis 모두 침묵 해제 방향) |
| E8 | ✓ | SUSPICIOUS_APP_INSTALLED ∉ UPGRADE_TRIGGERS → escape 입력 자격 없음 |

### 4.6. 다음 결정 포인트 입력값

- **#5 (α arm과 관계)** — α와 S2는 **같은 UPGRADE_TRIGGERS 차집합 식**을 escape에 사용한다. 차이는 wake-up trigger와 snapshot 입력:
  - α: wake-up = safe-confirm, snapshot = `lastResetSignals`(전체 누적 signal subset), TTL = 60s
  - S2: wake-up = 액션 발동, snapshot = scope 한정 signal, TTL = 30s
  - **escape 식은 같고 입력 snapshot/TTL/wake-up은 disjoint** → F5(α와 동일 변수 이중화) 회피 자연 통과. 본 결정으로 #5의 핵심 정합 기둥 확보.
- **#6 (CTA 클릭과 연결)** — escape predicate 입력에 CTA 시점 0건 (set 연산은 signal-set 입력만). #6은 "CTA가 입력 신호로라도 기여 가능한가"만 추가 판정.
- **#7 (테스트 전략)** — 단위 테스트 최소 표면:
  - escape positive: snapshot=`{REMOTE_CONTROL}` + this_tick=`{REMOTE_CONTROL, BANKING_AFTER_REMOTE}` → escape
  - escape positive: snapshot=`{}` + this_tick=`{TELEBANKING_AFTER_SUSPICIOUS}` → escape
  - escape negative: snapshot=`{REMOTE_CONTROL}` + this_tick=`{REMOTE_CONTROL}` → suppress
  - escape negative (PASSIVE 변동): snapshot=`{REMOTE_CONTROL}` + this_tick=`{REMOTE_CONTROL, UNKNOWN_CALLER}` → suppress (UNKNOWN_CALLER ∉ UPGRADE)
  - escape negative (install): snapshot=`{REMOTE_CONTROL}` + this_tick=`{REMOTE_CONTROL, SUSPICIOUS_APP_INSTALLED}` → suppress (SUSPICIOUS_APP_INSTALLED ∉ UPGRADE)
  - escape 후 재무장: escape → 다음 tick 같은 signal → suppress

---

## 결정 포인트 #5 — α arm과의 관계

**전제 (계속 유효):**
- `"일단 닫기"` = dismiss-only 불변
- safe-confirm = 별도 전용 흐름 (홈 `"안전 확인했어요"`, Warning `"안전 확인"`)
- REC-REFIRE 억제 = CTA가 아닌 monitor/orchestrator 축의 debounce
- #1: layer = S2 (Coordinator collect 블록 내부 게이트)
- #2: N = 30,000ms, M 미도입
- #3: scope = `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}`
- #4: escape predicate = `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS ≠ ∅`, escape 후 즉시 재무장
- 관찰 단위: trigger emission / coordinator 판단 tick (UI 액션 아님)

### 5.1. 본 결정의 책임 정의

#4까지의 결과가 "S2의 escape 식은 α arm과 같은 UPGRADE_TRIGGERS set을 참조한다"까지 도달했다. 그러나 **"식이 같다"는 두 layer가 안전하게 공존한다는 보증이 아니다**. 본 결정의 책임은 식의 표면적 동일성이 아니라, **두 layer가 실제로 읽고 쓰는 상태 변수 / 호출 lifecycle / 입력 시점이 disjoint한가**를 검증하는 것이다.

이 검증을 통해 F5("α와 S2가 사실상 같은 변수가 두 개가 되어 truth source 이중화"; §1.5 F5)를 회피하거나, 회피 불가하면 흡수/참조를 검토한다.

검증 축은 5개로 고정한다:

1. wake-up trigger source — 누가/언제 게이트를 활성화하는가
2. snapshot source — 게이트가 무엇을 비교 기준으로 저장하는가
3. TTL source — 시간 경계를 어느 상수가 결정하는가
4. reset path — 게이트가 어떤 경로로 해제·재무장되는가
5. escape predicate source — 식이 어디에 살고 무슨 입력을 받는가

각 축이 disjoint하면 두 layer는 공존(coexist) 가능, disjoint하지 않으면 흡수(absorb)/참조(reference)/축 갱신을 검토.

### 5.2. 5개 축 분리 검증

#### 축 1 — wake-up trigger source

| layer | 활성화 시점 | 호출자 | 코드 위치 |
|---|---|---|---|
| **α** | 사용자가 safe-confirm CTA를 누른 시점 | `RiskOverlayManager`(B-3/B-4 경로) / `HomeViewModel`(홈 "안전 확인했어요") / `WarningViewModel`(Warning "안전 확인") 외부 호출자 | `RiskSessionTracker.kt:260` `resetAfterUserConfirmedSafe()` |
| **S2** | Coordinator collect 블록이 modal surface 발동을 결정하는 그 tick | Coordinator 자기 자신 (외부 호출자 없음) | `DefaultRiskDetectionCoordinator.kt:160` collect 블록 내부, 액션 발동 분기 옆 |

**판정: disjoint ✓** — α는 외부(UI 핸들러)에서 호출되어 활성화, S2는 내부(Coordinator)에서 자기 활성화. 호출자·시점·위치 모두 분리. CTA 핸들러는 S2 wake-up에 0건 기여(R2 보강).

#### 축 2 — snapshot source

| layer | snapshot 입력 | 시점 | 범위 |
|---|---|---|---|
| **α** | 직전 종료된 세션의 `accumulatedSignals` 전체 (call + app + device 모두 포함) | safe-confirm CTA 호출 시각 | 세션 누적 전체 (`RiskSessionTracker.kt:262-264` `snapshot = current.accumulatedSignals` → `lastResetSignals`) |
| **S2** | `this_tick.signals ∩ scope` (#3 결정으로 scope 외 signal은 들어가지 않음) | Coordinator가 액션을 발동시키는 그 tick의 raw signal | scope 한정 (앱 기반 2종) |

**판정: disjoint ✓** — α snapshot은 "세션 누적 전체", S2 snapshot은 "이번 tick의 scope 한정". 입력 set의 의미와 시점이 모두 다르며, 두 변수가 같은 시각에 같은 값을 가리키더라도 그것은 **우연 일치**이지 변수 합치기가 아니다.

#### 축 3 — TTL source

| layer | 상수 | 값 | 위치 |
|---|---|---|---|
| **α** | `ALPHA_TTL_MS` | 60,000 ms | `RiskSessionTracker.kt:22` |
| **S2** | 별도 신설 상수 (코드 단계 명명, 후보 `S2_DEBOUNCE_TTL_MS`) | 30,000 ms (#2 결정) | Coordinator 또는 추출된 작은 클래스의 companion |

**판정: disjoint ✓** — 두 상수가 별도 선언되며, 값도 다름. #2 결정이 "α 상수와 별도 상수 유지(공용화 금지)"를 명시한 이유가 본 축의 disjoint 보장임. **같은 값이 되더라도 의미 축이 다르므로 공용화하지 않는다** — Step 1.5 §2.1 의미 축 분리 선언 보존.

#### 축 4 — reset path

| layer | 해제 경로 | 재무장 경로 |
|---|---|---|
| **α** | (a) TTL 만료 시 `update()` 진입부 자동 disarm (`RiskSessionTracker.kt:91-93` `lastResetAt = null` + `lastResetSignals = emptySet()`)<br>(b) suppress 조건 위반(escape) 시 그냥 정상 세션 생성 흐름으로 빠져나감 — 게이트는 disarm되지 않은 채 다음 update에서 다시 평가됨 (단, 새 세션이 생성되면 의미상 무력화)<br>(c) 명시 disarm 호출 없음 | 새 safe-confirm CTA 호출 시 `resetAfterUserConfirmedSafe()`가 `lastResetAt` + `lastResetSignals` 갱신 (line 262-264). **외부 트리거 의존**. |
| **S2** | (a) TTL 만료 시 자연 해제<br>(b) escape predicate 참 시 즉시 해제 + **액션 발동 직후 자체 재무장** (#4 §4.4 snapshot write-order) | 매 액션 발동마다 자기 참조로 재무장. **외부 트리거 의존 0건**. |

**판정: disjoint ✓** — α는 재무장이 외부(safe-confirm CTA)에 의존, S2는 자체 참조로 재무장. 두 layer의 lifecycle이 외부 의존도 측면에서 정반대 — α는 "사용자 개입 시점에만 재무장", S2는 "Coordinator가 액션을 낼 때마다 재무장". 같은 시각에 두 게이트가 동시 disarm/rearm되는 사건이 일어나려면 "Coordinator가 액션을 발동시키는 그 tick에 사용자가 동시에 safe-confirm CTA를 누른" 우연이어야 하므로 사실상 일어나지 않음.

#### 축 5 — escape predicate source (정밀 검증)

| layer | predicate 식 | 입력 변수 | 위치 |
|---|---|---|---|
| **α** | `appSet ⊄ armedSignals` **OR** `(appSet ∩ UPGRADE_TRIGGERS) ⊄ armedSignals`<br>(코드: `RiskSessionTracker.kt:97-98` `appSet.all { it in armedSignals } && upgradeNew.isEmpty()`의 부정) | `appSet` (이번 update의 appSignals), `armedSignals` (= `lastResetSignals`) | `RiskSessionTracker.update()` 내부 |
| **S2** | `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS ≠ ∅` | `this_tick.signals` (Coordinator combined signals), S2 snapshot (scope 한정) | Coordinator collect 블록 내부 |

**중요한 정정 — 두 식은 동일하지 않다.**

- **α는 broader**: PASSIVE/AMPLIFIER 새 원소가 추가되어도 subset 위반으로 escape함. 의미 — α는 **세션 respawn 자체**를 막는 책임이라 새 PASSIVE 출현(예: 새 통화 UNKNOWN_CALLER)은 새 세션 생성 의미가 있어 escape이어야 함.
- **S2는 narrower**: UPGRADE 차집합만 escape, PASSIVE 변동은 무관(E1/D3). 의미 — S2는 **modal surface 재발동**만 막는 책임이라 PASSIVE 변동은 modal 발동 자격이 없으므로(D3) 무관.

**즉 두 식이 다른 것이 의미상 정합** — 책임 축이 다르기 때문에 식이 같으면 오히려 의미 충돌. 둘은 **같은 `UPGRADE_TRIGGERS` set을 참조한다는 점만 공유**하고, 식 형태와 입력 변수와 위치는 모두 disjoint.

**판정: disjoint ✓** — 식 위치(파일 다름), 식 형태(broader vs narrower), 입력 변수(다른 set scope) 모두 분리.

**§4 §4.4 표현 정정 노트**: §4.4에 "α arm escape와 정확히 동일 식"이라는 표현이 있다. 이는 **set 참조 동일성**을 의미한 것이지 **식 동일성**을 의미한 것이 아니다 — 본 §5.2 축 5에서 식 자체는 broader vs narrower로 분리됨이 확정되었다. 코드 단계의 주석에는 "α와 S2는 같은 `UPGRADE_TRIGGERS` set을 참조하지만 책임 축이 달라 predicate 형태는 의도적으로 다르다"가 정확한 표현이다.

### 5.3. F5 위험 평가

F5(§1.5) 정의 = "α와 S2의 snapshot이 같은 시점·같은 값을 가리키게 되어 truth source 이중화".

| 검증 항목 | 결과 | 근거 |
|---|---|---|
| 같은 변수가 둘이 되는가? | **NO** | α 변수 = `lastResetAt`/`lastResetSignals` (RiskSessionTracker private). S2 변수 = Coordinator collect 블록 scope의 별도 게이트 변수. 위치·owner·visibility 모두 다름. |
| 같은 시점에 같은 값을 가리킬 수 있는가? | 가능 (우연) | 둘 다 REMOTE_CONTROL_APP_OPENED를 포함할 수 있음. 그러나 snapshot 시점·입력 set이 다르므로 같은 값 일치는 우연이지 변수 합치기 아님(축 2/4 disjoint). |
| truth source가 이중화되는가? | **NO** | α의 의미 축 = "safe-confirm 후 잔여 session respawn 억제". S2의 의미 축 = "라이브 modal 재발동 억제". 두 truth source가 답하는 질문이 다름. |
| 두 변수의 wake-up이 같은 사건에서 동시 발생하는가? | **NO** | α wake-up = safe-confirm CTA, S2 wake-up = Coordinator 액션 발동. 사건 종류가 disjoint. |

**결과: F5 회피 ✓**. 두 layer는 형식적·실질적으로 분리됨.

### 5.4. 관계 결정 — 흡수 / 참조 / 공존

세 후보 검토:

#### O1 — 공존 (coexist, 두 변수 분리 유지)

- 각 layer가 자기 변수를 자기 위치에서 read/write. 다른 layer 변수는 건드리지 않음.
- `UPGRADE_TRIGGERS` set만 의미 단일 truth source로 공유 (현재 RiskSessionTracker.kt:28-32 + Coordinator line 79-83 두 곳에 중복 선언 — 코드 단계에서 공용화 검토 가능, 메모리 "UPGRADE_TRIGGERS 공용화 임계 = 3번째 참조 시" 정책 도달).
- 단순성 tie-breaker 부합 — 새 추상 0개, 두 layer가 자기 책임 안에서 자기 데이터만 다룸.

#### O2 — 흡수 (S2 의미를 α 안으로 흡수)

- α 변수에 S2 의미를 추가 — 하나의 게이트가 "safe-confirm 후 잔여" + "라이브 재emit" 둘 다 책임.
- **거부**: 축 1 disjoint(wake-up source 다름) 때문에 한 변수로는 두 wake-up 논리를 동시에 표현 불가. 표현하려면 wake-up 분기 + TTL 분기 + snapshot 입력 분기 → 단일 변수가 사실상 두 변수의 합집합이 됨. F5의 정의 그 자체가 발생.
- 책임 축이 다른데 한 곳에 합치는 것은 Step 1.5 §2.1 의미 축 분리 선언 정면 위반.

#### O3 — 참조 (S2가 α 변수를 read)

- S2가 escape 판정 시 `lastResetSignals`/`lastResetAt`을 read해서 보조 입력으로 사용.
- **거부**: α 변수는 `RiskSessionTracker` private. 노출하려면 `internal` 승격 또는 게터 추가 → 캡슐화 약화 + truth source 모호("S2가 α를 본다"가 의미인가, "공유 진실인가").
- 단순성 tie-breaker 위반 — 두 layer 사이에 cross-read가 생기면 디버깅/테스트 표면 폭증.

### 5.5. #5 결정

- **α arm과 S2 = 공존 (O1 채택). 흡수/참조 미채택.**
- 두 layer는 5개 축 모두에서 disjoint이며, F5 위험 부재.
- **공유하는 것은 `UPGRADE_TRIGGERS` set 1개뿐** — 의미("자동 escape 대상 trigger 집합")가 같으므로 truth source 1개.
- **CTA 핸들러는 S2 변수에 read/write 0건** — 축 1 disjoint로 구조적 보장 (R2 + #6 입력값).
- **#4 §4.4 표현 정정**: "α arm escape와 정확히 동일 식" 표현은 set 참조 동일성을 의미한 것이며, 식 형태는 broader(α) vs narrower(S2)로 의도적 분리. 코드 주석은 본 §5.2 축 5의 정확한 표현을 따른다.

**공존의 의미 못박음 (불변):** α와 S2는 **같은 효과를 두 번 내는 중복장치가 아니다**. 서로 다른 질문에 답하는 별도 억제층이다 — α는 "safe-confirm 후 사용자가 안전을 확인했다고 명시한 직후, 같은 정적 상황의 새 세션이 자동 respawn하는 것을 막는가"의 답이고, S2는 "라이브 monitor가 같은 상황을 5s 단위로 재emit할 때 modal surface를 매번 다시 띄울 것인가"의 답이다. 두 질문이 다르므로 두 답(억제층)이 공존하는 것이 정합이다.

**공용화 임계 처리 (본 결정 범위 외):** S2 추가가 `UPGRADE_TRIGGERS` set의 3번째 참조 위치가 되어 메모리 정책의 공용화 임계에 도달함을 **follow-up 후보로 기록만** 한다. 본 #5 문서에서는 공용화 여부를 결정하지 않는다. #5의 범위는 α/S2 관계 판정이지 set 공용화 설계가 아니다 — follow-up 신호와 현재 결정 범위를 섞지 않는다. 공용화 위치/시점/방식은 코드 단계 또는 별도 작업선에서 다룬다.

### 5.6. 다음 결정 포인트 입력값

- **#6 (CTA 클릭과 연결)** — 본 결정으로 CTA가 S2 변수에 0건 기여함이 축 1로 재확정. #6은 "CTA가 S2의 입력 신호 중 하나라도 될 수 있는가"만 추가 판정 — 본 결정 이후 거의 자동 NO.
- **#7 (테스트 전략)** — 두 단위 테스트 클래스가 disjoint해야 분리 검증:
  - α는 기존 `RiskSessionTrackerAlphaTest.kt` 그대로
  - S2는 별도 신설 (가짜 clock + scope 시퀀스 + UPGRADE 시퀀스)
  - 두 테스트가 서로의 truth source에 의존하지 않음을 정적 검증 (테스트 클래스 import 측 분리)
- **코드 단계 후속 (본 문서 범위 밖)**: `UPGRADE_TRIGGERS` 3중 참조 도달 → 공용화 진입 검토 가능. 단순성 tie-breaker는 "동일 의미면 단일 truth source"이므로 공용화 부합. 다만 공용화 위치(어느 파일/패키지)는 코드 단계 자유 재량.

---

## 결정 포인트 #6 — CTA 클릭과의 연결 방식

**전제 (계속 유효):**
- `"일단 닫기"` = dismiss-only 불변
- safe-confirm = 별도 전용 흐름 (홈 `"안전 확인했어요"`, Warning `"안전 확인"`)
- REC-REFIRE 억제 = CTA가 아닌 monitor/orchestrator 축의 debounce
- #1: layer = S2 (Coordinator collect 블록 내부 게이트)
- #2: N = 30,000ms, M 미도입
- #3: scope = `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}`
- #4: escape predicate = `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS ≠ ∅`, escape 후 즉시 재무장
- #5: α/S2 공존, 5축 disjoint, 같은 `UPGRADE_TRIGGERS` set만 공유, predicate 식 broader/narrower로 의도적 분리
- 관찰 단위: trigger emission / coordinator 판단 tick (UI 액션 아님)

### 6.1. 본 결정의 책임 정의

R2(#1 §1.3) + 축 1(#5 §5.2)는 이미 **"S2 변수의 read/write 위치는 Coordinator 내부에 있고 CTA 핸들러는 그 위치에 손대지 않는다"**를 보장한다. 그러나 본 결정의 책임은 R2의 단순 재확인이 아니라 **"CTA가 S2의 입력으로 어떤 형태로든 기여할 수 있는가"의 직접/간접 두 축 모두 검증**하는 것이다.

가설은 NO 방향이지만 단정하지 않는다. 검증을 거친 후에만 NO를 결정으로 채택한다. 검증 축:

1. **직접 축** — CTA 핸들러가 S2의 read/write/wake-up 자리에 직접 접근하는가
2. **간접 축** — CTA 핸들러가 호출하는 부수효과 함수들이 S2 입력 변수(this_tick.signals, snapshot, lastFiredAt)에 어떤 형태로든 영향을 주는가

직접 축이 깨끗해도 간접 축에서 결합이 발견되면 R2의 보장은 표면적 보장에 불과하므로 추가 격리 조치를 검토해야 한다.

### 6.2. S2의 입력 surface 명시 (검증 대상 정의)

S2 게이트가 매 tick에서 read/write하는 자리는 정확히 다음 셋이다:

| 자리 | 의미 | 출처 |
|---|---|---|
| **I1** `this_tick.signals` | Coordinator collect 블록의 combined signals — `callSignals + appSignals + installSignals + deviceEnvSignals` (DefaultRiskDetectionCoordinator.kt:151-160) | monitor layer가 emit (`RealCallRiskMonitor`, `RealAppUsageRiskMonitor`, `RealAppInstallRiskMonitor`, `DeviceEnvironmentRiskMonitor`) |
| **I2** `snapshot` | S2 자기 변수 — 직전 액션 발동 시점의 `this_tick.signals ∩ scope` | S2 자체 (Coordinator 내부 변수) |
| **I3** `lastFiredAt` | S2 자기 timestamp — 직전 액션 발동 시각 | S2 자체 |

추가로 wake-up 자리:

| 자리 | 의미 |
|---|---|
| **W1** Coordinator 액션 발동 분기 진입 | 매 tick의 평가 결과로 modal surface 발동이 결정되는 그 자리에서 S2 게이트가 평가됨 |

검증 방향: CTA 핸들러가 I1/I2/I3/W1 어느 자리에든 직접 또는 간접으로 영향을 주는가.

### 6.3. CTA 핸들러의 호출 surface 매핑

코드 검증 결과 본 앱에서 CTA 핸들러가 호출하는 함수는 두 그룹으로 나뉜다.

**Group A — safe-confirm 경로 (홈 "안전 확인했어요" / Warning "안전 확인" / 팝업 보조 CTA):**

| 함수 | 위치 | 변경 대상 |
|---|---|---|
| `resetAfterUserConfirmedSafe()` | `RiskSessionTracker.kt:260` | α 변수 (`lastResetAt`, `lastResetSignals`), session 종료, snooze clear |
| `clearTelebankingAnchor()` | `RealCallRiskMonitor` | call monitor의 텔레뱅킹 anchor 변수 |
| `refreshAnchorHotNow()` | `DefaultRiskDetectionCoordinator` | coordinator의 anchorHot StateFlow mirror (UI 표면) |
| `clearCurrentRiskEvent()` | `RiskEventSink` | top-level `currentRiskEvent` |
| `snoozeForCall(callId)` | `RiskSessionTracker` (B-3/B-4 경로) | sessionTracker의 `snoozedCallId`, `snoozedAt` (call signal pre-filter) |
| `markCurrentCallConfirmedSafe(callId)` | `RealCallRiskMonitor` (B-3 경로 한정) | call monitor의 `safeConfirmedCallId` (`shouldApplyCallSafeEffects` allowlist) |
| `dismiss()` | `RiskOverlayManager` | overlay view 표시 상태 |

호출 묶음은 `RiskOverlayManager.performSafeCtaSideEffects` (line 590-608) pure function으로 추출되어 있다.

**Group B — dismiss-only 경로 ("일단 닫기" / "전화 앱으로 이동"):**

| 함수 | 위치 | 변경 대상 |
|---|---|---|
| `dismiss()` | `BankingCooldownManager` 또는 `RiskOverlayManager` | 자체 view 표시 상태 |

Group B는 의도상 view 닫기 외의 부수효과 0건 (#1 §1.1 dismiss-only 불변).

### 6.4. 직접 coupling 검증 (축 1)

각 CTA 핸들러가 S2 입력 자리(I1/I2/I3/W1)에 **직접** 접근하는가:

| CTA 그룹 | I1 (this_tick) | I2 (snapshot) | I3 (lastFiredAt) | W1 (액션 발동 진입) |
|---|---|---|---|---|
| Group A (safe-confirm) | NO | NO | NO | NO |
| Group B (dismiss) | NO | NO | NO | NO |

근거:
- I1은 monitor flow의 emit 결과로만 채워짐. CTA 핸들러는 monitor에 emit을 강제하지 않음.
- I2/I3은 Coordinator 내부 변수로 S2 자체 외에 노출되지 않음 (R2 + #5 축 1).
- W1은 Coordinator collect 블록의 평가 결과로만 진입. CTA 핸들러가 임의로 modal 발동을 트리거하지 않음.

**판정: 직접 coupling 0건 ✓**.

### 6.5. 간접 coupling 검증 (축 2)

Group A 함수들이 변경하는 변수가 S2 입력 자리(I1/I2/I3/W1) 중 하나에 어떤 경로로든 영향을 주는가:

| 함수 | 변경 변수 | 다음 tick의 I1에 영향? | 다음 tick의 I2/I3에 영향? | W1 트리거에 영향? |
|---|---|---|---|---|
| `resetAfterUserConfirmedSafe()` | α 변수 | NO — α는 sessionTracker 내부 별도 축, monitor emit이나 Coordinator combine에 입력 0건 | NO | NO (α suppress 시 session=null이 evaluator로 전파되어 alertState=OBSERVE → modal 발동 자격 없음. 이건 S2 게이트가 평가되기 전 단계에서 이미 차단되는 것이며, S2 입력 변수 자체엔 영향 없음) |
| `clearTelebankingAnchor()` | call monitor 텔레뱅킹 anchor | 간접 — anchor 클리어 시 후속 통화에서 TELEBANKING_AFTER_SUSPICIOUS 발화 자격이 사라짐. 다음 tick I1에서 TELEBANKING 미출현 가능. 단 TELEBANKING ∉ scope (#3 D4) → I2 ∩ scope에 영향 0. TELEBANKING ∈ UPGRADE_TRIGGERS이지만 미출현 시 escape 식 (this_tick \ snapshot) ∩ UPGRADE의 입력에서도 TELEBANKING 빠짐 → escape 미발동, 즉 게이트 침묵 유지. **이는 정상 의미** — 사용자가 안전 확인했으므로 후속 텔레뱅킹 가짜 트리거를 막는 것이 의도. S2 결과는 변하지 않음 (suppress 유지). | NO | NO |
| `refreshAnchorHotNow()` | anchorHot StateFlow mirror | NO — UI 표면 동기화. monitor emit이나 combined signals에 입력 0건 | NO | NO |
| `clearCurrentRiskEvent()` | top-level currentRiskEvent | NO — eventSink는 RiskEvent 표시용 surface. monitor emit/combined signals/sessionTracker.update()에 입력 0건 | NO | NO (riskEvent 클리어가 alertState 평가 입력은 아님. evaluator는 session.accumulatedSignals + score를 봄) |
| `snoozeForCall(callId)` | snoozedCallId | 간접 — coordinator pre-update filter (line 67-73 `CALL_DERIVED_SIGNALS`)가 해당 callId의 call-derived signal을 sessionTracker.update() 입력에서 제거. **그러나 S2가 보는 this_tick.signals (I1)는 combined signals이며**, snooze는 sessionTracker 진입 직전 필터일 뿐 I1 자체엔 영향 없음. 또한 snooze가 제거하는 set은 정의상 CALL_DERIVED_SIGNALS = `{UNKNOWN_CALLER, LONG_CALL_DURATION, UNVERIFIED_CALLER, REPEATED_UNKNOWN_CALLER, REPEATED_CALL_THEN_LONG_TALK}` — 모두 scope 외 + UPGRADE_TRIGGERS 외. TELEBANKING_AFTER_SUSPICIOUS는 snooze 대상 명시 제외(coordinator line 65-66 주석 "상위 trigger로 분류되어 snooze를 해제한다"). 따라서 **snooze가 적용되든 말든 S2 게이트의 두 입력 set(scope, UPGRADE_TRIGGERS) 어디에도 결과 변화 0**. | NO | NO |
| `markCurrentCallConfirmedSafe(callId)` | safeConfirmedCallId | 간접 — call monitor가 IDLE 진입 시 `lastSuspiciousCallEndedAt` 업데이트를 스킵 → 후속 텔레뱅킹 발신 시 TELEBANKING_AFTER_SUSPICIOUS 발화 자격 없음. 위 `clearTelebankingAnchor`와 같은 의미 경로의 다른 진입점. 결과: TELEBANKING ∉ scope + 미출현 시에도 S2는 기존 suppress 유지. | NO | NO |
| `dismiss()` | overlay view 상태 | NO — view 표시 토글. monitor emit/combined signals/Coordinator 평가 입력 0건 | NO | NO |

**중요 검증 결과**: Group A 부수효과 중 두 함수(`clearTelebankingAnchor`, `markCurrentCallConfirmedSafe`, `snoozeForCall`)가 다음 tick의 I1 set composition에 **간접 영향**을 줄 수 있다 — 그러나 그 영향은 모두 **scope 외 또는 UPGRADE_TRIGGERS 외 신호의 출현/미출현**에만 작용한다. S2 게이트의 두 입력 set(scope, UPGRADE_TRIGGERS) 어느 쪽에도 결과 변화를 만들지 않는다.

이 disjoint는 #3 결정(scope에서 call-derived/TELEBANKING 제외)과 UPGRADE_TRIGGERS 정의의 직접 결과다. CTA의 간접 부수효과가 S2에 닿지 않는 것은 우연이 아니라 **scope 정의가 의도적으로 그 경로를 disjoint하게 잘랐기 때문**이다.

**판정: 간접 coupling이 존재할 수 있는 경로는 식별되지만, S2 입력 set 정의(#3 + #4의 UPGRADE_TRIGGERS 차집합)가 그 경로의 결과를 흡수하여 S2 게이트 결과에 영향 0건 ✓**.

### 6.6. #6 결정

- **CTA → S2 = 입력 0건 (직접·간접 모두)**
- 직접 축: I1/I2/I3/W1 어느 자리에도 CTA 핸들러가 직접 접근하지 않음. R2 구조적 보장.
- 간접 축: Group A 부수효과가 다음 tick I1 set composition에 영향을 줄 수 있는 경로는 3건 식별됨(`clearTelebankingAnchor`, `markCurrentCallConfirmedSafe`, `snoozeForCall`). 그러나 영향이 미치는 신호 set이 모두 (scope 외) ∩ (UPGRADE_TRIGGERS 외)이므로 S2 게이트 결과에 변화 0.
- 이 disjoint는 #3 scope 정의와 #4 UPGRADE_TRIGGERS 차집합 정의가 책임지는 결과다 — **CTA 격리는 R2의 단순 컴파일 차원 격리뿐 아니라 scope/escape 정의의 의미 차원 격리에 의해 이중 보호**된다.
- 즉 CTA 클릭은 S2의 입력이 아니라 **S2와 무관한 별도 axis의 사건**이며, 본 결정으로 그 사실이 직접·간접 두 축 모두에서 확정된다.

**"입력 0건"의 범위 못박음 (불변)**: 본 결정의 "0건"은 **S2의 결정 입력면(I1/I2/I3/W1)에 한정된 0건**이다. CTA가 다른 축(α 변수, call monitor anchor, eventSink, overlay view 등)에 부수효과를 가지는 것은 부정하지 않는다 — Group A 7개 함수가 그 부수효과 그 자체다. 본 결정이 못박는 것은 "CTA가 아무 시스템에도 영향을 주지 않는다"가 아니라, **"CTA의 부수효과가 존재하더라도 그것이 S2의 결정 입력(scope, escape 집합 delta)으로 환류되지 않는다"**이다. 이 구분이 무너지면 §6.5 간접 축 검증의 의미가 사라진다.

**이중 보호의 회귀 규칙 못박음 (불변)**: 향후 CTA 경로에 새 부수효과가 추가될 경우(예: 새 call-axis cleanup 함수, 새 session-axis 마킹 등), **아래 3개 검증을 모두 통과해야 한다**. 본 §6.5 표는 설명 자료가 아니라 회귀 검증 템플릿으로 고정된다 — 새 부수효과 1건 추가 시 §6.5 표에 1행 추가하고 아래 3 컬럼이 모두 NO인지 확인.

| 회귀 검증 항목 | 의미 | §6.5 표 대응 컬럼 |
|---|---|---|
| **C1 — S2 입력면 직접 접근 없음** | 새 부수효과 함수 본체가 I1/I2/I3/W1을 직접 read/write하지 않음 (R2 구조적 격리 유지) | 직접 축 (§6.4 표 전체 NO) |
| **C2 — scope 영향 없음** | 새 부수효과가 변경하는 변수가 다음 tick의 monitor emit에서 `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}` (scope) 출현/미출현에 결과 변화를 만들지 않음 | "다음 tick의 I1에 영향?" 중 scope 한정 NO |
| **C3 — escape 집합 delta 영향 없음** | 새 부수효과가 변경하는 변수가 `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS`의 결과 set에 변화를 만들지 않음 | "다음 tick의 I1에 영향?" 중 UPGRADE_TRIGGERS 한정 NO + "다음 tick의 I2/I3에 영향?" NO |

C1은 컴파일 차원 보호(R2), C2/C3는 의미 차원 보호(#3 scope + #4 UPGRADE 정의). 어느 한쪽이라도 NO를 보장하지 못하면 S2 격리는 깨진다 — 그 경우 본 §6의 "이중 보호" 명제 자체를 재검토해야 한다.

### 6.7. 다음 결정 포인트 입력값

- **#7 (테스트 전략)** — CTA 격리는 단위 테스트 표면에서도 별도 클래스로 분리되어야 한다.
  - S2 단위 테스트에는 CTA 호출이 등장하지 않음 (가짜 monitor signal 시퀀스만 주입). 즉 테스트 setup 단계에서 CTA 핸들러를 호출하지 않는 것이 일관성 검증.
  - α 단위 테스트(`RiskSessionTrackerAlphaTest.kt`)는 `resetAfterUserConfirmedSafe()`를 직접 호출하여 wake-up 시뮬레이션 — 이는 α의 정상 입력이므로 격리 위반 아님.
  - 두 테스트 클래스가 서로의 wake-up 함수를 호출하지 않음을 정적 검증.
- **부수효과 추가 시 회귀 검증 절차** — Group A에 새 함수 추가 시 §6.5 표 갱신 + S2 단위 테스트에 "해당 부수효과 발동 직후 다음 tick에 scope/UPGRADE 외 신호만 변동되었을 때 S2가 suppress 유지하는지" 케이스 추가.

---

## 결정 포인트 #7 — 테스트 전략

**전제 (계속 유효):**
- `"일단 닫기"` = dismiss-only 불변
- safe-confirm = 별도 전용 흐름 (홈 `"안전 확인했어요"`, Warning `"안전 확인"`)
- REC-REFIRE 억제 = CTA가 아닌 monitor/orchestrator 축의 debounce
- #1: layer = S2 (Coordinator collect 블록 내부 게이트). pure function으로 추출 가능 (R3).
- #2: N = 30,000ms, M 미도입. 가짜 clock + 단일 tick 부분집합 비교.
- #3: scope = `{REMOTE_CONTROL_APP_OPENED, BANKING_APP_OPENED_AFTER_REMOTE_APP}`
- #4: escape predicate = `(this_tick.signals \ snapshot.signals) ∩ UPGRADE_TRIGGERS ≠ ∅`. escape 후 즉시 snapshot 재무장.
- #5: α/S2 5축 disjoint 공존. `UPGRADE_TRIGGERS` set 1개만 공유.
- #6: CTA → S2 결정 입력면 0건 (직접·간접). 회귀 검증 C1/C2/C3 못박음.

### 7.1. 본 결정의 책임 정의

#1~#6에서 도출된 테스트 요청 사항들이 분산되어 있다 — #1 §1.6, #2 T6/§2.5, #3 §3.7, #4 §4.6, #5 §5.6, #6 §6.7. 본 결정의 책임은 이들을 **단일 테스트 표면 설계**로 통합하고, 사용자 지침 3건(필수 포함 항목)을 제약으로 포함하는 것이다.

핵심 책임 3가지:
1. S2 게이트의 결정성(determinism) 단위 테스트 표면 정의
2. α 단위 테스트와의 격리 정적 보장
3. CTA 부수효과의 음성 회귀 케이스 (C2/C3 회귀 규칙의 실행 가능 검증)

본 결정은 테스트 케이스 목록을 나열하는 단계가 아니라 **"어느 테스트 클래스가 어느 책임을 갖고, 그들 사이가 어떻게 disjoint한가"의 구조 결정**이다. 개별 테스트 케이스 목록은 §7.4 표가 정리한다.

### 7.2. 테스트 표면 요구사항 (T-요구)

#1~#6의 inputs를 통합하면 본 단계가 만족시켜야 할 요구사항은 다음과 같다.

| ID | 요구사항 | 출처 | 검증 단위 |
|---|---|---|---|
| **T-D1** | S2 게이트 판정식이 가짜 clock 입력에서 결정성 보장 | #1 §1.6 R3, #2 T6 | 단위 테스트 |
| **T-D2** | T-1ms / T=30,000 / T+1ms 경계 3점에서 정확한 분기 | #2 §2.5 | 시간 경계 케이스 |
| **T-D3** | scope 2 signal의 5s/30s emit 시퀀스로 REC-REFIRE 재현 가능 | #3 §3.7 | 가짜 monitor 시퀀스 |
| **T-D4** | escape positive 2건 + escape negative 3건 + 재무장 1건 = 최소 6건 | #4 §4.6 | escape 진리표 |
| **T-D5** | α 테스트와 S2 테스트가 서로의 truth source에 의존하지 않음 | #5 §5.6 | 정적 격리 |
| **T-D6** | S2 단위 테스트에 CTA 호출 등장 0건 | 사용자 지침 1, #6 §6.7 | 정적 격리 |
| **T-D7** | CTA 부수효과 발동 직후 다음 tick에서 scope/UPGRADE 외 신호만 변동 시 S2 suppress 유지 | 사용자 지침 2, #6 §6.6 (C2/C3 회귀 규칙) | 음성 회귀 케이스 |
| **T-D8** | α 테스트와 S2 테스트가 서로의 wake-up 함수를 호출하지 않음 | 사용자 지침 3 | 정적 검증 (테스트 구조 + lint/주석) |
| **T-D9** | 실기 30s/60s 경계 시나리오 (S2 30s vs α 60s 시간 비대칭이 사용자 관찰 표면에 어떻게 드러나는지) | #2 §2.5, #5 §5.2 축 3 | 실기 검증 항목 |

요구사항 사이 충돌 검사:
- T-D5 / T-D6 / T-D8은 모두 격리 방향 — 서로 강화. 충돌 없음.
- T-D6 (CTA 호출 0건) ∧ T-D7 (CTA 부수효과 음성 회귀) — 일견 모순 같으나, T-D7은 "CTA 핸들러를 호출하는 것"이 아니라 **"CTA 부수효과가 만든 변수 상태와 동일한 가짜 monitor signal 입력"을 주입하여 검증**하는 것으로 해소. 즉 부수효과의 결과(예: TELEBANKING이 나오지 않는 신호 시퀀스)를 가짜 monitor가 재현. CTA 핸들러 자체는 호출하지 않는다.

### 7.3. 후보 검토

본 결정은 "테스트를 한다/안 한다"가 아니라 "어떤 구조로 분리하는가"이므로 후보는 격리 형태로 분기.

| 후보 | 형태 | T-D 충족 | 단순성 |
|---|---|---|---|
| **Q1 — 단일 테스트 클래스 통합** | `RiskDebounceTest`에 α + S2 + CTA 부수효과 케이스 모두 통합 | T-D5, T-D6, T-D8 위반 (서로의 truth source가 같은 클래스 내 setup에 섞임) | 표면 단순. 격리 검증 불가. |
| **Q2 — 2클래스 분리** | α는 기존 `RiskSessionTrackerAlphaTest.kt`. S2는 신설 `S2DebounceTest.kt` (가칭). 양 클래스가 서로 import하지 않음. | T-D5, T-D6, T-D8 충족. T-D7은 S2 클래스 안 별도 케이스로. | α 클래스 기존 보존. S2만 신설. |
| **Q3 — 3클래스 분리** | α / S2 / CTA-부수효과 회귀 각 별도 클래스 | 격리는 더 강하지만 CTA 부수효과 회귀 케이스가 S2 결정성 검증과 같은 truth source(가짜 monitor + 가짜 clock)를 쓰므로 분리할 실익 없음. F3 (블록 크기) 관점에서도 과분리. | 표면 분산. 단순성 위반. |

### 7.4. #7 결정

- **테스트 표면 = Q2 (2클래스 분리). α 기존 클래스 보존, S2 신설 클래스 1개.**
- 두 클래스가 서로의 wake-up 함수 호출 0건, 서로의 truth source import 0건.
- T-D7 (CTA 부수효과 회귀)는 S2 클래스 내 별도 그룹으로 작성 — 부수효과 자체는 호출하지 않고 부수효과 후 monitor가 emit할 신호 시퀀스를 가짜로 주입하여 S2가 suppress 유지하는지 검증.

**테스트 케이스 인벤토리 (S2DebounceTest 가칭, 신설):**

| 그룹 | 케이스 | 입력 | 기대 결과 | 출처 T-D |
|---|---|---|---|---|
| **G1 시간 경계** | C-T1: 액션 발동 후 T=29,999ms 시점 동일 scope signal 재emit | snapshot=`{REMOTE_CONTROL}`, t-lastFired=29,999ms, this_tick=`{REMOTE_CONTROL}` | suppress | T-D2 |
| | C-T2: 액션 발동 후 T=30,000ms 시점 동일 scope signal 재emit | snapshot=`{REMOTE_CONTROL}`, t-lastFired=30,000ms, this_tick=`{REMOTE_CONTROL}` | TTL 경계 정의대로 처리 (구현 선택: ≥/>) — 본 결정에서는 30,000ms 경과 시 TTL 만료 = `>` 경계 채택 → suppress | T-D2 |
| | C-T3: 액션 발동 후 T=30,001ms 시점 동일 scope signal 재emit | snapshot=`{REMOTE_CONTROL}`, t-lastFired=30,001ms, this_tick=`{REMOTE_CONTROL}` | TTL 만료 → 액션 재발동 가능 (S2 침묵 해제) | T-D2 |
| **G2 escape 진리표** | C-E1: scope 내 새 trigger 추가 | snapshot=`{REMOTE_CONTROL}`, this_tick=`{REMOTE_CONTROL, BANKING_APP_OPENED_AFTER_REMOTE_APP}` | escape (delta ∩ UPGRADE = `{BANKING_AFTER_REMOTE}`) | T-D4 |
| | C-E2: scope 외 UPGRADE trigger 출현 | snapshot=`{}`, this_tick=`{TELEBANKING_AFTER_SUSPICIOUS}` | escape (delta ∩ UPGRADE = `{TELEBANKING}`) | T-D4 |
| | C-E3: 동일 signal 반복 (REC-REFIRE 본질) | snapshot=`{REMOTE_CONTROL}`, this_tick=`{REMOTE_CONTROL}` | suppress (delta = ∅) | T-D4 |
| | C-E4: PASSIVE 변동 추가 | snapshot=`{REMOTE_CONTROL}`, this_tick=`{REMOTE_CONTROL, UNKNOWN_CALLER}` | suppress (UNKNOWN_CALLER ∉ UPGRADE) | T-D4 |
| | C-E5: install one-shot 추가 | snapshot=`{REMOTE_CONTROL}`, this_tick=`{REMOTE_CONTROL, SUSPICIOUS_APP_INSTALLED}` | suppress (SUSPICIOUS_APP_INSTALLED ∉ UPGRADE) | T-D4 |
| | C-E6: escape 후 즉시 재무장 | C-E1 escape → 다음 tick `{REMOTE_CONTROL, BANKING_AFTER_REMOTE}` 동일 재emit | suppress (snapshot이 즉시 갱신되어 이번 delta = ∅) | T-D4, #4 §4.4 재무장 불변 |
| **G3 REC-REFIRE 시퀀스 재현** | C-R1: 5s 간격 6 tick 동일 scope signal | t=0/5/10/15/20/25s 모두 this_tick=`{REMOTE_CONTROL}` | t=0 발동, t=5/10/15/20/25 모두 suppress | T-D3 |
| | C-R2: 5s 간격 7 tick (30s 경계 통과) | t=0~30s 7 tick 동일 signal | t=0 발동, t=5~25 suppress, t=30+ε 재발동 | T-D3, T-D2 |
| **G4 CTA 부수효과 음성 회귀** | C-C1: TELEBANKING 부수효과 시뮬레이션 | snapshot=`{REMOTE_CONTROL}` 활성 상태에서 다음 tick this_tick=`{REMOTE_CONTROL}` (부수효과 후에도 scope 내 변동 없음) | suppress (S2 결과 변화 0 — C2 회귀) | T-D7 |
| | C-C2: snooze 후 CALL_DERIVED 신호 사라짐 시뮬레이션 | snapshot=`{REMOTE_CONTROL}` 활성, this_tick=`{REMOTE_CONTROL}` (UNKNOWN_CALLER가 직전엔 있다가 snooze 후 사라짐) | suppress (CALL_DERIVED ∉ scope ∪ UPGRADE → S2 결과 변화 0 — C3 회귀) | T-D7 |
| | C-C3: clearAnchor 후 TELEBANKING 미출현 시뮬레이션 | snapshot=`{REMOTE_CONTROL}` 활성, this_tick=`{REMOTE_CONTROL}` (직전 TELEBANKING 자격이 있었으나 clearAnchor로 자격 박탈) | suppress (TELEBANKING이 나오지 않으면 delta ∩ UPGRADE = ∅) | T-D7 |

**격리 정적 보장 (T-D5, T-D6, T-D8):**

| 정적 규칙 | 적용 대상 | 검증 방법 |
|---|---|---|
| **S2 클래스에 `RiskSessionTracker` import 금지** (단 truth source 비공유 의미) — α의 `lastResetAt`/`lastResetSignals`에 read 접근하지 않음 | `S2DebounceTest.kt` | 클래스 상단 import 정적 점검 (lint 또는 PR 체크) |
| **α 클래스에 S2 게이트 함수 import 금지** | `RiskSessionTrackerAlphaTest.kt` | 동일 import 정적 점검 |
| **S2 클래스에 CTA 핸들러(`HomeViewModel.confirmSafe`, `WarningViewModel.confirmSafe`, `RiskOverlayManager.dismiss`/`performSafeCtaSideEffects` 등) 호출 0건** | `S2DebounceTest.kt` | 클래스 본문 정적 점검 (식별자 검색) |
| **α 클래스에 S2 게이트 wake-up 함수(액션 발동 핸들러) 호출 0건** | `RiskSessionTrackerAlphaTest.kt` | 동일 정적 점검 |

이 4개 정적 규칙은 PR 단계 lint 또는 코드 리뷰 체크리스트로 운영. 자동화 어려운 규칙은 테스트 클래스 상단 주석 헤더로 명시(`// ISOLATION: do not import RiskSessionTracker / do not call CTA handlers — see #6 §6.6 + #7 §7.4`).

**판정 단위 못박음 (불변):** 본 #7의 모든 단위 테스트 입력은 **set + 가짜 clock**이다. RiskLevel/AlertState/HomeStatus 같은 상위 표면을 기대 결과로 검증하지 않는다. S2 게이트의 책임은 "이번 tick에서 액션 발동을 허용/억제"의 boolean 결정이므로 그 단위에서만 검증한다 — 상위 표면 검증은 별도 viewmodel/ui 테스트 책임.

**실기 검증 시나리오 (T-D9):**

단위 테스트와 분리하여 실기에서만 관찰 가능한 시간 비대칭 케이스:

| 시나리오 | 조작 | 관찰 표면 | 기대 |
|---|---|---|---|
| **F-1: S2 30s 만료가 α 60s 만료 전에 발생** | 모달 발동 후 사용자 무반응으로 30s 경과, 다시 동일 scope signal | UI: 모달 재출현 (S2 침묵 해제) | S2 만료 후 α는 무관 — α는 safe-confirm 이후만 활성, 모달 무반응은 α를 invoke하지 않음 |
| **F-2: safe-confirm 후 60s 경과 전 동일 scope signal** | 안전 확인 후 30s 시점 monitor가 동일 scope signal emit | UI: 모달 미출현 | α suppress가 작동 (S2 무관 — S2는 액션 발동 없었으므로 snapshot 비어 있음) |
| **F-3: safe-confirm 후 60s 경과 후 동일 scope signal** | 안전 확인 후 65s 시점 동일 signal | UI: 모달 출현 (α TTL 만료, S2도 snapshot 없음) | 두 layer 모두 침묵 해제 — 정상 |
| **F-4: 모달 발동 → 25s 시점 escape signal** | 모달 활성 중 25s 시점 BANKING_AFTER_REMOTE 신호 출현 | UI: 모달 갱신 (S2 escape) | escape 즉시 재무장, 다음 동일 BANKING signal은 30s 카운트 재시작 |

이 4건은 단위 테스트로는 30s/60s 시간 비대칭의 사용자 관찰 표면을 보지 못하므로 실기로만 검증.

### 7.5. T-D 재검증

| ID | 충족 여부 | 근거 |
|---|---|---|
| T-D1 | ✓ | G1/G2 가짜 clock + set 입력의 결정성 |
| T-D2 | ✓ | C-T1/T2/T3 경계 3건 + TTL 경계 `>` 채택 명시 |
| T-D3 | ✓ | C-R1/R2 5s/30s emit 시퀀스 |
| T-D4 | ✓ | C-E1~E6 6건 (positive 2 + negative 3 + 재무장 1) |
| T-D5 | ✓ | 정적 규칙 1, 2 |
| T-D6 | ✓ | 정적 규칙 3 |
| T-D7 | ✓ | C-C1/C2/C3 3건 |
| T-D8 | ✓ | 정적 규칙 1~4 |
| T-D9 | ✓ | F-1~F-4 실기 시나리오 |

### 7.6. Step 2 종료 — 코드 단계로의 입력값

#7은 Step 2의 마지막 결정이며, 본 결정 이후의 출력은 코드 구현 단계의 입력이 된다. 코드 단계는 본 문서가 결정한 7가지를 **그대로** 구현하며, 어느 한 가지를 임의로 변경하면 본 문서의 정합 검증(R/T/D/E/축/C/T-D 모두)이 깨진다.

| Step 2 결과 | 코드 단계 입력값 |
|---|---|
| **#1 layer = S2** | `DefaultRiskDetectionCoordinator` 내부에 게이트 변수 + pure function 추출 (R3) |
| **#2 N = 30,000ms, M 미도입** | 상수 정의 (네이밍은 코드 단계 자유, α 상수와 별도) |
| **#3 scope = 2 signal 명시 set** | scope set 정의 위치 = Coordinator 내부 (또는 인접 파일). 신호 추가 시 §3.2 표 갱신 절차 |
| **#4 escape predicate** | `(this_tick \ snapshot) ∩ UPGRADE_TRIGGERS ≠ ∅` 식 그대로. snapshot 갱신 순서 = 액션 발동 직후 즉시 (§4.4 불변) |
| **#5 α/S2 공존, UPGRADE_TRIGGERS 1개 공유** | 코드 단계에서 set 공용화 위치/시점은 자유 재량. 단 truth source는 1개 |
| **#6 CTA 격리 + C1/C2/C3 회귀 규칙** | PR 체크리스트 항목으로 운영 |
| **#7 테스트 표면 Q2** | `S2DebounceTest.kt` 신설, α 기존 보존, 정적 규칙 4건 |

**미해결 follow-up (Step 2 범위 외):**
- `UPGRADE_TRIGGERS` 3중 참조 도달 시 공용화 위치 (코드 단계 또는 별도 작업선)
- 실기 시나리오 F-1~F-4의 검증 인프라 (현장 단말 + 시간 측정 도구) — 별도 검증 세션 작업선

### 7.6.5. 코드 착수 직전 체크포인트 (잠금)

Step 2 종료 승인 시점에 사용자 지침으로 잠긴 두 항목. 코드 첫 변경 전 반드시 본 문서로 확정되어 있어야 하며, 구현 중 감으로 변경할 수 없다.

**체크포인트 #C7-1 — TTL 경계 연산자 잠금 (불변):**

> S2 게이트의 TTL 만료 판정은 `(now - lastFiredAt) > N` 으로 한다. 즉 **정확히 30,000ms 경과한 시점에는 아직 만료되지 않으며, 30,000ms를 초과한 시점부터 만료**된다.

- 식: `(now - lastFiredAt) > 30_000L` → TTL 만료 → 액션 재발동 가능 (S2 침묵 해제)
- 그 보수: `(now - lastFiredAt) <= 30_000L` → TTL 유지 → snapshot 비교 + escape 판정으로 진행
- §7.4 G1 시간 경계 케이스 매핑:
  - C-T1 (t=29,999ms): `29,999 <= 30,000` → TTL 유지 → snapshot 비교 → suppress
  - C-T2 (t=30,000ms): `30,000 <= 30,000` → TTL 유지 → snapshot 비교 → suppress
  - C-T3 (t=30,001ms): `30,001 > 30,000` → TTL 만료 → 재발동 가능
- 테스트 이름과 기대값은 위 식과 정확히 일치시킨다 (예: `suppresses_at_exactly_30000ms`, `releases_at_30001ms`). C-T2를 "경계 자체에서 만료"로 해석하지 않는다.
- 본 결정의 근거: §7.4의 "TTL 경계 정의대로 처리 — 본 결정에서 30,000ms 경과 시 TTL 만료 = `>` 경계 채택"의 의미를 "30,000ms 정확히 일치 시점은 미만료"로 정확히 못박음.

**체크포인트 #C7-2 — 정적 규칙 자동화 범위 잠금 (불변):**

§7.4의 정적 규칙 4건을 "전부 자동화" 또는 "전부 수동"으로 처리하지 않는다. 초기 코드 단계의 적용 형태는 다음과 같이 분리한다.

| 정적 규칙 | 자동화 | 운영 형태 |
|---|---|---|
| 규칙 1 — S2 클래스에 `RiskSessionTracker` import 금지 | **자동** | 테스트 클래스 import 행 grep 또는 간단한 정적 검사 (예: gradle task 또는 PR check 스크립트) |
| 규칙 2 — α 클래스에 S2 게이트 함수 import 금지 | **자동** | 동일 import grep |
| 규칙 3 — S2 클래스에 CTA 핸들러 호출 0건 (`HomeViewModel.confirmSafe`, `WarningViewModel.confirmSafe`, `RiskOverlayManager.dismiss`/`performSafeCtaSideEffects`) | **자동** | 식별자 호출 grep — 정확한 식별자 4종 명시 매칭 |
| 규칙 4 — α 클래스에 S2 wake-up 함수 호출 0건 | **수동 (PR 체크리스트)** | S2 wake-up 함수가 코드 단계에서 신설되므로 식별자가 코드 단계 진입 전엔 미확정. 코드 단계 첫 PR에서 식별자가 확정된 후 grep 룰을 추가하여 자동화로 승격 가능 |

운영 원칙:
- 초기 진입 = 규칙 1/2/3은 grep 기반 자동 검사 (가벼운 형태로 시작 — 정교한 lint 인프라까지는 도입하지 않는다)
- 규칙 4는 PR 체크리스트 항목으로 시작 → 식별자 확정 후 자동화로 승격
- 자동화의 정밀도가 부족해도(예: 식별자 부분일치 false positive) 코드 단계에서 점진 보강. 처음부터 완전 자동화 시도하지 않는다 (단순성 원칙)

본 분리는 §7.4의 정적 규칙 4건의 "강도"를 낮추는 것이 아니라, **운영 비용을 단순성 원칙에 맞춰 초기 단계에 적합하게 분배**하는 것이다. 4건 모두 위반 시 S2 격리가 깨진다는 의미는 동일하게 유지된다.

### 7.7. Step 2 종료 선언

본 문서 Step 2는 #1~#7 결정 + 체크포인트 #C7-1/#C7-2 잠금으로 종료된다. 본 문서 Step 2의 정합 책임은 코드 단계로 옮겨가지 않으며, 코드 단계의 자율 결정은 본 문서가 결정한 7가지 + 체크포인트 2건의 의미를 변경하지 않는 범위 안에서만 허용된다.

**다음 액션 (코드 단계 진입):**
1. 본 문서 종료 승인 — 완료 (사용자 판정 2026-04-27)
2. 체크포인트 #C7-1 (TTL 경계 `>`) — 본 §7.6.5에 잠금 완료
3. 체크포인트 #C7-2 (정적 규칙 자동화 범위) — 본 §7.6.5에 잠금 완료
4. 다음 작업: 구현/테스트 계획 수립 (별도 작업선, 본 문서 범위 외)
