# Permission Safety 지시문

> **등급: T2 고위험** — 통화 CTA, 본인 알림, Manifest 기기 지원 조건.
> **검토:** Codex 자체검토 + 독립 계획/코드/최종 리뷰, 직렬 build/test/lint.
> **S1~S5:** 사용자의 2026-09-05 연속 실행 승인은 후속 계획 구현·검증·격리 branch·commit·push·Ready PR에 적용한다. 사용자는 비전화 기기를 지원하지 않고 전화 기기만 지원한다고 명시했다. main 병합은 별도 답변 전까지 실행하지 않는다. 정책/저장/DI/monitor 변경이나 이 목록 밖 변경은 먼저 원인과 범위를 검토한다.

## 기준

- M2 검증/리뷰 완료 source `0d7b30990bf3f08eaf8d56b220e41019b9996c3a`, main `7754ebf`.
- 격리 `codex/permission-safety-t2`; M2 미병합 중에는 stacked base로 작업하며 main을 수정하지 않는다.
- 기존 app 454, risk 7, contracts 4 tests; app lint 72건(5 errors, 67 warnings).
- 기존 근거: `investigations/2026-09-05-domain-contracts-m2-t2/FOLLOW_UP_PLAN.md` A1. 이 문서는 해당 후속 T2를 구체화한다.

## 5줄 계획

1. 허용 파일: `CallEndHelper.kt`, `RiskOverlayManager.kt`, `BankingCooldownManager.kt`, `RiskNotificationManager.kt`, `app/src/main/AndroidManifest.xml`, 위 core 경로의 해당 테스트, 필요한 `app/build.gradle.kts` 테스트 전용 설정/의존성, `gradle/libs.versions.toml`의 Robolectric4.16.1 테스트 전용 항목, 이 investigation 문서·검증 도구.
2. 목적: READ_PHONE_STATE/POST_NOTIFICATIONS 거부와 check/use 사이 권한 철회 시 크래시를 막고 전화 기기 전용 배포 의도를 명시한다.
3. 정책: 새 권한·자동 통화 종료·발신·보호자 메시지 없음. 기존 사용자 클릭의 전화 화면 열기 및 본인 알림만 방어한다. Manifest delta는 `<uses-feature android:name="android.hardware.telephony" android:required="true"/>` 하나다.
4. 검증: permission denied/granted/revoked, null Telecom service, 알림 API32/33+, 실제 CTA 성공/실패 및 presentation token 회귀를 RED→GREEN으로 검사. full build/unit/domain/lint 및 merged manifest exact delta 검증.
5. 중단: 연락·monitor·DI·저장/Coordinator 의미 변경 필요, 테스트 약화, 새로운 lint/권한/component, 기존 presentation/token 회귀. 앱 코드 변경 뒤 전체 gate를 새로 실행한다.

## 계약

- `CallEndHelper.isInCall()`은 READ_PHONE_STATE 없거나 Telecom service null/권한 철회 예외면 false. 권한을 요청하지 않는다.
- 같은 helper에 guarded 전화 화면 열기 함수를 추가하는 것은 허용한다. 호출 수락 여부를 반환하고 호출 직전 permission check와 SecurityException 처리를 갖춘다. true는 void API 호출이 예외 없이 반환했음을 뜻하며 전화 UI가 실제 표시됐다는 보장은 아니다. 외부 연락을 시작하지 않는다.
- 두 overlay의 사용자 클릭: 현재 통화이고 화면 열기 API가 예외 없이 반환하면 기존 SHOW_IN_CALL_DELAY_MS 및 `dismiss(presentationToken)`을 유지한다. 권한 거부/철회/서비스 null/실패는 기존 비통화 경로와 동일하게 즉시 `dismiss(presentationToken)`한다. 렌더 시점의 isInCall도 안전해야 한다. session safe-confirm을 실행하지 않는다.
- 본인 알림은 현재 helper guard가 이미 존재한다. 이를 lint가 이해하는 현장 검사로 바꾸고 notify 시 SecurityException을 처리한다. 채널, PendingIntent, 알림 ID/내용/빈도/우선순위 조건은 바꾸지 않는다.
- API32 이하는 POST_NOTIFICATIONS runtime grant 요구를 추가하지 않는다. API33+ denied는 notify 미호출, granted는 기존 payload, check/use 철회는 조용히 건너뛴다.
- Throwable/모든 Exception을 일괄 무시하지 않는다. 해당 권한 경쟁의 SecurityException만 처리한다.
- Android MockK stub 환경만으로 API33 실동작을 검증했다고 주장하지 않는다. 필요한 테스트 환경 선택은 로컬 cache/공식 의존 metadata 근거로 문서화한다.

## 테스트 환경과 실행 순서

- 테스트 전용 `org.robolectric:robolectric:4.16.1`을 pin한다. 공식 release/POM/module 및 SDK provider 검토로 기존 JDK21/Kotlin1.9.24와 API32/33를 지원하는 소비자 의존 graph를 확인했다. 초기 후보4.13은 신규 GradleDependency 부채를 만들 수 있어 현재 안정 버전으로 교정했다. Maven 다운로드가 필요하며 앱 runtime 의존성과 SDK/Kotlin 버전은 유지한다.
- app unitTests에 Android resources를 포함하고 `@Config(sdk = [32, 33], application = Application::class)`로 권한/알림 분기를 검사한다. production `SeniorShieldApp`을 생성해 monitoring service가 자동 시작되는 테스트 구성을 금지한다.
- Task1: 변경 전 기존 unit/domain 기준선 → 독립 DIRECTIVE 검토 → focused 회귀 테스트를 먼저 작성·실행해 실제 거부/철회 실패 RED 기록.
- Task2: 승인된 source4/Manifest 변경 → focused GREEN → 코드 독립 리뷰. 테스트 전용 설정 외 app build 변경 금지.
- Task3: fresh 전체 gate + 정확한 lint5 감소 + 정책/Manifest/범위 확인 → 독립 최종 리뷰 → commit/push/Ready PR. 새 기기 실험은 이 권한 트랙의 필수 gate로 가정하지 않으며 실제 기기 권한 철회 미실행 한계를 기록한다.

첫 fresh lint에서 기존 오류5는 제거됐으나 새 Robolectric 문자열 선언에 UseTomlInstead warning1이 발생했다. 기존67 유지 게이트를 완화하지 않고 해당 테스트 의존성만 기존 version catalog에 등록한다. 버전/해결 artifact/runtime graph는 동일하다. 연속 실행 승인의 테스트 환경 보완 범위이며 새 정책/권한 결정이 아니다. 독립 범위 검토 뒤 적용하고 build/test/lint를 다시 확인한다.

## 게이트

1. baseline 후 focused RED→GREEN 및 신규 회귀 검사. 기존 테스트 삭제/약화 금지.
2. `clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses` 성공; 기존465+신규, failures/errors/skipped0.
3. fresh app lint의 before/after multiset은 위 5 errors만 제거. 67 warnings는 exact 유지하며 update metadata 최신 버전과 line 이동만 기존 방식으로 정규화. M2 frozen72 evidence를 수정하지 않는다. contracts의 기존1 dependency warning 별도 유지.
4. merged manifest permission/components 변화0, telephony required=true 하나만 변화. 본인보호 정책 및 legacy SMS 신규 호출0.
5. 독립 최종 리뷰 필수/권장0, diff check, exact 범위 audit, 원격 branch와 Ready PR 게시. main 병합은 별도 승인.
