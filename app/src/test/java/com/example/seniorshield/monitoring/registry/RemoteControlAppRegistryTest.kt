package com.example.seniorshield.monitoring.registry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlAppRegistryTest {

    private val registry = RemoteControlAppRegistry()

    // ── prefix 매칭 ──────────────────────────────────────────────

    @Test
    fun `TeamViewer Play Store 변형 - 매칭`() {
        assertTrue(registry.matches("com.teamviewer.teamviewer"))
    }

    @Test
    fun `TeamViewer Samsung Store 변형 - 매칭`() {
        assertTrue(registry.matches("com.teamviewer.teamviewer.market.mobile"))
    }

    @Test
    fun `TeamViewer QuickSupport - 매칭`() {
        assertTrue(registry.matches("com.teamviewer.quicksupport.host"))
    }

    @Test
    fun `AnyDesk 구버전 - 매칭`() {
        assertTrue(registry.matches("net.anydesk.adcontrol"))
    }

    @Test
    fun `AnyDesk 신버전 - 매칭`() {
        assertTrue(registry.matches("com.anydesk.anydeskandroid"))
    }

    // ── exact 매칭 ──────────────────────────────────────────────

    @Test
    fun `알서포트 MobileSupport - 매칭`() {
        assertTrue(registry.matches("com.rsupport.rs.activity.rsupport"))
    }

    @Test
    fun `알서포트 SAMSUNG 전용 - 매칭`() {
        assertTrue(registry.matches("com.rsupport.rs.activity.rsupport.sec"))
    }

    @Test
    fun `알서포트 RemoteCall - 매칭`() {
        assertTrue(registry.matches("com.rsupport.remotecall.rtc.host"))
    }

    @Test
    fun `LogMeIn Rescue - 매칭`() {
        assertTrue(registry.matches("com.logmein.rescuemobile"))
    }

    @Test
    fun `Splashtop - 매칭`() {
        assertTrue(registry.matches("com.splashtop.remote.pad.v2"))
    }

    @Test
    fun `RealVNC - 매칭`() {
        assertTrue(registry.matches("com.realvnc.viewer.android"))
    }

    // ── 미일치 ──────────────────────────────────────────────────

    @Test
    fun `일반 앱 - 미매칭`() {
        assertFalse(registry.matches("com.kakao.talk"))
    }

    @Test
    fun `뱅킹 앱 - 미매칭`() {
        assertFalse(registry.matches("com.kbstar.kbbank"))
    }

    @Test
    fun `빈 문자열 - 미매칭`() {
        assertFalse(registry.matches(""))
    }
}
