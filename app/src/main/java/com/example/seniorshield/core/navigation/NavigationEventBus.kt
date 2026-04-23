package com.example.seniorshield.core.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 앱 전역 네비게이션 이벤트 버스.
 *
 * ViewModel/Repository가 아닌 인프라(예: [com.example.seniorshield.core.overlay.RiskOverlayManager])에서
 * 네비게이션을 직접 조작할 수 없기 때문에(NavController는 Composable local),
 * 여기 emit하면 SeniorShieldNavGraph가 collect하여 popBackStack을 수행한다.
 *
 * **현재 이벤트**
 * - [popToHomeEvents]: 오버레이(팝업) 뒤로가기 시 Warning 등 하위 destination을 모두 pop하고 Home까지 복귀.
 *
 * 추가 이벤트가 필요해지면 의미별로 별도 SharedFlow를 둔다 — axis 혼용 금지.
 */
@Singleton
class NavigationEventBus @Inject constructor() {

    private val _popToHomeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val popToHomeEvents: SharedFlow<Unit> = _popToHomeEvents.asSharedFlow()

    /** 현재 destination에서 Home으로 복귀 요청. Home이면 no-op (collector 측). */
    fun popToHome() {
        _popToHomeEvents.tryEmit(Unit)
    }
}
