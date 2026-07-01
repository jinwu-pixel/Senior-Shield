# A′-R0 Post-merge Runtime Sanity Gate — 운영 체크리스트 (draft)

작성일: 2026-04-29
대상 main HEAD: `08c286a docs: add S2 review checklist (#7)`
연관 PR: #5 (203f5e6) / #6 (c8ab0ec) / #7 (08c286a) — 모두 main 반영 완료
상태: **draft, uncommitted**

---

## 0. 목적과 범위

PR #5/#6/#7이 main에 squash merge된 뒤, 기존 A′ 실기 PASS 시나리오의 runtime invariant가 깨지지 않았는지 재확인한다. 본 문서는 사용자 단말 실기 run에서 로그를 일관되게 판정하기 위한 기준표다.

**범위 (이번 run)**
- 기존 A′ PASS 시나리오 재현
- 동일 단말 / 동일 시나리오
- main 최신 APK
- 코드 변경 0 / 테스트 추가 0 / PR 생성 0 / 새 branch 0

**범위 외 (이번 run 금지)**
- 새 단말, 새 시나리오, 새 번호군 도입
- callId 1776669049383 값 자체 재사용 — 새 callId가 발생하는 것이 정상
- UPGRADE_TRIGGERS 단일화, untracked 정리, PPT, unrelated remote cleanup

---

## 1. 실행 전 조건

- [ ] `git rev-parse HEAD` = `08c286a` (main)
- [ ] working tree: untracked 5건만 존재 (보존)
- [ ] 동일 단말 사용 (이전 A′ run과 같은 ALT 단말)
- [ ] APK는 main 최신 빌드, sideload 또는 Android Studio Run
- [ ] adb logcat 캡처 환경 준비 (`adb logcat -c` 후 시작)
- [ ] 새 callId가 새로 발생하는 것이 정상이라는 점 인지

검증 대상은 **callId 값 자체가 아니라 callId invariant**다.

---

## 2. 로그 수집 대상

### 관련 TAG (`SeniorShield-*`)
- `SeniorShield-Coordinator` — DefaultRiskDetectionCoordinator (popup/cooldown/REC-REFIRE/anchor)
- `SeniorShield-Session` — RiskSessionTracker (session/snooze/α/user-confirmed-safe)
- `SeniorShield-CallMonitor` — RealCallRiskMonitor (callId/anchor/safe-confirm)
- `SeniorShield-Overlay` — RiskOverlayManager (CTA/safe path/dismiss path)
- `SeniorShield-Cooldown` — BankingCooldownManager (banking cooldown lifecycle)

### 추적 대상 키 데이터
- `callId` / `currentCallId` / `startedAtMillis` / `liveCallId`
- `OFFHOOK` / `IDLE`
- `dismiss-only` / `safe CTA → ...` / `session reset [reason=user-confirmed-safe]`
- `α armed` / `non-call session respawn suppressed by α`
- `clearTelebankingAnchor` / `markCurrentCallConfirmedSafe`
- `anchorHotState mirror`
- `popup suppressed by S2 REC-REFIRE debounce` / `popup shown on state transition` / `새 trigger 팝업`
- `snooze set callId` / `snooze cleared`

---

## 3. grep 후보 패턴

기존 로그 스타일 확인 후 finalize됨. logcat 캡처 결과에 적용.

### 기본 로그 추출
```
adb logcat -s SeniorShield-Coordinator SeniorShield-Session SeniorShield-CallMonitor SeniorShield-Overlay SeniorShield-Cooldown
```

### 패턴 카테고리별

**A. callId / 통화 식별**
- `callId=` / `liveCallId=` / `currentCallId=` / `startedAtMillis=`
- `wasCallId=`

**B. 통화 상태 전환**
- `OFFHOOK` / `IDLE`
- `call became IDLE`
- `end-call suppression started`
- `suppression released after stabilization`

**C. CTA 분기 (RiskOverlayManager:437/439/441)**
- `safe CTA → anchor suppression + snooze` — in-call + callSafe (Invariant C)
- `safe CTA → snooze only, anchor preserved` — in-call but not callSafe
- `safe CTA → session reset only` — non-in-call (Invariant B/E)

**D. dismiss-only**
- `팝업 닫힘` (RiskOverlayManager 단순 dismiss)
- `오버레이 뒤로가기 키 → UI recovery`
- 부재해야 할 로그: `clearTelebankingAnchor` / `markCurrentCallConfirmedSafe` / `snooze set callId`

**E. safe-confirm 전용 흐름**
- `session reset [id=*, reason=user-confirmed-safe]`
- `snooze set callId=`
- `α armed (lastResetSignals=...)`

**F. anchor / IDLE skip**
- `anchorHotState mirror → true` / `→ false`
- `clearTelebankingAnchor`
- `markCurrentCallConfirmedSafe`

**G. REC-REFIRE / S2 debounce**
- `popup suppressed by S2 REC-REFIRE debounce (escalation path)`
- `popup suppressed by S2 REC-REFIRE debounce (new-trigger path)`
- `popup shown on state transition`
- `새 trigger 팝업`
- `s2Snapshot=` (포함된 lastFiredAt/TTL)

