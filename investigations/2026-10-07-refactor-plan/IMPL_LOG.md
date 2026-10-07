# R1 IMPL_LOG

## R1.0 — T002 (2026-10-07)

### 변경 파일 / 실행 경계

- 신규 테스트: `.worktrees/refactor-coordinator-r1/app/src/test/java/com/example/seniorshield/monitoring/orchestrator/CoordinatorTickCharacterizationTest.kt`
- 정적 계수 도구: `investigations/2026-10-07-refactor-plan/static_invariants.py`
- 이 로그: `investigations/2026-10-07-refactor-plan/IMPL_LOG.md`
- DIRECTIVE v1.0 전체와 PLAN v0.2를 읽었다. 작업 HEAD: `9657cf4ea519b90aa8af67958535d93cf7330829`. 최초 worktree status는 빈 출력.
- 제품 코드·하네스·기존 테스트·빌드 파일 변경 0. git 쓰기 0, Gradle/adb 실행 0. R1.1/R1.2 미착수.
- 작업 지시가 generic skill의 RED-first/Gradle/추가 ledger/commit 절차보다 우선한다. 현행 코드 characterization이며 별도 파일은 만들지 않는다. 검토는 Codex 자체검토 및 Claude 독립 실행/적대 검토로 나눈다.

### 핵심 변경 / 성질 매핑

`TESTED`는 실행 가능한 characterization을 작성했다는 분류이다. 이 샌드박스에서 Kotlin 테스트를 실행했다거나 GREEN을 확인했다는 뜻이 아니다. 실제 GREEN 판정은 Claude 실행 대기. STRUCTURALLY_PROVED 대체 항목은 0개.

| 성질 | 테스트 이름 | 분류 | 판별력 / 현행 소스 근거 |
|---|---|---|---|
| (a) banking 계산 이후 조기 종료 대입 | `noSessionExitAssignsDifferentEffectiveBanking` | TESTED | 이전 false, fresh epoch banking=true. 세션 null 및 cleanup +1로 no-session 출구 진입 확인 후 previous=true. C:822–832. |
| (b)-1 maintenance 비만료 출구 | `maintenanceWithoutTimeoutPreservesPreviousBanking` | TESTED+STRUCTURAL | 실 tick으로 previous=true 설정. 공유 FakeClock의 비만료 세션, mirror +1로 pulse 실행 확인. session ID/cleanup 수/previous 유지. C:663–667. |
| (b)-2 maintenance 스냅샷 없음 출구 | `maintenanceWithoutSnapshotPreservesPreviousBanking` | TESTED+STRUCTURAL | 첫 launch의 실 입력으로 previous=true. stop/runCurrent 후 같은 coordinator 재시작; CALL만 silent flow로 막아 새 launch의 combine 미발화. banking의 원시값은 false. 직접 만든 세션의 TTL 초과 → 세션 null, cleanup +1, previous=true 유지. C:648–674. |
| (c) escaping cancellation 미대입 | `escapingCancellationDoesNotAssignDifferentEffectiveBanking` | TESTED | 시작 전 fresh banking=true+비통화 REC, 기존 previous=false. notification hook 진입 assert 및 그 문맥의 Job 캡처 → stop → runCurrent → Job.join. hook finally, isCancelled/isCompleted 모두 확인 후 previous=false. C:1072/1207–1212. |
| (d) 처리된 push 실패 대입 | `handledPushFailureAssignsDifferentEffectiveBanking` | TESTED | fresh banking=true, previous=false. sink beforeCurrentEventSet에 일반 IllegalStateException 주입, 시도 1/실제 push 0/notify·overlay 0/previous=true. 훅 제거 후 banking=false tick이 정상 publish까지 진행해 collection 생존도 확인. C:1283–1291 → C:1041–1045. |
| (e) escalation 중단은 같은 tick new-trigger도 중단 | `escalationReplacementAbortsSameTickNewTriggerEffects` | TESTED | 비통화 REC 단독, banking=false, cooldown=false, INTERRUPT, 미통보 trigger. notification commit 직전 suspend 후 Debug replacement 완료. replacement의 push/overlay 포함 목록을 baseline으로 캡처하고 release 후 push/record/notify/overlay 목록 모두 동일(추가 0) 및 current replacement 보존. C:1072–1083 → C:1133–1199. |
| (e) 입력의 양성 대조 | `unnotifiedRemoteTriggerActuallyFiresWithoutEscalation` | TESTED | 같은 REC 입력·미통보 trigger·banking=false·비활성 cooldown·초기 S2. tracker의 notified alert/level만 사전 설정해 escalation 조건을 없애고 new-trigger에서 실제 push/notify/overlay 각각 1회와 trigger 통보 회계를 단언. |

### 설계 판단 / 자체검토

1. 기존 CoordinatorTestHarness의 real tracker/evaluator/resolver/factory, stamped monitor, fake sink/repository, relaxed presentation mocks를 재사용했다. `DefaultRiskDetectionCoordinatorIdleExpiryBoundaryTest`의 공유 시계·maintenance pulse·silent combine 패턴과 `WarningNavigationPublicationTest`의 deferred publication 교체 패턴을 따른다.
2. 하네스는 start()까지 실행하므로 첫 tick 전에 hook 설치와 재시작 CALL 보류가 필요한 두 경우에만 새 테스트 안의 private `newCoordinator`로 같은 구성 인자를 조립했다. 기존 파일 수정이나 production seam 추가는 없다.
3. reflection은 previousBankingForeground의 읽기만 사용한다. previous=true 설정은 실제 banking flow를 통해 수행한다. (a)/(c)/(d)는 fresh epoch 및 이전값과 다른 banking 입력을 명시적으로 검증한다.
4. (c)는 `stop()` 반환을 취소 완료로 오인하지 않는다. collect 문맥의 실제 Job을 hook에서 얻어 join한 뒤 단언한다. `advanceUntilIdle()`는 사용하지 않는다.
5. (e)의 hook은 첫 escalation만 보류한다. 잘못 전파되어 뒤따르는 new-trigger까지 보류하면 결함을 가릴 수 있으므로 이후 hook 호출은 통과시킨다. notification commit 거절 시 popupShownThisTick=false, cooldownFiredThisTick=false, unnotified REC가 남고 Debug overlay는 S2 회계를 갱신하지 않는다. 따라서 중단 전파가 빠지면 new-trigger의 push·notify·overlay가 발생하며 목록 delta가 이를 잡는다. 양성 대조는 escalation만 사전 notified 처리하고 같은 활성 trigger 입력의 실제 발화를 확인한다. 이 양성 대조 자체도 실행은 Claude 대기이다.
6. (b)-1의 보존 검사는 동일값 재대입 자체를 식별하는 쓰기 추적기가 아니다. 현행 maintenance 두 출구가 banking 계산보다 앞선다는 소스 근거를 함께 보존한다. (b)-2는 재시작 후 원시 banking=false와 이전 true를 함께 검증한다.
7. (d)는 일반 push 예외의 mutation 전 실패를 고정한다. mutation 후 실패 전부를 포괄한다고 주장하지 않는다. escaping 일반 예외 전체 대신 DIRECTIVE가 요구한 escaping cancellation을 (c)로 고정한다.
8. 모델/기존 FQCN/권한/통화·외부 연락·알림 제품 동작을 변경하지 않았다. 테스트는 Android 서비스나 실기기를 실행하지 않는다.
9. 경량 읽기 전용 explorer(`gpt-6-luna`, high)가 하네스 API/생성자/import/MockK Unit 반환/코루틴 API 및 7개 경로를 소스와 대조했다. 컴파일 차단 의심점은 발견하지 못했다. 작성자가 별도 전체 자체검토를 수행했다. 실제 컴파일·런타임 증거를 대체하지 않는다.

### 확인한 것 — 정적 도구

- `python -I static_invariants.py <Coordinator.kt>` 실제 종료코드 0, stdout JSON 파싱 성공.
- 파일 전체의 lexical count이다. 주석·선언·문자열의 일반 텍스트는 코드 호출에서 제외하고 `${...}`와 `$name`의 실제 읽기는 포함한다. volatile 3필드는 선언/단순 대입 LHS를 제외한 읽기 수이며 로그 interpolation 읽기도 포함한다.
- Log literal multiset은 따옴표/이스케이프/공백/보간식을 포함한 원문 철자를 보존한다. 48 calls / 49 literals: 한 Log 호출의 연결 문자열 두 조각도 각각 세므로 정상이다. UTF-8 바이트를 strict decode하며 newline/Unicode를 정규화하지 않는다. stdout은 ASCII JSON escape로 무손실 출력한다.
- 메모리 fixture: 중첩 주석, 일반·raw 문자열, 중첩 template 문자열, $field 읽기, hook/label/declaration 제외 검증 PASS.
- invalid UTF-8 입력은 메모리 주입으로 main 반환 2 + 빈 stdout 확인. 실제 subprocess에 기존 gradle-wrapper.jar의 바이너리 바이트를 입력해 종료코드 2 + 빈 stdout + UTF-8 decode 오류도 확인했다(Gradle 실행 아님).
- 닫히지 않은 주석/문자열과 인자 누락에 대한 fail-closed 반환 2 확인. 검증용 추가 파일/pycache 쓰기 없음.
- 이 도구는 횟수·literal 보존 보조 증거이며 K5의 순서/단락 평가/락 대상 동일성 증명은 아니다. R1.1에서 별도 등가성 검토가 필요하다.

### 정적 계수 전 — 기준값 대조

| 항목 | 실측 | Claude 기준 | 판정 |
|---|---:|---:|---|
| return@collect | 26 | 26 | 일치 |
| previousBankingForeground = bankingForeground | 25 | 25 | 일치 |
| userResetIntervened 라벨 총수 | 10 | 10 | 일치 |
| Log 호출 | 48 | 48 | 일치 |
| synchronized 호출 | 10 | 10 | 일치 |
| @Synchronized | 6 | 6 | 일치 |
| expectedResetEpoch = epochAtTickStart | 2 | 2 | 일치 |

아래 JSON은 무수정 worktree Coordinator에 실제 CLI를 실행해 stdout에서 얻은 전체 값이다.

