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
    FraudScenario(
        id = "ai_voice_clone",
        title = "AI 목소리 사칭 사기",
        description = "AI로 합성한 자녀 목소리로 사고를 빙자해 돈을 요구하는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "[전화] (흐느끼며) 엄마… 나야… 큰일 났어. 사고가 나서 지금 급하게 돈이 필요해…",
                choices = listOf(
                    ScenarioChoice("목소리가 우리 애가 맞네. 무슨 일이니? 얼마가 필요해?", false, "요즘은 AI로 목소리를 똑같이 흉내 낼 수 있습니다. 목소리가 같다고 자녀라고 믿으면 안 됩니다."),
                    ScenarioChoice("일단 끊고, 원래 알고 있는 번호로 직접 전화해서 확인할게.", true, "정확합니다! 목소리가 아무리 똑같아도, 반드시 기존에 알고 있는 번호로 직접 전화해서 확인하세요."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "합의금이 급해서 그래… 아빠한테는 말하지 마, 창피해. 이 계좌로 300만 원만 보내줘.",
                choices = listOf(
                    ScenarioChoice("알겠다, 아무한테도 말 안 하고 바로 보낼게.", false, "가족에게 비밀로 하라는 요구는 확인을 막으려는 전형적인 사기 수법입니다."),
                    ScenarioChoice("가족과 상의하고 네 원래 번호로 확인하기 전에는 못 보낸다.", true, "훌륭합니다! 비밀 요구와 급한 송금 요구가 함께 오면 사기를 의심해야 합니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "지금 바로 안 보내면 정말 큰일 나. 시간이 없어. 빨리!",
                choices = listOf(
                    ScenarioChoice("그래, 시간 없다니 서둘러 보낼게.", false, "서두르라는 압박은 침착한 판단을 막으려는 사기 수법입니다. 급할수록 멈추고 확인해야 합니다."),
                    ScenarioChoice("직접 통화하거나 만나서 확인하기 전까지는 절대 보내지 않겠다.", true, "완벽합니다! 가족끼리 미리 확인 질문을 정해 두면 이런 사기를 쉽게 가려낼 수 있습니다."),
                ),
            ),
        ),
    ),
    FraudScenario(
        id = "cash_pickup",
        title = "현금 수거 대면 사기",
        description = "기관을 사칭해 현금을 찾게 한 뒤 직원을 보내 직접 가져가는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "금융감독원입니다. 고객님 계좌가 범죄 자금에 연루되어 계좌에 있는 돈이 더 이상 안전하지 않습니다. 긴급 보호 조치가 필요합니다.",
                choices = listOf(
                    ScenarioChoice("네? 어떡하죠. 시키는 대로 하겠습니다.", false, "금융감독원은 전화로 계좌 위험을 통보하지 않습니다. 불안하게 만들어 지시에 따르게 하는 수법입니다."),
                    ScenarioChoice("금융감독원은 이렇게 전화하지 않습니다. 끊고 1332로 직접 확인하겠습니다.", true, "정확합니다! 의심되면 전화를 끊고 금융감독원 1332로 직접 전화해 확인하세요."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "수사 기밀이니 가족에게도 알리지 마시고, 은행에 가서 계좌의 돈을 전부 현금으로 찾으세요. 저희가 안전하게 보관했다가 돌려드립니다.",
                choices = listOf(
                    ScenarioChoice("알겠습니다. 지금 은행에 가서 현금으로 찾을게요.", false, "국가기관이 돈을 대신 보관해 주는 제도는 없습니다. 현금을 찾게 하는 것은 대면 편취 사기의 시작입니다."),
                    ScenarioChoice("국가기관은 현금을 보관해 주지 않습니다. 은행 직원에게 이 전화 내용을 그대로 말하겠습니다.", true, "맞습니다! 은행 창구 직원에게 사실대로 말하면 인출을 막고 신고를 도와줍니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "잠시 후 저희 직원이 댁 근처로 갈 겁니다. 찾은 현금을 봉투에 담아 직원에게 전달해 주세요.",
                choices = listOf(
                    ScenarioChoice("네, 준비하고 기다리겠습니다.", false, "어떤 국가기관도 직원을 보내 현금을 받아 가지 않습니다. 100% 사기입니다."),
                    ScenarioChoice("돈을 받으러 온다는 것 자체가 사기입니다. 즉시 112에 신고하겠습니다.", true, "완벽합니다! '직원이 현금을 받으러 간다'는 말이 나오면 그 순간 바로 112에 신고하세요."),
                ),
            ),
        ),
    ),
    FraudScenario(
        id = "remote_otp_combo",
        title = "원격지원·인증번호 복합 사기",
        description = "카드사를 사칭해 원격지원 앱을 설치시키고 인증번호까지 요구하는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "○○카드사입니다. 고객님 명의로 해외에서 카드 발급이 신청되었습니다. 명의도용인지 지금 확인해 드리겠습니다.",
                choices = listOf(
                    ScenarioChoice("제가 신청한 적 없어요. 어떻게 하면 되나요?", false, "상대가 안내하는 절차를 따라가면 안 됩니다. 카드 뒷면의 대표번호로 직접 전화해 확인하세요."),
                    ScenarioChoice("카드 뒷면 대표번호로 제가 직접 전화해서 확인하겠습니다.", true, "정확합니다! 확인이 필요하면 반드시 카드 뒷면이나 공식 홈페이지의 대표번호로 직접 전화하세요."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "명의도용 확인을 위해 원격 점검이 필요합니다. 문자로 보내드린 링크에서 원격지원 앱을 설치하고 화면 공유를 시작해 주세요.",
                choices = listOf(
                    ScenarioChoice("네, 설치하고 화면 공유를 시작할게요.", false, "원격지원 앱을 설치하면 사기꾼이 내 폰 화면을 보고 조작할 수 있습니다. 설치 요구는 100% 사기입니다."),
                    ScenarioChoice("원격 앱은 설치하지 않겠습니다. 전화를 끊고 신고하겠습니다.", true, "훌륭합니다! 어떤 기관도 전화로 원격지원 앱 설치나 화면 공유를 요구하지 않습니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "본인 확인 마지막 단계입니다. 방금 문자로 도착한 인증번호 6자리를 불러 주세요.",
                choices = listOf(
                    ScenarioChoice("인증번호는 592817입니다.", false, "인증번호를 불러주면 그 순간 돈이 빠져나갑니다. 은행·카드사 직원에게도 절대 알려주면 안 됩니다."),
                    ScenarioChoice("인증번호는 누구에게도 알려줄 수 없습니다. 앱을 삭제하고 카드사에 직접 확인하겠습니다.", true, "완벽합니다! 인증번호는 본인만 사용해야 합니다. 이미 앱을 설치했다면 즉시 삭제하고 비밀번호를 바꾸세요."),
                ),
            ),
        ),
    ),
    FraudScenario(
        id = "romance_investment",
        title = "친분·투자 권유 사기",
        description = "온라인에서 친분을 쌓은 뒤 투자를 권유해 목돈을 가로채는 시나리오",
        steps = listOf(
            ScenarioStep(
                fraudsterMessage = "[메시지] 요즘 대화가 즐거워요. 제가 하는 투자로 매달 수익을 내고 있는데, 소액으로 한번 체험해 보실래요?",
                choices = listOf(
                    ScenarioChoice("좋아요, 소액이니 한번 해볼게요.", false, "만난 적 없는 사람의 투자 권유는 사기입니다. 소액 수익 체험은 신뢰를 쌓기 위한 미끼입니다."),
                    ScenarioChoice("직접 만난 적 없는 분의 투자 권유는 받지 않겠습니다.", true, "정확합니다! 온라인에서만 알게 된 사람의 투자 권유는 아무리 친절해도 거절하세요."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "[메시지] 보세요, 벌써 20만 원 수익이 났어요! 이번에 1,000만 원을 넣으면 훨씬 크게 벌 수 있어요.",
                choices = listOf(
                    ScenarioChoice("정말 벌었네요! 이번에는 크게 넣어볼게요.", false, "수익 화면은 얼마든지 조작할 수 있습니다. 소액 수익을 보여준 뒤 목돈을 요구하는 것이 전형적인 수법입니다."),
                    ScenarioChoice("수익 화면만으로는 믿을 수 없습니다. 더 이상 투자하지 않겠습니다.", true, "훌륭합니다! 사기범이 보여주는 수익 인증은 조작된 화면입니다."),
                ),
            ),
            ScenarioStep(
                fraudsterMessage = "[메시지] 출금 신청이 접수됐어요. 그런데 출금하려면 먼저 수수료 5%를 입금하셔야 처리가 됩니다.",
                choices = listOf(
                    ScenarioChoice("알겠어요, 수수료를 먼저 보낼게요.", false, "출금하는 데 돈을 먼저 내라는 요구는 100% 사기입니다. 보낼수록 더 큰 돈을 요구합니다."),
                    ScenarioChoice("출금에 선입금을 요구하는 것은 사기입니다. 더 응하지 않고 경찰에 신고하겠습니다.", true, "완벽합니다! '출금 수수료 선입금'은 투자 사기의 결정적 신호입니다. 112 또는 1332에 신고하세요."),
                ),
            ),
        ),
    ),
)
