# Legacy PhoneStateListener Looper — T001 구현 로그

- 기준: `289ac80`, `codex/legacy-listener-main-looper`.
- 정본: main 작업 디렉터리의 `investigations/2026-10-11-legacy-listener-looper/DIRECTIVE.md` v1.0 전체를 읽음.
- 권한: `20261011-2002-T001-TASK`, T2 승인 범위. 이번 라운드는 구현 및 자체검토 후 RESULT 인계까지다.
- 상태: **구현 작성 및 정적 자체검토 완료, 실행 검증 대기**. Gradle/컴파일/테스트/adb/git 쓰기 실행 0. GREEN·RED 실측을 주장하지 않는다.
- 시작 시 worktree clean, HEAD와 브랜치를 read-only git으로 확인했다.

## 승인된 5줄 계획 재확인

1. 파일: monitor 1, legacy JVM setup 2, 신규 Robolectric 1, CI floor 2, 이 로그와 RESULT.
2. 목적: legacy listener 생성만 Main.immediate로 옮기고 생성 실패는 소유권 해제 후 중립 SEED로 유지한다.
3. 리스크: main callback의 동기 연락처 조회, 취소/세대 경계, Robolectric 전역 예외 handler. 정책/권한 변경 0.
4. 검증: Codex는 보존/범위 정적 확인, Claude는 SDK별 RED·ML1–ML3·전체 직렬 GREEN·lint/schema/self-check.
5. 중단: API31/interface/금지 파일 변경 필요, provenance 계약 변경 필요, 별도 스레드 설계 필요 또는 S1–S5 발생.

## Grounding 및 진행 기록

- `observeCallContextLegacyInternal()` 전체, `observeCallContextRaw`, `sharingScope`, 기존 SDK/executor seam 및 owner CAS, SEED/stale 게이트를 직접 읽었다.
- 증거 `probe_output.txt`: SDK 26/28/30 모두 probe1 Default 생성 NPE, probe2 main 생성 성공, probe3 실제 monitor 방출 0 및 uncaught NPE. **Claude가 제공한 기존 증거이며 이번 라운드 실행 결과가 아니다.**
- `LegacyListenerLooperProbeTest.kt.txt` probe3를 읽고 실제 Application/권한 grant/Default 수집/uncaught handler 캡처 구조를 사용했다.
- 기존 Robolectric `CallEndHelperPermissionSafetyTest`, `RiskNotificationPermissionSafetyTest`, `OverlayPermissionSafetyTest`의 Config/setup을 읽었다. `Application::class`, 실제 권한 grant, PAUSED main Looper 패턴을 따랐다.
- `CallerContactChecker`를 읽어 RINGING 시 동기 ContentResolver 조회와 실패 시 존재 여부 재조회 가능성을 확인했다.
- 테스트를 먼저 작성하고 production wrapper를 반영했다. 사용자 지시에 따라 RED 실행은 Claude에게 넘겼다.
- 적용 스킬: using-superpowers, systematic-debugging, executing-plans, test-driven-development, verification-before-completion. 승인된 DIRECTIVE를 실행하므로 별도 설계/승인 단계를 만들지 않았다. 쓰기 제한에 따라 작업 기록은 이 IMPL_LOG만 사용한다. 별도 commit/skill 작업 디렉터리/테스트 실행 대신 TASK의 전달 절차를 따른다. 최종 독립검토는 명시된 Claude 역할로 인계한다.

## 변경 목록 및 K 매핑

| 파일 | 매핑 | 변경 |
|---|---|---|
| `app/src/main/java/com/example/seniorshield/monitoring/call/RealCallRiskMonitor.kt` | K1/K1b | CoroutineDispatcher/withContext import, 지연 dispatcher provider, 기존 object 생성만 withContext+try/catch로 감쌈 |
| `app/src/test/java/com/example/seniorshield/monitoring/call/RealCallRiskMonitorProvenanceTest.kt` | K2/K3(c) | 기존 setup Unconfined 1줄, 생성 실패 JVM 테스트 1개 |
| `app/src/test/java/com/example/seniorshield/monitoring/call/RealCallRiskMonitorAnchorTest.kt` | K2 | 기존 setup Unconfined 1줄 |
| `app/src/test/java/com/example/seniorshield/monitoring/call/LegacyCallMonitorLooperTest.kt` | K3(a)(b) | SDK 26/28/30, 실제 listener 생성 및 main-thread shadow 전이 2개 테스트 |
| `.github/scripts/verify-unit-xml.ps1` | K4 | app floor 594, ASCII 이력 주석 |
| `.github/workflows/verify.yml` | K4 | 표시 floor 594/7/4 |
| 이 로그 및 `collab/to-claude/*-T001-RESULT.md` | 인계 | 구현/자체검토/실행 요청/메타 |

