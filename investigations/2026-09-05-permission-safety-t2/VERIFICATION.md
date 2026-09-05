# Permission Safety 검증 기록

기준 커밋 `0d7b30990bf3f08eaf8d56b220e41019b9996c3a`, 격리 브랜치 `codex/permission-safety-t2`.
사용자가 전화 기기만 지원한다고 결정했으므로 `android.hardware.telephony` required=true를 명시한다.

## 테스트 근거

- 변경 전 fresh baseline: app454 + risk7 + contracts4 =465, 실패/오류/skip0. JDK21, 단일 Gradle 실행자, serial flags, BUILD SUCCESSFUL 5m49s.
- 회귀 RED: 컴파일 성공 후 API32/33에서80회 실행, 예상 권한/철회 동작34회 실패. 초기 nullable MockK 타입 컴파일 오류는 회귀 RED로 계산하지 않는다. `evidence/focused-red.json`에 실제 실패 목록을 보존한다.
- 구현 후 focused GREEN: API32/33 총90회, 실패/오류/skip0, BUILD SUCCESSFUL 7m27s. `evidence/focused-green.json`에 suite별 수를 보존한다.
- 90회 구성: CallEndHelper22, 알림12, 위험 overlay28, cooldown overlay28. 실제 버튼 클릭과 Handler499/500ms, stale presentation token을 검증한다. production Application 대신 테스트 Application을 사용한다.
- 전체 fresh clean gate: BUILD SUCCESSFUL 7m36s,82 tasks 전부 executed. app544 + risk7 + contracts4 =555, 실패/오류/skip0 (`evidence/full-tests.json`). assembleDebug/kapt/duplicate class 검사 통과.
- 첫 metadata-enabled app lint: BUILD SUCCESSFUL 2m13s이나 exact delta 게이트 RED(오류0/경고68). 기존67 외 Robolectric 문자열 선언의 UseTomlInstead1을 검출했다. Robolectric4.16.1을 기존 version catalog에 등록하는 최소 수정이 독립 범위 리뷰를 통과했다.
- 카탈로그 교정 후 최종 gate: BUILD SUCCESSFUL 7m23s,90 tasks 중88 executed/2 up-to-date. 세 unit task를 `--rerun`으로 실제 재실행해555건 실패/오류/skip0을 확인했다 (`evidence/final-tests.json`). app unit suite 시작 UTC06:29:51, domain risk06:26:44/contracts06:26:49로 이전 실행과 구별된다.
- 같은 최종 gate의 fresh app lint: 오류0/경고67, frozen multiset 차이0 및 기대 SHA 일치 (`evidence/lint-delta.txt`, `lint-after.sanitized.xml`). 기존 오류5만 제거했다. contracts lint는 기존 GradleDependency Warning1, source 진단0.
- 최종 merged manifest는 telephony required=true 한 항목 외 M2와 exact 동일하다. 권한/component 변화0. 검증 대상 source/config/test10개의 Git blob을 `evidence/verified-source-git-blobs.json`에 기록했다.

## 독립 검토

- DIRECTIVE 계획 검토 PASS. `showInCallScreen()`은 void API이므로 반환 true가 실제 UI 표시를 보장한다는 표현을 배제했다.
- source/test 코드 검토 PASS, 정책/권한 추가 지적 없음.
- lint 도구 검토에서 추가 location 누락을 발견했다. 기준선의 단일 location 계약을 명시적으로 검사하고 추가/누락 location 회귀 probe를 보강했다. 총8 probe PASS (`evidence/lint-probes.txt`). 수정 후 독립 closure PASS.
- 전체 검증 근거를 포함한 최종 독립 리뷰 PASS(2026-09-05), 필수/권장0. 검토자가 raw 최종 로그, 최신555 XML, lint exact67 validator, source10 Git blob, 보존된 before/after merged manifest를 직접 대조했다. commit/push/Ready PR 실행 결과와 main 병합은 이 코드/검증 리뷰 판정에 포함하지 않는다.

## lint 판정 계약

M2의 frozen72를 유지한다. 기존 MissingPermission4 + Manifest 전화 기능 선언 오류1만 제거한다.
남은67 warnings는 id/severity/message/파일/errorLine1의 정규화된 multiset 및 SHA256이 동일해야 한다.
경고 기준 SHA256: `338C12F4515B3CC90E3337173B36FC74215E0764804FB6188181061787C7D332`.
버전 metadata의 최신 버전 문자열 및 이동된 line 번호만 기존 기준과 같이 정규화하며, 경고를 임의로 숨기지 않는다.

## 재현 명령

```powershell
./gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses
./gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain :app:lintDebug
./investigations/2026-09-05-permission-safety-t2/verify-lint-delta.ps1 -Current app/build/reports/lint-results-debug.xml
./investigations/2026-09-05-permission-safety-t2/probe-lint-delta.ps1
```

최종 카탈로그 교정 뒤에는 위 clean 성공 증거에 더해 `:domain:risk:test --rerun :domain:contracts:test --rerun :domain:risk:check :domain:contracts:check :app:testDebugUnitTest --rerun :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:lintAnalyzeDebug --rerun :app:lintDebug`를 동일 serial 옵션으로 실행했다. Gradle의 task별 `--rerun`은 해당 테스트를 강제 재실행한다 ([Gradle CLI](https://docs.gradle.org/8.7/userguide/command_line_interface.html)).

## 한계

실기기 권한 철회 및 전화 UI 실제 표시를 측정하지 않았다. Robolectric과 프레임워크 endpoint 대역을 사용한 권한 경계 및 사용자 클릭 회귀 검사다. 전화 기능 없는 기기의 배포 제외는 사용자의 명시적 지원 범위 결정이다. main 병합은 별도 승인 전까지 수행하지 않는다.