```json
{
  "Synchronized_annotations": 6,
  "expectedResetEpoch_epochAtTickStart": 2,
  "hook_calls": {
    "afterDebugEpochCapturedBeforePublish": 1,
    "afterDebugPublicationBeforeEffects": 1,
    "beforeDebugOverlayEffect": 1,
    "beforeInactiveSessionCleanupCommit": 1,
    "beforePublicationNotificationCommit": 2,
    "beforePublicationPopupAccountingCommit": 2,
    "beforeRenewalDowngradeCleanup": 1
  },
  "k5_calls": {
    "appUsageMonitor.latestBankingForegroundEventTimestamp": 1,
    "callMonitor.currentCallId": 8,
    "callMonitor.isTelebankingAnchorHot": 1,
    "clock": 6,
    "cooldownManager.isShowing": 4,
    "overlayManager.isEndCallSuppressed": 2,
    "sessionTracker.isCurrentSessionIdleTimedOut": 1,
    "sessionTracker.isSnoozeActive": 2,
    "sessionTracker.isSnoozedForCall": 1,
    "sessionTracker.snoozedAtOrNull": 1,
    "sessionTracker.snoozedCallIdOrNull": 1
  },
  "k5_property_reads": {
    "cooldownManager.dismissedAtMillis": 1,
    "cooldownManager.lastCountdownSec": 1,
    "cooldownManager.showedAtMillis": 1,
    "sessionTracker.sessionState.value": 5,
    "sessionTracker.userResetEpoch": 17
  },
  "k5_volatile_reads": {
    "cooldownConsumedSessionId": 7,
    "previousBankingForeground": 2,
    "s2RecRefireState": 6
  },
  "log_calls": 48,
  "log_string_literals_multiset": {
    "\"$reason — active Event publication retained\"": 1,
    "\"$reason — dismiss overlay/cooldown and clear current event\"": 1,
    "\"$reason — fail-closed PENDING Event retained\"": 1,
    "\"$reason — fail-closed safe-confirm warning retained for retry\"": 1,
    "\"$reason — newer Debug binding retained\"": 1,
    "\"$reason — newer Event context retained\"": 1,
    "\"$reason — newer PUBLISHED Event retained\"": 1,
    "\"Debug overlay skipped: production Event publication is pending\"": 1,
    "\"Debug overlay skipped: published Event was replaced before binding\"": 1,
    "\"anchorHotState mirror → $hot\"": 1,
    "\"call became IDLE during suppression, stabilization scheduled\"": 1,
    "\"cooldownConsumedSessionId cleared: session changed (was=$cooldownConsumedSessionId, now=${session.id})\"": 1,
    "\"cooldownConsumedSessionId cleared: session disappeared (was=$cooldownConsumedSessionId)\"": 1,
    "\"cooldownConsumedSessionId set=${session.id}: banking cooldown fired (level=${score.level}, isCallActive=$isCallActive)\"": 1,
    "\"cooldownConsumedSessionId set=${session.id}: telebanking cooldown fired (level=${score.level})\"": 1,
    "\"coordinator started\"": 1,
    "\"event publication failed; PENDING provenance retained\"": 1,
    "\"event publication superseded — abort escalation effects\"": 1,
    "\"event publication superseded — abort new-trigger effects\"": 1,
    "\"exact Event cleanup deferred: safe-confirm retry retains ${publication.eventId}\"": 1,
    "\"ghost check fallback: now=$now, dismissedAt=$dismissedAt, window=${fallbackWindow}ms → ghost=$isFallbackGhost\"": 1,
    "\"ghost check: eventTs=$eventTs, showedAt=$showedAt, dismissedAt=$dismissedAt → ghost=$isGhost\"": 1,
    "\"notification escalation: alertState=${session.notifiedAlertState}→$alertState, level=${session.notifiedLevel}→${score.level}\"": 1,
    "\"origin=${pending.request.origin}, subject=${pending.request.subject}\"": 1,
    "\"overlay show skipped: trusted Event binding is not current (${event.id})\"": 1,
    "\"popup shown on state transition → $alertState (s2Snapshot=${s2RecRefireState.snapshot})\"": 1,
    "\"popup suppressed by S2 REC-REFIRE debounce (escalation path) — rawTick=$rawTickSignals, snapshot=${s2RecRefireState.snapshot}\"": 1,
    "\"popup suppressed by S2 REC-REFIRE debounce (new-trigger path) — new=$newTriggers, snapshot=${s2RecRefireState.snapshot}\"": 1,
    "\"popup suppressed: cooldown fired this tick\"": 1,
    "\"publication replaced — abort new-trigger effects\"": 1,
    "\"publication replaced — abort new-trigger notification\"": 1,
    "\"publication replaced — abort notification commit\"": 1,
    "\"publication replaced — abort notification escalation\"": 1,
    "\"renewal downgraded to $alertState — dismiss stale popup/current event\"": 1,
    "\"safe-confirm failed closed at ${pending.nextStep}: \"": 1,
    "\"safe-confirm failed closed: origin=${request.origin}, subject=${request.subject}\"": 1,
    "\"session score: total=${score.total}, level=${score.level}, alertState=$alertState, sessionId=${session.id}\"": 1,
    "\"signal tick — rawCall=$callSignals, app=$appSignals, banking=$bankingForeground, install=$installSignals, deviceEnv=$deviceEnvSignals, advanced=$advancedSources\"": 1,
    "\"snooze filter applied (callId=$liveCallId): rawCall=$callSignals → filteredCall=$filtered\"": 1,
    "\"snooze still active — skip popup/notification/cooldown this tick (callId=$liveCallId)\"": 1,
    "\"suppression active, skip popup/notification/cooldown\"": 1,
    "\"tick predates user reset — session transition skipped\"": 1,
    "\"user reset during tick — abort $stage\"": 1,
    "\"뱅킹 쿨다운 발동: level=${score.level}, alertState=$alertState, reason=$reason\"": 1,
    "\"뱅킹 쿨다운 생략: call-based 세션 아님\"": 1,
    "\"뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id}, alertState=$alertState)\"": 1,
    "\"새 trigger 팝업: new=$newTriggers (s2Snapshot=${s2RecRefireState.snapshot})\"": 1,
    "\"텔레뱅킹 쿨다운 발동: level=${score.level}\"": 1,
    "\"텔레뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id})\"": 1
  },
  "previousBankingForeground_assignments": 25,
  "return_collect": 26,
  "s2_calls": {
    "s2RecRefireStateAfterFiring": 2,
    "shouldSuppressS2RecRefire": 2
  },
  "synchronized_calls": 10,
  "userResetIntervened_labels_multiset": {
    "\"cooldown stage\"": 1,
    "\"escalation effects\"": 1,
    "\"escalation popup show\"": 1,
    "\"escalation post-navigation gate\"": 1,
    "\"escalation post-push\"": 1,
    "\"new-trigger effects\"": 1,
    "\"new-trigger popup show\"": 1,
    "\"new-trigger post-navigation gate\"": 1,
    "\"new-trigger post-push\"": 1,
    "\"post ghost query\"": 1
  }
}
```

### 미확인 / Claude 실행 요청

- Kotlin 컴파일, 신규 7 tests GREEN, 기존 회귀, lint, XML fresh timestamp/모듈별 suites·tests·skipped, 검증기 및 self-check는 모두 미실행이다. 완료 게이트 결과를 추정하지 않는다.
- 소스 대조상 컴파일 확신이 낮은 특정 API는 발견하지 못했다. 가장 먼저 실행 확인할 지점은 flow delegation으로 CALL만 보류한 재시작과 UnconfinedTestDispatcher 하의 hook Job 취소 완료, publication 교체/양성 대조의 효과 수다.
- 먼저 아래 필터로 R1.0만 실행한 뒤 라운드 표적 전체를 fresh 실행한다. 둘 다 직렬 실행. 기대 신규 파일 결과는 1 suite / 7 tests / skipped 0이지만 아직 관측하지 않았다.

```powershell
Set-Location 'C:/Users/momen/AndroidStudioProjects/Senior_Shield/.worktrees/refactor-coordinator-r1'
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew --no-daemon --no-parallel --max-workers=1 :app:testDebugUnitTest --tests 'com.example.seniorshield.monitoring.orchestrator.CoordinatorTickCharacterizationTest' --rerun-tasks
./gradlew --no-daemon --no-parallel --max-workers=1 :app:testDebugUnitTest --tests 'com.example.seniorshield.monitoring.orchestrator.*' --tests 'com.example.seniorshield.core.overlay.RiskOverlayManagerBindingContractTest' --rerun-tasks
```

- 이어 DIRECTIVE §6의 전체 직렬 게이트/검증기/self-check로 R1.0 포함 모듈별 기준선을 기록한다. 필터 실행 결과만 전체 baseline으로 사용하지 않는다.
- Claude 결과 회신 및 별도 R1.1 지시 전까지 여기서 멈춘다. R1.2 가능 여부는 GREEN과 독립 검토 후 판단한다.

### 최종 범위 확인 (Python 실측 2026-10-07T13:50:15.402282+09:00)

- worktree tracked diff / staged diff / git diff --check: 모두 빈 출력, 종료코드 0.
- worktree status의 untracked는 허용된 신규 테스트 1개뿐이다.
- 7개 @Test와 로그의 테스트명 매핑 일치. 실제 CLI 전 JSON과 로그 내 JSON 깊은 비교 일치.
- 최종 자체검토: assertion 입력 차이, 훅 진입/취소 완료, 교체 publisher 효과 제외, 후속 new-trigger 양성 대조, 기존 파일 무수정 확인.

```text
?? app/src/test/java/com/example/seniorshield/monitoring/orchestrator/CoordinatorTickCharacterizationTest.kt
```


## R1.1 — T003

### REVIEW 반영 (코드 분해 전)

