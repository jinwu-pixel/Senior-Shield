# CLAUDE.md — 시니어쉴드 프로젝트 지침

이 파일은 Claude Code가 세션 시작 시 자동으로 읽는 프로젝트 지침이다.
모든 에이전트와 일반 대화에 공통으로 적용된다.

---

## 플러그인 규칙

- Superpowers 스킬을 자동으로 실행하지 말 것
- 기존 서브에이전트 워크플로우를 그대로 유지할 것

---

## 프로젝트 개요

시니어쉴드(Senior Shield)는 고령층 금융사기 예방 Android 앱이다.
Kotlin / Jetpack Compose / Hilt / DataStore 기반이며,
본인 보호형(self-protection only) 구조를 따른다.

---

## 제품 원칙 (INVIOLABLE)

이 원칙은 모든 코드 변경, 기능 제안, 문서 작성에 우선한다.
위반하는 요청이 오면 그대로 수행하지 말고, 충돌 이유와 compliant 대안을 먼저 제시해야 한다.

1. 공개판은 본인 보호형이다. 다른 성인을 자동 감시하지 않는다.
2. 보호자에게 자동 메시지를 발송하지 않는다. 백그라운드 알림, 자동 통지, 자동 신고를 만들지 않는다.
3. 외부 연락은 사용자가 명시적으로 시작한 경우만 허용한다. 전화는 ACTION_DIAL(다이얼러 열기)만 사용한다.
4. SMS 발송 기능, SEND_SMS 권한, READ_SMS 권한, 자동 알림 서비스는 금지한다.
5. 감시로 오해될 수 있는 기능(위치 추적, 행동 로깅 외부 전송, 보호자 대시보드 등)은 설계/제안/구현하지 않는다.

### 승인된 예외 (2026-03-31 확정)

아래 항목은 제품 원칙과 충돌하지만, 기능상 필수적이므로 의도적으로 허용한다.

| 예외 | 원칙 충돌 | 허용 근거 |
|------|-----------|-----------|
| **본인 푸시 알림** — `RiskNotificationManager`가 위험 감지 시 본인 기기에 알림 표시 | 원칙 2 "백그라운드 알림 금지" | 보호자가 아닌 본인에게만 표시. 본인 보호형 앱의 핵심 기능 |
| **Foreground Service** — `MonitoringForegroundService` + `START_STICKY` 자동 지속 | 원칙 2 + 구현 규칙 "service 금지" | 실시간 위험 감지에 필수. Android 백그라운드 제한 때문에 서비스 없이 동작 불가 |
| **SeniorShieldApp 초기화** — `onCreate()`에서 서비스 시작 + 알림 채널 생성 | 구현 규칙 "빈 상태 유지" | Foreground Service 예외와 연동. 앱 시작 시 즉시 보호 활성화 |
| ~~SMS 자동 전송~~ | ~~원칙 2, 4~~ | **v2에서 B안(제거) 확정 — 더 이상 승인된 예외가 아님** |

---

## 아키텍처

```
:app (single module, Clean Architecture)

domain/
  model/        → RiskScore, RiskLevel, RiskSignal, RiskEvent, Guardian, PermissionStatus, PolicySummary
  repository/   → RiskRepository, SettingsRepository, GuardianRepository (interfaces only)

data/
  local/        → SettingsDataStore, GuardianDataStore, fake/FakeRiskEventDataSource
  repository/   → RiskRepositoryImpl, SettingsRepositoryImpl, GuardianRepositoryImpl
  di/           → DataModule (Hilt bindings)

monitoring/
  evaluator/    → RiskEvaluator (interface), FakeRiskEvaluator
  call/         → CallRiskMonitor (interface), FakeCallRiskMonitor
  appusage/     → AppUsageRiskMonitor (interface), FakeAppUsageRiskMonitor
  di/           → MonitoringModule

feature/
  home/         → HomeScreen, HomeViewModel, HomeUiState
  history/      → HistoryScreen, HistoryViewModel
  warning/      → WarningScreen, WarningViewModel
  settings/     → SettingsScreen
  guardian/     → GuardianScreen, GuardianViewModel, GuardianUiState
  permissions/  → PermissionsScreen, PermissionsViewModel
  policy/       → PolicyScreen, PolicyViewModel
  onboarding/   → OnboardingScreen, OnboardingViewModel
  splash/       → SplashScreen, SplashViewModel, SplashUiState

core/
  navigation/   → SeniorShieldDestination (enum), SeniorShieldNavGraph
  designsystem/ → Theme, Color, Type, SeniorShieldScaffold, StatusCard, LargeActionButton

di/             → AppModule
```

