package com.example.seniorshield.feature.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenWarningNavigationContractTest {

    @Test
    fun `automatic Warning navigation validates the payload immediately before callback`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/feature/home/HomeScreen.kt",
        )
        val collector = source.substring(
            source.indexOf("LaunchedEffect(viewModel)"),
            source.indexOf("val lifecycleOwner", startIndex = source.indexOf("LaunchedEffect(viewModel)")),
        )
        val validation = collector.indexOf("viewModel.isWarningNavigationPayloadCurrent(payload)")
        val navigation = collector.indexOf("onNavigateWarning()")

        assertTrue(collector.contains("navigateToWarning.collect { payload"))
        assertTrue(validation >= 0)
        assertTrue("stale validation must happen before navigation", validation < navigation)
    }

    @Test
    fun `manual risk and guarded-card navigation remain direct user actions`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/feature/home/HomeScreen.kt",
        )

        assertTrue(source.contains("if (hasActiveRisk) Modifier.clickable { onNavigateWarning() }"))
        assertTrue(source.contains("onClick = onNavigateWarning"))
    }

    private fun readSource(relativePath: String): String {
        var root = File(".").canonicalFile
        while (!File(root, "settings.gradle.kts").exists()) {
            root = root.parentFile ?: error("project root not found")
        }
        return File(root, relativePath).readText(Charsets.UTF_8)
    }
}