### K2 전수 검색 근거

`rg -n 'RealCallRiskMonitor\(' app/src/test`로 기존 생성 지점 3곳을 찾고, `rg -n 'RealCallRiskMonitor|observeCallContextLegacyInternal' app/src/test app/src/androidTest`로 누락을 확인했다.

| 기존 파일 | 수집 여부 및 처리 |
|---|---|
| RealCallRiskMonitorProvenanceTest | `startCollector`, 직접 `observeCallSignals`/`observeCallContext` 수집. 기본 SDK stub의 legacy 경로를 사용하므로 apply에 Unconfined 추가. API31 테스트는 기존 sdkIntProvider override 유지 |
| RealCallRiskMonitorAnchorTest | `seedIdle`에서 실제 signals 수집, SDK R 지정. apply에 Unconfined 추가 |
| RealCallRiskMonitorTest | 실물 생성 후 시간 helper만 호출. Flow 수집 없음, lazy sharedCallContext 및 지연 provider 미평가. **변경하지 않음** |
| CallMonitorStateFlowTest | 실물 생성 없이 유사 Flow 구조를 재현하는 테스트. **변경하지 않음** |

기존 단언/테스트 본문/하네스 변경 0. Provenance는 신규 메서드와 setup 1줄을 제거하면 HEAD와 동일, Anchor는 setup 1줄을 제거하면 HEAD와 동일함을 정적 비교했다.

### K3 테스트와 shadow API 확인

