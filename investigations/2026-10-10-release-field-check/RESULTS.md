# 릴리스 전 실기 확인 결과 — 2026-10-10

> 지시문: `DIRECTIVE.md`(v0.1 + §0.1 tc-runner 합의). 실행: Claude(SeniorShield 세션)가 adb를 직접 구동. 사람 조작(보조폰 착신·통화 종료)은 사용자가 수행.
> 증거: `evidence/`(스크린샷 34장, UI 덤프, 단말 로그 946,176 B). **통화 번호가 포함될 수 있어 `.git/info/exclude`로 커밋 제외. 이 문서에는 번호·IMEI를 쓰지 않는다.**

## 0. 환경

| 항목 | 값 |
|---|---|
| 단말 | THOR3 AT-M160 `6d407d28`, Android 16 (SDK 36), 빌드 `Z082609IZ1005`, 480×800 @240dpi, SKT SIM, 음성망 IN_SERVICE |
| 단말 사용 | tc-runner-74와 시간 분할. 예약 20:01, 넘김 20:25, 반납 20:47 KST. 잠금 `~/.tc_runner_locks/ss-6d407d28.lock` 생성 후 삭제 |
| adb | SDK platform-tools 34.0.5(서버와 동일 버전). 모든 명령에 `-s` 사용. kill-server 0 |
| 앱 | `app-debug.apk` 20,915,839 B, SHA-256 `67a9f005…93a8e5`. 소스는 main `0414984` + 통화 감시 설정 읽기 수정(미커밋 4파일). versionName 1.1 (2), targetSdk 34 |
| 권한 | appops `SYSTEM_ALERT_WINDOW`·`GET_USAGE_STATS` allow. `READ_PHONE_STATE`·`POST_NOTIFICATIONS`·`READ_CONTACTS`·`READ_CALL_LOG` grant. `PROCESS_OUTGOING_CALLS`는 부여하지 않음 |
| 보호자 | 앱 내부에 `TestGuardian`과 존재하지 않는 더미 번호로 등록. 단말 연락처는 건드리지 않음 |

### 트리거 방법 (지시문에서 바꾼 점)

- 실제 TRIGGER는 **`SUSPICIOUS_APP_INSTALLED`**로 만들었다. 우리 빌드의 androidTest APK(`com.example.seniorshield.test`, 런처 아이콘 없음)를 `adb install`하는 방식이다. 설치원이 null이므로 사이드로딩으로 판정된다.
  - 단말에 원격제어 앱(RSupport 포함)이 없었다. Play 설치에는 Google 로그인이 필요해 기준선 단말의 계정이 바뀐다. 그래서 이 방식을 택했다.
  - 원격제어 감지 경로는 이전 A′ 실기에서 이미 검증됐다. 이번 판정 대상(팝업 UI)은 어떤 TRIGGER로 띄우든 같다.
- **Home 연락 버튼**은 위험 수준이 HIGH 이상일 때만 나온다(`HomeScreen.kt:126`, 설계). 의심 앱 설치 단독은 40점 MEDIUM이어서 F2는 설정의 "원격제어 앱 실행 시뮬레이션"(HIGH 이벤트 발행)으로 상태를 만들었다. Home 대화상자는 보호자 목록과 토글을 직접 읽으므로 이벤트 출처는 판정에 영향이 없다.
- 디버그 팝업은 guardian이 항상 null이다. 그래서 **F3 판정에는 쓰지 않았다**.
- 단말 상태를 보존하려고 문자·전화 버튼은 **표시 여부만 확인하고 누르지 않았다**. 문자 앱 임시 저장이 남는 것을 막기 위해서다. 버튼의 Intent 동작은 이번 변경과 무관한 기존 동작이다.

## 1. 판정

