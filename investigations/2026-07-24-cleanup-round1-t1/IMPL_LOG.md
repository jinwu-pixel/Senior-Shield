# Task 0 구현 로그 — T1 raw handoff

- 시작 HEAD: `d567683fdd92da3b60cd0427dc4142ae0bca1282`
- 실행 순서: C → A → B → D
- Git 게이트: stage/commit/push/PR 없음

## 변경 전 기준선

- JDK 21.0.2, Gradle 8.7, 로컬 Android SDK 사용.
- fresh 직렬 `clean testDebugUnitTest assembleDebug`: `BUILD SUCCESSFUL`.
- 테스트: 35 suites, 454 tests, failures/errors/skipped `0/0/0`.
- `lintDebug`: 5 errors / 67 warnings.

DIRECTIVE 작성 당시의 5E/62W와 현재 5E/67W 차이는 앱 또는 빌드 소스 변경이 아니다.
기준 커밋 `11b954b` 이후 현재 HEAD까지 변경은 `AGENTS.md`와 investigations 문서뿐이며,
현재 warning 67개 중 30개는 원격 최신 버전 메타데이터에 의존하는
`AndroidGradlePluginVersion`·`GradleDependency`·`OldTargetApi`, 나머지 37개는 비동적
진단이다. 같은 입력의 lint 재실행에서도 5E/67W가 재현됐다.

이번 실행은 숫자만 갱신하거나 경고를 억제하지 않고 변경 전 진단 전체를
`severity|issue id|relative path|line|message`로 정렬해 SHA-256 지문으로 고정했다.

- 전체: `7b729672602ac75a496051823bed682ca46ced008ee76116184df4b3c54414b2`
- 비동적: `37be71d347667f30403f5c31e007b205cc3815d43bf17d7f0282aba80d22a5d7`
- 동적: `0913db839ff13d6e3db0cfd60e70d0f0d0a9a626b0e7ceeacb1a1eca203054b8`

## C — elapsed 필드명 정정

- `lastSuspiciousCallEndedAt`을 `lastSuspiciousCallEndedElapsedMs`로 순수 rename.
- 선언·프로덕션 사용부·CallRiskMonitor KDoc·두 기존 테스트 참조만 동기화.
- 테스트 참조를 먼저 바꾼 RED에서 새 이름의 unresolved reference 12개를 확인.
- 프로덕션 rename 후 `RealCallRiskMonitorTest`와
  `RealCallRiskMonitorProvenanceTest` 표적 실행 GREEN.
- 런타임 식·시계 공급자·동시성·interface 시그니처 변경 없음.

## A — 복제 테스트를 실물 monitor 테스트로 대체

`TelebankingAnchorIdleLogicTest`의 `IdleAnchorReproducer` 7개 테스트를 삭제하고,
`RealCallRiskMonitorAnchorTest` 7개로 교체했다. 새 테스트는 mockk
Context/TelephonyManager와 `clock`·`monotonicClock` legacy callback seam을 통해 실물
`RealCallRiskMonitor.observeCallSignals()`의 IDLE 분기를 구동한다.

| 기존 분기 | 실물 테스트 |
|---|---|
| T-A1 동일 callId 안전 확인 시 anchor 미설정 | `T-A1 safe-confirmed same call idle skips anchor` |
| T-A2 다른 callId anchor 설정 | `T-A2 different call idle sets monotonic anchor` |
| T-A3 동일 callId IDLE 후 marker 자동 clear | `T-A3 matching idle clears safe marker for a later reused call id` |
| T-A3 보강 다른 callId가 marker 보존 | `T-A3 different idle preserves marker for its matching call` |
| T-A4 설정된 anchor clear | `T-A4 clear removes an armed anchor` |
| T-A4 보강 null clear no-op | `T-A4 clear is a no-op when anchor is null` |
| RINGING→IDLE의 null callId 매칭 불가·marker 보존 | `ringing to idle sets anchor and preserves unmatched safe marker` |

T-A2와 RINGING→IDLE은 wall과 monotonic clock을 서로 다른 값으로 구동하여 anchor가
`endedAtMillis`가 아니라 monotonic 종료 시각임을 함께 고정한다. marker clear/preserve는
private 필드 노출이나 reflection 없이 같은 callId를 재사용한 후 실제 anchor 결과로 관찰한다.

표적 첫 실행은 RINGING→IDLE에서 빈 신호 emission을 기다려 1/7 timeout이었다. 이 경로는
anchor 부수효과 후 seed와 같은 빈 목록이 `distinctUntilChanged`에서 억제되므로, 프로덕션을
바꾸지 않고 anchor 상태 조건 대기로 정정했다. 재실행은 7/7 GREEN.

## B — stale KDoc 정정

- `RiskSessionTracker.resetAfterUserConfirmedSafe` 프로덕션 호출부를 검색해
  `DefaultRiskDetectionCoordinator.confirmSafe` 한 곳임을 확인.
- Overlay·Home·Warning 진입점이 Coordinator command를 경유한다는 현재 구조로 KDoc 정정.
- 코드·동작 변경 없음.

## D — WarningViewModel 단언 복원

- behavior check 비침습 테스트에
  `refreshAnchorHotNowCallCount == 0` 단언을 복원.
- 기존 `captureCallCount == 0`, `confirmCallCount == 0`과 함께 monitoring command
  부수효과 0을 검증.
- `WarningViewModelTest` 표적 실행 GREEN.

## 테스트 회계 및 변경 후 완료 게이트

- 삭제: 복제 테스트 7개.
- 추가: 실물 monitor 테스트 7개.
- 순증감: 0.
- fresh 직렬 `clean testDebugUnitTest assembleDebug`: `BUILD SUCCESSFUL`.
- 테스트: 35 suites, 454 tests, failures/errors/skipped `0/0/0`.
- `lintDebug`: 5 errors / 67 warnings, 변경 전과 동일한 예상 nonzero 종료.
- 변경 후 lint 지문: 전체·비동적·동적 모두 변경 전과 동일 — 신규 진단 0.
- Manifest·permission·service·DI·navigation·repository·interface 시그니처 변경 없음.

최종 Git scope·`git diff --check`·staged 0 확인 후 Claude 독립검토로 raw handoff한다.

## Claude 독립검토 후속 보완

- Important: elapsed 필드 KDoc의 이전 필드명 호환 문구를 제거했다.
- Minor: `RealCallRiskMonitorAnchorTest`가 `sdkIntProvider = { Build.VERSION_CODES.R }`를 명시해 legacy callback seam을 고정하도록 보완했다.
- 보완 후 `RealCallRiskMonitorAnchorTest` 표적 실행: `BUILD SUCCESSFUL`.
- Claude scoped 재검토: `SPEC ✅`, `QUALITY APPROVED`, 기존 두 지적 모두 `ADDRESSED`,
  신규 Critical/Important 없음.
- 보완 후 fresh 직렬 `clean testDebugUnitTest assembleDebug`: `BUILD SUCCESSFUL`.
- 테스트: 35 suites, 454 tests, failures/errors/skipped `0/0/0`.
- 보완 후 `lintDebug`: 기존 예상 nonzero인 5 errors / 67 warnings 재현.
  변경 경로에 해당하는 lint 진단은 0이며 총계도 변경 전과 동일하다.
