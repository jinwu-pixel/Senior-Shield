# R1 Coordinator tick 분해 지시문 — 동작 불변 구조 리팩터 (v1.0 · 2026-10-07 사용자 승인)

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol` 참조)**
> - **등급**: T0 문서·보고 / T1 격리 코드 / **T2 고위험** — [이 트랙 = **T2**: orchestrator·동시성 순서·safe-confirm/S2 표면 인접]
>   (애매하면 위로 올린다. 실행 중 등급이 부족하다고 판명되면 S3로 자동 등급 상승·중단·질의한다.)
> - **검토**: T2 = Codex 전체 자체검토 + Claude 적대 워크플로 + 독립 빌드/lint.
> - **정지·질의는 S1~S5에서만** 한다. 그 밖은 자동 전진하고 한 줄 로그 `✓ <단위> [T2] — <수치>`를 남긴다.
>   - S1 되돌릴 수 없는 게이트(커밋·push·실기·Manifest/권한/DI 실제 변경)
>   - S2 적대검증을 통과한 지적 ≥recommend
>   - S3 등급 상승
>   - S4 제품·정책 결정 필요
>   - S5 완료 게이트 실패(RED·lint 신규·범위 위반)
>
> 작성: Claude(계획·검토). 수행: Codex. **v1.0 — 2026-10-07 사용자 "승인합니다"로 확정·기동.** Codex 검토 T001(CHANGES_REQUIRED→재검토 PASS_WITH_NOTES) 반영본.
> 해석이 갈리면 중단하고 질의한다. 배경·제약·후보 평가는 같은 폴더의 `PLAN.md` v0.2를 본다.

## 0. 현재 상태

- **기준 커밋**: main `9657cf4`.
- **작업 위치**: 새 worktree `C:\Users\momen\AndroidStudioProjects\Senior_Shield\.worktrees\refactor-coordinator-r1`, 브랜치 `codex/refactor-coordinator-r1`(base `9657cf4`). **Claude가 생성 완료**(local.properties 복사, ignored). 커밋은 S1이다.
- **Java**: 현재 셸의 `JAVA_HOME`이 `C:\Program Files\Java\jdk-21\bin`으로 **잘못 설정**되어 있다. 모든 Gradle·검증 명령 앞에 `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'`(JBR 21.0.9)를 명시한다. CI는 Temurin 21이라 배포판이 다르다는 점은 수용한다.
- **실행 역할 분담 (실측)**: Codex companion 샌드박스에서는 Gradle이 실행되지 않는다(2026-10-07 탐침: wrapper 단계 `SocketException: Permission denied`). 따라서 **Gradle·검증기·self-check 실행 = Claude**, **코드·테스트 작성·정적 계수 스크립트 실행·자체검토·IMPL_LOG = Codex**. 라운드마다 Codex가 RESULT로 넘기면 Claude가 표적 테스트를 돌려 결과를 회신한다(`--resume-last`). Codex는 Gradle을 시도하지 않는다.
- **대상**: `app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt`
  - `start()`(627–1205)의 collect 람다: 655–1203, 549줄.
  - `return@collect`는 26개다. 그중 maintenance 조기 반환 2개(667·673)는 대입이 없다. 나머지 24개는 `previousBankingForeground = bankingForeground` 직후에 있다. 정상 종료(1202)에서도 1회 대입하므로 대입은 합계 25회다.
- **기준선**: R1.0을 추가한 뒤 R1.1 전에 아래 두 가지를 측정해 IMPL_LOG에 기록한다.
  - §6 직렬 게이트 결과: 모듈별 suites·tests·skipped
  - §6 정적 불변 계수의 "전" 값

## 1. 불변 운영 제약

1. commit·push·stage는 S1이다. broad add는 금지한다.
2. Gradle은 직렬로 실행한다(`--no-daemon --no-parallel --max-workers=1`). Gradle·검증·Git 범위 확인의 **cwd는 새 worktree**다.
3. 금지 범위: Manifest·permission·service·DI 모듈·monitor interface·NavGraph·`RiskDetectionCoordinator` interface. 변경이 필요해지면 S1 또는 S3다.
4. **기존 테스트 파일은 수정·삭제 0.** 신규 테스트는 R1.0 characterization 파일 1개만 허용한다.
5. 제품 소스 신규 파일은 0이다. 예외는 R1.0 테스트 파일 1개와 이 트랙 산출물이다. 산출물(`IMPL_LOG.md`·정적 계수 스크립트와 출력)은 메인 working tree의 `investigations/2026-10-07-refactor-plan/`에만 쓴다.
6. probe self-check의 임시 git 작업은 전용 scratch 절대경로 `C:\tmp\seniorshield-r1-probes` 안에서만 허용한다.

