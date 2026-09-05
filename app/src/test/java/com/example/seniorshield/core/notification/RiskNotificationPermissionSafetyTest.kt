package com.example.seniorshield.core.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import com.example.seniorshield.MainActivity
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 33], application = Application::class)
class RiskNotificationPermissionSafetyTest {
    private lateinit var app: Application
    private lateinit var framework: NotificationManager
    private lateinit var manager: RiskNotificationManager
    private val received = mutableListOf<Pair<Int, Notification>>()
    private val event = RiskEvent("permission-event", "위험 제목", "위험 내용", 123L, RiskLevel.HIGH, emptyList())

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        framework = mockk(relaxed = true)
        every { framework.notify(isNull(), any(), any()) } answers {
            received += secondArg<Int>() to thirdArg<Notification>()
        }
        manager = RiskNotificationManager(object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.NOTIFICATION_SERVICE) framework else super.getSystemService(name)
        })
    }

    @Test
    fun deniedRuntimePermissionSkipsOnlyApi33AndAbove() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.notify(event)
        val expectedCalls = if (Build.VERSION.SDK_INT >= 33) 0 else 1
        assertEquals(expectedCalls, received.size)
        verify(exactly = expectedCalls) { framework.notify(null, event.id.hashCode(), any()) }
    }

    @Test
    fun grantedNotificationPreservesPayloadAndPendingIntent() {
        manager.notify(event)
        assertEquals(1, received.size)
        val (id, notification) = received.single()
        assertEquals(event.id.hashCode(), id)
        assertEquals(RiskNotificationManager.CHANNEL_ID, notification.channelId)
        assertEquals(event.title, notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(event.description, notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(Notification.PRIORITY_HIGH, notification.priority)
        assertEquals(android.R.drawable.ic_dialog_alert, notification.smallIcon.resId)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertNotNull(notification.contentIntent)
        val pending = shadowOf(notification.contentIntent)
        assertTrue(pending.isActivity)
        assertEquals(0, pending.requestCode)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, pending.flags)
        assertEquals(MainActivity::class.java.name, pending.savedIntent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, pending.savedIntent.flags)
    }

    @Test
    fun allRiskPrioritiesRemainUnchanged() {
        val priorities = mapOf(
            RiskLevel.LOW to Notification.PRIORITY_LOW,
            RiskLevel.MEDIUM to Notification.PRIORITY_DEFAULT,
            RiskLevel.HIGH to Notification.PRIORITY_HIGH,
            RiskLevel.CRITICAL to Notification.PRIORITY_MAX,
        )
        priorities.forEach { (level, expected) ->
            manager.notify(event.copy(level = level))
            assertEquals(expected, received.last().second.priority)
        }
    }

    @Test
    fun permissionRevokedAtNotifyIsSkipped() {
        every { framework.notify(isNull(), any(), any()) } answers {
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            throw SecurityException("notification permission revoked after check")
        }
        manager.notify(event)
        verify(exactly = 1) { framework.notify(null, event.id.hashCode(), any()) }
        assertTrue(received.isEmpty())
    }

    @Test
    fun unrelatedNotifyFailureIsNotSwallowed() {
        val failure = IllegalStateException("unrelated notification failure")
        every { framework.notify(isNull(), any(), any()) } throws failure
        assertEquals(failure, assertThrows(IllegalStateException::class.java) { manager.notify(event) })
    }

    @Test
    fun channelIdNameDescriptionAndImportanceRemainUnchanged() {
        manager.createChannel()
        verify(exactly = 1) {
            framework.createNotificationChannel(match {
                it.id == RiskNotificationManager.CHANNEL_ID &&
                    it.name.toString() == "금융사기 위험 경고" &&
                    it.description == "금융사기 위험이 감지될 때 즉시 알려드립니다." &&
                    it.importance == NotificationManager.IMPORTANCE_HIGH
            })
        }
    }
}