Navigation: Splash → Onboarding → Home → {History, Warning, Permissions, Policy, Settings, Guardian}

Tech: Min SDK 26, Target SDK 34, Kotlin 1.9.24, JVM 17, Compose + Material3, Navigation Compose 2.7.7, DataStore 1.1.1, Coroutines + Flow

---

## 구현 규칙

### 반드시 지킬 것
- 기존 아키텍처와 파일 구조를 유지한다.
- domain 레이어에 Android 의존성을 넣지 않는다.
- ViewModel은 StateFlow를 노출하고, Composable은 collectAsStateWithLifecycle로 수신한다.
- DI는 Hilt를 사용한다. 새 Repository나 Monitor를 추가하면 해당 Module에 바인딩을 추가한다.
- Guardian 연락은 ACTION_DIAL만 사용한다 (CALL_PHONE 권한 불필요).
- 새 화면을 추가하면 SeniorShieldDestination enum과 NavGraph에 모두 등록한다.

### 하지 않을 것
- 불필요한 리팩터링, 파일 이동, 네이밍 변경, 의존성 추가를 하지 않는다.
- AndroidManifest에 새 권한을 추가하지 않는다 (필요 시 보고만 한다).
- 새로운 foreground/background service를 추가하지 않는다 (기존 MonitoringForegroundService는 승인된 예외).
- SeniorShieldApp.kt에 새 로직을 추가하지 않는다 (기존 서비스 시작 + 알림 채널 생성은 승인된 예외).

---

## 고위험 작업 — 바로 구현 금지

아래 작업은 먼저 계획과 리스크 검토를 한 뒤 승인을 받고 구현해야 한다.

- AndroidManifest 수정
- permission 추가/삭제
- foreground/background service 관련 변경
- Fake monitor → 실제 monitor 교체
- 통화/보호자/외부 연락 흐름 변경
- SEND_SMS / READ_SMS / 알림 관련 기능
- Navigation 구조 변경
- DataStore / DI / Repository / ViewModel 계층을 동시에 건드리는 작업
- 감시로 오해될 수 있는 기능 아이디어

구현 전 반드시 5줄 계획을 먼저 세운다:
1. 수정 파일 목록
2. 변경 목적
3. 정책/권한 리스크
4. 테스트 방법
5. 중단해야 할 위험 조건

---
# CLAUDE.md 추가 지침 v2 (2026-03-31 리뷰 반영)

기존 CLAUDE.md의 "고위험 작업" 섹션 뒤에 삽입한다.

---

## SMS 방향 결정: 공개판에서 제거 (B안 확정)

- GuardianSmsManager 비활성화 (코드 보존, 호출 차단)
- SEND_SMS 권한 AndroidManifest에서 제거
- 보호자 연락 = ACTION_DIAL만 유지
- **자동 SMS 관련** 설정 토글 UI에서 숨김 (smsAlertEnabled)
- 이 결정은 제품 원칙 "자동 메시지 발송 금지"와 일치한다
- 단, 수동 문자 보내기 메뉴 토글(smsMenuEnabled, 기본 OFF)은 별도 — ACTION_SENDTO 방식으로 원칙 위반 아님

---

## RiskSession / 시퀀스 규칙 (P1, P2의 기초)

텔레뱅킹 감지, 반복 호출 감지는 모두 RiskSession 위에서 동작한다.
아래 규칙을 먼저 고정하고, P1/P2는 이 기준을 따른다.

### 세션 시작 조건
- 미저장/미확인 번호에서 수신 시
- 최근 저장(7일 이내) 번호에서 수신 시
- 앱 설치 감지 시 (사이드로딩)

### 세션 종료 조건
- 30분간 새 신호 없음 (타임아웃)
- 사용자가 경고 화면에서 "안전 확인" 선택 시
- 수동 초기화

### 반복 호출 판단 시간창
- 30분 이내 미저장/미확인 번호군에서 2회 이상 수신
- "동일 번호"만이 아니라 "동일/유사/미확인 번호군" 전체를 하나의 의심 그룹으로 판단
- 사기범은 발신번호를 바꿔가며 호출하므로, 같은 번호 2회로 축소 구현하지 않는다

### 텔레뱅킹 발신 판단 시간창
- 위험 세션 활성 상태에서만 동작
- 수상한 통화 종료 후 5분 이내에 은행 ARS 번호로 발신 시 경고
- 단독 은행 발신은 경고하지 않음 (오탐 방지)

