# 보호자 조회 실패 트랙 지시문 — 팝업 보호자 조회 예외가 coordinator를 멈추지 않게 한다 (v1.0)

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol` 참조)**
> - **등급**: T0 문서·보고 / T1 격리 코드 / **T2 고위험** — [이 트랙 = **T2**]
>   근거: coordinator tick 루프·취소 의미론(동시성) 표면. 애매하면 위로.
> - **검토**: T2 = Codex 전체 자체검토 + Claude 적대 검토 + 독립 빌드/lint.
> - **정지·질의 = S1~S5만** (그 밖은 자동 전진):
>   S1 되돌릴 수 없는 게이트(커밋·push·실기·Manifest/권한/DI 실제 변경) /
>   S2 적대검증 통과 지적 ≥recommend / S3 등급 상승 /
>   S4 제품·정책 결정 필요 / S5 완료 게이트 실패(RED·lint 신규·범위 위반).
>
> 작성: Claude(계획·검토). 수행: Codex(코드·테스트·IMPL_LOG). Gradle·검증기 실행: Claude.
> 사용자 승인: "진행해"(2026-10-10, Claude 권고 = `firstGuardian()` 예외 처리 + RED 테스트).

## 0. 현재 상태

- 기준: main `fe67cc1`(PR #20 머지 후). CI 기준선 = app 571 / risk 7 / contracts 4, 실패·skip 0.
- 작업 worktree: `.worktrees/guardian-read`, 브랜치 `codex/guardian-read-failure`.
- **결함**: `DefaultRiskDetectionCoordinator.firstGuardian()`의 두 번째 조회
  `guardianRepository.observeGuardians().first().firstOrNull()`에 예외 처리가 없다.
  - 운영 경로: `GuardianRepositoryImpl.observeGuardians()` = `guardianDataStore.data.map{...}`.
    JSON 파싱 실패는 흡수하지만, DataStore 파일 읽기 `IOException`(손상 처리기 미설정 → `CorruptionException` 포함)은 그대로 전파된다.
  - 전파 경로: `firstGuardian()` → `processTickEscalation` / `processTickNewTriggers` → `processTick` → `merge(...).collect` → `scope.launch` job 종료.
    scope는 `SupervisorJob`이라 앱은 죽지 않지만, **coordinator job이 끝나 이후 모든 위험 감지·팝업·알림이 서비스 재시작 전까지 조용히 멈춘다.**
  - 조건: 수동 문자 메뉴 토글 ON + INTERRUPT 이상 팝업 시점(`smsMenuEnabled == false`면 조회 자체를 안 함).
- 팝업에서 guardian 인자의 유일한 용도 = 보호자 문자 버튼(`RiskOverlayManager` `if (guardian != null)`). null이면 버튼만 숨고 팝업은 그대로 뜬다.
- 확정 의미(CLAUDE.md SMS 토글 줄): "설정 조회 실패·미방출 시 버튼 숨김". 보호자 조회 실패도 같은 축이다 — **팝업 유지, 문자 버튼만 숨김(fail-closed for SMS, fail-open for protection).** 신규 제품 결정 없음.

## 1. 불변 운영 제약

1. commit·push·stage = S1(사용자 명시 승인 전 금지). broad add 금지.
2. Gradle 직렬 `--no-daemon --no-parallel --max-workers=1`, JAVA_HOME=JBR. Codex 샌드박스는 Gradle 불가 → Claude 실행.
3. 금지 범위: Manifest·permission·service·DI 모듈·monitor interface·NavGraph·`RiskOverlayManager`·data 모듈·domain 모듈.
4. RED-first: 신규 실패 사례는 수정 전 코드에서 RED여야 한다(Claude가 수정 hunk 역적용으로 실측).
5. 기존 테스트 삭제·약화 금지. 기존 단언 변경 0.
6. 산출 문서는 이 트랙 폴더에만.

## 2. 승인된 5줄 계획

1. **수정 파일**: `DefaultRiskDetectionCoordinator.kt`(firstGuardian 1곳) / `testutil/CoordinatorTestHarness.kt`(FakeGuardianRepository 주입 필드 추가만) / `PopupGuardianSmsToggleTest.kt`(사례 추가) / `.github/scripts/verify-unit-xml.ps1`·`.github/workflows/verify.yml`(floor 래칫).
2. **목적**: 보호자 조회의 일반 예외·빈 Flow가 coordinator job을 끝내지 못하게 한다. 실패 시 guardian=null(문자 버튼 숨김)로 팝업은 그대로 진행하고 다음 tick은 정상 조회한다.
3. **리스크**: 정책 — 자동 연락·권한·서비스 변화 0, 문자 버튼은 실패 시 숨김(보수적). 기술 — catch가 취소를 삼키면 stop 이후 팝업이 뜰 수 있음 → 설정 조회와 같은 `CancellationException` 재던짐 + generic catch 첫 줄 `ensureActive()`로 막는다.
4. **테스트(완료 게이트)**: 신규 사례 RED-first 실측, 결함 주입 3종 검출, 전체 직렬 게이트 GREEN·0 skipped·floor 일치, lint 신규 0, schema drift 0, self-check, 정적 계수 차이 = Log +1만.
5. **중단 조건**: 하네스 기존 동작 변경 필요 / interface·DI·data 계층 변경 필요 / 기존 단언 변경 필요 / 취소 의미론이 설정 조회와 달라져야 함 → S3·S4 정지.

## 3. 구현 계약

- **K1 (프로덕션)**: `firstGuardian()`의 보호자 조회를 설정 조회와 **같은 형태**로 감싼다.
  ```kotlin
  if (!smsMenuEnabled) return null
  return try {
      guardianRepository.observeGuardians().first().firstOrNull()
  } catch (e: CancellationException) {
      throw e
  } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      Log.w(TAG, "guardian read failed — guardian SMS button hidden", e)
      null
  }
  ```
  - `ensureActive()`는 generic catch **첫 줄**(Log.w 앞). 문구는 위와 동일. 다른 줄 변경 0.
- **K2 (하네스)**: `FakeGuardianRepository`에 `var guardianFailure: Exception? = null`, `var emptyGuardianFlow: Boolean = false` 추가.
  `observeGuardians()` = `beforeFirstEmission` → `guardianFailure?.let { throw it }` → `if (!emptyGuardianFlow) emit(guardians)`. 기본값에서 기존 동작과 바이트 단위 동일한 의미.
  (`FakeSettingsRepository`의 `smsMenuFailure`/`emptySmsMenuFlow` 패턴과 동일.)
- **K3 (테스트, PopupGuardianSmsToggleTest)**: escalation·new-trigger **두 경로 각각**:
  - (a) 보호자 조회 `java.io.IOException` → 팝업 1회·guardian null, 이후 실패 해제 + 새 upgrade trigger → 두 번째 팝업 guardian 표시(= coordinator 생존).
  - (b) 보호자 Flow 미방출(`NoSuchElementException`) → 팝업 1회·guardian null(+ coordinator 생존 확인 권장).
  - (c) 보호자 조회 훅 안에서 **동기** `coordinator.stop()` 직후 suspend 없이 `IOException` → 팝업 0·popup accounting 0·banking 이전값 대입 0·tick job 취소.
  - 기존 `assertSettingFailure` / `assertStopDuringSettingRead` helper 구조를 따른다(새 helper 추가는 허용, 기존 helper·단언 변경 0).
- **K4 (floor)**: app MinTests 571 → **571 + 신규 테스트 수**. 이력 주석 한 줄 추가, workflow 단계 이름 floor 표기만 변경.

## 4. 판별력 계획 (Claude 실측)

| 결함 주입 | 기대 검출 | 실측 (2026-10-10, 클래스 21건) |
|---|---|---|
| MG0 수정 상태 | 전부 GREEN | 21/21 GREEN |
| MG1 수정 hunk 역적용(try/catch 제거) | K3 (a)(b) 4건 RED | 6건 RED((c) 2건은 runTest 미처리 예외 보고로 추가 RED), 기존 15건 영향 0 |
| MG2 `ensureActive()` 제거 | K3 (c) 2건 RED | (c) 2건 RED |
| MG3 `catch (e: Exception)` → `catch (e: RuntimeException)` | (a) 2건 RED가 결정적 근거. (c)는 runTest 보고 의존(Codex T001 정정) | (a)(c) 4건 RED, (b) GREEN(NoSuchElementException은 RuntimeException) |

## 5. 파일 범위

소스 1 + 테스트 2(하네스·PopupGuardianSmsToggleTest) + CI 2. 밖은 S3 정지.

## 6. 완료 게이트

fresh 직렬 게이트 GREEN·0 skipped·app = 새 floor / lint 신규 0(로컬 robolectric newer-version EXTRA는 환경 기준선) / schema drift 0 / self-check / 정적 계수 Log 49→50, 재검증 라벨 10, `expectedResetEpoch = epochAtTickStart` 2 / 범위 무접촉 / IMPL_LOG 매핑 표.

## 7. 범위 밖 (보고만)

- **형제 결함 1**: GUARDED 경로 `eventSink.recordRiskEvent(event)`(Room insert)도 예외 처리가 없어 같은 방식으로 coordinator를 멈출 수 있다. 실패 시 알림을 보낼지/tick을 중단할지 = 제품 결정(S4) → 별도 트랙.
- **형제 결함 2 (Claude 적대 검토 발견)**: `RealCallRiskMonitor.observeCallSignals()` 안의 테스트 모드 설정 읽기 2곳 — OFFHOOK `emitAll(settingsRepository.observeTestModeEnabled()...)`(:398), IDLE `observeTestModeEnabled().first()`(:483) — 에 `.catch`가 없다. `senior_shield_settings` IOException/CorruptionException → 통화 OFFHOOK/IDLE 시 combine 실패 → coordinator job 종료. 같은 파일이 `SmsMenuEnabled`도 담으므로 설정 파일 손상 시 이번 수정과 무관하게 첫 통화에서 보호가 멈출 수 있다. monitor 계층(T2) → 별도 트랙, 우선순위 높음.
- 방어 심층화(tick 단위 최후 catch)는 부분 효과·fail-closed 설계와의 상호작용 검토가 필요 → 별도 설계.
- 보호자 Flow가 **영원히 방출하지 않는** 경우(`first()` 무한 대기)는 DataStore 계약상 발생하지 않음 → 범위 밖.
