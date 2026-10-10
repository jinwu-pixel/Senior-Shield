# T001 IMPL_LOG — Home 보호자 문자 토글 + 취소 상태 보존

- 범위: DIRECTIVE.md v1.0의 라운드 A(RED)만. 등급 T2.
- TASK: 20261010-1036-T001-TASK.
- 작업 worktree: `C:/Users/momen/AndroidStudioProjects/Senior_Shield/.worktrees/home-sms`.
- 확인한 HEAD: `1ba41d2a18757dbfbaa393a83714596e51e18774`.
- 착수 시 `git status --porcelain --untracked-files=all`: 출력 없음.
- 이 로그는 TASK에 지정된 main 작업트리 경로에 작성했다.
- Gradle·컴파일·단위 테스트·adb·git 쓰기: 실행하지 않음. RED/GREEN은 아래의 기대값이며 관측값이 아니다.

## 라운드 A 변경 목록

| 파일(worktree 기준, 로그 제외) | 변경 |
|---|---|
| `app/src/main/java/com/example/seniorshield/feature/home/HomeUiState.kt` | `smsMenuEnabled: Boolean = false` 1줄 추가. |
| `app/src/main/java/com/example/seniorshield/feature/home/HomeViewModel.kt` | SettingsRepository import 및 guardianRepository 다음 생성자 의존성만 추가. 아직 사용하지 않음. combine/uiState 본문 무변경. |
| `app/src/test/java/com/example/seniorshield/feature/home/HomeViewModelSafeConfirmationTest.kt` | 기존 생성자 호출 1곳에 FQCN으로 FakeSettingsRepository 인자 1줄만 추가. 다른 줄·단언 무변경. |
| `app/src/test/java/com/example/seniorshield/feature/home/HomeGuardianSmsToggleTest.kt` | 신규 Home 사례 5개, 활성 구독자 및 실제 Flow fake. |
| `app/src/test/java/com/example/seniorshield/monitoring/orchestrator/PopupGuardianSmsToggleTest.kt` | I1 2개 추가. 기존 assertStopDuringSettingRead에 opt-in 입력을 추가하고 기존 단언을 그대로 재사용. |
| `investigations/2026-10-10-home-sms-toggle/IMPL_LOG.md` (main 작업트리) | 본 로그 신규 작성. |

### fake 및 I1 설계 근거

- 공용 FakeSettingsRepository 소스를 먼저 확인했다. HomeViewModelSafeConfirmationTest의 인자는 그대로 재사용한다.
- 공용 fake의 SMS Flow는 단일 방출 후 종료되어 설정 변경과 예외 후 변경을 지속적으로 모델링할 수 없다. 신규 Home 파일의 private HomeSettingsRepository는 다른 메서드를 공용 fake에 위임하고 SMS만 MutableStateFlow<Result<Boolean>>으로 구현한다. 공용 하네스 변경은 없다.
- ContextCompat/Settings의 mockkStatic 및 setMain/resetMain은 기존 Home 테스트 패턴을 따른다. coordinator mock은 strict이고 두 Flow 속성은 실제 MutableStateFlow/MutableSharedFlow를 명시한다. relaxed mock Flow는 없다.
- Home은 UNKNOWN_CALLER로 GUARDED 세션을 만들고 보호자를 실제 fake로 제공한다. hasGuardian을 함께 확인해 초기 UiState의 false만 검사하는 문제를 피한다.
- 각 Home 테스트는 backgroundScope의 활성 구독자를 둔다. tearDown에서 생성한 ViewModel의 scope를 취소한다.
- I1은 초기 tick의 banking=true와 previousBankingForeground=false를 기존대로 유지한다. 설정 훅이 먼저 releaseSetting.await()로 대기하는 동안 coordinator 참조와 accounting probe를 설치한다.
- 이후 대기를 정상 해제하면 같은 훅에서 coordinator.stop(); throw java.io.IOException(...)를 연속 실행한다. 취소 이후 await/emit/yield 등 suspend 지점은 없다. 기존 CE 사례처럼 외부 stop으로 await를 취소하는 방식이 아니다.
- I1 기존 단언은 전부 유지했다: job 취소·완료, previousBankingForeground=false, guardianReads=0, accounting=0, show=0, cooldown=0, 사전 publication/notification 각각 1회, active-threat accounting 없음.