| ID | 항목 | 판정 | 근거 (evidence) |
|---|---|---|---|
| F3 | 위험 팝업 문자 버튼 토글 | **PASS** | OFF(`11_A_popup_toggle_off`): 버튼 3개(일단 닫기·위험 경고 해제·앱 열어서 확인하기), 문자 버튼 없음. ON(`21_B_popup_toggle_on`): "등록된 보호자에게 문자 보내기" 표시. 두 회차 모두 실제 TRIGGER로 띄운 production 팝업(`popup shown on state transition`) |
| F2 | Home 연락 대화상자 토글 | **PASS** | OFF(`16_A_home_dialog_off`): "보호자에게 전화하기" + 닫기. ON(`25_B_home_dialog_on`): 전화 + "보호자에게 문자 보내기" + 닫기. 대화상자는 양쪽 모두 유지 |
| F1 | 런처 아이콘 | **PASS(부분)** / 원형·테마 **미행사** | `30_all_apps_last`: 새 아이콘이 런처 마스크(둥근 사각)에서 정상 표시되고 다른 앱과 같은 모양이다. 원형 마스크·테마 아이콘(monochrome)은 단말 설정을 바꿔야 해서 미행사. 사용자 육안 확인 권장 |
| F4 | 통화 UI | **PASS** | 미저장 번호 착신 → `UNKNOWN_CALLER` GUARDED → 통화 중 TRIGGER → CRITICAL 팝업(`32_F4_incall_popup`): 주 버튼 "전화 앱으로 이동", 보조 "통화 경고 닫기". 탭하자 `org.codeaurora.dialer` InCallActivity가 앞에 뜸(`33_…`). 로그상 `opening in-call screen` → `팝업 닫힘` 0.53초(설계 500ms). 통화는 계속 OFFHOOK, 자동 종료 0. 통화는 사용자가 종료 |
| F4-sub | 지연 중 새 경고를 이전 지연 콜백이 닫지 않음 | **미행사** | 지연 창 500ms라 수동 재현 불가. 단위 테스트 근거로 대체 |
| F4b | 전화 권한 철회 후 재진입 | **PASS** | `pm revoke READ_PHONE_STATE`(OS 설정 철회와 같음) → OS가 프로세스 종료·재시작(pid 변경, `service started`) → `permission not granted — emitting NoPermission`. 재진입 화면(`34_…`)에 "필수 권한이 허용되지 않았습니다" 안내. 오늘 크래시(AndroidRuntime FATAL) 0 |
| F5 | 통화 경로 회귀 (설정 읽기 수정) | **PASS(정상 경로)** | OFFHOOK `통화 임계 대기: 180초 (테스트모드=false…)`. IDLE `call ended (OFFHOOK→IDLE) 64초` → 의심 통화 종료 기록 → `signals emitted (FINAL): [UNKNOWN_CALLER]` → RESET. 실패 경로(DataStore 손상)는 실기에서 재현하지 않음(단위 테스트 근거) |

## 2. 관찰 사항 (결함 아님)

- **α 억제 실증**: 회차 B의 첫 재트리거(20:32:22)는 `non-call session respawn suppressed by α`로 억제됐다. 안전 확인(20:31:50) 뒤 60초 이내였다. 60초가 지난 뒤(20:33:05)에는 정상으로 팝업이 떴다. 2026-07-20 확정 결정 4번과 일치한다.
- **프로세스 재시작 시 세션 소실**: F4b에서 OS가 프로세스를 재시작하자 통화 중 만들어진 CRITICAL 세션이 사라져 Home이 SAFE로 표시됐다. 감지 이력 6건은 보존됐다. 세션이 메모리에만 있는 기존 동작이며, 백로그 "재시작 지속성"과 같은 주제다.
- **UsageStats (오프라인 로그 분석, 2026-10-10 21:2x)**: Android 16 THOR3에서 앱 사용 감지의 **주 경로는 동작한다**.
  - `tryQueryUsageStats(interval=4)`가 실기 중 전면에 올라온 앱을 폴링(5초) 안에 잡았다: 전화 앱(통화 화면) 10회, 설정 등 10회 이상, 연락처, 런처, SeniorShield.
  - 최종 폴백인 `tryQueryEvents`는 이벤트를 받지만(totalEvents 1~2) `MOVE_TO_FOREGROUND`는 0건이었다(전체 426회 중 foreground>0 = 0). 주 경로가 감지하므로 감지가 끊기지는 않는다.
  - "모든 UsageStats 조회에서 raw 관측 없음 — 권한 미부여 또는 기기 호환성 문제"(238회)는 실제로는 "30초 안에 사용된 앱 없음" 구간에 찍혔다. 경고 문구가 오해를 부르므로 문구 정정은 백로그다.
  - 남은 확인: 등록된 원격제어 앱 실제 실행 → `REMOTE_CONTROL_APP_OPENED`까지의 end-to-end는 이 단말에서 미확인이다(원격제어 앱 미설치). 필요하면 사용자 승인을 받아 설치한 뒤 별도 실기로 확인한다.

## 3. 반납 (A) 실측

- `com.example.seniorshield`와 `com.example.seniorshield.test` 삭제. 원격제어 앱 설치 0, Google 로그인 0.
- `stay_on_while_plugged_in` 15 → 0(시작 시 기록한 값).
- 접근성 null, DIALER 역할 `org.codeaurora.dialer`, IME `com.alt.folderkeyboard`, ko-KR — 모두 시작 시점과 같음.
- 단말 `/data/local/tmp`의 `ss_*` 0, logcat 프로세스 종료. 화면은 런처, 통화 IDLE.
- 잠금을 삭제하고 tc-runner-74에 반납을 통보했다(20:47).
- 단말에 남은 흔적: 착신 통화기록 1건(사용자가 시작한 시험 통화), 패키지 설치·삭제 이력. 런처 홈 배치·계산기·연락처는 접촉하지 않음.
