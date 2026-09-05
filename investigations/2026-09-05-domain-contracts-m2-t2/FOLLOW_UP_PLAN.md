# M2 이후 lint 부채 및 M3 data 경계 계획

> **협업 규약 헤더 (2026-07-21 확정)**
> - **현재 작업 등급: T0 조사·계획**, M2 검증 도구 보완은 기존 T2 트랙의 승인 범위.
> - **후속 구현 등급: T2 고위험** — lint 통화·알림 표면과 M3의 저장·DI 경계를 각각 별도 트랙으로 실행한다.
> - **검토**: 읽기 전용 Senior Shield 조사 + 독립 계획 검토. 구현 시 전체 자체검토·독립 최종 리뷰.
> - **S1~S5**: 이번 연속 진행 승인은 검증기 수정·검증·push와 이 조사/계획 게시에 적용한다. main 병합, 비전화 기기 지원 결정, 후속 T2 앱 소스 변경은 이 문서만으로 승인된 것으로 취급하지 않는다.

> **For agentic workers:** 후속 계획 실행에는 `superpowers:executing-plans`를 적용한다. 하나의 실행자가 Gradle을 직렬 실행하며 리뷰어는 별도로 검토한다.

**Goal:** M2의 PR 피드백을 닫고, 실제 코드 근거로 lint 부채와 다음 모듈 경계의 실행 단위·완료 기준을 정한다.

**Architecture:** 권한 안전성 보강과 모듈 이동을 별도 PR로 분리한다. 다음 구조 개선 후보는 Android library `:data`이며, repository port는 `:domain:contracts`, 위험 모델은 `:domain:risk`, composition root는 `:app`이 유지한다.

**Tech Stack:** 현재 AGP 8.5.2, Kotlin 1.9.24, Hilt 2.52, Room 2.6.1, DataStore 1.1.1, Coroutines 1.8.1, JDK 실행 21 / JVM target 17, compileSdk 34 / minSdk 26. 버전 업그레이드를 이 계획에 섞지 않는다.

**Spec:** M2 `DIRECTIVE.md`의 불변 정책 및 `DESIGN.md`의 후속 data/feature 분리 목적. 이 문서는 후속 T2 제안이며 M3 구현 결과가 아니다.

## 현재 증거와 실행 순서

- 조사 기준: M2 PR #10의 `dba491c`; 이 문서와 검증기 후속은 앱 소스/빌드 설정을 변경하지 않는다.
- Task 5 실제 결과: app 454 + risk 7 + contracts 4 = 465 tests, 실패·오류·skip 0. app lint 5 errors + 67 warnings = 기존 72건 동일. 상세는 `VERIFICATION.md`.
- `.github`에는 PR template/checklist가 있고 실행 workflow는 없다. PR의 CI 체크가 없는 것을 자동 검증 통과로 표시하지 않는다.
- 순서: 검증기 재현 결함 해소 → 독립 리뷰·PR 반영 → 별도 병합 판단 → clean main 확인 → 후속 T2 트랙 선택/시작. main 병합은 현재 제외되어 있다.
- M2 완료는 실제 Room/DataStore의 영속성·migration 검증 완료를 의미하지 않는다. 현재 `app/src/androidTest`와 data 구현 직접 테스트가 없으며, `RiskEventSinkClearTest`는 interface fake 테스트다.

## A. 기존 lint 오류 5건 원인 분류

Kotlin 경로는 `app/src/main/java/com/example/seniorshield/` 기준이며 Manifest는 `app/src/main/` 아래다. 줄 번호는 조사 기준이다.

