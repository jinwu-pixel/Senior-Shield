package com.example.seniorshield.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.seniorshield.data.local.LiveRiskEventStore
import com.example.seniorshield.data.local.RoomRiskEventStore
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.domain.repository.RiskRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltRiskStorageTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    // Do not inject Settings/Guardian: only SuiteDataStores may initialize their delegates.
    @Inject lateinit var sink: RiskEventSink
    @Inject lateinit var store: LiveRiskEventStore
    @Inject lateinit var repository: RiskRepository

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun productionBindingsShareOneRoomStoreAndInjectedRepositoryObservesSinkWrites() = storageTest {
        assertTrue(ApplicationProvider.getApplicationContext<Application>() is HiltTestApplication)
        assertTrue(store is RoomRiskEventStore)
        assertSame(sink, store)
        assertNull(repository.getCurrentRiskEvent().first())
        assertEquals(0, repository.countEventsSince(0))

        val event = RiskEvent(
            "hilt-event", "Hilt persisted event", "real Room through production bindings", 50,
            RiskLevel.HIGH, listOf(RiskSignal.UNKNOWN_CALLER),
        )
        try {
            sink.pushRiskEvent(event)
            assertEquals(event, repository.getCurrentRiskEvent().first { it != null })
            assertEquals(listOf(event), repository.getRecentRiskEvents().first { it.isNotEmpty() })
            assertEquals(1, repository.countEventsSince(50))
            assertEquals(0, repository.countEventsSince(51))
        } finally {
            sink.clearAll()
            store.recentEvents.first { it.isEmpty() }
        }
        // The eager store and its in-memory DB live until instrumentation process exit.
    }
}
