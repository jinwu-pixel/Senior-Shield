# Domain Risk 모듈 canary 지시문 — 위험 정책 모델 6종의 물리 경계 검증

> **협업 규약 헤더 (2026-07-21 확정)**
> - **등급**: **T2 고위험** — 위험 신호·경고 상태 모델의 모듈 경계를 변경한다.
> - **검토**: Codex 전체 자체검토 + Senior Shield 독립 정책 검토 + 독립 build/test/lint.
> - **정지·질의**: S3 또는 S5가 실제 발생할 때만 중단한다. 2026-08-30 사용자의 연속 진행 승인은 이 트랙의 branch/worktree, stage, commit, push, PR 생성까지 포함한다. `main` 병합은 포함하지 않는다.

## 0. 현재 상태

- 기준 main: `e5af6f7946937e1f173dee17b72e5e9d222d8b42` (Task 0 PR #8 병합 결과).
- 작업 브랜치: `codex/domain-risk-module-m1`, 격리 worktree 사용.
- 기준선: JDK 21 실행, JVM bytecode 17, app unit test 35 suites / 454 tests / failures 0 / errors 0 / skipped 0.
- lint 기준선: 기존 진단 5 errors / 67 warnings. 신규 진단은 허용하지 않는다.
- 제품 동작, 위험 점수, 경고 발화, 저장 형식은 변경하지 않는다.

## 1. 승인된 5줄 계획

1. 수정 파일: `settings.gradle.kts`, `app/build.gradle.kts`, 신규 모듈 빌드 설정과 Compose 안정성 설정, 위험 모델 6개 이동, 호환 계약 테스트, `AGENTS.md`, 이 트랙 문서와 `.gitignore`만 수정한다.
2. 변경 목적: `:app` 내부의 응집된 순수 위험 정책 모델을 `:domain:risk` Kotlin/JVM 모듈로 분리해 작은 물리 모듈 canary를 검증한다.
3. 정책/권한 리스크: enum 이름·순서·category·FQCN 변경은 정책 및 저장 호환성 변경이므로 금지한다. Manifest, permission, DI, service, monitor, Navigation은 건드리지 않는다.
4. 테스트 방법: 계약 테스트 RED→GREEN, `:domain:risk:test`, `:domain:risk:lint`, app 454 tests 유지, assemble/checkDuplicateClasses, lint 지문·Compose metrics 전후 비교, bytecode 17, `git diff --check`.
5. 중단 조건: 허용 파일 밖 변경 필요, 6개 외 모델 이동 필요, Android/Compose 의존 필요, 역방향/순환 의존, API·enum 의미 변경, 신규 lint, lint coverage 미입증, Compose 안정성 회귀, test/build RED면 S3/S5로 중단한다.

## 2. 구조 결정

```text
:app ───────────────▶ :domain:risk
Android/Compose/Hilt    Kotlin/JVM, bytecode 17
```

- `:domain:risk`로 이동: `AlertState`, `RiskEvent`, `RiskLevel`, `RiskScore`, `RiskSignal`, `SignalCategory`.
- `:app`에 유지: `Guardian`, `PermissionStatus`, `PolicySummary`, 모든 repository/interface/monitor/service/feature 코드.
- package와 FQCN은 `com.example.seniorshield.domain.model.*`로 유지한다.
- 신규 모듈 production dependency는 Kotlin 표준 라이브러리 외 0이다.
- `:app`만 `implementation(project(":domain:risk"))`로 의존한다.
- JDK 21로 Gradle을 실행하되 `jvmTarget=17`을 사용한다. `jvmToolchain(17)`은 사용하지 않는다.
- root `build.gradle.kts`에는 `org.jetbrains.kotlin.jvm` 또는 `com.android.lint`의 versioned plugin 선언을 추가하지 않는다. 기존 root classpath를 재사용해 모듈에서 versionless plugin을 적용한다.
- `settings.gradle.kts`의 모듈 변경은 `include(":domain:risk")`만 허용한다.
- `domain/risk/build.gradle.kts`의 plugin은 versionless `java-library`, `id("org.jetbrains.kotlin.jvm")`, `id("com.android.lint")`만 사용한다.
- 오래된 version catalog의 AGP/Kotlin 값은 사용하지 않고 기존 AGP `8.5.2`, Kotlin `1.9.24` 구현을 유지한다.
- Task 2의 offline RED는 plugin configuration을 통과한 뒤 대상 6개 모델의 unresolved reference만으로 실패해야 한다. plugin resolution/configuration 실패 또는 다른 원인의 실패는 S5다.
- `compose-stability.conf`에는 불변 enum 4종 `AlertState`, `RiskLevel`, `RiskSignal`, `SignalCategory`의 exact FQCN만 등록한다. collection property가 있는 `RiskEvent`, `RiskScore`는 등록하지 않는다.
- debug Compose metrics는 production 성능의 절대 평가가 아니라 같은 variant·compiler 조건의 전후 상대 canary로만 사용한다.

## 3. 호환 계약

- `AlertState`: `OBSERVE, GUARDED, INTERRUPT, CRITICAL` 순서를 고정한다.
- `RiskLevel`: `LOW, MEDIUM, HIGH, CRITICAL` 순서를 고정한다.
- `SignalCategory`: `PASSIVE, AMPLIFIER, TRIGGER` 순서를 고정한다.
- `RiskSignal` 이름과 category 매핑을 현재 값 그대로 고정한다. Room의 `name` 문자열 저장과 ordinal 비교를 보존한다.
- `RiskEvent`와 `RiskScore`의 생성자 property 이름·타입·순서를 그대로 유지한다.
- 원본과 이동본이 동시에 컴파일되지 않게 하고 `checkDebugDuplicateClasses`로 확인한다.

## 4. 허용 파일

- `.gitignore`
- `settings.gradle.kts`
- `app/build.gradle.kts`
- `compose-stability.conf`
- `domain/risk/build.gradle.kts`
- `app/src/main/java/com/example/seniorshield/domain/model/` 아래 대상 6개 삭제
- `domain/risk/src/main/kotlin/com/example/seniorshield/domain/model/` 아래 대상 6개 생성
- `domain/risk/src/test/kotlin/com/example/seniorshield/domain/model/RiskModelCompatibilityTest.kt`
- `AGENTS.md`
- `investigations/2026-08-30-domain-risk-module-m1-t2/` 아래 문서

그 외 source, Manifest, permission, DI, service, monitor interface, Navigation 변경은 S3다.

## 5. 완료 게이트

1. `:domain:risk:test` GREEN, skipped 0.
2. `:domain:risk:lint` task가 실제 생성·실행되고 신규 진단 0.
3. fresh `clean :domain:risk:check :app:testDebugUnitTest :app:assembleDebug :app:checkDebugDuplicateClasses` GREEN.
4. app unit test가 35 suites / 454 tests 이상이며 failures/errors/skipped 0.
5. `:app:lintDebug`가 기존 5E/67W 지문 대비 신규 진단 0.
6. 안정성 설정 적용 직전과 적용 후의 동일 debug Compose canary가 정확히 `226 total / 225 restartable / 142 skippable / 36 known unstable arguments / 49 inferred unstable classes / 89 total classes`를 유지하고, 모델 이동 후에도 해당 상대 지표 회귀가 0이다.
7. 위험 모델 class major version 61(Java 17), 중복 class 0.
8. `git diff --check` 통과, 허용 경로 외 변경 0.
