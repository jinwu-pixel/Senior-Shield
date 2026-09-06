# M3 + 권한 안전성 통합 검증 결과

기준: M2 `0d7b309`, M3 `20cc9fc`, 권한 안전성 `fcb2c35`.
격리 브랜치: `codex/integration-m3-permission`. main 및 원본 PR #11/#12 브랜치는 변경하지 않는다.

## 결합 결과

- M3와 권한 PR을 결합했다. 유일한 충돌은 app Gradle 테스트 의존성 삽입 위치였으며 M3 설정과 Robolectric 설정을 모두 유지했다.
- `app/build.gradle.kts`는 M3 원문에 `unitTests.isIncludeAndroidResources = true`와 `testImplementation(libs.robolectric)` 두 줄만 추가한 내용이다.
- 나머지 기존 source/config/test는 두 PR의 정확한 Git blob union이다. 신규 코드는 실제 권한 거부 검사 `PermissionDeviceSmokeTest.kt` 한 파일/3개 테스트이며 production 변경은 없다. `verify-source-union.ps1` 및 `evidence/verified-source-git-blobs.json`에196개 파일을 기록했다.

## fresh 전체 게이트

- JBR21, Gradle 단독 실행, `--no-daemon --no-parallel --max-workers=1 --console=plain`.
- `clean :domain:risk:check :domain:contracts:check :data:testDebugUnitTest :app:testDebugUnitTest :data:kaptDebugKotlin :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug`에 새 Compose 출력 경로를 지정했다.
- **BUILD SUCCESSFUL 8m7s,179 tasks 중175 executed/4 up-to-date.** 실제 unit XML41 suites/555 tests, 실패·오류·skip0. data unit task는 NO-SOURCE이며 추가 테스트 수로 계산하지 않았다.
- app lint **오류0/경고69**, data **오류0/경고14**, contracts 기존 GradleDependency 경고1, risk0. M3 단독에서 남아 있던 기존 오류5가 통합 상태에서 해소됐다.
- app 기대 multiset = frozen72 − 권한 오류5 − Room compiler 경고3 + 승인된 M3 테스트 선언 경고5. data14도 기존 승인된 정확한 목록과 일치한다. app SHA `4D4955366E3379A7AFA85E962536316EDA6D279F29337907FF5B2B4AA8DC05DF`; data SHA `8B8B8DBDD93425F073D1D928C27B5F9F29348173BD758F8C0E5F1A11821DBD32`. 일반 ID waiver는 없다. lint 정상/음성 probes7 PASS.
- 새로 생성한 data 공개 ABI14(major61) 및 Compose4종은 M3 검증본과 동일하다. ABI projection SHA `BF5D2B19C7E07BBAAEBBD8707345CFE237619F7965B06AD565F627971E1D3AA3`. merged Manifest의 XML 내용은 권한 PR 검증본과 동일하다(줄바꿈 차이는 제외).
- monitoring/app DI/Application/navigation/data/domain은 M3 대비 변경0. 금지 SMS 권한/API·ACTION_CALL·endCall 패턴0. 실기기에서 production 서비스가 실행되지 않았음을 확인했다.

## 실제 기기 검증

- AT-M150, Android14/API34, telephony feature가 있는 연결 기기. target/test 두 package를 `pm list packages -u`로 검사해 미설치 상태에서만 진행했다.
- 실제 APK bytes에서 aapt로 package·instrumentation runner/target을 확인하고 APK SHA를 기록했다. runner는 기존 `M3TestRunner`/`HiltTestApplication`이며 production Application/Activity를 시작하지 않았다.
- 최초 사전검사는 adb 목록의 `AT_M150`과 실제 getprop의 `AT-M150` 차이로 설치 전에 중단됐다. 동일 기기의 실제 모델/SDK와 package 미설치를 재확인한 뒤 모델 리터럴만 교정했다. 제품/테스트 소스 변경이나 기기 교체는 없었다.
- 초기 실제 두 권한 denied 상태: 기존 Room/DataStore/Hilt14 + 전화 상태·전화 화면 요청·알림의 거부 상태 smoke3 = **17/17 PASS**.
- instrumentation 프로세스 밖에서 READ_PHONE_STATE와 POST_NOTIFICATIONS를 grant하고 실제 system granted 상태를 확인했다. 두 권한을 revoke하고 denied를 확인한 뒤 동일 smoke3을 새 instrumentation으로 실행해 **3/3 PASS**.
- 두 실행은 총수뿐 아니라 고정된 정확한 `class#test` 이름, 시작1회/성공1회를 대조했다. 이름 대체·중복·skip·시작/완료 누락에 대한 정상/음성 probes6 PASS.
- `finally`에서 이번에 설치한 target/test package만 제거했고 둘 다 미등록 상태로 복구했다. cleanup 오류0. 외부 연락·통화 시작·알림 권한 granted 상태의 알림 발송은 실행하지 않았다.

## 독립 검토

- 계획 리뷰: target/test 양쪽 미설치 검사와 실패 경로 cleanup을 보강한 뒤 PASS.
- 도구/테스트 리뷰: 실제 테스트 이름 집합 검증을 보강하고 실제 APK identity 검사를 추가한 뒤 PASS.
- **최종 독립 Codex 리뷰 PASS, 수정 필수0/권장0.** 검토자가 raw full-gate 로그, 실제555 unit XML, fresh lint XML과 validator, 실제17+3 raw instrumentation과 이름 validator, 현재 compiled14.class major/javap, Compose4, Manifest내용,196개 blob을 직접 확인했다. Claude가 실행했다고 표시하지 않는다.

## 재현 및 근거

빌드 명령은 DIRECTIVE의 full gate를 따른다. `verify-integration-lint.ps1 -AppCurrent app/build/reports/lint-results-debug.xml -DataCurrent data/build/reports/lint-results-debug.xml`, `probe-integration-lint.ps1`의 동일 두 인자를 사용한다. 기기 검사는 앱 데이터가 없는 명시적 대상에서 `run-device-verification.ps1 -Serial <확인한 기기>`로 실행한다. 스크립트는 이번 확인 기기의 실제 모델/API를 고정하며 다른 기기에서는 실행 조건을 먼저 검토해야 한다.

raw build 로그는 ignored `.superpowers/integration/full-gate.log`에 남겼다. 커밋되는 `evidence/`에는 unit suite/timestamp, lint sanitized XML/판정/probes, ABI, Compose hash, merged Manifest, APK identity, 실제 기기 raw instrumentation, 권한 상태/cleanup, source blob과 정책 검사를 보존한다.

## 한계와 남은 확인

실제 통화 UI 표시, API 실행 도중 권한 철회 race, 여러 OEM 동작은 이 기기 검사로 입증하지 않았다. 권한 race의 기존 Robolectric 회귀90은 이번 unit555에 포함된다. DataStore 프로세스 재시작 지속성과 Guardian read-modify-write 원자성은 새로 주장하지 않는다. 수동 전화 UI 절차와 CI 후속 범위는 `REMAINING_CHECKS.md`에 기록했다. main 병합은 하지 않는다.