## 2. 승인 대상 5줄 계획

1. **수정 파일**: `DefaultRiskDetectionCoordinator.kt` 1개 + 신규 characterization 테스트 1개. 기존 테스트는 수정하지 않는다.
2. **목적**: `start()`의 549줄 람다를 순서가 고정된 단계 함수로 나눈다(동작·로그 불변). 25회 반복되는 대입은 증거를 확보한 뒤에만 1곳으로 통합한다.
3. **리스크**
   - 정책·권한: 0.
   - 기술: 조기 종료 전파 누락, 외부·volatile 상태 재조회 시점 변경, suspend·훅 순서, captured var 의미, 락 대상 변경.
4. **테스트**
   - 기존 무수정 GREEN
   - R1.0 GREEN(리팩터 전·후)
   - CI 검증기 + self-check
   - 정적 계수, 출구 대응표, S2 8항목
   - Claude 적대 검토
5. **중단 조건**(S3/S5): 아래 중 하나라도 해당하면 중단한다.
   - 기존 테스트를 수정해야 함
   - 다른 제품 파일 변경이 필요함
   - 계수 불일치를 해소할 수 없음
   - 출구 대응을 논증할 수 없음
   - R1.0이 현행 코드에서 GREEN이 아님

## 3. 구현 계약

**K1 범위**
- 바꾸는 것은 `start()` 본문과 새 private 함수·타입뿐이다.
- 227–626 구간, 1207행 이후의 기존 메서드, 하단 타입 정의는 손대지 않는다.
- 새 타입은 수동 선언 기준 file-private 최대 2개다. `TickLoopState`와 단계 간 값 묶음 1개다. 반환값은 가급적 Boolean이나 nullable로 표현해 새 타입을 쓰지 않는다.

**K2 루프 상태**
- `latestSignals`·`renewalToken`·`lastSeenSeq`를 `private class TickLoopState`로 묶는다.
- `scope.launch` 블록 안, collect 밖에서 생성한다. **새 launch마다** 새 인스턴스가 생긴다. 이미 활성이면 `start()`는 반환하므로 현행 수명과 같다.
- 필드 승격은 금지한다. holder는 상태 보관용일 뿐이며 **락 소유 객체가 되어서는 안 된다**. C:892의 `synchronized(this@DefaultRiskDetectionCoordinator)` 대상을 유지한다.

**K3 조기 종료와 banking 대입**
- **R1.1**: 대입 25곳의 위치와 순서를 그대로 둔다. `previousBankingForeground = bankingForeground; return@collect` 패턴은 단계 함수 안에서 `previousBankingForeground = bankingForeground; return <중단>`이 된다.
- **중단은 모든 호출 계층에서 즉시 전파한다.** 하위 단계가 중단을 반환하면 호출부도 다른 효과를 실행하지 않고 즉시 반환한다. 반환값을 무시하는 호출부는 없어야 한다.
- **R1.2(조건부)**: R1.0이 GREEN이면 단계부 내부의 대입을 제거한다. 그리고 `processTick`에서 단계부가 반환한 직후(계속·중단 무관) 1회 대입한다.
- 대입하지 않는 경우: 단계부 밖으로 전파되는 예외와 `CancellationException`.
- 대입하는 경우: 내부에서 처리되어 정상 반환한 실패. 예를 들어 C:1286–1291 push 실패는 null을 반환한 뒤 C:1045/1147에서 대입한다. 기존대로 유지한다.
- 새 `catch`·`finally`·취소 체크는 추가하지 않는다. 예외로 흐름을 제어하지 않는다. R1.2를 보류하는 것도 수용 가능한 결과다.

**K4 단계 순서 고정**
- 아래 순서와 각 단계 안의 부수효과·훅·로그 순서를 그대로 둔다.
  1. prologue: mirror → maintenance 게이트 → 신선도 → banking 정규화 → advancedSources → 진단 로그
  2. suppression IDLE 예약
  3. snooze 평가·필터
  4. renewal 토큰 검증
  5. transition → 정리
  6. 토큰 발급
  7. cooldownConsumed clear → evaluate/resolve → downgrade·재무장
  8. OBSERVE/suppression 종료
  9. trigger sync → snooze 잔존 종료 → 쿨다운 표시 중 마킹
  10. 재검증 (b)
  11. 쿨다운 단계: `expectedResetEpoch = epochAtTickStart` 리터럴 2곳 유지
  12. 재검증 (c) → escalation
  13. 재검증 (d) → new-trigger