- (a) `backgroundCollectionRegistersLegacyListenerAndEmitsNeutralSeed`: 실제 Application에서 READ_PHONE_STATE grant, 실제 framework TelephonyManager와 listener, Default signals collector. Main Looper를 idle하며 조건+5초 timeout으로 대기한다. 중립 SEED epoch 0, uncaught 0, collector active, **실제 등록 listener와 LISTEN_CALL_STATE flags**를 단언한다. K1b fallback의 중립 방출만으로는 통과할 수 없다.
- (b) `mainThreadRingingThenOffhookEmitsActiveContext`: Default context collector에 초기 Idle을 받고, 메인 테스트 스레드에서 shadow RINGING→OFFHOOK. Active OFFHOOK의 번호/미확인 분류/currentCallId와 연락처 조회 시 main Looper를 단언한다.
- (c) ``legacy listener creation failure stays neutral without registering``: provider가 IllegalStateException을 던짐. 기존 Channel 하네스에서 중립 1회/epoch 1/추가 방출 없음/collector active/실패 owner의 늦은 write 거부/callId null/anchor·버퍼 무변/listen 0을 확인한다. 기존 startCollector는 listener 캡처를 기다리므로 이 실패 테스트에서는 기존 직접 launch 패턴을 사용했다.
- 버전은 `gradle/libs.versions.toml`의 4.16.1을 확인했다. 실제 API 근거: [Robolectric 4.16.1 ShadowTelephonyManager](https://github.com/robolectric/robolectric/blob/robolectric-4.16.1/shadows/framework/src/main/java/org/robolectric/shadows/ShadowTelephonyManager.java), `listen` 279–287, `getListener/getEventFlags` 297–308, `setCallState` 350–370, `initListener` 887–896.
- **shadow 한계**: `setCallState`는 listener 메서드를 호출 스레드에서 직접 실행하며 Binder/Handler 전달을 모사하지 않는다. (b)의 main 단언은 테스트가 구동한 main 전이를 확인하며 실제 Android의 Looper 전달을 독립 증명하지 않는다. `listen`의 초기 콜백도 shadow에서는 Default 등록 스레드에서 동기 실행된다. 실제 생성자를 실행하는 (a)가 constructor Looper 회귀를 담당한다.
- shadow는 초기 콜백 **후** 등록 map에 추가한다. (b)는 중립 수신만으로 등록 완료를 가정하지 않고 RINGING 관측까지 조건 폴링하며 `setCallState`를 재시도한다. 이후 OFFHOOK는 1회만 구동한다.
- 공유 결과는 CopyOnWriteArrayList/AtomicReference로 기록한다. 고정 Thread.sleep 및 CountDownLatch로 main을 막는 대기는 없다. 조건 폴링 간격은 suspend delay(10), timeout은 5초다.
- `@After`에서 collector와 테스트 monitor의 private sharingScope Job을 취소하고 종료까지 main을 idle한다. 테스트 전용 reflection으로 5초 WhileSubscribed 지연 및 process-scope 잔류를 제거했다. 생산 cleanup seam은 추가하지 않았다. 기존 전역 uncaught handler는 finally에서 복원한다.

### K4 계산

`587 + 신규 JVM 1 + Robolectric 메서드 2 × SDK 3 = app 594`.

domain:risk 7 / domain:contracts 4는 유지하여 예상 총합 605. **XML 실측 전 예상 floor**이며, Claude가 SDK별 6 testcase와 JVM 1 증가를 XML로 확정해야 한다. 두 CI 파일의 app 표시는 594로 일치한다.

## 판별력 표 — Claude 실행 대기

| 주입 | 대상 | 예상 결과 및 판별 근거 | 이번 라운드 실측 |
|---|---|---|---|
| ML1 수정 전 legacy 생성 구간 복원 | K3(a), SDK 26/28/30 | probe3와 같은 uncaught NPE·방출 0으로 RED. K1만 제거하고 K1b를 남긴 변형은 catch 뒤 중립 방출이 가능하므로 등록 단언으로 RED | 미실행; 원본 probe3 3 SDK NPE 증거만 읽음 |
| ML2 생성 try/catch 제거, 지연 seam 유지 | K3(c) | provider 예외가 shareIn upstream으로 빠져 중립 수신 timeout/uncaught로 RED | 미실행 |
| ML3 provider 기본값 Dispatchers.Default | K3(a), SDK 26/28/30 | 생성 NPE가 K1b에서 중립으로 바뀌어도 listener/flags 등록 단언이 RED | 미실행 |

K3(c)는 **수정 전 원본에 seam이 없어 컴파일 기준 RED**다. 이를 ML2의 runtime RED와 구별해야 한다. 새 (a)의 수정 전 RED 역시 아직 실측하지 않았으며 probe3 근거로 예상한다. 원본으로 전체를 되돌리면 K2/K3(c)의 seam 참조가 컴파일을 막으므로, ML1은 seam 선언을 남기고 legacy 생성 구간만 원본으로 되돌려 (a) 필터로 실행한다.

## T2 전체 자체검토

### must-fix / recommend / info

- must-fix: 정적 검토에서 식별한 미해결 항목 없음.
- recommend: 승인 범위 내 미해결 항목 없음. 실행 증거는 아래 인계 게이트에 남는다.
- info: 메인 연락처 동기 조회와 framework 전달의 실기 확인은 DIRECTIVE가 이미 명시한 잔여 한계다. 이번 라운드에서 전용 스레드 변경이 필요하다고 판단하지 않았다.

### 스레드·소유권·취소

1. `sharingScope`는 여전히 SupervisorJob+Default다. Main.immediate provider는 생성자에서 평가하지 않고 legacy Flow 수집 중 try 내부에서만 평가한다. `withContext`는 listener 생성만 main으로 보내고 이후 등록/해제는 기존 coroutine 문맥에 남긴다.
2. 실제 Android의 listener는 main Looper에 결속되어 callback 지역 상태를 직렬 처리한다. RINGING의 `contactChecker.checkCaller` 역시 main에서 동기 실행되어 UI 지연 가능성이 있다. 정상 조회 1회, 예외 시 fallback 재조회가 가능하다. 본래 main 직렬화를 전제한 설계 복원이며, 지연/ANR 실측은 하지 않았다.
3. claimOwnership은 dispatch 전에 그대로 실행된다. main 대기 중 reset이 일어나도 첫 콜백이 실행 시점 epoch를 캡처하고 initialClaimed CAS로 SEED를 선점한다. object 생성 자체에는 상태 신호/연락처 조회/외부 연락이 없다. 소유 세대 gate·metadata epoch+회차 결속·callId CAS·하류 stale gate는 본문 변경 0이다.
4. 생성 dispatch 전/중/복귀 시 CancellationException은 ownership을 해제하고 그대로 재전파한다. main에서 이미 객체를 만들었어도 아직 listen 전이면 등록 자원은 없다. withContext 결과 복귀 직후 취소가 와서 등록까지 진행한 경우에도 기존 awaitClose가 해제한다. CancellationException을 중립 실패로 삼키지 않는다.
5. 일반 생성 예외는 release→현재 epoch Idle SEED→awaitClose{}→return이다. 실제 listener 등록은 하지 않는다. release의 세대 CAS는 이미 교체된 owner를 덮어쓰지 않는다. 추가 retry 정책은 넣지 않았으며 권한 재구독/상류 재시작은 기존 구조를 따른다.
6. awaitClose의 `listen(LISTEN_NONE)`는 main으로 옮기지 않았다. 기존 callbackFlow 정리 문맥(통상 Default, main 보장 없음)에서 owner를 먼저 해제한 후 등록을 취소한다. main queue에 남은 늦은 callback은 기존 owner gate가 거부한다. API31 구간은 동일하다.
7. 생성 실패 및 취소 경로에서 새로운 자동 외부 연락/권한/서비스/DI/Navigation 동작 없음. 본인 보호 정책 변경 0.

### Robolectric 안정성 및 검증 한계

- Application::class로 앱 서비스 초기화를 피하고 PAUSED main을 명시한다. SDK 분기 및 legacy dispatcher는 실제 기본 provider를 사용하며 테스트에서 Main/Unconfined로 덮어쓰지 않는다.
- 전역 uncaught handler를 사용하는 클래스이므로 동일 JVM 내 병렬 메서드 실행을 가정하지 않는다. 기존 직렬 Gradle 게이트로 실행 요청한다. unrelated worker 예외도 실패로 드러나게 캡처한다.
- 새 class는 Job 종료를 기다려 shadow teardown 뒤 지연 unregister가 실행되는 것을 막는다. private `sharingScope` 필드 reflection 의존은 테스트에 한정된다.
- Robolectric+coroutines Main 초기화, 세 SDK sandbox에서 MockK/PhoneStateListener 로딩, teardown timeout 안정성은 컴파일/실행 전이라 확인하지 못했다. SDK 런타임 다운로드 비용도 CI에서 확인해야 한다.

### S2 / CTA 8항목

`.github/S2_REVIEW_CHECKLIST.md` §2/§3/§6을 읽고 diff 기준으로 검토했다. N/A로 생략하지 않았다.

| 항목 | 정적 판정/근거 |
|---|---|
| 1. 일단 닫기 dismiss-only | 유지. overlay/cooldown dismiss 및 호출 그래프 변경 0 |
| 2. safe-confirm 전용 흐름 | 유지. Home/Warning/팝업 전용 진입점 변경 0 |
| 3. REC-REFIRE는 S2 orchestration | 유지. coordinator/S2 입력·snapshot·wake-up·CTA 참조 변경 0 |
| 4. TTL 경계 `>` | 유지. TTL 상수/조건 파일 변경 0 |
| 5. 비통화 dismiss call-safe 효과 금지 | 유지. 새 CTA 부수효과 호출 0 |
| 6. 통화 safe-confirm만 통화 안전 효과 | 유지. markCurrentCallConfirmedSafe/snoozeForCall 호출부 변경 0 |
| 7. α/S2 상수·상태·함수·테스트 분리 | 유지. 두 layer와 해당 테스트 파일 변경 0 |
| 8. UPGRADE_TRIGGERS follow-up 범위 | 유지. 세 set 정의 및 통합 변경 0 |

C1/C2/C3: CTA의 S2 직접 접근이나 새로운 부수효과가 없다. 정상 legacy의 callback 신호 분류·epoch·phase·scope/escape 집합 로직은 동일하다. 크래시 대신 정상 수집하거나 생성 실패 시 중립 SEED를 내는 것이 의도된 차이이며, CTA로 scope 또는 escape delta를 조작하는 변경은 없다. guardrail 실행은 Claude에게 요청한다.

## 수행한 정적 확인

- `git diff --check`: 통과.
- 수정 전 작업 파일에서 추출한 listener 본문 6,165바이트를 wrapper 수정 후 그대로 포함함을 확인. LF 정규화 후 callback 본문 SHA256: `ec7d0cdf0069b9ad535976f32eb4c9bb06263d82ac05587a6e300d9a215bb81e`, HEAD와 동일.
- API31 함수 구간이 HEAD와 동일. legacy `listen(LISTEN_CALL_STATE)` try/catch부터 파일 끝까지 동일: 등록·awaitClose·나머지 helper 변경 0.
- 기존 Provenance 본문은 신규 메서드/주입 1줄을 제외하면 동일, Anchor는 주입 1줄을 제외하면 동일.
- `git status --porcelain --untracked-files=all -- data/schemas`: 출력 없음. 이는 **현재 파일 drift 0** 확인이며 빌드 후 schema 게이트 통과 주장이 아니다.
- 기존 Windows CRLF를 유지했다. 새 CI 이력 주석은 ASCII다. 새 Robolectric @Test 수는 2개다.

## Claude 실행 요청

1. 새 class 우선: `:app:testDebugUnitTest --tests 'com.example.seniorshield.monitoring.call.LegacyCallMonitorLooperTest'` — 2메서드×3 SDK=6, skipped 0 확인.
2. JVM 실패 case 필터: `--tests '*RealCallRiskMonitorProvenanceTest.legacy listener creation failure stays neutral without registering'`.
3. 기존+신규 monitor 회귀: `--tests 'com.example.seniorshield.monitoring.call.RealCallRiskMonitor*Test' --tests 'com.example.seniorshield.monitoring.call.LegacyCallMonitorLooperTest'`.
4. ML1/ML2/ML3를 각각 격리 실행하고 주입 복원 후 전체 fresh gate. (a) 메서드 필터는 `--tests '*LegacyCallMonitorLooperTest.backgroundCollectionRegistersLegacyListenerAndEmitsNeutralSeed*'`; SDK 접미사를 포함하도록 wildcard를 사용한다. 원본 RED와 K1b 유지 변형 RED의 실패 원인을 구분 기록한다.
5. JBR·직렬 `--no-daemon --no-parallel --max-workers=1 --console=plain`, `clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug`.
6. fresh XML로 app **594**, risk 7, contracts 4, failures/errors/skipped 0을 확정. `.github/scripts/verify-unit-xml.ps1`, domain/app/data lint 검증기, schema drift, `investigations/2026-09-06-ci-baseline-t1/probe-verifiers.ps1` self-check 및 S2 guardrails/정책 점검을 수행한다. 필터 실행 XML로 전체 floor 판정을 하지 않는다.
7. API26–30 실제 단말 callback 스레드/연락처 지연 확인은 별도 실기 게이트로 유지한다. 이 RESULT는 commit/push/실기 승인을 의미하지 않는다.


## T001 REVIEW 대응

- 근거: `20261011-2045-T001-REVIEW`, 사용자 결정 "권장안으로 진행해". B안(정직한 명명·기록)과 Robolectric SDK [26,30] 축소를 적용했다. 이 절은 앞의 3 SDK/594 예상값 및 main 단언 설명을 대체하며, 기존 로그 본문은 이력으로 그대로 보존한다.
- 쓰기 범위: 신규 Robolectric 테스트 파일, production seam KDoc 1줄, CI floor 2파일, 이 로그 append 및 RESULT2. production 실행 코드 변경 0, JVM 기존 하네스/테스트 수정 0, git 쓰기/Gradle/컴파일/테스트/adb 실행 0.

### 변경 및 검증 범위의 정정

- `@Config`는 SDK [26,30]. 26은 Handler 경로, 30은 HandlerExecutor 경로를 대표한다. 28은 26과 동일 경로이므로 사용자 결정에 따라 제외했다.
- (b)는 `ringingThenOffhookDrivenOnMainEmitsActiveContext`로 이름을 바꿨다. `callerLooper` 필드·캡처·연락처 실행 main 단언을 제거하고 나머지 단언은 보존했다. 테스트 스레드가 main임을 확인하는 기존 assertSame은 구동 조건으로 남겼다.
- 클래스 KDoc 및 (b) 주석에 shadow의 동기 직접 호출은 **monitor 상태 전이만 검증**하며 실제 기기의 main-Looper 전달은 여기서 증명하지 않는다고 명시했다.
- seam KDoc는 listener 생성에 필요한 것은 Looper 스레드이고, callback을 main에서 직렬화하기 위해 main Looper를 선택했다는 뜻으로 교정했다. 지연 provider와 실행 로직은 그대로다.
- (b)의 실제 framework 전달 경로 검증을 생략한 사유는 **"불가능"이 아니라 "선택"**이다. hidden `PhoneStateListener.callback` 리플렉션 구동은 가능하다. 다만 SDK별 내부 구조에 의존하고 SDK 30에서는 native `Binder.clearCallingIdentity`를 거쳐 불확실하므로 사용자 결정 B안에 따라 채택하지 않았다. 상태 전이 테스트 (b) 자체는 유지했다.
- 메인 전달 근거는 이번 REVIEW에 보고된 **독립 적대 검토의 framework 바이트코드 확인**이다: SDK 26/28은 `PhoneStateListener$1` Handler, SDK 30은 `HandlerExecutor`. main에서 listener를 생성하면 해당 main Looper로 callback이 전달된다는 검토 결과다. Codex가 이번 라운드에서 바이트코드를 재검증하거나 실기 실행한 결과로 표기하지 않는다.
- 잔여 판별력 한계: **"메인이 아닌 다른 Looper에서 생성"하는 변이는 테스트로 검출되지 않는다.** (a)는 Looper 없는 worker 생성 결함을, (b)는 shadow 구동 상태 전이를 검출한다. main affinity의 별도 동적 증명은 추가하지 않았다.

### floor 및 실행 결과 구분

- 새 floor: `587 + JVM 1 + Robolectric 2메서드 × 2 SDK = 592`. risk 7/contracts 4 유지, 예상 총합 603. CI script 이력은 ASCII `# Raised 2026-10-11: app 587->592 (legacy listener main looper +5).`, workflow는 592/7/4로 일치시켰다.
- REVIEW의 Claude 실측 전달 내용: ML0 45/45 GREEN, ML1 Robolectric 6/6 RED(26/28/30) 및 (c) RED, ML2 (c) RED, ML3 6/6 RED, 원본 해시 복원, 전체 게이트 594/7/4, lint 신규 0, schema 0, self-check **39/40**. 독립 적대 검토는 must-fix 0이고 recommend는 본 B안으로 대응했다.
- 위 결과는 **SDK 축소 전 Claude 측 결과**다. 특히 self-check 39/40을 전체 통과로 표기하지 않는다. 원인 확인/처리는 Claude에게 남으며 이번 승인 범위에서 검증기 로직을 수정하지 않았다.
- 이번 Codex 확인은 정적 검사에 한정한다: source는 KDoc 1줄만 변경, 제거 대상 1개를 제외한 모든 단언 동일, 테스트 메서드 2개 유지, SDK [26,30], floor 양쪽 592, 로그 기존 바이트 prefix 보존, 이번 쓰기 범위 밖 파일 동일.
- Claude 후속 요청: 새 class 4 testcase와 JVM 추가 1건, 전체 fresh XML 592/7/4 및 skipped 0 확인. (b) 메서드 필터는 `*LegacyCallMonitorLooperTest.ringingThenOffhookDrivenOnMainEmitsActiveContext*`. 기존 RED 결과와 이번 축소 후 실행 결과를 구분 기록하고 self-check 39/40의 잔여 1건을 별도로 확인한다. Codex는 실행하지 않는다.
