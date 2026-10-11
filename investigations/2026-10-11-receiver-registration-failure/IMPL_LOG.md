# 수신기 등록 실패 T001 구현 기록

- 정본: main worktree의 `investigations/2026-10-11-receiver-registration-failure/DIRECTIVE.md` v1.0 전체.
- 기준: `e33fc3a`, `codex/receiver-registration-failure`. 시작 시 작업트리 변경 0.
- 상태: 승인된 구현 라운드 작성. 실행 검증 전이며 트랙 완료/GREEN을 주장하지 않는다.
- 제약: git 쓰기 0, Gradle 0, 컴파일/단위 테스트/adb 0. 실행 검증은 Claude 담당.
- 적용 절차: executing-plans, systematic-debugging, test-driven-development, using-git-worktrees, verification-before-completion, requesting-code-review. 별도 계획/ledger 대신 허용된 이 파일에 판단을 기록한다. 테스트를 먼저 작성했으나 RED 실측은 사용자 지정에 따라 Claude의 역적용 라운드로 위임한다.

## 1. 변경 목록과 승인된 5줄 계획

1. 파일: 통화/설치 monitor 2개, 각 provenance 테스트 2개, CI floor 2개, 이 문서와 RESULT 편지.
2. 목적: 수신기 등록 실패를 해당 source의 중립 방출로 격리하고 취소 때 소유권/executor를 정리한다.
3. 정책/권한: 새 권한/외부 연락/서비스/DI 변경 없음. 통화 번호 수신기 실패 시 해당 수집 주기는 통화 감시를 비활성화한다는 승인 의미를 따른다.
4. 확인: 기존 실패 처리·하네스 직접 대조, 정적 diff/원문 보존 검사. Claude가 RED/MA1–MA4·전체 GREEN·lint/schema/self-check를 실행한다.
5. 중단: 범위 밖 변경 필요, provenance 충돌, 새 제품 결정, S1–S5 발생 시 구현 중단·보고.

| 파일 | 변경 줄 수 (+/-) | 내용 |
|---|---:|---|
| `app/src/main/java/com/example/seniorshield/monitoring/call/RealCallRiskMonitor.kt` | 21/4 | API31 번호 수신기 등록 한 곳에 실패 분기 |
| `app/src/main/java/com/example/seniorshield/monitoring/appinstall/RealAppInstallRiskMonitor.kt` | 16/4 | 설치 수신기 등록 한 곳에 실패 분기, CE import |
| `app/src/test/java/com/example/seniorshield/monitoring/call/RealCallRiskMonitorProvenanceTest.kt` | 90/0 | K3(a)(b) 2개 추가, 기존 helper 수정 없음 |
| `app/src/test/java/com/example/seniorshield/monitoring/appinstall/RealAppInstallRiskMonitorProvenanceTest.kt` | 23/0 | K3(c) 1개 추가, verify import |
| `.github/scripts/verify-unit-xml.ps1` | 2/1 | app 584→587, ASCII 이력 주석 |
| `.github/workflows/verify.yml` | 1/1 | 단계 이름의 floor만 변경 |

## 2. K 매핑

| 계약 | 구현/단언 | 판정 범위 |
|---|---|---|
| K1 | call :666–688. 일반 Exception → 지정 Log.e → 현재 epoch Idle SEED → awaitClose에서 세대 release 후 shutdownNow → return | 번호 receiver 등록 한 곳. 등록 실패 시 unregister 없음. TelephonyCallback 등록에 도달하지 않음 |
| K2 | appinstall :101–116. 일반 Exception → 지정 Log.e → empty Produced → awaitClose에서 resetJob 취소/null → return | 성공 시 로그·초기값·awaitClose·분류 로직은 보존 |
| K3(a) | call test :453. SecurityException, epoch 1 중립 1회/추가 방출 없음, collector active, callback 등록 0, callId null, 취소 뒤 shutdown 및 unregister 0 | 기존 isShutdown() 공개 override 사용. helper 수정 0 |
| K3(b) | call test :488. 실패 수집 취소/종료 후 옛 owner 쓰기 거부, 같은 monitor 재구독, 새 receiver/callback/seed 큐 확인, epoch 1 OFFHOOK seed 중립 및 callId 복원 | 실패 epoch 0 replay로 회복을 오판하지 않음. 새 owner가 옛 세대를 무효화하는지 확인 |
| K3(c) | install test :82. epoch 3 empty 1회, 가상 10초 뒤 job active/추가 방출 없음, 취소 전후 unregister 0 | 기존 collectEmissions/runTest/UnconfinedTestDispatcher 하네스 사용 |
| K4 | app floor 587 / risk 7 / contracts 4 | 신규 @Test 정확히 3개. workflow의 명령·조건·권한 불변 |

