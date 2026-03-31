package com.example.seniorshield.feature.simulation

data class FraudScenario(
    val id: String,
    val title: String,
    val description: String,
    val steps: List<ScenarioStep>,
)

data class ScenarioStep(
    val fraudsterMessage: String,
    val choices: List<ScenarioChoice>,
)

data class ScenarioChoice(
    val text: String,
    val isCorrect: Boolean,
    val feedback: String,
)

val FRAUD_SCENARIOS = listOf(
    FraudScenario(
        id = "prosecutor",
        title = "검찰 사칭 사기",
        description = "검찰을 사칭하여 개인정보와 금전을 요구하는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "안녕하세요, 서울중앙지검 수사관 김○○입니다. 고객님 명의 계좌가 범죄에 이용된 것으로 확인되어 연락드렸습니다.",
                choices = listOf(
                    ScenarioChoice("네? 그럴 리가요. 무슨 일인가요?", false, "사기꾼은 불안감을 유발해 대화를 이어갑니다. 검찰은 전화로 수사 사실을 알리지 않습니다."),
                    ScenarioChoice("검찰이 전화로 연락하지 않습니다. 끊겠습니다.", true, "정확합니다! 검찰, 경찰, 금융감독원은 전화로 수사 사실을 통보하거나 개인정보를 요구하지 않습니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "본인 확인을 위해 주민등록번호와 계좌번호를 알려주셔야 합니다. 협조하지 않으시면 구속 수사될 수 있습니다.",
                choices = listOf(
                    ScenarioChoice("알겠습니다, 주민등록번호는...", false, "절대 안 됩니다! 정부기관은 전화로 주민등록번호나 계좌번호를 절대 요구하지 않습니다."),
                    ScenarioChoice("개인정보를 전화로 알려줄 수 없습니다. 112에 직접 확인하겠습니다.", true, "훌륭합니다! 의심되면 해당 기관에 직접 전화해서 확인하는 것이 가장 안전합니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "지금 바로 안전 계좌로 자금을 이체해야 보호됩니다. 제가 계좌번호를 알려드리겠습니다.",
                choices = listOf(
                    ScenarioChoice("네, 빨리 이체할게요.", false, "이것은 전형적인 보이스피싱입니다! '안전 계좌'라는 것은 존재하지 않습니다. 모든 이체 요구는 사기입니다."),
                    ScenarioChoice("안전 계좌는 없습니다. 전화를 끊고 경찰에 신고하겠습니다.", true, "완벽합니다! '안전 계좌'는 보이스피싱의 대표적인 수법입니다. 바로 112에 신고하세요."),
                ),
            ),
        ),
    ),
    FraudScenario(
        id = "loan",
        title = "대출 사기",
        description = "저금리 대출을 미끼로 수수료를 먼저 요구하는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "○○은행입니다. 고객님께 특별 저금리 대출 2,000만 원이 승인되었습니다. 관심 있으신가요?",
                choices = listOf(
                    ScenarioChoice("네, 좋은 조건이네요. 자세히 알려주세요.", false, "먼저 전화로 대출을 권유하는 것은 의심해야 합니다. 정상적인 은행은 전화로 대출 승인을 통보하지 않습니다."),
                    ScenarioChoice("은행에서 먼저 대출 전화를 하지 않습니다. 끊겠습니다.", true, "맞습니다! 은행은 먼저 전화해서 대출을 권유하지 않습니다. 대출이 필요하면 직접 은행에 방문하세요."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "대출 실행 전에 기존 대출을 정리해야 합니다. 선입금 200만 원을 보내주시면 바로 처리해 드리겠습니다.",
                choices = listOf(
                    ScenarioChoice("알겠습니다. 어디로 보내면 되나요?", false, "대출을 받기 위해 먼저 돈을 보내라는 것은 100% 사기입니다!"),
                    ScenarioChoice("대출받으려면 돈을 먼저 내라는 것은 사기입니다. 신고하겠습니다.", true, "정확합니다! 정상적인 금융기관은 대출 전 선입금을 요구하지 않습니다. 1332(금융감독원)에 신고하세요."),
                ),
            ),
        ),
    ),
    FraudScenario(
        id = "family",
        title = "자녀 사칭 사기",
        description = "자녀를 사칭하여 급하게 돈을 요구하는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "[메시지] 엄마, 나 폰이 고장나서 다른 번호로 연락해. 급하게 돈이 필요한데 50만 원만 보내줄 수 있어?",
                choices = listOf(
                    ScenarioChoice("아이고, 알겠다. 어디로 보내면 되니?", false, "잠깐! 자녀가 다른 번호로 연락해서 돈을 요구하면 반드시 기존 번호로 직접 전화해서 확인하세요."),
                    ScenarioChoice("기존 번호로 직접 전화해서 확인해 볼게.", true, "훌륭합니다! 자녀가 다른 번호로 급하게 돈을 요구하면, 반드시 기존에 알고 있는 번호로 직접 전화해서 확인하세요."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "[메시지] 지금 전화 못 받아, 회의 중이야. 급한 건이라 바로 보내줘. 이 계좌번호로 부탁해.",
                choices = listOf(
                    ScenarioChoice("그래, 급하니까 바로 보낼게.", false, "절대 안 됩니다! '전화 못 받아'라고 하면서 계좌 이체를 요구하는 것은 사기의 전형적인 수법입니다."),
                    ScenarioChoice("전화 확인 전까지 돈을 보내지 않겠습니다.", true, "정확합니다! 아무리 급해도 직접 음성 통화로 확인하기 전까지는 절대 돈을 보내지 마세요."),
                ),
            ),
        ),
    ),
)