- **escalation 본문(1030–1123)과 new-trigger 본문(1133–1200)은 각각 한 함수로 통째로 옮긴다. 내부 분할은 금지한다.**
- escalation 함수는 중단 여부와 `popupShownThisTick`을 함께 반환한다(예: `Boolean?`, null = 중단).
- `popupShownThisTick`은 지금처럼 overlay 회계 callback 안에서만 true가 된다.
- `cooldownFiredThisTick`은 쿨다운 단계의 결과로 escalation·new-trigger에 넘긴다.
- `syncedSession`은 C:952 copy 갱신 **이후** 값을 넘긴다. tracker 최신값으로 대체하지 않는다.
- `nowMs`는 guardian 조회 전에 캡처한 값을 회계에 그대로 쓴다.

**K5 상태 조회는 위치·횟수·단락 평가를 보존한다.** 아래 목록은 예시이며 한정 목록이 아니다.
- 대상: `cooldownManager.isShowing()`, `sessionTracker.isSnoozeActive()`·`isSnoozedForCall()`·`snoozed*()`·`sessionState.value`·`userResetEpoch`, `overlayManager.isEndCallSuppressed()`, `callMonitor.currentCallId()`, `clock()`.
- `@Volatile` 필드 `previousBankingForeground`(C:698·967), `cooldownConsumedSessionId`(C:872–874·981·1006), `s2RecRefireState`(C:1089–1113·1140–1192)도 포함한다.
- 금지 1: 단락 평가 안의 조회를 인자로 **미리 평가**하는 것.
  - 예: `bankingJustOpened`를 앞 단계에서 계산하지 않는다.
  - 예: `isShowing()`을 하나의 Boolean 인자로 합치지 않는다.
- 금지 2: 캐싱·병합·재정렬.

**K6 (v0.1의 publication 게이트 공통화) — R1 범위에서 제외한다.**
- 이유 1: 정적 호출 수 보존과 충돌한다.
- 이유 2: 핵심 효과 게이트는 이미 C:1386 helper로 공통화되어 있다.
- 이유 3: S2 불변 7(helper 공용화 금지)과 인접한다.
- 백로그로 기록한다.

**K7 그대로 둘 것**
- 공개 API·생성자·`@Singleton`·`@Inject`
- 훅 7개 + `runInactiveSessionCleanupForTest`의 이름·시그니처·호출 지점
- reflection 대상(`eventPublicationMutex`·`activeEventPublicationIntents`·`consumedSafeConfirmations`·`currentSafeConfirmationEvent`·`issueDebugOverlayBinding`)의 이름·타입·소유 객체
- 상수, 락 대상·블록 내용
- `Log.*` 문구(바이트 동일), `userResetIntervened` 라벨 10개
- S2 호출(`shouldSuppressS2RecRefire`·`s2RecRefireStateAfterFiring`)의 인자·순서
- 주석 의미. 단계 주석은 함수로 옮긴다.

## 4. 라운드

- **R1.0 — characterization 테스트 1파일 신설.** 제품 코드는 바꾸지 않는다.
  - 현행 코드에서 GREEN이어야 한다. RED-first가 아니라 **현행 동작을 고정**하는 단계다.
  - `previousBankingForeground`는 reflection으로 읽어도 된다(선례: `SafeConfirmationCommandTest.kt:622`).
  - 고정할 성질:
    - (a) banking 계산 이후 조기 종료(예: aborted·세션 없음·snooze 잔존 중 1개 이상) → 대입된다.
    - (b) maintenance 두 출구 → 이전값이 유지된다.
    - (c) 훅으로 tick을 멈춘 채 `stop()`해서 escaping cancellation이 나면 → 미대입이다.
    - (d) 처리된 push 실패 → 대입된다.
    - (e) escalation 중단(예: publication 교체) → 같은 tick에서 new-trigger 효과가 0이다.
  - **판별력 조건**: 아래 조건을 지키지 않으면 테스트가 잘못된 구현도 통과시킨다.
    - (a)·(c)·(d)는 effective banking 입력이 기존 previous 값과 **달라야** 한다. 같으면 대입 여부를 판별할 수 없다.
    - (c)는 훅 진입을 확인한 뒤 `stop()`을 호출하고, 취소 후속 처리가 끝난 다음에 단언한다.
    - (e)는 escalation이 중단되지 않았다면 같은 tick에서 new-trigger 효과가 실제로 발생할 입력을 구성한다. 그리고 교체 publisher가 낸 효과는 제외한 채, 대상 tick이 추가로 낸 event·notification·overlay가 0인지 검증한다.
  - 성질 하나라도 production seam 없이 설계할 수 없으면, 그 항목은 구조 논증으로 대체한다. 근거를 IMPL_LOG에 쓰고 계속한다. Claude가 검토할 때 판정한다.
  - 각 성질은 `TESTED` 또는 `STRUCTURALLY_PROVED`로 기록한다. R1.2 진행 여부를 판단할 때 이 근거를 함께 제시한다.
