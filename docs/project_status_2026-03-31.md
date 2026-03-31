# SeniorShield (시니어쉴드) 프로젝트 동작 현황서

**작성일**: 2026-03-31  
**버전**: 1.0 (Initial Release)  
**저장소**: https://github.com/jinwu-pixel/Senior-Shield

---

## 1. 프로젝트 개요

시니어쉴드는 고령층 금융사기(보이스피싱) 예방을 위한 Android 앱이다.  
통화, 앱 사용, 앱 설치, 기기 환경을 실시간 모니터링하여 위험 패턴을 감지하고,  
본인에게 즉시 경고한다.

### 핵심 원칙
- **본인 보호형(self-protection only)**: 다른 성인을 자동 감시하지 않음
- **외부 연락은 사용자가 명시적으로 시작**: ACTION_DIAL만 사용 (자동 전화 금지)
- **감시로 오해될 수 있는 기능 금지**: 위치 추적, 행동 로깅 외부 전송, 보호자 대시보드 없음

### 기술 스택
| 항목 | 사양 |
|------|------|
| 언어 | Kotlin 1.9.24 |
| UI | Jetpack Compose + Material3 |
| 아키텍처 | Single Module Clean Architecture (domain / data / feature) |
| DI | Hilt 2.52 |
| 비동기 | Coroutines 1.8.1 + Flow |
| 로컬 저장 | Room + DataStore Preferences |
| 네비게이션 | Navigation Compose 2.7.7 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |
| JVM | 17 |

---

## 2. 아키텍처

```
:app (single module)
├── domain/          순수 Kotlin — Android 의존성 없음
│   ├── model/       RiskScore, RiskLevel, RiskSignal, RiskEvent, Guardian 등
│   └── repository/  RiskRepository, SettingsRepository, GuardianRepository (인터페이스)
│
├── data/            Android 의존성 허용
│   ├── local/       SettingsDataStore, GuardianDataStore, Room DB
│   ├── repository/  인터페이스 구현체
│   └── di/          DataModule, DatabaseModule (Hilt)
│
├── monitoring/      위험 감지 엔진
│   ├── call/        통화 모니터 + 발신자 검증
│   ├── appusage/    앱 사용 모니터
│   ├── appinstall/  앱 설치 모니터
│   ├── deviceenv/   기기 환경 모니터 (루팅 탐지)
│   ├── evaluator/   위험 점수 평가
│   ├── event/       위험 이벤트 생성
│   ├── session/     위험 세션 관리
│   ├── orchestrator/ 전체 조율 (Coordinator)
│   └── di/          MonitoringModule (Hilt)
│
├── feature/         화면별 패키지
│   ├── splash/      스플래시
│   ├── onboarding/  온보딩
│   ├── home/        홈 (메인 대시보드)
│   ├── history/     감지 기록
│   ├── warning/     위험 경고 + 보호자 연락
│   ├── permissions/ 권한 설정 안내
│   ├── policy/      서비스 원칙
│   ├── settings/    앱 설정 + 디버그
│   ├── guardian/    보호자 관리
│   └── simulation/  보이스피싱 대응 연습
│
├── core/
│   ├── navigation/  SeniorShieldDestination, NavGraph
│   ├── designsystem/ Theme, Color, Type, 공통 컴포넌트
│   ├── notification/ 본인 알림
│   ├── overlay/     위험 팝업, 뱅킹 쿨다운
│   ├── sms/         보호자 SMS (승인된 예외)
│   └── util/        ContactIntentHelper, CallEndHelper
│
└── di/              AppModule
```

---

## 3. 화면 구성

### 네비게이션 플로우
```
Splash → Onboarding → Permissions → Home
                                      ├── History (감지 기록)
                                      ├── Warning (위험 경고 → Guardian 선택)
                                      ├── Permissions (권한 설정)
                                      ├── Policy (서비스 원칙)
                                      ├── Settings (앱 설정)
                                      ├── Guardian (보호자 관리)
                                      └── Simulation List → Simulation Play
```

