package com.example.seniorshield.data.local

import android.util.Log
import com.example.seniorshield.data.local.db.RiskEventDao
import com.example.seniorshield.data.local.db.RiskEventEntity
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.RiskEventSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-EventSink"

/**
 * Room 기반 위험 이벤트 저장소.
 *
 * - [recentEvents]: Room DB에서 구독 — 앱 재시작 후에도 이력 유지 (최신 50건)
 * - [currentEvent]: 인메모리 StateFlow — 세션 중 가장 최근에 발생한 이벤트
 *   (경고 화면 표시용이므로 재시작 시 초기화되어도 무방)
 */
@Singleton
class RoomRiskEventStore @Inject constructor(
    private val dao: RiskEventDao,
    ioDispatcher: CoroutineDispatcher,
) : LiveRiskEventStore, RiskEventSink {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    override val recentEvents: StateFlow<List<RiskEvent>> = dao.observeRecent()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _currentEvent = MutableStateFlow<RiskEvent?>(null)
    override val currentEvent: StateFlow<RiskEvent?> = _currentEvent.asStateFlow()

    override suspend fun pushRiskEvent(event: RiskEvent) {
        Log.d(TAG, "pushRiskEvent — id=${event.id}, level=${event.level}, title=${event.title}")
        dao.insert(event.toEntity())
        _currentEvent.value = event
        Log.d(TAG, "currentEvent updated — level=${event.level}")
    }

    override suspend fun updateCurrentRiskEvent(event: RiskEvent) {
        _currentEvent.value = event
    }

    override suspend fun clearCurrentRiskEvent() {
        _currentEvent.value = null
    }

    override suspend fun clearAll() {
        dao.deleteAll()
        _currentEvent.value = null
        Log.d(TAG, "전체 이벤트 초기화")
    }
}

// ── 매퍼 ──────────────────────────────────────────────────────────────────────

private fun RiskEvent.toEntity() = RiskEventEntity(
    id = id,
    title = title,
    description = description,
    occurredAtMillis = occurredAtMillis,
    level = level.name,
    signalsCsv = signals.joinToString(",") { it.name },
)

private fun RiskEventEntity.toDomain() = RiskEvent(
    id = id,
    title = title,
    description = description,
    occurredAtMillis = occurredAtMillis,
    level = runCatching { RiskLevel.valueOf(level) }.getOrDefault(RiskLevel.LOW),
    signals = if (signalsCsv.isBlank()) emptyList()
              else signalsCsv.split(",").mapNotNull {
                  runCatching { RiskSignal.valueOf(it.trim()) }.getOrNull()
              },
)
