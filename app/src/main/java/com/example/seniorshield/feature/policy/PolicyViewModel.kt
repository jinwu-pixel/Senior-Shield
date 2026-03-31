package com.example.seniorshield.feature.policy

import androidx.lifecycle.ViewModel
import com.example.seniorshield.domain.model.PolicySummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class PolicyViewModel @Inject constructor() : ViewModel() {

    val policy: StateFlow<PolicySummary> = MutableStateFlow(
        PolicySummary(
            title = "서비스 원칙 및 정책",
            items = listOf(
                "어르신 본인 보호를 최우선 원칙으로 합니다.",
                "다른 사람을 감시하거나, 나의 활동 정보를 다른 사람에게 자동으로 전송하지 않습니다.",
                "민감한 정보는 꼭 필요한 최소 범위만 처리하며, 가능한 휴대폰 내부에서만 처리합니다.",
                "모든 민감한 권한은 왜 필요한지 충분히 설명하고, 꼭 필요한 시점에만 요청합니다.",
                "시니어쉴드는 강제로 앱을 차단하는 앱이 아니며, 위험한 순간에 잠시 멈추고 다시 생각할 기회를 드리는 것을 목표로 합니다."
            ),
        )
    )
}