### 화면별 기능

| 화면 | Destination | 기능 |
|------|-------------|------|
| **Splash** | `splash` | 온보딩 완료 여부 확인 → 분기 |
| **Onboarding** | `onboarding` | 앱 소개, 최초 실행 시 표시 |
| **Home** | `home` | 현재 위험 상태, 주간 통계, 예방 팁, 권한 경고 배너 |
| **History** | `history` | 전체 감지 기록 목록 (빈 상태 처리 포함) |
| **Warning** | `warning` | 위험 경고 카드, 체크리스트, 보호자/공식기관 연락 |
| **Permissions** | `permissions` | 필수 권한 상태 확인 및 설정 안내 |
| **Policy** | `policy` | 서비스 원칙 및 개인정보 처리 방침 |
| **Settings** | `settings` | 앱 설정, 디버그 모드 |
| **Guardian** | `guardian` | 보호자 등록/삭제 (최대 3명) |
| **Simulation List** | `simulation_list` | 보이스피싱 대응 연습 시나리오 목록 |
| **Simulation Play** | `simulation_play/{id}` | 시나리오별 단계적 체험 + 결과 |

---

## 4. 모니터링 엔진

### 4.1 신호 체계

7개 위험 신호(RiskSignal)를 감지한다:

| 신호 | 가중치 | 감지 소스 | 설명 |
|------|:------:|-----------|------|
| `UNKNOWN_CALLER` | 20점 | CallerContactChecker | 연락처에 없는 발신자 |
| `UNVERIFIED_CALLER` | 20점 | CallerContactChecker | 7일 이내 신규 저장 연락처 |
| `LONG_CALL_DURATION` | 30점 | RealCallRiskMonitor | 3분 이상 통화 |
| `REMOTE_CONTROL_APP_OPENED` | 30점 | RealAppUsageRiskMonitor | 원격제어 앱 포그라운드 |
| `BANKING_APP_OPENED_AFTER_REMOTE_APP` | 40점 | RealAppUsageRiskMonitor | 원격제어 후 금융 앱 실행 |
| `SUSPICIOUS_APP_INSTALLED` | 40점 | RealAppInstallRiskMonitor | 사이드로딩 또는 원격제어 앱 설치 |
| `HIGH_RISK_DEVICE_ENVIRONMENT` | 20점 | RealDeviceEnvironmentRiskMonitor | 루팅/test-keys 탐지 |

### 4.2 위험 수준

| 수준 | 점수 범위 | 대응 |
|------|:---------:|------|
| **LOW** | 0~24 | 정상 상태 |
| **MEDIUM** | 25~49 | 이력 기록 |
| **HIGH** | 50~79 | 이력 + 본인 알림 + 위험 팝업(능동 위협 시) + SMS(능동 위협 시) |
| **CRITICAL** | 80+ | HIGH와 동일, 최고 수준 경고 |

### 4.3 모니터 구현 현황

| 모니터 | Real 구현 | Fake | DI 활성 |
|--------|:---------:|:----:|:-------:|
| CallRiskMonitor | RealCallRiskMonitor | FakeCallRiskMonitor | **Real** |
| AppUsageRiskMonitor | RealAppUsageRiskMonitor | FakeAppUsageRiskMonitor | **Real** |
| AppInstallRiskMonitor | RealAppInstallRiskMonitor | FakeAppInstallRiskMonitor | **Real** |
| DeviceEnvironmentRiskMonitor | RealDeviceEnvironmentRiskMonitor | FakeDeviceEnvironmentRiskMonitor | **Real** |
| RiskEvaluator | RiskEvaluatorImpl | FakeRiskEvaluator | **Real** |

모든 모니터가 Real 구현체로 바인딩되어 있으며, MonitoringModule.kt에서 주석 전환으로 Fake 복원 가능.

### 4.4 통화 모니터 상세

