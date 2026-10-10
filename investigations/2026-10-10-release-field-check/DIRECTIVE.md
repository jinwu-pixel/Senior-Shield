# 릴리스 전 실기 확인 지시문 — 통화 UI · 문자 버튼 토글 · 런처 아이콘 (v0.1 초안)

> 등급: **S1(실기기 조작)** — 사용자 지시 "실기는 tc-runner와 조율해서 단말 사용해서 진행"(2026-10-10).
> 실행: Claude(SeniorShield 세션)가 adb를 직접 구동한다(tc-runner-74 권고 방식 (가)). 사람 조작이 필요한 단계(보조폰 착신, 화면 육안 확인, OS 설정 변경)는 사용자가 수행한다.
> 성격: **증거 수집 전용**이다. 코드·테스트·기존 문서는 수정하지 않는다. FAIL이 나와도 고치지 않고 기록만 한다.

## 0. tc-runner 조율 결과 (2026-10-10)

- **사용자 지시 정정**: 실기는 **THOR3 단말 1대**로 진행한다. 단말이 한 대이므로 tc-runner와 **시간을 나눠 쓴다**. 대상은 PC에서 adb로 보이는 유일한 THOR3인 `6d407d28`이다.
- **tc-runner-74(계산기 트랙)**: `6d407d28`에서 Codex T063c를 실행 중이다. **"넘김 가능" 통보를 받기 전에는 읽기 전용 adb도 포함해 접촉 금지.**
  - 넘김 조건으로 합의할 것: 일정, SIM 유무, 원격제어 앱 유무, 기존 SeniorShield 설치 여부, 계산기 TC·화면 지도 기준선 영향, 반납 방식 (A) 삭제·원복 또는 (B) force-stop·오버레이 회수, 건드리면 안 되는 설정. tc-runner-74에 요청했고 답을 기다린다.
- **tc-runner-b3(LGU+ Lab AT)**: `6d407d9e`를 점유하고 있으며, 착신 시험 중 일시정지 상태다. **접촉 금지이며, 이 단말로 전화를 거는 것도 금지.**
- THOR2 계열(`B06201249E00030C`, `B06201249E0002F0`, `B2700125BW000115`)은 이번에 쓰지 않는다.
- **잠금**: `~/.tc_runner_locks/ss-6d407d28.lock`. 넘겨받는 순간 만들고(세션 이름과 시작 시각 기록) 반납할 때 지운 뒤 tc-runner-74에 알린다.
- **반납 상태**: tc-runner-74와 합의한 방식을 따른다. §5의 "앱 SAFE 복원"보다 이쪽이 우선한다.

### 0.1 tc-runner-74 합의 (2026-10-10 20:01)

- **넘김 시점**: 예약 잠금(`reserved`)을 20:01에 만들었다. 이 잠금은 계산기 실행기의 다음 배치만 막는다. 현재 배치(`t063c-repaired2`)가 끝나면 tc-runner-74가 확인한 뒤 **"넘김 가능"을 통보한다(예상 20:45~21:15 KST).** 그전에는 시작하지 않는다.
- **넘겨받은 직후 읽기 전용으로 확인하고 기록할 것**:
  - 망 등록 상태(착신 가능 여부)
  - `pm list packages`: SeniorShield 기설치 여부, 원격제어 앱 유무
  - `settings get global stay_on_while_plugged_in` 원래 값
  - 원격제어 앱이 없으면 설치하기 전에 **사용자에게 질의**한다(THOR3는 빌드 기준선 단말이다).
- **반납 = (A)**:
  - 원래 설치돼 있지 않았다면 SeniorShield를 삭제한다. 그 경우 권한·appops는 함께 사라진다.
  - 삭제하지 않는 경우에는 기록해 둔 원래 모드로 권한·appops를 복원한다.
  - `stay_on_while_plugged_in`은 기록한 원래 값으로 되돌린다.
  - logcat 파일은 pull한 뒤 단말에서 지운다.
  - 잠금을 지우고 tc-runner-74에 알린다.
  - §1의 "`pm clear`·앱 삭제 금지"는 THOR2 사용자 데이터 보존용 규칙이었다. 이번 THOR3에서는 이 합의가 우선한다.
- **접촉 금지**:
  - 기본 전화 앱 역할(SKT 전화). 역할 변경 요청이 뜨면 거절하거나 사용자에게 묻는다.
  - ko-KR 언어, IME `com.alt.folderkeyboard`, 글자·화면 크기, 런처 홈 배치, BT 상태
  - 계산기 앱(열지 않음)
  - 연락처 편집(읽기만 허용. 시험용 연락처는 `TCRUN_` 접두)
  - 접근성 서비스(켜지 않음)
  - 다른 잠금 파일, tc-runner 저장소 raw 디렉터리
