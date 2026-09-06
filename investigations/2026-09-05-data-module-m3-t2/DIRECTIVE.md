# Data Module M3 지시문

> **등급: T2 고위험** — 실제 저장 구현, Hilt 모듈, Android library 경계.
> **검토:** 독립 계획 검토 → 저장 계약 검증 → 이동 → full 자체검토/검증 → 독립 최종 리뷰.
> **S1~S5:** 사용자의 2026-09-05 “남은 작업들 제안대로 완료”는 기존 FOLLOW_UP_PLAN의 저장 검증/M3 구현, 격리 브랜치·commit·push·Ready PR를 승인한다. main 병합은 별도 응답 전까지 금지. 저장 형식/권한/제품 정책/동시성 의미가 달라지면 중단·범위 재검토한다.

## 기준과 실행 분리

- clean worktree `codex/data-module-m3`, 기준 M2 `0d7b30990bf3f08eaf8d56b220e41019b9996c3a`.
- main 병합 응답을 기다리는 동안 검증된 M2 커밋에서 stacked 작업한다. 권한 안전성 T2와 source를 공유하지 않는 별도 브랜치다. Gradle은 부모 실행자 1명이 직렬 실행하며 다른 worktree의 실행 중에 두 번째 Gradle을 시작하지 않는다.
- 같은 M2 source의 최근 fresh baseline은 app454 + risk7 + contracts4, 실패/오류/skip0. 이 트랙에서도 저장 테스트 도입 전후와 이동 후 결과를 새로 기록한다.
- 설계 근거: `investigations/2026-09-05-domain-contracts-m2-t2/FOLLOW_UP_PLAN.md` B. 순수 JVM data는 선택하지 않는다.

## 5줄 계획

1. 수정 파일: 아래 허용목록의 test infrastructure/저장 테스트, 신규 Android `:data` 구성, 12개 source와 schema의 1:1 이동, AGENTS/ignore/문서. app의 composition root는 유지한다.
2. 목적: 실제 저장 계약을 검증한 뒤 data 구현 및 Hilt 모듈을 Android library로 옮겨 domain port를 소비하는 물리 경계를 만든다.
3. 정책·권한: source 본문과 package/FQCN, 저장 포맷·Flow·dispatcher·singleton·migration 의미를 유지한다. 새 권한·service·자동 연락·monitor·navigation 변경 금지. 테스트는 사용자 데이터가 없는 전용 임시 emulator만 사용한다.
4. 검증: 실제 Room/DataStore 및 Hilt binding 테스트를 이동 전/후 동일하게 실행, source/schema/ABI/Compose/manifest/classpath 보존, full unit/build/kapt/duplicate/lint, 독립 리뷰.
5. 중단: 저장 스키마/키/파일명/enum/Flow 의미 변화, app 역의존/cycle, 중복 binding/class, 실제 저장 검증 실패, 미설명 Compose/ABI 변화, 승인된 진단 외 신규 lint. 테스트를 mock으로 바꿔 통과시키지 않는다.

## production 이동 허용목록

원본 root `app/src/main/java/com/example/seniorshield/data/` → 목적 root `data/src/main/java/com/example/seniorshield/data/`.

1. `local/GuardianDataStore.kt`
2. `local/SettingsDataStore.kt`
3. `local/LiveRiskEventStore.kt`
4. `local/RoomRiskEventStore.kt`
5. `local/db/RiskEventEntity.kt`
6. `local/db/RiskEventDao.kt`
7. `local/db/SeniorShieldDatabase.kt`
8. `repository/GuardianRepositoryImpl.kt`
9. `repository/SettingsRepositoryImpl.kt`
10. `repository/RiskRepositoryImpl.kt`
11. `di/DataModule.kt`
12. `di/DatabaseModule.kt`

