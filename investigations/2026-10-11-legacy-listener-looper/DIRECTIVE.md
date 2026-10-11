# Legacy PhoneStateListener Looper 트랙 지시문 — Android 8~11(API 26~30) 감시 시작 크래시를 없앤다 (v1.0)

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol`)**
> - **등급**: [이 트랙 = **T2**]. 통화 monitor의 legacy 경로, 스레드/Looper, callbackFlow 수명을 다룬다.
> - **검토**: Codex 전체 자체검토 + Claude 적대 검토 + 독립 빌드/lint.
> - **정지·질의 = S1~S5만**: S1 커밋·push·실기 / S2 지적 ≥recommend / S3 등급 상승 / S4 제품 결정 / S5 게이트 실패.
>
> 작성: Claude. 수행: Codex(코드·테스트·IMPL_LOG). Gradle·검증기: Claude.
> 사용자 승인: "남은 작업 쭉 이어가"(2026-10-11). 커밋은 별도 S1이다.

## 0. 현재 상태와 재현 증거

- 기준: main `289ac80`(PR #26 머지 후). CI 기준선 app 587 / risk 7 / contracts 4.
- worktree `.worktrees/legacy-looper`, 브랜치 `codex/legacy-listener-main-looper`.
- **결함**: `RealCallRiskMonitor.observeCallContextLegacyInternal()`(:877)은 `observeCallContextRaw().shareIn(sharingScope = SupervisorJob + Dispatchers.Default)` 안에서 돈다. 이 경로는 `object : PhoneStateListener()`(:904)를 **Looper가 없는 Default 워커 스레드에서** 생성한다.
  - API 26~30의 기본 생성자는 `new Handler(Looper.myLooper())`를 만든다. 이 스레드에서는 `myLooper()`가 null이라 NPE가 난다.
  - 생성 지점이 `listen()`의 try(:1029) 밖이라 예외가 shareIn으로 새어 나간다. sharingScope에는 CEH가 없으므로 결과는 **앱 크래시**이고, START_STICKY 재시작 때 반복될 수 있다.
- **Robolectric 재현 (2026-10-11, 증거 `evidence/probe_output.txt`, probe 원본 `evidence/LegacyListenerLooperProbeTest.kt.txt`)**: SDK 26·28·30 모두 같은 결과였다.
  - probe1 백그라운드 스레드 생성 → `NullPointerException: Cannot read field "mQueue" because "looper" is null`
  - probe2 메인 스레드 생성 → 예외 없음
  - probe3 실제 `RealCallRiskMonitor` legacy 경로(전화 권한 허용) → 방출 0, **uncaught NPE**
- 공백 원인: JVM 단위 테스트는 android.jar 스텁(`isReturnDefaultValues`)이라 생성자가 no-op이고, 실기는 API 34/36뿐이었다.
- 이 파일에는 `Dispatchers.Main`·`flowOn`·`getMainLooper`가 들어간 적이 없다(git log -S). 반면 주석(:875 "main looper가 콜백을 직렬화", :878, :900)은 메인 Looper 전달을 전제한다. **수정 = 문서화된 설계 의도의 복원**이다.

## 1. 불변 운영 제약

1. commit·push·stage = S1. broad add 금지.
2. Gradle 직렬, JBR. Codex 샌드박스는 Gradle 불가.
3. 금지: Manifest·권한·서비스·DI·interface·coordinator·API31 경로(`observeCallContextApi31Internal`)·data/domain.
4. RED-first: 새 Robolectric 테스트는 수정 전 코드에서 RED여야 한다(Claude가 수정 hunk를 역적용해 실측).
5. 기존 테스트 삭제·약화 금지, 기존 단언 변경 0. 기존 JVM 테스트의 **setup에 seam 지정을 추가하는 것만** 허용한다.
6. Codex 문서는 worktree 안 `investigations/2026-10-11-legacy-listener-looper/`에 쓴다.

## 2. 승인된 5줄 계획

1. **수정 파일**: `RealCallRiskMonitor.kt`(legacy 리스너 생성부 + seam 1개) / 신규 Robolectric 테스트 1 / legacy 경로를 수집하는 기존 JVM 테스트의 setup(seam 지정) / CI floor 2파일.
2. **목적**: API 26~30에서 리스너를 메인 Looper에서 생성해 콜백이 메인 Looper로 직렬 전달되게 한다(설계 의도). 생성 자체가 실패해도 크래시하지 않고 중립으로 남게 한다.
3. **리스크**:
   - 정책·권한 변화 0.
   - 콜백 안의 연락처 조회(`contactChecker.checkCaller`)가 메인 스레드에서 돈다. 통화 이벤트 1건당 1회이고, 설계가 원래 전제한 동작이다.
   - Robolectric SDK 26/28/30 런타임 다운로드로 CI 시간이 늘어난다.
4. **테스트**: RED-first(Robolectric), 결함 주입, 전체 직렬 게이트, lint 신규 0, schema 0, self-check.
5. **중단**: 다음 중 하나라도 해당하면 정지한다.
   - API31 경로나 interface 변경이 필요할 때
   - 기존 provenance 계약(SEED 선점, 소유권 세대, stale gate)을 바꿔야 할 때
   - 메인 Looper가 아닌 전용 스레드 설계가 필요하다고 판단될 때(S4로 보고)

## 3. 구현 계약

- **K1 (생성 위치)**: legacy 리스너 생성을 메인 Looper에서 수행한다.
  - `@VisibleForTesting internal var legacyListenerDispatcher: () -> CoroutineDispatcher = { Dispatchers.Main.immediate }`
    - **지연 provider**로 둔다. JVM 테스트에서는 `Dispatchers.Main` 접근 자체가 실패하므로, 생성자 시점에 평가하면 안 된다.
  - `val listener = withContext(legacyListenerDispatcher()) { object : PhoneStateListener() { ... 기존 본문 그대로 ... } }`
    - 리스너 본문(onCallStateChanged 로직)은 **바이트 단위로 변경 0**이다. 감싸는 위치만 바뀐다.
  - `telephonyManager.listen(...)`과 그 try/catch, awaitClose는 기존 그대로 둔다(어느 스레드에서 호출해도 된다).
- **K1b (생성 실패)**: `withContext(...)` 생성 단계를 try/catch로 감싼다.
  - `CancellationException`이면 `releaseOwnership(myOwnerGeneration)` 후 다시 던진다.
  - 그 밖의 예외(Main dispatcher 부재 등)는 기존 `listen()` 실패 블록과 같은 형태로 처리한다: `Log.e(TAG, "PhoneStateListener creation failed: ${e.message}")` → `releaseOwnership` → `trySend(Idle SEED, 현재 epoch)` → `awaitClose { }` → `return@callbackFlow`.
- **K2 (기존 JVM 테스트)**: legacy 경로를 수집하는 기존 JVM 테스트(최소 `RealCallRiskMonitorProvenanceTest`, `RealCallRiskMonitorAnchorTest`)의 monitor 생성 `apply { }`에 `legacyListenerDispatcher = { Dispatchers.Unconfined }`를 추가한다.
  - 대상은 Codex가 grep으로 전수 확인한다. 단언과 본문은 변경 0이다.
  - Unconfined는 호출 스레드에서 즉시 생성하므로 기존 스텁 동작과 같다.
- **K3 (신규 테스트)**:
  - 신규 Robolectric 테스트 `LegacyCallMonitorLooperTest`, `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [26, 28, 30], application = Application::class)`:
    - (a) 실제 `RealCallRiskMonitor`(Robolectric application context, READ_PHONE_STATE grant, 나머지 의존성은 mockk)로 `observeCallSignals()`를 `Dispatchers.Default`에서 수집한다.
      - 메인 Looper를 idle시키며 대기한다(`shadowOf(Looper.getMainLooper()).idle()` 반복). 고정 sleep은 쓰지 않고, 조건 폴링과 timeout을 쓴다.
      - 기대: 중립 방출 수신, uncaught 예외 0(`Thread.setDefaultUncaughtExceptionHandler` 캡처 후 원복).
    - (b) ShadowTelephonyManager로 통화 상태 전이를 일으킨다(가능한 shadow API를 확인한 뒤 RINGING→OFFHOOK).
      - 기대: 콜백이 메인 Looper로 전달되어 `observeCallContext()`가 Active를 낸다(콜백 스레드가 메인임을 단언해도 좋다).
      - shadow API로 결정적 구동이 불가능하면 (b)는 생략하고, 그 근거를 IMPL_LOG에 쓴다.
  - JVM 테스트(기존 `RealCallRiskMonitorProvenanceTest` 하네스):
    - (c) `legacyListenerDispatcher = { throw IllegalStateException("main dispatcher missing") }`로 두면, 수집이 중립 SEED 1회를 받고 Flow가 실패하지 않으며 `listen()` 호출이 0이어야 한다(K1b).
- **K4 (floor)**: app MinTests 587 → 실제 신규 testcase 수만큼 올린다. Robolectric `@Config(sdk=[26,28,30])`은 SDK별로 testcase가 생기므로 **XML 실측 수 기준**으로 Claude가 확정한다. Codex는 예상 수와 근거를 적는다.

## 4. 판별력 계획 (Claude 실측)

| 결함 주입 | 기대 검출 |
|---|---|
| ML1 K1 역적용(생성을 현재 스레드에서) | (a) RED (uncaught NPE·방출 0), SDK 26/28/30 각각 |
| ML2 K1b catch 제거 | (c) RED |
| ML3 provider를 `Dispatchers.Default`로 | (a) RED |

### 4.1 실측 (2026-10-11, Robolectric 3클래스 + JVM, 45건)

| 결함 주입 | Robolectric (a)(b) × SDK 26/28/30 | JVM (c) |
|---|---|---|
| ML0 수정 상태 | 6/6 GREEN | GREEN |
| ML1 현재 스레드 생성(= 수정 전, RED-first) | **6/6 RED** | RED |
| ML2 생성 실패 catch 무력화 | GREEN | RED |
| ML3 provider = Dispatchers.Default | 6/6 RED | GREEN |

- 원본 해시 복원은 일치한다.
- 전체 게이트: 594/7/4 = floor(587 + JVM 1 + Robolectric 2 × 3), 실패·skip 0. lint 신규 0, schema 0, self-check 39/40.
- **독립 적대 검토 PASS(must-fix 0)**. API 26/28/30 바이트코드로 확인했다: 메인에서 생성하면 콜백은 메인 Looper로 전달된다.
  - **recommend 1건 → S2 정지.** 테스트 (b)는 shadow가 메인 테스트 스레드에서 동기 호출하므로 "메인 전달"을 증명하지 못한다. 메인이 아닌 다른 Looper에서 생성하도록 바꾼 변이는 살아남는다.
  - info: CI 실행마다 Robolectric SDK jar 약 330MB를 다운로드한다. 26과 28은 같은 Handler 경로라 `sdk=[26,30]`으로 줄일 수 있다.

## 5. 파일 범위

소스 1 + 신규 테스트 1 + 기존 테스트 setup 1~3 + CI 2. 이 밖은 S3 정지.

## 6. 완료 게이트

- fresh 직렬 GREEN·0 skipped·app = 새 floor
- lint 신규 0
- schema 0
- self-check
- 범위 무접촉
- 기존 `RealCallRiskMonitor*Test` 전부 GREEN
- IMPL_LOG 매핑 표

## 7. 범위 밖 (보고만)

- legacy 경로를 실제 기기(API 26~30)에서 실기 확인(THOR3는 API 36) → 단말 확보 시 별도로 진행.
- 메인 스레드 연락처 조회를 전용 스레드로 옮기는 개선 → 필요하면 별도 설계.
