# 수신기 등록 실패 트랙 지시문 — BroadcastReceiver 등록 실패가 앱 크래시·coordinator 정지로 번지지 않게 한다 (v1.0)

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol`)**
> - **등급**: [이 트랙 = **T2**]. monitor 계층의 callbackFlow·shareIn 수명과 소유권·executor 정리를 다룬다.
> - **검토**: Codex 전체 자체검토 + Claude 적대 검토 + 독립 빌드/lint.
> - **정지·질의 = S1~S5만**: S1 커밋·push·실기 / S2 지적 ≥recommend / S3 등급 상승 / S4 제품 결정 / S5 게이트 실패.
>
> 작성: Claude. 수행: Codex(코드·테스트·IMPL_LOG). Gradle·검증기: Claude.
> 사용자 승인: "진행해"(2026-10-11, Claude 권고 = API31 수신기 등록 실패 crash 경로).

## 0. 현재 상태

- 기준: main `e33fc3a`. CI 기준선 app 584 / risk 7 / contracts 4.
- worktree `.worktrees/receiver-failure`, 브랜치 `codex/receiver-registration-failure`.
- **결함 1 (crash)**: `RealCallRiskMonitor.observeCallContextApi31Internal()` callbackFlow 안의 번호 수신기 등록 `context.registerReceiver(numberReceiver, intentFilter[, RECEIVER_NOT_EXPORTED])`(:666-671)에 예외 처리가 없다.
  - 이 Flow는 `observeCallContextRaw().shareIn(sharingScope = SupervisorJob + Default)`로 수집된다. 실패하면 upstream 예외가 handler 없는 scope로 전파되어 **앱이 크래시**한다. START_STICKY로 서비스가 재시작되면 같은 등록을 다시 시도하므로 크래시가 반복될 수 있다.
  - 등록 전에 이미 `claimOwnership()`과 단일 executor 생성이 끝났다. 따라서 실패하면 소유권 해제와 `executor.shutdownNow()`도 누락된다.
  - 실패 원인 예: `SecurityException`, 프로세스당 수신기 한도 초과 `IllegalStateException`, system_server 사망 시 `RuntimeException`.
- **결함 2 (silent stop)**: `RealAppInstallRiskMonitor.observeInstallSignals()` callbackFlow의 설치 수신기 등록(:100-104)에도 예외 처리가 없다.
  - 이 Flow는 coordinator `combine`이 직접 수집한다. 실패하면 coordinator job이 끝난다.
  - **정정(2026-10-11, 적대 검토)**: coordinator scope(`SupervisorJob + ioDispatcher`, :192)에는 CoroutineExceptionHandler가 없다. 따라서 `scope.launch`가 실패하면 "조용한 정지"가 아니라 **uncaught 예외 → Android 앱 크래시**이고, START_STICKY 재시작 뒤 같은 실패가 반복되면 크래시 루프가 된다.
  - #21·#22·#24 문서의 "조용히 멈춘다" 서술도 같은 이유로 실제로는 크래시였다. 수정 자체는 그대로 유효하다.
- **같은 파일의 기존 계약(재사용)**:
  - TelephonyCallback 등록 실패(:781-795): `Log.e` → `trySend(Idle SEED, 현재 epoch)` → 이미 등록한 수신기 해제 → `awaitClose { releaseOwnership; executor.shutdownNow() }` → `return@callbackFlow`.
  - legacy `listen` 실패(:1011-1021): 같은 형태다.
- **확정 의미 (신규 제품 결정 없음)**: 등록에 실패한 monitor는 그 source만 비활성화(중립 값)하고, 나머지 감지는 계속한다. 이는 기존 TelephonyCallback 실패 계약과 같다.

## 1. 불변 운영 제약

1. commit·push·stage = S1. broad add 금지.
2. Gradle 직렬, JBR. Codex 샌드박스는 Gradle 불가.
3. 금지: Manifest·권한·서비스·DI·monitor interface·coordinator·`RemoteControlAppRegistry`·data/domain.
4. RED-first(Claude가 수정 hunk를 역적용해 실측).
5. 기존 테스트 삭제·약화 금지, 기존 단언 변경 0.
6. **Codex 문서는 worktree 안** `investigations/2026-10-11-receiver-registration-failure/`에 쓴다. Claude가 main으로 옮긴다.

## 2. 승인된 5줄 계획

1. **수정 파일**: `RealCallRiskMonitor.kt`(번호 수신기 등록 1곳) / `RealAppInstallRiskMonitor.kt`(설치 수신기 등록 1곳) / 테스트(`RealCallRiskMonitorProvenanceTest`, `RealAppInstallRiskMonitorProvenanceTest`) / CI floor 2파일.
2. **목적**: 수신기 등록 예외가 앱 크래시나 coordinator 정지로 번지지 않게 한다. 실패한 source만 중립화하고 자원(소유권, executor)은 정리한다.
3. **리스크**: 정책·권한 변화 0. 번호 수신기가 실패하면 그 수집 주기 동안 통화 감시가 비활성이다. 기존 TelephonyCallback 실패 의미와 같고, 크래시보다는 낫다. 등록하지 않은 수신기를 해제하면 `IllegalArgumentException`이 나므로, 실패 경로에서는 해제하지 않는다.
4. **테스트**: RED-first, 결함 주입, 전체 직렬 게이트, lint 신규 0, schema 0, self-check.
5. **중단**: interface·coordinator 변경 필요, 기존 provenance 계약(SEED 선점·소유권 세대·executor 핸드오프)과 충돌, 번호 없는 부분 감시(B)처럼 새 동작 설계가 필요해지는 경우(S4).

## 3. 구현 계약

- **K1 (`RealCallRiskMonitor` API31)**: 번호 수신기 등록 if/else를 `try { ... } catch (e: Exception) { ... }`로 감싼다. 실패 블록은 바로 아래 TelephonyCallback 실패 블록과 **같은 형태**다.
  ```kotlin
  } catch (e: Exception) {
      Log.e(TAG, "number receiver registration failed: ${e.message}")
      trySend(SourcedCallState(CallMonitorState.Idle, sessionTracker.userResetEpoch, ContextOrigin.SEED))
      awaitClose {
          if (!releaseOwnership(myOwnerGeneration)) {
              Log.d(TAG, "collector release ignored — owner already superseded (API31 receiver registration failure)")
          }
          executor.shutdownNow()
      }
      return@callbackFlow
  }
  ```
  - 등록하지 않았으므로 `unregisterReceiver`는 호출하지 않는다.
  - callbackFlow 빌더 안의 `CancellationException`은 이 지점(동기 등록 호출)에서 나지 않는다. 그래도 `Exception` catch가 CE를 잡을 수 있으면 CE는 재던진다. 기존 블록과 일관되는지 Codex가 판단하고 근거를 IMPL_LOG에 적는다.
- **K2 (`RealAppInstallRiskMonitor`)**: 설치 수신기 등록 if/else를 try/catch로 감싼다. 실패하면 `Log.e(TAG, "install receiver registration failed: ${e.message}")` → 기존과 같은 중립 초기값 `trySend(Produced(emptyList(), resetEpochProvider.userResetEpoch))` → `awaitClose { resetJob?.cancel(); resetJob = null }`(해제 없음) → `return@callbackFlow`.
  - 성공 경로는 바이트 단위로 변경하지 않는다.
- **K3 (테스트)**:
  - (a) **통화 API31**: `sdkIntProvider = { S }`, manual executor, `context.registerReceiver(any(), any<IntentFilter>())`가 `SecurityException`을 던지도록 둔다.
    - 기대: 신호 수집이 중립 방출 1회를 받는다(크래시·Flow 실패 없음).
    - 기대: `registerTelephonyCallback` 호출 0, `currentCallId()` null.
    - 수집자를 취소하면 manual executor가 shutdown된다.
  - (b) **통화 재구독 회복**: (a) 이후 수집자를 취소하고 `registerReceiver`를 정상(returns null)으로 되돌린 다음 재수집한다.
    - 기대: TelephonyCallback이 등록되고 정상 API31 흐름(명시 seed 중립 방출)이 돈다. 소유권이 해제·재획득됐음을 확인한다.
    - 기존 API31 테스트 패턴(`broadcastSlot`, `callbackSlot`, `manualExecutor.drain()`)을 따른다.
  - (c) **설치**: `registerReceiver`가 예외를 던지면 첫 방출은 빈 신호이고, 일정 시간 동안 Flow가 완료·실패하지 않는다. 취소해도 `unregisterReceiver` 호출은 0이다.
  - 기존 하네스와 헬퍼를 재사용한다. 실시간 장시간 대기는 금지한다.
- **K4 (floor)**: app MinTests 584 → 584 + 신규 테스트 수. 이력 주석 1줄을 추가하고 workflow는 단계 이름만 바꾼다.

## 4. 판별력 계획 (Claude 실측)

| 결함 주입 | 기대 검출 |
|---|---|
| MA1 K1 역적용 | (a) RED (중립 방출 미수신 / 크래시 경로) |
| MA2 K2 역적용 | (c) RED |
| MA3 K1 실패 블록에서 `executor.shutdownNow()` 제거 | (a) RED (shutdown 단언) |
| MA4 K1 실패 블록에서 `awaitClose` 대신 즉시 return(채널 종료) | (a) 또는 (b) 결과 기록 |

## 5. 파일 범위

소스 2 + 테스트 2 + CI 2. 이 밖은 S3 정지.

## 6. 완료 게이트

fresh 직렬 GREEN·0 skipped·app = 새 floor / lint 신규 0 / schema 0 / self-check / 범위 무접촉 / IMPL_LOG 매핑 표 / 기존 `RealCallRiskMonitor*Test`·`AppInstall*Test` 전부 GREEN.

## 4.1 실측 (2026-10-11, 3클래스 38건)

| 결함 주입 | (a) 통화 실패 | (b) 재구독 회복 | (c) 설치 실패 |
|---|---|---|---|
| MA0 수정 상태 | GREEN | **RED** | GREEN |
| MA1 통화 가드 rethrow | RED | RED | GREEN |
| MA2 설치 가드 rethrow | GREEN | RED | RED |
| MA3 executor 정리 제거 | RED | RED | GREEN |
| MA4 중립 SEED 제거 | RED | RED | GREEN |

- (b)는 수정 상태에서도 `TimeoutCancellationException`이 난다. 테스트 설계 결함이다. 운영 코드는 정상이다.
  - 원인(적대 검토 must-fix): shareIn replay=1의 실패 SEED@0이 stale gate를 지나 중립 RESET이 되고, 새 owner의 SEED@1도 중립 RESET이다. 두 값이 같아 `distinctUntilChanged`가 @1을 버리므로 `awaitEmissionWhere { epoch == 1L }`가 시간 초과로 끝난다.
- 원본 해시 복원 일치. **S2·S5 정지 → 사용자 결정 대기.**
- **사용자 결정 "권장대로 진행해"(2026-10-11)**: (b)는 수정안 A로 고쳤다. drain 전에 replay @0을 소비하고, drain 뒤에는 `assertNoFurtherEmission`과 `currentCallId`·owner 단언으로 회복을 증명한다. 정리 대기는 7초에서 15초로 늘렸다.

**재실측 (수정 후, 3클래스 38건)**:

| 결함 주입 | (a) | (b) | (c) |
|---|---|---|---|
| MA0 수정 상태 | GREEN | **GREEN** | GREEN |
| MA1 통화 가드 rethrow | RED | RED | GREEN |
| MA2 설치 가드 rethrow | GREEN | GREEN | RED |
| MA3 executor 정리 제거 | RED | RED | GREEN |
| MA4 중립 SEED 제거 | RED | RED | GREEN |

- 각 결함은 해당 테스트만 RED로 만들고, 원본 해시 복원은 일치한다.

## 7. 범위 밖 (보고만)

- **[고위험 후보] API 26~30 legacy 경로 크래시**:
  - 현상(정적 분석, 미검증): `observeCallContextLegacyInternal()`(:877)이 `sharingScope`(Dispatchers.Default, Looper 없음)에서 `object : PhoneStateListener()`(:904)를 생성한다. API 26~30의 기본 생성자는 `Looper.myLooper()`로 Handler를 만들므로 NPE·RuntimeException이 난다. 이 지점은 try(:1029) 밖이라 shareIn 크래시 경로가 된다.
  - 검증 공백: JVM 테스트는 android.jar 스텁이라 생성자가 실행되지 않고, 실기는 API 34/36뿐이다.
  - 확인 방법: Robolectric `@Config(sdk = [30])` 또는 API 30 에뮬레이터. 별도 트랙으로 처리한다.

- 번호 수신기만 실패했을 때 TelephonyCallback만으로 부분 감시를 이어 가는 B안 → 새 동작 설계이므로 별도 결정.
- `DebugViewModel` smsMenu `stateIn` catch 부재 등 UI 계층 백로그.
