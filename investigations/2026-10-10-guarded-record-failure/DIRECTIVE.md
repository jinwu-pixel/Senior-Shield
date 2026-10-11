# GUARDED 이력 기록 실패 트랙 지시문 — 기록 실패가 coordinator를 멈추지 않고 본인 알림은 나가게 한다 (v1.0)

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol` 참조)**
> - **등급**: [이 트랙 = **T2**] — coordinator tick·취소 의미론.
> - **검토**: Codex 전체 자체검토 + Claude 적대 검토 + 독립 빌드/lint.
> - **정지·질의 = S1~S5만**: S1 커밋·push·실기 / S2 적대검증 지적 ≥recommend / S3 등급 상승 / S4 제품 결정 / S5 게이트 실패.
>
> 작성: Claude. 수행: Codex(코드·테스트·IMPL_LOG). Gradle·검증기: Claude.
> **사용자 결정 (2026-10-10, "A 진행해")**: 이력 기록이 실패해도 **본인 알림은 그대로 보내고 그 이벤트의 이력만 누락**한다(A안). tick 중단·재시도(B안)는 기각한다.

## 0. 현재 상태

- 기준: main `234d944`. CI 기준선 app 581 / risk 7 / contracts 4.
- worktree `.worktrees/record-failure`, 브랜치 `codex/guarded-record-failure`.
- **결함**: `DefaultRiskDetectionCoordinator.processTickEscalation`의 GUARDED 분기(`alertState < INTERRUPT`)에서 `eventSink.recordRiskEvent(event)`(:1197, Room insert)를 예외 처리 없이 부른다.
  - Room/SQLite 쓰기 실패(저장공간 부족 `SQLiteFullException`, DB 손상 등)가 나면 예외가 `processTick`을 거쳐 `collect`까지 올라가 coordinator job을 끝낸다. 그러면 이후 감지·팝업·알림이 모두 조용히 멈춘다.
  - 호출부는 이 1곳뿐이다(grep 확인).
- 바로 뒤의 알림 커밋은 `commitNotificationIfCurrent(event, publication = null)`이다. GUARDED는 currentEvent·safe-confirm 출처 정보가 없으므로 기록 실패를 흡수하면 알림이 그대로 진행된다.
- INTERRUPT 분기(`publishCurrentRiskEvent`)의 sink 실패는 이미 흡수된다(출처 정보 PENDING을 유지하고 tick을 중단하는 기존 설계). **이번 트랙에서는 변경하지 않는다.**

## 1. 불변 운영 제약

1. commit·push·stage = S1. broad add 금지.
2. Gradle 직렬, JBR. Codex 샌드박스는 Gradle 불가.
3. 금지: Manifest·권한·서비스·DI·interface(`RiskEventSink` 계약 포함)·data 모듈·INTERRUPT 분기·notification/overlay·S2/α.
4. RED-first(Claude가 수정 hunk를 역적용해 실측).
5. 기존 테스트 삭제·약화 금지, 기존 단언 변경 0.
6. **Codex 문서는 worktree 안** `investigations/2026-10-10-guarded-record-failure/`에 쓴다(샌드박스 쓰기 루트). Claude가 main으로 옮긴다.

## 2. 승인된 5줄 계획

1. **수정 파일**: `DefaultRiskDetectionCoordinator.kt`(GUARDED 기록 1곳) / `testutil/CoordinatorTestHarness.kt`(`FakeRiskEventSink` 주입 필드 추가만) / 신규 `GuardedHistoryRecordFailureTest.kt` / CI floor 2파일.
2. **목적**: GUARDED 이력 기록의 일반 예외가 coordinator job을 끝내지 못하게 한다. 실패해도 그 tick의 본인 알림·notified 표시는 정상 진행하고, 다음 tick도 정상 처리한다.
3. **리스크**:
   - 정책: 본인 알림만 나가며 외부 연락 변화는 0이다.
   - 기술: 취소를 삼키면 stop 이후에 알림이 나갈 수 있다 → CE 재던짐과 catch 첫 줄 `ensureActive()`로 막는다.
   - 기록 누락으로 History와 Home 집계가 실제보다 적어지는 것은 수용한다(사용자 결정).
4. **테스트**: RED-first, 결함 주입, 전체 직렬 게이트, lint 신규 0, schema 0, self-check, 정적 계수(Log +1만).
5. **중단**: interface·DI·data·INTERRUPT 분기 변경이 필요해질 때, 기존 단언 변경이 필요해질 때, 알림 의미를 A안과 다르게 해야 할 때(S4).

## 3. 구현 계약

- **K1 (프로덕션)**: GUARDED 분기를 다음 형태로 바꾼다. 문구·순서는 그대로 지킨다.
  ```kotlin
  } else {
      try {
          eventSink.recordRiskEvent(event)
      } catch (e: CancellationException) {
          throw e
      } catch (e: Exception) {
          currentCoroutineContext().ensureActive()
          Log.e(TAG, "risk history record failed — notification continues without history", e)
      }
      null
  }
  ```
  - 이 블록 뒤(`userResetIntervened(... "escalation post-push")`부터)는 변경 0이다.
- **K2 (하네스)**: `FakeRiskEventSink`에 `var recordFailure: Exception? = null`, `var beforeRecord: (suspend (RiskEvent) -> Unit)? = null`를 추가한다.
  - `recordRiskEvent`는 `beforeRecord?.invoke(event)` → `recordFailure?.let { throw it }` → `recorded += event` 순서로 동작한다.
  - 기본값에서는 기존 동작과 같아야 한다.
- **K3 (테스트, 신규 `GuardedHistoryRecordFailureTest`)**:
  - (a) 미확인 통화 PASSIVE 신호(`callMonitor.callSignals.value = listOf(UNKNOWN_CALLER)`)로 GUARDED를 만들고, 기록 실패는 `IllegalStateException`(Room 계열 RuntimeException 대역)으로 주입한다.
    - 기대: `notify` 1회, `notifiedAlertState == GUARDED`, `recorded` 비어 있음, `pushed` 비어 있음, 팝업 0.
    - 이어서 실패를 해제하고 위험 수준을 올리는 PASSIVE 신호(예: `LONG_CALL_DURATION` 추가)를 넣는다 → 두 번째 기록 1건과 `notify` 2회. coordinator가 살아 있고 기록도 회복됨을 확인한다.
    - 위험 수준이 실제로 오르는지는 evaluator 점수로 확인하고, 오르지 않으면 다른 PASSIVE 조합을 쓴다.
  - (b) (a)의 첫 단계를 `java.io.IOException`(checked)으로 바꾼 사례: `notify` 1회, coordinator 생존.
  - (c) `beforeRecord` 훅 안에서 **동기** `coordinator.stop()` 직후 suspend 없이 `IOException`을 던진다.
    - 기대: `notify` 0, notified 표시 0, banking 이전값 대입 0, tick job 취소, 팝업 0.
    - 기존 `PopupGuardianSmsToggleTest.assertStopDuringSettingRead`의 단언 집합과 banking 준비(`bankingForeground = true`)를 참고한다.
  - 기존 helper 구조와 runTest 패턴을 따른다. 실시간 delay 단언은 쓰지 않는다.
- **K4 (floor)**: app MinTests 581 → 581 + 신규 테스트 수. 이력 주석 1줄을 추가하고 workflow는 단계 이름만 바꾼다.

## 4. 판별력 계획 (Claude 실측)

| 결함 주입 | 기대 검출 |
|---|---|
| MR1 K1 역적용 (try/catch 제거) | (a)(b) RED |
| MR2 `ensureActive()` 제거 | (c) RED |
| MR3 `catch (e: Exception)` → `RuntimeException` | (b) RED |
| MR4 catch에서 `return null` (B안: tick 중단) | (a)(b) RED (notify 0) |

**실측 (2026-10-11, 신규 3건 + PopupGuardianSmsToggleTest + CoordinatorTickCharacterizationTest = 31건)**:
- MR0 31/31 GREEN
- MR1 신규 3건 RED(RED-first)
- MR2 (c) RED
- MR3 (b) RED. (c)는 runTest의 미처리 예외 보고로 추가 RED
- MR4 (a)(b) RED. 기각한 B안을 테스트가 막는다.
- 기존 테스트 영향 0, 원본 해시 복원 일치.
- 독립 적대 검토 PASS(must-fix 0, recommend 0). 정보 항목: 기록 실패 중 reset을 직접 고정하는 테스트는 없다(공백, 선택), `popupAccountingCalls` 단언은 GUARDED에서 구조적으로 공허하다(무해), tick 경로에 보호되지 않은 저장소 I/O가 더는 없다.

## 5. 파일 범위

소스 1 + 하네스 1 + 신규 테스트 1 + CI 2. 이 밖은 S3 정지.

## 6. 완료 게이트

- fresh 직렬 GREEN·0 skipped·app = 새 floor
- lint 신규 0(로컬 robolectric EXTRA는 환경 기준선)
- schema 0, self-check
- 정적 계수: Log 50→51, 라벨 10, `expectedResetEpoch` 2
- 범위 무접촉
- IMPL_LOG 매핑 표