## 3. RED 근거 및 판별력

모든 아래 결과는 **정적 예상**이다. Codex는 테스트나 mutant를 실행하지 않았다.

| 사례 | 수정 전 코드 예상 | 근거 |
|---|---|---|
| (a) | RED | registerReceiver의 SecurityException이 callbackFlow→flatMapLatest→shareIn sharing coroutine 밖으로 전파된다. 중립 SEED가 없어서 awaitEmission이 5초 내 받지 못하거나, 테스트 프로세스에서 미처리 예외가 보고된다. 소유권과 executor 정리도 누락된다 |
| (b) | 이 테스트 구성은 RED 예상 | 첫 중립 awaitEmission부터 수정 전에는 통과하지 못한다. 이를 건너뛰더라도 shutdown 대기·재획득 전 옛 owner 쓰기 거부가 실패하며, 같은 monitor의 죽은 sharing coroutine은 재구독만으로 복구되지 않는다. 단순 새 claim만 검사하면 release가 빠져도 새 claim이 옛 owner를 덮으므로 GREEN일 수 있다. 그래서 새 claim 전에 옛 owner 쓰기 거부를 별도로 검사한다 |
| (c) | RED | 직접 수집하는 callbackFlow가 SecurityException으로 종료된다. runTest 자식 실패/초기값 부재/active 단언 실패 중 하나로 검출된다 |

| 주입 | 판별 테스트 | 정적 기대와 한계 |
|---|---|---|
| MA1: K1 역적용 | (a), (b) | (a) 중립 수신 timeout 또는 미처리 예외로 RED. (b)도 첫 실패 SEED 대기에서 RED 예상. 반드시 Claude 실측 |
| MA2: K2 역적용 | (c) | 수집 예외 종료/초기 empty 부재로 RED |
| MA3: K1 일반 실패 awaitClose의 shutdownNow 제거 | (a), (b) | 취소 뒤 isShutdown 대기 7초 timeout으로 RED. CE 분기의 shutdown만 제거하면 SecurityException을 쓰는 이 3개 테스트는 검출하지 못한다 |
| MA4: K1 일반 실패 awaitClose 대신 즉시 return | (a) 또는 (b) | 채널을 닫지 않고 return하면 callbackFlow 수명 규약 예외; 중립이 먼저 전달되어도 shutdown 대기가 RED. 명시 close까지 넣는 변형에서는 shareIn 하류 job.isActive만으로 upstream 종료를 증명할 수 없다. 정리도 빠진 변형은 shutdown으로 검출하지만, close+정리까지 추가한 다른 변형은 통과 가능하므로 mutant 정확한 hunk와 실측 결과를 기록해야 한다 |

판별력의 의도: (b)의 epoch 전진은 stale replay(@0)를 새 seed(@1)로 착각하지 않도록 한다. 성공 seed를 OFFHOOK으로 설정해 중립 방출과 소유권 기반 callId 복원을 함께 관찰한다. callback 슬롯·broadcast 슬롯·수동 executor 큐도 모두 확인한다.

## 4. T2 자체검토

### 예외 전파와 수명