- **보충 정보 (tc-runner-74, 2026-09-18 getprop 근거, 이전 빌드 기록이므로 넘겨받은 뒤 다시 확인)**:
  - SIM: `gsm.sim.state=LOADED`, 통신사 SKTelecom
  - RSupport `com.rsupport.rs.activity.rsupport`가 선탑재돼 있을 가능성이 있다. 이 패키지는 `RemoteControlAppRegistry`에 등록돼 있다(:37). 설치돼 있으면 F3·F4의 실제 TRIGGER를 **새 앱 설치 없이** 만들 수 있다.
  - SeniorShield는 설치 흔적이 없다 → 반납 (A)에 삭제를 포함한다.
- **원격제어 앱 설치 = 사용자 승인** ("원격제어앱 설치해도 좋아", 2026-10-10):
  1. 선탑재 RSupport가 실제로 설치돼 있으면 그것을 쓴다(새 설치 0).
  2. 없으면 `RemoteControlAppRegistry`에 등록된 **TeamViewer QuickSupport**(`com.teamviewer.` 접두)를 **단말의 Play 스토어에서** 설치한다. 인터넷에서 받은 APK는 쓰지 않는다.
  3. 새로 설치한 원격제어 앱은 반납 (A) 때 **삭제**한다. 선탑재 앱은 건드리지 않는다.
- **SeniorShield가 설치돼 있는 동안**: 런처·설정 목록이 바뀌고 오버레이가 뜨므로 tc-runner의 계산기 TC와 화면 지도 측정은 불가능하다. 그래서 반납 전에 반드시 삭제한다.
- **adb**:
  - 서버와 같은 SDK platform-tools adb 34.0.5만 쓴다. 다른 버전의 adb는 서버를 재시작시킨다.
  - 모든 명령에 `-s <serial>`을 붙인다.
  - `kill-server`, `start-server`, `reconnect`는 금지한다.
- **예상 밖의 단말 상태**(설정·역할·언어)가 보이면 원복하기 전에 사용자에게 묻는다. 사용자가 병행해서 조작했을 수 있다.

## 1. 대상 빌드

- 통화 감시 설정 읽기 수정 브랜치 `codex/call-settings-read-failure`의 게이트 통과 커밋으로 빌드한다(통화 경로 포함).
- 브랜치를 머지할 때 main이 그대로이면 머지 결과 트리가 이 브랜치 트리와 같다. **트리 SHA**를 기록한다.
- `assembleDebug`로 만든 APK의 크기와 SHA-256을 기록한다.
- **실측 (2026-10-10 19:24 게이트 빌드)**: `.worktrees/call-settings/app/build/outputs/apk/debug/app-debug.apk`
  - 크기 20,915,839 B, SHA-256 `67a9f005b0bb90e6b8c57387d5014c3d037a51c614308639cacffcb5ab93a8e5`
  - 소스 = base `0414984` + 미커밋 diff 4파일(+164/−3, diff sha256 앞자리 `e4fa65fa3c6b5389`)
  - R1 수정은 테스트 코드만 바꾸므로 이 APK는 그대로 유효하다. 실행 직전에 소스가 바뀌지 않았는지 diff 해시로 다시 확인한다.

## 2. 준비 (2026-07-22 safe-confirmation-field DIRECTIVE §3 재사용)

