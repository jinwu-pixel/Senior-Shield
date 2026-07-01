# Step 1.5 — REC-REFIRE 억제와 `"일단 닫기"` 분리 규칙

- 일자: 2026-04-24
- 브랜치: `investigation/cta-semantics-step1` (base: `main` = `9a50b98`)
- 전제: Step 1 (`01_step1_semantics.md`) 결론 그대로 계승
- 범위: REC-REFIRE 억제 축과 CTA 의미 축의 **분리 규칙**을 정의한다.
- 금지: 구현 방식 확정 / API 이름 / 공용화 범위 / UI 문구 최종 / 코드 착수. 모두 Step 2 이월.
- 기본 방향 (사용자 지정): `"일단 닫기"`는 dismiss-only 유지. 억제는 별도 흐름으로 분리.

---

## 0. REC-REFIRE 메커니즘 근거 (main = `9a50b98`)

| 좌표 | 상수/동작 |
|---|---|
| `monitoring/appusage/RealAppUsageRiskMonitor.kt:20` | `POLL_INTERVAL_MS = 5_000L` — 5s tick |
| 〃 `:23` | `DETECTION_WINDOW_MS = 30_000L` — 최근 30s 내 foreground였던 앱을 "recent"로 판정 |
| 〃 `:36` 주석 | `REMOTE_CONTROL_APP_OPENED` : 원격제어 앱이 최근 30초 이내에 포그라운드였음 |
| `DefaultRiskDetectionCoordinator.kt` tick | combined flow → `sessionTracker.update(callSignals, appSignals)` → 팝업/쿨다운 판단 |
| `RiskSessionTracker.kt:88-104` | α arm 억제 — `current==null && callSignals.isEmpty() && appSignals ⊆ armed && 신규 UPGRADE 없음`일 때 60s 한정 respawn skip |

### 재발화 구조 요약
1. 원격제어 앱이 30s window에 한 번 foreground → 이후 30s 동안 매 5s tick마다 같은 signal emit
2. session이 살아있으면 `update()`는 `added.isEmpty()` 분기로 "현재 세션 유지" — 추가 팝업 생성은 dedupe 기전(`notifiedActiveThreats`)이 막지만, 쿨다운 judgement는 조건 맞으면 재발동 가능
3. session이 죽어 있으면 `update()`는 새 세션 생성 → α arm 60s 내에서는 억제, **60s 경과 후 동일 조건으로 새 세션 재생성** (REC-REFIRE의 잔여 축)

### α arm의 커버 범위와 한계
- 커버: non-call shared-root 재발화. 직전 session signal snapshot의 subset인 appSignal만 60s 억제.
- 한계: (1) 60s 경과 후 무력화, (2) 신규 UPGRADE 신호 진입 시 escape, (3) call-layer 재발화는 별도 축(snooze), (4) **아직 세션이 살아있을 때**는 α의 진입 조건(`current==null`)을 못 만나서 역할 없음.

**핵심 관찰:** REC-REFIRE는 근본적으로 **monitor layer의 재emit 빈도** 문제다. CTA가 무엇을 하든 30s window 동안 같은 signal이 5s 간격으로 계속 들어오는 사실은 변하지 않는다.

---

## 1. 질문별 답

### Q1. suppression이 필요한가?

**YES, 단 "좁은 의미로만."** 두 관점을 분리:

- **UX 관점**: 같은 쿨다운 오버레이가 5s 간격으로 반복 재등장하면 과잉 노출. 사용자가 한 번 인지한 직후 잠깐은 재등장을 막아야 피로 방지.
- **안전 관점**: 재발화 채널을 완전히 닫으면 추가 위협 신호를 놓칠 수 있음. "영구 억제"는 금물.

**결론**: "세션 종료/사용자 판정" 의미가 **아닌**, **순수 monitor/action 레이어의 짧은 debounce** 수준이 필요. α arm도 이 축의 일부지만 "safe-confirm 경로에서 켜지는 세션-layer 억제"라서 CTA dismiss 경로의 debounce와는 축이 다르다.

### Q2. 필요하다면 얼마나 좁은 의미인가?

후보 스펙트럼 (좁음 → 넓음, 참고용 — Step 1.5에서 하나로 확정하지 않음):