- `20261007-1359-T002-REVIEW` 전체를 읽었다. NOTE 1에 따라 (b)의 두 행을 `TESTED+STRUCTURAL`로 보강한다. 원래 C:667·673은 bankingForeground 계산(C:697–699)보다 앞에 있고, 대입 없이 반환한다. R1.1에서도 두 반환을 processTick 안에서 banking 계산 및 이후 단계 호출보다 앞에 유지한다. R1.2에서도 이 경계를 유지해야 한다.
- Claude 보고: R1.0 무수정 제품 코드에서 7/7 GREEN, XML 2026-10-07T04:59:02Z. 표적 결과 10 suites / 184 tests / failures 0 / errors 0 / skipped 0, BUILD SUCCESSFUL 6m 8s. 이는 Claude 실행 증거이며 Codex 실행 결과가 아니다.
- Claude 확정 구성 기준선: app 551 tests / 40 suites, risk 7/1, contracts 4/1, 총 562/42. main(base 9657cf4) 전체 게이트와 신규 7 tests/1 suite를 합친 기준이다. worktree 전체 게이트는 최종 1회 실행한다는 Claude 판단을 반영한다.
- main 검증기 결과(Claude 보고): unit app 544/39·risk 7/1·contracts 4/1 PASS, domain lint PASS, lint-union app 65/69·data 14/14 PASS.
- NOTE 2: (e) 중단 전파 제거 및 (c) finally 대입 주입 mutation probe는 Claude가 R1.1 이후 실행한다. Codex는 제품 mutation probe/Gradle/adb를 실행하지 않는다.
- 이번 쓰기 범위: Coordinator 소스 1개 및 이 로그. 모든 테스트·스크립트·기존 소스의 보호 구간은 무수정. R1.2 대입 통합은 수행하지 않는다.


### 변경 요약 / 단계 함수 목록

- `start()`는 기존 merge/collect 흐름을 유지하고 launch 내부의 `TickLoopState`를 `processTick`에 전달한다.
- 새 file-private 타입은 `TickLoopState`와 `TickContext` 정확히 2개다. 전자는 launch 상태만, 후자는 해당 tick의 기존 지역 값만 저장한다. 둘 다 lock owner가 아니다.
- Boolean 단계는 false=중단, true=계속이다. cooldown/escalation의 Boolean?는 null=중단이며 false도 정상 진행 값이다. escalation의 Boolean 값은 기존 popupShownThisTick이다.

| 함수 | 새 줄 | 반환형 | suspend | 역할 |
|---|---:|---|---|---|
| `processTick` | 653 | `Unit` | 예 | maintenance 게이트와 K4 순서 실행·중단 전파 |
| `prepareTickSignals` | 708 | `TickContext` | 아니오 | freshness·banking 정규화·sequence·진단 로그 |
| `scheduleSuppressionReleaseForTick` | 749 | `Unit` | 아니오 | suppression IDLE 예약 |
| `filterTickCallSignals` | 761 | `Unit` | 아니오 | liveCallId 캡처·snooze 평가/필터 |
| `validateRenewalToken` | 805 | `Unit` | 아니오 | renewal 검증 및 세 가지 지역 flag |
| `transitionTickSession` | 870 | `Boolean` | 예 | transition·aborted/no-session 반환·cleanup |
| `issueTickRenewalToken` | 908 | `Unit` | 아니오 | renewal token 발급 |
| `evaluateTickSession` | 945 | `Unit` | 예 | cooldownConsumed 정리·평가·downgrade/재무장 |
| `canPresentTick` | 993 | `Boolean` | 아니오 | OBSERVE/suppression 출구 |
| `syncTickTriggers` | 1010 | `Boolean` | 아니오 | trigger sync·snooze 출구·cooldown 표시 중 마킹 |
| `processTickCooldown` | 1050 | `Boolean?` | 아니오 | epoch(b)·banking/telebanking cooldown |
| `processTickEscalation` | 1123 | `Boolean?` | 예 | 기존 escalation 본문 전체 |
| `processTickNewTriggers` | 1223 | `Boolean` | 예 | 기존 new-trigger 본문 전체 |

- `TickLoopState`: 1841행.

- `TickContext`: 1852행.

### 출구 대응표 — 26행

원래 줄은 base 9657cf4 기준, 새 줄은 R1.1 최종 소스 기준이다. 단계의 중단 결과와 최상위 processTick 반환 사이에 다른 부수효과는 없다.

| 원래 줄 | 새 위치 (함수:줄) | banking 대입 | 호출부 전파 경로 |
|---:|---|---|---|
| 667 | `processTick:665` | 아니오 | processTick:665 return → collect 호출 완료 |
| 673 | `processTick:671` | 아니오 | processTick:671 return → collect 호출 완료 |
| 820 | `transitionTickSession:883` | 예 (반환 직전) | transitionTickSession:883 return false → processTick:681 즉시 return → collect 호출 완료 |
| 831 | `transitionTickSession:894` | 예 (반환 직전) | transitionTickSession:894 return false → processTick:681 즉시 return → collect 호출 완료 |
| 915 | `canPresentTick:997` | 예 (반환 직전) | canPresentTick:997 return false → processTick:684 즉시 return → collect 호출 완료 |
| 922 | `canPresentTick:1004` | 예 (반환 직전) | canPresentTick:1004 return false → processTick:684 즉시 return → collect 호출 완료 |
| 942 | `syncTickTriggers:1029` | 예 (반환 직전) | syncTickTriggers:1029 return false → processTick:685 즉시 return → collect 호출 완료 |
| 959 | `processTickCooldown:1055` | 예 (반환 직전) | processTickCooldown:1055 return null → processTick:686 즉시 return → collect 호출 완료 |
| 976 | `processTickCooldown:1072` | 예 (반환 직전) | processTickCooldown:1072 return null → processTick:686 즉시 return → collect 호출 완료 |
| 1027 | `processTick:691` | 예 (반환 직전) | processTick:691 return → collect 호출 완료 |
| 1045 | `processTickEscalation:1140` | 예 (반환 직전) | processTickEscalation:1140 return null → processTick:693 즉시 return → collect 호출 완료 |
| 1056 | `processTickEscalation:1151` | 예 (반환 직전) | processTickEscalation:1151 return null → processTick:693 즉시 return → collect 호출 완료 |
| 1063 | `processTickEscalation:1158` | 예 (반환 직전) | processTickEscalation:1158 return null → processTick:693 즉시 return → collect 호출 완료 |
| 1070 | `processTickEscalation:1165` | 예 (반환 직전) | processTickEscalation:1165 return null → processTick:693 즉시 return → collect 호출 완료 |
| 1082 | `processTickEscalation:1177` | 예 (반환 직전) | processTickEscalation:1177 return null → processTick:693 즉시 return → collect 호출 완료 |
| 1098 | `processTickEscalation:1193` | 예 (반환 직전) | processTickEscalation:1193 return null → processTick:693 즉시 return → collect 호출 완료 |
| 1102 | `processTickEscalation:1197` | 예 (반환 직전) | processTickEscalation:1197 return null → processTick:693 즉시 return → collect 호출 완료 |
| 1117 | `processTickEscalation:1212` | 예 (반환 직전) | processTickEscalation:1212 return null → processTick:693 즉시 return → collect 호출 완료 |
| 1130 | `processTick:700` | 예 (반환 직전) | processTick:700 return → collect 호출 완료 |
| 1147 | `processTickNewTriggers:1239` | 예 (반환 직전) | processTickNewTriggers:1239 return false → processTick:702 즉시 return → collect 호출 완료 |
| 1154 | `processTickNewTriggers:1246` | 예 (반환 직전) | processTickNewTriggers:1246 return false → processTick:702 즉시 return → collect 호출 완료 |
| 1159 | `processTickNewTriggers:1251` | 예 (반환 직전) | processTickNewTriggers:1251 return false → processTick:702 즉시 return → collect 호출 완료 |
| 1164 | `processTickNewTriggers:1256` | 예 (반환 직전) | processTickNewTriggers:1256 return false → processTick:702 즉시 return → collect 호출 완료 |
| 1173 | `processTickNewTriggers:1265` | 예 (반환 직전) | processTickNewTriggers:1265 return false → processTick:702 즉시 return → collect 호출 완료 |
| 1180 | `processTickNewTriggers:1272` | 예 (반환 직전) | processTickNewTriggers:1272 return false → processTick:702 즉시 return → collect 호출 완료 |
| 1196 | `processTickNewTriggers:1288` | 예 (반환 직전) | processTickNewTriggers:1288 return false → processTick:702 즉시 return → collect 호출 완료 |

원래 정상 종료 대입 C:1202는 `processTick:704`에 남는다. 출구 26개 = 무대입 maintenance 2개 + 반환 직전 대입 24개. 정상 대입 1개를 합쳐 총 25개이며 R1.2 통합은 수행하지 않았다.

### K2·K3 등가성 / captured var·suspend 검토