**H. α / S2 분리 확인**
- α 로그: `α armed`, `non-call session respawn suppressed by α`
- S2 로그: `S2 REC-REFIRE debounce`, `s2Snapshot`
- 두 그룹이 한 통화 내에서 **동일 callId의 동일 사건에 동시 적용된 흔적이 없어야 함**

---

## 4. invariant별 PASS/FAIL 표

총 7 invariants (A~G). C1/C2/C3 회귀 규칙은 `.github/S2_REVIEW_CHECKLIST.md` §3을 reviewer가 별도 대조.

### Invariant A — callId 일관성 (한 통화 안에서 흔들리지 않음)
- **PASS**
  - 한 통화 내 모든 `callId=` / `liveCallId=` / `currentCallId=` 값이 동일
  - OFFHOOK에서 잡힌 `startedAtMillis` 와 후속 로그의 `startedAtMillis`가 일치
- **FAIL**
  - 같은 통화 중 callId가 바뀜
  - IDLE 처리에서 다른 callId로 anchor/safe-confirm 로그가 발생

### Invariant B — dismiss-only 분리
- **PASS**
  - 사용자가 "일단 닫기"(dismiss-only) 누른 후 `팝업 닫힘`만 발생
  - **부재해야 할 핵심 로그 4개** (이 4개 중 하나라도 발생하면 즉시 FAIL):
    1. `markCurrentCallConfirmedSafe` — CallRiskMonitor.markCurrentCallConfirmedSafe(callId) 호출 흔적
    2. `clearTelebankingAnchor` — CallRiskMonitor.clearTelebankingAnchor() 호출 흔적
    3. `snoozeForCall` — RiskSessionTracker 의 `snooze set callId=$callId` 로그가 이에 해당
    4. `session reset [id=*, reason=user-confirmed-safe]` — RiskSessionTracker:270
  - **보조 부재 항목** (위 4개와 분리해서 다룬다 — 핵심 4개와 섞지 말 것):
    - `α armed (lastResetSignals=...)` — α arm은 user-confirmed-safe의 후속 효과이므로 dismiss-only에서는 부재해야 함
- **FAIL**
  - dismiss-only 후 핵심 4개 중 하나라도 발생
  - 화면상 dismiss로 보이지만 위 4개 중 하나의 로그 흔적이 동일 callId로 매칭되는 경우

### Invariant C — safe-confirm 전용 흐름
- **PASS**
  - safe-confirm CTA에서만 `safe CTA → anchor suppression + snooze` 또는 `safe CTA → session reset only` (분기는 in-call/callSafe에 따름)
  - 기대 side effect: anchor clear, mark safe, snooze, session reset 중 분기에 맞는 것만
- **FAIL**
  - dismiss-only 또는 non-in-call dismiss에서 safe-confirm 효과 발생
  - 분기 조건과 다른 side effect 발생 (e.g. non-in-call에서 anchor clear)

### Invariant D — IDLE anchor skip
- **PASS**
  - `anchorHotState mirror → false` 가 safe-confirm된 callId에 대해서만 발생
  - safe-confirm 없는 통화에서는 IDLE 후 anchor가 자연 만료될 때까지 유지
- **FAIL**
  - safe-confirm 없이 anchor skip 발생
  - 다른 callId에 anchor skip 적용

### Invariant E — REC-REFIRE / S2 debounce (TTL=30초)

**관찰창 정의 (30,000ms)**
- T0 = 첫 `popup shown on state transition` 또는 `새 trigger 팝업` 로그 시각.
- T0 ~ T0 + 30,000ms 구간: 동일 scope/snapshot 조건의 중복 popup은 발생하지 않아야 한다.
- T0 + 30,001ms 이후: 조건이 유지되면 재발화 가능.
- **정확히 30,000ms는 만료가 아니다** — TTL 경계 식은 `(now - lastFiredAt) > S2_REC_REFIRE_TTL_MS` 이며, 같음(`==`)은 미만 처리.
- 동일 scope/snapshot 판정은 logcat 의 `s2Snapshot=...` 값을 기준으로 한다.

**PASS**
- T0 ~ T0 + 30,000ms 구간 내 동일 scope/snapshot 의 popup 재발화 0건
- 그 구간 안의 새 trigger 시도는 `popup suppressed by S2 REC-REFIRE debounce (escalation path)` 또는 `(new-trigger path)` 로그로만 설명됨
- `s2Snapshot` 의 `lastFiredAt` 이 T0 와 일관 (동일 scope 한정)
- 억제 origin은 orchestration-layer 의 S2 debounce — CTA dismiss 호출과 인과 관계 없음

**FAIL**
- T0 ~ T0 + 30,000ms 구간 내 동일 scope/snapshot 의 popup 재발화 ≥1건
- dismiss CTA 가 REC-REFIRE 억제 원인처럼 동작 (CTA layer 억제 흔적)
- 정확히 30,000ms 시점에 만료된 것처럼 동작 (경계 식 위반 — `==`는 미만)
- 재발화가 T0 + 30,001ms 이전에 발생