| 진단 / 위치 | 확인한 사실 | 후속 처리와 완료 조건 |
|---|---|---|
| MissingPermission — `core/util/CallEndHelper.kt:21` | `TelecomManager.isInCall`에 현재 권한 검사/예외처리가 없다. Manifest 선언만으로 runtime grant를 보장하지 않는다. | READ_PHONE_STATE 거부 및 호출 직전 철회 시 false로 안전하게 반환. 권한 허용/거부/서비스 null/예외 테스트. |
| MissingPermission — `core/overlay/BankingCooldownManager.kt:452` | 사용자 클릭 경로에서 `showInCallScreen(false)` 호출. 앞선 상태 확인 뒤에도 권한 철회 가능. | 현장 권한 확인과 SecurityException 처리. 성공 시 기존 token 기반 지연 dismiss 유지, 실행 불가/실패 시 기존 비통화 분기의 즉시 dismiss를 제안하고 구현 전 계약에 명시. |
| MissingPermission — `core/overlay/RiskOverlayManager.kt:622` | 위와 같은 호출. 렌더/클릭 시 `isInCall()`도 사용. | 렌더와 클릭 둘 다 실패에 안전하게 하고 기존 presentation generation/token 계약 유지. 실패 CTA 처리 계약은 위와 동일하게 검토. |
| MissingPermission — `core/notification/RiskNotificationManager.kt:71` | 이미 `hasNotificationPermission()` guard가 있다. helper는 API 33 미만 true, 33+ POST_NOTIFICATIONS 확인. | 간접 helper가 lint에 증명되지 않는 정황이다. 호출 지점에서 분석 가능한 검사로 바꾸고 SecurityException 경쟁 처리. 기존 본인 알림 조건·빈도 유지. 수정 후 fresh lint로 원인 가설 확인. |
| PermissionImpliesUnsupportedChromeOsHardware — `AndroidManifest.xml:29` | PROCESS_OUTGOING_CALLS에 대응하는 telephony feature 선언이 없다. 현재 merged manifest에도 없다. | 비전화 기기 지원 여부를 먼저 결정. 지원이면 `required=false`와 기능 저하 검증, 미지원이면 제한 의도와 해당 진단 처리 방안을 별도로 검토. 임의로 Manifest를 바꾸거나 suppress하지 않는다. |

통화 API의 권한 근거는 설치된 Android 34 SDK의 `android/telecom/TelecomManager.java`에서 `isInCall`과 `showInCallScreen`의 `@RequiresPermission(READ_PHONE_STATE)`로 확인했다. 위 경고 4건을 전부 “권한 guard 없음” 또는 “실제 크래시 확인”으로 표현하면 부정확하다. 실제 기기에서 권한 철회는 아직 실행하지 않았다.

### A1. 통화·본인 알림 안전성 T2의 5줄 계획

1. 수정 허용: 위 Kotlin 4파일, `app/src/test/java/com/example/seniorshield/core/{util,overlay,notification}/`의 해당 회귀 테스트, 별도 lint 트랙 문서. Manifest는 제외한다.
2. 목적: 현재 권한 거부/철회 경로의 방어와 lint에 확인 가능한 권한 검사. 통화 자동 종료·발신·새 알림 정책은 추가하지 않는다.
3. 리스크: 통화 CTA dismiss와 본인 알림 표면 T2. 실패 시 dismiss 선택이 사용자 경험에 영향을 주므로 계약을 먼저 잠근다. 신규 권한·자동 외부 연락·보호자 알림은 금지한다.
4. 검증: 허용/거부/호출 직전 철회/서비스 null, 렌더·클릭, API 32 및 33+ 알림, token 유지와 no-new-side-effect를 RED→GREEN으로 확인. 전체 app/domain 테스트와 fresh lint에서 정확히 승인한 4진단만 감소하는지 비교한다.
5. 중단: 연락 동작·Coordinator 알림 상태 전이·Manifest 변경 필요, 기존 token 테스트 실패, 다른 lint 진단 변화. 실패 분기 변경은 독립 계획 검토를 먼저 받는다.

완료 기준은 수정된 경로에 대한 동작 검증과 4진단 감소다. 기존 고정 SHA는 M2 증거로 그대로 보존하고, 후속 트랙에서 before/after multiset의 exact 4건 감소를 기록한다. 기존 72건 전체를 숨기거나 기준선을 덮어쓰지 않는다.