| ID | 범위 | 세션/이벤트 의미 | 명칭 기준 |
|---|---|---|---|
| S0 | 현 main — 억제 없음 | 건드리지 않음 | 현상 |
| **S1** | **쿨다운-로컬 재발화 debounce** — "방금 dismiss된 쿨다운"이 같은 조건으로 N초 내 재발동되지 않음 | **건드리지 않음** | action-layer |
| **S2** | **Coordinator tick-level signal debounce** — 같은 signal 집합이 M초 내 반복 emit되면 내부 action trigger는 한 번만 | **건드리지 않음** (accumulatedSignals 계속 축적) | orchestration-layer |
| S3 | α arm (세션-layer) — 이미 존재, safe-confirm 경로 전용 | 세션 reset 뒤에 arm됨 | session-layer |
| S4 | session reset + α arm = full userConfirmedSafe-세션축 | 세션 종료 | user-action-layer |

Step 1.5에서 고정하는 것:
- **허용 축**: S1 또는 S2 (action / orchestration 계층). "세션/이벤트 의미 미변경"이 경계.
- **제외 축**: S3, S4. 이미 다른 경로(safe-confirm)의 의미 단위이므로 CTA dismiss에 결합하면 의미 충돌.

S1/S2 선택은 Step 2 구현 설계 단계로 이월.

### Q3. 그 의미를 `"일단 닫기"`에 포함시킬 수 있는가?

**NO. 포함시키지 않는다.**

- 문자 그대로: view-level dismiss의 부수효과로 debounce 상태 한 줄을 기록하는 건 기술적으로 쉽다. 그러나 **의미 계약 관점**에서 CTA 라벨과 내부 상태 주입이 분리되는 순간 Step 1 close 사유였던 semantics drift가 다시 생긴다.
- 대안(S1/S2)은 둘 다 "사용자가 무엇을 했느냐"가 아니라 "시스템이 같은 상황을 얼마나 자주 재평가하느냐"로 귀결되므로, **CTA에 종속시킬 필요가 없다**. CTA는 시점 신호로만 쓰거나, 아예 무관하게 monitor/coordinator 축에서만 돌 수 있다.

### Q4. 포함시키면 라벨/의미 충돌이 생기는가?

**YES, 두 종류.**

1. **라벨-내부 충돌**: 라벨은 "일단 닫기"(순수 view gesture)인데 내부는 "닫기 + N초 debounce 상태"가 됨. 코드 리뷰에서 즉시 보일 드리프트.
2. **사용자 멘탈 모델 충돌**: 시니어가 `"일단 닫기"` 후 N초간 같은 쿨다운이 안 뜨는 것을 관찰하면 "이게 안전 판정된 거구나" 오해 가능. 홈 `"안전 확인했어요"`의 존재로 의미 축이 이미 분리돼 있는데 `"일단 닫기"`에 억제를 얹으면 **세 번째 애매 축**이 신설된다 (`feedback_ux_wording_axes.md`의 축 정렬을 깨뜨림).

### Q5. 충돌이 생기면 별도 CTA/흐름 필요?

**별도 흐름이 맞다. 별도 CTA는 재시도 금지.**

- **별도 CTA 추가**: PR #3(`f6326ec`)가 했던 형태 — 주 CTA dismiss, 보조 CTA safe-confirm/α arm. close 사유에 "두 CTA 의미 차이를 시니어가 구분 못 해 UX 혼란" 명시됨. **재시도하지 않는다.**
- **별도 흐름**: 억제 로직을 CTA 행위에서 분리하고, **시스템 내부 규칙**으로 구현한다. 사용자가 어떤 버튼을 눌렀는지와 무관하게 시스템이 자기 자신의 재fire 빈도만 조절한다. CTA 의미 축은 불변.

---

## 2. Step 1.5 고정 결정

### 2.1. 의미 축 분리 선언

| 축 | 책임 | 담당 위치 | 변경? |
|---|---|---|---|
| **CTA dismiss** | 현재 쿨다운 창만 걷어내기 | `"일단 닫기"` (not-inCall), `"전화 앱으로 이동"` (inCall) | **불변** |
| **세션 safe-confirm** | 세션/이벤트 해제 + α arm | 홈 `"안전 확인했어요"`, Warning `"안전 확인 — 위험하지 않습니다"` | 불변 |
| **REC-REFIRE 억제** | 같은 상황의 재fire 빈도 조절 | **비-CTA 축 (monitor / orchestrator 내부 debounce)** | 신설 대상 |

### 2.2. 고정 규칙

