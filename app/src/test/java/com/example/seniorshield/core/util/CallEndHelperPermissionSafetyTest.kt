package com.example.seniorshield.core.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.telecom.TelecomManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CallEndHelperPermissionSafetyTest {
    private lateinit var app: Application
    private lateinit var telecom: TelecomManager
    private lateinit var helper: CallEndHelper
    private var service: TelecomManager? = null
    private var serviceReads = 0

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        telecom = mockk()
        service = telecom
        every { telecom.isInCall } returns true
        every { telecom.showInCallScreen(false) } returns Unit
        helper = CallEndHelper(object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.TELECOM_SERVICE) {
                    serviceReads++
                    service
                } else super.getSystemService(name)
        })
    }

    @Test
    fun deniedPermissionDoesNotAccessTelecomService() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertFalse(helper.isInCall())
        assertEquals(0, serviceReads)
        verify(exactly = 0) { telecom.isInCall }
    }

    @Test
    fun absentServiceReturnsFalse() {
        service = null
        assertFalse(helper.isInCall())
    }

    @Test
    fun grantedPermissionReturnsActiveCall() {
        assertTrue(helper.isInCall())
        verify(exactly = 1) { telecom.isInCall }
    }

    @Test
    fun grantedPermissionReturnsIdleCall() {
        every { telecom.isInCall } returns false
        assertFalse(helper.isInCall())
    }

    @Test
    fun permissionRevokedAtIsInCallReturnsFalse() {
        every { telecom.isInCall } answers {
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            throw SecurityException("READ_PHONE_STATE revoked after check")
        }
        assertFalse(helper.isInCall())
        verify(exactly = 1) { telecom.isInCall }
    }

    @Test
    fun unrelatedIsInCallFailureIsNotSwallowed() {
        val failure = IllegalStateException("unrelated telecom failure")
        every { telecom.isInCall } throws failure
        assertEquals(failure, assertThrows(IllegalStateException::class.java) { helper.isInCall() })
    }

    @Test
    fun deniedPermissionDoesNotAccessServiceOrOpenPhone() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertFalse(helper.showInCallScreen())
        assertEquals(0, serviceReads)
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun absentServiceDoesNotAcceptInCallInvocation() {
        service = null
        assertFalse(helper.showInCallScreen())
    }

    @Test
    fun normalReturnMeansInvocationAcceptedWithoutProvingUiVisibility() {
        assertTrue(helper.showInCallScreen())
        verify(exactly = 1) { telecom.showInCallScreen(false) }
    }

    @Test
    fun permissionRevokedAtOpeningReturnsFalse() {
        every { telecom.showInCallScreen(false) } answers {
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            throw SecurityException("READ_PHONE_STATE revoked after check")
        }
        assertFalse(helper.showInCallScreen())
        verify(exactly = 1) { telecom.showInCallScreen(false) }
    }

    @Test
    fun unrelatedOpeningFailureIsNotSwallowed() {
        val failure = IllegalStateException("unrelated telecom opening failure")
        every { telecom.showInCallScreen(false) } throws failure
        assertEquals(failure, assertThrows(IllegalStateException::class.java) { helper.showInCallScreen() })
    }
}