- **K2 수명**: `if (job?.isActive == true) return`과 `scope.launch`는 그대로다. TickLoopState 생성은 launch 안·collect 밖이고, 재시작마다 새 객체다. latestSignals/renewalToken/lastSeenSeq의 초기값과 갱신 지점은 기존 그대로이며, loopState 필드 접근으로만 치환했다. 다른 coroutine/필드에 이 holder를 저장하지 않는다.
- **K3 출구**: 위 26개를 전수 대응했다. processTick의 모든 중단 가능한 단계 호출은 `if (!...) return` 또는 `?: return`이다. helper 중단이 다른 단계나 정상 종료 대입으로 흘러가지 않는다. epoch(c)/(d)는 processTick 안에서 기존 대입 후 직접 반환한다.
- **예외**: 새 catch/finally/취소 체크가 없다. escaping exception/cancellation은 호출 스택을 그대로 빠져나가며 banking 대입을 추가하지 않는다. 기존 publisher의 일반 push 예외→null은 escalation/new-trigger의 기존 위치에서 대입 후 중단한다.
- **escalation/new-trigger**: C:1030–1123와 C:1133–1200 각각 전체를 하나의 함수로 이동했다. 내부 helper 분할·publication gate 공통화(K6)는 하지 않았다. 원문 블록의 차이는 indentation과 `return@collect`→중단 값뿐이다.
- **지역 값 전달**: filter는 currentCallId 조회→nonCallSignals 계산→snooze/필터 순서를 유지한 뒤 값을 TickContext에 기록한다. renewal 세 flag도 지역 변수로 계산한 뒤 기록한다. transition의 snapshot session/outcome은 cleanup 후 전달하며 tracker를 재조회하지 않는다. evaluate의 score/alertState는 기존 순서로 계산하고 downgrade 처리 후 전달한다.
- **초기화 지배 관계**: prepare의 constructor vals → filter의 nonCallSignals/filteredCallSignals/liveCallId → validate의 flags → transition 성공의 outcome/session → evaluate의 score/alertState → sync 성공의 rawTickSignals/syncedSession/triggers/activeTriggers 순이다. transition/sync가 중단되면 뒤쪽 lateinit 값을 읽기 전에 processTick이 반환한다. holder는 해당 tick에서만 사용되며 다른 흐름에 노출되지 않는다.
- **syncedSession**: C:952의 copy 갱신을 마친 지역 값을 context에 전달한다. cooldown의 tracker 마킹 뒤에도 이 snapshot을 사용한다. 최신 tracker 값으로 대체하지 않는다.
- **captured vars**: popupShownThisTick은 escalation 내부 지역 var로 남으며 overlay 회계 callback에서만 true가 된다. cooldownFiredThisTick은 cooldown 단계에서 계산되어 같은 값으로 두 후속 함수에 전달된다. nowMs는 각 경로의 guardian 조회 전 val을 callback이 그대로 캡처한다.
- **suspend**: processTick/transitionTickSession/evaluateTickSession/processTickEscalation/processTickNewTriggers는 suspend다. 기존 suspend cleanup·hook·publication·guardian 호출을 옮긴 함수에 빠짐없이 suspend가 있다. `with(context)`는 inline이므로 내부의 일반 return은 해당 단계 함수로 비지역 반환한다. 새로운 coroutine launch/dispatcher 전환/suspension 지점은 없다.
- **락 대상**: 기존 `synchronized(this@DefaultRiskDetectionCoordinator)` 식과 블록은 그대로다. with receiver가 TickContext여도 qualified this가 coordinator를 가리킨다. 원래 tick 본문의 다른 this 사용은 없다.

### K5 지점별 조회 보존

다음 표의 모든 원문 구간을 신 코드와 기계 대조했다. 단계 본문 15구간이 indentation, loopState 필드 한정 및 반환형 치환을 제외하고 동일하다. 원래 C:656–1202의 비공백 줄은 전부 대응하며 정상 대입도 별도로 확인했다. context 전송에는 외부 상태 조회가 없다.

| 대상 | 원래 줄 → 새 함수:줄 | 단락 평가·시점 근거 |
|---|---|---|
| sessionTracker.userResetEpoch | 684→prepareTickSignals:715 | maintenance 두 출구 다음 1회 캡처; 기존 helper 내부 조회는 보호 구간 그대로 |
| sessionTracker.sessionState.value | 761→validateRenewalToken:817 | renewal token 존재 분기 안의 sessionGone 계산 위치 유지 |
| clock() | 727→filterTickCallSignals:776; 760→validateRenewalToken:816; 866→issueTickRenewalToken:937; 1088→processTickEscalation:1183; 1139→processTickNewTriggers:1231 | snooze/renewal/토큰/각 popup 원래 분기에서 호출; guardian 전 nowMs 유지 |
| currentCallId() | 718→filterTickCallSignals:767 | suppression 예약 뒤, snooze 평가 전에 한 번 캡처 |
| isSnoozeActive() | 724→filterTickCallSignals:773; 938→syncTickTriggers:1025 | pre-update와 trigger sync 뒤의 별도 조회 두 곳 유지 |
| snoozed* / isSnoozedForCall | 725→filterTickCallSignals:774; 726→filterTickCallSignals:775; 743→filterTickCallSignals:792 | 활성 snooze 분기 및 liveCallId != null 단락 조건 유지 |
| isEndCallSuppressed() | 709→scheduleSuppressionReleaseForTick:753; 919→canPresentTick:1001 | IDLE 예약과 presentation 출구의 별도 조회 유지 |
| isShowing() | 948→syncTickTriggers:1035; 1004→processTickCooldown:1100; 1086→processTickEscalation:1181; 1138→processTickNewTriggers:1230 | 4곳 각각의 && 조건 및 평가 위치 유지; Boolean 인자 캐싱 없음 |
| previousBankingForeground 읽기 | 698→prepareTickSignals:729; 967→processTickCooldown:1063 | stale banking 분기와 bankingForeground && !previous 단락 유지 |
| cooldownConsumedSessionId 참조/쓰기 | 872→evaluateTickSession:948; 873→evaluateTickSession:949; 874→evaluateTickSession:950; 981→processTickCooldown:1077; 992→processTickCooldown:1088; 993→processTickCooldown:1089; 1006→processTickCooldown:1102; 1016→processTickCooldown:1112; 1017→processTickCooldown:1113 | 세션 변경의 두 읽기·로그 보간·clear와 각 cooldown 비교/쓰기 순서 그대로 |
| s2RecRefireState 참조/쓰기 | 1089→processTickEscalation:1184; 1090→processTickEscalation:1185; 1110→processTickEscalation:1205; 1113→processTickEscalation:1208; 1140→processTickNewTriggers:1232; 1141→processTickNewTriggers:1233; 1188→processTickNewTriggers:1280; 1192→processTickNewTriggers:1284 | 판정/로그 조회 및 overlay 회계 callback 쓰기 위치 그대로 |
| expectedResetEpoch 인자 | 989→processTickCooldown:1085; 1013→processTickCooldown:1109 | banking·telebanking 두 triggerIfNotActive 호출 안에 리터럴 그대로 |

추가 조회 `isCurrentSessionIdleTimedOut()`는 maintenance 분기 안에서 유지한다. `isCooldownGhostTransition()`은 bankingJustOpened 및 alertState 조건 뒤의 단락 평가 안에 남으며, helper 내부 timestamp/clock 조회는 원문 무수정이다. K5의 파일 전체 호출·property·volatile 읽기 계수도 모두 동일하다. 새 context는 기존 지역 값의 전달 수단이며 query 결과를 새로 미리 계산하거나 기존 volatile 필드를 cache하지 않는다.

### K1·K7 / 정적 계수 전후

- start 이전 원문(1–626행), stop부터 기존 파일 끝까지의 원문을 base와 대조해 일치했다(newline 표현만 통일해 비교). 기존 생성자/API/필드/annotation/import/하단 타입/메서드 변경 없음. 추가 타입 2개는 기존 파일 끝 뒤에 덧붙였다.
- Log 48 calls 및 literal multiset 49개, reset 라벨 multiset 10개, hooks 7종, K5 전 항목, S2 두 함수, 락/epoch 인자, 대입 25 모두 깊은 비교 일치. `return_collect` 26→0은 승인된 변화다. 그 밖 **불일치 0**.
- 새 함수의 이름·반환형·호출부를 대조했고 delimiter 균형도 검사했다. 이는 Kotlin compiler 실행을 대체하지 않는다.
- 읽기 전용 하위 에이전트(gpt-6-luna high)가 출구 26개와 API/반환형/lateinit/기존 source-text 계약을 독립 기계 대조했다. 기존 RiskOverlayManagerBindingContractTest의 epoch 인자 2회 조건 유지; 다른 관련 source-text 테스트가 읽는 파일은 무수정이다.

| 정적 항목 | 전 | 후 | 판정 |
|---|---|---|---|
| `Synchronized_annotations` | 6 | 6 | 일치 |
| `expectedResetEpoch_epochAtTickStart` | 2 | 2 | 일치 |
| `hook_calls` | 7 keys / total 9 | 7 keys / total 9 | 전체 dictionary 일치 |
| `k5_calls` | 11 keys / total 28 | 11 keys / total 28 | 전체 dictionary 일치 |
| `k5_property_reads` | 5 keys / total 25 | 5 keys / total 25 | 전체 dictionary 일치 |
| `k5_volatile_reads` | 3 keys / total 15 | 3 keys / total 15 | 전체 dictionary 일치 |
| `log_calls` | 48 | 48 | 일치 |
| `log_string_literals_multiset` | 49 keys / total 49 | 49 keys / total 49 | 전체 dictionary 일치 |
| `previousBankingForeground_assignments` | 25 | 25 | 일치 |
| `return_collect` | 26 | 0 | 허용된 변화 |
| `s2_calls` | 2 keys / total 4 | 2 keys / total 4 | 전체 dictionary 일치 |
| `synchronized_calls` | 10 | 10 | 일치 |
| `userResetIntervened_labels_multiset` | 10 keys / total 10 | 10 keys / total 10 | 전체 dictionary 일치 |

정적 계수 **후** (기존 스크립트를 python -I로 실행한 stdout 전체):

