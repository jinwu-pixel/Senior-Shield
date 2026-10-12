# T001 저장소 실패 크래시 경로 구현 기록

## 상태와 권한

- 정본: 본 저장소 main 쪽 `investigations/2026-10-12-storage-failure-ui/DIRECTIVE.md` v1.0 전체 및 `PLAN.md` D1–D4를 읽고 수행했다.
- 기준 HEAD: `b7978a3c14d179d889f988c20f2b7ec280a2f04b`, 브랜치 `codex/storage-failure-fallback`.
- T2 사용자 승인 및 5줄 계획은 TASK/DIRECTIVE §2를 적용했다. 추가 정책 결정 없음.
- 구현 라운드 산출 완료, **컴파일·RED/GREEN·mutation·lint·최종 게이트는 미실행**이다. Claude 실행 전 통과로 간주하지 않는다.
- Gradle·컴파일·테스트 실행·adb·git stage/commit/push 없음. git 명령은 조회/diff에만 사용했다.
- 테스트 소스를 먼저 작성한 다음 프로덕션을 수정했다. RED 실측은 TASK가 정한 대로 Claude가 K2 seam을 남기고 프로덕션 hunk를 역적용하여 수행한다.
- 작업 스킬의 별도 ledger/commit/실행 절차 대신, 쓰기 제한과 역할 분담을 우선하여 이 파일에 기록하고 자체 정적 검토 후 Claude 독립 검토에 넘긴다.

## 변경 목록과 K 매핑

| K | 파일 (worktree 상대 경로) | 변경 |
|---|---|---|
| K1 | `data/src/main/java/com/example/seniorshield/data/local/PreferencesReadFallback.kt` | internal `Flow<Preferences>` 확장, IOException만 기본 Preferences 1회 방출 후 완료 |
| K1/K2/K3 | `data/src/main/java/com/example/seniorshield/data/repository/SettingsRepositoryImpl.kt` | DataStore 생성자 + Context @Inject 보존, observe 4종 fallback, set 4종 IOException 로그 |
| K1/K2/K4 | `data/src/main/java/com/example/seniorshield/data/repository/GuardianRepositoryImpl.kt` | 읽기 2종 fallback, 추가/삭제 단일 edit 내부 현재값 파싱, 쓰기 예외 전파 |
| K5 | `data/src/main/java/com/example/seniorshield/data/local/RoomRiskEventStore.kt` | map 뒤 stateIn 앞 catch, ensureActive, 빈 이력 |
| K5 | `data/src/main/java/com/example/seniorshield/data/repository/RiskRepositoryImpl.kt` | count 실패 0, CancellationException 재전파 및 일반 실패 시 ensureActive |
| K6 | `app/src/main/java/com/example/seniorshield/feature/guardian/GuardianAddViewModel.kt` | IOException 안내 후 return, saved 이벤트 없음, false 정원 안내 유지 |
| K6 | `app/src/main/java/com/example/seniorshield/feature/guardian/GuardianViewModel.kt` | 삭제 IOException 안내, 성공 안내는 성공 분기에만 유지 |
| K7 | `app/src/test/java/com/example/seniorshield/data/StorageFailureTestBase.kt` | TemporaryFolder, DataStore 소유 scope, Main dispatcher, ViewModel 해제, suspend 예외 단언 |
| K7(a)(b)(e)(k) | `app/src/test/java/com/example/seniorshield/data/SettingsRepositoryStorageFailureTest.kt` | 신규 5개 |
| K7(c)(d)(e)(k) | `app/src/test/java/com/example/seniorshield/data/GuardianRepositoryStorageFailureTest.kt` | 신규 6개 |
| K7(f) | `app/src/test/java/com/example/seniorshield/data/RoomStorageFailureTest.kt` | 신규 4개 (이력, 집계, 명시 취소, 취소와 일반 예외 경합) |
| K7(g) | `app/src/test/java/com/example/seniorshield/feature/splash/SplashStorageFailureTest.kt` | 신규 1개 |
| K7(h) | `app/src/test/java/com/example/seniorshield/feature/onboarding/OnboardingStorageFailureTest.kt` | 신규 1개 |
| K7(i)(j) | `app/src/test/java/com/example/seniorshield/feature/guardian/GuardianStorageFailureViewModelTest.kt` | 신규 3개 (추가 실패, 정원 회귀, 삭제 실패) |
| K8 | `.github/scripts/verify-unit-xml.ps1`, `.github/workflows/verify.yml` | app 예상 floor 592 → 612, risk 7 / contracts 4 유지 |

