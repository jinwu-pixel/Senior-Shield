# Domain Contracts M2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** M1 후속 계약을 마감하고, `Guardian`과 repository interface 4종을 동작·API·저장 호환성 변화 없이 `:domain:contracts` Kotlin/JVM 모듈로 분리한다.

**Architecture:** `:app`은 `:domain:risk`와 `:domain:contracts`에 직접 의존하고, contracts는 공개 API 타입 때문에 risk와 coroutines-core에 `api` 의존한다. Android/Hilt 구현체와 UI 표현 모델은 app에 남는다.

**Tech Stack:** Gradle 8.7, AGP/Lint 8.5.2, Kotlin 1.9.24, Coroutines 1.8.1, JDK 21 실행, JVM bytecode 17, JUnit 4.13.2.

**Spec:** `investigations/2026-09-05-domain-contracts-m2-t2/DIRECTIVE.md`

## Global Constraints

- 이동 대상은 `Guardian`, `RiskRepository`, `RiskEventSink`, `SettingsRepository`, `GuardianRepository` 5개 파일뿐이다.
- `PermissionType`, `PermissionStatus`, `PolicySummary`와 모든 구현체·DI source는 app에 남긴다.
- package/FQCN, public API, default, `val`, Flow/suspend/nullability 의미를 변경하지 않는다.
- Manifest, permission, DI, service, monitor, Navigation과 제품 동작을 변경하지 않는다.
- contracts production dependency는 `api(project(":domain:risk"))`와 `api(kotlinx-coroutines-core:1.8.1)`만 허용한다.
- Gradle은 JDK 21, serial workers, JVM target 17이다. root build와 versioned plugin 선언은 변경하지 않는다.
- 자동 SMS API는 이동만 하며 활성화·호출 추가·삭제하지 않는다.
- app lint 기존 5E/67W에 신규 진단 0, app tests 454 미만 감소 금지, skipped 0이다.

---

### Task 1: M1 후속 불변성 계약과 최종 증거

**Files:**
- Modify: `domain/risk/src/test/kotlin/com/example/seniorshield/domain/model/RiskModelCompatibilityTest.kt`
- Modify: `investigations/2026-08-30-domain-risk-module-m1-t2/IMPL_LOG.md`

**Interfaces:**
- Consumes: 현재 `RiskEvent`/`RiskScore` data-class API와 PR #9 최종 검증 산출물.
- Produces: backing field final/setter 부재 회귀 계약과 HEAD의 6-test 감사 기록.

- [ ] PR #9 병합 HEAD의 domain 6 tests와 post-merge full gate 결과를 최종 섹션으로 기록한다. 기존 5-test 기록은 당시 역사로 유지한다.
- [ ] `RiskEvent`와 `RiskScore`의 모든 production property backing field가 final이고 Java setter가 없음을 검사하는 테스트를 먼저 작성한다.
- [ ] `RiskEvent.kt`와 `RiskScore.kt`의 SHA-256을 먼저 기록한다. 각 모델의 대표 property를 `val -> var`로 한 번씩 일시 mutation해 새 테스트가 RED임을 각각 확인한 뒤 즉시 원본으로 복원한다. finally 성격의 복원 검증으로 두 SHA 동일, 두 production 파일 diff 0을 확인하며 production 변경은 commit하지 않는다.
- [ ] `:domain:risk:test --rerun-tasks`와 `:domain:risk:lint --rerun-tasks --info`를 실행해 7 tests, failures/errors/skipped 0과 lint 0을 확인한다.

### Task 2: M2 Compose/ABI 기준선과 RED contracts 골격

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `compose-stability.conf`
- Create: `domain/contracts/build.gradle.kts`
- Create: `domain/contracts/src/test/kotlin/com/example/seniorshield/domain/ContractCompatibilityTest.kt`
- Modify: `investigations/2026-09-05-domain-contracts-m2-t2/IMPL_LOG.md`

