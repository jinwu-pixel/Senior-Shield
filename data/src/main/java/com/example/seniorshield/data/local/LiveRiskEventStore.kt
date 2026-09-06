package com.example.seniorshield.data.local

import com.example.seniorshield.domain.model.RiskEvent
import kotlinx.coroutines.flow.StateFlow

interface LiveRiskEventStore {
    val recentEvents: StateFlow<List<RiskEvent>>
    val currentEvent: StateFlow<RiskEvent?>
}
