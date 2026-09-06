# CI 기준선 트랙 실행 로그

기준 main `7c6bfd120b1cafb66e50c4f2f19e846843f9986f`(PR #13 merge, tree = 34832de). 등급 T1. 작성·수행·검토 = Claude(사용자 지시 "검토 후 다음 작업 진행해", 2026-09-06). 커밋·push는 S1 대기.

## R1 — workflow·검증 스크립트 작성과 로컬 실검증

- `✓ 파일 작성 [T1]` — `.github/workflows/verify.yml`(11 steps, 액션 4종 commit SHA 핀: checkout v7.0.1 `3d3c42e…`, setup-java v6.0.0 `dd06d9c…`, gradle/actions v6.3.0 `9c97196…`, upload-artifact v7.0.1 `043fb46…`), `.github/scripts/verify-unit-xml.ps1`, `verify-domain-lint.ps1`, `lint-diff.ps1`. PyYAML 파싱 OK, PowerShell 파서 오류 0.
- `✓ fresh 직렬 게이트(main) [T1]` — JBR 21. 1부 `clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses` BUILD SUCCESSFUL 6m10s(107 tasks). 2부 `:app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug` BUILD SUCCESSFUL 3m4s(130 tasks). 로컬 도구 10분 제한 때문에 두 번 호출했을 뿐 CI는 한 호출. `evidence/full-build.txt`.
- `✓ unit XML [T1]` — 41 suites / 555 tests / failures·errors·skipped 0 (app 39 suites 544, risk 7, contracts 4). `evidence/unit-tests.json`.
- `✓ lint [T1]` — app 0E/69W 지문 `4D4955…`, data 0E/14W 지문 `8B8B8D…`(기존 `verify-integration-lint.ps1` PASS), risk 0, contracts GradleDependency warning 1·errors 0(`verify-domain-lint.ps1` PASS). `evidence/lint-check.txt`.
- `✓ schema drift [T1]` — `git diff --exit-code -- data/schemas` = 0.
- `✓ 변조 probe [T1]` — 13/13 기대대로: 정상 사본 2건 PASS, 변조 11건(skipped=1·suite 파일 제거·failures=1·errors=1·results 디렉터리 없음·risk 진단 추가·contracts 승인 경고 제거/Error 승격/중복/ID 변경·리포트 없음) 각각 FAIL. `evidence/verifier-probes.txt`.
- `✓ lint-diff 진단 [T1]` — 실제 리포트 missing 0/extra 0. 변조본(기준 진단 1건 제거 + 가짜 1건 추가)에서 MISSING/EXTRA 각 1건 출력, 같은 변조본에 exact-union 검증기 exit 1. `evidence/lint-diff-probe.txt`.
- 수정 1건(자기검토): `verify-domain-lint.ps1`가 단일 요소 결과를 PowerShell이 스칼라로 풀어 `$contracts[0]`가 XML 자식 노드를 가리키던 결함 → 호출부 `@()` 래핑. 수정 후 실제 리포트 PASS·probe 재실행 13/13.
- probe 하네스 1차 실행은 MSYS 경로(`/c/...`)를 루트로 넘겨 복사가 비어 있었음(무효). Windows 경로로 재실행한 결과만 증거로 채택.

## R1' — 독립 리뷰 지적 반영 (S2, 2026-09-06)

리뷰어가 게이트 누락 2건을 직접 재현했고, 코드 확인으로 둘 다 사실이었다.

1. **`verify-domain-lint.ps1`가 id·severity·파일 접미사만 검사** → 같은 id/severity/파일의 다른 라이브러리 경고로 바꿔도 PASS. 수정: 승인 경고를 6개 필드로 고정 — id, severity, `<LATEST>` 정규화 메시지(`A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-core than 1.8.1 is available: <LATEST>`), 선언문 `errorLine1`(`    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")`), location 개수 정확히 1, 저장소 상대 경로 정확히 `domain/contracts/build.gradle.kts`(lint-records와 같은 루트 접두사 제거·`/` 정규화, 루트 밖 경로는 throw). 실패 시 어떤 필드가 어긋났는지 출력.
2. **schema drift `git diff --exit-code`가 미추적 JSON을 놓침** → 신규 `.github/scripts/check-schema-drift.sh`: `git status --porcelain --untracked-files=all -- data/schemas`가 비어 있어야 PASS(추적 파일 수정·삭제 + 미추적 신규 모두 검출), 실패 시 상태와 diff --stat 출력. workflow가 이 스크립트를 호출한다.
3. **권장: probe 하네스 커밋** → `investigations/2026-09-06-ci-baseline-t1/probe-verifiers.ps1`(저장소 루트·scratch·evidence 경로 인자화, 실제 lint XML의 절대 경로 접두사를 probe 루트로 재작성해 "그 루트에서 생성된 동일 리포트"를 만든 뒤 변조). workflow에 `Verifier self-check (mutation probes)` 단계로 추가해 CI가 검증기 자체를 매번 재검증한다.

- `✓ 재검증 [T1]` — 실제 리포트: 강화된 도메인 lint 검증기 PASS, schema 스크립트 PASS(drift 0). **probe 25/25 기대대로**: unit 6, domain lint 14(신규 = 최신버전 메타데이터만 변동 → PASS 확인, 다른 라이브러리 동일 id/파일 → FAIL, 선언문만 변경 → FAIL, 메시지 고정버전만 변경 → FAIL, location 2개 → FAIL, 같은 파일명 다른 디렉터리 → FAIL, 루트 밖 경로 → FAIL), schema 5(임시 git 저장소: clean PASS, 미추적 2.json FAIL, 추적 수정 FAIL, 추적 삭제 FAIL, 복원 PASS). `evidence/verifier-probes.txt`.
- 하네스 1차 실행에서 정상 사본 2건이 실패한 것은 하네스 결함(복사본의 절대 경로가 실제 저장소를 가리켜 강화된 검증기가 루트 밖으로 판정) → 경로 접두사 재작성으로 수정. 검증기 자체는 실제 리포트에서 처음부터 PASS.

## R2 — 실기기 post-merge 기준선 (S1 해석: 사용자 "실기도 연결 가능" 명시)

- `✓ AT-M150 API34 [T1]` — `run-device-baseline.ps1 -Serial <기기>`(통합 트랙 스크립트 사본, evidence 경로만 이 폴더). preflight: 모델·API·telephony·user 0·두 package 미설치 확인 → 설치 → 권한 denied 상태 17/17 PASS → grant/시스템 상태 확인 → revoke/확인 → smoke 3/3 PASS → 프로덕션 ServiceRecord 0 → finally 제거, cleanup 오류 0, 기기에 seniorshield package 0. `evidence/device-run.json`, `device-initial-instrumentation.txt`, `device-after-revoke-instrumentation.txt`.
- 통화 UI 실제 표시·호출 도중 권한 철회 race·OEM 다양성은 여전히 미확인(사용자 수동 항목, REMAINING_CHECKS 절차).

## 계약 ↔ 산출물 매핑

| DIRECTIVE 항목 | 산출물 / 증거 |
|---|---|
| §3.1 트리거·permissions·concurrency·runner·JDK21·SDK·캐시·chmod | `verify.yml` steps 1~5 |
| §3.1 게이트 명령(로컬과 동일) | `verify.yml` step 5, `evidence/full-build.txt` |
| §3.1 판정 1 unit floor 544/7/4·skipped 0 | `verify-unit-xml.ps1`, `evidence/unit-tests.json`, probes 1~6 |
| §3.1 판정 2 domain lint (6필드 고정) | `verify-domain-lint.ps1`, `evidence/lint-check.txt`, probes 7~20 |
| §3.1 판정 3 app/data exact union | 기존 `verify-integration-lint.ps1` 재사용(무수정), `evidence/lint-check.txt` |
| §3.1 판정 4 schema drift (추적+미추적) | `check-schema-drift.sh`, `verify.yml` step 9, probes 21~25 |
| §3.1 판정 5 verifier self-check | `probe-verifiers.ps1`, `verify.yml` step 10, `evidence/verifier-probes.txt` |
| §3.1 판정 6 lint-diff 진단 | `lint-diff.ps1`, `evidence/lint-diff-probe.txt` |
| §3.1 artifact | `verify.yml` step 12 (`if: always()`, 14일) |
| §3.3 실기기 기준선 | `run-device-baseline.ps1`, `evidence/device-*` |
| §5 범위 | `git status`: `.github/scripts/`, `.github/workflows/`, 이 폴더만 |

## R3 — S1 대기

- 커밋 대상(exact path, 18 파일): `.github/workflows/verify.yml`, `.github/scripts/verify-unit-xml.ps1`, `.github/scripts/verify-domain-lint.ps1`, `.github/scripts/lint-diff.ps1`, `.github/scripts/check-schema-drift.sh`, `investigations/2026-09-06-ci-baseline-t1/DIRECTIVE.md`, `IMPL_LOG.md`, `probe-verifiers.ps1`, `run-device-baseline.ps1`, `evidence/.gitattributes`, `evidence/*`(8 파일).
- push 후 첫 `verify` run GREEN 확인과 run URL·소요 시간·artifact 기록은 사용자 승인 뒤 이 섹션에 추가한다. RED면 S5 보고.
- 후속 T0: `gradlew` 실행 비트(`git update-index --chmod=+x gradlew`)를 별도 커밋으로 정리하면 workflow의 `chmod +x` 단계를 제거할 수 있다.