1. `adb -s <serial> devices -l`로 대상 단말이 authorized인지 확인한다. 모델, Android/SDK, 해상도를 기록한다. 그다음 잠금 파일을 만든다.
2. `install -r`만 쓴다. `pm clear`, 앱 제거, "전체 상태 초기화"는 쓰지 않는다. 기존 History와 보호자 데이터는 사용자 데이터이므로 보존한다.
3. 권한:
   - appops: `SYSTEM_ALERT_WINDOW=allow`, `GET_USAGE_STATS=allow`
   - `pm grant`: `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, `READ_CONTACTS`, `READ_CALL_LOG`
   - `PROCESS_OUTGOING_CALLS`는 부여하지 않는다(발신 경로를 쓰지 않음).
4. `svc power stayon true`. 테스트 모드 OFF("3분 (프로덕션)")를 확인한다. 문자 메뉴 토글의 **시작 값**을 기록한다.
5. 단말 쪽 로그:
   - 파일 `/data/local/tmp/ss_release_field.log`
   - 형식 `threadtime`, 7개 태그(`SeniorShield-CallMonitor`, `-Coordinator`, `-Overlay`, `-Session`, `-AppMonitor`, `-Cooldown`, `-EventSink`)
   - `nohup logcat -f`로 띄운 뒤 파일 크기가 실제로 늘어나는지 확인하고 시작한다.
6. 보호자 1명이 등록돼 있어야 한다. 없으면 **사용자가 확인한 테스트용 번호**로 GuardianAdd 화면에서 등록한다. 실제 지인 번호는 쓰지 않는다.
7. 원격제어 앱(TeamViewer 등, `RemoteControlAppRegistry`에 등록된 것)이 설치돼 있는지 `pm list packages`로 확인한다.
   - 없으면 F3과 F4의 팝업 단계는 **미행사**로 기록한다.
   - 설치는 별도 승인 사항이다.

## 3. 확인 항목

| ID | 항목 | 방법 | 판정 근거 |
|---|---|---|---|
| F1 | **런처 아이콘** | 홈/앱 서랍 스크린샷. 테마 아이콘(Android 13+)은 사용자가 OS 설정에서 켠 경우에만 확인 | 원형·사각 마스크 표시, 테마 ON 시 monochrome 레이어 표시. 육안 판단은 사용자 확인 |
| F2 | **Home 연락 대화상자 토글** | 토글 OFF → Home "보호자에게 연락하기" → 대화상자. 토글 ON → 같은 경로. ON 상태에서 "문자 보내기"를 누르면 문자 앱이 받는 사람만 채워진 채 열리는지 확인하고 **보내지 않고 뒤로** 나온다. 전화 버튼은 다이얼러가 열리는지만 확인하고 **발신하지 않는다** | OFF = 전화 버튼만, ON = 전화+문자. 대화상자는 양쪽 모두 유지된다. 자동 발신·발송 0 |
| F3 | **위험 팝업 문자 버튼 토글** | 비통화 상태에서 원격제어 앱 실행(`monkey -p <pkg> 1` 또는 사용자 탭) → 실제 TRIGGER 팝업. 토글 OFF/ON 각 1회. 회차 사이에는 "위험 경고 해제" 또는 안전 확인으로 SAFE 복귀. S2 재발화 억제 TTL 30초를 넘겨 진행 | OFF = 문자 버튼 없음, ON = 문자 버튼 있음(보호자 등록 시). 로그 `popup shown`. 디버그 팝업은 guardian이 항상 null이라 **판정에 쓰지 않는다** |
| F4 | **통화 UI** (REMAINING_CHECKS §1~4) | 사용자가 보조폰으로 시험 단말에 **비긴급 테스트 통화**를 건다. **번호를 확인해 `6d407d9e`로 걸리지 않게 한다.** 통화 중 원격제어 앱 실행 → 통화 중 팝업 → 사용자가 "전화 앱으로 이동"을 탭 | 전화 UI가 실제로 앞에 표시되고, 지연 후 해당 팝업만 닫히며, 새 경고의 팝업을 이전 지연 콜백이 닫지 않음. 자동 통화 종료 0 |
| F4b | 권한 철회 후 재진입 | 사용자가 OS 설정에서 전화 권한을 철회한 뒤 앱 재진입 → 종료 시 재부여 | 앱 크래시 0. OS의 프로세스 종료와 앱 크래시를 구분한다 |
| F5 | 통화 경로 회귀 (이번 수정) | F4 통화 로그에서 `통화 임계 대기: 180초 (테스트모드=false…)`, UNKNOWN_CALLER/FINAL/RESET 흐름 확인 | 설정 읽기 정상 경로 무변화. 실패 경로(DataStore 손상)는 실기에서 재현하지 않는다 |

## 4. 증거와 개인정보

- 증거 경로는 이 폴더 `evidence/`다. 화면 PNG, UI XML, 로그 발췌를 둔다. 로그에는 통화 번호가 포함될 수 있으므로 **`evidence/`는 `.git/info/exclude`에 등록해 커밋하지 않는다.**
- `RESULTS.md`에는 전화번호, IMEI, 통화 내용을 쓰지 않는다. 판정 어휘는 `PASS / FAIL / 미행사` 중 하나와 근거로 쓴다.

## 5. 복원 (종료 시)

- 테스트 모드 OFF, 문자 메뉴 토글을 시작 값으로, `svc power stayon false`, 철회했던 권한 재부여, 앱 SAFE 상태로 되돌린다.
- 단말 쪽 logcat 프로세스를 종료하고, 로그 파일을 `evidence/`로 가져온 뒤 단말에서 지운다.
- 잠금 파일을 삭제한다.

## 6. 중단 조건

- 대상 단말이 아닌 serial이 보일 때. 특히 `6d407d28`, `6d407d9e`.
- 잠금 충돌이 있을 때.
- 단말이 unauthorized일 때.
- 로그 수집이 성립하지 않을 때.
- 외부 연락 위험이 있을 때. 예: 다이얼러·문자 앱에서 실제 발신·발송 직전 상태.
- 재현에 코드 변경이나 앱 데이터 삭제가 필요할 때.
- 예상 밖의 단말 상태가 보일 때(→ 사용자에게 질의).