## B. M3 의존성 조사와 경계 선택

data production 소스는 정확히 12개다. 모든 파일의 package/FQCN을 유지하는 이동을 제안한다.

| 묶음 | `app/src/main/java/com/example/seniorshield/data/` 아래 파일 | 주 의존성 |
|---|---|---|
| local (4) | `GuardianDataStore.kt`, `SettingsDataStore.kt`, `LiveRiskEventStore.kt`, `RoomRiskEventStore.kt` | Context, DataStore, Android Log, Flow, domain, Inject |
| local/db (3) | `RiskEventEntity.kt`, `RiskEventDao.kt`, `SeniorShieldDatabase.kt` | Room annotations/runtime, Flow |
| repository (3) | `GuardianRepositoryImpl.kt`, `SettingsRepositoryImpl.kt`, `RiskRepositoryImpl.kt` | contracts, local/DAO, Hilt qualifier, org.json, Flow |
| di (2) | `DataModule.kt`, `DatabaseModule.kt` | Hilt 5 bindings, Room DB/DAO 2 providers |

data 소스의 프로젝트 내부 import는 data/domain뿐이다. data 밖 production의 concrete data 타입 직접 참조는 조사에서 0이었다. ViewModel, Coordinator, RealCallRiskMonitor는 repository port를 소비한다. `app/di/AppModule.kt`의 CoroutineDispatcher provider는 app에 남으며, `RoomRiskEventStore` 주입에 필요한 Hilt graph 연결은 실제 kapt로 검증해야 한다.

권고 그래프:

```text
:app -> :data (Android library)
  |        |-> :domain:contracts -> :domain:risk
  |        `-> :domain:risk
  |-> :domain:contracts
  `-> :domain:risk
```

12개 전체 이동은 현재 응집된 구현 및 DI 묶음을 함께 옮긴다. DI 2개를 app에 남기는 10개 이동안은 app이 Room/DAO/구현체 타입을 계속 알아야 하므로 파일 수만으로 위험이 낮다고 볼 수 없다. Settings만 먼저 분리하는 안은 첫 Android library 도입 실험으로는 가능하지만 모듈 수와 DataModule 편집이 추가된다. 순수 JVM data는 Context/Room/DataStore/Hilt/Log/org.json 제거까지 요구하므로 선택하지 않는다.

### 반드시 보존할 계약

- DB FQCN `com.example.seniorshield.data.local.db.SeniorShieldDatabase`, 파일명 `senior_shield.db`, version 1, exportSchema true.
- `app/schemas/com.example.seniorshield.data.local.db.SeniorShieldDatabase/1.json`의 identityHash `bec47e2ef0393e24083a677a42dcbf74` 및 schema 내용.
- DAO: 동일 id REPLACE, `occurredAtMillis DESC LIMIT 50`, `occurredAtMillis >= sinceMillis`. 동일 timestamp 간 순서는 기존 SQL이 보장하지 않으므로 새 보장으로 쓰지 않는다.
- Settings store `senior_shield_settings`; keys `onboarding_completed`, `sms_alert_enabled`, `test_mode_enabled`, `sms_menu_enabled`; 각 기본 false.
- Guardian store `guardian_store`, key `guardians_json`, JSON fields `id`, `name`, `phoneNumber`, `relationship`; 누락 relationship 기본 빈 문자열, 기존 invalid JSON/entry 처리 유지.
- `RoomRiskEventStore`: Eagerly recentEvents, 메모리 currentEvent 초기 null; push=DB+current, record=DB만, update=current만, clearCurrent=current null만, clearAll=DB 삭제+current null.
- `DataModule` binding **5개**와 `DatabaseModule` provider **2개**, singleton scope 유지. LiveRiskEventStore와 RiskEventSink는 같은 RoomRiskEventStore 인스턴스를 가리켜야 한다.
- `fallbackToDestructiveMigration()`은 기존 그대로 유지. Guardian add/remove의 기존 read-modify-write 원자성 공백은 별도 부채로 기록하고 이동 중 수정하지 않는다.
- 자동 SMS legacy API/key는 신규 production 호출 0. Manifest·권한·service·navigation·monitor·화면 동작 변화 0.