```json
{
  "Synchronized_annotations": 6,
  "expectedResetEpoch_epochAtTickStart": 2,
  "hook_calls": {
    "afterDebugEpochCapturedBeforePublish": 1,
    "afterDebugPublicationBeforeEffects": 1,
    "beforeDebugOverlayEffect": 1,
    "beforeInactiveSessionCleanupCommit": 1,
    "beforePublicationNotificationCommit": 2,
    "beforePublicationPopupAccountingCommit": 2,
    "beforeRenewalDowngradeCleanup": 1
  },
  "k5_calls": {
    "appUsageMonitor.latestBankingForegroundEventTimestamp": 1,
    "callMonitor.currentCallId": 8,
    "callMonitor.isTelebankingAnchorHot": 1,
    "clock": 6,
    "cooldownManager.isShowing": 4,
    "overlayManager.isEndCallSuppressed": 2,
    "sessionTracker.isCurrentSessionIdleTimedOut": 1,
    "sessionTracker.isSnoozeActive": 2,
    "sessionTracker.isSnoozedForCall": 1,
    "sessionTracker.snoozedAtOrNull": 1,
    "sessionTracker.snoozedCallIdOrNull": 1
  },
  "k5_property_reads": {
    "cooldownManager.dismissedAtMillis": 1,
    "cooldownManager.lastCountdownSec": 1,
    "cooldownManager.showedAtMillis": 1,
    "sessionTracker.sessionState.value": 5,
    "sessionTracker.userResetEpoch": 17
  },
  "k5_volatile_reads": {
    "cooldownConsumedSessionId": 7,
    "previousBankingForeground": 2,
    "s2RecRefireState": 6
  },
  "log_calls": 48,
  "log_string_literals_multiset": {
    "\"$reason — active Event publication retained\"": 1,
    "\"$reason — dismiss overlay/cooldown and clear current event\"": 1,
    "\"$reason — fail-closed PENDING Event retained\"": 1,
    "\"$reason — fail-closed safe-confirm warning retained for retry\"": 1,
    "\"$reason — newer Debug binding retained\"": 1,
    "\"$reason — newer Event context retained\"": 1,
    "\"$reason — newer PUBLISHED Event retained\"": 1,
    "\"Debug overlay skipped: production Event publication is pending\"": 1,
    "\"Debug overlay skipped: published Event was replaced before binding\"": 1,
    "\"anchorHotState mirror → $hot\"": 1,
    "\"call became IDLE during suppression, stabilization scheduled\"": 1,
    "\"cooldownConsumedSessionId cleared: session changed (was=$cooldownConsumedSessionId, now=${session.id})\"": 1,
    "\"cooldownConsumedSessionId cleared: session disappeared (was=$cooldownConsumedSessionId)\"": 1,
    "\"cooldownConsumedSessionId set=${session.id}: banking cooldown fired (level=${score.level}, isCallActive=$isCallActive)\"": 1,
    "\"cooldownConsumedSessionId set=${session.id}: telebanking cooldown fired (level=${score.level})\"": 1,
    "\"coordinator started\"": 1,
    "\"event publication failed; PENDING provenance retained\"": 1,
    "\"event publication superseded — abort escalation effects\"": 1,
    "\"event publication superseded — abort new-trigger effects\"": 1,
    "\"exact Event cleanup deferred: safe-confirm retry retains ${publication.eventId}\"": 1,
    "\"ghost check fallback: now=$now, dismissedAt=$dismissedAt, window=${fallbackWindow}ms → ghost=$isFallbackGhost\"": 1,
    "\"ghost check: eventTs=$eventTs, showedAt=$showedAt, dismissedAt=$dismissedAt → ghost=$isGhost\"": 1,
    "\"notification escalation: alertState=${session.notifiedAlertState}→$alertState, level=${session.notifiedLevel}→${score.level}\"": 1,
    "\"origin=${pending.request.origin}, subject=${pending.request.subject}\"": 1,
    "\"overlay show skipped: trusted Event binding is not current (${event.id})\"": 1,
    "\"popup shown on state transition → $alertState (s2Snapshot=${s2RecRefireState.snapshot})\"": 1,
    "\"popup suppressed by S2 REC-REFIRE debounce (escalation path) — rawTick=$rawTickSignals, snapshot=${s2RecRefireState.snapshot}\"": 1,
    "\"popup suppressed by S2 REC-REFIRE debounce (new-trigger path) — new=$newTriggers, snapshot=${s2RecRefireState.snapshot}\"": 1,
    "\"popup suppressed: cooldown fired this tick\"": 1,
    "\"publication replaced — abort new-trigger effects\"": 1,
    "\"publication replaced — abort new-trigger notification\"": 1,
    "\"publication replaced — abort notification commit\"": 1,
    "\"publication replaced — abort notification escalation\"": 1,
    "\"renewal downgraded to $alertState — dismiss stale popup/current event\"": 1,
    "\"safe-confirm failed closed at ${pending.nextStep}: \"": 1,
    "\"safe-confirm failed closed: origin=${request.origin}, subject=${request.subject}\"": 1,
    "\"session score: total=${score.total}, level=${score.level}, alertState=$alertState, sessionId=${session.id}\"": 1,
    "\"signal tick — rawCall=$callSignals, app=$appSignals, banking=$bankingForeground, install=$installSignals, deviceEnv=$deviceEnvSignals, advanced=$advancedSources\"": 1,
    "\"snooze filter applied (callId=$liveCallId): rawCall=$callSignals → filteredCall=$filtered\"": 1,
    "\"snooze still active — skip popup/notification/cooldown this tick (callId=$liveCallId)\"": 1,
    "\"suppression active, skip popup/notification/cooldown\"": 1,
    "\"tick predates user reset — session transition skipped\"": 1,
    "\"user reset during tick — abort $stage\"": 1,
    "\"뱅킹 쿨다운 발동: level=${score.level}, alertState=$alertState, reason=$reason\"": 1,
    "\"뱅킹 쿨다운 생략: call-based 세션 아님\"": 1,
    "\"뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id}, alertState=$alertState)\"": 1,
    "\"새 trigger 팝업: new=$newTriggers (s2Snapshot=${s2RecRefireState.snapshot})\"": 1,
    "\"텔레뱅킹 쿨다운 발동: level=${score.level}\"": 1,
    "\"텔레뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id})\"": 1
  },
  "previousBankingForeground_assignments": 25,
  "return_collect": 0,
  "s2_calls": {
    "s2RecRefireStateAfterFiring": 2,
    "shouldSuppressS2RecRefire": 2
  },
  "synchronized_calls": 10,
  "userResetIntervened_labels_multiset": {
    "\"cooldown stage\"": 1,
    "\"escalation effects\"": 1,
    "\"escalation popup show\"": 1,
    "\"escalation post-navigation gate\"": 1,
    "\"escalation post-push\"": 1,
    "\"new-trigger effects\"": 1,
    "\"new-trigger popup show\"": 1,
    "\"new-trigger post-navigation gate\"": 1,
    "\"new-trigger post-push\"": 1,
    "\"post ghost query\"": 1
  }
}
```

### S2 8항목 자체검토 (정적 판정)

| 항목 | 판정 / 근거 |
|---|---|
| 1. 일단 닫기 dismiss-only | 보존 — CTA 코드 무수정 |
| 2. safe-confirm 별도 흐름 | 보존 — 기존 command/publication 보호 구간 무수정 |
| 3. REC-REFIRE는 S2 orchestration | 보존 — 기존 S2 호출을 두 tick 단계로만 이동; CTA 영향 없음 |
| 4. TTL `>` 경계 | 보존 — S2 함수/상수 파일 무수정 |
| 5. 비통화 dismiss의 call-safe 효과 없음 | 보존 — CTA/overlay/monitor 무수정 |
| 6. 통화 safe-confirm 전용 부수효과 | 보존 — 해당 경로 무수정 |
| 7. α/S2 분리 | 보존 — 공용 debounce helper/state/상수/테스트 도입 없음 |
| 8. UPGRADE_TRIGGERS 단일화 없음 | 보존 — 모든 기존 set 및 파일 무수정 |

### 확인한 것 / 미확인 / 다음 실행

- 코드·문서 자체검토와 정적 도구 실행만 수행했다. R1.1 Kotlin 컴파일·표적 GREEN·mutation probe·lint·APK 크기·전체 게이트는 미실행(Claude 담당). R1.0 GREEN을 R1.1 GREEN으로 간주하지 않는다.
- 소스 대조에서 명백한 컴파일 오류는 찾지 못했다. nullable Boolean의 null/false 구분, inline with의 비지역 return, suspend 호출 이동과 TickContext lateinit 초기화 지배 관계는 Claude compile/runtime으로 확인해야 한다.
- 별도 검증 스크립트/출력 파일은 쓰지 않았다. 일회성 Python 검증은 메모리에서 수행했다. 본문 대조의 최초 탐침은 단일 정상 대입 문자열이 25곳에 중복되어 유일성 assertion이 실패했으며, 함수 범위를 제한한 검사로 수정해 정상 대입과 15개 원문 블록 전체를 확인했다. 소스 결함이나 계수 불일치는 아니었다.
- 다음 표적 실행(cwd=worktree, JBR):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew --no-daemon --no-parallel --max-workers=1 :app:testDebugUnitTest --tests 'com.example.seniorshield.monitoring.orchestrator.*' --tests 'com.example.seniorshield.core.overlay.RiskOverlayManagerBindingContractTest' --rerun-tasks
```

- 기대 비교 대상: R1.0 표적 10 suites / 184 tests / skipped 0, 신규 characterization 7/7. actual XML timestamp·counts 확인과 NOTE 2 mutation probe는 Claude 실행 대기.
- R1.2는 착수하지 않았으며 별도 지시 전 여기서 중단한다.

### R1.1 최종 범위 검사 (2026-10-07T14:12:32.600937+09:00)

- base git blob을 worktree의 원래 CRLF로 복원한 SHA-256이 R1.0 기록 ded7b0b052418851e808f0ff7d0d501b37b9eede88e99e9d985b9c26b3aaba33과 일치한다. 이 원본과 원래 1–626 및 stop부터 기존 파일 끝까지의 바이트를 직접 대조: 일치.
- 정적 전/후 차이는 return_collect뿐. 기록한 후 JSON과 최종 CLI 출력 동일. 출구표 26행 및 함수표 13행 확인. 새 file-private 타입 정확히 2개.
- git diff --check 종료코드 0, staged 0. git 쓰기 및 Gradle/adb 실행 0.

```text
 M app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt
?? app/src/test/java/com/example/seniorshield/monitoring/orchestrator/CoordinatorTickCharacterizationTest.kt
```

```text
 .../DefaultRiskDetectionCoordinator.kt             | 1135 +++++++++++---------
 1 file changed, 632 insertions(+), 503 deletions(-)