- 통화 공유 흐름은 `SupervisorJob() + Dispatchers.Default` scope의 `shareIn(WhileSubscribed(5_000), replay=1)`이다. SupervisorJob은 실패한 자식의 일반 예외를 흡수하거나 해당 sharing coroutine을 재시작하지 않는다. 등록 예외를 callbackFlow 내부에서 처리하여 이 경로로 내보내지 않는 것이 K1이다.
- 중립 SEED는 observeCallSignals의 SEED gate에서 neutralReset으로 변환되어 위험신호/반복호출/anchor 부수효과를 만들지 않는다. awaitClose로 source를 살아 있게 유지한다.
- 설치 흐름은 직접 callbackFlow를 수집하므로 예외가 하류 combine으로 나가면 수집 전체를 끊는다. empty Produced와 awaitClose가 기존 성공 초기값과 동일하게 combine을 충전한다. coordinator 자체는 변경하지 않았다.
- 등록을 자동 재시도하지 않는다. 통화 회복 테스트는 실제 upstream 종료 후 같은 monitor를 재구독한다. 설치 자동 회복/부분 통화 감시는 범위 밖 승인 의미다.

### 소유권·executor 정리

- claim은 등록보다 앞서 실행된다. 일반 실패에서는 기존 TelephonyCallback 실패와 같이 awaitClose까지 보유하고, 취소 시 releaseOwnership(myOwnerGeneration) CAS로 owner와 callId를 함께 무효화한 뒤 executor.shutdownNow()를 호출한다.
- 이전 owner가 이미 대체된 경우 release는 false이며 새 owner/callId를 건드리지 않는다. executor는 해당 collector의 지역 자원이라 release 성공 여부와 무관하게 정리한다.
- ManualExecutorService의 기존 volatile flag를 읽는 isShutdown()으로 취소 후 정리를 관찰한다. (b)는 재획득 전 writeCallId(failedOwner, null)의 거부로 release 누락을 직접 구별한다. 새 helper나 생산 seam은 없다.
- 통화 테스트의 7초 timeout은 기존 shareIn의 실제 5초 stop 유예를 포함하는 상한이다. 10ms 조건 폴링은 기존 API31 하네스 패턴이며 임의의 장시간 고정 sleep을 추가하지 않았다. 정상 구현에서는 두 테스트 각각 약 5초 유예가 필요하다. 설치 10초 관찰은 가상 시간이다.

### unregister 미호출

- 등록 호출이 실패한 receiver에는 성공 등록으로 취급하는 cleanup을 하지 않는다. K1에서는 TelephonyCallback도 등록 전이다. 일반 실패와 CE 분기 모두 unregisterReceiver를 호출하지 않는다.
- 기존 TelephonyCallback 실패 분기의 unregister는 번호 receiver 등록 성공 이후 실패이므로 필요하다. 그 블록은 변경하지 않았다.
- 실패 후 플랫폼이 부분 등록을 남기는 특이 OEM 동작은 단위 하네스로 보장하지 못한다. 지시문의 실패 시 미해제 계약을 따른다.

### CancellationException 판단

- registerReceiver는 동기 비중단 호출이므로 일반 coroutine 취소가 이 호출에서 CE를 던지는 형태는 아니다. 그러나 Exception은 CE의 상위 타입이므로 신규 catch는 타입을 확인하고 재던진다.
- K1 CE는 일반 등록 실패로 중립화하지 않는다. 이미 얻은 owner/executor를 먼저 release/shutdown하고 동일 CE를 재던진다. 즉시 재던지기만 하면 awaitClose에 도달하지 않아 자원이 누락되므로 이 최소 cleanup을 K1 catch 안에 둔다.
- K2 CE는 즉시 재던진다. 정상 동기 등록 실패 지점에는 설치 방송 처리가 시작되지 않았고 resetJob은 null이다.
- 기존 TelephonyCallback/legacy catch는 CE 구분이 없지만 승인 범위 밖이므로 손대지 않았다. 신규 블록의 일반 Exception 경로는 지정된 기존 형태와 같고, CE guard만 취소 보존을 위해 다르다. CE 분기 실측은 이번 +3 테스트에 포함되지 않는다.

