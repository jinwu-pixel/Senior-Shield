# 팝업 보호자 문자 버튼 토글 — IMPL_LOG

## 라운드 A (RED) — T002

- 근거: DIRECTIVE.md v1.1 §2 K1–K7, §3 라운드 A 및 20261008-1415-T002-TASK.
- 작업 위치: `C:/Users/momen/AndroidStudioProjects/Senior_Shield/.worktrees/sms-toggle`.
- 기준 HEAD: `650cb54b6856589f1ed10e12adf66ab6548a0eae`, 브랜치 `codex/popup-sms-toggle`; 착수 시 porcelain 출력 없음.
- 상태: 라운드 A 소스/테스트 작성 및 정적 검증 완료. 컴파일·RED/GREEN은 미실행이며 Claude 검증 대기. 라운드 B 미착수.
- 실행 제한 준수: Gradle 0, adb 0, git 쓰기 0. 지정된 소스/테스트 5개와 이 로그만 작성.

### 변경 목록

| 파일 (로그 외 worktree 기준) | 변경 |
|---|---|
| `app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt` | +2/-0. SettingsRepository import와 guardianRepository 다음의 미사용 생성자 파라미터만 추가 |
| `app/src/test/java/com/example/seniorshield/testutil/CoordinatorTestHarness.kt` | +29/-2. 기본 ON 설정 fake, 예외/빈 Flow 훅, 기본 빈 보호자 목록 fixture, 생성자 연결 |
| `app/src/test/java/com/example/seniorshield/monitoring/orchestrator/CoordinatorTickCharacterizationTest.kt` | +1/-0. 생성자 인자 1곳만 추가 |
| `app/src/test/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinatorIdleExpiryBoundaryTest.kt` | +4/-0. 생성자 인자 4곳만 추가; import 변경 없이 fake FQCN 사용 |
| `app/src/test/java/com/example/seniorshield/monitoring/orchestrator/PopupGuardianSmsToggleTest.kt` | 신규 111줄. 정상 표시 사례 5개 |
| main 작업트리의 `investigations/2026-10-08-popup-sms-toggle/IMPL_LOG.md` | 이 라운드의 변경·기대 결과·정적 계수 기록 |

### 사례 매핑과 기대 결과 (실행 결과 아님)

| 테스트 메서드 | 경로 | 조건 | 기대 guardian | 라운드 A 기대 |
|---|---|---|---|---|
| `escalationSmsMenuOffWithGuardianShowsNullGuardian` | escalation | OFF + 보호자 있음 | null | RED: guardian assertEquals 실패 |
| `escalationSmsMenuOnWithGuardianShowsGuardian` | escalation | ON + 보호자 있음 | 등록한 보호자 | GREEN |
| `escalationSmsMenuOnWithoutGuardianShowsNullGuardian` | escalation | ON + 보호자 없음 | null | GREEN |
| `newTriggerSmsMenuOffWithGuardianShowsNullGuardian` | new-trigger | OFF + 보호자 있음 | null | RED: guardian assertEquals 실패 |
| `newTriggerSmsMenuOnWithGuardianShowsGuardian` | new-trigger | ON + 보호자 있음 | 등록한 보호자 | GREEN |

- 다섯 사례 모두 실제 coordinator를 실행하고 overlay 경계에서 guardian을 수집한다. `verify(exactly = 1)`로 전체 show 호출 수를 확인한 뒤 `assertEquals(expectedGuardian, shownGuardian)`를 검사한다.
- escalation: 미통보 `REMOTE_CONTROL_APP_OPENED` 방출.
- new-trigger: 기존 `unnotifiedRemoteTriggerActuallyFiresWithoutEscalation`처럼 session update 후 alert/level만 notified 처리. active threat는 미통보 상태를 유지한다.
- 동일 FakeClock(1,000,000ms)을 사용한다. maintenance 시간이 진행되지 않도록 `runCurrent()`로 처리하고 finally에서 `stop()` + `runCurrent()`로 정리한다.
- 현행 firstGuardian은 설정을 읽지 않고 보호자를 반환하므로 OFF 2건만 guardian 단언에서 실패할 것으로 예상한다. 컴파일 오류는 RED로 인정하지 않는다.
- 예외/빈 Flow 테스트와 설정 대기·취소/reset 테스트는 작성하지 않았다. Settings fake의 `smsMenuFailure`와 `emptySmsMenuFlow`는 라운드 B 준비 훅이다.

### 정적 계수 전/후

수정 전과 수정 후 아래 명령을 각각 실행했으며 exit code는 모두 0이다.

