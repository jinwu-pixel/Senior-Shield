# 팝업 보호자 문자 버튼 토글 적용 지시문 (v1.1 · 2026-10-08 사용자 승인)

> **협업 규약 헤더 (2026-07-21 확정)**
> - **등급**: **T2**. 근거: 보호자/외부 연락 흐름 변경(고위험 목록), orchestrator 생성자 의존성 추가(DI 연결 변경), overlay 표면 인접.
> - **검토**: Codex 전체 자체검토 + Claude 적대 검토 + 독립 빌드/lint/self-check.
> - **정지·질의**: S1~S5에서만 한다. 커밋·push·실기 = S1.
>
> 작성: Claude. 수행: Codex. 근거가 된 사용자 결정 2건(2026-10-08):
> - 팝업의 보호자 문자 버튼도 `smsMenuEnabled`를 따른다.
> - 이 계획을 승인한다("승인합니다").
>
> v0.1 → v1.0 변경: Codex T001 검토(CHANGES_REQUIRED, BLOCKING 1·SHOULD 4·NOTE 1)를 반영했다. Claude가 지적 사항을 실측으로 확인했다.
> v1.0 → v1.1 변경: 재검토 결과(BLOCKING #1 PARTIAL 종결, timeout 미도입 수용)를 반영했다. 새로 나온 지적 N1(BLOCKING: 라운드 A/B 테스트 범위 분리), N2·N3(SHOULD), N4(NOTE)도 반영했다. 남은 위험(무제한 대기)은 수용했다. 설정 조회가 재개된 뒤 reset 안전성은 유지된다. 다만 대기 자체를 끝내지는 못한다.
>
> **실행 역할**: Codex 샌드박스에서는 Gradle이 동작하지 않는다(10-07 실측). 그래서 다음과 같이 나눈다.
> - Codex: 코드·테스트 작성, 정적 계수 측정, IMPL_LOG 기록.
> - Claude: Gradle 실행과 검증기 실행.

## 0. 현재 상태 (실측, Codex 교차확인)
- main `650cb54`. 작업 worktree는 `C:\Users\momen\AndroidStudioProjects\Senior_Shield\.worktrees\sms-toggle`(브랜치 `codex/popup-sms-toggle`)이며 Claude가 생성했다.
- **팝업(`RiskOverlayManager`)의 `guardian` 파라미터**는 문자 버튼에만 쓰인다.
  - 근거: `:681` `if (guardian != null)` → ACTION_SENDTO `:697`.
  - 팝업에는 보호자 전화 버튼이 없다.
- **guardian의 공급 경로**: Coordinator `firstGuardian()`(`:589`)이 공급한다.
  - 운영 호출: escalation `:1214`, new-trigger `:1289`.
  - Debug 경로는 null을 전달한다(`:399·:421`).
- **토글 적용 현황**:
  - 이미 적용됨: Warning(`WarningScreen.kt:186·210`), Guardian(`GuardianScreen.kt:125`).
  - 아직 미적용: **팝업**과 **Home 연락 대화상자**(`HomeScreen.kt:272`, `HomeViewModel`에 토글 없음).
  - **이번 트랙은 팝업만 다룬다.** Home은 별도 제품 결정 대상이며 범위를 넓히지 않는다.
- **설정 저장소**: `SettingsRepository.observeSmsMenuEnabled()`는 `DataModule:32`에서 이미 바인딩되어 있다. 구현(`SettingsRepositoryImpl.kt:52-55`)은 DataStore `data.map`이고 기본값은 false이며 오류 복구가 없다.
- 기준선: 562 tests / 42 suites (10-07, R1 포함). 착수 전 재측정은 Claude가 한다.

## 1. 5줄 계획
1. **수정 파일**:
   - 프로덕션은 `DefaultRiskDetectionCoordinator.kt` 1개다. 허용되는 변경은 다음뿐이다.
     - import 추가
     - 생성자에 `settingsRepository: SettingsRepository` 추가. 기존 binding을 쓰는 DI 연결 변경이며, DI 모듈 파일은 수정하지 않는다.
     - `firstGuardian()` 본문
   - 테스트:
     - `CoordinatorTestHarness.kt`: 제어 가능한 Settings fake와 보호자 목록 fixture 추가, 생성자 인자 추가.
     - `CoordinatorTickCharacterizationTest.kt`(1곳), `DefaultRiskDetectionCoordinatorIdleExpiryBoundaryTest.kt`(4곳): 생성자 인자만 추가. 하네스를 포함해 총 6곳이다.
     - 신규 테스트 파일 1개.
2. **목적**: 위험 팝업의 "등록된 보호자에게 문자 보내기" 버튼을 수동 문자 메뉴 토글(기본 OFF)에 맞춘다.
3. **리스크**:
   - 정책: ACTION_SENDTO(사용자가 시작하는 동작)를 유지하므로 원칙과 충돌하지 않는다.
   - 의도된 동작 변화: 보호자는 등록했지만 토글이 OFF인 사용자의 팝업에서 문자 버튼이 사라진다.
   - 권한·Manifest·DI 모듈 파일 변경은 0이다.
   - 기술: suspend 대기가 한 번 늘어난다. 순서는 바뀌지 않지만 타이밍은 바뀐다. 대기 중 reset은 기존 "popup show" 재검증이 처리한다.
4. **테스트**:
   - RED-first: 라운드 A에서 계약 테스트가 **컴파일된 상태로 단언에서 실패**해야 한다.
   - 위 RED가 확인된 뒤 라운드 B에서 GREEN을 만든다.
   - CI `verify.yml` 54–84 전체(직렬 Gradle, 검증기 4종, self-check)를 통과해야 한다.
   - S2 체크리스트 8항목을 판정한다.
5. **중단 조건(S3/S5)**:
   - Coordinator 외 프로덕션 파일 변경이 필요해진 경우
   - 기존 테스트의 **단언** 변경이 필요해진 경우(fixture·생성자 인자 추가는 제외)
   - 실패 복구가 설정 조회 밖으로 넓어지는 경우
   - 실시간 토글 반영을 위해 overlay나 binding 수정이 필요해진 경우
   - 아래 허용 차이 밖에서 정적 계약이 바뀌는 경우

## 2. 구현 계약
**K1 조회 위치와 순서**
- `firstGuardian()` 안에서 토글을 **먼저** 읽는다.
  - OFF이면 즉시 null을 반환하고 보호자 조회를 생략한다.
  - ON이면 기존과 똑같이 `guardianRepository.observeGuardians().first().firstOrNull()`을 반환한다.
- 호출부 2곳의 코드, 순서, reset 재검증, `nowMs`, 훅, 로그는 바꾸지 않는다.

**K2 실패 계약 (BLOCKING 해소)**
- 설정 조회는 `settingsRepository.observeSmsMenuEnabled().first()`로 한다.
- `CancellationException`은 **다시 던진다**(부모 취소나 stop이 일어나면 팝업을 재개하지 않는다).
- 그 밖의 `Exception`은 OFF로 취급해 null을 반환하고 경고 처리는 계속한다. 빈 Flow에서 나는 `NoSuchElementException`도 여기에 포함된다.
  - 이때 `Log.w`를 1회 남긴다. 문구: `"sms menu setting read failed — guardian SMS button hidden"`.
- try/catch 범위는 **설정 조회 한 줄**로 한정한다. 보호자 조회의 예외 처리는 바꾸지 않는다(기존 동작 유지, 백로그로 기록).
- **timeout은 도입하지 않는다.**
  - 근거 1: 같은 DataStore 계층인 기존 보호자 조회에도 timeout이 없다.
  - 근거 2: 새 타이밍 상수를 만들지 않는다는 단순성 원칙.
  - 근거 3: 대기 중 reset은 기존 재검증이 처리한다.
  - 이 판단은 Claude 결정이다. 재검토에서 반대 근거가 나오면 S2로 처리한다.

**K3 의미**
- 팝업 생성 요청 시점에 토글 값을 한 번 읽는다(스냅샷).
- 표시 중에 토글이 바뀌어도 버튼을 실시간으로 갱신하지 않는다(수용한 동작).

**K4 무변경 대상**
- `RiskOverlayManager.kt` (소스 텍스트 계약 대상)
- binding
- Debug 경로
- reflection 대상, 훅 7개, 락, `expectedResetEpoch = epochAtTickStart` 2회, reset 라벨 10개

**K5 정적 계수**
- `static_invariants.py`로 변경 전후를 비교한다. 허용되는 차이는 **Log 호출 48→49와 리터럴 1개 추가뿐**이다. 나머지 항목은 모두 같아야 한다.

**K6 테스트 fixture**
- 하네스 Settings fake:
  - Boolean 값을 제어할 수 있어야 한다.
  - 예외 모드와 빈 Flow 모드를 지원한다.
  - **하네스 기본값은 ON**이다. 그래야 기존 guardian 지연 훅 테스트가 그대로 통과한다. 제품 기본값(false)은 바꾸지 않는다.
- 보호자 fake:
  - 목록 fixture를 추가한다. 기본값은 빈 목록이다(기존과 같음).
  - `beforeFirstEmission` 훅은 보존한다.
- 하네스는 `advanceUntilIdle()`을 쓰지 않는다(maintenance 루프 때문). `runCurrent()`와 `stop()`을 쓴다.

**K7 신규 테스트**
- 정상 경로와 조회 실패 fallback: `show`가 정확히 1회 호출됐는지와 전달된 guardian 인자를 함께 단언한다.
- 설정 대기 중 취소/reset: `show` 0회를 단언한다.
- 실패·취소·reset은 escalation과 new-trigger 각각에서 검증한다.
- escalation 경로: 미통보 원격제어 trigger로 유도한다.
  - OFF+보호자 → null
  - ON+보호자 → 그 보호자
  - ON+보호자 없음 → null
- new-trigger 경로: `CoordinatorTickCharacterizationTest`의 양성 대조 방식(alert/level만 notified)으로 유도한다.
  - OFF+보호자 → null
  - ON+보호자 → 그 보호자
- 실패 처리(라운드 B에서 추가, 두 경로 각각):
  - 설정 예외 → null, show 1회, 이후 tick이 정상 처리됨
  - 빈 Flow → null, show 1회
  - 설정 대기 중 `stop()` → show 0회, 이후 대입·효과 없음
  - 설정 대기 중 reset → 기존 재검증으로 중단, show 0회

## 3. 라운드
- **A (RED)**:
  - 생성자 파라미터를 추가하되 **사용하지 않는다**(firstGuardian 동작 유지).
  - 하네스 fixture와 **K7의 정상 표시 사례 5개만** 작성한다. 설정 실패·대기 사례는 이 라운드에서 작성하지 않는다.
  - → Claude가 실행한다. 아래를 확인한다.
    - OFF+보호자 2건(두 경로)이 **단언 RED**인지
    - 신규 3건(ON+보호자 ×2, ON+보호자 없음)과 기존 테스트 전부가 GREEN인지
    - 컴파일 오류를 RED로 간주하지 않는다.
- **B (GREEN)**:
  - K1·K2를 구현한다.
  - K7 실패 처리 테스트(두 경로 각각)를 추가한다.
  - → Claude가 실행한다. 전체 GREEN과 정적 계수 K5를 확인한다.
- **C (검토)**:
  - Codex가 전체 자체검토를 하고 S2 8항목을 판정한다.
  - → Claude가 적대 검토, 전체 게이트, self-check를 수행한다.
- 문서 정정은 Claude가 main 작업트리에서 수행하고 일일 batch로 커밋한다.
  - 대상: CLAUDE.md·AGENTS.md의 smsMenuEnabled 줄(현재 157/177)
  - 방식: 기존 예외 문장(수동 문자·기본 OFF·ACTION_SENDTO 허용)은 **그대로 두고**, 그 뒤에 덧붙인다.
  - 덧붙일 문안: "Guardian·Warning 화면과 위험 팝업의 보호자 문자 버튼은 이 토글을 따른다(팝업은 설정을 실제로 읽은 시점의 스냅샷). Home 연락 대화상자는 미적용(별도 결정)."
  - "ACTION_DIAL만" 줄(113/154, 133/174)은 승인된 SMS 예외 구조라 수정하지 않는다.