### provenance 계약 상호작용

- 실패 SEED는 발화 시 epoch로 생산한다. 번호 등록 실패 시 callback과 명시 seed가 아직 만들어지거나 제출되지 않으므로 initialClaimed 경쟁이 없다.
- 성공 경로의 initialClaimed 원자 선점, callGeneration, 번호 epoch/회차 결속, callbackDeliveryExecutor 제출 epoch 핸드오프는 수정하지 않았다.
- 실패 이후 reset이 와도 이미 생산된 replay를 새 epoch로 바꾸지 않는다. 기존 stale gate가 적용되며, (b)는 이를 고려해 새 seed를 생산 epoch로 구분한다.
- 실패 cleanup은 새 세대에 늦게 접근하지 못하도록 기존 CAS 함수를 재사용한다. 별도 공유 상태나 lock은 추가하지 않았다.

### S2 / CTA 8항목

`.github/S2_REVIEW_CHECKLIST.md` 전체를 읽고 §6의 8항목을 대조했다.

| 항목 | 정적 판정/근거 |
|---|---|
| 1. 일단 닫기는 dismiss-only | 보존. dismiss/overlay/cooldown 핸들러 변경·새 호출 0 |
| 2. safe-confirm 전용 흐름 | 보존. 생산 코드에 safe-confirm 호출 추가 0 |
| 3. REC-REFIRE는 S2 orchestration | 보존. S2/coordinator/CTA 입력 접근 변경 0 |
| 4. TTL 경계는 `>` | 보존. TTL 상수와 비교식 변경 0 |
| 5. 비통화 dismiss는 call-safe 효과 없음 | 보존. call-safe/snooze/anchor 호출 추가 0 |
| 6. 통화 safe-confirm 진입 제한 | 보존. markCurrentCallConfirmedSafe와 UI 진입점 변경 0 |
| 7. α/S2 상수·상태·함수·테스트 분리 | 보존. 해당 파일과 테스트 변경 0 |
| 8. UPGRADE_TRIGGERS | 보존. 세 정의/소유 위치 변경 0 |

C1/C2/C3: CTA 부수효과나 S2 입력 접근을 추가하지 않았다. 승인된 차이는 실패 source에서 empty를 내어 나머지 감지가 계속될 수 있게 하는 것이다. 정상 신호 시퀀스는 보존하며 CTA가 scope 교집합이나 escape delta를 조작하는 경로는 추가되지 않는다.

## 5. 정적 확인과 실행 한계

- `git diff --check`: 통과. 코드·CI tracked 변경은 허용 6파일뿐이다.
- Python 원문 비교: 새 테스트 블록/import 제거 후 기존 두 테스트 파일과 HEAD 내용이 같음. 통화 @Test 29→31, 설치 4→5. 기존 단언 삭제/변경 0.
- 설치 monitor의 성공 로그부터 파일 끝까지, receiver 본문과 분류 로직은 원문과 동일(개행 정규화 후 비교). 등록 if/else는 try 안으로 들여쓰기만 이동했다. 체크아웃 개행 CRLF를 유지했다.
- `ManualExecutorService` 기존 isShutdown override 확인. 새 테스트는 기존 seam·slot·Channel/runBlocking 또는 runTest 수집 패턴을 사용한다.
- 정적 정책 확인: 새 permission/Manifest/DI/service/interface/coordinator/registry 변경 0. SMS/외부 연락/자동 통지 추가 0. `/project:ss-policy` 실행 결과라고 주장하지 않는다.
- 컴파일·unit XML·lint·schema·verifier self-check·MA1–MA4는 미실행. Android 실제 등록/실제 방송 순서도 미확인.
- 컴파일 주의 지점: MockK registerReceiver 2인자 overload 및 registerTelephonyCallback any() 타입 추론, JUnit Long/Long? assertEquals, SDK stub + sdkIntProvider(S) 조합. 기존 파일 패턴을 재사용했지만 Kotlin 컴파일 전 확정 불가.