1. `"일단 닫기"`(not-inCall) = **엄격 dismiss-only** 유지. 세션/이벤트/α/snooze/anchor 절대 건드리지 않음.
2. REC-REFIRE 억제는 **CTA 행위와 독립된** 내부 규칙으로 처리. 사용자의 탭 여부와 무관하게 시스템이 자체적으로 재fire 빈도를 관리.
3. 허용 축 범위 = S1(action-layer) 또는 S2(orchestration-layer). 세션/이벤트 의미는 미변경. S3/S4(세션-layer 이상)는 이 경로에서 사용하지 않음 (safe-confirm 전용).
4. α arm은 **현재 위치(safe-confirm 경로)에 그대로** 둔다. REC-REFIRE 억제 신설이 α를 대체하거나 이동시키지 않는다.
5. 별도 CTA 추가 금지. 시니어 UI는 현 CTA 구성(`"일단 닫기"` / `"전화 앱으로 이동"`)을 유지한다.
6. **단순성 선택 지표 (Step 2 tie-breaker)**: 동일한 사용자 보호 효과를 만족할 수 있다면, CTA 의미를 늘리거나 새 UI 상태를 추가하는 선택보다, 기존 CTA semantics를 유지한 채 monitor/orchestrator 레이어에서 억제하는 선택을 우선한다. (사실상 S2 우선, S1/혼합은 S2가 timing/escape/testability 요구를 만족하지 못할 때만 예외 검토.)

### 2.3. 분리 축의 의미

> **CTA는 시점 신호, 억제는 주기 신호.** CTA(dismiss)가 일어났다는 사실을 억제 규칙의 **입력 신호 중 하나**로 참조할 수는 있어도(예: "방금 dismiss된 조건"), CTA가 억제를 **소유**하지는 않는다. 구현 시에도 동일 — CTA 클릭 핸들러가 억제 상태를 쓰지 않는다.

이 구분을 유지하면 CTA 의미는 변하지 않고, 억제는 시스템 축에서 튜닝 가능한 독립 변수로 남는다.

---

## 3. Step 2 이월 (구현/설계 단계)

Step 2에서만 확정한다:

1. **억제 계층 선택** — S1(cooldown-local dismiss timestamp 기반) vs S2(Coordinator tick-level signal debounce) vs 혼합
2. **시간창 N/M** — 몇 초?
3. **대상 signal 범위** — `REMOTE_CONTROL_APP_OPENED` 단독? appSignal 전체? call signal 별도?
4. **escape 조건** — 상위 UPGRADE 신호(`REMOTE_CONTROL_APP_OPENED`, `BANKING_APP_OPENED_AFTER_REMOTE_APP`, `TELEBANKING_AFTER_SUSPICIOUS`) 또는 새 TRIGGER 진입 시 억제 무력화 (α 철학 계승)
5. **α arm과의 관계** — 공존(가장 단순), 참조(armed snapshot 재사용), 흡수(반대 금지, Step 1.5가 경계 그었음)
6. **CTA 클릭과의 연결 방식** — CTA가 억제 규칙에 "입력 신호"로 기여할지(예: dismissedAt 기록만), 아예 무관할지
7. **테스트 전략** — 단위(가짜 clock으로 5s/30s/60s 경계), 실기(30s 내 재진입 / 60s 경과 / UPGRADE escape)

Step 1.5 범위 밖으로 명시:
- 구현 방식 확정
- API 이름 확정 (`SuppressionRegistry` 같은 네이밍 포함)
- 공용화 범위
- UI 문구 최종 확정 (현 CTA 라벨은 유지한다는 것만 고정)
- 코드 착수

---

## 4. 결론 요약

- **Q1** — YES 좁은 의미의 억제 필요. UX 과잉 노출 방지 목적. 단 "세션 판정" 의미는 아님.
- **Q2** — 세션/이벤트 의미를 안 건드리는 debounce 범위. S1(action) 또는 S2(orchestration).
- **Q3** — NO. `"일단 닫기"` 의미에 포함시키지 않는다.
- **Q4** — YES 충돌. 라벨-내부 드리프트 + 사용자 멘탈 모델 드리프트.
- **Q5** — **별도 흐름** (내부 debounce 규칙). **별도 CTA 추가는 금지** (PR #3 close 사유 재발 방지).

기본 방향 고정:
- `"일단 닫기"` dismiss-only 유지
- REC-REFIRE 억제 = **CTA와 독립된 monitor/orchestrator 축의 debounce**
- α arm은 safe-confirm 경로의 세션-layer 억제로 현 위치 유지
- 구현/네이밍/수치는 Step 2에서
- **단순성 tie-breaker**: 동일 보호 효과 시 S2(orchestration-layer) 우선, S1/혼합은 예외 사유 명시 시에만