### 팝업 즉시 발생 조건
1. 위험 점수 50점 이상 도달
2. 원격제어 앱 실행 감지 (단독)
3. 위험 세션 중 금융 앱 실행 → 쿨다운 인터럽터 발동 (BankingCooldownManager)
4. 위험 세션 중 은행 ARS 번호 발신
5. 원격제어 앱 직후 금융 앱 실행 (최고 위험)
6. 반복 호출 패턴 후 원격제어 또는 금융행동

### 팝업 동작 규칙
- 주 액션: "지금 전화 끊기"
- 위험 세션 중 금융 앱/텔레뱅킹 시도 시 60초 쿨다운 (행동 지연)
- TYPE_APPLICATION_OVERLAY 사용 — 통화 중에도 표시하기 위해 의도적으로 시스템 오버레이 사용
- 새 위협 발생 시 기존 팝업을 닫고 새 내용으로 갱신
- 자동 외부 연락 없음 (제품 원칙 준수)

---

## 텔레뱅킹 번호 관리

은행 ARS 번호는 코드에 하드코딩하지 않는다.
별도 registry (BankArsRegistry 또는 리소스 파일)에서 관리한다.

규칙:
- 번호 normalize 후 비교 (하이픈, 공백 제거)
- 향후 은행 추가/변경 시 registry만 수정
- 초기 목록: 국민, 신한, 우리, 하나, 농협, 기업, 카카오뱅크, 토스뱅크

---

## 감지 로직 추가 RiskSignal

| Signal | 조건 | 점수 |
|--------|------|:----:|
| REPEATED_UNKNOWN_CALLER | 미확인 번호군 30분 내 2회+ 수신 | +15 |
| REPEATED_CALL_THEN_LONG_TALK | 반복 호출 후 3분+ 통화 | +20 |
| TELEBANKING_AFTER_SUSPICIOUS | 위험 세션 중 은행 ARS 발신 | +25 |

복합 패턴:
- 반복 호출 + 원격제어 → 즉시 CRITICAL
- 반복 호출 + 금융 앱/텔레뱅킹 → 즉시 CRITICAL

---

## 구현 우선순위

| 순위 | 작업 |
|:----:|------|
| P0 | SMS 비활성화 |
| P0.5 | RiskSession/시퀀스 규칙 코드 고정 |
| P1 | 텔레뱅킹 감지 |
| P2 | 반복 호출 패턴 감지 |
| P3 | 팝업 + 쿨다운 정교화 |
| P4 | 텔레뱅킹 유도 시뮬레이션 |
| P5 | 현황 보고서 최종 반영 |

---

## 단계별 완료 규칙

각 단계(P0~P4) 완료 시 반드시 수행:
1. 빌드 확인 (compile error 0)
2. 단위 테스트 통과
3. 정책 체크 (/project:ss-policy)
4. 문서 동기화 (해당 단계의 변경 내용을 보고서에 즉시 메모)

P5에서 최종 보고서를 정리하되, 단계별 메모는 즉시 반영한다.



## 완료 보고 형식

모든 구현 작업의 완료 보고는 아래 형식을 따른다:

```
1. 변경 파일
2. 핵심 변경
3. 확인한 것
4. 미확인/리스크
```

모든 리뷰 작업의 출력은 아래 형식을 따른다:

```
- 수정 필수
- 수정 권장
- 정책/권한 리스크
- 미확인 사항
```

---

## 서브에이전트 구성

| 에이전트 | 역할 | 모델 | 파일 수정 |
|---------|------|------|----------|
| senior-shield | 계획, 리뷰, 정책 검토 | sonnet | 안 함 |
| senior-shield-impl | 자율 코드 구현 | sonnet | 함 (승인 범위 내) |
| senior-shield-advisor | 문서, 전략, 사업 판단 | opus | 안 함 |
| bug-investigator | 버그 원인 분석 | sonnet | 안 함 |
| test-runner | 테스트 실행/분석 | sonnet | 안 함 |

---

## 슬래시 명령어

프로젝트에 등록된 커스텀 슬래시 명령어:

| 명령어 | 용도 |
|--------|------|
| /ss-status | 프로젝트 현황 분석 |
| /ss-plan | 구현 전 계획 수립 |
| /ss-impl | 승인된 범위 내 구현 |
| /ss-review | 변경사항 리뷰 |
| /ss-test | 테스트 실행 및 분석 |
| /ss-policy | 제품 원칙 위반 검사 |
| /ss-bug | 버그 원인 추적 |
| /ss-doc | 기획/문서 작업 |
| /ss-final | 퇴근 전 최종 점검 |
