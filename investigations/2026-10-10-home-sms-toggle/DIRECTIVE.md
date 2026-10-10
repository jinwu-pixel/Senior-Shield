# Home 보호자 문자 버튼 토글 적용 + 취소 상태 보존 보강 지시문 (v1.0 · 2026-10-10 사용자 승인)

> **협업 규약 헤더**
> - **등급**: **T2**. 근거는 세 가지다.
>   - 보호자/외부 연락 흐름을 바꾼다.
>   - ViewModel·Repository 주입·DataStore Flow를 동시에 건드린다(CLAUDE.md 고위험 목록).
>   - orchestrator의 취소 의미를 바꾼다.
> - **검토 흐름**: Codex 구현(RED-first) → Codex 자체검토 → Claude 적대 검토 + 독립 빌드·lint·self-check.
> - **정지·질의**: S1~S5에서만 한다. 커밋·push·실기 = S1.
>
> 사용자 결정(2026-10-10):
> - "3, 4번은 제안한대로 진행해": (3) Home 대화상자 문자 버튼도 토글을 따른다. (4) I1 보강을 이 트랙에 넣는다.
> - "5건 반영해": 독립 검토 SHOULD 5건을 반영해 v1.0으로 확정한다.
>
> **역할**: Codex 샌드박스는 Gradle을 실행할 수 없다. 그래서 Gradle과 검증기 실행은 Claude가, 코드·테스트 작성과 IMPL_LOG 기록은 Codex가 맡는다.
> **착수 전제**: 팝업 토글 PR #17이 main에 머지된 뒤, 그 main을 base로 삼는다. I1은 #17에서 들어간 `firstGuardian()`을 수정하기 때문이다.