## API와 테스트 설계 근거

### 실제 캐시 확인

- `~/.gradle/caches/modules-2/files-2.1/androidx.datastore/datastore-preferences-core-jvm/1.1.1/e562d4a944240c0e67317f0b862da24aca3aabc/datastore-preferences-core-jvm-1.1.1.jar`를 JBR javap로 읽었다.
- `androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(..., CoroutineScope, Function0<File>)`와 default overload를 확인했다.
- `androidx.datastore.preferences.core.PreferencesFactory`의 Kotlin metadata `emptyPreferences` 및 JVM 이름 `createEmpty`, `preferencesOf`를 확인했다.
- core 1.1.1 원본 캐시는 `datastore-core-android/1.1.1/.../datastore-core-release.aar`이며, 해당 변환본 `~/.gradle/caches/transforms-4/364034053c92730adf21886f2c409841/transformed/datastore-core-release-runtime.jar`에서 `androidx.datastore.core.CorruptionException extends java.io.IOException`, `DataStore.getData/updateData` 시그니처를 확인했다.
- 같은 core jar의 `DataStoreImpl$transformAndWrite$2` 바이트코드 143–151: 기존값/새값 `Intrinsics.areEqual`, 같으면 197로 분기. 다를 때만 174의 `writeData` 호출로 간다. 동일 Preferences의 실제 쓰기 생략 근거다.
- 일부 javap 호출은 필요한 disassembly 출력 후 archive 종료 단계에 AccessDeniedException 진단을 냈다. 위 내용은 출력된 선언/분기에서 확인한 것으로, 컴파일이나 실행 증거가 아니다. 캐시 변경/우회 시도 없음.

### 손상 파일과 생명주기

- 손상 바이트: `0A 02 0A`. protobuf length-delimited field가 길이 2를 선언하지만 payload는 1바이트뿐인 잘린 메시지다. 빈 파일을 손상으로 가정하지 않는다.
- 손상 파일을 사용하는 테스트 5개 모두 저장소 동작을 검사하기 **전에** `assertSuspendThrows<CorruptionException> { store.data.first() }`를 실행하도록 작성했다. 실제 파싱 실패 여부는 Claude 실행에서 확정한다.
- 파일마다 DataStore 하나만 생성한다. `PreferenceDataStoreFactory.create(scope=..., produceFile=...)`에 테스트 소유 SupervisorJob + StandardTestDispatcher를 제공한다.
- `@After`에서 소유 DataStore/ViewModel scope를 cancel하고 scheduler를 drain한 다음 Main을 reset한다. TemporaryFolder 정리는 JUnit rule에 맡긴다. 프로덕션 파일 삭제 코드는 없다.
- 실제 저장 파일 바이트를 전후 비교한다. 설정 setter별, 보호자 추가/삭제별, Onboarding 실패 후에도 원본 바이트가 같아야 한다.

### JVM JSON 처리와 runner 선택

- 앱의 `unitTests.isReturnDefaultValues = true` 환경에서 Android `org.json` stub은 실제 파싱/직렬화를 보장하지 않는다. K7(d)(k)에서 JSON을 mock으로 대체하면 보존/왕복 단언의 신뢰도가 약해진다.
- 따라서 보호자 repository 테스트 클래스 하나만 기존 `testImplementation(libs.robolectric)`과 `RobolectricTestRunner`를 사용한다. SDK 33 하나, `Application::class`, `Config.NONE`으로 앱 초기화/서비스 시작을 배제한다. 새 의존성과 Gradle 수정은 0이다.
- TASK의 JVM/JUnit4 범위를 유지한다. DIRECTIVE의 “Robolectric 불필요”에서 runner는 최소 필요 범위에만 사용한 구현 선택이다. 나머지 14개는 일반 JVM 테스트이며 Robolectric 테스트는 6개다.

### K7(d) 판별력

- `SplitGuardianStore.data`는 항상 IOException을 던지고, `updateData`는 미리 저장한 실제 Preferences를 transform에 제공한다.
- update 호출 수와 실제 값 변경 수를 분리한다. 실제 DataStore처럼 동일값이면 writeCount를 증가시키지 않는다.
- K4 유지 시 add: `[one,two] → [one,two,three]`, remove: `[one,two] → [two]`, full add: `[one,two,three]` 보존 및 false, writeCount 0이다.
- K4를 제거하고 K1만 남기면 public read가 `[]`로 바뀌어 add 결과는 `[three]`뿐이다. **기존 2명 보존 단언이 먼저 실패**한다. readCount만 검사하는 테스트가 아니다.
- 검사에는 별도 읽기 전용 snapshot DataStore로 저장된 Preferences를 읽는다. 실패하는 data Flow로 결과를 재조회하지 않는다.
- fake는 순차 판별용이다. 동시성 자체를 시뮬레이션하지 않으며, 직렬화 보장은 실제 DataStore edit의 계약 및 구현을 근거로 검토했다.