## 6. Claude 실행 요청

1. 승인 환경/JBR에서 직렬로 신규 테스트를 먼저 컴파일·실행한다. 필터:
   - `com.example.seniorshield.monitoring.call.RealCallRiskMonitorProvenanceTest.api31 number receiver registration failure stays neutral until cancellation`
   - `com.example.seniorshield.monitoring.call.RealCallRiskMonitorProvenanceTest.api31 number receiver registration recovers after cancellation and resubscription`
   - `com.example.seniorshield.monitoring.appinstall.RealAppInstallRiskMonitorProvenanceTest.receiver registration failure stays neutral until cancellation`
2. 테스트는 유지하고 K1/K2만 각각 역적용하여 (a)(c) RED를 실측한다. (b)의 실제 실패 위치도 기록한다. 이어 MA3/MA4를 정확한 mutant hunk와 함께 각각 측정하고 원복한다. CE guard의 shutdown과 일반 실패 awaitClose의 shutdown을 혼동하지 않는다.
3. 회귀 필터: `com.example.seniorshield.monitoring.call.RealCallRiskMonitor*Test`, `com.example.seniorshield.monitoring.appinstall.*AppInstall*Test`. 필터 실행 후 full gate는 반드시 필터 없이 fresh 직렬 실행한다.
4. 전체 gate: `clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug` 및 기존 no-daemon/no-parallel/max-workers=1 플래그.
5. XML app 587/risk 7/contracts 4(합계 598), failures/errors/skipped 0 확인. 기존 lint verifier로 신규 0, schema drift 0, verifier self-check 및 정책 검사, 범위 무접촉 확인. app 587은 기대치이며 실측값이 아니다.

## 7. 자체검토 판정

작성자 정적 검토에서 must-fix/recommend 발견 없음. 실행 결과는 info/미확인으로 분리한다. 별도 읽기 전용 검토(static_review)도 수정 필수 0·수정 권장 0으로 응답했다. 해당 검토도 컴파일/테스트/lint/mutation을 실행하지 않았다. Claude 독립 검증 전 병합/트랙 완료 판단은 보류한다.


## T001 REVIEW 대응

