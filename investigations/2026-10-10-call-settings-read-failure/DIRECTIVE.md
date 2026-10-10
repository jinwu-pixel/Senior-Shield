# 통화 감시 설정 읽기 실패 트랙 지시문 — testMode 설정 읽기 예외가 coordinator를 멈추지 않게 한다 (v1.0)

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol` 참조)**
> - **등급**: T0 문서·보고 / T1 격리 코드 / **T2 고위험** — [이 트랙 = **T2**]
>   근거: monitor 계층(통화 감시 Flow)·취소 의미론. 애매하면 위로.
> - **검토**: T2 = Codex 전체 자체검토 + Claude 적대 검토 + 독립 빌드/lint.
> - **정지·질의 = S1~S5만** (그 밖은 자동 전진):
>   S1 되돌릴 수 없는 게이트(커밋·push·실기·Manifest/권한/DI 실제 변경) /
>   S2 적대검증 통과 지적 ≥recommend / S3 등급 상승 /
>   S4 제품·정책 결정 필요 / S5 완료 게이트 실패(RED·lint 신규·범위 위반).
>
> 작성: Claude(계획·검토). 수행: Codex(코드·테스트·IMPL_LOG). Gradle·검증기 실행: Claude.
> 사용자 승인: "진행해"(2026-10-10, Claude 권고 = RealCallRiskMonitor testMode 설정 읽기 무방비 수정).

## 0. 현재 상태

- 기준: main `0414984`(PR #21 머지 후). CI 기준선 = app 577 / risk 7 / contracts 4, 실패·skip 0.
- 작업 worktree: `.worktrees/call-settings`, 브랜치 `codex/call-settings-read-failure`.
- **결함** (PR #21 적대 검토가 발견, `investigations/2026-10-10-guardian-read-failure/DIRECTIVE.md` §7 형제 결함 2):
  `RealCallRiskMonitor.observeCallSignals()` 안에서 testMode 설정을 읽는 두 곳에 예외 처리가 없다.
  - **OFFHOOK(수신)** `:397-425` `emitAll(settingsRepository.observeTestModeEnabled().distinctUntilChanged().flatMapLatest { ... LONG 타이머 ... })`
  - **IDLE(수신 종료)** `:483` `val testMode = settingsRepository.observeTestModeEnabled().first()`
  - 운영 경로: `SettingsRepositoryImpl` → `senior_shield_settings` DataStore. 파일 읽기 `IOException`(손상 처리기 미설정 → `CorruptionException` 포함)이 그대로 전파된다.
  - 전파: 내부 flow → `flatMapLatest` → `observeCallSignals()` → coordinator `combine` → `merge(...).collect` → coordinator job 종료.
    **설정 파일이 손상되면 수신 통화가 연결되거나 끝나는 순간 위험 감지·팝업·알림이 서비스 재시작 전까지 조용히 멈춘다.** 같은 파일에 `SmsMenuEnabled`도 있어 PR #17/#21의 팝업 catch만으로는 막지 못한다.
  - monitor 계층의 저장소 무방비 읽기는 이 2곳이 전부다(grep 확인).
- testMode = 디버그용 설정(제품 기본 OFF). 의미는 LONG 통화 임계값 선택뿐이다(`LONG_CALL_THRESHOLD_MS` 180초 / `TEST_LONG_CALL_THRESHOLD_MS` 10초).
- **확정 의미(신규 제품 결정 없음)**: 읽기 실패 시 testMode = `false`(제품 기본값 = 운영 임계값). 감시는 계속되고 임계값만 운영값으로 고정된다.

## 1. 불변 운영 제약

1. commit·push·stage = S1(사용자 명시 승인 전 금지). broad add 금지.
2. Gradle 직렬 `--no-daemon --no-parallel --max-workers=1`, JAVA_HOME=JBR. Codex 샌드박스는 Gradle 불가 → Claude 실행.
3. 금지 범위: Manifest·permission·service·DI 모듈·monitor interface(`CallRiskMonitor`)·NavGraph·coordinator·data/domain 모듈·`CallSignalMapper`.
4. RED-first: 신규 실패 사례는 수정 전 코드에서 RED여야 한다(Claude가 수정 hunk 역적용으로 실측).
5. 기존 테스트 삭제·약화 금지. 기존 단언 변경 0.
6. 산출 문서는 이 트랙 폴더에만. **Codex 샌드박스 쓰기 루트 = worktree** → 문서는 worktree 안 `investigations/2026-10-10-call-settings-read-failure/`에 쓰고 Claude가 main 작업트리로 옮긴다.

## 2. 승인된 5줄 계획

1. **수정 파일**: `RealCallRiskMonitor.kt`(설정 읽기 2곳) / `RealCallRiskMonitorProvenanceTest.kt`(사례 추가) / `.github/scripts/verify-unit-xml.ps1`·`.github/workflows/verify.yml`(floor 래칫).
2. **목적**: testMode 설정 읽기의 일반 예외·빈 Flow가 통화 감시 Flow를 끝내지 못하게 한다. 실패 시 운영 임계값으로 계속 감시한다.
3. **리스크**: 정책 — 권한·서비스·자동 연락 변화 0. 기술 — catch가 취소를 삼키면 reset/collector 종료 뒤 stale 처리가 이어질 수 있음 → 취소 재던짐·`ensureActive()`로 막는다. OFFHOOK의 실패 fallback이 진행 중 타이머를 재시작할 수 있음(§3 K1 주의).
4. **테스트(완료 게이트)**: 신규 사례 RED-first 실측, 결함 주입 검출, 전체 직렬 게이트 GREEN·0 skipped·floor 일치, lint 신규 0, schema drift 0, self-check.
5. **중단 조건**: interface·DI·coordinator·mapper 변경 필요 / 기존 단언 변경 필요 / fallback을 `false` 외 값으로 해야 함(S4) / 기존 provenance 계약(epoch 재검증·SEED 중립·stale 게이트)과 충돌.

## 3. 구현 계약

- **K1 (OFFHOOK 스트림)**: `settingsRepository.observeTestModeEnabled()` 바로 뒤, `distinctUntilChanged()` **앞**에 `.catch`를 둔다.
  ```kotlin
  settingsRepository.observeTestModeEnabled()
      .catch { e ->
          currentCoroutineContext().ensureActive()
          Log.w(TAG, "test mode setting read failed — production long-call threshold used", e)
          emit(false)
      }
      .distinctUntilChanged()
      .flatMapLatest { ... 기존 그대로 ... }
  ```
  - `Flow.catch`는 하류 예외와 수집자 취소 원인은 다시 던진다. 그래도 취소 직후 들어온 non-CE 예외에 대비해 `ensureActive()`를 첫 줄에 둔다.
  - 이미 `false`였다면 `distinctUntilChanged`가 fallback을 걸러 타이머를 재시작하지 않는다. `true` 수신 후 상류가 실패하면 운영 임계값으로 타이머가 재시작된다. testMode가 ON인 디버그 상황에서만 생기는 edge이며 감시 지속이 우선이라 수용한다(KDoc/주석 1줄로 명시).
  - 일회성 경계(`recordUnknownCall`·immediate 신호)와 LONG 타이머 본문은 변경 0.
- **K2 (IDLE 1회 읽기)**: `firstGuardian`과 같은 형태로 감싼다.
  ```kotlin
  val testMode = try {
      settingsRepository.observeTestModeEnabled().first()
  } catch (e: CancellationException) {
      throw e
  } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      Log.w(TAG, "test mode setting read failed — production long-call threshold used", e)
      false
  }
  if (sessionTracker.userResetEpoch != producedAtEpoch) return@flow   // 기존 줄 유지
  ```
  - 기존 epoch 재검증 줄과 그 뒤 mapper·FINAL·RESET 방출은 변경 0.
- **K3 (테스트, `RealCallRiskMonitorProvenanceTest`)**: 기존 하네스(legacy listener 구동, `testModeEnabled` MutableStateFlow, `every { settingsRepository.observeTestModeEnabled() } returns flow { ... }` 패턴 :270)를 그대로 쓴다.
  - (a) OFFHOOK: testMode 스트림이 수집 즉시 `java.io.IOException` → immediate 신호(UNKNOWN_CALLER) 방출, 이후 IDLE의 RESET 방출, **다음 통화(RINGING→OFFHOOK)의 신호도 방출**(= 감시 Flow 생존).
  - (b) IDLE: testMode 읽기 `IOException` → `mapper.map(ctx, LONG_CALL_THRESHOLD_MS)` 호출, RESET 방출, 다음 통화 신호 방출.
  - (c) IDLE: testMode Flow 미방출(`NoSuchElementException`) → (b)와 같은 결과.
  - (d) IDLE: testMode 읽기 훅 안에서 수집자 job을 **동기 cancel**한 직후 suspend 없이 `IOException` → `mapper.map` 호출 0, 추가 방출 0, anchor 등 기존 사이드이펙트 단언은 기존 패턴을 따른다.
  - OFFHOOK와 IDLE에서 같은 settings mock을 쓰는 경우, 호출 순번(`AtomicInteger`)으로 OFFHOOK 스트림과 IDLE 읽기를 구분하는 기존 :270 패턴을 따른다.
  - 실시간 delay를 기다리는 단언(3분/10초 타이머 발화)은 쓰지 않는다.
- **K4 (floor)**: app MinTests 577 → **577 + 신규 테스트 수**. 이력 주석 한 줄, workflow 단계 이름 floor 표기만.

## 4. 판별력 계획 (Claude 실측)

| 결함 주입 | 기대 검출 |
|---|---|
| MC1 K1 `.catch` 제거 | (a) RED |
| MC2 K2 try/catch 제거 | (b)(c) RED |
| MC3 K2 `ensureActive()` 제거 | (d) RED |
| MC4 K2 `catch (e: Exception)` → `RuntimeException` | (b) RED |
| MC5 (R1 이후) K1 catch에서 `recordUnknownCall()` 재실행 = fallback이 일회성 경계를 다시 실행 | (a) size 단언 RED |

**실측 (2026-10-10)**:
- MC0~MC4는 기대대로 검출됐다(MC0 29/29 GREEN).
- 적대 검토 R1(공허한 size 단언)과 Codex가 지적한 경합(수집 즉시 실패 → 세션 개설 전 fallback)을 테스트 수정으로 해소했다. 수정 내용은 (a)·(d)의 세션 개설과 (a)의 실패 해제 게이트다.
- 수정 후 MC0r 29/29 GREEN, **MC5 3회 연속 RED**(결정적), MC1r RED. 원본 해시 복원 일치.
- 잔여 한계: K1 `emit(false)` 삭제는 실시간 180초 대기 금지 조건 때문에 미검출이다(IMPL_LOG 기록).

## 5. 파일 범위

소스 1 + 테스트 1 + CI 2. 밖은 S3 정지.

## 6. 완료 게이트

fresh 직렬 게이트 GREEN·0 skipped·app = 새 floor / lint 신규 0(로컬 robolectric newer-version EXTRA는 환경 기준선) / schema drift 0 / self-check / 범위 무접촉 / IMPL_LOG 매핑 표 / 기존 `RealCallRiskMonitor*Test` 전부 GREEN.

## 7. 범위 밖 (보고만)

- GUARDED 경로 `eventSink.recordRiskEvent` Room insert 무방비(실패 시 알림 여부 = 제품 결정) → 별도 트랙.
- testMode 이외 설정의 실패 의미, tick 단위 최후 catch(방어 심층화) → 별도 설계.