### M3 제안 5줄 계획

1. 수정 파일: 아래 Task 1~3의 검사/테스트, Gradle 구성, 12개 소스의 1:1 이동, schema 파일 이동, `.gitignore`, AGENTS 아키텍처와 새 M3 트랙 문서.
2. 목적: 저장·repository 구현과 해당 Hilt 모듈을 Android library `:data`에 캡슐화한다. 순수 domain 경계와 app composition root를 유지한다.
3. 리스크: Room processor/schema 소유권, Hilt 집계/singleton identity, DataStore 파일과 key, coroutine lifetime. migration·동시성 개선·버전 업그레이드는 섞지 않는다.
4. 검증: 이동 전 실제 저장 계약 증거 → source/schema/ABI 보존 → kapt/assemble/duplicate/full tests/lint/Compose/merged manifest 비교 → 독립 최종 리뷰.
5. 중단: data의 app 역의존, DI 또는 저장 의미 변화, device 증거 불가, schema identity 변경, 새 Compose delta, 신규 lint 또는 허용목록 밖 수정. M2 B+ 예외를 자동으로 확대 적용하지 않는다.

### Task 1. 기준선과 실제 저장 검증을 먼저 확보

**Files:** 새 M3 investigation 문서·검증 도구, `app/src/androidTest/java/com/example/seniorshield/data/StorageCompatibilityTest.kt`, `app/src/androidTest/java/com/example/seniorshield/data/RepositoryStorageContractTest.kt`, 필요한 app androidTest 의존성/runner 설정. production 소스는 그대로 둔다.

**Consumes:** 현재 public DAO, DB, repository 생성자와 contracts. **Produces:** 이동 전/후에 그대로 실행할 저장 계약 및 산출물.

- [ ] 병합 승인 후 clean main에서 별도 `codex/data-module-m3` worktree를 만들고 새 DIRECTIVE를 독립 검토한다. 기준 main SHA는 그때 실제 값으로 고정한다.
- [ ] 12개 source SHA, 공개 API, schema 원본 SHA, Hilt 5+2 목록, merged manifest, Compose 4종, 테스트/lint 기준선을 추적 증거로 동결한다.
- [ ] instrumentation 실행 환경은 사용자 앱/데이터가 없는 전용 임시 emulator로 한정한다. 기기 조작 대상/허용 범위를 명시하고 기존 사용자 앱 데이터에 clear/uninstall을 실행하지 않는다. Hilt identity 테스트는 실제 ApplicationContext provider가 target APK의 DB를 열기 때문에 test runner 변경만으로 저장소가 격리된다고 가정하지 않는다. 이 테스트도 해당 전용 emulator에서만 실행한다.
- [ ] Android test runner 의존성은 실제 설치/해결 가능한 버전과 앱 빌드 호환성을 확인해 새 DIRECTIVE에 pin한다. 현재 없는 test infrastructure 도입을 암묵적으로 승인 처리하지 않는다.
- [ ] 아래 입력/기대값의 실제 Room/DataStore 테스트를 이동 전에 실행한다. 단순 mock DAO로 Room SQL·파일 재오픈 통과를 대체하지 않는다.