- 근거: `20261011-1712-T001-REVIEW`, 사용자 승인 수정안 A 및 정리 대기 15초. 이번 쓰기는 통화 provenance 테스트 1개, 이 절 append, RESULT2로 한정했다. 프로덕션 변경 0, git 쓰기/Gradle/컴파일/테스트/adb 실행 0.
- Claude 전달 실측(3클래스 38건): MA0에서 (a)(c) GREEN, (b) TimeoutCancellationException RED. MA1에서는 (a)(b) RED/(c) GREEN, MA2에서는 (a) GREEN/(b)(c) RED, MA3·MA4에서는 (a)(b) RED/(c) GREEN. 이는 Claude의 REVIEW 결과이며 Codex가 실행한 결과가 아니다. 이번 실측 MA4는 **중립 SEED 제거**로, 앞 절에 적은 awaitClose 제거 예상과 다른 mutant다.
- **(b) 원인 정정:** `WhileSubscribed(5_000)`의 replay는 stop 후에도 남는다. reset 후 두 번째 수집자의 실패 SEED@0은 stale gate를 통해 `neutralReset(0)`이 되고, 새 OFFHOOK SEED@1도 SEED gate에서 `neutralReset(1)`이 된다. `neutralReset`은 둘 다 `(RESET, [])`를 만들며 `distinctUntilChanged { a, b -> a.value == b.value }`(RealCallRiskMonitor.kt:525)는 epoch를 제외한다. 따라서 @1 선별 대기는 올바른 구현에서도 끝나지 않는다. 초기 IMPL_LOG/RESULT의 epoch 1 방출로 회복을 증명한다는 설명과 정적 검토 결론은 이 항목으로 정정한다.
- **수정:** broadcast/callback 슬롯과 seed 큐 확인 뒤 drain 전에 replay를 명시적으로 소비해 epoch 0·빈 신호를 단언한다. drain 뒤 freshSeed epoch 1 대기를 제거하고 `assertNoFurtherEmission()`으로 중립 RESET 합침/위험 신호 미파생을 확인한다. 요청된 dUC 설명 주석 1줄을 추가했다. 그 아래 기존 회복 단언은 모두 그대로 유지했다.
- **회복 증명 근거:** `ManualExecutorService.drain()`은 큐에서 꺼낸 Runnable의 `task.run()`을 직접 호출하므로 seed 작업 완료 후 반환한다. API31 seedTask(RealCallRiskMonitor.kt:819–866)는 owner 일치와 initialClaimed 선점에 성공해야 진행한다. `restoreSeedState(OFFHOOK)`(:1059 이후)는 `clock()`을 `offhookAtMillis`로 넣고, seedTask는 `writeCallId(myOwnerGeneration, offhookAtMillis)`(:847) CAS가 성공해야 SEED를 전송한다. 테스트는 TelephonyManager.callState를 OFFHOOK으로 고정하며 callback을 구동하지 않는다. 실패 경로는 callId를 만들지 않고 claim/release는 null로 설정한다. 따라서 drain 후 `currentCallId() == fakeClock.provider()`라는 non-null 값은 새 owner가 OFFHOOK seed를 실제로 복원한 증거다. 재구독 전 failedOwner 쓰기 거부는 이전 owner 해제를, drain 후 동일 옛 owner 쓰기 거부는 세대 격리를 확인한다. executor 생성 2회·callback 등록 1회·반복호출 버퍼 empty·anchor null 단언도 유지했다. 정상 신호의 하류 전달 여부를 새 epoch 중립 방출로 검증한다는 의미는 아니다.
- **대기 15초:** (a)(b)의 cleanup 조건 대기 `withTimeout(7_000)` 두 곳만 `withTimeout(15_000)`으로 변경했다. 실제 stopTimeout은 여전히 5초이며 shutdown 조건이 충족되면 즉시 끝나므로 성공 시 고정 비용은 늘지 않는다. 기존 파일의 `released.await(15, TimeUnit.SECONDS)` 두 곳과 같은 여유다. 다른 5초 수신/등록 대기는 변경하지 않았다.
- **K2 서술 정정:** coordinator는 `CoroutineScope(SupervisorJob() + ioDispatcher)`(:192), 통화 sharingScope는 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`(:246)이며 CEH가 없다. coordinator.start의 `scope.launch` 안에서 설치 Flow를 combine/merge/collect하며 등록 예외를 감싸는 catch도 없다. 미처리 일반 등록 예외는 단순한 **조용한 정지**가 아니라 Android 기본 미처리 예외 경로의 **앱 크래시**로 이어진다. 기존 K2 설명 중 coordinator 수집 종료만 언급해 결과를 축소한 부분을 정정한다. K2 중립화 프로덕션 코드는 변경하지 않는다.
- **범위 밖 고위험 후보 — 다음 트랙:** API 26~30 legacy 경로의 `object : PhoneStateListener()`(RealCallRiskMonitor.kt:921)가 sharingScope의 Default 스레드에서 생성되는 구조다. 해당 스레드에 Looper가 없으면 생성 단계에서 실패할 수 있다는 **정적 분석 후보이며 미검증**이다. 이번 라운드에서 수정하거나 실기/테스트를 실행하지 않았다.
- 정적 확인: 승인된 치환을 역으로 적용하면 라운드 시작 테스트 원문과 정확히 같음. 기존 회복 단언·helper·신규 테스트 개수는 불변이다. IMPL_LOG 기존 바이트는 prefix 그대로 보존했다. 실제 GREEN·판별력 재측정은 Claude에게 요청하며 성공을 주장하지 않는다.
