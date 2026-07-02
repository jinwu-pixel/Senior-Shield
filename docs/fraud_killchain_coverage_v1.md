# SeniorShield Fraud Kill Chain Coverage v1

- 작성일: 2026-07-02 · 기준 커밋: `d680cc5` (main, 244 unit tests GREEN)
- 상태: **draft** (배치 커밋 대상, 즉시 커밋 금지 — 글로벌 commit policy)
- 성격: 분석/결정 프레임 문서. **이 문서의 어떤 항목도 구현 승인이 아니다.** 구현 착수는 항목별 별도 계획·승인 절차를 따른다.

## 0. 목적과 방법

SeniorShield는 "통화 → 원격제어/금융앱/은행 ARS"로 이어지는 핵심 보이스피싱 킬체인에는 강한 1차 방어를 갖췄다. 이 문서는 **사기꾼이 돈을 빼내는 경로 전체**를 단계별로 펼쳐 놓고, 각 단계에서 (1) 현재 앱이 무엇을 감지·차단하는지, (2) 무엇이 비어 있고 왜 비어 있는지(구조적 불가 vs 단순 미구현), (3) 어디에서 사용자를 멈춰 세울 수 있는지를 코드 사실 기반으로 매핑한다. 마지막으로 다음 구현 후보 3개를 선정하고, smsMenuEnabled 오버레이 게이팅의 의미 결정 프레임을 제시한다.