### K7(f) 결정성

- 실패 DAO Flow의 collection 횟수 1을 먼저 확인해 초기 `emptyList`만 보고 통과하는 공허한 검사를 막는다.
- 같은 TestCoroutineScheduler의 `runCurrent()`로 실패가 실행될 때까지 진행한다. default uncaught handler를 캡처하고 finally에서 원복한다. runTest도 별도 store scope의 미처리 coroutine 예외를 실패로 보고한다.
- catch 제거 시 handler 캡처 또는 runTest uncaught 경로로 RED여야 한다. 고정 sleep/실시간 기다림은 없다.
- count 테스트는 반환값 0 외에 sinceMillis 전달도 검사한다. 취소 경합 테스트는 await 취소뿐 아니라 호출 뒤 정상 반환이 없었는지도 검사해 잘못된 fallback을 구별한다.

## 판별력 표 (ML1–ML8, 실측 전 예상)

> Claude 실측 결과(2026-10-12): 전 항목 예상대로 검출. 표는 `DIRECTIVE.md` §4.1, 판정은 `collab/archive/20261012-2010-T001-REVIEW.md`.

| 주입 | 대상/기대 검출 | 실측 |
|---|---|---|
| ML1 | K2는 유지하고 K1/K3/K4/K5/K6 역적용. 설정 손상 읽기/쓰기, 보호자 손상 읽기, 분리 fake 3개, Room 이력/집계, Splash, Onboarding, 보호자 추가/삭제 실패 안내 RED | Claude 대기 |
| ML2 | 설정 observe 4종에서 helper 제거 → 설정 손상 read, Splash RED | Claude 대기 |
| ML3 | 보호자 읽기 helper 제거 → 보호자 손상 테스트 RED | Claude 대기 |
| ML4 | K1 유지 + K4를 getGuardians RMW로 복원 → add 보존 `[one,two,three]` 단언이 `[three]`로 RED, remove/full도 RED | Claude 대기 |
| ML5 | helper가 모든 예외 흡수 → 설정/보호자 IllegalStateException identity 전파 테스트 RED | Claude 대기 |
| ML6 | K3 catch 제거 → 설정 손상 write, Onboarding RED | Claude 대기 |
| ML7 | recentEvents catch 제거 → uncaught 테스트 RED; count catch 제거 → 집계 테스트 RED. 각각 따로 실행 | Claude 대기 |
| ML8 | K6 add/remove catch 제거 → 각 실패 안내 테스트 RED; add 실패 문구를 정원 문구로 변경 → exact 안내 단언 RED | Claude 대기 |

정상 왕복/정원 회귀 및 과잉 흡수 방지 테스트 일부는 원래 코드에서도 GREEN인 회귀 대조군이다. 모든 신규 테스트가 ML1에서 RED라고 주장하지 않는다. ML1의 대상은 DIRECTIVE §4가 지정한 실패 경로다.

## T2 전체 자체검토

### K4 원자성·정원·반환값

- 추가/삭제 모두 단일 edit transform 안에서 `prefs[GuardiansJson]`을 파싱한다. public getGuardians fallback이 쓰기 입력에 섞이지 않는다.
- 정원 비교도 동일 transform 안에서 수행하므로 별도 read/write 사이 동시 추가의 stale snapshot 문제가 없다.
- `added`는 각 add 호출의 지역 변수다. transform마다 현재 size로 다시 대입하고, edit가 정상 완료된 뒤에만 반환한다. 호출 간 공유 상태가 아니다. transform/쓰기 실패 시 edit가 throw하여 이전 대입값을 성공으로 반환하지 않는다.
- 정원 초과 시 Preferences를 수정하지 않는다. edit 자체는 호출되지만 실제 disk write는 위 DataStore 1.1.1 동등성 분기에서 건너뛴다. fake의 writeCount 0 + 건강한 파일 byte 동일 단언을 함께 작성했다.
- JSON 자체가 손상되었을 때 `parseGuardians`가 빈 목록을 반환하고 다음 추가가 덮어쓰는 기존 경로는 그대로다. 이는 DIRECTIVE §7의 승인된 범위 밖 잔여다.

### K1 완료 Flow와 소비자

