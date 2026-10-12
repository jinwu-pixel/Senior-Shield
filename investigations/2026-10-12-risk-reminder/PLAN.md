# 재알림(notification-only reminder) 계획 초안 — v0.1 (2026-10-12)

> 상태: **계획 단계, 구현 전 승인 필요.** CLAUDE.md 고위험 작업 "알림 관련 기능"에 해당한다.
> 등급 예정: **T2**. coordinator tick, 세션 상태, 알림을 함께 다룬다.
> 전제(2026-07-20 확정 결정 3): **B 원칙 = notification-only reminder.** 절대 조건 4개는 아래와 같다.
> ① fresh/live source가 확인된 INTERRUPT/CRITICAL만 대상이다.
> ② 사용자 안전 확인이 재알림보다 우선한다.
> ③ 세션당 횟수 상한을 둔다.
> ④ TTL·α를 reminder trigger로 재사용하지 않는다.
> 간격·상한 수치와 구현 시점은 이 계획에서 **사용자가 결정**한다.

## 0. 현행

- 알림은 AlertState 전이나 RiskLevel 상승 때만 나간다. 같은 세션이 지속되거나 갱신(D1 renewal)될 때는 시간이 지나도 다시 알리지 않는다(escalation-only).
- coordinator에는 15초 maintenance tick이 있다(`ANCHOR_MIRROR_INTERVAL_MS`). 현재는 TTL 만료 판정에만 쓴다.
- `RiskNotificationManager.notify(event)`는 알림 id를 `event.id.hashCode()`로 정하며, 본인 기기 알림만 낸다(승인된 예외).
- 세션 필드에는 `notifiedAlertState`·`notifiedLevel`·`notifiedActiveThreats`가 있다. 재알림용 시각·횟수 필드는 없다.

## 1. 5줄 계획 (초안)

1. **수정 파일(예상)**
   - `DefaultRiskDetectionCoordinator`: maintenance tick에 재알림 판정 단계 추가
   - `RiskSession`·`RiskSessionTracker`: 재알림 시각(monotonic)과 횟수
   - `RiskNotificationManager`: 재알림 문구 변형
   - 신규 테스트, CI floor
   - interface·DI·Manifest·권한은 바꾸지 않는다.
2. **목적**: 위험이 실제로 계속되는데 사용자가 아무 반응도 하지 않으면, 정해진 간격으로 **본인 기기 알림만** 다시 낸다. 팝업·쿨다운은 반복하지 않는다.
3. **정책·권한 리스크**
   - 본인 알림(승인된 예외) 범위 안이다. 보호자 연락·외부 전송은 0이다.
   - 알림 피로가 생길 수 있어 상한으로 통제한다.
   - `POST_NOTIFICATIONS`가 거부된 상태면 재알림은 표시되지 않는다. 대체 UX를 두지 않는다. 오버레이 반복은 원칙 밖이다.
4. **테스트**
   - 절대 조건 ①~④를 각각 RED-first로 검증한다.
   - 결함 주입: 간격 무시, 상한 무시, 안전 확인 후 재알림, TTL·α 재사용
   - 전체 게이트, 적대 검토
5. **중단 조건**
   - 팝업이나 쿨다운 반복이 필요해지는 경우
   - fresh/live 판정에 monitor interface 변경이 필요한 경우
   - 기존 S2·α·SafeConfirmationPolicy 계약과 충돌하는 경우

## 2. 결정 필요 (S4)

| # | 항목 | 선택지 | Claude 권고 |
|---|---|---|---|
| R1 | **재알림 간격** | 1분 / 3분 / 5분 / 10분 (등급별로 다르게 가능) | **CRITICAL 3분, INTERRUPT 5분.** 통화 중 사기는 수 분 안에 진행되고, 고령 사용자는 1분 간격이면 피로가 크다 |
| R2 | **세션당 상한** | 1 / 2 / 3회 | **2회.** 최초 알림을 포함해 최대 3번 알린다. 지속 위험을 상기시키는 효과와 피로 사이의 균형이다 |
| R3 | **"live" 판정** | (a) 최근 fresh tick에 TRIGGER 신호가 **지금도** 있음 / (b) 세션에 누적된 TRIGGER만 있어도 됨 | **(a).** 절대 조건 ①을 만족한다. 예: 원격제어 앱이 아직 전면, 통화가 계속됨. 설치 신호처럼 5초 뒤 사라지는 사건형 신호는 재알림하지 않는다 |
| R4 | **표시 방식** | (a) 같은 알림을 갱신해 다시 울림 / (b) 새 알림을 추가 | **(a).** 알림창에 쌓이지 않게 한다. 문구 예: "아직 위험 상황이 이어지고 있습니다 — 확인해 주세요" |
| R5 | **멈추는 조건** | 안전 확인(Home·Warning·팝업 "위험 경고 해제"), 세션 종료(TTL), live 소멸 | 권고 그대로. 팝업 "일단 닫기"(dismiss만)로는 멈추지 않는다. 사용자가 안전을 확인한 것이 아니기 때문이다 |
| R6 | **구현 시점** | 지금 / 실기(Android 8~11·원격제어) 후 | **지금.** 실기 항목과 독립적이다 |

## 3. 위험·미확인

- 고령 사용자의 알림 피로도에 대한 실데이터가 없다. 수치는 출시 후 조정할 수 있는 상수로 둔다.
- 사건형 TRIGGER(설치, 텔레뱅킹 발신)는 R3(a)를 따르면 재알림 대상이 되지 않는다. 의도된 동작이며, 지속형(원격제어 전면, 통화 중)만 대상이다.
- 휴대폰이 Doze 상태면 15초 tick 간격이 늘어나 재알림이 늦어질 수 있다. foreground service가 있으므로 영향은 작을 것으로 예상하며, 실기에서 확인한다.