| 계약 | 고정 입력 / 기대 결과 |
|---|---|
| DAO replace | id `same`에 timestamp 10/title old, 이후 timestamp 20/title new 삽입 → 행 1개, title new |
| 최근 50개 | 서로 다른 id, timestamp 1..51 삽입 → timestamp 51..2 정확히 50개 |
| count 경계 | 위 데이터에서 since=50 → 2, since=51 → 1, since=52 → 0 |
| DB 재오픈 | 격리 DB에 저장 후 close/reopen → 동일 행 복원, schema identity 동일 |
| enum 복원 | HIGH 및 UNKNOWN_CALLER name 저장/복원; 알 수 없는 level → LOW, 모르는 signal 생략 |
| sink 의미 | push(A) → current A+DB A; record(B) → current A+DB B; update(C) → current C, DB C 없음; clearCurrent → null, DB 유지; clearAll → DB 비움+null |
| Settings | 격리 Context의 네 key 기본 false, 각 true/false 왕복, 두 repository 인스턴스에서 동일 파일 값 관측 |
| Guardian | 빈 저장소 → 빈 목록; 3건까지 add true, 4번째 false; remove 후 나머지 유지; JSON field/default 및 invalid entry 처리 동일 |

테스트의 Android Context는 test APK 또는 테스트별 파일 루트로 제한하며, 동일 DataStore 파일에 살아 있는 인스턴스를 중복 생성하지 않는다. 프로세스 재시작 지속성을 주장하려면 실제 별도 프로세스 실행 증거를 추가한다. 단순 repository 재생성만으로 그 주장을 하지 않는다.

### Task 2. 12개 파일과 processor 소유권을 한 모듈로 이동