| 소비자 | IOException fallback 이후 |
|---|---|
| Home combine | 보호자 []/문자 false 값을 받아 combine 초기 방출이 가능하다. 해당 입력은 마지막 기본값으로 고정되지만 risk/session/permission/weekly 등 다른 입력은 계속 반영된다. 재구독 전 저장소 복구는 반영되지 않는다. |
| Guardian/Warning combine | []/false를 마지막 값으로 유지한다. message/picker/current-event 같은 나머지 Flow는 계속 처리한다. WhileSubscribed가 실제 중단 후 재시작되면 다시 읽을 수 있다. |
| coordinator first() | false이면 SMS 보호자 조회를 생략, 보호자 []이면 firstOrNull은 null. 기존 catch는 비-IO 예외와 취소를 위한 이중 방어로 남는다. 무방출 Flow 처리 자체는 변경하지 않는다. |
| RealCallRiskMonitor catch/first | IOException은 upstream에서 false가 되어 기존 catch까지 도달하지 않는다. flatMapLatest는 운영 임계값의 LONG 타이머를 실행하고, 상류 정상 완료 후에도 마지막 inner Flow 완료를 기다린다. 기존 catch는 비-IO 실패 방어로 남는다. first()도 false를 받는다. |

- 기본값 1회 방출 후 완료는 승인된 D1 패턴의 성질이다. 자동 retry/recovery는 추가하지 않았다.
- Room recentEvents도 실패 후 빈 목록으로 끝나고 eager stateIn에 남는다. 새 Room 수집을 자동 재개하는 동작은 이번 계약에 없다.
- K1/K3는 IOException만 처리하므로 CancellationException은 흡수하지 않는다. Room count는 명시 취소 재전파와 일반 예외 경합 ensureActive를 모두 유지한다. Room Flow catch도 ensureActive 후에만 fallback한다.

### K2 생성자 호환

- 공개 DataStore primary constructor에는 @Inject를 달지 않았다. 유일한 @Inject secondary constructor의 입력은 기존과 같은 `@ApplicationContext Context`다.
- Hilt 생성 factory는 Context provider를 계속 사용해야 하며 DataStore binding을 요구하지 않는다. `DataModule`의 @Binds 입력 타입도 그대로다.
- 기존 androidTest의 `SettingsRepositoryImpl(SuiteDataStores.context)` / `GuardianRepositoryImpl(SuiteDataStores.context)`가 같은 Context overload를 선택한다.
- 실제 kapt/AndroidTest 컴파일은 미실행이며 Claude 게이트에서 확인한다.

### 로그·정책·범위

- 새 로그 메시지는 store 이름과 실패 종류만 포함한다. 보호자 이름/번호/JSON/설정값/요청 id를 보간하지 않는다. K1/K3/K5가 정한 throwable 진단 인자는 유지한다.
- 외부 연락·SMS 발송·추가 권한·서비스·자동 복구·프로덕션 파일 삭제 없음. 기존 legacy smsAlert API만 동일하게 보호한다.
- Manifest/DI/domain interface/coordinator/monitor/Gradle 파일은 diff에 없다. Splash/Onboarding/Home/History/Debug/Warning 프로덕션도 수정하지 않았다.
- 기존 테스트 수정/삭제/단언 약화 0. 정책 검사는 정적 범위 확인이며 `/ss-policy` 자동 실행 결과를 주장하지 않는다.

### S2 / CTA 8항목

`.github/S2_REVIEW_CHECKLIST.md`를 읽고 §6의 8항목을 대조했다.

| 항목 | 정적 판정과 근거 |
|---|---|
| 1 dismiss-only | 위반 추가 0. overlay/cooldown dismiss 핸들러 변경 없음 |
| 2 safe-confirm 전용 흐름 | 위반 추가 0. confirmSafe/performSafeCtaSideEffects 호출 추가 없음 |
| 3 S2 orchestration 책임 | 위반 추가 0. 저장소 실패 안내는 보호자 편집 화면의 error/message만 갱신; S2 변수 접근 없음 |
| 4 TTL 경계 `>` | 위반 추가 0. S2 파일·상수·비교식 변경 없음 |
| 5 비통화 dismiss call-safe 금지 | 위반 추가 0. markCurrentCallConfirmedSafe/snoozeForCall 호출 추가 없음 |
| 6 통화 safe-confirm 진입 제한 | 위반 추가 0. 통화 CTA/anchor/session 진입점 변경 없음 |
| 7 α/S2 분리 | 위반 추가 0. 공유 TTL/상태/함수/테스트 클래스 도입 없음 |
| 8 UPGRADE_TRIGGERS | 위반 추가 0. 3중 참조 set 및 소유권 변경 없음 |

