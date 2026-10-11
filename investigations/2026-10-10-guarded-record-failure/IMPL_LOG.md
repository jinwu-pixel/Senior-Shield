# GUARDED 이력 기록 실패 — T001 구현 로그

## 상태와 승인 범위

K1–K4 구현 및 T2 정적 자체검토 완료. 빌드·컴파일·단위 테스트·RED/GREEN·mutation·lint 게이트는 Claude 실행 대기다. 실행 성공이나 전체 트랙 완료를 주장하지 않는다. Gradle·adb·git 쓰기 0회.

정본 `C:/Users/momen/AndroidStudioProjects/Senior_Shield/investigations/2026-10-10-guarded-record-failure/DIRECTIVE.md` v1.0 전체와 TASK를 읽고 승인된 5줄 계획을 따랐다. base는 `234d9444c1bdd89c59b911d4a24b9480e69336eb`, 브랜치는 `codex/guarded-record-failure`; 시작 시 git status는 깨끗했다.

직접 읽은 구현 근거: `processTickEscalation` 전체, `commitNotificationIfCurrent`, `publishCurrentRiskEvent`, tick 호출/마지막 banking 대입, `FakeRiskEventSink`, `PopupGuardianSmsToggleTest` 전체, `RiskEvaluatorImpl`, `AlertStateResolver`, `RiskSignal.category`, `RiskSession`, cooldown ghost 판정, `.github/S2_REVIEW_CHECKLIST.md`.

스킬 적용: using-superpowers, executing-plans, test-driven-development, verification-before-completion. 이 TASK의 명시적 제한에 따라 별도 스킬 작업파일·커밋·테스트 실행·독립 에이전트 검토를 만들지 않고, 이 로그에 기록하며 Claude에게 실행 및 독립 검토를 인계한다. 테스트를 먼저 작성하고 K1을 적용했으나 RED-first 실측은 Claude가 K1 hunk를 역적용해 수행한다.

## 변경 목록

경로는 worktree 기준. 추적 파일 증감은 git diff 기준, 신규 파일은 실제 행 수다.

| 파일 | 추가 | 삭제 | 전체 행 수 |
|---|---:|---:|---:|
| `app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt` | 8 | 1 | 1926 |
| `app/src/test/java/com/example/seniorshield/testutil/CoordinatorTestHarness.kt` | 4 | 0 | 327 |
| `app/src/test/java/com/example/seniorshield/monitoring/orchestrator/GuardedHistoryRecordFailureTest.kt` | 163 | 0 | 163 |
| `.github/scripts/verify-unit-xml.ps1` | 2 | 1 | 58 |
| `.github/workflows/verify.yml` | 1 | 1 | 106 |

코드/CI 합계 +178/-3. 문서는 이 IMPL_LOG와 `collab/to-claude/<Python datetime 실측>-T001-RESULT.md`만 신규 작성한다. 기존 파일의 CRLF를 유지하고 신규 Kotlin 파일도 CRLF로 맞췄다. CI 두 파일은 ASCII 디코딩으로 확인했다.

## K 매핑

| 계약 | 구현 | 정적 확인 |
|---|---|---|
| K1 | GUARDED record 호출 1곳만 지정 try/catch로 교체 | CancellationException 재던짐 → Exception catch 첫 줄 ensureActive → 지정 Log.e → 블록 뒤 null. 기존 import 3개 존재, 추가 0. K1 치환 외 Coordinator 전체가 HEAD와 동일 |
| K2 | `recordFailure: Exception? = null`, `beforeRecord: (suspend (RiskEvent) -> Unit)? = null` | beforeRecord → recordFailure throw → recorded 추가 순서. 기본값은 기존 recorded 추가만 수행 |
| K3(a) | `runtimeRecordFailureStillNotifiesAndNextPassiveEscalationRecords` | 첫 notify=1, notified=GUARDED/LOW, 기록·push·팝업=0, Job active. 실패 해제 후 동일 세션/Job에서 기록=1, notify=2, notified=GUARDED/HIGH |
| K3(b) | `checkedRecordFailureStillNotifiesAndCoordinatorSurvives` | IOException으로 동일 helper 실행. 첫 notify와 생존뿐 아니라 다음 tick 기록 회복도 확인 |
| K3(c) | `stopThenRecordIOExceptionAbortsNotificationAndTickAssignment` | stop 직후 suspend 없이 IOException. notify=0, notifiedAlertState/Level=null, notifiedActiveThreats 비어 있음, banking 이전값=false, Job cancelled/completed, popup accounting/show/cooldown=0 |
| K4 | app floor 581→584 | 신규 @Test 정확히 3개. ASCII 이력 주석 1줄, workflow 단계 이름만 변경. risk=7/contracts=4 유지 |