**Files:** root `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, 신규 `data/build.gradle.kts`, `data/src/main/java/com/example/seniorshield/data/**`의 위 12파일, 동일 app 원본 삭제, `data/schemas/com.example.seniorshield.data.local.db.SeniorShieldDatabase/1.json`, app 원본 schema 삭제, `/data/build/` ignore, AGENTS, M3 문서. 필요한 최소 library manifest는 permission/component가 없는 경우만 새 DIRECTIVE에서 명시한다.

**Consumes:** Task 1 동결 source/schema/저장 계약. **Produces:** 앱이 단일 `:data` production 산출물을 소비하는 graph.

- [ ] root에 `com.android.library` 8.5.2 apply false, settings에 `include(":data")`, app에 `implementation(project(":data"))`를 추가한다.
- [ ] data는 Kotlin Android/Hilt/kapt, namespace `com.example.seniorshield.data`, compileSdk 34, minSdk 26, JVM 17로 만든다. Compose/buildConfig 활성화는 필요하지 않다.
- [ ] data의 공개 domain 타입에는 `api(project(":domain:contracts"))`, `api(project(":domain:risk"))`; 저장 구현 의존에는 기존 Room 2.6.1, DataStore 1.1.1, coroutines-android 1.8.1, Hilt 2.52, javax.inject 1을 같은 버전으로 사용한다. kapt에 Room/Hilt processor를 둔다.
- [ ] 정확히 12개 파일을 package/본문 변경 없이 이동한다. app의 runtime 의존성 정리는 후속으로 남겨 불필요한 classpath 정리를 섞지 않는다. app Hilt plugin/compiler와 dispatcher provider는 유지한다.
- [ ] Room compiler와 `room.schemaLocation` 설정 소유권을 app에서 data로 옮긴다. 생성 대상은 `data/schemas`; frozen 1.json을 byte-preserve 이동한다. app에 두 번째 entity/DAO/DB source root를 남기지 않는다.
- [ ] Task 1 테스트를 같은 계약으로 실행하고 source 12개 normalized SHA와 schema exact SHA를 비교한다. schema identity 또는 의미가 달라지면 이동 완료로 판정하지 않는다.

### Task 3. app 통합·최종 게이트

**Files:** 새 M3 검증 도구/로그, `app/src/androidTest/java/com/example/seniorshield/data/DataBindingIdentityTest.kt`. Task 1 저장 계약 테스트는 app androidTest에 유지한다. **Consumes:** Task 2 산출물. **Produces:** 검증 증거와 독립 리뷰 가능한 별도 PR.

- [ ] app Hilt graph에서 주입된 LiveRiskEventStore와 RiskEventSink의 참조 identity를 실제 검사한다. 임의로 생성한 두 fake 인스턴스 비교로 대체하지 않는다. 테스트 Hilt runner/application 구성은 새 DIRECTIVE 허용목록에 포함한다.
- [ ] app/data runtime classpath, kapt, 중복 class 검증을 실행한다. data의 core/feature/monitoring/app 참조 및 domain의 Android/Hilt 의존 0을 확인한다.
- [ ] JDK 21에서 한 실행자로 아래 Gradle gate를 실행한다. app 테스트 454개와 domain 7+4를 유지하고 새 storage/integration 테스트는 별도로 합산한다.

```powershell
$m3After = Join-Path $PWD '.superpowers/m3/compose-after'
./gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :domain:contracts:check :data:testDebugUnitTest :app:testDebugUnitTest :data:kaptDebugKotlin :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses "-PcomposeCompilerReportsDir=$m3After"
./gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain :data:lintDebug :app:lintDebug
./gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain :app:connectedDebugAndroidTest
```

- [ ] Task 1에서는 같은 property로 별도 `compose-before` 경로를 지정해 `:app:compileDebugKotlin --rerun-tasks`를 실행하고 4종 보고서를 먼저 동결한다. 이동 후에는 위 명령의 `compose-after`에서 실제 생성된 4종을 exact 비교한다. 새 module 이동에 따른 class/metric delta가 나오면 frozen 이전 보고서와 정확히 설명되는 projection을 검토하고 승인 없이 완화하지 않는다.
- [ ] app merged manifest의 permission/component 변화 0, schema identity·파일명·version 유지, 자동 SMS 신규 production 호출 0, source/API 정책을 검사한다.
- [ ] lint는 그 트랙 시작 시점의 before/after multiset을 비교한다. data 새 모듈의 build-script version 진단도 별도로 열거·판정하며 M2의 contracts 예외를 data 전체에 적용하지 않는다.
- [ ] 독립 리뷰로 지적을 닫고 source/config 변경 뒤의 실제 검증 결과를 기록한 후 별도 원격 브랜치와 Ready PR을 게시한다. main 병합은 별도다.

## C. 남은 결정과 리스크

| 결정/미확인 | 근거 | 다음 완료 조건 |
|---|---|---|
| M2 병합 | PR 게시와 merge 권한은 분리되어 있음 | 새 리뷰 보완 후 명시적 병합 승인, main 반영/clean 확인 |
| 비전화 기기 지원 | telephony feature 선언은 배포 대상에 영향 | 지원 여부 결정 및 Manifest 후속 T2 계획 |
| 실제 저장/DI 검증 환경 | 현재 androidTest 인프라와 직접 저장 테스트 부재 | Task 1/3 test runner·대상 기기·격리 정책을 새 DIRECTIVE에 확정 |
| 자동 CI | 현재 workflow 없음, PR 체크 결과 없음 | 별도 CI 계획에서 환경/JDK/SDK·lint 기준선·증거 보존 설계; 무조건 lint exit 0 또는 전체 skip 사용 금지 |
| Guardian 동시성 | add/remove가 read→write로 분리됨 | 별도 재현 및 동시성 T2, M3 이동에는 포함하지 않음 |

우선순위는 M2 리뷰 마감, 통화·알림 권한 안전성 계획, 저장 계약 증거 확보, M3 구현이다. 저장 테스트/기기 환경을 확정하지 않은 상태에서 모듈 이동만 먼저 끝내지 않는다.

## 독립 계획 검토 결과

2026-09-05 Senior Shield 독립 검토에서 조사 근거와 T2 범위는 적합 판정.
권장 2건(Compose 출력 property 누락, instrumentation 테스트 소유 모듈과 실행 명령 불일치)을 수정하고 재검토 PASS를 받았다.
실제 Hilt ApplicationContext 저장소 격리는 runner만으로 보장되지 않으므로 전용 emulator 제한을 추가했다.
남은 수정 필수/권장 지적은 0이다. 이 판정은 조사·제안 문서의 적합성이고 M3 구현이나 기기 검증 통과 판정이 아니다.