C1: 새 보호자 편집 오류 처리에서 S2 입력면 직접 접근 0. C2/C3: CTA가 다음 tick의 scope/escape delta를 바꾸는 새 부수효과 0. 설정 실패가 test-mode false로 가는 변경은 승인된 D2 fallback이며 기존 monitor 실패 시 운영 임계값 동작과 같고, monitor 신호 생성/epoch 코드는 수정하지 않았다.

### must-fix / recommend / info

- must-fix: 정적 자체검토에서 발견 0. 실행 검증 완료를 의미하지 않는다.
- recommend: 승인 범위 내 별도 수정 제안 0.
- info: D3에 따라 손상 파일은 계속 남고 쓰기도 계속 실패할 수 있다. K1 Flow는 재구독 전 복구값을 반영하지 않는다. JSON 손상 기존 경로, Room Flow 재시작, clearAll 실패는 범위 밖이다.
- info: 보호자 JVM 테스트 6개는 이미 있는 Robolectric 단일 SDK runner를 사용한다. 실행 시간과 클래스 로딩은 Claude 환경에서 확인해야 한다.

## K8 수량 및 정적 확인

- 신규 @Test: Settings 5 + Guardian repository 6 + Room 4 + Splash 1 + Onboarding 1 + Guardian ViewModel 3 = **20**.
- 공유 base는 @Test 0. parameterized 테스트 없음. Robolectric SDK도 33 하나이므로 예상 testcase 중복 없음.
- 예상 app **592 + 20 = 612**, risk 7, contracts 4, 전체 623. XML 실측으로 Claude가 확정한다.
- floor script 주석은 ASCII: `# Raised 2026-10-12: app 592->612 (storage failure fallback +20).`
- `git diff --check` 통과. 기존 수정 파일 8개는 원래 CRLF 형태로 저장했다. 새로운 코드/테스트 8개와 문서 2개를 포함하여 예상 변경 파일은 18개다.
- 실제 JVM/Android 빌드, 단위 테스트, lint, 검증기, schema/self-check 실행은 0이다.

## Claude 실행 요청

1. JBR / 직렬 Gradle로 신규 테스트를 먼저 실행한다. 클래스 필터(각 항목에 `--tests` 지정):

   - `com.example.seniorshield.data.SettingsRepositoryStorageFailureTest`
   - `com.example.seniorshield.data.GuardianRepositoryStorageFailureTest`
   - `com.example.seniorshield.data.RoomStorageFailureTest`
   - `com.example.seniorshield.feature.splash.SplashStorageFailureTest`
   - `com.example.seniorshield.feature.onboarding.OnboardingStorageFailureTest`
   - `com.example.seniorshield.feature.guardian.GuardianStorageFailureViewModelTest`

2. ML1–ML8 표대로 mutation을 각각 실행한다. K2 constructor seam 유지; ML4에는 K1 유지. ML7은 recent/count를 분리한다. 대상 테스트만 필터링한 실행에 전체 floor 검증기를 적용하지 않는다.
3. 최종 코드를 복원한 뒤 fresh 전체 직렬 게이트를 **필터 없이** 실행한다: `clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug`.
4. `.github/scripts/verify-unit-xml.ps1`로 app 612 / risk 7 / contracts 4 및 0 skipped 확인. domain/app/data lint 검증, schema drift 0, verifier self-check, S2 guardrail 및 정책 검사 수행. CI workflow에 있는 기존 명령/옵션을 그대로 사용한다.
5. `@Inject` secondary constructor의 Hilt factory 및 기존 androidTest Context 생성자 호출 호환을 확인한다. 결과가 RED/새 lint/범위 위반이면 S5로 처리한다. 커밋은 별도 S1이며 이 RESULT는 커밋 승인이 아니다.

## 컴파일 확신도와 남은 실측

- 생산 코드의 API 형태는 기존 코드/캐시 선언과 일치하지만 컴파일은 하지 않았다.
- 상대적으로 실측 필요도가 큰 곳: Robolectric 단일 SDK runner + 공용 JUnit base의 classloader/dispatcher 조합, runTest가 별도 Room scope의 uncaught 예외를 포착하는 ML7 결과, secondary @Inject constructor의 kapt 결과.
- raw 손상 단언의 실제 통과, scope cancel 후 파일 해제, 예상 testcase 20개 집계는 실행 전 가정이며 위 테스트/게이트가 이를 검증한다.