- **API 31+**: `TelephonyCallback` + `BroadcastReceiver`(EXTRA_INCOMING_NUMBER) 조합
- **API 26~30**: `PhoneStateListener` (레거시)
- **P1 수정 적용**: API 31+ 타이밍 경쟁 해소 — BroadcastReceiver에서 번호 확보 시 RINGING 컨텍스트를 re-emit
- **발신자 검증**: `CallerContactChecker`가 `CallerCheckResult` enum 반환
  - `NOT_IN_CONTACTS` → UNKNOWN_CALLER (25점)
  - `NEW_CONTACT` (≤7일) → UNVERIFIED_CALLER (20점)
  - `VERIFIED_CONTACT` → 신호 없음
  - `UNAVAILABLE` → 판단 보류

### 4.5 기기 환경 모니터 상세

`RealDeviceEnvironmentRiskMonitor`가 앱 시작 시 1회 체크:
- su 바이너리 존재 여부 (6개 경로)
- Superuser.apk 존재 여부
- Build.TAGS에 `test-keys` 포함 여부
- 루팅 관련 패키지 설치 여부 (6개 패키지, `<queries>` 블록으로 API 30+ 대응)

### 4.6 세션 관리

`RiskSessionTracker`가 위험 세션의 생명주기를 관리:
- 첫 신호 발생 시 세션 생성
- 신호는 누적만 되고 감소하지 않음 (시퀀스 기반 탐지)
- 30분 비활동 시 세션 자동 만료
- 에스컬레이션: 이전 알림 수준보다 높아질 때만 새 알림 발행

### 4.7 조율기 (Coordinator)

`DefaultRiskDetectionCoordinator`가 5개 스트림을 combine:
```
callSignals + appUsageSignals + bankingForeground + installSignals + deviceEnvSignals
    → 세션 누적 → 점수 평가 → 에스컬레이션 처리
```

에스컬레이션 시:
1. 이벤트 기록 (RoomDB)
2. HIGH+ → 본인 알림
3. 능동 위협(원격제어/뱅킹 연계/의심 앱 설치) 시 → 전체화면 팝업 + 보호자 SMS
4. HIGH+ + 뱅킹 포그라운드 → 60초 강제 대기(쿨다운 인터럽터)

---

## 5. 권한 구성

| 권한 | 용도 | 유형 |
|------|------|------|
| `READ_PHONE_STATE` | 통화 상태 감지 | 런타임 |
| `PACKAGE_USAGE_STATS` | 앱 사용 기록 조회 | 시스템 설정 |
| `POST_NOTIFICATIONS` | 위험 경고 알림 (Android 13+) | 런타임 |
| `FOREGROUND_SERVICE` | 백그라운드 지속 실행 | 일반 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 서비스 유형 | 일반 |
| `SYSTEM_ALERT_WINDOW` | 위험 팝업 오버레이 | 시스템 설정 |
| `ANSWER_PHONE_CALLS` | "전화 끊기" 버튼 (API 28+) | 런타임 |
| `READ_CONTACTS` | 발신자 연락처 조회 | 런타임 |
| `READ_CALL_LOG` | 수신 번호 캡처 (API 29+) | 런타임 |
| `SEND_SMS` | 보호자 SMS 전송 (**승인된 예외**) | 런타임 |

---

## 6. 시뮬레이션 (보이스피싱 대응 연습)

5개 시나리오로 실제 사기 패턴을 체험하고 올바른 대응을 학습:

| ID | 시나리오 | 단계 | 핵심 학습 |
|----|---------|:----:|-----------|
| `prosecutor` | 검찰 사칭 사기 | 3 | 검찰은 전화로 수사 통보 안 함, 안전 계좌 없음 |
| `loan` | 대출 사기 | 2 | 선입금 요구는 100% 사기 |
| `family` | 자녀 사칭 사기 | 2 | 다른 번호 급전 요구 시 기존 번호로 직접 확인 |
| `remote_control` | 원격제어 앱 설치 유도 | 3 | 팀뷰어/애니데스크 설치 요구는 사기, 접속 코드 절대 불가 |
| `bank_impersonation` | 은행 사칭 개인정보 탈취 | 2 | 은행은 전화로 비밀번호/OTP 안 물음 |

