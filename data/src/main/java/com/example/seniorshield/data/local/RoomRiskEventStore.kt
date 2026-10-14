package com.example.seniorshield.data.local

import android.util.Log
import com.example.seniorshield.data.local.db.RiskEventDao
import com.example.seniorshield.data.local.db.RiskEventEntity
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.RiskEventSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-EventSink"
private const val HISTORY_RETRY_DELAY_MS = 5_000L

/**
 * Room 기반 위험 이벤트 저장소.
 *
 * - [recentEvents]: Room DB에서 구독 — 앱 재시작 후에도 이력 유지 (최신 50건)
 *   소비자가 있을 때만 조회하고, 실패 시 빈 목록을 표시한 뒤 5초 후 재구독한다.
 *   Home의 상시 수집도 소비자이므로 화면 표시 여부와 구독 수명은 다르다.
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
        .retryWhen { e, _ ->
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "risk history read failed — empty history used", e)
            emit(emptyList())
            delay(HISTORY_RETRY_DELAY_MS)
            true
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
            emptyList(),
        )

    private val _currentEvent = MutableStateFlow<RiskEvent?>(null)
    override val currentEvent: StateFlow<RiskEvent?> = _currentEvent.asStateFlow()

    override suspend fun pushRiskEvent(event: RiskEvent) {
        Log.d(TAG, "pushRiskEvent — id=${event.id}, level=${event.level}, title=${event.title}")
        dao.insert(event.toEntity())
        _currentEvent.value = event
        Log.d(TAG, "currentEvent updated — level=${event.level}")
    }

    override suspend fun recordRiskEvent(event: RiskEvent) {
        Log.d(TAG, "recordRiskEvent (이력만, currentEvent 미승격) — id=${event.id}, level=${event.level}, title=${event.title}")
        dao.insert(event.toEntity())
    }

    override suspend fun updateCurrentRiskEvent(event: RiskEvent) {
        _currentEvent.value = event
    }

    override fun clearCurrentRiskEvent() {
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