### Invariant F — α / S2 분리
- **PASS**
  - α 흐름(`α armed` / `non-call session respawn suppressed by α`)과 S2 흐름(`S2 REC-REFIRE debounce`)이 로그/상태/함수 레벨에서 분리
  - α side effect가 S2 REC-REFIRE 억제 결정에 영향 주지 않음
- **FAIL**
  - α arm 로그와 S2 debounce snapshot이 같은 사건에 동시 적용된 흔적
  - S2 debounce가 safe-confirm/call-safe 효과를 유발

### Invariant G — 화면-로그 무모순
- **PASS**
  - 화면이 닫혔으면 로그상 `팝업 닫힘` 또는 `safe CTA → ...` 둘 중 하나가 정확히 1회
  - 화면이 살아있으면 dismiss/safe 계열 로그 부재
- **FAIL**
  - 화면은 닫혔는데 내부 상태가 safe-confirm처럼 처리되는 로그 모순
  - 또는 그 반대

---

## 5. 사용자 실기 후 보고 템플릿

```
1. 사용 단말:
2. APK 빌드 (commit/branch):
3. branch / commit:
4. 새 callId:
5. 재현 시나리오 (한 줄):
6. 통화 시작/종료 시각:
7. 누른 CTA (dismiss-only / safe-confirm / N/A):
8. 화면 관찰:
   - popup 발화 시점:
   - popup 소거 시점:
   - 통화 중 IDLE 후 anchor 표시 여부:
   - REC-REFIRE 30초 TTL 내 재발화 유무:
9. grep 로그 (각 카테고리별로 raw 라인 첨부):
   A. callId / 통화 식별:
   B. 상태 전환:
   C. CTA 분기:
   D. dismiss-only:
   E. safe-confirm:
   F. anchor / IDLE skip:
   G. REC-REFIRE / S2:
   H. α / S2 분리:
10. invariant PASS/FAIL 판정 (A ~ G):
11. 애매한 지점:
12. 다음 액션 후보:
```

---

## 6. 금지 (이번 run 한정 재확인)

- 새 branch 생성 금지
- 코드 변경 금지
- 테스트 추가 금지
- PR 생성 금지
- UPGRADE_TRIGGERS 단일화 착수 금지
- untracked investigations 01~03 정리 금지
- PPT 처리 금지
- unrelated remote branch 삭제 금지
- 신규 단말/신규 시나리오/신규 번호군 도입 금지
- callId 1776669049383 값 자체 재사용 시도 금지

---

## 부록 A — TAG → 파일 매핑 (디버깅 시 참조)

| TAG | 파일 |
|-----|------|
| SeniorShield-Coordinator | `monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt` |
| SeniorShield-Session | `monitoring/session/RiskSessionTracker.kt` |
| SeniorShield-CallMonitor | `monitoring/call/RealCallRiskMonitor.kt`, `monitoring/call/OutgoingCallReceiver.kt` |
| SeniorShield-Overlay | `core/overlay/RiskOverlayManager.kt` |
| SeniorShield-Cooldown | `core/overlay/BankingCooldownManager.kt` |
| SeniorShield-AppMonitor | `monitoring/appusage/RealAppUsageRiskMonitor.kt` |
| SeniorShield-AppInstall | `monitoring/appinstall/RealAppInstallRiskMonitor.kt` |
| SeniorShield-DeviceEnv | `monitoring/deviceenv/RealDeviceEnvironmentRiskMonitor.kt` |
| SeniorShield-Service | `monitoring/service/MonitoringForegroundService.kt` |
| SeniorShield-EventSink | `data/local/RoomRiskEventStore.kt` |
| SeniorShield-ContactChecker | `monitoring/call/CallerContactChecker.kt` |

## 부록 B — invariant ↔ 코드 위치 (회귀 시 첫 검사 지점)

| Invariant | 핵심 코드 진입점 |
|-----------|----------------|
| A. callId 일관성 | `RealCallRiskMonitor.kt` callId 부여 / `DefaultRiskDetectionCoordinator.kt` `liveCallId` 사용 |
| B. dismiss-only 분리 | `RiskOverlayManager.kt:437/439/441` (3분기 CTA 핸들러) |
| C. safe-confirm 전용 | 동일 (위 분기 중 in-call+callSafe / non-in-call 두 경로) |
| D. IDLE anchor skip | `DefaultRiskDetectionCoordinator.kt:399~407` (`_anchorHotState`), `WarningViewModel.kt:83`, `HomeViewModel.kt:188` |
| E. REC-REFIRE / S2 | `monitoring/orchestrator/S2RecRefireDebounce.kt`, `DefaultRiskDetectionCoordinator.kt:355/376` |
| F. α / S2 분리 | `RiskSessionTracker.kt:74,99,265` (α arm), S2 (위) |
| G. 화면-로그 무모순 | RiskOverlayManager + Coordinator 로그 cross-check |

---

(end of draft)