기존 테스트 본문·단언 변경 0. 테스트는 공용 CoordinatorTestHarness, with(harness) { start(FakeClock(...)) }, runCurrent, finally의 stop/runCurrent, 기존 accounting 훅과 previousBanking reflection 패턴을 사용한다. 실제 시간 delay 및 advanceUntilIdle 사용 0.

## 신호 조합과 취소 fixture 근거

RiskEvaluatorImpl은 UNKNOWN_CALLER=20, LONG_CALL_DURATION=30, HIGH 임계값=50이다. 첫 단계 UNKNOWN_CALLER만으로 LOW(20), 회복 단계에 LONG_CALL_DURATION을 추가하면 HIGH(50)로 실제 levelEscalated가 발생한다. RiskSignal에서 둘 다 PASSIVE이고 AlertStateResolver는 세션에 TRIGGER가 없으면 GUARDED를 반환한다. 각 단계에 resolver 결과와 notified 수준을 리터럴로 단언한다. 회복 이벤트에는 정확히 두 신호와 HIGH가 저장되고 그 이벤트로 두 번째 notify가 호출되어야 한다.

(c)는 첫 유효 tick 전에 bankingForeground=true와 UNKNOWN_CALLER를 준비한다. 이 조합은 record보다 앞선 cooldown 단계에서 쿨다운을 만들 수 있으므로, 테스트에만 기존 ghost 입력을 설정했다: showedAt=900000, dismissedAt=950000, latestBankingTs=925000. 실제 isCooldownGhostTransition이 닫힌 구간 안의 이벤트로 판단하여 쿨다운을 건너뛴다. banking 값 자체는 true로 유지되므로 ensureActive가 빠지면 마지막 대입이 false→true가 되는 판별력은 유지된다. 프로덕션 쿨다운 변경 0.

beforeRecord에서 tick Job을 캡처하고 releaseRecord.await()로 잠시 대기한다. coordinator 할당·accounting 훅 설치·previousBanking=false 관측 후 release한다. 이후 coordinator.stop()과 IOException 사이에는 suspend가 없다. cancellation 때문에 await에서 먼저 종료되는 다른 사례를 잘못 검증하지 않도록 한 구조다.

## 사례별 판별력 표 (MR1–MR4)

아래는 코드 경로에 근거한 예상이며 실측 결과가 아니다. mutation은 K1 GUARDED catch에만 적용하고 매번 복원한다.

| 사례 | MR1: K1 역적용 | MR2: ensureActive 제거 | MR3: Exception→RuntimeException | MR4: catch에서 return null |
|---|---|---|---|---|
| (a) IllegalStateException + 회복 | RED 기대 | GREEN 기대 | GREEN 기대 | RED 기대 |
| (b) IOException + 회복 | RED 기대 | GREEN 기대 | RED 기대 | RED 기대 |
| (c) stop 직후 IOException | GREEN 가능; 주 검출 대상 아님 | RED 기대 | GREEN 가능; 주 검출 대상 아님 | GREEN 기대(ensureActive 유지) |

- MR1: (a)(b)의 첫 record에서 예외가 processTick/collect까지 전파되어 coordinator Job을 끝낸다. notify=0, notified 표시는 없음, 후속 tick 회복도 불가하며 runTest가 미처리 예외를 보고할 수 있다. 수정 전 RED의 근거이며 직접 실행한 결과는 아니다.
- MR2: 활성 Job인 (a)(b)는 영향 없음. (c)는 취소된 Job에서 IOException을 흡수한 뒤 publication=null의 직접 commit으로 진행해 notified 표시·notify가 생기고 마지막 banking 대입도 수행한다. Job cancelled 자체만으로는 검출 불가하므로 효과 단언을 함께 둔다.
- MR3: RuntimeException catch는 IllegalStateException을 잡지만 checked IOException을 놓친다. (b)의 첫 notify 및 생존 단언이 실패한다.
- MR4: 일반 기록 실패 후 함수에서 조기 return null 하면 runTickStages를 중단하여 (a)(b)의 notify가 0이 된다. 승인된 A안과 기각된 B안을 구별한다.
- (c)는 원래 코드에서 IOException 전파 자체가 후속 효과를 차단하므로 GREEN일 수 있다. runTest의 미처리 IOException 보고에 따라 RED여도 MR1/MR3의 필수 증거로 세지 않는다. 역할은 MR2 검출이다.

