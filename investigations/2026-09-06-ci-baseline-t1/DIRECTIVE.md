# CI 기준선 지시문 — GitHub Actions 검증 게이트 도입

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol` 참조)**
> - **등급**: T0 문서·보고 / **T1 격리 코드** / T2 고위험 — [이 트랙 = **T1**]
>   앱 소스·테스트·Gradle 빌드 스크립트·Manifest 변경 0. 신규 파일은 workflow 1 + 검증 스크립트 3 + 트랙 문서·증거뿐.
>   (실행 중 앱 소스 변경이 필요해지면 S3 자동 등급 상승·중단·질의.)
> - **검토**: T1 = Claude 독립검토가 유일 게이트. 이 트랙은 사용자 지시("검토 후 다음 작업 진행해")에 따라
>   **작성·수행·검토 모두 Claude**(Codex 미기동). 수행자와 검토자가 같으므로 완료 게이트는 로컬 산출물 실검증 + 원격 Actions 첫 run GREEN을 모두 요구한다.
> - **정지·질의 = S1~S5만** (그 밖은 자동 전진, 한 줄 로그 `✓ <단위> [T1] — <수치>`):
>   S1 되돌릴 수 없는 게이트(커밋·push·실기·Manifest/권한/DI 실제 변경) /
>   S2 적대검증 통과 지적 ≥recommend / S3 등급 상승 /
>   S4 제품·정책 결정 필요 / S5 완료 게이트 실패(RED·lint 신규·범위 위반).
> - **S1 해석**: 커밋·push는 사용자 명시 승인 전 금지(글로벌 commit 정책). 실기기는 사용자가 이번 지시에서
>   "실기도 연결 가능하니 참조하고"라고 명시했으므로 **테스트 패키지 설치→실행→제거**(기존 검증 스크립트의 preflight·finally cleanup 그대로)에 한해 허용된 것으로 해석한다. 프로덕션 앱을 기기에 남기지 않는다. 사용자가 시작해야 하는 통화 UI 수동 확인은 이 트랙에서 수행하지 않는다.

## 0. 현재 상태

- main `7c6bfd120b1cafb66e50c4f2f19e846843f9986f` = PR #13 merge commit(squash 없음). tree는 검증된 `34832de`와 동일. origin/main 동기.
- 기준선(34832de 통합 검증 + Claude 독립 재실행 2026-09-06): unit **41 suites / 555 tests / failures·errors·skipped 0**
  (app 544 / domain:risk 7 / domain:contracts 4). app lint **0 errors / 69 warnings** fingerprint
  `4D4955366E3379A7AFA85E962536316EDA6D279F29337907FF5B2B4AA8DC05DF`, data lint **0 / 14** fingerprint
  `8B8B8DBDD93425F073D1D928C27B5F9F29348173BD758F8C0E5F1A11821DBD32`, contracts lint 기존 `GradleDependency` warning 1(coroutines-core 1.8.1 pin, 승인), risk 0.
- 실기기(AT-M150, API34) instrumentation 17 + 권한 철회 후 3 PASS(34832de). 통화 UI 실제 표시는 수동 미확인(릴리스 전 항목).
- `.github/`에 workflow 없음(PR template·S2 checklist만). GitHub 자동 체크 0. 저장소 PUBLIC, Actions enabled(allowed_actions=all).
- `gradlew`(sh)는 git mode `100644`(실행 비트 없음) → Linux runner에서 `chmod +x` 필요.
- 로컬 Gradle 실행 환경: JBR 21, `--no-daemon --no-parallel --max-workers=1 --console=plain`.

## 1. 불변 운영 제약

1. commit·push·stage = S1(사용자 명시 승인 전 금지). broad add 금지. 커밋 시 아래 §5 파일만 exact path.
2. 로컬 검증 Gradle은 직렬(`--no-parallel --max-workers=1`), JAVA_HOME=JBR 21. CI도 같은 플래그로 동일 게이트를 실행한다.
3. 금지 범위: 앱·data·domain 소스, 모든 `*.gradle.kts`, `gradle.properties`, Manifest, `compose-stability.conf`, 기존 investigations 증거·스크립트 — 변경 시 S3.
4. **무조건 통과 금지**: lint `abortOnError=false`·baseline 파일·`continue-on-error`·`|| true`·전체 skip·테스트 필터로 게이트를 우회하지 않는다. 라이선스 수락 등 게이트가 아닌 준비 단계만 예외이며 명시한다.
5. 시크릿·외부 서비스·실기기 runner 불필요. instrumentation(17)은 CI 범위 밖, 수동/후속 device-farm 트랙으로 유지.
6. 산출 문서·증거는 이 트랙 폴더에만. 기존 트랙 evidence 파일을 덮어쓰지 않는다(기기 기준선은 이 폴더의 사본 스크립트로 실행).

## 2. 승인된 5줄 계획

1. **수정 파일**: 신규 `.github/workflows/verify.yml`, `.github/scripts/verify-unit-xml.ps1`, `.github/scripts/verify-domain-lint.ps1`, `.github/scripts/check-schema-drift.sh`, `.github/scripts/lint-diff.ps1`, `investigations/2026-09-06-ci-baseline-t1/**`(DIRECTIVE·IMPL_LOG·probe-verifiers.ps1·run-device-baseline.ps1·evidence). 기존 `investigations/2026-09-05-integration-m3-permission/verify-integration-lint.ps1`·`verify-instrumentation.ps1`, `investigations/2026-09-05-data-module-m3-t2/lint-records.ps1`은 **무수정 재사용**.
2. **목적**: main push·PR·수동 실행마다 로컬 검증 게이트(fresh 직렬 build/unit/kapt/APK/duplicate/androidTest APK 컴파일/lint)와 **동일한 명령·동일한 판정 스크립트**를 자동 실행해, 이후 트랙의 "빌드·테스트·lint 기준선 동일" 주장을 GitHub 체크로 대체한다.
3. **리스크**: 제품 동작·정책·권한 변경 0. 공급망 = 외부 액션 4종을 **commit SHA로 핀**(major tag 주석 병기). 비용 = PUBLIC 저장소라 Actions 무료. 노이즈 = lint 최신 버전 메타데이터 변동은 `<LATEST>` 정규화로 흡수, 그 밖의 진단 변동은 의도적 fingerprint 실패이며 `lint-diff.ps1`가 missing/extra 키를 출력한다.
4. **테스트(완료 게이트)**: ① main에서 CI와 동일 명령 fresh 직렬 실행 GREEN ② 그 산출물로 스크립트 3종 + 기존 lint 검증기 PASS ③ 변조 probe(테스트 XML 1건 skipped 변조·lint 항목 제거)로 각 스크립트가 실제로 FAIL하는지 확인 ④ workflow YAML 파싱 ⑤ (S1 이후) push 후 첫 Actions run GREEN·run URL을 IMPL_LOG에 기록.
5. **중단 조건**: 스크립트가 로컬 산출물에서 실패, YAML 오류, 앱/Gradle 소스 변경이 필요, 신규 lint 진단, Actions run RED(원인이 workflow가 아닌 코드/환경이면 S5 보고 후 대기).

## 3. 구현 계약

### 3.1 workflow `verify.yml`
- 트리거: `push`(main), `pull_request`(main 대상), `workflow_dispatch`. `permissions: contents: read`. `concurrency: verify-<ref>` cancel-in-progress.
- runner `ubuntu-24.04`(latest 별칭 금지), `timeout-minutes: 45`. JDK = temurin **21**(로컬 JBR 21과 동일 major). Android SDK = runner 내장 + `sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"` 멱등 설치. 라이선스 수락은 게이트가 아닌 준비 단계.
- Gradle 캐시 = `gradle/actions/setup-gradle`(main만 cache write). `chmod +x gradlew` 후 실행.
- 게이트 명령(로컬과 동일, 한 번의 Gradle 호출):
  `./gradlew --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug`
- 후속 판정(각각 독립 step, 실패 시 job 실패):
  1. `verify-unit-xml.ps1`: 모듈별 JUnit XML 합산. **app ≥ 544, risk ≥ 7, contracts ≥ 4**, failures·errors·**skipped = 0**. 결과 JSON을 artifact에 포함.
  2. `verify-domain-lint.ps1`: risk 진단 0, contracts = **정확히 1건이며 6개 필드가 승인 경고와 일치** — id `GradleDependency`, severity `Warning`, `<LATEST>` 정규화 메시지, 선언문 `errorLine1`(`api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")`), location 개수 1, 저장소 상대 경로 정확히 `domain/contracts/build.gradle.kts`(루트 밖 경로는 실패). 같은 id/파일의 다른 라이브러리 경고로 대체되면 실패해야 한다.
  3. 기존 `verify-integration-lint.ps1 -AppCurrent app/build/reports/lint-results-debug.xml -DataCurrent data/build/reports/lint-results-debug.xml`: app 69 / data 14 exact multiset.
  4. Room schema drift: `check-schema-drift.sh data/schemas` = `git status --porcelain --untracked-files=all -- data/schemas`가 비어 있어야 통과. 추적 JSON 수정·삭제뿐 아니라 **미추적 신규 JSON**(버전 올리고 schema 미커밋)도 실패. `git diff`만으로는 미추적 파일을 놓치므로 사용하지 않는다.
  5. Verifier self-check: `probe-verifiers.ps1`가 게이트 산출물 사본을 변조해 각 검증기가 정상 사본 PASS·변조 FAIL인지 매 run 재확인(unexpected 0 필수). schema 스크립트는 임시 git 저장소에서 probe한다.
  6. 실패 시(`if: failure()`) `lint-diff.ps1`가 missing/extra 키를 출력(진단 전용, 판정 아님).
- artifact(`if: always()`, 14일): lint XML/HTML, test-results XML, 판정 JSON. APK는 올리지 않는다.

### 3.2 스크립트 계약
- 세 스크립트 모두 PowerShell 7, `$ErrorActionPreference='Stop'`, 저장소 루트 = `$PSScriptRoot/../..`, 로컬(Windows)과 CI(Linux) 양쪽에서 동일 판정. 경로 비교는 `/`·`\` 정규화.
- 임계값은 스크립트 상수(기준선 명시). 테스트 수가 줄면 실패한다("기존 테스트 삭제·약화 금지"의 기계화). 늘어나면 통과하며, 큰 증가는 다음 트랙에서 임계값을 올린다.
- lint 판정은 기존 검증기를 그대로 호출한다. 일반 ID waiver·baseline 파일·suppression 도입 금지. 승인된 진단 목록이 바뀌는 변경은 그 트랙에서 frozen evidence를 갱신하고 CI가 그것을 검증한다.

### 3.3 실기기 기준선(선택 실행, S1 해석 §헤더)
- 이 폴더의 `run-device-baseline.ps1`(통합 트랙 스크립트 사본, evidence 경로만 이 폴더)로 main 7c6bfd1 빌드의 APK를 AT-M150에서 실행: preflight(모델·API34·telephony·user 0·두 package 미설치) → 17 → grant/revoke → 3 → 서비스 미실행 확인 → finally 제거.
- 결과는 CI 범위 밖 항목의 **post-merge 기준선**으로만 기록한다. 통화 UI 표시·race·OEM은 여전히 미확인.

## 4. 라운드 분할

- R1 스크립트·workflow 작성 + 로컬 fresh 게이트(main) + 스크립트 실검증·변조 probe + YAML 파싱 → `✓ R1 [T1] — 수치`.
- R2 실기기 기준선(선택) → `✓ R2 [T1] — 17/3 PASS, cleanup PASS`.
- R3 (S1) 사용자 승인 후 exact-path 커밋·push → 첫 Actions run 확인 → IMPL_LOG 기록.

## 5. 파일 범위

- `.github/workflows/verify.yml` (신규)
- `.github/scripts/verify-unit-xml.ps1`, `.github/scripts/verify-domain-lint.ps1`, `.github/scripts/check-schema-drift.sh`, `.github/scripts/lint-diff.ps1` (신규)
- `investigations/2026-09-06-ci-baseline-t1/**` (신규, probe 하네스 `probe-verifiers.ps1` 포함 — 판정 재현용으로 커밋)
- 밖은 S3 중단·질의. 특히 `gradlew` 실행 비트(`git update-index --chmod=+x`)는 index 변경 = 커밋 대상이므로 이번에는 workflow의 `chmod +x`로 대체하고 후속 T0로 남긴다.

## 6. 완료 게이트

- 로컬: fresh 직렬 GREEN, unit 555 이상·skipped 0, lint app 0E/69W·data 0E/14W·contracts 1W·risk 0, schema drift 0, 스크립트 3종 PASS + 변조 probe FAIL 확인, YAML 파싱 OK, 앱/Gradle 소스 무접촉(`git status`가 §5 파일만).
- 원격(S1 이후): 첫 `verify` run GREEN, run URL·소요 시간·artifact 목록을 IMPL_LOG에 기록. RED면 S5.
- IMPL_LOG에 계약 항목 ↔ 파일/증거 매핑 표.
