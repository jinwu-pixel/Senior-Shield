# Step 1 — not-inCall 쿨다운 CTA Semantics 정의

- 일자: 2026-04-24
- 브랜치: `investigation/cta-semantics-step1` (base: `main` = `9a50b98`)
- 참조 비교점: `feature/cooldown-dismiss-timing` (`f6326ec`, PR #3 close, **미merge 보존**)
- 범위: 사용자 고정 4개 질문에 현상 + 구조 분석으로 답한다.
- 금지: REC-REFIRE 최종 해결안 채택 / `userConfirmedSafe` 통합 API 설계 / 코드 구조 선택 / 구현 착수. 이 모든 항목은 Step 1.5 이월.
- 산출물 위치: `investigations/2026-04-24-cta-semantics/01_step1_semantics.md` (repo 내 untracked, 로컬 스크래치)

---

## 0. 분석 대상 코드 (main = `9a50b98`)

| 파일 | 라인 | 관련 역할 |
|---|---|---|
| `core/overlay/BankingCooldownManager.kt` | 289 | 주 CTA 라벨 분기 (`isCallActive ? "전화 앱으로 이동" : "일단 닫기"`) |
| 〃 | 300–311 | 주 CTA click handler (inCall → showInCallScreen + 지연 dismiss / not-inCall → dismiss() only) |
| 〃 | 74–78 | `dismissIfShowing()` — 외부 세션 종료 경로가 쿨다운 창을 닫을 때만 사용 |
| 〃 | 287 주석 | "세션 종료는 하단 '안전 확인했어요' 버튼이 담당" — 책임 분리 설계 의도 명시 |
| `monitoring/session/RiskSessionTracker.kt` | 242–247 | `reset()` — 세션 종료만, α arm 없음 (debug/admin용) |
| 〃 | 260–271 | `resetAfterUserConfirmedSafe()` — 세션 종료 + α arm(60s, accumulatedSignals 스냅샷) + clearSnooze |
| 〃 | 88–104 | α 억제 조건: `current==null && callSignals.isEmpty() && appSignals ⊆ armed && 신규 UPGRADE 없음` |
| `core/overlay/RiskOverlayManager.kt` | 401 | 보조 CTA 라벨 (비쿨다운 팝업): inCall → `"통화 경고 닫기"` / not-inCall → `"위험 경고 해제"` |
| 〃 | 416–444 | 보조 CTA click handler |
| 〃 | 565–572 | `shouldApplyCallSafeEffects` — anchor 축 provenance allowlist |
| 〃 | 590–608 | `performSafeCtaSideEffects` — 두 축 분리된 부수효과 순서 |
| `monitoring/call/CallRiskMonitor.kt` | 32, 41 | `clearTelebankingAnchor()`, `markCurrentCallConfirmedSafe(callId)` — 통화-scope 안전 확인 레이어 |

PR #3 참조 구현(`f6326ec`, 미merge) 요지:
- 쿨다운에 보조 CTA `"위험 경고 해제"` 추가 (not-inCall only, `isCallActive=true`면 숨김)
- 5s guard 후 활성화
- click 순서: `countdownJob.cancel → sessionTracker.resetAfterUserConfirmedSafe → dismiss`
- 주 CTA `"일단 닫기"`의 의미는 변경하지 않음 (여전히 dismiss-only)

---

## 1. Q1 — not-inCall 쿨다운 주 CTA `"일단 닫기"`의 정확한 의미

현 main에서 click handler가 수행하는 것 전부 (`BankingCooldownManager.kt:300-311`):
1. `countdownJob?.cancel()`
2. `dismiss()` → `windowManager.removeView` + `overlayView = null` + `dismissedAtMillis = now`

**수행하지 않는 것:**
- `sessionTracker.reset()` / `resetAfterUserConfirmedSafe()` — 세션 유지
- `eventSink.clearCurrentRiskEvent()` — 이벤트 유지
- `snoozeForCall` / `clearTelebankingAnchor` / `markCurrentCallConfirmedSafe`
- α arm

**설계 의도 (`line 287` 주석):**
> "세션 종료는 하단 '안전 확인했어요' 버튼이 담당"

→ 쿨다운 주 CTA의 책임은 **"지금 뜬 쿨다운 창을 걷어내는 제스처"** 수준으로 의도적으로 제한됨. 위험 상황 판단/해제 책임은 홈의 `"안전 확인했어요"`에 위임.

---

## 2. Q2 — dismiss-only 인가?

**YES. 현재 main은 엄격 dismiss-only.**

- 코드 사실: click handler에 `removeView` + `countdownJob.cancel` 외 부수효과 없음
- 설계 의도: 위 책임 분리 주석과 일치

외부 경로에서 `dismissIfShowing()`이 호출되는 경우 (TTL 만료 / 홈 safe-confirm / 비쿨다운 팝업 B-3/5/6 safe CTA 결과의 하류 반영)는 **CTA 자체의 의미에 포함되지 않음**. 이것은 상위 세션 해제의 부수 UI 반영이지, `"일단 닫기"` 버튼이 만드는 의미가 아님.

---

## 3. Q3 — temporary re-fire suppression까지 포함하는가?

**NO. 현 main의 `"일단 닫기"`에는 어떤 suppression도 결합돼 있지 않음.**

### 시스템 전체의 재발화 억제 계층과 쿨다운 CTA의 관계

| 레이어 | 매커니즘 | 주 CTA가 호출? |
|---|---|---|
| 세션 TTL | 30min (no trigger) / 60min (trigger) idle | ✗ (간접). 세션 유지이므로 새 신호 재감지 시 TTL 리셋 |
| α arm | `resetAfterUserConfirmedSafe` → 60s non-call shared-root 억제 | ✗ |
| snooze | `snoozeForCall(liveCallId)` | ✗ (not-inCall이므로 N/A) |
| `isShowing()` 가드 | 중복 `addView` 방지 | 뷰 레이어만 해당. dismiss 직후는 가드가 false 복귀 → 즉각 재소환 차단 효과 없음 |

### 구조적 공백 진단

- not-inCall 쿨다운 dismiss 직후, 같은 세션에서 은행 앱이 여전히 foreground + `REMOTE_CONTROL_APP_OPENED`/`BANKING_APP_OPENED_AFTER_REMOTE_APP` 등 appSignal이 살아있으면 → 세션 활성 유지 + 같은 조건으로 다음 tick에 `triggerIfNotActive` 재호출 가능
- 즉각 재소환을 막는 구조적 장치가 `isShowing()` 외에 없음 → **"해제 후 즉시 재소환 방어 공백"**
- 이 공백이 `COOLDOWN-DISMISS-TIMING` 원 pain point의 **잔여 축**. pain point 자체(해제 수단 부재)는 main의 `"전화 앱으로 이동" / "일단 닫기"` 주 CTA로 해소됐지만, 해제 후 시스템 복귀 여유 축은 여전히 미해결.

PR #3 참조점(미merge): 이 공백을 쿨다운 **보조 CTA**로 메우도록 `resetAfterUserConfirmedSafe` (α arm 60s) 호출. 주 CTA의 의미는 불변으로 남겼음.

---

## 4. Q4 — 그 suppression이 기존 `userConfirmedSafe`와 같은 의미인가, 더 좁은 별도 의미인가?

### 4.1. `userConfirmedSafe`는 단일 API가 아님 — 네 개 레이어의 복합체

main에서 "사용자가 안전 확인했다"는 개념은 **단일 함수로 존재하지 않고** 네 레이어로 분산돼 있다:

| 레이어 | 함수 | 효과 | 적용 조건 |
|---|---|---|---|
| **A. 세션** | `RiskSessionTracker.resetAfterUserConfirmedSafe()` | session null + α arm(60s, signal snapshot) + clearSnooze | 호출 시 무조건 (세션 null이면 α arm만 skip) |
| **B. 이벤트** | `RiskEventSink.clearCurrentRiskEvent()` | top-level currentRiskEvent 소거 | 호출 시 무조건 |
| **C. 통화-scope(snooze)** | `RiskSessionTracker.snoozeForCall(liveCallId)` | 해당 callId의 call-derived signal Coordinator pre-update 필터 | `liveCallId != null` |
| **D. 통화-scope(anchor)** | `clearTelebankingAnchor` + `markCurrentCallConfirmedSafe(callId)` | 텔레뱅킹 anchor 즉시 삭제 + 해당 callId 재anchor 차단 | `shouldApplyCallSafeEffects(inCall, signals) == true` (CALL_SIGNALS 전부에 속할 때) |

비쿨다운 팝업 B-3/5/6(`performSafeCtaSideEffects`)의 순서: **A → B → (조건) C → (조건) D**.
PR #3 쿨다운 보조 CTA의 순서: **A → dismiss** (B 생략, C/D는 not-inCall로 N/A).

### 4.2. not-inCall 쿨다운에서 **구조적으로 가능한 의미 범위**

not-inCall 전제이므로:
- **C (snooze)** → liveCallId null이라 N/A
- **D (anchor)** → 통화-scope라 N/A

즉 not-inCall 쿨다운 CTA가 취할 수 있는 레이어는 **A, B**만. 이것이 "full userConfirmedSafe의 not-inCall 한정 subset". 후보 semantic(명명은 Step 1에서 확정하지 않음, 참고용):

| 후보 | 구성 | full userConfirmedSafe와의 관계 |
|---|---|---|
| **N0.** view dismiss only | — | 현 main 주 CTA. 완전 disjoint (세션/이벤트/α 모두 유지) |
| **N1.** A (`resetAfterUserConfirmedSafe`) + dismiss | 세션 해제 + α arm, 이벤트 소거 없음 | **subset**. PR #3(`f6326ec`) 보조 CTA가 채택한 형태 |
| **N2.** A + B + dismiss | 세션 해제 + α arm + 이벤트 소거 | **subset**. not-inCall에서 취할 수 있는 **full userConfirmedSafe** |
| **N3.** α arm only (session 유지) | 세션을 살려두고 60s 재소환만 억제 | 현 API로는 표현 불가. `resetAfterUserConfirmedSafe`가 session==null 진입 시 snapshot 빈 set이라 α arm 생략. 새 API 필요 |

### 4.3. Step 1 답

- not-inCall 쿨다운에서 **구조적으로 가능한 suppression = 세션 레이어 α arm (A)**. 필요하면 이벤트 레이어(B)까지 추가 가능.
- 이는 기존 full `userConfirmedSafe`(A+B+C+D)의 **부분집합(subset)** 이다. C, D는 통화-scope이므로 구조적으로 N/A.
- `"같은 의미인가? 좁은 별도 의미인가?"` → **좁은 subset**.
- 이 subset을 어떻게 명명/표현할지(별도 이름 `cooldownAck` / `cooldownUserSafe` vs. 기존 API의 not-inCall caller로 흡수)는 **Step 1.5 이월 결정 포인트**.

---

## 5. 공백 / 리스크 정리

1. 현 main 주 CTA `"일단 닫기"`가 dismiss-only인 것은 **설계 의도와 일치**하나, UX상 두 가지 공백이 남아 있음:
   - 공백-1: 통화 종료 후 쿨다운 카운트다운이 아직 남아 있을 때, 주 CTA는 `"전화 앱으로 이동"`이 아닌 `"일단 닫기"`로 전환되며 즉시 dismiss는 가능하지만 — dismiss 후 같은 appSignal로 **즉시 재소환** 가능.
   - 공백-2: 재소환 방어 장치(α/snooze)가 쿨다운 CTA 경로에 결합돼 있지 않음.
2. α arm은 현재 **session reset 경로에 결합**되어 있음. "session은 살려두고 α arm만" 표현하는 API가 없음 (이런 의미를 만들지 여부도 Step 1.5 결정 사항).
3. `clearCurrentRiskEvent`의 호출 여부는 "UI 동기화" 목적. 쿨다운 overlay는 `currentRiskEvent`와 직접 바인딩되어 있지 않으므로 **쿨다운 경로에서 반드시 필요하지는 않다**. 단, 홈 카드 상태와의 일관성 관점에서 포함 여부는 검토 여지 있음.
4. PR #3가 보조 CTA 추가로 공백-2를 메우려 했지만, 주/보조 CTA의 의미 차이가 시니어 UX에 혼란을 줄 위험이 close 사유 중 하나였음(`feedback_cta_semantics_first.md`). 즉 **"의미를 추가하되 UI를 분리"** 대신 **"주 CTA 의미 자체를 재정의"** 방향도 Step 1.5에서 검토 대상.

---

## 6. Step 1.5로 이월할 결정 포인트 (REC-REFIRE 연결)

Step 1에서 확정하지 않음. Step 1.5 범위:

1. not-inCall 쿨다운의 CTA 구성을 **단일 CTA**(의미 하나)로 갈지, **이중 CTA**(dismiss + safe-confirm) 유지할지.
2. 채택할 suppression 범위 — N1(A only) / N2(A+B) / N3(α only) 중 하나로 고정.
3. 그 의미를 기존 `resetAfterUserConfirmedSafe` caller로 흡수할지, **별도 API로 분리**할지 (공용화 B안 여부).
4. N3 형태가 필요하다 판정되면 — α arm을 session reset에서 **분리할 새 API** 도입 여부.
5. `clearCurrentRiskEvent`를 쿨다운 경로에 포함할지 — 홈 카드 일관성과의 trade-off 정리.
6. REC-REFIRE(원격제어 앱 foreground 보유 시 5s마다 재감지) 근본 해결이 위 1–4 결정에 어떻게 종속/독립하는지. 특히 subset suppression이 REC-REFIRE를 부분 커버할지, 별도 축인지.

---

## 7. 결론 요약

- **Q1** — 현재 `"일단 닫기"` = view-level dismiss only. 세션/이벤트/α/snooze/anchor 모두 무간섭. 설계 의도상 세션 해제 책임은 홈의 `"안전 확인했어요"`에 위임.
- **Q2** — YES (현 main). 외부 경로의 `dismissIfShowing`은 CTA 의미에 포함하지 않음.
- **Q3** — NO. 재발화 억제 장치는 주 CTA에 결합돼 있지 않음. 해제 후 즉시 재소환 방어는 구조적 공백.
- **Q4** — not-inCall 쿨다운에서 가능한 suppression = 세션 레이어 α arm(A), 선택적으로 이벤트 레이어(B). 통화-scope(C, D)는 구조적 N/A. **full `userConfirmedSafe`의 좁은 subset**. 명명/공용화 여부는 Step 1.5 이월.

Step 1.5: 이 subset을 어떤 형태로 고정할지 + REC-REFIRE 해결안과의 결합 순서를 확정.
