# Domain Contracts M2 지시문 — 순수 domain port 경계의 물리 모듈 분리

> **협업 규약 헤더 (2026-07-21 확정)**
> - **등급**: **T2 고위험** — repository interface와 Guardian 모델의 물리 모듈 경계를 변경한다.
> - **검토**: Codex 전체 자체검토 + Senior Shield 독립 아키텍처·정책 검토 + 독립 build/test/lint.
> - **정지·질의**: S2/S3/S4/S5가 실제 발생하면 전진을 멈춘다. 승인 범위 안에서 해소 가능한 S2는 review/fix/re-review로 닫고 재개한다. S3/S5는 범위를 넓히지 않으며, 미지의 제품·정책 결정인 S4는 사용자 판단 없이는 진행하지 않는다. 2026-09-05 사용자의 연속 진행 승인은 이 트랙의 branch/worktree, stage, commit, push, Ready PR 생성까지 포함한다. `main` 병합은 포함하지 않는다.

## 0. 현재 상태

- 기준 main: `7754ebf5bd457e7fabeb6e9357d178df95fd01e9` (Domain Risk M1 PR #9 병합 결과).
- 작업 브랜치: `codex/domain-contracts-m2`, 격리 worktree 사용.
- 현재 모듈: Android/Hilt `:app`과 순수 Kotlin/JVM `:domain:risk`.
- 기준선: JDK 21 실행, JVM bytecode 17, app 35 suites / 454 tests / failures 0 / errors 0 / skipped 0, domain:risk 6 tests / failures 0 / errors 0 / skipped 0.
- app lint 기준선: 기존 5 errors / 67 warnings. 신규 진단은 허용하지 않는다.
- 제품 동작, 위험 점수, 연락 흐름, 저장 형식, DI binding은 변경하지 않는다.

## 1. 승인된 5줄 계획

1. 수정 파일: M1 계약 테스트·로그, settings/app Gradle, 신규 contracts 모듈, Guardian와 repository interface 4개 이동, Compose 안정성 설정, AGENTS 및 이 트랙 문서만 수정한다.
2. 변경 목적: Android/Hilt와 무관한 domain port를 `:domain:contracts` Kotlin/JVM 모듈로 분리해 이후 data/feature 분리의 안전한 seam을 만든다.
3. 정책/권한 리스크: API·Flow/suspend 의미·SMS 유산 계약을 변경하거나 새 호출자를 만들지 않으며 Manifest, permission, service, monitor, navigation, DI 소스는 건드리지 않는다.
4. 테스트 방법: M1 불변성 mutation RED→GREEN, M2 미존재 계약 RED→GREEN, standalone lint, full app test/assemble/kapt/duplicate, app lint 지문, Compose pre/post canary, javap ABI/bytecode, diff 범위 검증.
5. 중단 조건: Android/Compose/Hilt 의존 필요, app 역의존/cycle, DI 또는 금지 표면 수정 필요, API/저장/Flow 의미 변화, 자동 SMS 재활성화, 신규 lint, Compose/API/test/build 회귀면 S3/S5로 중단한다.

## 2. 구조 결정

```text
:app ───────────────▶ :domain:risk
  └─────────────────▶ :domain:contracts ──api──▶ :domain:risk
                                         └─api──▶ kotlinx-coroutines-core
```

- `:domain:contracts`로 이동: `Guardian`, `RiskRepository`, `RiskEventSink`, `SettingsRepository`, `GuardianRepository`.
- `:app`에 유지: `PermissionType`, `PermissionStatus`, `PolicySummary`, repository 구현체, DataStore/Room/Hilt/feature/monitor/service 코드.
- package/FQCN과 모든 공개 시그니처는 유지한다.
- contracts는 `api(project(":domain:risk"))`와 `api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")`만 명시적 production dependency로 가진다.
- Android, AndroidX, Compose, Hilt, Kapt, `javax.inject`, `coroutines-android`를 contracts에 넣지 않는다.
- `:app`은 두 domain 모듈에 직접 의존한다. `:domain:risk`는 contracts를 참조하지 않는다.
- `DataModule`과 구현체는 FQCN 유지로 무수정이어야 한다. 수정 필요가 드러나면 S3다.
- 자동 SMS 유산 API `observe/setSmsAlertEnabled`는 이동만 하고 새 사용처·행동을 만들지 않는다. 제거는 별도 T2다.

## 3. 호환 계약

- `Guardian`: FQCN, 생성자 property 이름·타입·순서, `relationship = ""`, component 순서, 모든 `val`, `MAX_COUNT = 3`을 고정한다.
- repository 4종: interface FQCN, 함수명, 인자 순서·타입, nullability, 반환형, `suspend` 여부를 고정한다.
- `RiskEventSink.clearCurrentRiskEvent()`는 non-suspend다.
- `Flow`를 `StateFlow`나 property로 바꾸지 않으며 구현체의 cold/hot·저장 동작을 바꾸지 않는다.
- 원본과 이동본을 동시에 컴파일하지 않는다.
- M1 후속으로 `RiskEvent`와 `RiskScore` backing field final 및 setter 부재 계약을 추가한다. RED 증명 중 두 production 파일의 대표 property를 한 번씩 일시 mutation하는 것만 허용하되 commit은 금지한다. 각 파일 SHA-256을 사전 기록하고, 각 RED 뒤 즉시 복원하며, 최종 SHA 동일·production diff 0을 확인한다.
- repository 계약 테스트는 구현자 관점 exact-signature fake와 소비자 관점의 명시적 non-null/local-type 대입 harness를 모두 사용한다. `javap -public -s`의 공개 선언과 descriptor를 기준으로 보존한다. 출력의 generic 선언은 비교에 포함하고 Kotlin nullability는 consumer harness로 보완한다.

## 4. Compose canary

- 기존 stability config 4종에 exact FQCN `Guardian`만 추가한다. wildcard는 금지한다.
- `List<Guardian>`은 stable로 선언하지 않는다. `PermissionStatus`, `PermissionType`, `PolicySummary`도 이번 config에 추가하지 않는다.
- 비교 단계를 원본 P0, Guardian config 추가 P1, 이동 P2로 고정한다. P0/P1의 module JSON, composables CSV/TXT, classes TXT는 모두 exact 동일해야 한다.
- P1에서 이동 대상 중 실제 존재하는 class block 목록과 block별 SHA-256을 동결한 뒤 P1/P2를 비교한다. post-move UI metrics와 composables CSV/TXT는 P1과 exact 동일해야 한다.
- P1/P2 class report에서는 사전 동결한 이동 class block만 사라질 수 있고 공통 block은 exact 동일해야 한다. module JSON은 제거 block 분류로 설명되는 정확한 class-count delta 외 모든 필드가 동일해야 한다. 무관한 class 추가·삭제는 S5다.
- named anchor에서 `GuardianCard`의 `Guardian` 인자는 stable이고 restartable/skippable 상태가 유지되어야 한다.
- P0/P1 artifacts와 block hash는 `clean` 밖의 ignored SDD workspace에 보존하고 추적 IMPL_LOG에도 수치·SHA를 기록한다.

## 5. 허용 파일

- `settings.gradle.kts`
- `app/build.gradle.kts`
- `compose-stability.conf`
- `domain/risk/src/test/kotlin/com/example/seniorshield/domain/model/RiskModelCompatibilityTest.kt`
- `domain/risk/src/main/kotlin/com/example/seniorshield/domain/model/RiskEvent.kt`의 RED 증명용 일시 mutation만 허용(최종 diff 금지)
- `domain/risk/src/main/kotlin/com/example/seniorshield/domain/model/RiskScore.kt`의 RED 증명용 일시 mutation만 허용(최종 diff 금지)
- `domain/contracts/**`
- `app/src/main/java/com/example/seniorshield/domain/model/Guardian.kt` 삭제
- `app/src/main/java/com/example/seniorshield/domain/repository/` 아래 4개 interface 삭제
- `AGENTS.md`
- `investigations/2026-08-30-domain-risk-module-m1-t2/IMPL_LOG.md`
- `investigations/2026-09-05-domain-contracts-m2-t2/**`

그 외 source, root build, Manifest, permission, DI, service, monitoring, Navigation 변경은 S3다.

## 6. 완료 게이트

1. `:domain:risk:test`는 정확히 7 tests, `:domain:contracts:test`는 정확히 4 tests이며 failures/errors/skipped 0이다.
2. 두 domain 모듈 lint의 main source 분석이 실제 실행되고 진단 0.
3. fresh `clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses` GREEN.
4. app unit test 35 suites / 454 tests 이상, failures/errors/skipped 0.
5. metadata-access `:app:lintDebug`는 기존 5 errors 때문에 expected nonzero다. 72개 진단은 issue id, severity, message, relative path, source anchor를 보존한다. worktree 절대 prefix, line/column, update-check latest-version 값만 정규화했을 때 M1 canonical SHA-256 `8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD`와 같고 normalized multiset 차이 0이어야 한다.
6. Compose canary가 §4를 만족한다.
7. 이동 class/interface의 `javap -public -s` 공개 멤버 투영은 generic 선언과 descriptor를 포함해 pre/post 동일하다. 사전 출력에서 app Compose compiler가 Guardian에 생성한 `public static final int $stable` 및 바로 뒤 descriptor `I` 블록만 제거한 결과와 post 출력을 비교한다. 별도 `javap -v` 검사는 class별 `major version`과 class-level `StabilityInferred(parameters=1)` 존재 여부만 추출한다. Guardian의 `$stable:I`와 `StabilityInferred` 제거는 예상 compiler-boundary delta이며, path/timestamp/size/checksum/constant-pool 번호/bytecode index는 API 비교 입력에 넣지 않는다. contracts main의 `Guardian$Companion`을 포함한 전체 class inventory가 major version 61이다.
8. contracts main source에 Android/AndroidX/Compose/Hilt/Inject import 0, `:domain:risk -> :domain:contracts` 역의존 0, duplicate class 0.
9. `git diff --check`, 허용 경로 audit, 독립 최종 리뷰를 통과한다.
10. 브랜치를 push하고 Ready PR을 생성하되 merge하지 않는다.
11. `DataModule.kt`, `RiskRepositoryImpl.kt`, `SettingsRepositoryImpl.kt`, `GuardianRepositoryImpl.kt`, `RoomRiskEventStore.kt`의 SHA-256이 pre/post 동일하다. `observeSmsAlertEnabled`/`setSmsAlertEnabled`의 interface 선언과 implementation override를 제외한 production invocation site는 baseline 0/post 0이다.
