# Domain Risk Module M1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 위험 정책 모델 6종을 동작·API·저장 호환성 변화 없이 `:domain:risk` Kotlin/JVM 모듈로 분리한다.

**Architecture:** `:app`이 순수 Kotlin/JVM 모듈 `:domain:risk`에 단방향 의존한다. 기존 package/FQCN과 enum 순서·이름·category를 그대로 유지하고 계약 테스트, standalone lint, Compose compiler metrics로 경계 이동 회귀를 검출한다.

**Tech Stack:** Gradle 8.7, AGP/Lint 8.5.2, Kotlin 1.9.24, JDK 21 실행, JVM bytecode 17, JUnit 4.13.2.

**Spec:** `investigations/2026-08-30-domain-risk-module-m1-t2/DIRECTIVE.md`

## Global Constraints

- 이동 대상은 `AlertState`, `RiskEvent`, `RiskLevel`, `RiskScore`, `RiskSignal`, `SignalCategory` 6개뿐이다.
- package/FQCN, public property, enum 이름·순서·category를 변경하지 않는다.
- Manifest, permission, DI, service, monitor, Navigation과 제품 동작을 변경하지 않는다.
- 신규 모듈 production dependency는 Kotlin 표준 라이브러리 외 0이다.
- Gradle은 JDK 21로 실행하고 bytecode는 17로 생성한다. `jvmToolchain(17)`은 금지한다.
- 기존 app lint 5E/67W에 신규 진단 0, app test 454개 미만 감소 금지, skipped 0이다.

---

### Task 1: 관측 기준선과 모듈 플러그인 preflight

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `investigations/2026-08-30-domain-risk-module-m1-t2/IMPL_LOG.md`

**Interfaces:**
- Consumes: 기존 `:app` Compose compiler 1.5.14 설정.
- Produces: `composeCompilerReportsDir` Gradle property가 있을 때만 metrics/reports를 생성하는 debug compiler 관측 설정과 baseline 지표.

- [ ] **Step 1: 조건부 Compose compiler 보고 설정 추가**

`app/build.gradle.kts`에 `composeCompilerReportsDir`가 지정된 경우에만 debug Kotlin compile의 `reportsDestination`과 `metricsDestination`을 같은 절대 경로로 전달한다. 일반 빌드의 compiler argument는 바꾸지 않는다.

- [ ] **Step 2: baseline metrics 생성**

Run: `./gradlew.bat --no-daemon --no-parallel --max-workers=1 :app:compileDebugKotlin --rerun-tasks -PcomposeCompilerReportsDir=C:/Users/momen/AndroidStudioProjects/Senior_Shield/.worktrees/domain-risk-module-m1/app/build/compose-baseline`

Expected: `*-module.json`, `*-composables.csv`, `*-classes.txt`가 생성되고 restartable/skippable/unstable 관련 수치를 `IMPL_LOG.md`에 기록한다.

- [ ] **Step 3: offline plugin cache 확인**

Kotlin JVM `1.9.24`와 AGP/Lint `8.5.2` 캐시를 확인한다. 캐시 미존재로 네트워크가 필요하면 S5로 중단한다.

### Task 2: RED 호환 계약과 모듈 골격

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `domain/risk/build.gradle.kts`
- Create: `domain/risk/src/test/kotlin/com/example/seniorshield/domain/model/RiskModelCompatibilityTest.kt`

**Interfaces:**
- Consumes: 기존 모델의 exact FQCN과 enum 계약.
- Produces: `:domain:risk:test`, `:domain:risk:lint`, bytecode 17의 순수 Kotlin/JVM 모듈.

- [ ] **Step 1: 모듈을 settings와 plugin management에 등록**

Root plugins에 `org.jetbrains.kotlin.jvm` `1.9.24`와 `com.android.lint` `8.5.2`를 `apply false`로 추가하고 `settings.gradle.kts`에 `include(":domain:risk")`를 추가한다.

- [ ] **Step 2: bytecode 17과 standalone lint 모듈 생성**