```powershell
python -I -B C:/Users/momen/AndroidStudioProjects/Senior_Shield/investigations/2026-10-07-refactor-plan/static_invariants.py app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt
```

두 JSON을 파싱해 전체 구조/키/값을 비교한 결과 `identical = true`, `changedKeys = []`이다. 아래 표는 두 결과를 모든 숫자 leaf 및 multiset 키까지 펼친 전/후 비교다. import와 생성자 파라미터는 계수 대상이 아니다.

| 항목 | 전 | 후 | 차이 |
|---|---:|---:|---:|
| `Synchronized_annotations` | 6 | 6 | 0 |
| `expectedResetEpoch_epochAtTickStart` | 2 | 2 | 0 |
| `hook_calls.afterDebugEpochCapturedBeforePublish` | 1 | 1 | 0 |
| `hook_calls.afterDebugPublicationBeforeEffects` | 1 | 1 | 0 |
| `hook_calls.beforeDebugOverlayEffect` | 1 | 1 | 0 |
| `hook_calls.beforeInactiveSessionCleanupCommit` | 1 | 1 | 0 |
| `hook_calls.beforePublicationNotificationCommit` | 2 | 2 | 0 |
| `hook_calls.beforePublicationPopupAccountingCommit` | 2 | 2 | 0 |
| `hook_calls.beforeRenewalDowngradeCleanup` | 1 | 1 | 0 |
| `k5_calls.appUsageMonitor.latestBankingForegroundEventTimestamp` | 1 | 1 | 0 |
| `k5_calls.callMonitor.currentCallId` | 8 | 8 | 0 |
| `k5_calls.callMonitor.isTelebankingAnchorHot` | 1 | 1 | 0 |
| `k5_calls.clock` | 6 | 6 | 0 |
| `k5_calls.cooldownManager.isShowing` | 4 | 4 | 0 |
| `k5_calls.overlayManager.isEndCallSuppressed` | 2 | 2 | 0 |
| `k5_calls.sessionTracker.isCurrentSessionIdleTimedOut` | 1 | 1 | 0 |
| `k5_calls.sessionTracker.isSnoozeActive` | 2 | 2 | 0 |
| `k5_calls.sessionTracker.isSnoozedForCall` | 1 | 1 | 0 |
| `k5_calls.sessionTracker.snoozedAtOrNull` | 1 | 1 | 0 |
| `k5_calls.sessionTracker.snoozedCallIdOrNull` | 1 | 1 | 0 |
| `k5_property_reads.cooldownManager.dismissedAtMillis` | 1 | 1 | 0 |
| `k5_property_reads.cooldownManager.lastCountdownSec` | 1 | 1 | 0 |
| `k5_property_reads.cooldownManager.showedAtMillis` | 1 | 1 | 0 |
| `k5_property_reads.sessionTracker.sessionState.value` | 5 | 5 | 0 |
| `k5_property_reads.sessionTracker.userResetEpoch` | 17 | 17 | 0 |
| `k5_volatile_reads.cooldownConsumedSessionId` | 7 | 7 | 0 |
| `k5_volatile_reads.previousBankingForeground` | 2 | 2 | 0 |
| `k5_volatile_reads.s2RecRefireState` | 6 | 6 | 0 |
| `log_calls` | 48 | 48 | 0 |
| `log_string_literals_multiset."$reason — active Event publication retained"` | 1 | 1 | 0 |
| `log_string_literals_multiset."$reason — dismiss overlay/cooldown and clear current event"` | 1 | 1 | 0 |
| `log_string_literals_multiset."$reason — fail-closed PENDING Event retained"` | 1 | 1 | 0 |
| `log_string_literals_multiset."$reason — fail-closed safe-confirm warning retained for retry"` | 1 | 1 | 0 |
| `log_string_literals_multiset."$reason — newer Debug binding retained"` | 1 | 1 | 0 |
| `log_string_literals_multiset."$reason — newer Event context retained"` | 1 | 1 | 0 |
| `log_string_literals_multiset."$reason — newer PUBLISHED Event retained"` | 1 | 1 | 0 |
| `log_string_literals_multiset."Debug overlay skipped: production Event publication is pending"` | 1 | 1 | 0 |
| `log_string_literals_multiset."Debug overlay skipped: published Event was replaced before binding"` | 1 | 1 | 0 |
| `log_string_literals_multiset."anchorHotState mirror → $hot"` | 1 | 1 | 0 |
| `log_string_literals_multiset."call became IDLE during suppression, stabilization scheduled"` | 1 | 1 | 0 |
| `log_string_literals_multiset."cooldownConsumedSessionId cleared: session changed (was=$cooldownConsumedSessionId, now=${session.id})"` | 1 | 1 | 0 |
| `log_string_literals_multiset."cooldownConsumedSessionId cleared: session disappeared (was=$cooldownConsumedSessionId)"` | 1 | 1 | 0 |
| `log_string_literals_multiset."cooldownConsumedSessionId set=${session.id}: banking cooldown fired (level=${score.level}, isCallActive=$isCallActive)"` | 1 | 1 | 0 |
| `log_string_literals_multiset."cooldownConsumedSessionId set=${session.id}: telebanking cooldown fired (level=${score.level})"` | 1 | 1 | 0 |
| `log_string_literals_multiset."coordinator started"` | 1 | 1 | 0 |
| `log_string_literals_multiset."event publication failed; PENDING provenance retained"` | 1 | 1 | 0 |
| `log_string_literals_multiset."event publication superseded — abort escalation effects"` | 1 | 1 | 0 |
| `log_string_literals_multiset."event publication superseded — abort new-trigger effects"` | 1 | 1 | 0 |
| `log_string_literals_multiset."exact Event cleanup deferred: safe-confirm retry retains ${publication.eventId}"` | 1 | 1 | 0 |
| `log_string_literals_multiset."ghost check fallback: now=$now, dismissedAt=$dismissedAt, window=${fallbackWindow}ms → ghost=$isFallbackGhost"` | 1 | 1 | 0 |
| `log_string_literals_multiset."ghost check: eventTs=$eventTs, showedAt=$showedAt, dismissedAt=$dismissedAt → ghost=$isGhost"` | 1 | 1 | 0 |
| `log_string_literals_multiset."notification escalation: alertState=${session.notifiedAlertState}→$alertState, level=${session.notifiedLevel}→${score.level}"` | 1 | 1 | 0 |
| `log_string_literals_multiset."origin=${pending.request.origin}, subject=${pending.request.subject}"` | 1 | 1 | 0 |
| `log_string_literals_multiset."overlay show skipped: trusted Event binding is not current (${event.id})"` | 1 | 1 | 0 |
| `log_string_literals_multiset."popup shown on state transition → $alertState (s2Snapshot=${s2RecRefireState.snapshot})"` | 1 | 1 | 0 |
| `log_string_literals_multiset."popup suppressed by S2 REC-REFIRE debounce (escalation path) — rawTick=$rawTickSignals, snapshot=${s2RecRefireState.snapshot}"` | 1 | 1 | 0 |
| `log_string_literals_multiset."popup suppressed by S2 REC-REFIRE debounce (new-trigger path) — new=$newTriggers, snapshot=${s2RecRefireState.snapshot}"` | 1 | 1 | 0 |
| `log_string_literals_multiset."popup suppressed: cooldown fired this tick"` | 1 | 1 | 0 |
| `log_string_literals_multiset."publication replaced — abort new-trigger effects"` | 1 | 1 | 0 |
| `log_string_literals_multiset."publication replaced — abort new-trigger notification"` | 1 | 1 | 0 |
| `log_string_literals_multiset."publication replaced — abort notification commit"` | 1 | 1 | 0 |
| `log_string_literals_multiset."publication replaced — abort notification escalation"` | 1 | 1 | 0 |
| `log_string_literals_multiset."renewal downgraded to $alertState — dismiss stale popup/current event"` | 1 | 1 | 0 |
| `log_string_literals_multiset."safe-confirm failed closed at ${pending.nextStep}: "` | 1 | 1 | 0 |
| `log_string_literals_multiset."safe-confirm failed closed: origin=${request.origin}, subject=${request.subject}"` | 1 | 1 | 0 |
| `log_string_literals_multiset."session score: total=${score.total}, level=${score.level}, alertState=$alertState, sessionId=${session.id}"` | 1 | 1 | 0 |
| `log_string_literals_multiset."signal tick — rawCall=$callSignals, app=$appSignals, banking=$bankingForeground, install=$installSignals, deviceEnv=$deviceEnvSignals, advanced=$advancedSources"` | 1 | 1 | 0 |
| `log_string_literals_multiset."snooze filter applied (callId=$liveCallId): rawCall=$callSignals → filteredCall=$filtered"` | 1 | 1 | 0 |
| `log_string_literals_multiset."snooze still active — skip popup/notification/cooldown this tick (callId=$liveCallId)"` | 1 | 1 | 0 |
| `log_string_literals_multiset."suppression active, skip popup/notification/cooldown"` | 1 | 1 | 0 |
| `log_string_literals_multiset."tick predates user reset — session transition skipped"` | 1 | 1 | 0 |
| `log_string_literals_multiset."user reset during tick — abort $stage"` | 1 | 1 | 0 |
| `log_string_literals_multiset."뱅킹 쿨다운 발동: level=${score.level}, alertState=$alertState, reason=$reason"` | 1 | 1 | 0 |
| `log_string_literals_multiset."뱅킹 쿨다운 생략: call-based 세션 아님"` | 1 | 1 | 0 |
| `log_string_literals_multiset."뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id}, alertState=$alertState)"` | 1 | 1 | 0 |
| `log_string_literals_multiset."새 trigger 팝업: new=$newTriggers (s2Snapshot=${s2RecRefireState.snapshot})"` | 1 | 1 | 0 |
| `log_string_literals_multiset."텔레뱅킹 쿨다운 발동: level=${score.level}"` | 1 | 1 | 0 |
| `log_string_literals_multiset."텔레뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id})"` | 1 | 1 | 0 |
| `previousBankingForeground_assignments` | 1 | 1 | 0 |
| `return_collect` | 0 | 0 | 0 |
| `s2_calls.s2RecRefireStateAfterFiring` | 2 | 2 | 0 |
| `s2_calls.shouldSuppressS2RecRefire` | 2 | 2 | 0 |
| `synchronized_calls` | 10 | 10 | 0 |
| `userResetIntervened_labels_multiset."cooldown stage"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."escalation effects"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."escalation popup show"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."escalation post-navigation gate"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."escalation post-push"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."new-trigger effects"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."new-trigger popup show"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."new-trigger post-navigation gate"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."new-trigger post-push"` | 1 | 1 | 0 |
| `userResetIntervened_labels_multiset."post ghost query"` | 1 | 1 | 0 |