## 사례별 기대 결과

아래 RED/GREEN은 소스 대조에 따른 예상이다. 실제 관측은 Claude 실행 후 기록해야 한다.

| 테스트 | 파일 | 사례 | 라운드 A 기대 | 라운드 B 이후 판별력 근거 |
|---|---|---|---|---|
| smsMenuOffWithGuardianIsFalse | HomeGuardianSmsToggleTest.kt | (a) OFF + 보호자 | GREEN: 새 필드 기본값 false | OFF를 true로 뒤집거나 무조건 노출하면 RED. (b)와 함께 상수 구현을 구분. |
| smsMenuOnWithGuardianIsTrue | HomeGuardianSmsToggleTest.kt | (b) ON + 보호자 | RED: 마지막 assertTrue, 필드는 항상 false | 토글 Flow 누락·UiState 전달 누락·항상 false이면 RED. |
| settingFailureHidesSmsMenuAndHomeKeepsUpdating | HomeGuardianSmsToggleTest.kt | (c) ON 방출 뒤 IOException, 이후 위험/이력 갱신 | GREEN: A에서는 설정을 수집하지 않아 false이며 위험 갱신은 계속됨 | catch 제거 시 수집 실패. false fallback 대신 ON 유지 시 토글 단언 실패. 전체 combine을 종료하면 WARNING/CRITICAL/이력 1건 갱신 단언 실패. |
| silentSettingFlowDoesNotFreezeHomeAtSafe | HomeGuardianSmsToggleTest.kt | (d) 무방출·미완료 Flow와 HIGH→CRITICAL 위험 갱신 | GREEN: A의 기존 combine은 설정에 의존하지 않음 | onStart 제거 시 내부 combine이 시작하지 않아 초기 SAFE/LOW에 고정되어 RED. emptyFlow가 아닌 awaitCancellation으로 무방출을 유지. |
| toggleAfterFailureIsIgnoredWhileHomeRemainsSubscribed | HomeGuardianSmsToggleTest.kt | (e) 처음부터 예외, 이후 ON 변경·활성 구독 5,001ms 유지·위험 갱신 | GREEN: A에서는 기본 false | 오류 후 자동 재구독/retry 또는 위험 갱신 시 설정 Flow 재생성으로 ON이 반영되면 RED. 재구독 전에는 false라는 제약을 고정. |
| escalationStopThenSettingIOExceptionAbortsPopupAndTickAssignment | PopupGuardianSmsToggleTest.kt | I1 escalation | RED: generic catch가 IOException을 삼켜 banking/accounting/show 효과로 진행 | catch 첫 줄 ensureActive가 없으면 중단 후 기존 0회/false 단언 위반. 추가 후 GREEN 기대. |
| newTriggerStopThenSettingIOExceptionAbortsPopupAndTickAssignment | PopupGuardianSmsToggleTest.kt | I1 new-trigger | RED: 같은 취소 누락이 new-trigger 경로에 존재 | 동일한 경계 검증을 new-trigger에서도 수행. 추가 후 GREEN 기대. |

- 신규 7개 기준 기대: RED 3개, GREEN 4개. 기존 테스트는 회귀 GREEN 기대.
- (e)는 활성 구독 중 자동 회복하지 않는다는 범위를 검증한다. 화면 이탈·재진입 후 ON 복구 자체는 이번 테스트의 단언 범위에 포함하지 않았다.
- 라운드 B 이후 권장 판별력 확인: 설정 onStart 제거 → (d) RED, catch 제거/false 대신 true → (c) RED, 즉시 retry 추가 → (e) RED, I1 ensureActive 제거 → 두 I1 RED. 이번 라운드에서 mutation이나 실행은 하지 않았다.
- 선택 사항인 HomeScreen 계약 단언은 추가하지 않았다. 해당 파일과 HomeScreen은 TASK의 라운드 A 쓰기 범위 밖이다.

## 정적 계수 기준값

실행 작업 디렉터리: 위 worktree. 아래 명령 exit code 0.

```powershell
python -I -B C:/Users/momen/AndroidStudioProjects/Senior_Shield/investigations/2026-10-07-refactor-plan/static_invariants.py app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt
```