- **R1.1 — 분해.** `TickLoopState` + `processTick` + 단계 함수(K4)로 나눈다. 대입은 제자리에 두고 중단 전파 규칙을 적용한다(K3).
- **R1.2(조건부) — 대입 통합(K3).**
- **각 라운드 종료 시**
  - 표적 테스트를 fresh로 실행한다. `--rerun-tasks` 또는 사전 `cleanTestDebugUnitTest`로 캐시를 배제하고, XML의 타임스탬프와 개수를 확인한다. 대상은 orchestrator 패키지 + `RiskOverlayManagerBindingContractTest` + R1.0 파일이다.
  - 정적 계수가 PASS여야 한다.
  - IMPL_LOG에 append한다.

## 5. 파일 범위

- 제품 소스: 1개.
- 테스트: R1.0 신규 1개만. 기존 테스트 수정은 0이다.
- 산출물: 이 트랙 폴더의 `IMPL_LOG.md`와 정적 계수 스크립트·출력.
- 이 범위를 벗어나면 S3다.

## 6. 완료 게이트 (cwd = worktree, JAVA_HOME = JBR)

1. **직렬 게이트를 fresh로 실행**해 GREEN이어야 한다. 명령은 CI `verify.yml` 59–64와 같다.
   ```
   ./gradlew --no-daemon --no-parallel --max-workers=1 clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug
   ```
2. **검증기를 worktree의 스크립트로** 실행한다. 모두 종료코드 0이어야 한다.
   ```
   ./.github/scripts/verify-unit-xml.ps1 -RepositoryRoot <worktree>
   ./.github/scripts/verify-domain-lint.ps1 -RepositoryRoot <worktree>
   ./.github/scripts/verify-lint-union.ps1 -RepositoryRoot <worktree> -AppCurrent <worktree>/app/build/reports/lint-results-debug.xml -DataCurrent <worktree>/data/build/reports/lint-results-debug.xml
   bash ./.github/scripts/check-schema-drift.sh data/schemas
   ./investigations/2026-09-06-ci-baseline-t1/probe-verifiers.ps1 -RepositoryRoot <worktree> -ReportRoot <worktree> -Scratch C:\tmp\seniorshield-r1-probes
   ```
   마지막 self-check는 `unexpected=0`이어야 한다.
3. **모듈별 suites·tests**를 기준선(R1.0 포함)과 비교해 같아야 한다. `verify-unit-xml`은 floor 검사뿐이므로 이 비교를 별도로 기록한다. skipped는 0이어야 한다.
4. **범위 검사**: worktree에서 `git status --porcelain --untracked-files=all` + `git diff --cached --name-only`를 실행한다. 나오는 것이 정확히 제품 소스 1 + R1.0 테스트 1이어야 하고, staged는 0이어야 한다.
5. **정적 불변 계수 전/후 표** — 일치해야 한다.
   - Log 호출 수와 Log 문자열 리터럴 multiset
   - `userResetIntervened` 라벨 multiset(10)
   - `synchronized(` 10 · `@Synchronized` 6
   - `expectedResetEpoch = epochAtTickStart` 2
   - 훅 7개 각각의 호출 수
   - K5 대상 호출 각각의 수
   - S2 함수 호출 수
   - `previousBankingForeground = bankingForeground`: R1.1은 25, R1.2를 적용하면 maintenance 출구를 제외한 단일 지점
6. **출구 대응표**: 기존 26개 출구 각각에 대해 다음을 적는다. 새 위치(함수:줄), 대입 여부, 호출부 전파 경로.
7. **S2 체크리스트** `.github/S2_REVIEW_CHECKLIST.md` §6의 8항목 판정을 IMPL_LOG에 쓴다.
8. **Codex 전체 자체검토**, APK 크기, 등가성 논증(K2·K3·K5 지점별)을 IMPL_LOG에 쓴다.