```


## R1.2 — T004

### REVIEW 반영 / 알려진 트레이드오프

- T003 REVIEW 전체를 읽었다. Claude 보고: R1.1 표적 GREEN(10 suites / 184 tests / failures 0 / errors 0 / skipped 0), fresh XML 05:16:57–05:17:09Z, 정적 독립 대조 일치. M1 중단 전파 제거, M2 finally 대입, M3 no-session 대입 제거 mutation probe가 모두 RED를 검출했고 원본 SHA-256 복원이 확인됐다. 이 증거로 R1.2 진행 조건이 충족됐다.
- 알려진 트레이드오프: TickContext의 lateinit 묶음은 단계 순서에 초기화 의존성을 만들지만, 새 타입 최대 2개 계약에 따른 수용된 선택이다. NOTE대로 관련 코드 변경은 하지 않는다.
- runTickStages로 기존 단계부를 이동하고 내부의 banking 대입 25곳을 제거했다. processTick에서 runTickStages가 정상 반환한 직후 단일 대입한다. maintenance 두 출구는 그대로 banking 계산 전 반환하며, escaping 예외/취소에는 대입하지 않는다.
- 이번 쓰기 범위는 Coordinator 및 이 로그뿐이며, 테스트·하네스·계수 스크립트는 무수정이다.

- R1.1 편집 직전 Coordinator SHA-256: `2d0546fbf61632d6ee45d29a16920dd1eb5f592fb14cc1a38e64f8752ec356e0`. 추출을 역치환하고 대입 줄을 제외한 전체 바이트가 R1.1과 일치함을 검사했다.


### R1.2 출구 대응표 — 26행 (이 표가 최종 위치)

R1.1의 역사적 대응표는 유지한다. 아래는 base 9657cf4의 26개 출구를 R1.2로 갱신한 표다. 하위 단계의 중단은 runTickStages까지 즉시 전파되고, processTick은 정상 반환을 받은 직후 단일 대입하고 종료한다.

| 원래 줄 | 최종 출구 (함수:줄) | 대입 | 대입 도달 / 무대입 경로 |
|---:|---|---|---|
| 667 | `processTick:665` | maintenance 무대입 | processTick:665 return → collect 종료 (banking 계산·runTickStages 호출 전) |
| 673 | `processTick:671` | maintenance 무대입 | processTick:671 return → collect 종료 (banking 계산·runTickStages 호출 전) |
| 820 | `transitionTickSession:886` | 단일 지점(processTick:679) 경유 | transitionTickSession:886 return false → runTickStages:688 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 831 | `transitionTickSession:896` | 단일 지점(processTick:679) 경유 | transitionTickSession:896 return false → runTickStages:688 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 915 | `canPresentTick:998` | 단일 지점(processTick:679) 경유 | canPresentTick:998 return false → runTickStages:691 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 922 | `canPresentTick:1004` | 단일 지점(processTick:679) 경유 | canPresentTick:1004 return false → runTickStages:691 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 942 | `syncTickTriggers:1028` | 단일 지점(processTick:679) 경유 | syncTickTriggers:1028 return false → runTickStages:692 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 959 | `processTickCooldown:1053` | 단일 지점(processTick:679) 경유 | processTickCooldown:1053 return null → runTickStages:693 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 976 | `processTickCooldown:1069` | 단일 지점(processTick:679) 경유 | processTickCooldown:1069 return null → runTickStages:693 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1027 | `runTickStages:697` | 단일 지점(processTick:679) 경유 | runTickStages:697 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1045 | `processTickEscalation:1136` | 단일 지점(processTick:679) 경유 | processTickEscalation:1136 return null → runTickStages:699 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1056 | `processTickEscalation:1146` | 단일 지점(processTick:679) 경유 | processTickEscalation:1146 return null → runTickStages:699 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1063 | `processTickEscalation:1152` | 단일 지점(processTick:679) 경유 | processTickEscalation:1152 return null → runTickStages:699 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1070 | `processTickEscalation:1158` | 단일 지점(processTick:679) 경유 | processTickEscalation:1158 return null → runTickStages:699 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1082 | `processTickEscalation:1169` | 단일 지점(processTick:679) 경유 | processTickEscalation:1169 return null → runTickStages:699 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1098 | `processTickEscalation:1184` | 단일 지점(processTick:679) 경유 | processTickEscalation:1184 return null → runTickStages:699 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1102 | `processTickEscalation:1187` | 단일 지점(processTick:679) 경유 | processTickEscalation:1187 return null → runTickStages:699 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1117 | `processTickEscalation:1201` | 단일 지점(processTick:679) 경유 | processTickEscalation:1201 return null → runTickStages:699 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1130 | `runTickStages:705` | 단일 지점(processTick:679) 경유 | runTickStages:705 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1147 | `processTickNewTriggers:1227` | 단일 지점(processTick:679) 경유 | processTickNewTriggers:1227 return false → runTickStages:707 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1154 | `processTickNewTriggers:1233` | 단일 지점(processTick:679) 경유 | processTickNewTriggers:1233 return false → runTickStages:707 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1159 | `processTickNewTriggers:1237` | 단일 지점(processTick:679) 경유 | processTickNewTriggers:1237 return false → runTickStages:707 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1164 | `processTickNewTriggers:1241` | 단일 지점(processTick:679) 경유 | processTickNewTriggers:1241 return false → runTickStages:707 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1173 | `processTickNewTriggers:1249` | 단일 지점(processTick:679) 경유 | processTickNewTriggers:1249 return false → runTickStages:707 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1180 | `processTickNewTriggers:1255` | 단일 지점(processTick:679) 경유 | processTickNewTriggers:1255 return false → runTickStages:707 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |
| 1196 | `processTickNewTriggers:1270` | 단일 지점(processTick:679) 경유 | processTickNewTriggers:1270 return false → runTickStages:707 즉시 return → processTick:678 정상 반환 → :679 대입 → processTick 종료 |

**정상 종료(C:1202)**: processTickNewTriggers 정상 true → runTickStages:707 통과 → runTickStages 정상 끝 → processTick:678 반환 → processTick:679 단일 대입 → processTick 종료. 위 26개 조기 출구와 별도의 정상 종료 경로다.

### R1.2 제어 흐름 등가성 검증

- base의 15개 tick 본문 구간을 대입 줄 제거·반환값 치환·loopState 필드 접근 치환·들여쓰기 차이만 허용하여 최종 소스와 대조: 전부 일치. 원래 비공백 본문 줄의 누락 없음(25개 대입만 단일 위치로 이동).
- maintenance 2개는 여전히 processTick 안의 prepareTickSignals 호출보다 앞이다. 단계의 aborted/no-session/OBSERVE/suppression/snooze/epoch/publication 실패는 모두 runTickStages 정상 반환으로 바뀌므로 단일 대입에 도달한다.
- escaping exception 또는 CancellationException이면 runTickStages 호출식이 정상 완료되지 않아 그 다음 대입에 도달하지 않는다. try/finally/catch/취소 체크 추가 없음.
- 기존 helper 안에서 처리된 일반 push 실패는 null/false 상태를 반환하고 runTickStages를 정상 종료하므로 대입한다. 단락 평가/volatile 읽기/외부 query/suspend 훅은 추가하거나 옮기지 않았다.
- Q5 및 커버리지 평가는 아래 자체검토에서 분리한다. 정적 대응표를 실행 커버리지로 간주하지 않는다.


### R1.2 정적 계수 / 전체 필드 대입 검증

- R1.1 후 대비 변경 키는 `previousBankingForeground_assignments` 25→1 하나뿐이다. 나머지 12개 최상위 키와 모든 내부 multiset·호출·읽기 계수는 동일하다. 의도하지 않은 불일치 0.
- 같은 스크립트의 특정 RHS 계수와 별도로, 파일 전체 Kotlin token에서 `previousBankingForeground` 다음 단일 `=`를 찾아 선언(`var`/`val`)을 분리했다: 실행 중 필드 대입 1개(processTick:679, RHS bankingForeground), 선언 초기화 1개(190행, RHS false). 원래 선언 초기화는 K7 보존 대상이므로 변경하지 않았다. plain `rg --pcre2 'previousBankingForeground\s*=(?!=)'`도 이 두 줄만 반환한다. 선언을 포함한 문자열 출현 2개를 실행 대입 2개로 오인하지 않는다.
- volatile previousBankingForeground 읽기는 2개(stale banking 분기, bankingJustOpened 단락 오른쪽)로 그대로다. 새 try/catch/finally/취소 체크 없음.
- base 9657cf4 대비 원래 start 이전 및 stop부터 기존 파일 끝까지의 바이트 동일성을 재확인했다. 기존 K7 선언·함수·하단 타입은 그대로이며 새 타입 2개만 appended 상태다.

| 항목 | R1.1 후 | R1.2 후 | 결과 |
|---|---:|---:|---|
| `Synchronized_annotations` | 6 | 6 | 동일 |
| `expectedResetEpoch_epochAtTickStart` | 2 | 2 | 동일 |
| `hook_calls` | 7 keys, 합계 9 | 7 keys, 합계 9 | 전체 dictionary 동일 |
| `k5_calls` | 11 keys, 합계 28 | 11 keys, 합계 28 | 전체 dictionary 동일 |
| `k5_property_reads` | 5 keys, 합계 25 | 5 keys, 합계 25 | 전체 dictionary 동일 |
| `k5_volatile_reads` | 3 keys, 합계 15 | 3 keys, 합계 15 | 전체 dictionary 동일 |
| `log_calls` | 48 | 48 | 동일 |
| `log_string_literals_multiset` | 49 keys, 합계 49 | 49 keys, 합계 49 | 전체 dictionary 동일 |
| `previousBankingForeground_assignments` | 25 | 1 | 승인된 통합 |
| `return_collect` | 0 | 0 | 동일 |
| `s2_calls` | 2 keys, 합계 4 | 2 keys, 합계 4 | 전체 dictionary 동일 |
| `synchronized_calls` | 10 | 10 | 동일 |
| `userResetIntervened_labels_multiset` | 10 keys, 합계 10 | 10 keys, 합계 10 | 전체 dictionary 동일 |

R1.2 최종 CLI stdout JSON:

```json
{
  "Synchronized_annotations": 6,
  "expectedResetEpoch_epochAtTickStart": 2,
  "hook_calls": {
    "afterDebugEpochCapturedBeforePublish": 1,
    "afterDebugPublicationBeforeEffects": 1,
    "beforeDebugOverlayEffect": 1,
    "beforeInactiveSessionCleanupCommit": 1,
    "beforePublicationNotificationCommit": 2,
    "beforePublicationPopupAccountingCommit": 2,
    "beforeRenewalDowngradeCleanup": 1
  },
  "k5_calls": {
    "appUsageMonitor.latestBankingForegroundEventTimestamp": 1,
    "callMonitor.currentCallId": 8,
    "callMonitor.isTelebankingAnchorHot": 1,
    "clock": 6,
    "cooldownManager.isShowing": 4,
    "overlayManager.isEndCallSuppressed": 2,
    "sessionTracker.isCurrentSessionIdleTimedOut": 1,
    "sessionTracker.isSnoozeActive": 2,
    "sessionTracker.isSnoozedForCall": 1,
    "sessionTracker.snoozedAtOrNull": 1,
    "sessionTracker.snoozedCallIdOrNull": 1
  },
  "k5_property_reads": {
    "cooldownManager.dismissedAtMillis": 1,
    "cooldownManager.lastCountdownSec": 1,
    "cooldownManager.showedAtMillis": 1,
    "sessionTracker.sessionState.value": 5,
    "sessionTracker.userResetEpoch": 17
  },
  "k5_volatile_reads": {
    "cooldownConsumedSessionId": 7,
    "previousBankingForeground": 2,
    "s2RecRefireState": 6
  },
  "log_calls": 48,
  "log_string_literals_multiset": {
    "\"$reason — active Event publication retained\"": 1,
    "\"$reason — dismiss overlay/cooldown and clear current event\"": 1,
    "\"$reason — fail-closed PENDING Event retained\"": 1,
    "\"$reason — fail-closed safe-confirm warning retained for retry\"": 1,
    "\"$reason — newer Debug binding retained\"": 1,
    "\"$reason — newer Event context retained\"": 1,
    "\"$reason — newer PUBLISHED Event retained\"": 1,
    "\"Debug overlay skipped: production Event publication is pending\"": 1,
    "\"Debug overlay skipped: published Event was replaced before binding\"": 1,
    "\"anchorHotState mirror → $hot\"": 1,
    "\"call became IDLE during suppression, stabilization scheduled\"": 1,
    "\"cooldownConsumedSessionId cleared: session changed (was=$cooldownConsumedSessionId, now=${session.id})\"": 1,
    "\"cooldownConsumedSessionId cleared: session disappeared (was=$cooldownConsumedSessionId)\"": 1,
    "\"cooldownConsumedSessionId set=${session.id}: banking cooldown fired (level=${score.level}, isCallActive=$isCallActive)\"": 1,
    "\"cooldownConsumedSessionId set=${session.id}: telebanking cooldown fired (level=${score.level})\"": 1,
    "\"coordinator started\"": 1,
    "\"event publication failed; PENDING provenance retained\"": 1,
    "\"event publication superseded — abort escalation effects\"": 1,
    "\"event publication superseded — abort new-trigger effects\"": 1,
    "\"exact Event cleanup deferred: safe-confirm retry retains ${publication.eventId}\"": 1,
    "\"ghost check fallback: now=$now, dismissedAt=$dismissedAt, window=${fallbackWindow}ms → ghost=$isFallbackGhost\"": 1,
    "\"ghost check: eventTs=$eventTs, showedAt=$showedAt, dismissedAt=$dismissedAt → ghost=$isGhost\"": 1,
    "\"notification escalation: alertState=${session.notifiedAlertState}→$alertState, level=${session.notifiedLevel}→${score.level}\"": 1,
    "\"origin=${pending.request.origin}, subject=${pending.request.subject}\"": 1,
    "\"overlay show skipped: trusted Event binding is not current (${event.id})\"": 1,
    "\"popup shown on state transition → $alertState (s2Snapshot=${s2RecRefireState.snapshot})\"": 1,
    "\"popup suppressed by S2 REC-REFIRE debounce (escalation path) — rawTick=$rawTickSignals, snapshot=${s2RecRefireState.snapshot}\"": 1,
    "\"popup suppressed by S2 REC-REFIRE debounce (new-trigger path) — new=$newTriggers, snapshot=${s2RecRefireState.snapshot}\"": 1,
    "\"popup suppressed: cooldown fired this tick\"": 1,
    "\"publication replaced — abort new-trigger effects\"": 1,
    "\"publication replaced — abort new-trigger notification\"": 1,
    "\"publication replaced — abort notification commit\"": 1,
    "\"publication replaced — abort notification escalation\"": 1,
    "\"renewal downgraded to $alertState — dismiss stale popup/current event\"": 1,
    "\"safe-confirm failed closed at ${pending.nextStep}: \"": 1,
    "\"safe-confirm failed closed: origin=${request.origin}, subject=${request.subject}\"": 1,
    "\"session score: total=${score.total}, level=${score.level}, alertState=$alertState, sessionId=${session.id}\"": 1,
    "\"signal tick — rawCall=$callSignals, app=$appSignals, banking=$bankingForeground, install=$installSignals, deviceEnv=$deviceEnvSignals, advanced=$advancedSources\"": 1,
    "\"snooze filter applied (callId=$liveCallId): rawCall=$callSignals → filteredCall=$filtered\"": 1,
    "\"snooze still active — skip popup/notification/cooldown this tick (callId=$liveCallId)\"": 1,
    "\"suppression active, skip popup/notification/cooldown\"": 1,
    "\"tick predates user reset — session transition skipped\"": 1,
    "\"user reset during tick — abort $stage\"": 1,
    "\"뱅킹 쿨다운 발동: level=${score.level}, alertState=$alertState, reason=$reason\"": 1,
    "\"뱅킹 쿨다운 생략: call-based 세션 아님\"": 1,
    "\"뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id}, alertState=$alertState)\"": 1,
    "\"새 trigger 팝업: new=$newTriggers (s2Snapshot=${s2RecRefireState.snapshot})\"": 1,
    "\"텔레뱅킹 쿨다운 발동: level=${score.level}\"": 1,
    "\"텔레뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id})\"": 1
  },
  "previousBankingForeground_assignments": 1,
  "return_collect": 0,
  "s2_calls": {
    "s2RecRefireStateAfterFiring": 2,
    "shouldSuppressS2RecRefire": 2
  },
  "synchronized_calls": 10,
  "userResetIntervened_labels_multiset": {
    "\"cooldown stage\"": 1,
    "\"escalation effects\"": 1,
    "\"escalation popup show\"": 1,
    "\"escalation post-navigation gate\"": 1,
    "\"escalation post-push\"": 1,
    "\"new-trigger effects\"": 1,
    "\"new-trigger popup show\"": 1,
    "\"new-trigger post-navigation gate\"": 1,
    "\"new-trigger post-push\"": 1,
    "\"post ghost query\"": 1
  }
}
```

- NOTE 수치 정정(info): REVIEW에는 lateinit 11개로 기재되어 있으나 실제 TickContext 선언은 10개(1845–1857행)다. 초기화 순서 결합이라는 트레이드오프 판단은 그대로 반영했다.


## R1 전체 자체검토 — T004 / T2

### 범위·방식

- 기준은 main `9657cf4`이며, worktree의 Coordinator 전체 diff와 base에 없는 신규 CoordinatorTickCharacterizationTest 전체를 검토했다. tracked diff만 보고 신규 테스트를 누락하지 않았다.
- 작성자가 전체 구조·정적 계수·원문 대응을 재검토했고, 읽기 전용 에이전트에 ① 제어 흐름, ② 동시성/K5, ③ 계약+④ 테스트 렌즈를 나눠 독립 대조했다. 에이전트는 파일 수정·git 쓰기·Gradle·adb를 수행하지 않았다.
- 판정: must-fix 0, recommend 0. 아래 info는 검토 결론 또는 남은 검증 범위다. 제품/테스트를 추가로 바꾸지 않았다.

| # | 렌즈 | 등급 | 내용 | 근거 (최종 worktree 줄) | 처리 |
|---|---|---|---|---|---|
| I1 | ① 제어 흐름 | info | 결함 미발견. maintenance 두 반환은 무대입; 단계부 중단과 정상 종료는 동일 단일 대입; 예외/취소는 대입 미도달. false/null 전파가 모두 즉시 종료한다. | Coordinator:665,671,678–679,683–707; 이 로그 R1.2 26행 대응표 | 확인·기록 |
| I2 | ② 동시성·상태 | info | K5 조회·단락 평가·락·suspend 훅 순서와 캡처를 보존했다. TickContext의 lateinit 10개는 순서 의존성을 갖지만 init-before-use 지배 관계가 성립하며 REVIEW가 수용한 트레이드오프다. | Coordinator:733,777–805,819–821,904–905,950–952,970–978,990–991,1034–1044,1061,1175–1197,1220–1263,1845–1857 | 수용된 트레이드오프 기록, 코드 무변 |
| I3 | ③ K7 계약 | info | 로그·라벨·훅·S2 인자/호출과 reflection 대상 보존. 소스 텍스트 계약의 expectedResetEpoch 리터럴 2회도 유지. 새 publication 공통화나 α/S2 공용화 없음. | Coordinator:1079–1111,1120–1279 및 보호 구간; RiskOverlayManagerBindingContractTest.kt:89 | 확인·기록 |
| I4 | ④ 테스트·Q5 | info | R1.0의 입력 차이, cancel join, 교체 publisher 효과 제외 및 new-trigger 양성 대조는 유효하다. 다만 Q5에서 의심한 12개 출구의 개별 true 실행 커버리지는 아직 확인하지 않았다. 이를 구조 증명과 혼동하지 않는다. | CoordinatorTickCharacterizationTest.kt:40,94,142,186,226,286; archive/20261007-1107-T001-RESULT.md:169–208 | 구조 대응 유지, 실행 커버리지는 미확인으로 인계 |

### 렌즈별 상세 근거

1. **제어 흐름**: 원래 24개 대입 조기 출구 = transition 2 + presentation 2 + sync 1 + cooldown 2 + escalation 8 + new-trigger 7 + epoch(c)/(d) 2. 모두 runTickStages의 정상 반환을 통해 processTick:679로 도달한다. 정상 종료도 같다. maintenance 2개는 processTick 자체에서 반환한다. Boolean?의 false는 정상 값이고 null만 중단이다. Kotlin inline with 내부 return은 각 단계 함수에서 끝나며, 새 suspend 함수 경계는 새 suspension/취소 체크를 추가하지 않는다.
2. **동시성/K5**: loopState는 scope.launch 안에서 생성되고 collect의 직렬 lane만 접근한다(:646–649). 락은 여전히 this@DefaultRiskDetectionCoordinator(:970)다. stale banking의 volatile 조회(:733), edge 조건 오른쪽 조회(:1061)를 병합/캐시하지 않았다. cooldownConsumed의 null 검사→ID 비교→로그 재조회(:950–952), 각 cooldown 분기의 조회/쓰기 순서(:1074–1109)를 보존했다. isShowing 네 곳(:1034,1097,1173,1219)의 && 순서도 그대로다. S2는 clock→판정/로그 읽기→guardian→회계 callback 쓰기를 유지한다. session/outcome은 동일 transition snapshot, syncedSession은 copy 갱신 후 snapshot이며 tracker 최신값을 재조회하지 않는다. popupShownThisTick은 회계 callback에서만 true이고 nowMs는 guardian 조회 전 값 그대로다.
3. **계약**: 생성자·공개 API·기존 필드·annotation·reflection 함수 소유 객체와 protected suffix는 byte-identical이다. static_invariants의 전체 dictionary를 비교했고 Log 48회/문자열 literal 49개·reset 라벨 10개·hook 7종·S2 2/2·락 10/6·epoch 인자 2가 동일하다. 기존 source-text 계약의 경계 문자열은 보존했다. 코드/테스트를 수정해서 검사 우회하지 않았다.
4. **테스트 판별력**: cancellation은 hook 진입을 확인하고 stop 이후 captured Job.join/isCancelled/isCompleted를 단언한다. replacement 테스트는 새 publisher가 실제로 push/overlay를 낸 후 snapshot을 잡아 대상 tick의 추가 효과만 비교한다. 양성 대조는 미통보 REC가 실제 new-trigger effect를 만드는 입력이다. Claude가 R1.1에서 확인한 M1/M2/M3 mutation RED는 이전 라운드 증거로만 인용하며 R1.2 실행 결과로 확대하지 않는다.

### Q5 미커버 의심 출구의 처리

정의/기준은 `collab/archive/20261007-1107-T001-RESULT.md`의 Q5 표(169–208행)다. 당시 정적 매핑은 12개 근거 있음 / 2개 방어적 미도달 추론 / 12개 미확인이었다. 신규 R1.0은 banking 대입 의미와 cancellation/escalation 중단을 직접 보강했으나 26개 출구별 true 실행을 전수 추가한 것은 아니다.

- **미확인 12개(base 줄)**: C:922 suppression; C:1027 escalation effects reset; C:1056 escalation post-push reset; C:1070 escalation post-navigation reset; C:1130 new-trigger effects reset; C:1147·1154·1159·1164·1173·1180·1196 new-trigger publication/reset/navigation/notification/guardian/overlay 실패 출구.
- **방어적 미도달 추론 2개**: C:915 OBSERVE(non-null session을 resolver가 OBSERVE로 내리지 않음), C:1102 interruptPublication null(앞선 동일 alert 조건의 publish-null에서 이미 종료). 동작 변경이나 출구 제거는 하지 않았다.
- 위 출구들의 최종 위치/경로는 R1.2 26행 표에 모두 있다. escalation/new-trigger 본문 전체를 보존했고 모든 정상 중단은 단일 대입으로 귀결되므로 새로운 production seam이나 범위 밖 테스트 변경이 필요한 must-fix로 보지 않는다.
- 기존 테스트에 절대로 커버가 없다고 단정하지 않는다. Q5 원문도 best-effort 정적 매핑이며 실제 줄 커버리지는 미측정이다. 이번 검토에서는 기존 계약·경합 패턴과 신규 테스트를 대조했으며 전체 branch coverage를 실행하지 않았다.

### S2 체크리스트 §6 — 8항목 (R1 전체 최종 정적 판정)

| 항목 | 판정 | 근거 |
|---|---|---|
| 1. 일단 닫기 dismiss-only | 보존 | CTA/overlay 파일 무수정 |
| 2. safe-confirm 전용 흐름 분리 | 보존 | 기존 confirmSafe 및 publication 보호 구간 무수정 |
| 3. REC-REFIRE는 S2 orchestration | 보존 | Coordinator의 두 S2 판정 위치만 기존 흐름대로 추출, CTA 이동 없음 |
| 4. TTL `(now-lastFiredAt) > TTL` | 보존 | S2 함수·상수 및 비교 연산 파일 무수정 |
| 5. 비통화 dismiss에 call-safe 효과 없음 | 보존 | 관련 CTA/monitor 경로 무수정 |
| 6. 통화 safe-confirm 전용 부수효과 | 보존 | 기존 safe-confirm 명령·조건·외부 효과 무수정 |
| 7. α/S2 상수·상태·함수·테스트 분리 | 보존 | 공용 debounce 도입 없음, 두 기존 S2 함수 호출/상태 소유 유지 |
| 8. UPGRADE_TRIGGERS 단일화 금지 | 보존 | 기존 set 원소/위치/다른 두 파일 무수정 |

C1/C2/C3 관련 새 CTA 효과/monitor signal 시퀀스 변경도 없다. 정책·권한·서비스·외부 연락 동작 추가 없음. 위 판정은 정적 자체검토이며 최종 lint/테스트 게이트의 실행 판정과 구별한다.

### Claude 실행 요청 / 미확인

- R1.2 compile·표적 테스트·mutation 재검증은 미실행이다. R1.1 GREEN을 최종 GREEN으로 간주하지 않는다. APK 크기도 최종 build 전이므로 측정하지 않았다.
- 실행 cwd: `.worktrees/refactor-coordinator-r1`. JBR 경로를 지정하고 fresh·직렬 실행한다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew --no-daemon --no-parallel --max-workers=1 :app:testDebugUnitTest --tests 'com.example.seniorshield.monitoring.orchestrator.*' --tests 'com.example.seniorshield.core.overlay.RiskOverlayManagerBindingContractTest' --rerun-tasks
```