**Interfaces:**
- Consumes: app 내부 이동 전 class/API와 M1 Compose 관측 설정.
- Produces: pre-move ABI/Compose 기준선, pure JVM contracts skeleton, 미존재 선언 RED.

- [ ] 이동 5개 대상의 `javap -public -s` 공개 선언·generic 표기·descriptor 기준을 캡처한다. 별도 `javap -v`에서 major version과 class-level `StabilityInferred(parameters=1)`만 추출한다. `DataModule.kt`, `RiskRepositoryImpl.kt`, `SettingsRepositoryImpl.kt`, `GuardianRepositoryImpl.kt`, `RoomRiskEventStore.kt`의 SHA-256도 캡처한다. 원본 P0 Compose report/metrics를 생성해 수치, artifact SHA, class block별 SHA, named anchors를 ignored SDD workspace와 IMPL_LOG에 기록한다.
- [ ] stability config에 exact `Guardian` FQCN 하나만 추가해 P1 report를 만든다. P0/P1 module JSON, composables CSV/TXT, classes TXT가 모두 byte-identical이고 `GuardianCard` 계약을 유지하는지 확인한다. P1에서 이동 대상 중 실제 존재하는 class block 집합과 block별 SHA를 사전 동결한다.
- [ ] settings에 `:domain:contracts`를 등록하고 versionless `java-library`, Kotlin JVM, Android Lint, bytecode 17 모듈을 만든다.
- [ ] contracts에 `api(project(":domain:risk"))`, `api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")`, test-only JUnit을 선언한다.
- [ ] 정확히 4개의 JUnit test로 Guardian FQCN/default/component/constant/immutability와 repository 4종 FQCN·공개 계약을 고정한다. 구현자 관점 exact-signature fake와 소비자 관점의 명시적 non-null/local-type 대입 harness를 함께 두고, `clearCurrentRiskEvent()`는 일반 test 함수에서 직접 호출해 non-suspend를 고정한다.
- [ ] `:domain:contracts:test`가 plugin/dependency configuration을 통과한 뒤 이동 대상 unresolved reference만으로 RED인지 확인한다.

### Task 3: Guardian·repository interface 이동과 GREEN

**Files:**
- Modify: `app/build.gradle.kts`
- Move: `Guardian.kt`와 repository interface 4개를 app에서 `domain/contracts/src/main/kotlin/...`로 이동
- Modify: `investigations/2026-09-05-domain-contracts-m2-t2/IMPL_LOG.md`

**Interfaces:**
- Consumes: Task 2 module/test and exact source declarations.
- Produces: `app -> contracts -> risk`와 `app -> risk` 단방향 graph.

- [ ] app에 `implementation(project(":domain:contracts"))`를 추가하고 기존 risk 직접 의존을 유지한다.
- [ ] 5개 파일을 package/KDoc/declaration 변경 없이 이동한다. consumer import, 구현체, DataModule은 수정하지 않는다.
- [ ] `:domain:contracts:test :domain:contracts:lint :domain:risk:check` GREEN과 skipped 0, contracts main lint source 분석·진단 0을 확인한다.
- [ ] `:app:compileDebugKotlin :app:kaptDebugKotlin :app:checkDebugDuplicateClasses` GREEN을 확인한다.
- [ ] imports/Gradle graph를 검사해 contracts Android/Hilt/Compose/Inject 0, risk 역의존 0을 확인한다.
- [ ] `:domain:contracts:dependencies --configuration api` 출력에서 risk와 coroutines-core가 실제 `api`에 노출되는지 확인한다.

### Task 4: 문서 동기화와 post-move canary

**Files:**
- Modify: `AGENTS.md`
- Modify: `investigations/2026-09-05-domain-contracts-m2-t2/IMPL_LOG.md`

**Interfaces:**
- Consumes: 실제 3-module graph와 Task 2 pre-move canary.
- Produces: 최신 아키텍처 문서와 post-move 상대 회귀 증거.

