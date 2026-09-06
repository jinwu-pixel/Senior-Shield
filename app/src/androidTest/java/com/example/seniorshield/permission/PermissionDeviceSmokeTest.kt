package com.example.seniorshield.permission

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.seniorshield.core.notification.RiskNotificationManager
import com.example.seniorshield.core.util.CallEndHelper
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real framework checks. The external runner controls permission state between processes. */
@RunWith(AndroidJUnit4::class)
class PermissionDeviceSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun requireDenied(permission: String) {
        assertTrue("This device suite requires Android 13+", Build.VERSION.SDK_INT >= 33)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
    }

    @Test
    fun deniedPhonePermissionReturnsNoCall() {
        requireDenied(Manifest.permission.READ_PHONE_STATE)
        assertFalse(CallEndHelper(context).isInCall())
    }

    @Test
    fun deniedPhonePermissionDoesNotRequestCallScreen() {
        requireDenied(Manifest.permission.READ_PHONE_STATE)
        assertFalse(CallEndHelper(context).showInCallScreen())
    }

    @Test
    fun deniedNotificationPermissionDoesNotPost() {
        requireDenied(Manifest.permission.POST_NOTIFICATIONS)
        val event = RiskEvent(
            id = "permission-smoke-${UUID.randomUUID()}",
            title = "Permission smoke test",
            description = "Denied notifications must not be posted",
            occurredAtMillis = 0L,
            level = RiskLevel.HIGH,
            signals = emptyList(),
        )
        RiskNotificationManager(context).notify(event)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertFalse(notifications.activeNotifications.any { it.id == event.id.hashCode() })
    }
}
