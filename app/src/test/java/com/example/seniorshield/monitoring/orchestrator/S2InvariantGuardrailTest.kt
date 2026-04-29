package com.example.seniorshield.monitoring.orchestrator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PR2 — S2 REC-REFIRE debounce isolation guardrails.
 *
 * ## Source of truth (단일 설계 참조)
 * 본 테스트의 설계 참조는 `investigations/2026-04-24-cta-semantics/04_step3_impl_plan.md`
 * §6 PR2-G1~G6 + G6-B 한 곳으로 고정한다. Step 2의 잠긴 결정(§7.4 격리 4규칙,
 * #C7-1 TTL `>` 연산자, #C7-2 자동화 승격 등)은 본 04 문서에 restate되어 있다.
 *
 * 01~03 investigation notes(`01_step1_semantics.md`, `02_step1_5_recrefire_linkage.md`,
 * `03_step2_design.md`)는 PR #5에서 separate docs scope로 다뤄진 상태이며 본 PR2의 직접 참조에
 * 두지 않는다 — 본 테스트 review의 source of truth가 04 한 곳임을 명확히 하기 위함.
 *
 * ## 본 클래스의 책임
 * Step 2 격리 4규칙 + #C7-1 + #C7-2를 가벼운 source-text 검사로 운영화한다. 동작 코드를
 * 변경하지 않고, S2/α/CTA 격리가 회귀하지 않도록 grep-static guardrail 7개(G1~G6 + G6-B)를
 * 자동화한다. G6-B는 BankingCooldownManager의 dismiss / dismissIfShowing 본문이
 * safe-confirm 부수효과로 오염되는 퇴행을 막는 추가 guardrail이다.
 *
 * ## 격리 원칙 (PR1 + §7.4 정적 규칙)
 * - 본 클래스는 운영 코드(`S2RecRefireDebounce`, `RiskSessionTracker`, `RiskOverlayManager` 등)를
 *   **import하지 않는다** — 모든 검사는 파일 본문을 텍스트로 읽어 적용한다.
 * - PR1 §11.2 baseline 식별자 5종을 grep 안정성 baseline으로 사용한다.
 *
 * ## 운영 방식
 * 각 G 케이스는 두 단계로 검사한다:
 *   1. **production assertion** — 실제 운영 소스가 위반 패턴을 포함하지 않음을 단언.
 *   2. **fixture assertion** — 동일 predicate가 위반 fixture 문자열에 대해 실제로 발화함을 단언
 *      (즉 predicate가 단순히 `false`만 반환하지 않음을 자기증명 — `04_step3_impl_plan.md` §6.4 RED 조건 회피).
 */
class S2InvariantGuardrailTest {

    // ── PR1 §11.2 baseline 식별자 5종 (grep 안정성 baseline) ──────────────
    private val s2TtlConst = "S2_REC_REFIRE_TTL_MS"
    private val s2GateFun = "shouldSuppressS2RecRefire"
    private val s2WakeUpFun = "s2RecRefireStateAfterFiring"
    private val s2StateClass = "S2RecRefireDebounceState"
    private val s2TestClass = "S2RecRefireDebounceTest"

    // ── source paths ─────────────────────────────────────────────────────
    private val s2MainPath = "app/src/main/java/com/example/seniorshield/monitoring/orchestrator/S2RecRefireDebounce.kt"
    private val s2TestPath = "app/src/test/java/com/example/seniorshield/monitoring/orchestrator/S2RecRefireDebounceTest.kt"
    private val alphaMainPath = "app/src/main/java/com/example/seniorshield/monitoring/session/RiskSessionTracker.kt"
    private val alphaTestPath = "app/src/test/java/com/example/seniorshield/monitoring/session/RiskSessionTrackerAlphaTest.kt"
    private val overlayMainPath = "app/src/main/java/com/example/seniorshield/core/overlay/RiskOverlayManager.kt"
    private val cooldownMainPath = "app/src/main/java/com/example/seniorshield/core/overlay/BankingCooldownManager.kt"

    // ── PR2-G1 ───────────────────────────────────────────────────────────
    // 격리 규칙 1: S2 클래스에 RiskSessionTracker import 금지

    @Test
    fun `G1_s2Sources_doNotImportRiskSessionTracker`() {
        val s2Main = readSource(s2MainPath)
        val s2Test = readSource(s2TestPath)

        assertFalse(
            "S2 운영 소스가 RiskSessionTracker를 import하면 격리 규칙 1 위반 (§7.4 규칙 1)",
            containsRiskSessionTrackerImport(s2Main),
        )
        assertFalse(
            "S2 테스트 소스가 RiskSessionTracker를 import하면 격리 규칙 1 위반 (§7.4 규칙 1)",
            containsRiskSessionTrackerImport(s2Test),
        )

        // fixture self-proof — predicate가 실제로 위반을 잡아내는지 자기증명
        val violationFixture = """
            package com.example.seniorshield.monitoring.orchestrator
            import com.example.seniorshield.monitoring.session.RiskSessionTracker
            class Foo
        """.trimIndent()
        assertTrue(
            "predicate self-proof: 위반 fixture(import RiskSessionTracker)는 G1 검사에서 발화해야 함",
            containsRiskSessionTrackerImport(violationFixture),
        )
    }

    // ── PR2-G2 ───────────────────────────────────────────────────────────
    // 격리 규칙 2: α 클래스에 S2 게이트 함수 import 금지

    @Test
    fun `G2_alphaSources_doNotImportS2GateSymbols`() {
        val alphaMain = readSource(alphaMainPath)
        val alphaTest = readSource(alphaTestPath)

        assertFalse(
            "α 운영 소스가 S2 게이트 식별자를 import하면 격리 규칙 2 위반 (§7.4 규칙 2)",
            containsS2GateImport(alphaMain),
        )
        assertFalse(
            "α 테스트 소스가 S2 게이트 식별자를 import하면 격리 규칙 2 위반 (§7.4 규칙 2)",
            containsS2GateImport(alphaTest),
        )

        // fixture self-proof: PR1 §11.2 baseline 5종 각각이 import로 등장하면 모두 위반으로 잡혀야 한다
        listOf(s2TtlConst, s2GateFun, s2WakeUpFun, s2StateClass).forEach { ident ->
            val violationFixture = """
                package com.example.seniorshield.monitoring.session
                import com.example.seniorshield.monitoring.orchestrator.$ident
                class Foo
            """.trimIndent()
            assertTrue(
                "predicate self-proof: 위반 fixture(import $ident)는 G2 검사에서 발화해야 함",
                containsS2GateImport(violationFixture),
            )
        }
    }

    // ── PR2-G3 ───────────────────────────────────────────────────────────
    // 격리 규칙 3: S2 클래스 본문에 CTA 핸들러 호출 0건

    @Test
    fun `G3_s2Sources_doNotCallCtaHandlers`() {
        val s2Main = readSource(s2MainPath)
        val s2Test = readSource(s2TestPath)

        assertFalse(
            "S2 운영 소스에 CTA 핸들러 호출이 등장하면 격리 규칙 3 위반 (§7.4 규칙 3)",
            containsCtaHandlerCall(s2Main),
        )
        assertFalse(
            "S2 테스트 소스에 CTA 핸들러 호출이 등장하면 격리 규칙 3 위반 (§7.4 규칙 3)",
            containsCtaHandlerCall(s2Test),
        )

        // fixture self-proof: §6.3 PR2-G3 표가 명시한 4종 호출 패턴 각각이 발화해야 한다
        val violationFixtures = listOf(
            "performSafeCtaSideEffects(",
            "HomeViewModel.confirmSafe(",
            "WarningViewModel.confirmSafe(",
            "overlayManager.dismiss(",
        )
        violationFixtures.forEach { token ->
            val fixture = "class Foo { fun bar() { $token state) } }"
            assertTrue(
                "predicate self-proof: 위반 fixture($token)는 G3 검사에서 발화해야 함",
                containsCtaHandlerCall(fixture),
            )
        }
    }

    // ── PR2-G4 ───────────────────────────────────────────────────────────
    // 격리 규칙 4: α 클래스에 S2 wake-up 함수 호출 0건 (#C7-2 자동화 승격)

    @Test
    fun `G4_alphaSources_doNotCallS2WakeUp`() {
        val alphaMain = readSource(alphaMainPath)
        val alphaTest = readSource(alphaTestPath)

        assertFalse(
            "α 운영 소스에 S2 wake-up 함수 호출이 등장하면 격리 규칙 4 위반 (§7.4 규칙 4 + #C7-2)",
            containsS2WakeUpCall(alphaMain),
        )
        assertFalse(
            "α 테스트 소스에 S2 wake-up 함수 호출이 등장하면 격리 규칙 4 위반 (§7.4 규칙 4 + #C7-2)",
            containsS2WakeUpCall(alphaTest),
        )

        // fixture self-proof — wake-up 함수 + gate decision 함수 호출 모두 잡아야 한다
        listOf(
            "$s2WakeUpFun(",
            "$s2GateFun(",
        ).forEach { token ->
            val fixture = "class Foo { fun bar() { $token state, signals, now) } }"
            assertTrue(
                "predicate self-proof: 위반 fixture($token)는 G4 검사에서 발화해야 함",
                containsS2WakeUpCall(fixture),
            )
        }
    }

    // ── PR2-G5 ───────────────────────────────────────────────────────────
    // TTL 경계 변형 위반 탐지 (#C7-1: `>` 연산자 잠금)
    //
    // **검사 규약 (commit 전 보강 — G5 regex 명확화):**
    //   negative predicate (위반):
    //     >=\s*(?:30_?000L|S2_REC_REFIRE_TTL_MS)
    //   positive predicate (정상 baseline):
    //     now\s*-\s*lastFiredAt\s*>\s*S2_REC_REFIRE_TTL_MS
    //
    // **요구:**
    //   - S2 운영 소스에서 positive baseline이 반드시 1건 이상 발견되어야 한다.
    //   - `>= 30_000L` 또는 `>= S2_REC_REFIRE_TTL_MS` 변형은 negative predicate에서 발화해야 한다.
    //   - self-proof fixture에 `>= S2_REC_REFIRE_TTL_MS` 케이스를 포함해 guardrail이
    //     실제 발화함을 자기증명한다 (§6.4 RED 회피).
    //   - 합법적 공백/줄바꿈 변경은 허용하되, `>=` 변형은 놓치지 않게 한다.

    @Test
    fun `G5_s2TtlOperator_isStrictGreaterThan_not_gte`() {
        val s2Main = readSource(s2MainPath)

        assertFalse(
            "S2 운영 소스에 `>= 30_000L` 또는 `>= S2_REC_REFIRE_TTL_MS` 형태가 등장하면 #C7-1 위반",
            containsTtlGteVariant(s2Main),
        )

        // 운영 소스에는 정상 baseline(`now - lastFiredAt > S2_REC_REFIRE_TTL_MS`)이 반드시 포함되어야 한다.
        // 합법적 공백/줄바꿈은 허용한다.
        assertTrue(
            "S2 운영 소스가 `now - lastFiredAt > $s2TtlConst` baseline을 포함해야 한다 (#C7-1 잠금된 정상 형태)",
            containsTtlPositiveBaseline(s2Main),
        )

        // negative predicate self-proof — `>=` 변형이 잡혀야 한다
        listOf(
            "if (now - lastFiredAt >= 30_000L) return false",
            "if (now - lastFiredAt >= S2_REC_REFIRE_TTL_MS) return false",
            "(now - lastFiredAt)>=30_000L",
            "(now - lastFiredAt) >= S2_REC_REFIRE_TTL_MS",
        ).forEach { token ->
            assertTrue(
                "predicate self-proof: 변형 fixture($token)는 G5 negative predicate에서 발화해야 함",
                containsTtlGteVariant(token),
            )
        }

        // positive predicate self-proof — 정상 형태(공백 변형 포함)가 잡혀야 한다
        listOf(
            "if (now - lastFiredAt > S2_REC_REFIRE_TTL_MS) return false",
            "now-lastFiredAt>S2_REC_REFIRE_TTL_MS",
            "now  -  lastFiredAt  >  S2_REC_REFIRE_TTL_MS",
        ).forEach { token ->
            assertTrue(
                "predicate self-proof: 정상 fixture($token)는 G5 positive baseline에서 발화해야 함",
                containsTtlPositiveBaseline(token),
            )
        }

        // positive predicate negative self-proof — `>=` 변형은 positive baseline에서 발화하면 안 된다
        listOf(
            "now - lastFiredAt >= S2_REC_REFIRE_TTL_MS",
            "now - lastFiredAt >= 30_000L",
        ).forEach { token ->
            assertFalse(
                "predicate self-proof: `>=` 변형($token)은 positive baseline에서 발화하면 안 됨",
                containsTtlPositiveBaseline(token),
            )
        }
    }

    // ── PR2-G6 ───────────────────────────────────────────────────────────
    // dismiss 경로와 safe-confirm 부수효과 결합 위반 탐지 — RiskOverlayManager.dismiss 한정.
    //
    // **본 guardrail의 범위 (commit 전 보강 — G6 한계 명시):**
    //   - 대상: `RiskOverlayManager.kt`의 `fun dismiss(...)` 본문 한 곳.
    //   - 검사: 해당 본문에 `performSafeCtaSideEffects(` 호출이 등장하지 않음을 단언한다.
    //
    // **본 guardrail이 보장하지 않는 것 (의도된 범위 한정):**
    //   - "dismiss-only CTA 전체 production wiring" 검증은 PR2 범위 밖이다.
    //   - `BankingCooldownManager` / `HomeViewModel` / `WarningViewModel` 등 다른 CTA wiring 전체 검증은
    //     PR3 또는 별도 CTA-side regression/guardrail에서 다룬다.
    //   - PR2는 behavior 변경 없이 dismiss 본문이 safe-confirm 부수효과로 오염되는 퇴행만 막는다.
    //
    // **G6-B (별도 테스트):** BankingCooldownManager의 dismiss 본문에도 동일 predicate를 적용해
    // 한 곳을 더 covers한다. 그래도 "전체 wiring 검증"의 책임은 여전히 후속 PR에 있다.

    @Test
    fun `G6_overlayDismiss_doesNotCallSafeCtaSideEffects`() {
        val overlay = readSource(overlayMainPath)

        val dismissBody = extractFunctionBody(overlay, "fun dismiss(")
        assertNotNull(
            "RiskOverlayManager.kt에서 `fun dismiss(` 본문을 찾을 수 없음 — 식별자 변경 시 §11.2 동시 갱신 필요",
            dismissBody,
        )

        assertFalse(
            "RiskOverlayManager.dismiss 본문이 performSafeCtaSideEffects를 호출하면 dismiss-only ↔ safe-confirm 결합 위반",
            dismissBody!!.contains("performSafeCtaSideEffects("),
        )

        // fixture self-proof — 결합 위반 fixture에서 predicate가 발화해야 한다
        val violationFixture = """
            class FakeOverlay {
                fun dismiss() {
                    performSafeCtaSideEffects(state)
                }
            }
        """.trimIndent()
        val fixtureDismissBody = extractFunctionBody(violationFixture, "fun dismiss(")
        assertNotNull(
            "predicate self-proof: fixture에서 dismiss 본문 추출이 가능해야 한다",
            fixtureDismissBody,
        )
        assertTrue(
            "predicate self-proof: 결합 위반 fixture는 G6 검사에서 발화해야 함",
            fixtureDismissBody!!.contains("performSafeCtaSideEffects("),
        )
    }

    // ── PR2-G6-B ─────────────────────────────────────────────────────────
    // BankingCooldownManager dismiss 경로에도 동일 predicate 적용 — G6 범위 한 곳 확장.
    //
    // **범위 한정 (G6과 동일):**
    //   - 본 검사도 BankingCooldownManager.dismiss 본문이 safe-confirm 부수효과로 오염되는 퇴행만 막는다.
    //   - "일단 닫기" CTA 전체 wiring 검증(쿨다운 인터럽터 → 세션 상태 → α arm 등)은 본 PR2 범위 밖이다.

    @Test
    fun `G6B_cooldownDismiss_doesNotCallSafeCtaSideEffects`() {
        val cooldown = readSource(cooldownMainPath)

        val dismissBody = extractFunctionBody(cooldown, "fun dismiss(")
        assertNotNull(
            "BankingCooldownManager.kt에서 `fun dismiss(` 본문을 찾을 수 없음 — 식별자 변경 시 §11.2 동시 갱신 필요",
            dismissBody,
        )

        assertFalse(
            "BankingCooldownManager.dismiss 본문이 performSafeCtaSideEffects를 호출하면 dismiss-only ↔ safe-confirm 결합 위반",
            dismissBody!!.contains("performSafeCtaSideEffects("),
        )

        // dismissIfShowing 본문도 같은 predicate를 적용한다 (G6과 같은 부패 패턴이 외부 진입점에 들어오는 회귀 차단).
        val dismissIfShowingBody = extractFunctionBody(cooldown, "fun dismissIfShowing(")
        assertNotNull(
            "BankingCooldownManager.kt에서 `fun dismissIfShowing(` 본문을 찾을 수 없음 — 식별자 변경 시 §11.2 동시 갱신 필요",
            dismissIfShowingBody,
        )
        assertFalse(
            "BankingCooldownManager.dismissIfShowing 본문이 performSafeCtaSideEffects를 호출하면 dismiss-only ↔ safe-confirm 결합 위반",
            dismissIfShowingBody!!.contains("performSafeCtaSideEffects("),
        )

        // fixture self-proof — 결합 위반 fixture에서 predicate가 발화해야 한다
        val violationFixture = """
            class FakeCooldown {
                fun dismissIfShowing() {
                    performSafeCtaSideEffects(state)
                }
                private fun dismiss() {
                    performSafeCtaSideEffects(state)
                }
            }
        """.trimIndent()
        listOf("fun dismiss(", "fun dismissIfShowing(").forEach { sig ->
            val body = extractFunctionBody(violationFixture, sig)
            assertNotNull("predicate self-proof: fixture에서 $sig 본문 추출이 가능해야 한다", body)
            assertTrue(
                "predicate self-proof: 결합 위반 fixture($sig)는 G6-B 검사에서 발화해야 함",
                body!!.contains("performSafeCtaSideEffects("),
            )
        }
    }

    // ── source readers + predicates ──────────────────────────────────────

    private fun readSource(relativePath: String): String {
        val root = findProjectRoot()
        val file = File(root, relativePath)
        check(file.exists()) {
            "guardrail source not found: ${file.absolutePath} (cwd=${File(".").canonicalPath})"
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists() ||
                File(dir, "settings.gradle").exists()
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        error("project root not found from cwd=${File(".").canonicalPath}")
    }

    /** G1: `import ...session.RiskSessionTracker` (전체 클래스 또는 nested 멤버) */
    private fun containsRiskSessionTrackerImport(source: String): Boolean {
        val pattern = Regex("""^\s*import\s+com\.example\.seniorshield\.monitoring\.session\.RiskSessionTracker\b""", RegexOption.MULTILINE)
        return pattern.containsMatchIn(source)
    }

    /** G2: PR1 §11.2 baseline 5종 중 어느 식별자라도 import 형태로 등장 */
    private fun containsS2GateImport(source: String): Boolean {
        val baselineGroup = listOf(
            s2TtlConst,
            s2GateFun,
            s2WakeUpFun,
            s2StateClass,
            "S2RecRefireDebounce",
        ).joinToString("|") { Regex.escape(it) }
        val pattern = Regex(
            """^\s*import\s+com\.example\.seniorshield\.monitoring\.orchestrator\.($baselineGroup)\b""",
            RegexOption.MULTILINE,
        )
        return pattern.containsMatchIn(source)
    }

    /** G3: 4종 CTA 호출 패턴 (괄호로 끝나는 call site) */
    private fun containsCtaHandlerCall(source: String): Boolean {
        val callPatterns = listOf(
            "performSafeCtaSideEffects(",
            "HomeViewModel.confirmSafe(",
            "WarningViewModel.confirmSafe(",
            "overlayManager.dismiss(",
            "RiskOverlayManager.dismiss(",
        )
        return callPatterns.any { source.contains(it) }
    }

    /** G4: S2 wake-up + gate decision 호출 (괄호로 끝나는 call site) */
    private fun containsS2WakeUpCall(source: String): Boolean {
        val callPatterns = listOf(
            "$s2WakeUpFun(",
            "$s2GateFun(",
        )
        return callPatterns.any { source.contains(it) }
    }

    /** G5 negative: `>= 30_000L` 또는 `>= S2_REC_REFIRE_TTL_MS` 변형 (#C7-1 위반) */
    private fun containsTtlGteVariant(source: String): Boolean {
        val pattern = Regex(
            """>=\s*(?:30_?000L|$s2TtlConst)\b""",
        )
        return pattern.containsMatchIn(source)
    }

    /**
     * G5 positive: `now - lastFiredAt > S2_REC_REFIRE_TTL_MS` 정상 baseline.
     *
     * 합법적 공백/줄바꿈은 허용하되 `>` 연산자 (strict greater-than)만 허용한다.
     * `>=` 변형이나 다른 식별자는 발화하지 않는다.
     */
    private fun containsTtlPositiveBaseline(source: String): Boolean {
        val pattern = Regex(
            """now\s*-\s*lastFiredAt\s*>\s*$s2TtlConst\b""",
        )
        return pattern.containsMatchIn(source)
    }

    /**
     * Kotlin source에서 주어진 함수 시그니처로 시작하는 본문을 brace 매칭으로 추출.
     * @return 함수 본문(`{ ... }` 포함) 또는 null(시그니처 미발견)
     */
    private fun extractFunctionBody(source: String, signaturePrefix: String): String? {
        val sigIdx = source.indexOf(signaturePrefix)
        if (sigIdx < 0) return null
        val openIdx = source.indexOf('{', sigIdx)
        if (openIdx < 0) return null
        var depth = 1
        var i = openIdx + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) return source.substring(openIdx, i + 1)
            i++
        }
        return null
    }
}