방어 철학(불변): 음성·번호의 진위를 판별하지 않고 **행동 패턴**을 본다. AI 음성 고도화 시대에 "진짜/가짜 목소리 판별"은 방어 중심이 될 수 없다 — 통제 연구에서 참가자들의 AI/실제 음성 분류 정확도는 37.5%로 우연 수준(50%) 미만, 판별력은 사실상 0이었다 ([Can You Tell It's AI? — arXiv 2602.20061](https://arxiv.org/abs/2602.20061); ※ 2026-07 현재 저자에 의해 withdrawn/개정 대기 상태이므로 인용 시 주의).

## 1. 최신 수법 동향 — 근거 요약

### 1.1 한국 (금감원·경찰청·KISA)

| 사실 | 수치 | 출처 |
|---|---|---|
| 2025년 보이스피싱 피해 사상 최대 | 1조 2,578억원, 23,360건, 건당 5,384만원 | [헤럴드경제 2026-01-24](https://biz.heraldcorp.com/article/10661923) |
| 2024년 피해 | 8,545억원 (당시 역대 최고) | [뉴데일리 2025-07-15](https://www.newdaily.co.kr/site/data/html/2025/07/15/2025071500048.html) |
| 수법 축 이동: 기관사칭형 지배 | 2025년 피해액의 78.6%가 기관사칭형 (2024년 75%) | 헤럴드경제·뉴데일리 위와 동일 |
| 사칭 축 이동: 가족→기관 | 메신저피싱 662억('23)→263억('24)→129억('25상) 감소, 기관사칭 611억→2,063억→2,603억 급증 | [아시아경제 2025-10-24](https://www.asiae.co.kr/article/2025102316421880204) |
| 고령층 집중 심화 | 경찰청 피해자 중 60대 이상 16%('20)→25%('24)→30.6%('25상, 전 연령 1위). 금감원 누적('20~'25상) 60대 이상 37.6% | [EBN 2025-09-30](https://www.ebn.co.kr/news/articleView.html?idxno=1680675), [EBN 2025-10-06](https://www.ebn.co.kr/news/articleView.html?idxno=1681433) |
| **대면편취(현금 전달)가 주류 전달 수단** | 대면편취형 8.6%('19)→47.7%('20)→**73.4%('21)**→64.4%('22) — 계좌이체형 역전. 이 급증이 2023-11-17 통신사기피해환급법 개정(대면편취 편입)의 입법 근거 | [금융위원회 보도자료 2023-11-16](https://www.fsc.go.kr/no010101/81090) |
| 스미싱 유형 상위 | 3년 누적: 공공기관 사칭(과태료·범칙금) 59.4% > SNS 기업 사칭 16.9% > 청첩장·부고 등 지인 사칭 15.5%. 2024년 탐지 150만 건(전년 3배) | [관계부처 합동, 정책브리핑](https://www.korea.kr/briefing/pressReleaseView.do?newsId=156670886), [보안뉴스(KISA) 2024-12](https://m.boannews.com/html/detail.html?idx=134792) |
| 악성앱 수법 | 기관 사칭 문자→APK 사이드로딩→연락처·문자 탈취, **통화 가로채기**(피해자가 금융사에 걸어도 범인 연결), 정상 앱 동일 아이콘 위장 | [KISA 보호나라 2025-03-20](https://www.boho.or.kr/kr/bbs/view.do?searchCnd=1&bbsId=B0000133&menuNo=205020&pageIndex=1&nttId=71689) |
| 원격제어 앱 설치 유도 | 카드배송·기관사칭 시나리오에서 "검열/명의도용 확인" 명목 원격제어 앱 설치가 주 수법 (2025 1분기 피해 3,116억, 전년比 2.2배) | [세계일보(금감원) 2025-04-27](https://www.segye.com/newsView/20250427507459) |

미확인 항목: 금감원 2024년 연간 분석 보도자료 원문 `[KR 소스 확인 필요]` · 2023~2025 대면편취 비중 연도별 수치 `[KR 소스 확인 필요]` · 상품권/가상자산 전달 수단 공식 비중 `[KR 소스 확인 필요]` (fss.or.kr / counterscam112.go.kr JS 렌더링으로 fetch 불가, data.go.kr 원파일 열람 필요).

### 1.2 해외 참고 (FBI IC3 2024 · FTC)

- FBI IC3 2024(원문 PDF 직접 확인): 총 손실 **$16.6B**(전년比 +33%). **60세 이상: 147,127건, $4.885B**(손실 +43%, 전 연령 1위, 전체의 약 29.4%). 손실 상위: Investment $6.57B > BEC $2.77B > Tech Support $1.46B(60+ 몫 $0.98B) > Government Impersonation $0.41B. 가상자산 연계 전체 $9.3B. [2024 IC3 Annual Report](https://www.ic3.gov/AnnualReport/Reports/2024_IC3Report.pdf)
- Crypto ATM/kiosk(IC3 2024): 10,956건 $246.7M — **연령 기재 신고의 약 2/3가 60세 이상**. FTC 계열 수치: 2023 $114M → 2025 $388M 급증, 피해액 2/3 이상이 고령층. [CBS News](https://www.cbsnews.com/news/bitcoin-atm-scams-ftc-cryptcurrency-fraud/), [Forbes 2026-06-22](https://www.forbes.com/sites/steveweisman/2026/06/22/ftc-warning-crypto-atm-scams-up-1000-how-to-protect-yourself/)
- **한국 적용 시 핵심 보정: 한국에는 내국인용 코인 ATM이 사실상 존재하지 않는다.** 특금법상 실명계좌 요건으로 익명 현금 BTM 운영 불가, 유일한 최근 시도(다윈KS, 외국인 전용)도 2025-09 FIU 조치로 중단. [전자신문 2024-07-24](https://www.etnews.com/20240724000226), [전자신문 2026-05-22](https://www.etnews.com/20260522000316) → 미국식 "현금→코인 ATM" 벡터는 한국에서 **"현금 인출→수거책 대면 전달"**(§1.1 대면편취 73.4%) 또는 "OTC/거래소 경유 코인 세탁"([아이뉴스24](https://m.inews24.com/v/1837461))으로 치환된다. 2026년 10월부터 가상자산사업자에 지급정지·피해환급 의무 부과 예정. [뉴스핌 2026-06-04](https://www.newspim.com/news/view/20260604000951)

### 1.3 AI 음성 사칭 (딥보이스)

- 국내 실사례: 항저우 기반 조직이 TV 출연 검사 얼굴·목소리로 딥페이크 사칭 연습(자백, 피해 1,491억 단일 조직 최대) [서울신문 2024-04-02](https://www.seoul.co.kr/news/society/law/2024/04/02/20240402001006) · AI 합성 딸 목소리 "엄마 살려줘" 사기(지하철 부역장이 저지) [농민신문 2025-04-10](https://www.nongmin.com/article/20250410500221) · 금감원 2026-02 "AI 조작 아이 울음소리" 소비자경보 [아주경제 2026-02-01](https://www.ajunews.com/view/20260201132746889) · 경찰청 2024-11 딥페이크 자녀납치형 주의 공지 [counterscam112](https://www.counterscam112.go.kr/bbs002/board/boardDetail.do?pstSn=5)
- 시사점: 음성 진위 판별은 인간에게도 기계에게도 신뢰할 수 없는 방어선 → SeniorShield의 행동 신호 중심 설계가 맞는 방향. AI 사칭 대응은 (i) 행동 신호(미저장 번호, 반복 호출, 송금 유도)와 (ii) 교육(시뮬레이션) + (iii) 자가확인 질문으로 구성해야 한다.

### 1.4 종합 시사점 (5줄)

1. 한국의 최신 주류는 **기관사칭 + 악성앱(사이드로딩·통화 가로채기) + 원격제어 유도 + 현금 대면편취** 조합이다.
2. 고령층 비중은 매년 커지고 있고(경찰 기준 피해자의 30.6%), 사이드로딩 경로도 고령층에 집중된다 — 국내 최대 스미싱 조직 검거 사례(청첩장·부고장 위장, 피해자 1,000여 명·120억원)에서 피해자의 80~90%가 50대 이상 [뉴시스 2025-11-26](https://www.newsis.com/view/NISX20251126_0003417219).
3. 앱의 현재 강점(원격제어/금융앱/텔레뱅킹 TRIGGER)은 정확히 이 주류 킬체인의 후반부를 때린다 — 방향은 맞다.
4. 가장 큰 공백은 **오프라인 자금이동(현금 대면 전달)** 과 **메신저/OTP 축**인데, 상당 부분이 "구조적 불가"라서 감지 확장이 아니라 **자가확인·교육·복구 UX**로 접근해야 한다.
5. 미국발 "코인 ATM" 위협은 한국에선 성립하지 않으므로, 시뮬레이션·경고 문구는 한국형(현금 수거책·거래소 경유)으로 조정한다.

## 2. 킬체인 단계 정의

| 단계 | 정의 | 대표 예시 |
|---|---|---|
| 0. 사전 정보수집 | 번호·음성·가족관계 데이터 수집 (딥페이크 학습 등) | **앱 개입 불가 영역 — 매트릭스 평가 제외** |
| 1. 최초 접근 | 전화/문자/메신저로 첫 접촉 | 검찰 사칭 전화, 부고장 문자, AI 자녀 음성 |
| 2. 신뢰구축/설득 | 사칭·서사 구축, 장기 그루밍 | 기관 사칭, 대출빙자, 로맨스/투자 |
| 3. 고립/압박 | 비밀유지·긴급성 강요, 검증 차단 | "가족에게 말하지 마라", 통화 유지 강요 |
| 4. 도구/권한 확보 | 원격제어 설치, 악성앱, OTP/인증번호 요구 | AnyDesk 설치, 교통민원24 사칭앱, 인증번호 요구 |
| 5. 자금이동 실행 | 실제 재산 이전 | 금융앱 이체, 텔레뱅킹, 현금 인출→대면 전달, 상품권 |
| 6. 피해 후 | 인지·신고·지급정지·복구 | 112/1332, 계좌 지급정지, 보호자 연락 |

## 3. 방어 커버리지 매트릭스

**방어 강도 rubric (코드 사실 기반)**
- **강** = TRIGGER 신호가 위험 세션 위에서 INTERRUPT/CRITICAL 팝업을 발화 (AlertStateResolver.kt:27-38 — TRIGGER 없으면 GUARDED에서 멈춤)
- **부분** = PASSIVE/AMPLIFIER 신호로 세션·점수에 기여하나 단독 팝업 불가 (GUARDED 이하)
- **약** = 런타임 감지 없음, FraudScenario 시뮬레이션 교육만 존재
- **없음** = 감지·교육 모두 부재 → 반드시 태그 부착: **[구조적 불가]**(통화내용 분석=도청·원칙 위반 / SMS·메신저 판독=원칙4·5 금지 / 위치추적=원칙5 금지 / 오프라인 물리행동=앱 관측범위 밖) vs **[단순 미구현]**(원칙 위반 없이 구현 가능하나 아직 없음). 이 태그가 없으면 "약함 = 다음에 만들면 됨"으로 오독된다.
- 표기 규칙: 한 수법이 킬체인 안에서 여러 하위 단계로 갈리면 `값A(축)/값B(축)` 병기 허용(예: 링크 단계=없음, 설치 단계=강). 코드 확인이 안 된 셀은 `확인 필요`로 표기하고 판정하지 않는다.

| 단계 | 대표 수법 | 방어 강도 | 방어 기전 | 근거 | 태그 |
|---|---|---|---|---|---|
| 0 | 번호·음성·가족관계 사전 수집 (딥페이크 학습 등) | 없음 | 앱 외부에서 일어나는 단계 — 감지 대상 아님 | — | **[구조적 불가]** (앱 관측범위 밖, §2에 따라 평가 제외) |
| 1 | 미저장/미검증 번호 전화 수신 | 부분 | UNKNOWN_CALLER(20)·UNVERIFIED_CALLER(20) PASSIVE → 세션 시작 | RiskSignal.kt:4,6 · RiskEvaluatorImpl.kt:42,44 · RiskSessionTracker.kt:106-125 | — |
| 1 | 번호 바꿔가며 반복 호출 | 부분 | REPEATED_UNKNOWN_CALLER(15) — 번호가 아닌 **미확인 호출 횟수** 기준(번호군), 30분 창 2회+ | RealCallRiskMonitor.kt:118,237-243,651-655,790 | — |
| 1 | 스미싱/메신저 링크 (부고장·과태료·가족사칭) | 없음(링크·내용 단계) / 강(설치 단계) | 문자·메신저 내용 판독 불가. 단 링크→APK 설치로 이어지면 4단계 SUSPICIOUS_APP_INSTALLED TRIGGER가 잡음 | RiskSignal.kt:11 (설치 단계) | 링크·내용 단계=**[구조적 불가]** (READ_SMS=원칙4 금지, 메신저 감시=원칙5) |
| 1 | AI 가족·지인 목소리 사칭 전화 | 부분+약 | 음성 진위 판별은 하지 않음(§1.3 — 판별 자체가 무력). 행동축(미저장 번호 수신=PASSIVE)으로만 세션화. family 시나리오 교육 존재 | RiskSignal.kt:4-6 · FraudScenario.kt:72-91 | 음성 판별=**[구조적 불가]** (통화내용 분석 금지) |
| 2 | 기관사칭/대출빙자 통화 지속 | 부분 | LONG_CALL_DURATION(30) PASSIVE, REPEATED_CALL_THEN_LONG_TALK(20) AMPLIFIER (3분+, CallSignalMapper 임계) | RiskSignal.kt:5,13 · RiskEvaluatorImpl.kt:43,50 | — |
| 2 | 기관·검찰·은행 사칭 서사 (교육 축) | 약 | prosecutor/loan/bank_impersonation 시나리오 | FraudScenario.kt:23-70,152-172 | — |
| 2 | 로맨스/투자/알바 장기 그루밍 (메신저 기반) | 없음 | 감지·교육 모두 부재 | — | 내용 감지=**[구조적 불가]** · 교육=**[단순 미구현]** (후보 b) |
| 3 | 장시간 통화 유지 강요 | 부분 | LONG_CALL_DURATION + AMPLIFIER (2단계와 동일 기전) | RiskSignal.kt:5,13 | — |
| 3 | "가족에게 말하지 마라" 고립 (교육 축) | 약 | telebanking 시나리오 Step3 (정답: 가족 상의·112 확인) | FraudScenario.kt:142-146 | 런타임 감지=**[구조적 불가]** (통화내용) |
| 4 | 원격제어 앱 실행 | **강** | REMOTE_CONTROL_APP_OPENED(30) TRIGGER — 단독 팝업(INTERRUPT+) | RiskSignal.kt:7 · AlertStateResolver.kt:27-38 · RemoteControlAppRegistry.kt:15-45 | registry gap 있음(부록 B) |
| 4 | 악성앱 사이드로딩 (교통민원24 사칭 등) | **강** | SUSPICIOUS_APP_INSTALLED(40) TRIGGER — 세션 시작 조건 겸함 | RiskSignal.kt:11 · RiskEvaluatorImpl.kt:48 | 위장 변형앱(임의 패키지명)은 패키지 매칭 불가 — 사이드로딩 감지가 방어선 |
| 4 | OTP/인증번호 구두 유출 요구 | 약 | 실시간 감지 없음. bank_impersonation Step2가 인증번호 요구를 교육 | FraudScenario.kt:164 | 감지=**[구조적 불가]** (READ_SMS 금지·통화내용 금지) → 후보 (a) 자가확인 문항이 원칙-호환 대체 |
| 5 | 원격제어 후 금융앱 이체 | **강** | BANKING_APP_OPENED_AFTER_REMOTE_APP(40) TRIGGER + 강제 CRITICAL + 쿨다운 60초 | RiskSignal.kt:8 · RiskEvaluatorImpl.kt:64-69 · BankingCooldownManager.kt:109-113 | — |
| 5 | 위험 세션 중 금융앱 실행 | **강** | 쿨다운 인터럽터 (CRITICAL 60s / HIGH 30s / 그 외 10s) | BankingCooldownManager.kt:40-42,109-113 | — |
| 5 | 텔레뱅킹 (수상 통화 후 은행 ARS 발신) | **강** | TELEBANKING_AFTER_SUSPICIOUS(25) — anchor 5분 창 내에서만 방출(단독 은행 발신 무시=오탐 방지), 단독 CRITICAL, 쿨다운 60초 | RealCallRiskMonitor.kt:217-232,673-677,785 · RiskEvaluatorImpl.kt:70 · BankArsRegistry.kt:13-67 (23개 기관) | — |
| 5 | **현금 인출 → 수거책 대면 전달** (한국 주류, '21년 73.4%) | 없음 | 은행 앱 실행까지는 5단계 기전이 잡지만, 창구/ATM 인출과 오프라인 전달은 관측 밖 | — | **[구조적 불가]** (위치추적=원칙5 금지, 물리행동 관측 불가) → 유일 개입=세션 생존(30-60분) 중 자가확인 (후보 a) |
| 5 | 상품권(기프트카드) 구매 유도 | 없음 | 감지·교육 모두 부재 | — | 구매행위 감지=**[구조적 불가]** · 교육=**[단순 미구현]** (후보 b) |
| 5 | 가상자산 경유 (거래소 앱/OTC) | 확인 필요 | 금융앱 registry에 가상자산 거래소 앱 포함 여부 미확인 — v1.1에서 RealAppUsageRiskMonitor 금융앱 목록 대조 필요 | `[확인 필요]` | 포함 안 돼 있다면 **[단순 미구현]** (registry 추가로 커버 가능) |
| 6 | 신고·상담·지급정지 다이얼 | 부분 | WarningScreen 기관 다이얼 4종: 112(경찰)·1332(금감원)·118(KISA)·1577-5500(금융결제원 지급정지) — 전부 ACTION_DIAL | WarningScreen.kt:57-62,86-88 | 거래은행 직통·사건 요약 부재 → 후보 (c) |
| 6 | 보호자에게 도움 요청 | 부분 | Warning/Guardian 화면 문자 메뉴(smsMenuEnabled 게이트) + 오버레이 문자 버튼(게이트 불일치 — §6) + ACTION_DIAL 통화 | WarningScreen.kt:159 · RiskOverlayManager.kt:456-493 | §6 의미 결정 대기 |
| 6 | 피해 사실 정리(무슨 일이 있었는지) | 없음 | History 화면은 이벤트 로그만, "사건 요약" 관점 화면 부재 | — | **[단순 미구현]** (후보 c — 단, 본인 열람 전용 제약) |

## 4. 행동 지연 지점 (Interdiction Points)

정의: 각 벡터에서 **되돌릴 수 없는 지점**(송금 완료 / 인증번호 유출 / 원격조작 허용 / 현금 전달) 직전에 시스템이 사용자를 멈춰 세우는가. 상태 = 활성 / 잠재적 / 부재 (하위 단계별로 갈리면 `잠재적→활성`처럼 전이 병기, 일부만 커버하면 `부분 활성`으로 표기).

| 벡터 | 되돌릴 수 없는 지점 | 마지막 개입 가능 시점 | 상태 | 기전 |
|---|---|---|---|---|
| 금융앱 이체 | 이체 확인 버튼 | 금융앱 포그라운드 진입 순간 | **활성** | 쿨다운 60/30/10초 강제 대기 (BankingCooldownManager.kt:109-113) |
| 텔레뱅킹 | ARS 이체 완료 | 은행 ARS 발신 순간 | **활성** | TELEBANKING TRIGGER → CRITICAL 팝업+쿨다운 60초 |
| 원격조작 허용 | 접속 코드 전달 | 원격제어 앱 실행 순간 | **활성** | REMOTE TRIGGER → 즉시 팝업 |
| 현금 인출→대면 전달 | 수거책에게 현금 전달 | **집을 나서기 전, 위험 세션 생존(30-60분) 동안** (앱은 외출을 관측하지 않음 — 세션 TTL 기반 노출만) | **부재** | 감지 기전 없음(구조적 불가). 유일한 개입 = 세션 생존 중 자가확인 질문 노출 (후보 a) — 새 감지가 아니라 "시점을 놓치지 않는 UX" 문제 |
| OTP 구두 유출 | 인증번호를 불러주는 순간 | 통화 중 | **부재** | 구조적 불가 → 자가확인 문항("인증번호를 알려달라고 했나요?")이 원칙-호환 대체 (후보 a) |
| 메신저 링크→악성앱 | 설치 완료·권한 허용 | APK 설치 순간 | **잠재적→활성** | 링크 단계는 못 잡지만 설치 순간 SUSPICIOUS_APP_INSTALLED TRIGGER가 잡음 (후단 방어) |
| 피해 직후 확산 | 추가 이체·2차 피해 | 피해 인지 직후 골든타임 | **부분 활성** | 지급정지 다이얼 존재(1577-5500). 거래은행 직통·행동 순서 안내 부재 (후보 c) |

## 5. 다음 구현 후보 평가 — 3개 선정

**"선정"은 권고 우선순위이며 구현 승인이 아니다.** 착수는 항목별로 별도 5줄 계획(수정 파일/목적/정책 리스크/테스트/중단 조건) 수립과 사용자 승인을 거친다.

스코어카드: 재산피해 감소효과 × 제품 원칙 적합성 × 구현 난이도.

| 후보 | 효과 | 원칙 | 난이도 | 판정 |
|---|---|---|---|---|
| (a) 위험 세션 중 자가확인 체크 | **Med-High** — 구조적 불가 벡터(현금 대면 전달·OTP·상품권)의 유일한 개입 지점 | **Green** — 통화 분석 0, 외부 전송 0, 사용자 자기응답만 | S-M | **선정 ①** |
| (b) 시뮬레이션 확장 4종 | Med — 교육(실시간 아님)이지만 §1의 최신 수법과 현 6종 사이 공백이 큼 | **Green** — FraudScenario.kt 정적 데이터 추가만, 권한/DI/Nav 무관 | **S** | **선정 ②** |
| (c) 피해 직후 복구 UX | Med — 최초 피해 방지가 아닌 확산 저지·골든타임 단축 | Green~Yellow — 다이얼 확장은 Green, 사건 요약 화면은 제약 필수 | S~M | **선정 ③** (2단계 분할) |
| (d) OTP/인증번호 요구 실시간 감지 | (High였다면) | **Red** | — | **탈락 — 어떤 변형으로도 구현 금지** |

### 선정 ① — 위험 세션 중 자가확인 체크 (Behavior Check)

- 내용: 위험 세션(GUARDED 이상) 동안 사용자가 스스로 확인하는 질문 세트 — "지금 돈을 보내라고 했나요?" / "인증번호를 알려달라고 했나요?" / "앱 설치나 화면 공유를 요구했나요?" / "은행·검찰·금감원·자녀라며 급하다고 했나요?" / "현금을 찾아서 누구에게 전달하라고 했나요?"(대면편취 대응).
- 설계 원칙: 통화 내용 자동 분석 없음, 응답의 외부 전송 없음, 응답의 저장 여부·보존 기간도 착수 계획에서 명시. 신규 화면·상태 신설보다 **기존 Warning 화면/오버레이/GUARDED_ANCHOR 홈 카드(HomeUiState.kt:38-45) 흐름에 얹는 방식을 우선 검토**(단순성 원칙). 단 오버레이 표면은 **이미 팝업이 발화된 INTERRUPT 이상 상황에 한정** — GUARDED에서 새 팝업을 만들지 않는다(AlertState 불변식 유지). "예" 응답 시의 효과(경고 강화? 지연? 안내?)는 CTA 의미 축 정의부터 — 착수 시 별도 5줄 계획 필수.
- (d)를 흡수: OTP 질문 문항이 원칙-호환 대체 — 이것은 감지가 아니라 사용자 자가응답이다.

### 선정 ② — 시뮬레이션 확장 4종 (한국형 보정 반영)

| 신규 시나리오 | 근거 |
|---|---|
| AI 자녀/손주 목소리 사칭 (납치·사고 빙자) | §1.3 국내 실사례 다수, 금감원 소비자경보 |
| **현금 인출→수거책 대면 전달** (원안 "코인 ATM"의 한국형 치환) | §1.2 — 한국에 코인 ATM 부재, 대면편취 73.4%가 실제 벡터 |
| 원격지원 + OTP/인증번호 요구 복합 | §1.1 카드배송·기관사칭 주 수법, 기존 remote_control/bank_impersonation의 복합 심화판 |
| 로맨스/투자/작업알바형 장기 설득 | FBI IC3 Investment $6.57B 최대 손실 유형, 국내 리딩방 |

- 원칙 리스크 0 (정적 데이터). 기존 6종(FraudScenario.kt:21-172)과 동일 구조로 추가 제안.

### 선정 ③ — 피해 직후 복구 UX (2단계 분할)

- **1단계 (S, Green)**: WarningScreen 기관 다이얼(WarningScreen.kt:57-62) 확장 — 거래은행 직통 다이얼(BankArsRegistry.kt의 23개 기관 번호를 감지용→표시/다이얼용으로 재사용 검토), "지금 순서: ①은행 지급정지 ②112 ③1332" 행동 순서 안내. 전부 ACTION_DIAL, 원칙 3 부합.
- **2단계 (M, Yellow — 제약 필수)**: "방금 무슨 일이 있었는지" 사건 요약 화면. **본인 열람 전용. 자동 전송·자동 공유 절대 금지**(위반 시 원칙 2·5 정면 충돌). 공유는 사용자가 직접 시작한 경우만(ACTION_SENDTO/공유시트) — 공유 UI를 포함할지 자체도 착수 계획에서 결정. 신규 destination 필요 시 Navigation 변경=고위험 → 기존 History 화면 확장으로 대체 가능한지 먼저 검토.
- 참고 제도: 2026-10부터 가상자산사업자 지급정지 의무화(§1.2) — 안내 문구에 반영 가치.

### 탈락 — (d) OTP/인증번호 요구 실시간 감지

어떤 형태로 구현해도 SMS 내용 판독(READ_SMS — CLAUDE.md 명시 금지 권한), 알림 내용 판독(NotificationListenerService — 감시성 기능, 원칙 5 저촉), 또는 통화 내용 분석(도청) 중 하나가 필요하다. **SMS·알림·통화내용 판독에 기반한 어떤 변형의 실시간 감지도 금지 — 구현 불가로 판정하고 재논의하지 않는다.** 유일하게 허용되는 형태는 선정 ①의 사용자 자가응답이며, 교육 축은 기존 bank_impersonation(FraudScenario.kt:164)이 담당한다.

## 6. smsMenuEnabled 오버레이 게이팅 — 의미 결정안 (미확정)

**팩트 (검증 완료, 2026-07-02 기준 라인)**
- 위험 팝업 오버레이의 "등록된 보호자에게 문자 보내기" 버튼은 `guardian != null`만 체크한다 (RiskOverlayManager.kt:456). smsMenuEnabled는 core/overlay·monitoring 계층에서 참조 0건.
- guardian 공급: DefaultRiskDetectionCoordinator.kt:153-154 `firstGuardian()` → :365, :389 `overlayManager.show(event, firstGuardian())`. guardian 파라미터의 유일한 용도가 이 버튼.
- 반면 Warning(WarningScreen.kt:159)·Guardian(GuardianScreen.kt:125) 화면은 `smsMenuEnabled && guardians.isNotEmpty()`로 게이팅. 기본값 OFF (SettingsRepositoryImpl.kt:52-54).
- 동작 자체는 ACTION_SENDTO(사용자 클릭, 자동 발송 아님)라 원칙 3 직접 위반은 아님. 문제는 **의미의 불일치**.

**결정 프레임 (이 문서는 확정하지 않는다 — 사용자 결정)**

| | A안: OFF = "평상시 문자 메뉴 숨김" | B안: OFF = "모든 문자 도움 요청 숨김" |
|---|---|---|
| 오버레이 처리 | 예외 유지 (위험 상황은 별도 맥락) | 오버레이도 게이트 |
| 필요한 작업 | 코드 0줄 — 현행 동작을 CLAUDE.md 승인된 예외표에 명시(예외 '신설'이 아니라 이미 동작 중인 현행의 문서화). 어느 안이든 현재의 암묵 불일치 상태는 해소 필요 | Coordinator에 SettingsRepository 주입 + firstGuardian() 게이트 (프로덕션 ~6줄 + 테스트 ~75줄, show() 시그니처 불변). 구현 방안은 검토돼 있으나 **착수 여부·시점 미결** — 결정 시 5줄 계획 승인 절차부터 |
| 리스크 | 사용자가 명시적으로 끈 기능이 위험 순간에 되살아남 (설정 신뢰 훼손) | 위험 순간의 보호자 연락 수단 하나가 줄어듦 (재산피해 방지 관점 손실) |

핵심 질문: **"위험 순간 보호자 연락 수단 축소"와 "사용자가 끈 기능의 위험 시 부활" 중 어느 쪽이 더 큰 리스크인가.** 어느 안이든 자동 전송 금지·ACTION_SENDTO 사용자 클릭 기반은 불변.

## 부록 A. 현재 감지 체계 사실 요약 (v1 기준 코드 근거)

- 신호 10종: PASSIVE 5 / TRIGGER 4 / AMPLIFIER 1 (RiskSignal.kt:3-15). HIGH_RISK_DEVICE_ENVIRONMENT는 단독 세션 생성 불가 modifier (RiskSignal.kt:9-10, RiskSessionTracker.kt:112-115).
- 점수: UNKNOWN_CALLER 20 / LONG_CALL 30 / UNVERIFIED 20 / REMOTE 30 / BANKING_AFTER_REMOTE 40 / HIGH_RISK_ENV 20 / SUSPICIOUS_INSTALL 40 / REPEATED 15 / REPEATED_LONG 20 / TELEBANKING 25 (RiskEvaluatorImpl.kt:41-52). RiskLevel: CRITICAL≥80 / HIGH≥50 / MEDIUM≥25 (:54-59).
- 강제 CRITICAL 3패턴: call 신호+TRIGGER / REMOTE+BANKING_AFTER_REMOTE / TELEBANKING 단독 (:67-72).
- 팝업은 점수가 아닌 AlertState로 발화: 세션 없음=OBSERVE, TRIGGER 없음=GUARDED(팝업 없음), TRIGGER=INTERRUPT, 고신뢰 조합=CRITICAL (AlertStateResolver.kt:27-38).
- 세션: 기본 TTL 30분, TRIGGER 시 60분, "안전 확인" 즉시 종료+α arm 60초 (RiskSessionTracker.kt:18-22, 260-271).
- 통화: 반복 호출 30분 창·번호군 방식(타임스탬프 버퍼), 텔레뱅킹 anchor 5분 창·단독 은행 발신 무시 (RealCallRiskMonitor.kt:118, 651-655, 673-677, 785, 790).
- 쿨다운: CRITICAL 60s / HIGH 30s / 그 외 10s, TYPE_APPLICATION_OVERLAY, 자동 통화 종료 없음 (BankingCooldownManager.kt:109-113, 129, 295-318).
- 은행 ARS registry: 23개 기관, normalize 후 exact match (BankArsRegistry.kt:13-67, 76-84). ※ CLAUDE.md의 "초기 목록 8개 은행"은 이후 23개 기관으로 확장된 상태.

## 부록 B. RemoteControlAppRegistry 갱신 제안 (코드 변경 없음 — 별도 승인 필요)

현재: TeamViewer·AnyDesk 프리픽스 + rsupport 3종·LogMeIn·Splashtop Personal·RealVNC 정확 매칭 (RemoteControlAppRegistry.kt:28-45).

| 우선순위 | 제안 | 근거 |
|---|---|---|
| 높음 | RustDesk 추가 (`com.carriez.flutter_hbb`) | 은행 사칭 캠페인 악용 확인 [Dr.Web](https://news.drweb.com/show/?i=14755), [ASEC](https://asec.ahnlab.com/ko/84654/) |
| 높음 | AweSun 추가 (`com.aweray.remote` 또는 `com.aweray.` 프리픽스) | RustDesk와 동일 캠페인 (Dr.Web) |
| 중간 | Splashtop 피제어측(SOS, `com.splashtop.sos`) 커버 검토 — 현재는 제어측 뷰어만 등록 | RemoteControlAppRegistry.kt:43 |
| 중간 | TeamViewer QuickSupport 주석 정정 (실제 Play ID `com.teamviewer.quicksupport.market`, 프리픽스 매칭이라 동작 무영향) | :25 주석 vs Play 스토어 |
| 참고 | 위장 변형앱(임의 패키지명)은 패키지 매칭으로 커버 불가 — 사이드로딩 감지(SUSPICIOUS_APP_INSTALLED)가 기존 방어선 | Dr.Web Android.FakeApp.1426 |

## 변경 이력

- v1 (2026-07-02): 최초 작성. 기준 커밋 d680cc5. 코드 인용 라인은 작성 시점 검증값(3관점 교차 검수: 인용 39건 전수 일치, 정책 위반 서술 0, 완전성 지적 2건 반영 완료) — 이후 코드 변경 시 재확인 필요.