추가 허용:
- `build.gradle.kts`: `com.android.library` 8.5.2 apply false 한 플러그인.
- `settings.gradle.kts`: `include(":data")`.
- `data/build.gradle.kts`, 필요할 경우 permission/component 없는 `data/src/main/AndroidManifest.xml`.
- `app/build.gradle.kts`: :data 의존, Room kapt/processor schema 소유권 이전, 아래 테스트 의존/runner 설정. 기존 runtime 의존성의 불필요한 정리는 하지 않는다.
- `app/schemas/com.example.seniorshield.data.local.db.SeniorShieldDatabase/1.json` → `data/schemas/`의 동일 경로.
- `.gitignore`: `/data/build/`; AGENTS 모듈 구조 동기화; 이 investigation 문서/검증 도구/증거.
- `app/src/androidTest/java/com/example/seniorshield/test/M3TestRunner.kt` 및 `app/src/androidTest/java/com/example/seniorshield/data/`의 저장/DI 테스트와 테스트 전용 DB module.

그 외 production source, domain 모듈, app Manifest, permission/service/monitor/navigation/dispatcher provider는 변경하지 않는다.

## 저장 및 주입 불변 계약

- DB FQCN `com.example.seniorshield.data.local.db.SeniorShieldDatabase`, file `senior_shield.db`, version1, exportSchema=true, schema identityHash `bec47e2ef0393e24083a677a42dcbf74`.
- 기존 `fallbackToDestructiveMigration()` 유지; migration/version 변경 없음.
- Room DAO REPLACE, 시간 내림차순 최근50개, count `>= sinceMillis`. 동일 timestamp의 순서는 새로 보장하지 않는다.
- level/signals는 enum name 문자열; 잘못된 level LOW fallback, 미지 signal 생략, 빈 signals 빈 목록.
- currentEvent 메모리 초기null. push DB+current / record DB만 / update current만 / clearCurrent null만 / clearAll DB삭제+null. recentEvents의 Eagerly StateFlow 및 scope/dispatcher 변화 없음.
- Settings store `senior_shield_settings`; keys `onboarding_completed`, `sms_alert_enabled`, `test_mode_enabled`, `sms_menu_enabled` 각 default=false.
- Guardian store `guardian_store`, key `guardians_json`, JSON id/name/phoneNumber/relationship, default relationship 빈 문자열, malformed JSON/entry 기존 처리, MAX_COUNT3.
- Guardian add/remove의 기존 read-modify-write 원자성 부채는 이 트랙에서 고치거나 보장된다고 주장하지 않는다.
- Hilt DataModule binding5, DatabaseModule provider2, singleton 유지. RiskEventSink와 LiveRiskEventStore가 같은 RoomRiskEventStore 인스턴스여야 한다.
- `app/di/AppModule.kt` CoroutineDispatcher provider 무변경, Hilt app composition root 유지.
- legacy SMS API는 신규 production 호출0. 테스트에서 key 저장을 검사하는 것은 발송 기능이 아니며 외부 연락을 실행하지 않는다.

## 테스트 환경

