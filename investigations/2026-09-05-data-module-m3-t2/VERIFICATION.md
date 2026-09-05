# M3 검증 기록

기준 production source `0d7b30990bf3f08eaf8d56b220e41019b9996c3a`, 격리 브랜치 `codex/data-module-m3`.
권한 안전성 PR과 별도로 M2 위에서 작업하며 main 병합은 수행하지 않는다.

## 이동 전 근거

- `:data:tasks`가 project data not found로 실패: 경계 미존재 RED. 제품 회귀 테스트 실패로 계산하지 않는다.
- JDK21/단일 실행자/serial flags, `--rerun-tasks`로 원본 unit/APK/androidTest 및 별도 Compose 출력 생성. BUILD SUCCESSFUL 5m3s,97 tasks 전부 executed.
- app454+risk7+contracts4 =465, 실패/오류/skip0 (`evidence/before-unit-tests.json`).
- 전용 API34 AVD `SeniorShieldM3_e53e5b3e` / `emulator-5580`에서 실제 instrumentation14/14 통과,2.498s. runner가 HiltTestApplication을 사용하고 production Application의 monitoring 시작을 실행하지 않는다 (`evidence/before-instrumentation.txt`).
- 실제 Room DAO4, RoomRiskEventStore3, Settings2, Guardian4, Hilt identity1. DataStore는 동일 활성 파일을 별도 repository에서 관측한 검사이며 프로세스 재시작 지속성으로 표현하지 않는다. Room UUID DB는 실제 close/reopen과 schema identity를 검사한다.
- 원본12개 byte SHA와 schema SHA 유지. 공개 타입/facade14개 JVM major61; Compose4종과 merged manifest를 해당 빌드에서 동결했다.

## 이동 및 독립 검토

- 독립 계획 리뷰 PASS; DataStore delegate의 최초 applicationContext/파일 격리 규약을 보완했다.
- Task1 테스트 독립 리뷰 PASS, 수정 필수/권장0.
- Task2 이동 source12개는 raw SHA 및 기준 Git blob 모두 동일, schema bytes 동일. 독립 소스/설정 리뷰 PASS. Hilt bindings5/providers2, singleton/dispatcher/FQCN 의미 변화0.
- 검증 도구 독립 리뷰에서 exact frozen 보고서 이름 집합, parser 잔여내용 거부, source12 exact 경로 집합을 보강해 PASS를 받았다.
- Compose 도구 정상/음성 probe6 PASS. 이 결과는 합성 projection 검사이며 이동 후 앱 보고서 통과 근거와 구분한다.
- 이동 후 clean gate: BUILD SUCCESSFUL 4m25s,144 tasks 중142 executed/2 up-to-date. domain check/data+app kapt/unit/APK/androidTest APK/duplicate 모두 통과했다. app454+risk7+contracts4 =465, 실패/오류/skip0. data unit task는 NO-SOURCE이며 새 unit 수를 만들어 합산하지 않는다 (`evidence/after-build.txt`, `after-unit-tests.json`).
- 이동 후 실제 instrumentation14/14 PASS,1.056s. 이동 전과 동일한 테스트/전용 AVD/runner로 실행했다 (`evidence/after-instrumentation.txt`).
- source12 byte hashes 및 schema exact, 공개ABI14는 Compose가 생성한 `$stable:I` 필드만 제거한 projection SHA `BF5D2B19C7E07BBAAEBBD8707345CFE237619F7965B06AD565F627971E1D3AA3`로 전후 일치하고 major61이다. merged manifest exact.
- Compose는 이동된10개 block 제거, 나머지76개 exact, 추가0, composables CSV/TXT exact, module은 승인된 class count 변화만 있다. 해당 결과는 생성 보고서 비교이며 런타임 recomposition을 측정했다고 주장하지 않는다 (`evidence/compose-check.txt`, `boundary-check.txt`).
- 최초 offline 빌드는 새 com.android.library8.5.2 plugin marker가 캐시에 없어 compile 전에 실패했다. 동일 버전을 공식 저장소에서 받은 online 재실행으로 위 clean gate를 통과했다. 빈 library Manifest 추가는 필요하지 않았다.
- fresh app/data lint 완료: app 기존오류5/경고69, data경고14, 신규source/Manifest0. app task는 기존 오류로 실패(2m32s,94tasks 중39executed)했으며 전체진단의 exact projection은 독립 검토 및 validator PASS다 (`LINT_DECISION.md`, `evidence/lint-check.txt`).
- lint 정상/음성 probes6 PASS. 전체 근거의 최종 독립 리뷰 PASS(2026-09-05), 필수/권장0. 검토자가 실제 빌드 로그,37 unit suites/465 tests, 전후 동일14개 instrumentation 이름/성공, 현재 .class14개의 javap/major61, boundary/Compose/lint validators 및 각각6개 probes를 직접 확인했다. 원격 게시와 main 병합은 이 판정에 포함하지 않는다.