- 표적 비교: 10 suites / 184 tests / skipped 0, characterization 7/7. XML timestamp 확인.
- mutation 재검증: escalation 중단 전파 누락과 단일 대입의 finally 이동은 기존 판별 테스트 RED를 기대한다. R1.2에서는 no-session 개별 대입이 없어졌으므로 M3의 이전 위치를 그대로 적용할 수 없다. 대신 중앙 대입 제거/우회 mutation으로 대입 의미를 재확인할 수 있다. 실제 probe 편집/복원은 Claude가 담당한다.
- 이후 REVIEW의 결정대로 worktree 전체 직렬 게이트·검증기·self-check를 최종 1회 수행하고 구성 기준선 app 551/40 + risk 7/1 + contracts 4/1 = 562 tests / 42 suites, skipped 0과 비교한다.
- must-fix 0으로 제품 수정 추가 없음. 결과 인계 후 정지하며 commit/push는 수행하지 않는다.

### T004 최종 검증 (2026-10-07T14:35:11.426592+09:00)

- source SHA-256 `0afa8933513fdbec2dea0ad5b705ce580f9f36ea5fd0c05390e13b57f76f6d5d`; 독립 검토 전후 동일. 신규 테스트·static_invariants.py SHA-256도 동일.
- R1.1 대비 대입 25→1만 변경, 나머지 계수 동일. 최종 JSON 기록 일치, 실행 대입 1, previous volatile 읽기 2, maintenance 2 + 중앙 대입 경유 출구 24 + 정상 종료 경로 확인.
- 새 단계부에 try/catch/finally/ensureActive 없음. git diff --check PASS, staged 0. HEAD 9657cf4ea519b90aa8af67958535d93cf7330829.