- test-only: runner1.7.0/core1.7.0/ext:junit1.3.0, hilt-android-testing2.52 및 kaptAndroidTest hilt compiler2.52. 공식 POM audit에서 Kotlin1.9.24/coroutines1.8.1과 호환되는 graph 확인; 실제 컴파일로 확정한다. core-ktx/espresso/Room-testing 추가 없음.
- custom AndroidJUnitRunner `newApplication`은 `dagger.hilt.android.testing.HiltTestApplication`을 선택해 SeniorShieldApp의 monitoring 초기화를 실행하지 않는다. production Application은 수정하지 않는다.
- 현재 사용자 데이터 없는 새 임시 AVD `SeniorShieldM3_e53e5b3e`, serial `emulator-5580`, API34, 프로젝트 내부 `.superpowers/m3-preparation/runtime/`에만 userdata. WHPX 사용 가능, boot_completed=1, SeniorShield 설치 package0 확인.
- 개인 AVD/실제 기기에 연결·설치·삭제하지 않는다. 매 실행 전 `adb -s emulator-5580 emu avd name`이 위 이름인지 확인한다. 새 emulator를 다시 만드는 경우 fresh runtime 경로와 새 이름을 기록하고 이후 명령을 그 serial에만 한정한다.
- Hilt binding 테스트는 production DataModule을 그대로 쓰며 test-only `@TestInstallIn(replaces=[DatabaseModule::class])`에서 실제 in-memory Room과 DAO를 제공한다. 같은 테스트에서 실제 DatabaseModule provider가 호출되지 않게 하고 별도 파일 재오픈 검사로 file persistence를 검증한다.
- Room 재오픈은 `m3-<UUID>.db`만 사용하고 그 DB만 정리한다. `senior_shield.db` 또는 다른 DB를 삭제하지 않는다.
- DataStore delegate는 프로세스별 singleton을 캐시하고 첫 초기화에 applicationContext를 사용한다. suite-wide 단일 ContextWrapper가 applicationContext로 자기 자신을 반환하고 고정된 격리 filesDir를 제공하도록 한다. 두 delegate를 처음부터 이 wrapper로만 초기화한다. 각 테스트 전 같은 활성 instance에서 `edit { clear() }`로 key를 reset하고 살아 있는 파일을 삭제하지 않는다. Hilt identity 테스트는 risk store/sink/repository만 주입해 Settings/Guardian delegate가 실제 app context에서 먼저 초기화되지 않게 한다. Settings/Guardian 구현 생성자는 위 suite wrapper만 사용한다. 프로세스 재오픈은 이번 테스트로 주장하지 않는다.

## Task1 — 실제 저장 계약과 기준선 동결

1. 12개 source SHA와 14개 public type/facade ABI, schema SHA/identity, Compose4종, merged manifest, lint72 기준선을 동결한다. 기존 증거를 새 실행으로 오인하지 않도록 실행 SHA를 기록한다.
2. 먼저 아래 실제 계약 테스트와 runner를 작성한다. production source 이동/수정은 테스트 실행 전 금지한다.
3. 실제 emulator에서 APK build/install/instrumentation을 serial 한정 실행하고 원본 source의 저장 동작을 characterize한다. `:data` 프로젝트 미존재 실패를 이동 작업의 RED로 기록하며, 기대값만 틀리게 만든 실패를 제품 회귀 탐지 증거로 주장하지 않는다.

필수 입력/기대:
- same id old10→new20: 행1/title new; timestamp1..51: 최근51..2 50개; since50=2/51=1/52=0.
- UUID DB close/reopen 후 같은행 유지 및 identityHash동일.
- HIGH/UNKNOWN_CALLER roundtrip, invalidlevel→LOW, invalidsignal생략.
- pushA→DB A/currentA, recordB→DB B/currentA, updateC→currentC/DB C없음, clearCurrent→null/DB보존, clearAll→DB0/null.
- Settings 네key defaultfalse/truefalse왕복; 별도 repository 인스턴스 같은파일 관측.
- Guardian3개허용/4번째거부/remove/JSON필드/기본값/invalid entry.
- 실제 Hilt Sink===LiveStore; 해당 sink를 통해 저장한 값이 주입받은 RiskRepository에서 관측됨.

테스트는 app androidTest에 유지하며 moved data public type을 직접 소비한다. 데이터 모듈 이동이 없는 시점의 테스트 통과 결과를 반드시 먼저 확보한다.

## Task2 — 12개 source 및 processor 이동

- data Android library: namespace `com.example.seniorshield.data`, compileSdk34/minSdk26/JVM17, Kotlin1.9.24/Hilt2.52/kapt. Compose plugin/build feature는 도입하지 않는다.
- `api(project(":domain:contracts"))`, `api(project(":domain:risk"))`; 기존 Room2.6.1, DataStore1.1.1, coroutines-android1.8.1, Hilt2.52/javax.inject1을 필요한 implementation/kapt로 고정. app의 기존 runtime deps는 유지한다.
- 12개 source는 FQCN/본문 무변경 이동, schema byte-preserve 이동. Room compiler 및 `room.schemaLocation`만 app→data로 소유권 이전한다. app Hilt compiler/plugin은 유지한다.
- Task1 동일 저장/Hilt 테스트를 재실행하고 source/schema/ABI를 비교한다.