- [ ] AGENTS 모듈 구조를 `:app + :domain:risk + :domain:contracts`로 갱신하고 책임·의존 방향·UI 모델 잔류를 명시한다. 제품 원칙은 수정하지 않는다.
- [ ] P2 post-move Compose report를 같은 조건으로 생성한다. P1과 UI metrics/composables CSV/TXT를 exact 유지한다. B+ projection은 Guardian 제거 1/추가 0, 공통 78 block exact, 열거된 8 class의 11 repository field `runtime -> unstable`, OnboardingViewModel result 전이, exact 5-field module JSON delta만 허용한다. 영향받는 type parameter는 0이어야 한다.
- [ ] `GuardianCard`가 restartable/skippable이고 `Guardian` 인자가 stable인지 확인한다.
- [ ] P1/P2 4종 report와 ABI pre를 추적 evidence로 byte-preserve하고 P1 raw SHA를 validator에 동결한다. validator는 JSON key를 양방향 검사하고 class omission/duplicate를 거부하며, optional `EvidenceRoot`로 fresh post reports를 받을 수 있어야 한다. P2-only JSON key와 미승인 field transition mutation probe가 모두 RED인지 확인한다.
- [ ] 이동 대상의 post `javap -public -s`가 공개 선언·generic 표기·descriptor까지 pre projection과 동일한지 기록한다. pre에서 exact `Guardian.$stable:I` block만 제거한다. 별도 `javap -v`로 Guardian의 class-level `StabilityInferred(parameters=1)` 제거를 기록하고, path/timestamp/size/checksum/constant-pool/bytecode index는 비교하지 않는다. `Guardian$Companion`을 포함한 contracts main 전체 class의 major version 61을 확인한다.
- [ ] 위에 열거한 DI/implementation 5개 파일 SHA가 pre와 동일한지 확인한다. `observeSmsAlertEnabled`/`setSmsAlertEnabled`의 interface 선언과 implementation override를 제외한 production invocation site가 baseline 0/post 0인지 확인한다.
- [ ] 현재 compiler reports에서 recomposition contract 회귀가 검출되지 않았다고만 판정한다. runtime recomposition과 generated-bytecode identity는 이번 metadata gate의 측정 대상이 아니다.
- [ ] 문서·추적 evidence·validator/probe patch를 독립 검토로 넘기고 멈춘다. 수정된 DIRECTIVE 검토 전에는 Task 5를 실행하지 않는다.

### Task 5: fresh 완료 게이트·독립 리뷰·Ready PR

**Files:**
- Modify: `investigations/2026-09-05-domain-contracts-m2-t2/IMPL_LOG.md`

**Interfaces:**
- Consumes: Tasks 1-4 전체 결과.
- Produces: 독립 리뷰 가능한 clean commit series와 Ready PR.

- [ ] JDK 21/serial로 fresh `clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses`를 실행한다.
- [ ] XML에서 app 35 suites/454 tests 이상, domain:risk 정확히 7 tests, domain:contracts 정확히 4 tests, 전체 failures/errors/skipped 0을 집계한다.
- [ ] 두 domain lint가 main source를 분석해 0 issues인지 확인한다. metadata-access app lint는 기존 5 errors 때문에 expected nonzero이며, 72 diagnostics의 issue/severity/message/relative path/source anchor를 보존하고 worktree prefix·line/column·latest-version 값만 정규화한 multiset이 canonical SHA `8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD`와 동일하고 차이 0인지 비교한다.
- [ ] Compose/ABI/bytecode/import/graph/duplicate/allowlist/`git diff --check` 완료 게이트를 재확인한다.
- [ ] 전체 브랜치 독립 Senior Shield 리뷰에서 Critical/Important 0과 정책 통과를 확인한다.
- [ ] 명시 파일만 stage·commit하고 `codex/domain-contracts-m2`를 push해 Ready PR을 생성한다. merge하지 않는다.
