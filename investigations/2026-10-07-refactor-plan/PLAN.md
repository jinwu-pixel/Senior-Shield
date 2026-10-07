# 리팩터링 계획 v0.2 — 검토 반영본 (2026-10-07)

> 등급: 이 문서 = **T0**(계획·보고). 실행 트랙 R1 = **T2**(DIRECTIVE.md 초안, 기동 = 사용자 승인).
> 작성: Claude(계획·검토). 검토: Codex T001(읽기 전용) — v0.1 판정 CHANGES_REQUIRED, v0.2에 반영(§6).
> 구현 착수 전 사용자 승인 필수.

## 0. 기준선

- **git**: main `9657cf4`, 로컬 origin/main과 차이 0/0(원격 최신 여부는 미조회). 추적 파일 변경은 0이고, untracked는 이 트랙 폴더뿐이다. 기존 worktree 4개(`.worktrees/*`, codex/* 브랜치)가 있다.
- **CI**: main push run 34042249010 GREEN(작업 기록 기준, 로컬 증거 파일은 없음). 판정 항목은 다음과 같다.
  - unit floor app 544 / risk 7 / contracts 4, skipped 0
  - domain lint
  - app·data lint exact. 열거된 부재만 허용한다: app 3키 5건, data 1키 1건. contracts 1건은 domain lint 쪽에서 별도로 다룬다.
  - schema drift, verifier self-check
- **테스트**: 보존된 증거 기준 app 544/39 · risk 7/1 · contracts 4/1(합계 555/41, 2026-09-06). **오늘 재측정은 하지 않았다.**
- **모듈**: `:app` `:data` `:domain:risk` `:domain:contracts`.

## 1. 진단 (실측, Codex 교차확인 일치)

| 파일 | 줄 | 변경 횟수* | 문제 |
|---|---:|---:|---|
| `monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt` | 1,747 | 8 | 아래 목록 참조 |
| `monitoring/call/RealCallRiskMonitor.kt` | 1,355 | 8 | API31(531–858)·legacy(859–1024) callbackFlow가 통화 회차 상태를 각자 보유하고 비슷한 전이를 중복 구현한다. CallLog 조회(1218–1316, 순수 I/O)가 같은 파일에 섞여 있다 |
| `core/overlay/RiskOverlayManager.kt` + `BankingCooldownManager.kt` | 764 + 478 | 6 / 5 | surface 수명주기가 비슷한 코드로 두 벌 있다. 다만 세대 무효화·exact removal 순서가 미묘하게 다르다. buildView는 280줄·157줄이다 |
| 화면 3개(Warning / Home / Settings) | 561 / 450 / 411 | 4 / 3 / 3 | Warning·Settings는 이미 private Composable로 분할되어 있다 |

\* 2026-04-01 이후 해당 파일을 건드린 커밋 수.

**Coordinator의 문제**
- `start()`의 collect 람다 하나가 549줄이다(655–1203).
- `return@collect`가 26개다. maintenance 조기 반환 2개를 빼면 24개 모두 `previousBankingForeground = bankingForeground` 직후에 나온다. 정상 종료까지 합쳐 대입은 25회다.
- reset 재검증 게이트가 10개다.
- escalation(1037–1123)과 new-trigger(1134–1200) 경로가 비슷한 publication 절차를 두 벌 가지고 있다.
- 안전확인/publication 원장(필드 217–224 + 관련 메서드)과 tick 엔진이 한 클래스에 있다. 락은 `synchronized(` 10개, `@Synchronized` 6개다.

## 2. 리팩터링을 제한하는 제약

- **C1 소스 텍스트 계약 테스트.** 아래 테스트가 프로덕션 파일을 문자열로 읽어 내용과 경계를 확인한다.
  - `RiskOverlayManagerBindingContractTest` — Overlay·Cooldown·Coordinator·DebugViewModel
  - `S2InvariantGuardrailTest` — Overlay·Cooldown·RiskSessionTracker·S2RecRefireDebounce
  - `HomeScreenWarningNavigationContractTest` — HomeScreen
  - 예: Coordinator의 `expectedResetEpoch = epochAtTickStart`가 **정확히 2회** 있어야 한다. Overlay의 `val confirmed = safeConfirmation.confirm(`부터 `setOnFocusChangeListener`까지 구간이 있어야 한다. Cooldown 함수의 순서 경계도 고정되어 있다.
  - 이 문자열·경계 assertion에 영향을 주는 변경은 계약 재검토(S4) 대상이다. 보존되는 구조 변경은 허용된다.
- **C2 CI lint 키.** 키 = `id|severity|정규화 message|파일 경로|errorLine1`이며 줄 번호는 포함하지 않는다.
  - Kotlin 소스 경고는 `BankingCooldownManager.kt`의 SetTextI18n 2건(:249, :410 — :410은 buildView 안)뿐이다.
  - 이 두 줄을 다른 파일로 옮기거나 문구를 바꾸면 CI가 FAIL한다.
- **C3 테스트가 사용하는 내부 seam과 reflection.**
  - Coordinator: 훅 7개 + `runInactiveSessionCleanupForTest`(합산 4파일 27회), `clock`.
  - Coordinator **reflection**: `SafeConfirmationCommandTest.kt:622·641·833`, `SafeConfirmationOverlayCommandTest.kt:609·621` 등. 대상은 `eventPublicationMutex`, `activeEventPublicationIntents`, `consumedSafeConfirmations`, `currentSafeConfirmationEvent`, `issueDebugOverlayBinding`이다. 이름·타입·소유 객체를 바꾸면 깨진다. R2의 직접 제약이다.
  - RealCallRiskMonitor: 생성자 named args(3파일), `lastSuspiciousCallEndedElapsedMs`(25회), `recentUnknownCalls`(14회), `sdkIntProvider`, `callbackExecutorFactory`, ownership 함수 3개.
- **C4 로그 문구 = 실기 증거 계약.** 선례는 `investigations/2026-07-22-safe-confirmation-field/RESULTS.md`(W-SESSION canonical 로그 대조)다. `Log.*` 문구는 바이트 동일로 유지한다. Codex가 app unit·androidTest를 검색한 범위에서는 Log 문자열을 단언하는 테스트가 발견되지 않았다.
- **C5 동작 계약.** SafeConfirmationPolicy 순서 계약, 제품 결정 D5를 전제로 한다.
- **C6 S2 리뷰 의무.** `.github/S2_REVIEW_CHECKLIST.md` §6에 따라, `shouldSuppressS2RecRefire`·`s2RecRefireStateAfterFiring` 호출 위치를 옮기는 PR은 **8개 체크 항목 리뷰 대상**이다.
  - 불변 7: α/S2 상태·함수·helper 공용화 금지.
  - 같은 문서 :149의 줄 번호는 이미 stale이다. 갱신은 T0 후속으로 처리한다.

## 3. 후보 평가와 권고 (Codex 동의 반영)

| ID | 후보 | 판단 |
|---|---|---|
| **R1** | Coordinator tick 람다를 단계 함수로 분해 (축소안) | **채택(1순위).** 동작 불변이 조건이다. 변경 축은 함수 경계 + 조기 종료 전파로 좁히고, 대입 통합(K3)은 characterization 테스트 확보 후에 한다. publication 게이트 공통화(구 K6)는 **R1에서 제외**한다(정적 계수 보존과 충돌하고, 핵심 게이트는 이미 C:1386 helper로 공통화되어 있음). |
| R2 | 안전확인/publication 원장을 별도 클래스로 분리 | **보류.** Coordinator monitor 락과 publication mutex가 함께 작동하고, reflection 5개가 소유 객체를 고정한다. 재알림 구현에 착수할 때 재평가한다. |
| R3 | CallMonitor CallLog 조회(1218–1316)를 분리 | **선택(소형).** 범위에 권한 조회·재시도 delay·후보 선택·예외·로그가 포함된다. "테스트 미접촉"은 안전 근거가 아니므로 별도 등가성 검토가 필요하다. |
| R4 | API31·legacy 상태 기계 통합 | **비권고(동결 구역).** 스레드 모델이 다르고, 라운드 7–17 수정이 집중된 구간이다. |
| R5 | Overlay·Cooldown 공통화 | **비권고.** C1·C2에 더해 세대 무효화·exact removal 순서 차이가 있다(`RiskOverlayManager.kt:275–363`, `BankingCooldownManager.kt:90–128·260–313`). |
| R6 | UI 분할 | **비권고.** 이미 private Composable로 분할되어 있어 추가로 얻는 이익이 불명확하다. |

## 4. R1 요약 (상세 = DIRECTIVE.md v0.2)

1. **R1.0 — characterization 테스트 1파일 추가.** 현행 코드에서 GREEN이어야 한다. 고정하는 항목:
   - banking 이전값 대입 의미: 조기 종료 시 대입, maintenance 종료 시 미대입, escaping cancellation 시 미대입, 처리된 push 실패 시 대입
   - escalation 중단 시 같은 tick의 new-trigger 효과 0
2. **R1.1 — 대입 25곳을 제자리에 둔 채 단계 함수로 분해.**
   - 각 단계는 계속/중단을 반환하고, 호출부가 즉시 전파한다.
   - escalation·new-trigger 본문은 각각 한 함수로 통째로 옮긴다.
3. **R1.2 — 대입을 1곳으로 통합(K3).** R1.0이 GREEN일 때만 진행하며, 보류할 수 있다.
4. **등가성 증거**
   - 기존 테스트 파일 무수정 GREEN
   - R1.0 테스트
   - 출구 26개 대응표
   - 정적 불변 계수 전/후 일치
   - S2 8항목 리뷰

## 5. 진행 순서와 게이트

1. Codex 검토 T001 → Claude REVIEW → (BLOCKING이 있었으므로) v0.2 재검토 1회 → 사용자에게 의견 보고. **← 현재 단계**
2. 사용자 승인 → DIRECTIVE v1.0 확정.
3. Codex 구현(새 worktree, `--write`).
4. Claude 적대 검토 + 독립 빌드·lint.
5. 커밋·PR = S1(사용자 게이트).

## 6. 검토 이력

- **T001 (Codex, 2026-10-07 11:07) — CHANGES_REQUIRED**: BLOCKING 1, SHOULD 8, NOTE 1. Claude가 전부 실측 대조했다. 처리 내역은 `collab/`의 REVIEW 편지에 있다.
  - 수용 9건
  - 부분 수용 1건: #10. 표현은 수정했고, W-SESSION 선례는 Claude가 경로를 확인해 해소했다.
- v0.2 변경 요약:
  - 사실 정정 3건: lint 건수, CallLog 범위, git 상태
  - C3 reflection, C6 S2 리뷰 의무 추가
  - R1 축소: K6 제외, 대입 제자리 보존 후 조건부 통합, R1.0 characterization 신설
  - 게이트 구체화: cwd, JBR 경로, self-check, untracked 범위 검사, 모듈별 수 비교
- **T001 재검토 (Codex, 11:16) — PASS_WITH_NOTES**: 지적 1–9는 RESOLVED, 10은 PARTIAL이다. 새 BLOCKING은 없다. 함께 나온 SHOULD 2건과 NOTE 2건은 v0.2에 바로 반영했다.
  - N1·N2: R1.0 테스트의 판별력 조건
  - N3: TESTED / STRUCTURALLY_PROVED 구분
  - N4: C4 문안
  - **검토는 이것으로 종결하며, 사용자 승인을 기다린다.**