### 소스 대조 및 범위 검증

- Coordinator에서 허용된 추가 2줄을 제거한 내용이 HEAD 원본과 일치한다(CRLF/LF 정규화 비교). firstGuardian 본문, 운영 호출부 2곳, Debug 경로, 훅·락·reset 재검증 모두 그대로다.
- 기존 테스트 2개 파일에서 새 생성자 인자를 제거한 내용이 HEAD 원본과 일치한다. 기존 단언 수정 0.
- 생성자 직접 호출 6곳 전부 새 인자가 있다. SettingsRepository의 8개 메서드 시그니처와 fake override를 대조했다.
- 기존 DataModule의 SettingsRepository binding을 읽어 확인했다. DI 모듈, Manifest, 권한, overlay, binding 파일 수정 0.
- Guardian 모델의 id/name/phoneNumber 및 기본 relationship, overlay show의 guardian(nullable 두 번째 인자), 하네스 start·FakeClock·session API를 직접 대조했다.
- guardian fixture 기본값은 빈 목록이고 beforeFirstEmission 호출 순서는 보존했다. 제품 설정 기본값은 변경하지 않았다.
- `git diff --check` exit 0. 신규 테스트 @Test 5개, advanceUntilIdle 사용 0. worktree porcelain에 허용된 소스/테스트 5개만 존재함을 확인했다.
- 소스 대조에서 구체적 시그니처 불일치는 발견하지 않았다. Kotlin/MockK 컴파일과 Hilt 생성 코드, 실제 Flow 스케줄링 결과는 미확인이다.