---

## 4. v0.1 독립 검토 결과 (2026-10-12) — 결론 "조건부"

### 4.1 v0.1의 사실 오류 (정정)

- **"통화가 계속됨 = live"는 틀렸다.** 통화 신호 UNKNOWN_CALLER·LONG_CALL_DURATION·UNVERIFIED_CALLER는 PASSIVE이고, REPEATED_CALL_THEN_LONG_TALK는 AMPLIFIER다(`RiskSignal.kt:4-13`). 통화만으로는 GUARDED이므로 조건 ①의 대상이 아니다.
- **TELEBANKING은 사건형이 아니다.** 선캡처 경로로 감지되면 **ARS 통화가 이어지는 동안 live**다(`RealCallRiskMonitor.kt:367-388`, IDLE RESET까지). 사건형은 CallLog 폴백 경로뿐이다.
- **핵심 대상인 BANKING_APP_OPENED_AFTER_REMOTE_APP이 누락**됐다. 원격 앵커 30분 + 금융앱 최근 30초 사용이 조건이다.
- **REMOTE_CONTROL_APP_OPENED는 "전면인 동안"이 아니다.** 최근 30초 안에 사용된 경우(`lastTimeUsed ≥ now-30s`)이며, 5초 poll로 확인한다.
- **R5 멈추는 조건 누락**: 팝업 "통화 경고 닫기"(OVERLAY_LIVE_CALL)도 안전 확인 경로이고, Debug reset도 멈춘다. dismiss만 하는 동작(일단 닫기, 전화 앱으로 이동, 뒤로가기, 쿨다운 주 버튼)을 열거해야 한다.
- **R2 문구**: "최대 3번"이 아니라 "재알림 최대 2회 + 기존 경로 알림 n회"가 맞다. 에스컬레이션과 new-trigger 재진입 알림은 별도로 나간다.
- **R1 축**: RiskLevel이 아니라 AlertState 기준임을 명시한다. 텔레뱅킹 단독은 RiskLevel CRITICAL이지만 AlertState는 INTERRUPT다.

### 4.2 설계 보강 요구 (수정 필수)

- coordinator 캐시(`latestSignals`)에는 **시간 신선도가 없다**. 값이 바뀔 때만 갱신되고, UsageStats Unknown poll은 방출을 건너뛴다. 따라서 다음 보강이 필요하다.
  - idle-timeout tick에서는 재알림하지 않는다.
  - sleep gap(직전 tick과의 간격 > 2×15초)이 생긴 tick은 보류한다.
  - live trigger가 maintenance tick 2회 연속 유지된 뒤에만 재알림한다.
  - 잔여 위험은 수용할지, monitor가 관측 시각을 노출하도록 할지(interface 변경) 정해야 한다 → **R7 결정 필요**.
- **재알림은 새 RiskEvent·publish·record·navigation·overlay·cooldown을 만들지 않는다.** 마지막 INTERRUPT 이상 알림의 event와 receipt를 보존해 `commitNotificationIfCurrent`로 재게시한다.
  - 새 publish를 하면 열린 팝업의 binding이 무효가 된다.
  - 기존 직렬화 경로를 쓰면 안전 확인과의 경쟁 창이 닫힌다.
- 시계는 `elapsedRealtime`(seam)으로 한다. 기준 시각은 에스컬레이션과 new-trigger 알림 commit 시점에 갱신한다.
- 상태 저장 위치는 coordinator 내부 `ReminderState(sessionId, …)`로 한다(`cooldownConsumedSessionId` 패턴).

### 4.3 실측 근거: 앱 기반 TRIGGER의 live 지속 시간 (Claude 오프라인 로그 분석)

- 대상: 10-10 THOR3(Android 16) 실기 로그. SeniorShield가 20:27~20:34 동안 연속 전면이었고, 그 사이 앱 안에서 화면 이동을 계속했다.
- 결과: `tryQueryUsageStats(interval=4) filtered`에 SeniorShield가 잡힌 구간은 20:26:42~20:27:07(약 25초)뿐이었다. 그 뒤 **421초 동안 부재**했다. 20:34:19(홈으로 나감 = 화면 전환) 이후 다시 약 25초만 잡혔다.
- 해석: 이 단말에서 `lastTimeUsed`는 activity 전환 때만 갱신되고, 연속 전면이나 앱 내 조작으로는 갱신되지 않는다. 따라서 **앱 기반 TRIGGER(원격제어, 원격 후 금융앱)는 화면 전환 후 약 30~35초만 live**다.
  - 단서: 여러 activity를 오가는 금융앱이라면 전환마다 다시 갱신될 수 있다. 단일 기기·단일 OS 관측이다.
- 결과적으로 R3(a)·R1(3/5분)대로라면 앱 기반 재알림은 **거의 발화하지 않는다.** 실질 대상은 의심 통화 뒤의 **은행 ARS 발신 통화 중(텔레뱅킹 선캡처)**뿐이다.

### 4.4 정책 메모

- 본인 기기 알림이라 승인된 예외 범위 안이다.
- 다만 CLAUDE.md 승인된 예외 표는 "위험 감지 시 알림"으로만 적혀 있다. 구현하게 되면 "지속 위험 시 상한 있는 재알림"을 추가해야 한다(사용자 승인 사항).