각 단계마다 2개 선택지(정답/오답) + 피드백을 제공하며, 완료 시 정답 수와 핵심 기억사항을 표시.
진행 막대(LinearProgressIndicator)로 현재 진행률을 시각적으로 표시.

---

## 7. 디자인 시스템

### 타이포그래피 (고령층 맞춤)

| 스타일 | 크기 | 용도 |
|--------|:----:|------|
| headlineLarge | 28sp | 경고 화면 핵심 제목 |
| headlineMedium | 24sp | 일반 화면 메인 제목 |
| titleLarge | 20sp | 카드/섹션 제목 |
| titleMedium | 18sp | 버튼 텍스트, 기관 정보 |
| titleSmall | 17sp | 카드/배너 내 라벨 |
| bodyLarge | 17sp | 본문 텍스트 |
| bodyMedium | 15sp | 보조 설명 |
| bodySmall | 16sp | 배너 설명 |
| labelLarge | 18sp | 버튼, 진행률 |
| labelMedium | 16sp | 소형 버튼 |

### 색상 체계

| 색상 | 코드 | 용도 |
|------|------|------|
| BluePrimary | #1F4C8F | 주조색 (신뢰감) |
| StatusGreen | #2E7D32 | LOW (안전) |
| StatusYellow | #F9A825 | MEDIUM (주의) |
| StatusOrange | #E67E22 | HIGH (경고) |
| StatusRed | #C0392B | CRITICAL (위험) |

### 공통 컴포넌트

- **PrimaryButton**: 56dp 높이, 가장 중요한 행동
- **SecondaryButton**: 56dp 높이, 보조 행동
- **BasicTextButton**: 48dp 높이, 닫기 등
- **StatusCard**: RiskLevel별 아이콘/색상/테두리 자동 적용
- **SeniorShieldScaffold**: 통일된 상단바 + 뒤로가기

---

## 8. 테스트 현황

**총 45개 단위 테스트** — 전체 통과

| 테스트 클래스 | 수 | 검증 대상 |
|---------------|:--:|-----------|
| CallSignalMapperTest | 12 | 통화 신호 매핑, UNKNOWN/UNVERIFIED 상호 배타성 |
| RiskEvaluatorImplTest | 10 | 가중치 계산, 위험 수준 임계값, 중복 제거 |
| RiskEventFactoryTest | 7 | 이벤트 생성, 신호별 메시지 |
| RiskSessionTrackerTest | 10 | 세션 생성/누적/유지, markNotified, reset |
| WarningViewModelTest | 6 | uiState combine, 보호자/이벤트 반영, 상태 전환 |

### 테스트 인프라
- `junit:4.13.2` + `kotlinx-coroutines-test:1.8.1`
- `unitTests.isReturnDefaultValues = true` (android.util.Log 등 기본값 반환)
- ViewModel 테스트: `Dispatchers.setMain(UnconfinedTestDispatcher)` + `backgroundScope` 패턴

### 알려진 제한
- `RiskSessionTracker` 타임아웃(30분) 만료 경로: `System.currentTimeMillis()` 직접 호출로 테스트 불가 (Clock 주입 리팩터링 필요)
- `HomeViewModel`: Context 의존성으로 순수 단위 테스트 불가 (Robolectric 필요)

---

## 9. 정책 준수 현황

### 위반 없음 확인
- ACTION_CALL 사용 없음 (ACTION_DIAL만 사용)
- WorkManager / 외부 서버 전송 없음
- 위치 추적 없음
- 보호자 대시보드 없음

### 승인된 예외 (CLAUDE.md 등록, 2026-03-31 확정)

