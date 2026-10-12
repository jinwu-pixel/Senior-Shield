# 시니어쉴드 현황 스냅샷 — 2026-10-12

**성격**: 2026-06-22 스냅샷을 잇는 현행 스냅샷이다. 이전 스냅샷(`project_status_2026-06-22.md`, `project_status_2026-03-31.md`)은 역사 기록으로 보존한다.
**baseline**: main = `1a2de54` (#30 머지 후, origin 동기화, 추적 파일 변경 없음)
**테스트**: CI 게이트 기준 **623개**(app 612 / domain:risk 7 / domain:contracts 4), failures·errors·skipped 0.
- app에는 Robolectric 테스트가 포함되어 있다. SDK 26·30은 legacy 통화 리스너, SDK 32·33은 권한 race 검증용이고, SDK 33은 보호자 저장소의 실제 JSON 처리 검증에도 쓴다.
- 계측 테스트(androidTest) 17개는 2026-09-05 실기기(AT-M150, Android 14)에서 PASS했다. CI는 이 테스트를 빌드만 하고 실행하지 않는다.

> 근거는 git log, PR, `investigations/` 트랙 기록(DIRECTIVE·IMPL_LOG·RESULTS)에 있다.

---

## 1. 2026-06-22 이후 변경분

| 시기 | 변경 | 내용 |
|------|------|------|
| 06-23 | f2b11fe | dead 권한 `ANSWER_PHONE_CALLS` 제거 (6월 감사 RISK 해소) |
| 06-23 | baf980e · 1624308 · 1dba378 | coordinator 통합 가드레일 테스트, dead DAO 쿼리 제거, Room 스키마 baseline과 migration fallback |
| 06-30 | df921e3 | 시간 의존 로직을 테스트 가능하게 분리 (단위 테스트 224 → 244) |
| 07-02 | 50dcf30 · 99ca7ed · cfaca80 | 사기 kill chain 커버리지 매트릭스, GUARDED 사기 세션 행동 점검, 시뮬레이션 시나리오 4종 추가 |
| 07-07 | 714134b | 활성 경고에서 통화 CTA 우선 노출, GUARDED 이벤트는 이력 전용 |
| 07-18 | d972ccb · e0349b3 | 감지 provenance(A안: 생산 시점 epoch 캡처)·idle 만료 TTL 강화, 통화 시간창 monotonic 시계, testMode 전환 시 일회성 경계 |
| 07-22 | 11b954b | SafeConfirmationPolicy 트랙 — 안전 확인 정책과 표시 세대 통일. 실기 PASS, 단 production 오버레이 경로(`OVERLAY_IDLE`·`OVERLAY_LIVE_CALL`)는 실기에서 미행사 |
| 08-29~08-30 | d567683 · e5af6f7 | Codex 협업 지침(AGENTS.md)과 T1 정리 지시문, 텔레뱅킹 anchor 재현 테스트 교체(동작 변경 없음) |
| 09-05~09-06 | #9 · #10 · #13 | 모듈 분리: `:domain:risk`, `:domain:contracts`, `:data`. 권한 철회 방어 통합. **Manifest에 `uses-feature android.hardware.telephony required="true"`를 추가해 전화 기기 전용 배포로 결정** |
| 09-07 | #14 | GitHub Actions 검증 게이트 (아래 §3) |
| 10-07 | #15 | coordinator tick 루프를 순서 있는 단계로 분해 (동작 무변경 리팩터링) |
| 10-10 | #17 · #18 | 보호자 문자 버튼이 위험 팝업과 Home 연락 대화상자에서도 수동 문자 메뉴 토글을 따름 |
| 10-10 | #20 | 런처 아이콘 교체 (adaptive mipmap 레이어 + monochrome) |
| 10-10~10-12 | #21 · #22 · #24 · #26 · #28 | **크래시 경로 7곳 차단** (아래 §2-6) |
| 10-12 | #30 | **저장소 실패 크래시 경로(화면·저장소 계층) 차단** (아래 §2-6) |

## 2. 구현 현황

1. **모듈 구조**: `:app`(composition root) → `:data`(Room·DataStore 구현, Hilt data 모듈) → `:domain:contracts` → `:domain:risk`(pure Kotlin)로 의존이 한 방향으로만 흐른다.
2. **런타임 DI 전 구간 Real**: 통화·앱 사용·앱 설치·기기 환경 monitor, evaluator, coordinator가 모두 실구현이다. Fake는 테스트 소스에만 있다.
3. **영속화**: 위험 이벤트는 Room(`senior_shield.db` **v1**, 스키마 `data/schemas/.../1.json`, destructive fallback)에, 설정·온보딩·보호자는 DataStore에 저장한다.
4. **팝업 발화**: AlertState 기반(세션 + TRIGGER → INTERRUPT/CRITICAL)이다. 6월과 동일하다. PASSIVE 신호만 있으면 점수 50(HIGH)이어도 팝업 없이 GUARDED 알림만 나간다.
5. **보호자 문자 버튼**: Guardian·Warning 화면, 위험 팝업, Home 연락 대화상자의 4곳 모두 수동 문자 메뉴 토글(기본 OFF)을 따른다.
   - 설정이나 보호자 조회가 실패하면 버튼만 숨기고 팝업은 유지한다.
   - Home 대화상자는 토글이 OFF여도 유지되며 전화 버튼만 표시한다.
6. **크래시 경로 차단 (10-10~10-12)**:
   - 공통 원칙(#21~#26, #30): 저장소 읽기·쓰기나 시스템 등록이 실패하면 **해당 source만 중립화하고 나머지 보호는 계속**한다.
   - #28은 실패 처리가 아니라 생성 스레드 결함이다. 리스너를 Looper가 없는 스레드에서 생성하던 것을 메인 Looper로 옮긴 것이 수정의 본체이고, 생성 실패 가드는 부수적으로 추가했다.
   - 차단 전에는 이 경로들이 모두 **앱 크래시**로 이어졌다. coordinator, 통화 sharing scope, `viewModelScope`, Room store scope에 CoroutineExceptionHandler가 없었기 때문이며, START_STICKY로 재시작되면 반복될 수 있었다. 처음에는 #21·#22·#24 경로를 "조용히 멈춤"으로 판단했으나 #26에서 정정했다.

   | PR | 경로 | 처리 |
   |----|------|------|
   | #21 | 팝업 보호자 조회 실패 | 문자 버튼만 숨김 |
   | #22 | 통화 감시 testMode 설정 읽기(OFFHOOK·IDLE) 실패 | 운영 임계값(180초)으로 계속 감시 |
   | #24 | GUARDED 이력 기록 실패 | **본인 알림은 유지**하고 해당 이력만 누락 (제품 결정 A안) |
   | #26 | API 31+ 번호 수신기, 앱 설치 수신기 등록 실패 | 해당 source만 중립 |
   | #28 | **API 26~30 legacy `PhoneStateListener`를 Looper 없는 스레드에서 생성** | 메인 Looper에서 생성. Android 8~11에서 전화 권한을 허용하는 즉시 나던 크래시를 Robolectric으로 재현한 뒤 수정 |
   | #30 | 설정·보호자 DataStore, 이력 Room의 읽기·쓰기 실패. 특히 **설정 파일이 손상되면 앱을 시작할 때마다 크래시** | 읽기는 기본값(설정 OFF·온보딩 재표시, 보호자·이력 빈 목록, 주간 0). 설정 쓰기는 로그만. 보호자 추가·삭제 실패는 "저장하지 못했습니다" 안내. 손상 파일은 지우지 않는다. 보호자 추가·삭제를 저장 단계 안에서 처리해, 일시 읽기 실패로 기존 보호자가 지워지는 경로도 막았다 |

## 3. 검증 체계

- **CI** (`.github/workflows/verify.yml`, PR과 main push마다 실행):
  - 직렬 Gradle 게이트: `clean`, `:domain:*:check`, `:app:testDebugUnitTest`, `kaptDebugKotlin`, `assembleDebug`, `checkDebugDuplicateClasses`, `assembleDebugAndroidTest`(빌드만), `:data`·`:app` `lintDebug`
  - 단위 테스트 XML floor: 하한 고정, skipped 0
  - domain lint: risk 0, contracts는 0 또는 승인된 경고 1건(coroutines-core 1.8.1 pin)
  - app/data lint: 고정된 진단 목록(app 69 / data 14)과 **정확히 일치**해야 한다. 네트워크 조회에 의존하는 버전 경고 6건(app 5 / data 1)만 빠져도 허용한다.
  - Room 스키마 drift 0
  - 검증기 자체 결함 주입(self-check)
  - 로컬 게이트도 같은 명령을 쓴다.
- **트랙 운영**: 등급(T0/T1/T2)과 정지 조건(S1~S5)을 둔다.
  - 코드는 Codex가 작성하고, 검증은 Claude가 한다.
  - 검증 항목: RED-first, 결함 주입, 전체 게이트, 독립 적대 검토
  - 커밋·push는 사용자 승인 후에만 한다.
  - 기록은 `investigations/<날짜-트랙>/`에 남긴다.
- **실기**:
  - 07-22 SafeConfirmationPolicy 실기 PASS (AT-M140, Android 14). production 오버레이 경로는 미행사
  - 10-10 릴리스 전 실기(THOR3 AT-M160, Android 16, tc-runner와 단말 시간 분할)
    - 시험 빌드: `0414984` + #22 미커밋 수정. 따라서 #24·#26·#28·#30은 이 실기 범위 밖이다.
    - PASS: Home 대화상자 토글, 팝업 문자 버튼 토글, 통화 UI("전화 앱으로 이동" → 통화 화면, 자동 종료 없음), 권한 철회 후 재진입, 통화 정상 경로
    - 부분: 런처 아이콘
    - 미행사: 지연 콜백 중 새 경고(500ms 창)
    - 근거: `investigations/2026-10-10-release-field-check/RESULTS.md`

## 4. 정책 준수 (감사 2026-10-12)

- Manifest 권한은 `READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`, `PROCESS_OUTGOING_CALLS`, `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`, `FOREGROUND_SERVICE`(+`SPECIAL_USE`)뿐이다.
  - **`SEND_SMS`·`READ_SMS`·`INTERNET`·위치·`CALL_PHONE`·`ANSWER_PHONE_CALLS`는 없다.** 따라서 외부 전송, 자동 통지, 위치 추적, 자동 통화 제어는 구조적으로 불가능하다.
- 전화는 `ACTION_DIAL`만, 보호자 문자는 사용자가 누를 때 `ACTION_SENDTO`로 수동 발송한다(토글 기본 OFF).
- 승인된 예외(본인 알림, Foreground Service, 시스템 오버레이) 범위 안에 있다.
- `uses-feature android.hardware.telephony required="true"`(09-05)로 전화 기능이 없는 기기에는 배포되지 않는다.
- 6월 감사 RISK(`ANSWER_PHONE_CALLS` dead 권한)는 06-23에 해소됐다.

## 5. 6월 follow-up 상태

| 항목 | 상태 |
|------|------|
| UPGRADE_TRIGGERS 3중 참조 단일화 | **동결 유지**: 여전히 3곳(RiskSessionTracker / Coordinator / S2RecRefireDebounce) |
| `RiskEventDao.getEventsSince()` dead query | 해소 (06-23) |
| CLAUDE.md 아키텍처 트리·Navigation 현행화 | 해소 (모듈 구조, Navigation 12 destination 반영). 잔여: 트리에 `core/overlay`·`core/notification`·`core/util`이 없다 |
| A′-R0 런타임 게이트 문서 untracked | 해소 (07-01, CTA-semantics 조사 문서 추적) |
| Room 보존 Migration 미작성 | **미해결**: v1 단일·destructive fallback. 스키마를 바꾸기 전에 명시 Migration이 필요하다 |
| G1~G4 확장 단위 테스트 | 해소 (06-30 df921e3, `S2RecRefireDebounceTest`에 11건 추가) |
| 실기 F-1~F-4(REC-REFIRE 재진입·경과·UPGRADE escape) 인프라 | **미해결** |

## 6. 남은 일

- **실기**
  - Android 8~11 실제 기기 확인 (#28 legacy 경로는 Robolectric으로만 검증했다. 현재 단말 없음)
  - 원격제어 앱 실행 → 감지 end-to-end를 **THOR3(Android 16)에서** 확인. A′ 실기(Android 14)에서는 검증했다. THOR3에는 원격제어 앱이 없고, 앱 사용 감지 주 경로가 동작하는 것만 로그로 확인했다.
  - 런처 원형 마스크·테마 아이콘 확인
  - SafeConfirmation production 오버레이 경로(`OVERLAY_IDLE`·`OVERLAY_LIVE_CALL`) 실기
  - #24·#26·#28·#30 이후 main 빌드의 실기 회귀 확인
- **제품 기능 (2026-07-20 확정)**
  - 재알림(본인 기기 알림 전용, 절대 조건 4): 10-12 **보류**. 독립 검토와 실기 로그 분석 결과, 앱 기반 위험 신호는 화면 전환 후 약 30초만 유지되어 재알림이 거의 발화하지 않는다. 실질 대상은 의심 통화 뒤 은행 ARS 통화 중뿐이다(`investigations/2026-10-12-risk-reminder/PLAN.md` §4).
  - episode 분리
  - 이력 명시 삭제와 보존 상한
- **결정 대기**
  - Home "바로 다이얼" UX
  - 미머지 브랜치 `feature/cooldown-dismiss-timing` 처리
- **안정성 백로그**
  - 손상된 저장소 파일 자동 복구(#30에서 데이터 보존을 위해 넣지 않음, 필요 시 별도 결정)
  - 이력 DB 읽기가 한 번 실패하면 프로세스 수명 동안 빈 이력으로 남음(#30 잔여)
  - 앱 사용 기록 경고 문구 정정
  - CI Robolectric 런타임 캐시
  - Room Migration
  - 프로세스 재시작 시 메모리 세션 소실("재시작 지속성"; 감지 이력은 보존됨)