```text
 M app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt
?? app/src/test/java/com/example/seniorshield/monitoring/orchestrator/CoordinatorTickCharacterizationTest.kt
```

```text
 .../DefaultRiskDetectionCoordinator.kt             | 1129 +++++++++++---------
 1 file changed, 620 insertions(+), 509 deletions(-)
```

### T005 주석 보완 — TASK3 재시도

- 요청: `20261007-2041-T005-TASK3`. F1·F2·F3 주석만 수정. 앱·테스트 실행 코드 변경 0.
- F1: runTickStages 및 단계 함수 11개의 반환 계약을 한국어 KDoc으로 명시. escalation의 false는 계속, null은 중단이며 banking 이전값 대입은 호출자 정상 반환 뒤 1회임을 기록.
- F3: S2 상태의 collect lane read/write, stop() 초기화, 외부 비노출을 명시. 나머지 문장 보존.
- F2: 테스트 KDoc의 외부 기록 참조를 제거하고 테스트 이름 7개와 고정 성질을 1:1로 연결. private 필드 reflection 이유 명시.
- 검증: 지정 static_invariants.py를 importlib으로 로드하고 KotlinLex·inventory 사용. 토큰은 offset 순 text, 문자열은 lexer.strings 원문으로 구성. JSON은 ensure_ascii=True, sort_keys=True, separators=(',', ':')로 직렬화하여 SHA-256 산출.

| 대상 | 해시 종류 | 편집 전 | 편집 후 |
|---|---|---|---|
| Coordinator | 토큰·문자열 | `0906c2aeff8defc4c366c8c42ee47dfadb630a4283989a4607561f0636f9dc37` | `0906c2aeff8defc4c366c8c42ee47dfadb630a4283989a4607561f0636f9dc37` |
| Coordinator | invariant JSON | `03776eaef9fd64ee59cf2c00aa33ed0c15e3dc06ba5a949046690c76478ad822` | `03776eaef9fd64ee59cf2c00aa33ed0c15e3dc06ba5a949046690c76478ad822` |
| 테스트 | 토큰·문자열 | `c8ede420c35257f25b28977b20dcb44440cfd6fa0842423dd60fc9224954ab61` | `c8ede420c35257f25b28977b20dcb44440cfd6fa0842423dd60fc9224954ab61` |
| 테스트 | invariant JSON | `ee92eb7ca5bb2546f032cbd012a86a3967629376377a460f23461bb0cb050844` | `ee92eb7ca5bb2546f032cbd012a86a3967629376377a460f23461bb0cb050844` |

- 추가 주석 49줄에 금지 문자열 7종을 rg -n -F로 검사: 0건(exit 1, 오류 출력 없음).
- 줄바꿈 실측 보존: Coordinator CRLF 1858→1896줄, bare LF 0. 테스트는 기존 LF 345→352줄, 기록 파일도 기존 LF 유지.
- 시작 Get-Date 응답: 명령 실행 2.769초, 도구 호출 전체 14.4초. 60초 무응답 중단 조건 발생 없음.
- git 쓰기·Gradle·adb 실행 0. 동적 검증은 요청에 따라 미실행.
- 기록 시각: 2026-10-07T21:13:28+09:00