## 정적 계수와 범위 확인

수정 전과 후 각각 다음 명령을 실행했고 exit=0이었다.

```powershell
python -I -B C:/Users/momen/AndroidStudioProjects/Senior_Shield/investigations/2026-10-07-refactor-plan/static_invariants.py C:/Users/momen/AndroidStudioProjects/Senior_Shield/.worktrees/record-failure/app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt
```

| 항목 | 전 | 후 |
|---|---:|---:|
| Log 호출 | 50 | 51 |
| userResetIntervened 라벨 합계 | 10 | 10 |
| expectedResetEpoch = epochAtTickStart | 2 | 2 |
| synchronized 호출 / @Synchronized | 10 / 6 | 10 / 6 |
| S2 억제 / 회계 호출 | 2 / 2 | 2 / 2 |
| previousBankingForeground = bankingForeground | 1 | 1 |
| return@collect | 0 | 0 |

전체 JSON을 메모리에서 비교: `log_calls`와 `log_string_literals_multiset` 외 모든 항목 동일. 신규 문자열은 `risk history record failed — notification continues without history` 1개뿐이며 기존 문자열 삭제/변경 0. hook_calls, K5 호출/프로퍼티/volatile 읽기도 모두 동일하다.

추가 정적 확인: 개행을 정규화하여 HEAD Coordinator에 K1의 정확한 치환만 한 결과가 현재 파일과 같음을 확인했다. 따라서 import, INTERRUPT 분기, publishCurrentRiskEvent, post-push 이후 블록, notification/overlay, interface, DI, data 등 금지 영역 변경 0이다. git diff --check exit=0. 신규 Kotlin @Test 3개 및 CI ASCII 확인. 컴파일/테스트 실행의 대체 증거로 취급하지 않는다.

## T2 자체검토 — must-fix / recommend / info

- must-fix: 정적 검토에서 발견 0.
- recommend: 구현 변경 권고 0.
- info: RED/GREEN·MR1–MR4·컴파일·전체 게이트 미실행. 이력 실패 시 그 이벤트의 이력 및 History/Home 집계 누락은 승인된 A안의 한계다. 재시도는 추가하지 않았다. 실제 저장소가 쓰기 후 예외를 던졌는지까지 판별하는 새 계약은 없다.

취소 의미론: 직접 CancellationException은 첫 catch가 그대로 재던진다. 동기 stop과 일반 예외가 겹치면 generic catch 첫 줄 ensureActive가 취소를 재전파하여 로그·알림 커밋·notified 표시·banking 마지막 대입을 차단한다. Error까지 잡는 Throwable catch는 추가하지 않았다. (c)는 이 경계만 검증하며 모든 stop 경합을 검증한다고 주장하지 않는다.

알림 커밋: 활성 Job의 일반 실패는 로그 후 기존 null publication으로 진행한다. 기존 post-push epoch 재검증을 거친 후 commitNotificationIfCurrent(publication=null)가 markAlertStateNotified, markNotified, notify를 기존 순서로 실행한다. 이 실패만으로 같은 수준을 재시도하지 않으며, 새 PASSIVE 신호가 수준을 올린 다음 tick에서 정상 기록/알림이 가능하다. 실패 시 notified를 건너뛰거나 조기 반환하는 B안은 구현하지 않았다.

INTERRUPT 불변: publishCurrentRiskEvent, PENDING 유지, mutex, provenance 및 receipt, notification/overlay 재검증은 HEAD와 같다. 신규 catch는 GUARDED 이력 전용 else 내부다. 하네스의 push/update/clear는 변경 0이며 기본값의 record 동작도 동일하다.

S2/CTA 8항목은 `.github/S2_REVIEW_CHECKLIST.md` §6 기준으로 모두 정적 검토했다.