- Log 49, userResetIntervened 라벨 10, expectedResetEpoch_epochAtTickStart 2.
- Coordinator는 라운드 A에서 수정하지 않았다. 아래는 실행 출력 전체이다.

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
  "log_calls": 49,
  "log_string_literals_multiset": {
    "\"$reason \u2014 active Event publication retained\"": 1,
    "\"$reason \u2014 dismiss overlay/cooldown and clear current event\"": 1,
    "\"$reason \u2014 fail-closed PENDING Event retained\"": 1,
    "\"$reason \u2014 fail-closed safe-confirm warning retained for retry\"": 1,
    "\"$reason \u2014 newer Debug binding retained\"": 1,
    "\"$reason \u2014 newer Event context retained\"": 1,
    "\"$reason \u2014 newer PUBLISHED Event retained\"": 1,
    "\"Debug overlay skipped: production Event publication is pending\"": 1,
    "\"Debug overlay skipped: published Event was replaced before binding\"": 1,
    "\"anchorHotState mirror \u2192 $hot\"": 1,
    "\"call became IDLE during suppression, stabilization scheduled\"": 1,
    "\"cooldownConsumedSessionId cleared: session changed (was=$cooldownConsumedSessionId, now=${session.id})\"": 1,
    "\"cooldownConsumedSessionId cleared: session disappeared (was=$cooldownConsumedSessionId)\"": 1,
    "\"cooldownConsumedSessionId set=${session.id}: banking cooldown fired (level=${score.level}, isCallActive=$isCallActive)\"": 1,
    "\"cooldownConsumedSessionId set=${session.id}: telebanking cooldown fired (level=${score.level})\"": 1,
    "\"coordinator started\"": 1,
    "\"event publication failed; PENDING provenance retained\"": 1,
    "\"event publication superseded \u2014 abort escalation effects\"": 1,
    "\"event publication superseded \u2014 abort new-trigger effects\"": 1,
    "\"exact Event cleanup deferred: safe-confirm retry retains ${publication.eventId}\"": 1,
    "\"ghost check fallback: now=$now, dismissedAt=$dismissedAt, window=${fallbackWindow}ms \u2192 ghost=$isFallbackGhost\"": 1,
    "\"ghost check: eventTs=$eventTs, showedAt=$showedAt, dismissedAt=$dismissedAt \u2192 ghost=$isGhost\"": 1,
    "\"notification escalation: alertState=${session.notifiedAlertState}\u2192$alertState, level=${session.notifiedLevel}\u2192${score.level}\"": 1,
    "\"origin=${pending.request.origin}, subject=${pending.request.subject}\"": 1,
    "\"overlay show skipped: trusted Event binding is not current (${event.id})\"": 1,
    "\"popup shown on state transition \u2192 $alertState (s2Snapshot=${s2RecRefireState.snapshot})\"": 1,
    "\"popup suppressed by S2 REC-REFIRE debounce (escalation path) \u2014 rawTick=$rawTickSignals, snapshot=${s2RecRefireState.snapshot}\"": 1,
    "\"popup suppressed by S2 REC-REFIRE debounce (new-trigger path) \u2014 new=$newTriggers, snapshot=${s2RecRefireState.snapshot}\"": 1,
    "\"popup suppressed: cooldown fired this tick\"": 1,
    "\"publication replaced \u2014 abort new-trigger effects\"": 1,
    "\"publication replaced \u2014 abort new-trigger notification\"": 1,
    "\"publication replaced \u2014 abort notification commit\"": 1,
    "\"publication replaced \u2014 abort notification escalation\"": 1,
    "\"renewal downgraded to $alertState \u2014 dismiss stale popup/current event\"": 1,
    "\"safe-confirm failed closed at ${pending.nextStep}: \"": 1,
    "\"safe-confirm failed closed: origin=${request.origin}, subject=${request.subject}\"": 1,
    "\"session score: total=${score.total}, level=${score.level}, alertState=$alertState, sessionId=${session.id}\"": 1,
    "\"signal tick \u2014 rawCall=$callSignals, app=$appSignals, banking=$bankingForeground, install=$installSignals, deviceEnv=$deviceEnvSignals, advanced=$advancedSources\"": 1,
    "\"sms menu setting read failed \u2014 guardian SMS button hidden\"": 1,
    "\"snooze filter applied (callId=$liveCallId): rawCall=$callSignals \u2192 filteredCall=$filtered\"": 1,
    "\"snooze still active \u2014 skip popup/notification/cooldown this tick (callId=$liveCallId)\"": 1,
    "\"suppression active, skip popup/notification/cooldown\"": 1,
    "\"tick predates user reset \u2014 session transition skipped\"": 1,
    "\"user reset during tick \u2014 abort $stage\"": 1,
    "\"\ubc45\ud0b9 \ucfe8\ub2e4\uc6b4 \ubc1c\ub3d9: level=${score.level}, alertState=$alertState, reason=$reason\"": 1,
    "\"\ubc45\ud0b9 \ucfe8\ub2e4\uc6b4 \uc0dd\ub7b5: call-based \uc138\uc158 \uc544\ub2d8\"": 1,
    "\"\ubc45\ud0b9 \ucfe8\ub2e4\uc6b4 \uc0dd\ub7b5: \uc138\uc158\ub2f9 1\ud68c \uc815\ucc45 (sessionId=${session.id}, alertState=$alertState)\"": 1,
    "\"\uc0c8 trigger \ud31d\uc5c5: new=$newTriggers (s2Snapshot=${s2RecRefireState.snapshot})\"": 1,
    "\"\ud154\ub808\ubc45\ud0b9 \ucfe8\ub2e4\uc6b4 \ubc1c\ub3d9: level=${score.level}\"": 1,
    "\"\ud154\ub808\ubc45\ud0b9 \ucfe8\ub2e4\uc6b4 \uc0dd\ub7b5: \uc138\uc158\ub2f9 1\ud68c \uc815\ucc45 (sessionId=${session.id})\"": 1
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

## 자체검토

- 수정 필수: 소스 대조에서 발견 없음. 컴파일 및 RED 실측은 Claude 확인 대기.
- 수정 권장: 없음.
- 정책/권한 리스크: UI·외부 연락·Manifest·권한·DI 모듈 변경 없음. 기존 생성자에 이미 존재하는 SettingsRepository 계약만 주입.
- 미확인 사항: Kotlin/Hilt 컴파일, MockK static 실행 호환성, 테스트 스케줄러에서 I1 단언 RED 2개 및 Home ON 단언 RED 1개 실측. 설정 실패 뒤 재진입 복구는 위에서 명시한 테스트 범위 밖.
- 실제 확인: git diff --check exit 0; 기존 safe-confirmation 파일은 인자 1줄 추가만; 기존 Popup 단언 텍스트 보존; 새 테스트 7개; 금지 파일 diff 없음. 기존 수정 파일의 CRLF를 보존했다.

## Claude 실행 요청

아래는 요청 명령이며 Codex가 실행한 명령이 아니다. worktree에서 실행한다.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.seniorshield.feature.home.HomeGuardianSmsToggleTest' --tests 'com.example.seniorshield.feature.home.HomeViewModelSafeConfirmationTest' --tests 'com.example.seniorshield.monitoring.orchestrator.PopupGuardianSmsToggleTest'
```

- 컴파일 오류가 아닌 위 3개 단언 RED인지 확인하고 테스트 XML의 실제 결과를 회신해 달라.
- 실제 관측값: 대기. 라운드 B 구현은 진행하지 않고 RESULT 후 정지한다.

## T002 — 라운드 B(GREEN) 구현 + floor + 전체 자체검토

### 상태와 입력 증거

- TASK: `20261010-1046-T002-TASK`, 정본 DIRECTIVE v1.0 K1–K5. 검토 기준은 main `1ba41d2` 대비 worktree 전체 diff(라운드 A 포함).
- Claude의 T001 REVIEW PASS 수신: 14 suites / 209 tests / skipped 0, 컴파일 정상. 실패는 아래 정확히 3건의 단언 RED라고 보고받았다. Codex가 재실행한 결과는 아니다.
  - HomeGuardianSmsToggleTest.smsMenuOnWithGuardianIsTrue
  - PopupGuardianSmsToggleTest.escalationStopThenSettingIOExceptionAbortsPopupAndTickAssignment
  - PopupGuardianSmsToggleTest.newTriggerStopThenSettingIOExceptionAbortsPopupAndTickAssignment
- 위 관측으로 T001의 RED 확인 대기 항목은 해소되었다. T002 이후 GREEN·전체 빌드·lint·floor 검증은 Claude 실행 대기다.
- Gradle·컴파일·단위 테스트·adb·git 쓰기 없음. 테스트 파일과 HomeUiState는 T002에서 추가 수정하지 않았다.

### 파일별 diff 요지 (1ba41d2 대비 누적)

| 파일 | + / - | 요지 |
|---|---:|---|
| HomeViewModel.kt | +10 / -1 | SettingsRepository 주입, catch/onStart import, SessionCombined 필드, 내부 다섯 번째 설정 Flow, UiState 전달. 외부 combine·기존 계산·stateIn 그대로. |
| HomeUiState.kt | +1 / -0 | 라운드 A의 기본 false 필드 유지. |
| HomeScreen.kt | +5 / -1 | 표시 여부 전달, 대화상자 파라미터 기본 false, 문자 버튼에만 조건. 전화·닫기·탐색·Intent 콜백 그대로. |
| DefaultRiskDetectionCoordinator.kt | +3 / -0 | currentCoroutineContext/ensureActive import 2줄 + firstGuardian generic catch 첫 줄 1개. |
| .github/scripts/verify-unit-xml.ps1 | +2 / -1 | app MinTests 571, 지정된 ASCII Raised 주석. 나머지 검증기 그대로. |
| .github/workflows/verify.yml | +1 / -1 | 단계 이름 floor 571/7/4만 변경. |
| HomeViewModelSafeConfirmationTest.kt | +1 / -0 | 라운드 A의 생성자 인자만 추가된 상태 유지. |
| PopupGuardianSmsToggleTest.kt | +29 / -3 | 라운드 A의 I1 두 테스트 및 헬퍼 파라미터화 유지. |
| HomeGuardianSmsToggleTest.kt | 신규 232줄 | 라운드 A의 Home 5개 테스트 유지. |

### 정적 계수 차이 0 증거

- 동일한 `python -I -B <static_invariants.py> <worktree Coordinator>`를 subprocess로 실행했다: exit 0.
- 기준: `git show 1ba41d2:<Coordinator 경로>`를 같은 inventory 함수에 전달. 추가 비교: 이 로그의 라운드 A JSON.
- JSON의 최상위 13개 키 및 모든 하위 dict/multiset을 깊은 동등 비교했다.

```json
{
  "script_exit": 0,
  "compared_top_level_keys": 13,
  "diff_keys_vs_1ba41d2": [],
  "equal_round_A": true,
  "log_calls": 49,
  "reset_labels": 10,
  "epoch_args": 2
}
```

- Log 문자열 multiset, 재검증 라벨 multiset, synchronized/annotation, hooks, K5 calls/property/volatile reads, S2 calls, banking 대입, return@collect 모두 동일하다.
- import 2줄과 ensureActive 1줄을 메모리에서 제외하면 Coordinator 전체가 기준 소스와 동일함을 확인했다. try 범위·호출부·reflection 필드 이름/타입/소유 객체·S2 위치가 보존된다.

### 사례별 판별력 및 변형 분석

아래 T002 변형은 소스에 적용하거나 실행하지 않은 정적 분석이다. ensureActive 제거에 해당하는 라운드 A만 Claude가 실제 RED를 확인했다.

| 사례/변형 | RED가 되는 테스트 또는 판정 | 근거 및 한계 |
|---|---|---|
| (a) OFF를 true로 전달 | smsMenuOffWithGuardianIsFalse | 보호자 존재를 확인한 실제 UiState의 false 단언. |
| (b) 설정 연결/필드 전달 누락 | smsMenuOnWithGuardianIsTrue | ON이 기본 false로 남음. Claude가 라운드 A에서 단언 RED 실측. |
| (c) catch 제거 | settingFailureHidesSmsMenuAndHomeKeepsUpdating; toggleAfterFailureIsIgnoredWhileHomeRemainsSubscribed | IOException이 수집을 종료하고 전파됨. 특히 (c)는 ON 이후 false fallback·위험/이력 갱신을 검증한다. 미처리 IOException 또는 상태 단언 실패로 RED 예상. |
| (d) onStart 제거 | silentSettingFlowDoesNotFreezeHomeAtSafe | 설정은 완료도 방출도 하지 않는 Flow. 내부 combine 첫 값이 없으므로 UiState가 SAFE/LOW에 남고 WARNING/HIGH 단언 실패. |
| (e) 예외 후 자동 재구독/재시도 도입 | toggleAfterFailureIsIgnoredWhileHomeRemainsSubscribed | 활성 구독을 5,001ms 유지하며 ON으로 바꿔도 false여야 함. 관측 시간 안에 자동 복구하면 단언 실패. 무한 즉시 재시도는 별도의 비종료 문제를 일으킬 수 있다. |
| ensureActive 제거 | escalationStopThenSettingIOExceptionAbortsPopupAndTickAssignment; newTriggerStopThenSettingIOExceptionAbortsPopupAndTickAssignment | 취소 직후 IOException을 삼켜 banking/accounting/show 효과가 진행됨. 두 경로 모두 라운드 A에서 Claude가 단언 RED 확인. |
| ensureActive를 Log.w 뒤로 이동 | 기존 테스트 중 RED를 보장하는 테스트 없음 | 두 I1은 후속 효과 차단만 검사하고 Log.w 호출은 검사하지 않는다. 정적 계수도 호출 순서를 검사하지 않아 동일하다. 이번 소스 대조에서 catch 첫 statement가 ensureActive이고 그 다음이 Log.w임을 확인했다. |

- 기존 테스트 단언/하네스/I1 테스트 파일 변경이 T002에서 금지되어 있어 로그 호출 단언을 추가하지 않았다. 신규 Home 파일에 무관한 Coordinator 테스트를 넣지도 않았다.
- HomeScreen에는 실제 UiState 값을 전달하고 문자 SecondaryButton만 조건으로 감쌌다. 화면 연결 자체는 이번 JVM 테스트가 검증하지 않으며 UI ON/OFF 수동 확인이 남는다.
- (e)는 재구독 전 제약을 검증한다. 재진입 후 ON 회복 자체를 검증하지 않는 기존 범위는 유지했다.

### 전체 자체검토 — 4개 렌즈

| ID | 등급 | 렌즈 | 지적/판정 | 처리 |
|---|---|---|---|---|
| I1 | info | ① Home fail-open/fail-closed | onStart(false)로 무방출 설정 때문에 실제 위험 표시가 영구 SAFE에 고정되는 경로를 차단. catch(false)는 문자 숨김만 유지하며 다른 상태 Flow는 계속 수집. 초기 stateIn 값 자체는 계약대로 보존. | K1 형태 및 외부 combine·기존 필드 계산 무변경 확인. |
| I2 | info | ① Home fail-closed | 설정 예외 후 토글 변경은 구독 재시작 전 반영되지 않음. 승인된 K1 제약이며 retry 없음. | 테스트 (e) 및 IMPL_LOG에 명시. |
| I3 | info | ② 취소 의미 | generic catch 첫 statement ensureActive, Log.w 전에 취소 전파. CE catch와 try 범위/호출부 그대로. 검사 후 새로 발생하는 취소 경합까지 완전히 차단하는 변경은 아님. | 소스 첫 statement 대조 True. |
| I4 | info | ③ 계약 보존 | Coordinator는 import 2줄+본문 1줄뿐. 전체 정적 계수 0차이, reflection 필드와 source-text epoch 리터럴 2회 유지. Home Warning navigation 계약 대상 구간 보존. | 기준 소스 역대조 True, git diff --check exit 0. |
| I5 | info | ④ 테스트 판별력 | ensureActive의 Log.w 뒤 이동은 현재 자동 테스트와 계수로 검출되지 않음. | 자동 검증 보유로 과장하지 않고 위 표에 공백 명시; 현재 위치는 정적 대조 확인. |
| I6 | info | ④ 테스트/UI/CI 실측 | T002 GREEN, 전체 571/7/4, lint, preview 컴파일 및 Home 대화상자 ON/OFF는 Codex 미실행. | Claude 검증 요청. preview는 기존 HomeUiState 기본값과 대화상자 기본 false를 사용 가능. |

- 수정 필수(must-fix): 발견 0건.
- 수정 권장(recommend): 0건.
- 정책/권한 리스크: 자동 외부 연락 없음. ACTION_DIAL/ACTION_SENDTO 콜백은 기존 그대로이고 문자 표시 조건만 추가. Manifest·권한·DI 모듈 변경 0건.
- 미확인 사항: I5/I6와 재진입 회복의 실행 확인. 문서 동기화는 DIRECTIVE에 지정된 Claude 담당이며 Codex 쓰기 범위 밖.

### S2 체크리스트 §6 — 8항목

정본 `.github/S2_REVIEW_CHECKLIST.md` §6을 읽고 N/A 없이 이번 diff가 추가하는 위반이 없는지 판정했다. 전 항목은 정적 PASS이며 런타임/guardrail 실행 PASS를 의미하지 않는다.

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| 1 | 일단 닫기 dismiss-only | PASS | RiskOverlayManager/BankingCooldownManager 원본 동일. Home 대화상자 닫기 콜백도 그대로. |
| 2 | safe-confirm 전용 흐름 | PASS | HomeViewModel.confirmSafe 및 Coordinator typed command 본문 변경 없음. 문자 조건이 safe-confirm을 호출하지 않음. |
| 3 | REC-REFIRE는 S2 orchestration 책임 | PASS | S2 검사/발화 함수 호출 각각 2회 유지. CTA에 snapshot/lastFiredAt/wake-up 접근 추가 없음. |
| 4 | TTL 경계 > 30,000ms | PASS | S2RecRefireDebounce.kt 전체 무변경. `now - lastFiredAt > S2_REC_REFIRE_TTL_MS` 및 30_000L 유지. |
| 5 | non-in-call dismiss에 call-safe 효과 없음 | PASS | dismiss·통화 상태 분기 무변경. 새 mark/snooze/anchor 효과 없음. |
| 6 | in-call safe-confirm 효과 경로 보존 | PASS | 기존 통화 안전 처리 진입점/분기를 변경하지 않아 추가 위반 없음. |
| 7 | α/S2 상수·상태·함수·테스트 분리 | PASS | RiskSessionTracker/S2RecRefireDebounce 및 두 테스트 클래스 무변경; 공유 추상화 추가 없음. |
| 8 | UPGRADE_TRIGGERS follow-up 유지 | PASS | 세 정의 모두 원본 유지. 이번 트랙에서 원소 변경/단일화 없음. |

- C1/C2/C3: CTA에서 S2 입력 직접 접근 추가 0건; monitor 신호 시퀀스와 CTA 부수효과를 바꾸지 않아 scope 교집합·escape delta에 새 영향 없음.

### 실행한 정적 확인 / Claude 실행 요청

- git diff --check exit 0 (경고/오류 출력 없음).
- HomeViewModel에서 이번 추가 부분을 제외한 전체 소스가 1ba41d2와 동일: True.
- HomeScreen에서 값 전달·파라미터·문자 조건을 제외한 전체 소스가 1ba41d2와 동일: True.
- floor 스크립트는 지정 주석과 MinTests만, workflow는 단계 이름만 변경: True. 주석 정확 일치 및 ASCII: True.
- 하네스·HomeScreenWarningNavigationContractTest·RiskOverlayManager·BankingCooldownManager·S2RecRefireDebounce·RiskSessionTracker 무변경: True.

아래 명령은 Claude 실행 요청이며 Codex는 실행하지 않았다.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.seniorshield.feature.home.*' --tests 'com.example.seniorshield.monitoring.orchestrator.*'
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug
.\.github\scripts\verify-unit-xml.ps1
.\.github\scripts\verify-domain-lint.ps1
.\.github\scripts\verify-lint-union.ps1 -AppCurrent app/build/reports/lint-results-debug.xml -DataCurrent data/build/reports/lint-results-debug.xml
```

- CI와 같은 schema drift 검사 및 probe-verifiers.ps1 self-check도 Claude가 수행한다.
- 대상 회귀 209건 GREEN, 전체 floor 571/7/4(합계 582), skipped 0을 실제 XML로 확인 요청. 합계는 설정된 최소값이지 이번 실행에서 관측한 값이 아니다.
- 릴리스 전 Home 대화상자 OFF(전화만)/ON(전화+문자), 닫기 유지, 기존 수동 Intent 동작을 수동 확인 목록에 유지한다. 이번 세션에서 adb/실기 조작은 하지 않았다.
- 라운드 B 산출물과 자체검토를 인계하고 RESULT 후 정지한다.

