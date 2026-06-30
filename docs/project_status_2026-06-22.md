# 시니어쉴드 현황 스냅샷 — 2026-06-22

**성격**: 2026-03-31 v3.0 보고서를 잇는 현행 스냅샷. 기존 `project_status_2026-03-31.md`는 역사 기록으로 보존한다.
**baseline**: main = `30284c6` (origin 동기화, 추적 파일 변경 없음 기준)
**테스트**: 로컬 `testDebugUnitTest` **220개 PASS** / failures 0 / errors 0 / skipped 0 (2026-06-22 16:07 KST 실행)

> 이 스냅샷은 코드 정독 + DI 배선 교차검증으로 작성됨. 세부 근거는 git log / 코드 / MEMORY 참조.

---

## 1. 2026-03-31(v3.0) 이후 변경분

| 변경 | 내용 |
|------|------|
| #2 (450d756) | state-sync α — non-call shared-root 세션 respawn 억제 |
| #4 (9a50b98) | 텔레뱅킹 severity, anchor-hot Home, Warning back-nav |
| v1.1 (1d55b51) | a-prime-sync 앱 표면 ship + 기기 테스트 피드백 5건 수정(b1fa052) |
| S2 (203f5e6) | REC-REFIRE debounce 게이트 (`S2RecRefireDebounce`, 30s window + UPGRADE escape) |
| S2 (c8ab0ec) | S2 invariant 가드레일 테스트 |
| #7 (08c286a) | S2 리뷰 체크리스트 + PR 템플릿 |
| 아이콘 (30284c6) | Senior S adaptive 런처 아이콘 교체 |

## 2. 구현 현황 교정 (CLAUDE.md 동기화 반영)

- **런타임 DI 전 구간 Real** — 5개 모니터(Call/AppUsage/AppInstall/DeviceEnvironment) + RiskEvaluator + Coordinator 모두 `Real*`/`Impl`/`Default`로 `@Binds`. Fake 클래스는 `app/src/test`에만 존재(프로덕션 그래프 무관). *이전 문서의 Fake 기재는 stale였음.*
- **위험 이벤트 영속화 = Room** (`senior_shield.db` v1, 최신 50건). 설정/온보딩 = DataStore(Preferences), 보호자 = DataStore + JSON.
- **팝업 발화 = AlertState 기반** (세션 + TRIGGER → INTERRUPT/CRITICAL). 위험 '점수' 게이트가 아님 — PASSIVE-only 세션은 점수 50(= RiskLevel.HIGH)이어도 GUARDED라 팝업이 뜨지 않는다.
- **쿨다운 = 레벨별 차등** — CRITICAL 60초 / HIGH 30초 / 그 외 10초 (텔레뱅킹은 항상 CRITICAL → 60초). `BankingCooldownManager.kt:103-107`.
- **팝업 주 CTA 실라벨** — 통화 중 "전화 앱으로 이동"(`showInCallScreen`, 사용자가 직접 종료) / 비통화 "일단 닫기". 자동 통화 종료(`TelecomManager.endCall`) 없음. 보조 CTA: "통화 경고 닫기" / "위험 경고 해제".

## 3. 정책 준수 (감사 2026-06-22)

- `SEND_SMS`/`READ_SMS`/`INTERNET`/위치/`CALL_PHONE` 권한 전무 → 외부 전송·자동 통지·위치 추적이 구조적으로 불가능.
- 전화 = `ACTION_DIAL`만, 보호자 문자 = 사용자 클릭 → `ACTION_SENDTO` 수동.
- 승인된 예외(자기 알림 / Foreground Service / 시스템 오버레이) 범위 내.
- 🚩 **RISK 1건**: `ANSWER_PHONE_CALLS` = dead 권한(코드 사용처 0, `endCall`/`acceptRingingCall` 미사용)인데 Manifest 선언 + PermissionsScreen 사용자 요청 노출. 권한 최소화 관점 제거 검토 필요 → **고위험 작업(계획·승인 선행)**.

## 4. 알려진 follow-up (미해결)

- **UPGRADE_TRIGGERS 3중 참조(RiskSessionTracker/Coordinator/S2RecRefireDebounce) 단일화 — A′-R0 게이트가 명시 동결. 지금 착수 금지.**
- G1~G4 확장 단위테스트 11건 (PR1 merge gate는 핵심 6건만 강제).
- 실기 시나리오 F-1~F-4(REC-REFIRE 재진입/경과/UPGRADE escape) 검증 인프라 미구축.
- A′-R0 post-merge 런타임 게이트 문서(`investigations/2026-04-24-cta-semantics/05_...md`) draft/untracked.
- CLAUDE.md 아키텍처 트리 전수 현행화(session/orchestrator/appinstall/deviceenv/registry/event 추가) + Navigation 라인(Simulation/GuardianAdd/온보딩→권한 경로 누락) — 이번 좁은 정정 범위 밖.
- ~~`RiskEventDao.getEventsSince()` dead query~~ → **2026-06-23 제거 완료(1624308)**. 현 DAO엔 live `countEventsSince()`(HomeViewModel 주간 이벤트 집계, HomeViewModel:208 경유)만 존재 — stale 검증 완료(dead query 없음 확인).
- Room: destructive-fallback + 스키마 export baseline(`app/schemas/.../1.json`)은 1dba378로 추가됨, 단 보존 Migration 클래스는 미작성(v1 단일) — 릴리스 관점 점검 대상.