### Claude 실행 요청

작업 위치는 위 worktree다. 다음 명령은 Codex가 실행하지 않았다.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.seniorshield.monitoring.orchestrator.PopupGuardianSmsToggleTest"
.\gradlew.bat :app:testDebugUnitTest
```

첫 실행에서 신규 5건 중 OFF 2건만 guardian 단언 RED, ON 3건 GREEN인지 확인한다. 전체 실행에서는 해당 2건 외 기존 테스트가 모두 GREEN인지 확인한다. 전체 명령은 예상 RED 2건 때문에 실패 종료할 수 있다. Claude의 확인과 별도 라운드 B 지시 전까지 구현을 진행하지 않는다.

## 라운드 B (GREEN 구현) + 라운드 C (Codex 전체 자체검토) — T003

### 입력 근거와 상태

- 근거: 20261008-1513-T003-TASK 및 DIRECTIVE v1.1 K1–K7, 라운드 B·C.
- Claude가 보고한 라운드 A 실행: 11 suites / 189 tests / skipped 0; 실패는 OFF+보호자 2건의 guardian 단언뿐이며 ON 3건과 해당 실행의 기존 테스트는 GREEN, 컴파일 정상.
- B 구현 및 C 소스 자체검토 완료. **이번 변경의 컴파일·테스트 GREEN·lint·검증기 통과는 미확인**이며 Claude 실행 대기.
- Gradle·adb·git 쓰기 0. 신규 파일 작성 0. T003에서는 허용된 Coordinator, 하네스, 신규 테스트 파일과 이 로그 append만 수행했다.

### B1·B2 변경

- `DefaultRiskDetectionCoordinator.kt:591`: 설정 `observeSmsMenuEnabled().first()`를 먼저 조회한다. `CancellationException`은 재전파하고 다른 `Exception`은 지정된 `Log.w(TAG, ..., e)` 1회 후 false로 취급한다.
- OFF/fallback이면 즉시 null을 반환하여 보호자 조회를 생략한다. ON일 때만 기존 `guardianRepository.observeGuardians().first().firstOrNull()`을 실행한다. 보호자 조회는 try/catch 바깥이다.
- 기존 운영 호출부 두 곳, nowMs 취득, popup reset 재검증, accounting 훅, Debug 경로 및 락은 변경하지 않았다. 추가 import는 필요하지 않았다.
- `CoordinatorTestHarness.kt:302`: `beforeSmsMenuEmission: (suspend () -> Unit)? = null` 및 flow 본문 첫 줄의 훅 호출만 추가했다(T003 하네스 변경 +2줄). null 기본값에서 기존 fake 동작과 기본 ON은 동일하다.
- timeout·실시간 토글 구독·새 상태·권한·서비스·외부 연락 동작 추가 0.

### B3 신규 테스트 8건 및 판별력

기존 정상 표시 5건을 보존하여 이 클래스는 총 13건이다. 아래 결과는 실행 기대이며 mutation 실행 결과가 아니다.

| 테스트 | 경로 | 사례 | 기대 | 판별력 근거 |
|---|---|---|---|---|
| `escalationSettingFailureHidesGuardianAndNextTickShowsGuardian` | escalation | IllegalStateException 후 정상 ON tick | 첫 show 1회/null, 다음 show 추가 1회/보호자 | 설정 읽기 1→2, 보호자 읽기 0→1, 수집 guardian [null, 보호자], push 2. B1 누락·fail-open·catch 누락·collector 중단·fallback 고정이면 실패 |
| `newTriggerSettingFailureHidesGuardianAndNextTickShowsGuardian` | new-trigger | IllegalStateException 후 정상 ON tick | 첫 show 1회/null, 다음 show 추가 1회/보호자 | alert/level만 notified로 첫 new-trigger 강제; 나머지 판별은 위와 동일 |
| `escalationEmptySettingFlowShowsNullGuardian` | escalation | 빈 Flow(first의 NoSuchElementException) | show 1회/null, 보호자 읽기 0 | 보호자 fixture가 존재하고 설정 훅 1회 확인. B1 누락·예외 종류를 좁힌 catch·fail-open이면 실패 |
| `newTriggerEmptySettingFlowShowsNullGuardian` | new-trigger | 빈 Flow | show 1회/null, 보호자 읽기 0 | new-trigger 강제 후 위와 동일 |
| `escalationStopDuringSettingReadAbortsPopupAndTickAssignment` | escalation | 설정 awaitCancellation 중 stop | Job 종료 후 show/accounting/보호자 읽기 0, previousBanking=false | 훅 진입을 확인한 뒤 stop→runCurrent→tickJob.join. hook finally·Job 취소/완료, 실제 tick의 banking=true와 이전값=false 차이를 검사. B1 누락·취소 삼킴이면 실패 |
| `newTriggerStopDuringSettingReadAbortsPopupAndTickAssignment` | new-trigger | 설정 awaitCancellation 중 stop | 동일 | 올바른 clock으로 session을 사전 생성하고 alert/level만 notified 처리. show뿐 아니라 취소 후 banking 대입·active threat 통보·accounting 진입도 검사 |
| `escalationResetDuringSettingReadAbortsAtPopupRevalidation` | escalation | 설정 대기 중 tracker reset 후 ON 재개 | show 0, currentEvent null, accounting 0 | 진입·미재개를 확인한 뒤 epoch +1 및 실제 재개/보호자 읽기 1을 확인. reset은 Event를 직접 지우지 않아 기존 popup 재검증이 회수해야 함. 재검증 제거 시 후단 binding 검사로 show만 0이어도 event/accounting 단언이 실패 |
| `newTriggerResetDuringSettingReadAbortsAtPopupRevalidation` | new-trigger | 설정 대기 중 tracker reset 후 ON 재개 | 동일 | new-trigger 강제 후 위와 동일 |

- 예외 후 다음 tick은 remote에 `BANKING_APP_OPENED_AFTER_REMOTE_APP`을 추가한다. S2 upgrade delta로 억제를 벗어나고 bankingForeground=false를 유지하므로 cooldown 대신 팝업 경로에 도달한다. 이 후속 tick은 CRITICAL escalation이며, 첫 실패 경로가 무엇이든 collector 생존과 새 설정 읽기를 확인한다.
- stop 테스트는 첫 signal tick 전에 remote와 banking=true를 준비한다. call 기반 신호가 없으므로 banking cooldown이 발생하지 않는다. 설정 읽기는 notification/push 이후이므로 각 1회를 보존하는 것이 올바른 기대이며, 취소 뒤 추가 show/accounting/guardian 조회와 banking 이전값 대입이 없어야 한다.
- reset 유도는 기존 `DefaultRiskDetectionCoordinatorIdleExpiryBoundaryTest`의 `sessionTracker.resetAfterUserConfirmedSafe()` 방식을 재사용했다. 후단 binding 검증만으로 우연히 통과하지 않도록 currentEvent 회수와 popup accounting 훅 미진입까지 검사한다.
- 모든 테스트는 runCurrent와 finally의 stop으로 정리한다. advanceUntilIdle 사용 0. 설정 대기 테스트는 훅 진입 확인 없이 await하지 않으므로 B1 누락 시 무한 대기 대신 단언 실패한다.

### B4 정적 계수 전/후

라운드 B 수정 직전과 수정 후 worktree에서 아래 명령을 실행했으며 exit code는 모두 0이다.

```powershell
python -I -B C:/Users/momen/AndroidStudioProjects/Senior_Shield/investigations/2026-10-07-refactor-plan/static_invariants.py app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt
```

| 항목 | 전(A) | 후(B) | 차이 |
|---|---:|---:|---:|
| log_calls | 48 | 49 | +1 |
| 새 Log 문자열 `"sms menu setting read failed — guardian SMS button hidden"` multiplicity | 0 | 1 | +1 |
| 기존 Log 문자열 multiset | 49개 키 | 49개 기존 키 유지 | 0 |
| 나머지 top-level 계수 그룹 | 11개 | 11개 동일 | 0 |

- JSON 구조 전체 비교 결과 변경 키는 정확히 `["log_calls", "log_string_literals_multiset"]`이다.
- 기존 문자열의 모든 multiplicity가 동일하고, 새 문자열 키는 위 1개뿐이다. 나머지 11개 그룹의 모든 값·키·중첩 구조가 동일하다.
- 라운드 A의 전/후 93개 숫자 leaf 표에서 log_calls만 48→49이고 문자열 leaf 1개가 추가된다. synchronized 10, @Synchronized 6, expectedResetEpoch 2, reset 라벨 10, 훅 7개 호출 벡터, K5 조회, S2 호출 및 previousBanking 대입 계수는 모두 그대로다.

### 라운드 C 전체 자체검토

대상은 `650cb54` 대비 worktree 전체 tracked diff와 미추적 `PopupGuardianSmsToggleTest.kt`를 포함한다.

| 렌즈 | 판정 | 근거 |
|---|---|---|
| ① 실패 계약·취소 의미 | 소스상 적합 | catch 순서가 CancellationException→Exception이며 설정 조회 한 줄만 감싼다. OFF/fallback에서 guardian 조회 0, ON에서 기존 표현식 실행 |
| ② 순서·타이밍 | 소스상 적합 | 허용된 import·생성자·firstGuardian 변경을 되돌린 메모리 내 소스가 650cb54와 동일. 호출부·nowMs·reset check·accounting 훅 순서 무변경 |
| ③ 계약 보존 | 소스상 적합 | 허용 외 프로덕션 diff 0; 정적 계수 허용 차이만 존재. reflection 대상 필드·overlay/binding·Debug·S2/α 파일 무변경 |
| ④ 테스트 판별력 | 소스상 적합, 실행 대기 | 설정 조회/보호자 조회 수, guardian 값과 show 횟수, Job 완료 및 대입 미실행, reset 후 실제 재개/회수/accounting 미진입 검증 |

- 기존 두 테스트 파일은 T003 시작 시 SHA-256과 동일하다. 하네스에서 추가 훅 2줄을 제거한 bytes의 SHA-256이 T003 시작과 동일하다.
- 기존 정상 테스트 5개와 해당 helper의 단언을 보존했다. 추가 테스트에 사용한 Job·CompletableDeferred·reset·clock·session API는 기존 테스트 및 소스와 대조했다.
- 별도 읽기 전용 senior-shield 검토에도 전체 diff와 신규 테스트를 전달해 실패/취소 의미와 판별력을 독립 확인했다.
- 검토 결과: must-fix 0, recommend 0. 아래 info는 승인된 잔여 특성 또는 실행 미확인 사항이다.

| 등급 | 지적/잔여 사항 | 근거·처리 |
|---|---|---|
| info | 설정 Flow가 끝없이 대기하면 tick도 대기한다 | DIRECTIVE K2의 timeout 미도입은 승인된 결정. stop 취소와 조회 재개 후 reset 재검증은 유지 |
| info | ON 이후 보호자 조회의 예외/대기는 기존 계약 그대로다 | 보호자 조회를 catch 밖에 보존. 복구 확대는 범위 밖이며 기존 백로그로 유지 |
| info | 신규 8건 및 전체 GREEN·lint는 아직 실행하지 않았다 | TASK의 Gradle 금지에 따라 Claude 검증 필요. 소스 분석과 예상 mutation 판별을 실측 테스트 통과로 보고하지 않음 |

### S2 §6 체크리스트 8항목

N/A 처리하지 않고 8항목 모두 소스 diff 기준 위반 0을 확인했다. 자동 guardrail 실행은 Claude에게 이관한다.

| # | 항목 | 판정·근거 |
|---|---|---|
| 1 | 일단 닫기 dismiss-only | 유지. RiskOverlayManager/BankingCooldownManager 및 CTA 본문 변경 0 |
| 2 | safe-confirm 전용 흐름 분리 | 유지. confirmSafe·binding·진입점 변경 0; 테스트의 직접 reset은 테스트 코드에만 존재 |
| 3 | REC-REFIRE는 S2 orchestration-layer debounce | 유지. S2 함수/게이트 호출부와 입력면 무변경, CTA 새 참조 0 |
| 4 | TTL 경계 `(now - lastFiredAt) > S2_REC_REFIRE_TTL_MS` | 유지. S2RecRefireDebounce.kt 무변경, nowMs 취득 위치 무변경 |
| 5 | 비통화 dismiss에서 call-safe 효과 없음 | 유지. dismiss 및 call-safe 경로 수정 0 |
| 6 | 통화 safe-confirm만 통화 안전 부수효과 허용 | 유지. markCurrentCallConfirmedSafe/snoozeForCall 호출 변경 0 |
| 7 | α/S2 상수·상태·함수·테스트 클래스 분리 | 유지. 신규 공용화 0, 두 debounce 구현/테스트 클래스 변경 0 |
| 8 | UPGRADE_TRIGGERS 단일화/원소 변경 없음 | 유지. 3개 set 선언 모두 그대로이며 이번 범위에서 통합하지 않음 |

C1/C2/C3: 새 CTA 부수효과와 monitor signal 시퀀스 변경이 없어 production 입력면·scope·escape delta 변경 0. 테스트에서 주입한 upgrade 신호는 fixture에 한정한다. 정책/권한 신규 리스크는 발견하지 않았으며 수동 ACTION_SENDTO 버튼의 표시 조건만 바뀐다.

### Claude 실행 요청

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.seniorshield.monitoring.orchestrator.PopupGuardianSmsToggleTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.seniorshield.monitoring.orchestrator.*" --tests "*RiskOverlayManagerBindingContractTest"
```

