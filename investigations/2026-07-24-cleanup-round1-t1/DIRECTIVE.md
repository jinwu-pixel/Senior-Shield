# 정리 1차 지시문 — 복제 테스트 대체 + KDoc/네이밍 (신규 협업 규약 첫 적용)

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol` / AGENTS.md 참조)**
> - **등급**: **T1** (격리 코드·테스트 — 동시성·공유가변상태·interface·정책 표면 없음).
>   실행 중 interface/동시성/정책 표면이 필요해지면 = **S3 자동 등급 상승·중단·질의**.
> - **검토**: **Codex raw 핸드오프**(자체검토 생략) → **Claude 독립검토가 유일 게이트**.
> - **정지·질의 = S1~S5만** (그 밖 자동 전진, 항목별 한 줄 로그 `✓ <항목> [T1] — <수치>`):
>   S1 되돌릴 수 없는 게이트(commit·push·Manifest/권한/DI 실제 변경) /
>   S2 (해당 없음 — 이 트랙은 Claude 검토가 게이트) / S3 등급 상승 /
>   S4 제품·정책 결정 / S5 완료 게이트 실패(RED·lint 신규·범위 위반).
>
> 작성: Claude(계획·검토). 수행: Codex. 이 지시문 기동 = 아래 5줄 계획 승인.

---

## 0. 현재 상태 (2026-07-24)
- git: `main` = `11b954b` = origin/main (**ahead 0**). tracked clean.
- 기준선: fresh 직렬 **454/454 GREEN·0 skipped(35 suites)**, lint **5E/62W**(5E로 task 실패=정상).
- 이 트랙은 E 검토(SafeConfirmationPolicy)에서 나온 info 지적 중 **격리 가능한 것만** 정리.
  cold replay·lint(Manifest 연계 가능)는 **범위 밖 — 별도 등급 판정**(T1 자동 편입 금지).

## 1. 불변 운영 제약
1. commit·push·stage = S1(사용자 명시 승인 전 금지). broad add 금지.
2. Gradle 직렬 `--no-parallel --max-workers=1`, `JAVA_HOME`=JBR.
3. **금지 범위(무접촉)**: Manifest·permission·service·DI 모듈·`CallRiskMonitor` **시그니처**·
   NavGraph·repository. 이들 변경이 필요해지면 **S3 중단·질의**.
4. 프로덕션 **동작 변경 0** — 이 트랙은 테스트 대체 + 주석 + 순수 rename만. 앱 런타임 동작·
   신호·순서·정책 무변. 동작이 바뀌는 수정이 필요하면 S3 중단.
5. 기존 테스트 삭제·약화 금지(복제 테스트 대체는 예외 — 아래 A에서 커버리지 **동등 이상** 증명).
6. 산출 문서는 이 트랙 폴더 `IMPL_LOG.md`에만.

## 2. 승인된 5줄 계획
1. **수정 파일**: 아래 §5 (프로덕션 2 = 주석/rename만 + 테스트 3 + 삭제 1).
2. **목적**: E 검토 info 지적 정리 — ①wall 전제 복제 테스트를 실물 monitor 기반으로 대체
   ②stale KDoc 정정 ③elapsed 의미와 어긋난 필드명 정정 ④상실된 테스트 단언 복원.
3. **리스크**: 외부 정책·권한 리스크 0. 프로덕션 동작 무변(주석·rename·테스트만). interface
   시그니처 무변(rename 대상은 `@VisibleForTesting internal` 필드 + KDoc 주석뿐).
4. **테스트**: 각 항목 후 표적 GREEN → 전 항목 후 fresh 직렬 전체 GREEN·0 skipped, lint 신규 0.
   테스트 개수는 복제 7 삭제 + 신규 N 추가로 변동 — IMPL_LOG에 순증감 회계 명시.
5. **중단 조건(S3/S5)**: 실물 monitor 테스트가 seam으로 재현 불가해 프로덕션 표면 변경이
   필요 / rename이 interface 시그니처에 닿음 / 기존 GREEN 회귀 해소 불가.

## 3. 작업 항목 (A→C→B·D 순서 권장 — C가 A의 새 테스트 필드명에 선행)

### A. 복제 테스트 대체 (test-only)
- 대상: `TelebankingAnchorIdleLogicTest.kt` — 현재 `IdleAnchorReproducer`가 프로덕션 IDLE
  분기를 **손으로 복제**(hardcoded Long, wall `endedAtMillis` 대입)해 검증. E 검토 지적:
  프로덕션은 `endedAtElapsedRealtime`(monotonic)으로 바뀌어 이 복제가 **시계축을 더는
  핀하지 않음**(safe-confirm skip/clear 분기 의미만 유효).
- 조치: 복제 클래스를 **삭제**하고, `RealCallRiskMonitor` 실물을 기존 seam으로 구동하는
  테스트로 대체(`RealCallRiskMonitorProvenanceTest`·`RealCallRiskMonitorTest`가 이미 쓰는
  mockk Context/TelephonyManager + `clock`/`monotonicClock`/`callbackExecutorFactory`/
  `sdkIntProvider` seam 패턴 준용). 복제가 커버하던 6분기(T-A1 안전확인 동일 callId anchor
  미설정 / T-A2 다른 callId anchor 설정 / T-A3 IDLE 후 safeConfirmedCallId 자동 클리어 /
  T-A3보강 다른 callId 보존 / T-A4 clearTelebankingAnchor / T-A4보강 null no-op /
  RINGING→IDLE callIdAtIdle null)를 **실물 경로로 동등 이상** 커버 — 특히 anchor 저장이
  **monotonic `endedAtElapsedRealtime`**임을 핀(복제가 잃은 시계축 복원). 대체 클래스 위치는
  `monitoring/call/` 테스트 경로 자유. 커버리지 동등 이상을 IMPL_LOG에 분기별 매핑.
- 참고: 클래스 KDoc의 "직접 인스턴스화 불가" 전제는 낡음(ProvenanceTest가 이미 실물 구동).

### B. stale KDoc 정정 (주석-only, 프로덕션 파일)
- 대상: `RiskSessionTracker.kt:452` — `resetAfterUserConfirmedSafe`의 KDoc
  `호출부: B-3(RiskOverlayManager), B-5(HomeViewModel), B-6(WarningViewModel).`
- 실제: E 통일 후 프로덕션 호출부는 `DefaultRiskDetectionCoordinator.confirmSafe`(:302)
  **단일**(세 진입점은 Coordinator command 경유). KDoc을 단일 Coordinator 호출부로 정정.
  코드·동작 무변.

### C. 필드명 정정 (순수 rename)
- 대상: `RealCallRiskMonitor.kt`의 `@Volatile @VisibleForTesting internal var
  lastSuspiciousCallEndedAt`(:71 부근) — 값이 wall이 아니라 **elapsed realtime**인데 이름이
  wall을 시사(E 검토 info + KDoc의 "기존 필드명은 테스트 범위 호환을 위해 유지" 자인).
- 조치: elapsed 의미가 드러나는 이름으로 rename(예: `lastSuspiciousCallEndedElapsedMs`).
  갱신 범위 = 선언+사용부(RealCallRiskMonitor.kt) + KDoc 주석 참조(CallRiskMonitor.kt:32·41·49
  — **주석뿐, 시그니처 아님**) + 테스트 참조(`RealCallRiskMonitorTest`·
  `RealCallRiskMonitorProvenanceTest`). "테스트 호환 위해 유지" 정당화 문구는 삭제(이제 무의미).
  A의 대체 테스트는 **rename 후 최종 이름** 사용(그래서 C를 A보다 먼저 권장).

### D. 상실 테스트 단언 복원 (test-only)
- 대상: `WarningViewModelTest.kt`의 자가확인 무부수효과 테스트 — E에서
  `assertEquals(0, coordinator.refreshAnchorHotNowCallCount)` 단언이 빠짐(coordinator는
  여전히 주입되고 `refreshAnchorHotNow` 직접 호출 가능하므로 회귀 그물 상실).
- 조치: 해당 테스트에 `refreshAnchorHotNowCallCount == 0` 단언을 복원(현재 존재하는
  `captureCallCount==0`·`confirmCallCount==0`와 병치). Fake가 이미 `refreshAnchorHotNowCallCount`
  추적 중이라 배선 추가 불필요.

## 4. 범위 밖 (별도 등급 판정 — 이 트랙에서 다루지 않음)
- **cold 재구독 replay 부작용** 조사 — 별도 트랙(등급 재판정).
- **lint 5E** — 첫 오류가 `BankingCooldownManager` MissingPermission 등, Manifest/권한 연계
  가능성 → T1 자동 편입 금지, 착수 시 별도 등급 판정.
- E 검토의 나머지 info(runIfCurrent lock-order KDoc·suppression @Volatile 통일) — 후속 후보.

## 5. 파일 범위
- 프로덕션: `monitoring/call/RealCallRiskMonitor.kt`(rename), `monitoring/session/RiskSessionTracker.kt`(주석),
  `monitoring/call/CallRiskMonitor.kt`(주석 참조 rename) — **동작 변경 0**.
- 테스트: `TelebankingAnchorIdleLogicTest.kt`(삭제) + 대체 신규(monitoring/call 경로),
  `RealCallRiskMonitorTest.kt`·`RealCallRiskMonitorProvenanceTest.kt`(rename 반영),
  `WarningViewModelTest.kt`(단언 복원).
- 그 밖 = S3 중단·질의.

## 6. 완료 게이트
fresh 직렬 `clean testDebugUnitTest assembleDebug` = **전체 GREEN·0 skipped**(개수는 복제 7
삭제+신규 N, IMPL_LOG에 순증감 회계), lint **5E/62W 신규 0**, `git diff --check` 통과, 금지
범위 무접촉, staged/commit/push 0. IMPL_LOG에 항목별 전/후 + A 분기 매핑. 이후 Claude 독립검토.