## 0. 현재 상태 (실측 + 독립 검토 교차확인)
- **대화상자 표시**: `HomeScreen.kt:232-280`의 `GuardianContactDialog`는 전화(ACTION_DIAL)와 문자(ACTION_SENDTO) 버튼을 **항상** 둘 다 보여 준다(문자 버튼은 `:246·:272`). 보호자가 없으면 대화상자 대신 Guardian 화면으로 이동한다(`:233-236`). 미리보기는 `:430-450`.
- **ViewModel**: `HomeViewModel.kt:37-43`의 생성자에는 Settings가 없다. 내부 combine(`:70-75`)은 Flow 4개, 외부 combine은 5개를 받는다. `uiState`는 `stateIn(WhileSubscribed(5_000), initialValue = HomeUiState())`이며, 이 초기값의 문구는 "안전합니다"(SAFE)다(`:111-114`, `HomeUiState:47·60`).
- **문자 진입점은 모두 4곳**: Guardian(토글 적용), Warning(적용), 팝업(#17로 적용), Home(**미적용**). 알림과 시뮬레이션에는 문자 진입점이 없다.
- **테스트 현황**:
  - HomeViewModel 생성자를 호출하는 테스트는 `HomeViewModelSafeConfirmationTest.kt:212` 1곳이다.
  - `HomeScreenWarningNavigationContractTest`는 Warning 이동 문자열만 고정하고, 대화상자는 다루지 않는다.
  - Compose UI 테스트(`ui-test-junit4`)는 카탈로그에만 있고 `app/build.gradle.kts`에 연결되어 있지 않다. 연결하려면 의존성을 추가해야 하는데, 이는 금지 사항이다.
- **문서**: `CLAUDE.md:158`과 `AGENTS.md:178`에 "Home 연락 대화상자는 미적용(별도 결정)"이 남아 있다. 이 트랙에서 같이 고친다.
- **I1의 정확한 위험**: #17의 `firstGuardian()`은 generic catch로 예외를 받으면 false를 반환한다. job이 이미 취소된 상태에서 설정 Flow가 CE가 아닌 예외를 던지면, 이 catch가 취소 상태를 삼킨다. 그 뒤 `overlay.show`까지 suspend 지점이 없으므로 `stop()` 이후에도 팝업이 표시될 수 있다.

## 1. 5줄 계획
1. **수정 파일**
   - 프로덕션:
     - `HomeViewModel.kt`: SettingsRepository 주입, 내부 combine에 토글 Flow 추가
     - `HomeUiState.kt`: 필드 `smsMenuEnabled: Boolean = false` 추가
     - `HomeScreen.kt`: 대화상자에 표시 여부를 전달하고, false면 문자 버튼만 숨김
     - `DefaultRiskDetectionCoordinator.kt`: `firstGuardian()`의 generic catch 첫 줄에 `currentCoroutineContext().ensureActive()`, 필요한 import 추가
   - 테스트:
     - `HomeViewModelSafeConfirmationTest.kt`: 생성자 인자만 추가
     - Home 토글 테스트 1파일 신설
     - `PopupGuardianSmsToggleTest.kt`: I1 사례 추가
   - 문서: Claude가 main 작업트리에서 `CLAUDE.md:158`·`AGENTS.md:178`의 "Home 미적용" 문구를 정정한다.
2. **목적**
   - 문자 버튼을 표시하는 네 곳 모두가 수동 문자 메뉴 토글(기본 OFF)을 따르게 해서 동작을 일관되게 만든다.
   - 예외 경로가 코루틴 취소 상태를 삼키지 않게 한다. 취소는 협력적이라 `firstGuardian()`이 반환된 뒤의 경합은 남는다. 이 작업은 "race 완전 차단"이 아니다.
3. **리스크**
   - 정책: 문제없다. 전화(ACTION_DIAL)는 그대로 두고, 문자(사용자가 시작하는 ACTION_SENDTO)를 숨기는 방향으로만 바뀐다.
   - 의도된 동작 변화: 기본값이 OFF이므로 **사실상 모든 사용자**의 Home 대화상자가 "보호자에게 전화하기" 버튼 1개짜리가 된다.
     - 대화상자는 유지한다. 최소 변경이고 실수 발신을 막는다.
     - "대화상자 없이 바로 다이얼"은 연락 흐름 변경(S4)이므로 이번에 하지 않고 UX 백로그로 기록한다.
   - 권한·Manifest·DI 모듈 파일 변경은 없다(0).
   - 기술: 설정 Flow가 Home 상태를 멈추게 하면 안 된다. 상세 계약은 K1.
4. **테스트**
   - RED-first:
     - 필드를 먼저 추가하고(기본 false), ON 사례로 RED를 확인한다. 필드가 없어서 나는 컴파일 실패는 RED로 인정하지 않는다.
     - I1 RED는 K3대로 확인한다.
   - 회귀: 기존 테스트 전체, HomeScreen 계약 테스트, CI와 동일한 직렬 게이트, 검증기, self-check.
   - 테스트를 추가하므로 app floor를 그만큼 올리는 래칫도 이 트랙에 포함한다(`verify-unit-xml.ps1`·`verify.yml` 표기).
   - 실기: 릴리스 전 수동 확인 목록에 Home 대화상자의 토글 ON/OFF 동작 확인을 추가한다.
5. **중단 조건(S3/S5)** — 아래 중 하나라도 해당하면 멈춘다.
   - Home 화면 구조·네비게이션 변경이 필요한 경우
   - HomeScreen 계약 테스트 문자열을 바꿔야 하는 경우
   - 기존 테스트 단언을 바꿔야 하는 경우
   - Compose UI 테스트 의존성을 추가해야 하는 경우
   - I1이 catch 범위나 호출부 순서를 바꿔야 하는 경우

## 2. 구현 계약
**K1 Home 설정 Flow (SHOULD-1)**
- 사용할 형태: `settingsRepository.observeSmsMenuEnabled().onStart { emit(false) }.catch { emit(false) }`
  - `onStart`: 설정이 아직 없거나 Flow가 값을 내지 않아도 combine이 시작되도록 한다. 이게 없으면 Home 상태가 초기값 "안전합니다"에 고정되는 fail-open이 생긴다.
  - `catch`: 예외가 나면 false를 내고 upstream을 끝낸다. 실제 취소 원인은 catch 연산자가 그대로 다시 던진다.
- 수용하는 제약: 예외로 upstream이 끝난 뒤에는 토글 변경이 화면 재진입(WhileSubscribed 재구독) 전까지 반영되지 않는다. retry는 넣지 않는다(단순성 원칙). 이 제약은 테스트로 명시한다.
- 위치: 내부 combine의 다섯 번째 Flow로 넣는다(typed 오버로드 5개 이내). 외부 combine 구조는 그대로 둔다.

**K2 UI (SHOULD-2)**
- 필드 이름은 Guardian/Warning UiState와 맞춰 `smsMenuEnabled`로 한다. 기본값은 false다.
- `GuardianContactDialog`에 표시 여부를 넘긴다. false면 문자 버튼만 숨기고 전화 버튼과 대화상자는 그대로 둔다.
- 미리보기는 기본값 그대로 두어도 컴파일되어야 한다.

**K3 I1 (SHOULD-3·4)**
- `firstGuardian()`의 generic `catch (e: Exception)` 블록에서 **첫 줄에** `currentCoroutineContext().ensureActive()`를 둔다. 반드시 `Log.w`보다 앞이어야 한다. 그래야 취소된 경우에 "읽기 실패" 로그가 남지 않는다.
- try 범위와 호출부는 바꾸지 않는다.
- 정적 계수:
  - 바뀌는 것은 import 최대 2줄과 catch 블록 1줄이다.
  - Log 49, 재검증 라벨 10, `expectedResetEpoch = epochAtTickStart` 2회는 그대로 유지한다.
- RED 테스트 설계:
  - 설정 대기 훅 안에서 **동기적으로** `stop()`(또는 job cancel)을 호출한다.
  - suspend를 거치지 않고 곧바로 `IOException`이 나게 한다.
  - escalation과 new-trigger 두 경로 모두에서 기존 `assertStopDuringSettingRead`의 단언(banking 대입 0, accounting 0, show 0)을 재사용한다.
  - 현행 코드(#17)에서 RED가 나는지 IMPL_LOG에 관측해 기록한다.
  - 기존 테스트처럼 cancellable await로 대기하면 현행 코드에서도 CE가 나서 RED가 되지 않는다. 그 방식은 쓰지 않는다.

**K4 Home 테스트 (Q4)**
- ViewModel 수준에서 단언한다.
- 기존 `HomeViewModelSafeConfirmationTest`의 `mockkStatic(ContextCompat/Settings)`·`setMain` 패턴을 따른다.
- 활성 구독자(WhileSubscribed)를 두고 실제 fake Flow를 쓴다. relaxed mockk Flow는 값을 내지 않아 combine이 멈춘다.
- 다룰 사례:
  - OFF + 보호자 → `smsMenuEnabled=false`
  - ON + 보호자 → `true`
  - 설정 예외 → `false`이면서 uiState가 계속 갱신됨
  - 설정 Flow 무방출 → Home 상태가 실제 위험을 반영함(SAFE에 고정되지 않음)
  - 예외 후 토글 변경은 재구독 전까지 반영되지 않음
- 화면 연결 회귀를 확인하는 선택지로, 기존 계약 테스트 클래스에 단언 1개를 추가하는 방법이 있다(새 파일을 만들지 않음). 추가 여부는 Codex가 판단하고 근거를 IMPL_LOG에 기록한다.

**K5 커밋 구성**: Home 토글, I1 보강, floor 래칫을 각각 별도 커밋으로 나눈다(되돌리기 쉽게). 커밋은 S1이다.

## 3. 범위 밖 (백로그로 기록)
- Guardian/Warning ViewModel에서 설정 Flow 예외를 처리하지 않아 크래시가 날 수 있는 경로
- `firstGuardian()`에서 보호자 조회 예외가 나면 coordinator job이 종료되는 기존 문제
- Home "대화상자 없이 바로 다이얼" UX
- Google Maven 의존성의 newer-version lint EXTRA로 main CI가 RED가 될 잠재 위험
