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
    FraudScenario(
        id = "remote_control",
        title = "원격제어 앱 설치 유도",
        description = "기관을 사칭하여 원격제어 앱 설치를 요구하는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "금융감독원 조사관입니다. 고객님 계좌에 의심 거래가 발견되어 보안 점검이 필요합니다. 제가 안내하는 앱을 설치해 주세요.",
                choices = listOf(
                    ScenarioChoice("네, 어떤 앱을 설치하면 되나요?", false, "금융감독원은 절대 전화로 앱 설치를 요구하지 않습니다. 원격제어 앱을 설치하면 개인정보가 모두 유출됩니다."),
                    ScenarioChoice("공공기관은 앱 설치를 요구하지 않습니다. 끊겠습니다.", true, "정확합니다! 어떤 공공기관도 전화로 앱 설치를 요구하지 않습니다. 즉시 끊으세요."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "'팀뷰어'라는 보안 점검 프로그램을 설치해야 합니다. Play 스토어에서 검색해서 설치해 주세요.",
                choices = listOf(
                    ScenarioChoice("알겠습니다, 지금 설치할게요.", false, "팀뷰어는 원격제어 프로그램입니다! 설치하면 사기꾼이 내 폰을 완전히 조작할 수 있습니다. 절대 설치하지 마세요."),
                    ScenarioChoice("원격제어 앱은 사기 수법입니다. 전화를 끊고 신고하겠습니다.", true, "훌륭합니다! 팀뷰어, 애니데스크 등 원격제어 앱 설치를 요구하면 100% 사기입니다. 112에 신고하세요."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "앱을 설치하셨으면 접속 코드를 알려주세요. 저희가 원격으로 보안 점검을 진행하겠습니다.",
                choices = listOf(
                    ScenarioChoice("접속 코드는 123456입니다.", false, "접속 코드를 알려주면 사기꾼이 폰을 원격 조작하여 금융 앱에 접근하고 돈을 빼갑니다!"),
                    ScenarioChoice("접속 코드를 절대 알려주지 않겠습니다. 앱을 삭제하겠습니다.", true, "완벽합니다! 이미 설치했다면 즉시 삭제하고, 접속 코드는 절대 알려주지 마세요. 비밀번호도 즉시 변경하세요."),
                ),
            ),
        ),
    ),
    FraudScenario(
        id = "telebanking",
        title = "텔레뱅킹 유도 사기",
        description = "수상한 전화 후 은행 ARS에 전화하도록 유도하는 사기 수법을 연습합니다.",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "금융감독원 수사관입니다. 고객님 명의 계좌가 대포통장으로 사용되고 있어 긴급 조치가 필요합니다. 지금 바로 본인 거래 은행에 전화하셔야 합니다.",
                choices = listOf(
                    ScenarioChoice("네? 큰일이네요. 어디로 전화하면 되나요?", false, "금융감독원은 전화로 은행에 연락하라고 지시하지 않습니다. 이것은 텔레뱅킹 유도 사기의 시작입니다."),
                    ScenarioChoice("금융감독원은 이런 식으로 연락하지 않습니다. 끊겠습니다.", true, "정확합니다! 공공기관은 전화로 은행 연락을 지시하지 않습니다. 즉시 전화를 끊으세요."),
                    ScenarioChoice("일단 알겠습니다. 나중에 확인해 볼게요.", false, "사기범은 시간을 주지 않고 즉시 행동하도록 압박합니다. '나중에'가 아닌 즉시 끊는 것이 안전합니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "지금 바로 국민은행 고객센터 1588-9999에 전화해서 '계좌 안전 조치'를 요청하세요. 제가 알려드리는 절차대로 하시면 됩니다.",
                choices = listOf(
                    ScenarioChoice("알겠습니다, 바로 전화하겠습니다.", false, "사기범이 알려준 번호는 가짜 ARS일 수 있습니다. 시니어쉴드는 수상한 통화 직후 은행 전화를 텔레뱅킹 유도로 감지하여 경고합니다."),
                    ScenarioChoice("은행에 직접 방문해서 확인하겠습니다.", true, "가장 안전한 방법입니다! 수상한 전화 후에는 전화가 아닌 직접 은행을 방문하세요."),
                    ScenarioChoice("은행 번호는 제가 직접 찾아보겠습니다.", false, "직접 번호를 찾는 것은 낫지만, 수상한 통화 직후 은행에 전화하면 사기범의 지시대로 행동하게 됩니다. 직접 방문이 가장 안전합니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "이 건은 극비 수사 사항이므로 가족을 포함해 누구에게도 알리면 안 됩니다. 알리시면 공무집행방해로 처벌받을 수 있습니다.",
                choices = listOf(
                    ScenarioChoice("네, 비밀로 하겠습니다.", false, "비밀 유지를 강요하는 것은 전형적인 사기 수법입니다. 진짜 수사기관은 절대 이런 요구를 하지 않습니다."),
                    ScenarioChoice("가족에게 먼저 상의하겠습니다.", true, "올바른 판단입니다! 수상한 전화를 받으면 반드시 가족이나 주변 사람에게 먼저 알려서 함께 판단하세요."),
                    ScenarioChoice("경찰(112)에 직접 확인하겠습니다.", true, "좋은 판단입니다! 의심되면 경찰(112) 또는 금융감독원(1332)에 즉시 신고하세요."),
                ),
            ),
        ),
    ),
    FraudScenario(
        id = "bank_impersonation",
        title = "은행 사칭 개인정보 탈취",
        description = "은행 직원을 사칭하여 비밀번호와 인증번호를 요구하는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "○○은행 보안팀입니다. 고객님 계좌에서 비정상 출금 시도가 감지되었습니다. 본인 확인을 위해 계좌 비밀번호를 알려주세요.",
                choices = listOf(
                    ScenarioChoice("아이고, 큰일이네요. 비밀번호는...", false, "은행은 절대 전화로 비밀번호를 묻지 않습니다! 이것은 100% 사기입니다."),
                    ScenarioChoice("은행은 전화로 비밀번호를 묻지 않습니다. 직접 은행에 확인하겠습니다.", true, "맞습니다! 은행은 어떤 경우에도 전화로 비밀번호, OTP, 보안카드 번호를 요구하지 않습니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "지금 문자로 보내드린 인증번호를 알려주시면 출금을 차단해 드리겠습니다.",
                choices = listOf(
                    ScenarioChoice("네, 인증번호는 483921입니다.", false, "인증번호를 알려주면 사기꾼이 계좌에서 돈을 빼갑니다! 문자 인증번호는 절대 타인에게 알려주면 안 됩니다."),
                    ScenarioChoice("인증번호는 본인만 사용해야 합니다. 전화를 끊겠습니다.", true, "정확합니다! 인증번호(OTP)는 본인만 알아야 하며, 누구에게도 알려주면 안 됩니다."),
                ),
            ),
        ),
    ),
)
