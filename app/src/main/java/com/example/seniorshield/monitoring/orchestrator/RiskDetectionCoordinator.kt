package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SafeConfirmationOrigin {
    OVERLAY_LIVE_CALL,
    OVERLAY_IDLE,
    HOME,
    WARNING,
    DEBUG,
}

sealed interface SafeConfirmationSubject {
    data class Event(val eventId: String) : SafeConfirmationSubject {
        init { require(eventId.isNotBlank()) }
    }

    data class Session(val sessionId: String) : SafeConfirmationSubject {
        init { require(sessionId.isNotBlank()) }
    }

    data class Debug(val token: String) : SafeConfirmationSubject {
        init { require(token.isNotBlank()) }
    }
}

data class SafeConfirmationRequest(
    val origin: SafeConfirmationOrigin,
    val subject: SafeConfirmationSubject,
    val expectedResetEpoch: Long,
    val liveCallId: Long?,
    val signals: Set<RiskSignal>,
    /** Overlay/Debug surface identity; null only for non-overlay Session routes. */
    val expectedEventId: String? = null,
)

/** A published Event identity carried across the asynchronous Home navigation boundary. */
data class WarningNavigationPayload(
    val eventId: String,
    val expectedResetEpoch: Long,
) {
    init { require(eventId.isNotBlank()) }
}

internal enum class SafeConfirmationOverlayBindingKind { EVENT, DEBUG }

/**
 * Coordinator-issued, non-null capability for the Overlay safe-confirm CTA.
 *
 * The Overlay supplies only click-time call identity and the signals it actually displayed.
 * This binding owns origin selection and delegates the complete request back to Coordinator.
 */
data class SafeConfirmationOverlayBinding internal constructor(
    val subject: SafeConfirmationSubject,
    val expectedEventId: String,
    val expectedResetEpoch: Long,
    val expectedSignals: Set<RiskSignal>,
    internal val kind: SafeConfirmationOverlayBindingKind,
    private val consume: (SafeConfirmationRequest) -> Boolean,
) {
    init {
        require(expectedEventId.isNotBlank())
    }

    fun confirm(liveCallId: Long?, signals: Set<RiskSignal>): Boolean {
        val origin = when (kind) {
            SafeConfirmationOverlayBindingKind.EVENT -> if (liveCallId == null) {
                SafeConfirmationOrigin.OVERLAY_IDLE
            } else {
                SafeConfirmationOrigin.OVERLAY_LIVE_CALL
            }
            SafeConfirmationOverlayBindingKind.DEBUG -> SafeConfirmationOrigin.DEBUG
        }
        return consume(
            SafeConfirmationRequest(
                origin = origin,
                subject = subject,
                expectedResetEpoch = expectedResetEpoch,
                liveCallId = liveCallId,
                signals = signals,
                expectedEventId = expectedEventId,
            ),
        )
    }
}

interface RiskDetectionCoordinator {
    fun start()
    fun stop()

    /**
     * 텔레뱅킹 anchor가 5분 TTL 내에 살아 있는지 표시하는 UI-facing mirror.
     * Source of truth는 [com.example.seniorshield.monitoring.call.CallRiskMonitor.isTelebankingAnchorHot].
     * coordinator tick마다 읽어 갱신되며, Home이 GUARDED_ANCHOR 상태를 렌더할 때 사용.
     */
    val anchorHotState: StateFlow<Boolean>

    /** Non-replaying navigation receipts emitted only after Event publication completes. */
    val warningNavigationEvents: Flow<WarningNavigationPayload>

    /** Captures the current trusted Event or eventless Session route context. */
    fun captureSafeConfirmationRequest(
        origin: SafeConfirmationOrigin,
    ): SafeConfirmationRequest?

    /** Consumes a validated safe-confirm request at most once. */
    fun confirmSafe(request: SafeConfirmationRequest): Boolean

    /** Shows a Debug-only overlay without publishing it as the current event. */
    fun showDebugOverlay(event: RiskEvent)

    /** Publishes a Debug event completely before issuing and showing its safe-confirm binding. */
    suspend fun publishAndShowDebugOverlay(event: RiskEvent)

    /**
     * **명시적 사용자 액션 후 mirror 즉시 동기화**.
     *
     * 평시의 mirror 갱신은 15초 ticker 또는 combine emit 시점에 일어나므로,
     * "안전 확인했어요" 같은 명시적 safe-confirm 후에도 최대 15초 동안 stale `anchorHot=true`가
     * 남아 Home UI가 GUARDED_ANCHOR에 걸리는 체감 지연이 발생한다.
     * 이 메서드는 그 대기 없이 CallRiskMonitor의 실제 상태를 즉시 읽어 StateFlow에 반영한다.
     *
     * **호출 규칙**
     * - 사용자가 명시적으로 상태를 바꾼 시점에만 호출한다 (자동 재조회 경로에서 쓰지 않는다).
     * - 순서가 중요: [com.example.seniorshield.monitoring.call.CallRiskMonitor.clearTelebankingAnchor]
     *   호출 **이후**에 부르고, `eventSink.clearCurrentRiskEvent()` **이전**에 부른다.
     *   그렇지 않으면 `currentRiskEvent=null && anchorHot=true` 중간 상태가 드러나 GUARDED_ANCHOR가
     *   짧게 다시 보일 수 있다.
     */
    fun refreshAnchorHotNow()
}