| 예외 | 원칙 충돌 | 허용 근거 |
|------|-----------|-----------|
| 본인 푸시 알림 | 원칙 2 | 보호자가 아닌 본인에게만 표시 |
| Foreground Service + START_STICKY | 원칙 2 | 실시간 위험 감지에 필수 |
| SeniorShieldApp onCreate 초기화 | 구현 규칙 | 앱 시작 시 즉시 보호 활성화 |
| SMS 자동 전송 + SEND_SMS | 원칙 2, 4 | 사용자 명시적 유지 요청, 설정 토글 사전 동의 |

### 주의 필요 (1건)
- `GuardianSmsManager.kt:83` — SMS 본문에 "보호자 앱에서 확인해 주세요." 문구가 존재하지 않는 보호자 앱을 언급. 수정 권장.

---

## 10. Git 이력

| 커밋 | 내용 | 변경 |
|------|------|------|
| `73d1464` | Initial commit: P1/P2/B1/B2 + 정책 예외 | 127파일, +7,345줄 |
| `e3b4481` | B3: isVerifiedCaller 구현, B4: 폴백 정책 | 3파일, +112/-40 |
| `3abeba9` | 테스트 보강 + Fake 패턴 완성 | 6파일, +424줄 |
| `2351d34` | 제품 완성도: 폰트/시나리오/접근성/진행 막대 | 6파일, +100/-9 |

**총 소스 파일**: 92개 (main) + 5개 (test)

---

## 11. 오늘(2026-03-31) 작업 내역

### B3: isVerifiedCaller 판정 로직 구현
- `CallerContactChecker`에 `CallerCheckResult` enum 도입 (NOT_IN_CONTACTS / NEW_CONTACT / VERIFIED_CONTACT / UNAVAILABLE)
- `RealCallRiskMonitor`에서 `isVerifiedCaller`를 올바르게 세팅
- UNKNOWN_CALLER와 UNVERIFIED_CALLER를 상호 배타적으로 분리
- CallSignalMapperTest에 4개 테스트 추가

### B4: queryExistenceOnly 폴백 정책
- 타임스탬프 미지원 단말에서 저장된 번호를 VERIFIED_CONTACT로 처리 (안전 측)
- 정책 결정 근거를 KDoc에 문서화

### 테스트 보강 (29 → 45개)
- RiskSessionTrackerTest 10개: 세션 생명주기 전체 분기 검증
- WarningViewModelTest 6개: WhileSubscribed stateIn 테스트 패턴 확립
- `unitTests.isReturnDefaultValues = true` 설정 추가

### Fake 패턴 완성
- FakeAppInstallRiskMonitor 신규 생성
- FakeRiskEvaluator 신규 생성
- MonitoringModule에 모든 바인딩에 Fake 전환 가이드 주석 추가

### 제품 완성도 개선
- 폰트 크기 보정: titleSmall(17sp), bodySmall(16sp), labelMedium(16sp) 정의
- StatusCard body: bodyMedium(15sp) → bodyLarge(17sp) 상향
- 시뮬레이션 시나리오 2개 추가 (3→5개): 원격제어 앱 설치 유도, 은행 사칭
- 불릿 문자 수정 (공백 → "•")
- 애니메이션 진행 막대(LinearProgressIndicator) 추가
- contentDescription 보강 (권한 경고, 체크리스트 아이콘)

### 정책 검사
- 전체 코드 정책 위반 검사 실행 → 위반 없음 (승인된 예외 제외)
- GuardianSmsManager SMS 문구 수정 필요 1건 발견

---

## 12. 향후 작업

| 순위 | 작업 | 설명 |
|------|------|------|
| 1 | GuardianSmsManager SMS 문구 수정 | "보호자 앱" → 정확한 문구로 변경 |
| 2 | GitHub Actions CI | push/PR 시 자동 빌드 + 테스트 |
| 3 | ProGuard/R8 규칙 | 릴리스 빌드 난독화 확인 |
| 4 | 앱 아이콘/스플래시 브랜딩 | 기본 아이콘 → 시니어쉴드 디자인 |
| 5 | HomeViewModel 테스트 | Robolectric 도입 |
| 6 | 릴리스 빌드 + 서명 설정 | Play Store 배포 준비 |