| 항목 | 판정 및 근거 |
|---|---|
| 1. 일단 닫기 dismiss-only | 보존. overlay/cooldown CTA 변경 0 |
| 2. safe-confirm 별도 흐름 | 보존. 진입점과 부수효과 호출 변경 0 |
| 3. REC-REFIRE는 S2 orchestration | 보존. 게이트/회계/입력 변경 0, 새 CTA 참조 0 |
| 4. TTL `(now - lastFiredAt) > S2_REC_REFIRE_TTL_MS` | 보존. 식·상수·시각 취득 위치 변경 0 |
| 5. 비통화 dismiss의 call-safe 효과 없음 | 보존. CTA 및 monitor 변경 0 |
| 6. 통화 중 safe-confirm 전용 효과 | 보존. mark/snooze/anchor 경로 변경 0 |
| 7. α/S2 상수·상태·함수·테스트 클래스 분리 | 보존. 새 공유 추상화와 기존 클래스 변경 0 |
| 8. UPGRADE_TRIGGERS 단일화 범위 | 보존. 세 정의 모두 변경 0 |

C1/C2/C3: CTA 부수효과와 monitor 생산 신호 시퀀스 변경 0. 신규 PASSIVE 조합 및 ghost 입력은 테스트 fixture에만 존재한다. 자동 외부 연락·새 권한·서비스·Manifest·DI·Navigation·data/domain 변경 0. 승인된 본인 알림 경로를 기록 실패 후에도 유지한다.

## Claude 실행 요청 필터

아래 명령은 실행 요청이며 Codex가 실행하지 않았다. Android Studio JBR 환경에서 직렬 실행한다. MR1은 K1 hunk만 역적용하고 하네스/신규 테스트는 유지한다. MR2–MR4도 해당 K1 블록만 변형하며 각각 결과 수집 후 복원한다.

```powershell
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest --rerun-tasks --tests 'com.example.seniorshield.monitoring.orchestrator.GuardedHistoryRecordFailureTest'
```

개별 필터:

- (a) `com.example.seniorshield.monitoring.orchestrator.GuardedHistoryRecordFailureTest.runtimeRecordFailureStillNotifiesAndNextPassiveEscalationRecords`
- (b) `com.example.seniorshield.monitoring.orchestrator.GuardedHistoryRecordFailureTest.checkedRecordFailureStillNotifiesAndCoordinatorSurvives`
- (c) `com.example.seniorshield.monitoring.orchestrator.GuardedHistoryRecordFailureTest.stopThenRecordIOExceptionAbortsNotificationAndTickAssignment`

인접 회귀 필터: `com.example.seniorshield.monitoring.orchestrator.PopupGuardianSmsToggleTest`, `com.example.seniorshield.monitoring.orchestrator.DefaultRiskDetectionCoordinator*`, `com.example.seniorshield.monitoring.orchestrator.SafeConfirmation*`, `com.example.seniorshield.monitoring.orchestrator.WarningNavigationPublicationTest`, `com.example.seniorshield.monitoring.orchestrator.S2*`, `com.example.seniorshield.monitoring.session.RiskSessionTrackerAlphaTest`.

mutation 복원 후 필터 없는 fresh 전체 게이트:

```powershell
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug
```

이후 기존 workflow의 XML·domain lint·app/data lint·schema drift·verifier self-check 및 정책 검사를 수행한다. 기대 app/risk/contracts=584/7/4, 합계 595, failures/errors/skipped=0, lint 신규 0(로컬 robolectric EXTRA는 환경 기준선), schema drift=0. 필터 실행 XML에 전체 floor 검증기를 실행하지 않는다.

## 컴파일 확신도가 낮은 지점

정적으로 특정한 타입/시그니처 오류는 발견하지 않았다. 새 라이브러리·프로덕션 import가 없고 새 테스트는 기존 TestScope 확장, CompletableDeferred<Job>, currentCoroutineContext, MockK, reflection 사용 패턴을 따른다. 다만 Kotlin 컴파일을 하지 않았으므로 신규 파일의 타입 해석·internal 훅 접근과 eager dispatcher에서의 훅 대기/Job 수명은 Claude가 확인해야 한다. 특히 (c) ghost fixture와 MR2의 notify/banking 검출을 실측해야 한다.
