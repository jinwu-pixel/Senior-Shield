package com.example.seniorshield.domain.model

enum class RiskSignal(val category: SignalCategory) {
    UNKNOWN_CALLER(SignalCategory.PASSIVE),
    LONG_CALL_DURATION(SignalCategory.PASSIVE),
    UNVERIFIED_CALLER(SignalCategory.PASSIVE),
    REMOTE_CONTROL_APP_OPENED(SignalCategory.TRIGGER),
    BANKING_APP_OPENED_AFTER_REMOTE_APP(SignalCategory.TRIGGER),
    /** 단독으로 세션을 생성하지 않는다. 기존 세션의 위험도·메시지만 강화(modifier). */
    HIGH_RISK_DEVICE_ENVIRONMENT(SignalCategory.PASSIVE),
    SUSPICIOUS_APP_INSTALLED(SignalCategory.TRIGGER),
    REPEATED_UNKNOWN_CALLER(SignalCategory.PASSIVE),
    REPEATED_CALL_THEN_LONG_TALK(SignalCategory.AMPLIFIER),
    TELEBANKING_AFTER_SUSPICIOUS(SignalCategory.TRIGGER),
}