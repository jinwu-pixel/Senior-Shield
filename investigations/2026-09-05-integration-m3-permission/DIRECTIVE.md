# M3 + 권한 안전성 통합 검증 지시문

> **등급: T2 고위험** — 승인된 data/DI 이동과 권한 방어를 결합해 검증한다.
> **검토:** 독립 계획 검토 → 직렬 실행/전체 자체검토 → 독립 최종 리뷰.
> **S1~S5:** 사용자의 “남은 작업들 진행해”와 기존 저장소별 게시 승인을 적용한다. main 및 기존 PR branch는 변경하지 않는다. 새 정책/권한/제품 변경, 예상 밖 lint/테스트 실패는 원인과 범위를 검토한다. 이 문서는 독립 Codex 검토를 사용하며 Claude 실행으로 표기하지 않는다.

## 승인 범위와 5줄 계획

1. 파일: 이미 리뷰된 PR #11 `fcb2c35`와 #12 `20cc9fc`를 `codex/integration-m3-permission`에서 결합한다. 추가 파일은 이 investigation의 문서·검증 도구·증거 및 `app/src/androidTest/java/com/example/seniorshield/permission/PermissionDeviceSmokeTest.kt`뿐이다.
2. 목적: 두 변경이 공존할 때 app lint 기존 오류5가 제거되고 저장/Hilt 계약과 권한 회귀가 유지되는지 증명한다. production 새 동작/의존성은 만들지 않는다.
3. 정책/권한: Manifest는 PR #11과 동일, data source/DI/schema는 PR #12와 동일. 실기기는 target app 미설치를 사전 확인했으며 test Application만 실행한다. 외부 연락·실제 통화 시작·production monitoring 실행·개인 데이터 읽기 금지.
4. 검증: clean full unit555/build/kapt/APK/duplicate/domain check 및 app/data/domain lint, 실제 Android instrumentation 기존14 + 신규 권한 거부 smoke3, grant→revoke를 instrumentation 프로세스 밖에서 수행한 뒤 동일 smoke3 재실행, source/schema/ABI/Compose/Manifest/정책 검사 및 독립 최종 리뷰.
5. 중단: 예상하지 못한 source 병합 충돌, 저장/권한 의미 변화, 신규 production/lint 진단, 연결 기기 변경·대상 앱 기존 설치 발견, 테스트 실패. 통화 UI 표시나 in-flight 권한 race를 기기 검사로 입증했다고 주장하지 않는다.

## 실행 계획

- [x] 독립 계획 리뷰: 설치 전 target/test 두 package absent 및 실패 경로 finally cleanup 보강 조건부 PASS, 해당 조건을 아래에 반영했다. parent만 Gradle을 직렬 실행한다. 기존 테스트/기준선은 변경하지 않는다.
- [x] M3 기준 worktree에서 `git merge --no-commit --no-ff fcb2c35`로 결합한다. app Gradle의 M3와 Robolectric 설정을 모두 유지한다. 기존 PR/main에는 merge하지 않는다.
- [x] 신규 AndroidJUnit4 smoke3: 실제 READ_PHONE_STATE denied 상태에서 `CallEndHelper.isInCall()` false; `showInCallScreen()` false; POST_NOTIFICATIONS denied 상태에서 `RiskNotificationManager.notify()`가 예외 없이 끝나고 테스트 ID 알림이 존재하지 않음. 각 테스트가 실제 denied 상태를 먼저 assert하며 SDK33+를 assert한다. mock·실제 통화·알림 권한 granted 상태의 알림 발송 없음. UUID event ID 사용.
- [x] JBR21와 `--no-daemon --no-parallel --max-workers=1 --console=plain`으로 `clean :domain:risk:check :domain:contracts:check :data:testDebugUnitTest :app:testDebugUnitTest :data:kaptDebugKotlin :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug`를 실행한다. Compose 보고서는 새 ignored 경로에 출력한다. XML failure/error/skipped 모두0, 총555를 확인한다.
- [x] lint 기대 app: frozen M2 72 − 권한 오류5 − Room compiler 경고3 + M3 테스트 선언 경고5 = 69 warnings/0 errors. data14 warnings 및 contracts1 existing warning, risk0. 원본72 hash와 승인된 app-additions/data multiset hash를 검증한 뒤 각각 exact 대조한다. 일반 ID waiver 금지. 변조 probe로 추가/누락/중복/경로/추가 location 검출을 확인한다.
- [x] source/설정 Git blob provenance: 공통 파일 중 결합된 app/build.gradle.kts 외에는 원본 PR blob 중 하나와 일치. production kotlin은 M3 소유12개와 권한 소유4개 보존, schema exact. merged Manifest는 권한 evidence와 exact. fresh ABI14는 M3와 exact projection/major61, Compose4는 M3 이후와 exact(신규 androidTest는 main 보고서에 영향을 주지 않아야 함).
- [x] 실기기 SDK34/telephony/target package absent를 기록한다. APK/runner identity를 먼저 검사한다. 설치 전 absent 재확인, 새 설치만 허용. `M3TestRunner`가 HiltTestApplication을 사용하므로 production Application을 시작하지 않는다. 기존 저장14 + smoke3 총17을 실행한다. 이후 shell에서 두 runtime permission grant → 시스템 granted 상태 확인 → revoke → denied 상태 확인 후 smoke3만 재실행한다. shell exit0 외 테스트 이름/시작/성공 개수와 OK를 모두 검사한다. 재설치가 필요하면 이 세션이 설치한 package와 동일 서명인지 확인한다.
- [x] 설치 전 target `com.example.seniorshield` 및 test `com.example.seniorshield.test` 두 package 모두 absent임을 확인한다. 하나라도 존재하면 덮어쓰지 않고 중단한다. 설치/테스트 전체를 try/finally로 감싸 성공 여부와 무관하게 이 세션이 설치한 package만 uninstall한다. 설치 도중 실패해도 preflight absent인 대상에 남은 설치가 있으면 정리한다. 둘 다 absent임을 확인하고 cleanup 실패도 명시적으로 실패 처리한다. 기록에는 기기 serial 등 불필요한 식별자를 넣지 않는다. 실제 통화 UI·경합 중 권한 철회·OEM 다양성은 미확인으로 남긴다.
- 독립 최종 리뷰 PASS 이후 게시 절차: 증거를 커밋하고 기존 승인 저장소 `https://github.com/jinwu-pixel/Senior-Shield`에 integration branch를 push한다. 별도 통합 Ready PR은 M3를 base로 하여 검증 내용을 제시하고, #11/#12를 대체하거나 main에 자동 병합하지 않음을 명시한다.

## 후속 수동 기기 체크

실제 전화 화면 표시는 사용자가 시작한 비긴급 테스트 통화 중에만 확인한다. 통화 중 CTA 클릭→전화 앱 표시, overlay 닫힘과 세션 보존을 관찰한다. 이 자동 검증은 그 결과를 대신하지 않는다. CI 구축은 새 workflow/외부 실행 설정이 필요한 별도 계획 항목이며 현재 자동 체크가 존재한다고 주장하지 않는다.