```kotlin
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("com.android.lint")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 3: 호환 계약 테스트 작성**

JUnit 테스트에서 6개 class의 FQCN, `AlertState`/`RiskLevel`/`SignalCategory`의 exact ordered names, `RiskSignal`의 exact ordered `name to category`, `RiskEvent`/`RiskScore` 생성자 사용을 고정한다.

- [ ] **Step 4: RED 확인**

Run: `./gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 :domain:risk:test`

Expected: 모듈에 production 모델이 아직 없으므로 unresolved reference로 실패한다. plugin resolution 실패는 테스트 RED가 아니라 S5다.

### Task 3: 위험 모델 이동과 app 단방향 의존

**Files:**
- Modify: `app/build.gradle.kts`
- Move: 대상 6개를 `app/src/main/java/.../domain/model/`에서 `domain/risk/src/main/kotlin/.../domain/model/`로 이동

**Interfaces:**
- Consumes: Task 2의 모듈과 호환 계약 테스트.
- Produces: 동일 FQCN의 위험 모델 jar와 `:app -> :domain:risk` 의존.

- [ ] **Step 1: app 의존 추가**

`app/build.gradle.kts` dependencies 첫 부분에 `implementation(project(":domain:risk"))`를 추가한다.

- [ ] **Step 2: 6개 파일을 내용 변경 없이 이동**

package, KDoc, 선언 본문을 바꾸지 않는다. `Guardian`, `PermissionStatus`, `PolicySummary`는 이동하지 않는다.

- [ ] **Step 3: 계약 GREEN 확인**

Run: `./gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 :domain:risk:test :domain:risk:lint`

Expected: tests GREEN, skipped 0, lint task 실행 및 진단 0.

- [ ] **Step 4: 연결·중복 검증**

Run: `./gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 :app:compileDebugKotlin :app:checkDebugDuplicateClasses`

Expected: 동일 FQCN consumer 수정 없이 compile 성공, duplicate class 0.

### Task 4: 아키텍처 문서 동기화

**Files:**
- Modify: `AGENTS.md`
- Modify: `investigations/2026-08-30-domain-risk-module-m1-t2/IMPL_LOG.md`

**Interfaces:**
- Consumes: 실제 Gradle module graph.
- Produces: `:app + :domain:risk` 구조, 책임과 금지 경계를 설명하는 최신 프로젝트 지침.

- [ ] **Step 1: AGENTS 구조 갱신**

`single module` 표현을 현재 2개 모듈로 바꾸고, 위험 모델 6종은 `:domain:risk`, 나머지 앱 모델과 repository는 `:app`에 있음을 명시한다. 제품 원칙 문구는 수정하지 않는다.

- [ ] **Step 2: 구현 로그 갱신**

각 gate의 명령, 종료 코드, test/lint/metrics 수치, bytecode major version을 기록한다.

### Task 5: fresh 완료 게이트와 리뷰 게시

**Files:**
- Modify: `investigations/2026-08-30-domain-risk-module-m1-t2/IMPL_LOG.md`

**Interfaces:**
- Consumes: Tasks 1-4 전체 결과.
- Produces: 독립 리뷰 가능한 commit/push/PR.

- [ ] **Step 1: fresh 직렬 전체 검증**

Run: `./gradlew.bat --no-daemon --no-parallel --max-workers=1 clean :domain:risk:check :app:testDebugUnitTest :app:assembleDebug :app:checkDebugDuplicateClasses`

Expected: BUILD SUCCESSFUL, app tests 454 이상, 전체 failures/errors/skipped 0.

- [ ] **Step 2: lint coverage와 지문 검증**

Run: `./gradlew.bat --no-daemon --no-parallel --max-workers=1 :domain:risk:lint :app:lintDebug`

Expected: domain lint 실제 실행·진단 0. app lint는 기존 5E/67W와 exact diagnostic fingerprint 동일.

- [ ] **Step 3: post Compose metrics와 bytecode 검증**

post metrics를 baseline과 비교해 restartable/skippable/unstable 지표 회귀 0을 확인한다. `javap -verbose`로 대상 class의 major version이 61인지 확인한다.

- [ ] **Step 4: 범위와 whitespace 검증**

Run: `git diff --check`

Expected: 출력 없음. 변경 파일이 DIRECTIVE 허용 목록 안에만 존재한다.

- [ ] **Step 5: 자체검토 후 커밋·push·PR**

정책, API/저장 호환, Gradle 경계, tests, lint, metrics를 전체 자체검토한다. 이후 명시적 파일만 stage하고 conventional commit으로 커밋해 `codex/domain-risk-module-m1`을 push하고 PR을 생성하되 merge하지 않는다.