## lint 판정

frozen72 원본을 수정하지 않는다. 승인된 Room kapt 이동으로 해당 선언의 GradleDependency/KaptUsageInsteadOfKsp/UseTomlInstead 경고 각1개가 앱에서 제거되는 것을 정확히 반영한다. 나머지69건(오류5/경고64)은 내용과 중복 횟수를 유지한다.
추가 진단은 실제 app/data 보고서의 정확한 항목과 횟수를 독립 검토한 뒤 동결하며 종류만으로 허용하지 않는다. 새 source/Manifest 진단은 허용하지 않는다. 기존5 오류의 해결은 별도 권한 안전성 PR 소유다.

이번 실제 목록은 app 테스트 선언 UseTomlInstead5와 data build-script14개로 확정하여 독립 PASS를 받았다. data14는 모두 경로를 app으로 대응하면 frozen baseline에 동일한 진단이 존재한다. 기존69개 및 catalog 중복 횟수는 유지됐다. 고정 hash로 검증하며 미래 진단까지 허용하지 않는다.

## 이동 후 재현 순서

```powershell
$reports = Join-Path $PWD '.superpowers/m3/compose-after'
./gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :domain:contracts:check :data:testDebugUnitTest :app:testDebugUnitTest :data:kaptDebugKotlin :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest "-PcomposeCompilerReportsDir=$reports"
./gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain --continue :data:lintDebug :app:lintDebug
./investigations/2026-09-05-data-module-m3-t2/capture-data-abi.ps1 -Classes data/build/tmp/kotlin-classes/debug -OutputDirectory investigations/2026-09-05-data-module-m3-t2/evidence/abi-after
./investigations/2026-09-05-data-module-m3-t2/verify-data-boundary.ps1
./investigations/2026-09-05-data-module-m3-t2/verify-compose-move.ps1 -Current $reports
./investigations/2026-09-05-data-module-m3-t2/verify-lint-move.ps1 -AppCurrent app/build/reports/lint-results-debug.xml -DataCurrent data/build/reports/lint-results-debug.xml
./investigations/2026-09-05-data-module-m3-t2/probe-lint-move.ps1
```

instrumentation은 사용자 데이터 없는 별도 emulator에서만 실행한다. 먼저 `adb -s emulator-5580 emu avd name`으로 위 이름을 검증하고, app-debug.apk와 app-debug-androidTest.apk를 설치한 후 `adb -s emulator-5580 shell am instrument -w -r com.example.seniorshield.test/com.example.seniorshield.test.M3TestRunner`를 실행한다. 전체14 시작/성공과 `OK (14 tests)`를 확인하며 adb exit0만으로 통과시키지 않는다. 재현 환경에서는 전용 새 AVD의 serial/name에 맞춰 대상 제한을 설정해야 한다.

## 한계

Guardian add/remove의 read-modify-write 원자성, 새 migration, 실제 기기 전화 UI/권한 회수 검사는 이 파일 이동의 범위가 아니다. 앱 runtime 의존성 중복 선언은 기존대로 유지했다. 권한 PR과 함께 main에 반영된 상태의 통합 검증은 해당 병합 순서와 별도 승인이 확정된 뒤 수행한다.