## Compose 및 공개 ABI 게이트

- Task1 실제 compileDebugKotlin에 `-PcomposeCompilerReportsDir=<ignored>/compose-before --rerun-tasks`를 주고 4종을 동결한다. 이동 뒤는 별도 compose-after에 생성한다.
- 기본은 exact 비교. app report에서 이동 source에 속하는 10개 block만 제거될 것으로 조사됐다: DataModule, DatabaseModule, GuardianKeys, SettingsKeys, RoomRiskEventStore, RiskEventEntity, SeniorShieldDatabase, GuardianRepositoryImpl, RiskRepositoryImpl, SettingsRepositoryImpl. Task1에서 각 block hash/list를 실제 동결한다.
- 추가 block0, 그 밖의 공통 block exact; composables CSV/TXT exact. module 지표는 제거10개 class의 안정성 분류 감소만 동결 목록으로 설명해야 하며 값이나 key를 일반 허용하지 않는다. 사전 예측 외 delta는 먼저 독립 검토한다. repository 또는 data 타입을 stability config에 새로 넣지 않는다.
- javap의 14개 public type/facade signature와 generic/descriptor를 비교한다. Compose가 생성했던 `$stable:I` 필드만 exact 제거 projection을 허용한다. 그 밖 public Kotlin/Java 계약 변화 금지. 모든 class major61.
- runtime recomposition/전화 UI 실표시는 이 트랙에서 측정했다고 주장하지 않는다.

## Task3 — 전체 검증·리뷰·게시

1. JDK21, 한 Gradle 실행자, --no-daemon --no-parallel --max-workers=1 --console=plain.
2. fresh clean domain check, app testDebugUnitTest, data/app kaptDebugKotlin, app assembleDebug/checkDebugDuplicateClasses, app assembleDebugAndroidTest; 기존465 및 실제 instrumentation 테스트 실패/오류/skip0.
3. app/data lintDebug와 domain lint source 실제 분석. app은 frozen72에서 승인된 Room processor 이동에 연결된 경고3개만 제거한다: `app/build.gradle.kts`의 `kapt("androidx.room:room-compiler:2.6.1")`에 붙은 GradleDependency, KaptUsageInsteadOfKsp, UseTomlInstead 각1개. 나머지69개(오류5/경고64)는 canonical 내용과 중복 횟수를 유지한다(독립 권한 PR의 수정은 이 브랜치에 포함하지 않음). 새 테스트 의존성5줄/data 의존성8줄 및 plugin 설정의 진단은 실제 보고서별 ID·severity·경로·정확한 선언·메시지·횟수·근거를 개별 동결하고 독립 리뷰로 판정한다. Hilt test2.52는 production과 버전을 맞춘다. 기존 catalog 진단5종 각3회의 반복 횟수가 달라지면 발생 모듈/보고서 근거와 정확한 증가분을 별도로 검토한다. ID 종류만으로 일괄 허용하거나 중복 제거하지 않는다. 신규 source·Manifest 진단은 허용하지 않으며 기존 baseline 자체를 덮어쓰지 않는다.
4. merged app manifest permission/component 변화0. app reverse dependency0, domain Android/Hilt import0, duplicate0, source12/schema/ABI/Compose/DI5+2 증거, SMS production 호출0, 범위/diff check.
5. 독립 최종 리뷰의 필수/권장 지적을 닫고 source 변경 뒤 검증을 마친 커밋을 원격에 push, 별도 Ready PR. main은 승인 전까지 merge하지 않는다.

## 실행 명령의 serial 제한

APK는 Gradle로 build하되 `connectedAndroidTest`를 대상 지정 없이 실행하지 않는다. `adb -s emulator-5580`로 테스트 APK/대상 APK를 설치하고 manifest에서 확인한 runner를 명시해 `shell am instrument -w -r` 실행한다. 테스트 성공은 전체 instrumentation 결과와 테스트 수로 확인하며 exit0만으로 단정하지 않는다. 대상 emulator 종료도 avd 이름 재확인 후 해당 serial에만 `emu kill`한다.