그다음 DIRECTIVE에 지정된 `.github/workflows/verify.yml:54-84` 전체 직렬 게이트를 실행한다: clean; domain risk/contracts check; app unit/kapt/assemble/duplicate classes/androidTest assemble; data/app lint; 검증기 4종(unit XML, domain lint, app/data lint union, schema drift); verifier self-check. Codex는 이 명령들을 실행하지 않았다.


## T004 — 설정 예외 테스트의 checked IOException 판별력 보완

- 근거: 20261010-0025-T004-TASK, DIRECTIVE v1.1 K7 및 Claude의 T003 REVIEW PASS_WITH_NOTES.
- Claude 보고 실행 결과: 표적 11 suites / 197 tests GREEN(신규 13건 포함). MB1 RED 6, MB2 stop 2건 RED, MB3 RED 4, MB4 RED 8. MB5(Exception catch를 RuntimeException으로 축소)는 기존 13건 전부 통과하여 미검출.
- 원인: 이전 설정 예외 fixture의 IllegalStateException과 빈 Flow의 NoSuchElementException은 모두 RuntimeException 계열이다. 따라서 T003 판별력 표의 catch 축소 검출 근거는 checked 예외까지 포괄하지 못했다.
- 변경: PopupGuardianSmsToggleTest.kt의 공용 helper에서 emptyFlow=false일 때 주입하던 예외 한 줄만 java.io.IOException("settings datastore read failed")으로 교체했다. import 추가 없이 FQCN을 사용했다.
- 적용 테스트: escalationSettingFailureHidesGuardianAndNextTickShowsGuardian 및 newTriggerSettingFailureHidesGuardianAndNextTickShowsGuardian. 테스트 이름과 모든 단언을 유지했다.
- 빈 Flow 테스트는 그대로다. 이제 checked IOException과 RuntimeException 계열 NoSuchElementException을 각각 검증한다. 하네스 smsMenuFailure는 Exception?이므로 변경 없이 IOException을 수용한다.
- 판별력 기대: 정상 catch(Exception)는 IOException을 OFF로 처리하여 첫 show 1회/guardian null 및 다음 정상 ON tick을 유지한다. MB5 catch(RuntimeException)는 IOException을 잡지 못해 fallback 표시와 후속 tick 계약이 깨지므로 위 2건이 RED여야 한다.
- 검증: 교체를 메모리에서 역변환한 테스트 bytes가 수정 전과 완전히 동일함을 확인했다. 총 13개 테스트 유지. 프로덕션·하네스·기존 테스트 2개의 SHA-256은 작업 전후 동일하다. 이 로그는 기존 내용 뒤에 append했다.
- 실행 상태: Codex는 Gradle·adb·git 쓰기 및 mutation probe를 실행하지 않았다. 정상 13건 GREEN과 MB5에서 위 2건 RED인지는 Claude 재실행 대기다.


## T005 — CI unit floor 래칫 및 unit-removed probe 보정

- 근거: 20261010-1007-T005-TASK, 사용자 승인("권고대로 진행해", 2026-10-10).
- 작업 위치: `.worktrees/sms-toggle`, HEAD `8697980`. 시작 시 worktree porcelain 출력은 비어 있었다.
- Claude 실측 원인: app 564건에서 첫 suite 12건만 제거하면 552건으로 기존 floor 544 이상이므로 unit-removed probe가 기대한 검증기 FAIL이 나오지 않는다. Codex는 self-check를 재실행하지 않았다.
- `.github/scripts/verify-unit-xml.ps1`: app MinTests 544→564. 기존 Baseline 주석 보존 및 래칫 이력 한 줄 추가. risk 7 / contracts 4 유지.
- `.github/workflows/verify.yml`: 단계 이름의 floor 표기만 564/7/4로 변경.
- `investigations/2026-09-06-ci-baseline-t1/probe-verifiers.ps1`: unit-removed 블록에서 $unitScript 본문의 app MinTests를 정규식으로 추출하며 실패 시 throw. app suite XML을 이름순으로 하나씩 삭제하고 매번 남은 XML의 tests 합계를 다시 계산하여 floor 미만에서 중단한다. 모두 삭제해도 미달하지 않으면 throw. 라벨은 `unit: app suites removed until below floor`, 기대값은 $false 유지.
- 정적 확인: PowerShell Parser.ParseFile로 두 ps1 파일 파싱 오류 총 0. 수정 probe의 AST에서 추출한 정규식으로 실제 verifier 본문의 app floor 564를 읽는 것을 확인했다. git diff --check exit 0.
- 범위 확인: HEAD 대비 verifier는 floor/이력만, workflow는 단계 이름 한 줄만 변경. probe의 unit-removed 앞뒤 본문은 동일하여 다른 probe 및 함수 변경 0. worktree 변경은 지정한 3개 파일뿐이다.
- 이 로그는 TASK가 별도로 지정한 주 작업공간 IMPL_LOG 경로에 append했다. 9월 DIRECTIVE·IMPL_LOG 및 프로덕션·테스트 코드는 수정하지 않았다.
- 미확인/이관: 실제 verifier self-check와 CI 실행 결과는 Claude 확인 대기. Codex의 git 쓰기 0, Gradle 실행 0, self-check 실행 0.
