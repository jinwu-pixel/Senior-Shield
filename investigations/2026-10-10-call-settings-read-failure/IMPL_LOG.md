# 통화 감시 설정 읽기 실패 — T001 구현 기록

## 상태와 권한

- 정본: main 작업트리의 `investigations/2026-10-10-call-settings-read-failure/DIRECTIVE.md` v1.0 전체를 읽고 §2 승인된 5줄 계획 및 §3 K1–K4를 적용했다.
- TASK: `20261010-1902-T001-TASK`, T2. 구현 라운드 산출물이며 검증 완료 선언이 아니다.
- 작업 위치: `.worktrees/call-settings`. 시작 시 `git status --porcelain --untracked-files=all` 출력은 비어 있었다.
- 직접 읽은 근거: `observeCallSignals()` 전체, provenance 테스트 하네스 및 testMode 관련 기존 3건, mapper 시그니처/임계값, `.github/S2_REVIEW_CHECKLIST.md` 전체.
- 실행 계획·TDD·완료 검증 스킬을 적용했다. 사용자 지시 우선: 신규 테스트를 먼저 작성했지만 RED/GREEN 실행, Gradle, 컴파일, 검증기, 결함 주입은 Claude 담당으로 이관했다. 별도 스킬 작업공간·git 쓰기·추가 리뷰 에이전트는 만들지 않았다.
- git 쓰기·Gradle·컴파일·테스트 실행·adb: 모두 0회. git은 status/show/diff 읽기만 사용했다.

## 변경 목록

| 파일 | 변경 |
|---|---|
| `app/src/main/java/com/example/seniorshield/monitoring/call/RealCallRiskMonitor.kt` | +19/-1. import 4개, OFFHOOK catch/edge 주석, IDLE try/catch |
| `app/src/test/java/com/example/seniorshield/monitoring/call/RealCallRiskMonitorProvenanceTest.kt` | +142/-0. 기존 25건 유지, 신규 4건으로 29건 |
| `.github/scripts/verify-unit-xml.ps1` | +2/-1. app MinTests 577→581 및 ASCII 이력 1줄 |
| `.github/workflows/verify.yml` | +1/-1. 단계 이름 floor만 581/7/4로 변경 |
| `investigations/2026-10-10-call-settings-read-failure/IMPL_LOG.md` | 본 기록 신규 |
| `investigations/2026-10-10-call-settings-read-failure/collab/to-claude/*-T001-RESULT.md` | 결과 편지 신규, 임시 파일 작성 후 rename |

## K1–K4 매핑

| 계약 | 구현 및 정적 확인 |
|---|---|
| K1 | OFFHOOK `observeTestModeEnabled()` 바로 뒤, `distinctUntilChanged()` 앞에 catch. ensureActive → 지정 Log.w → emit(false) 순서. true 이후 실패 시 운영 타이머 재시작 주석 1줄 |
| K2 | IDLE first()를 try/catch로 감쌈. CancellationException 재던짐 → Exception의 ensureActive → 지정 Log.w → false. 바로 뒤 기존 epoch 재검증 유지 |
| K3(a) | `test mode read failure at offhook keeps the call signal flow alive`: 첫 설정 수집 IOException, UNKNOWN_CALLER, 추가 immediate 방출 없음, IDLE RESET, 다음 통화 UNKNOWN_CALLER |
| K3(b) | `test mode read failure at idle falls back to the production threshold`: 두 번째 수집 IOException, 운영 threshold mapper 1회, RESET, anchor, 다음 통화 UNKNOWN_CALLER |
| K3(c) | `empty test mode flow at idle falls back to the production threshold`: 두 번째 수집 무방출 완료로 first() 실패, (b)와 동일 결과 |
| K3(d) | `cancellation during idle test mode read prevents mapper and further emissions`: 두 번째 수집에서 collectorJob 동기 cancel 직후 suspend 없이 IOException. join 후 mapper 0회·추가 방출 0·기존 anchor/통화 기록 유지 |
| K4 | app floor 577 + 4 = 581. risk 7 / contracts 4 유지, 합계 floor 592. workflow 실행 본문 불변 |

`RealCallRiskMonitor.kt`에는 기존 CancellationException 사용/import가 없었다. 새 import는 `kotlinx.coroutines.CancellationException`을 선택했다(인접 coordinator와 같은 표기). catch/currentCoroutineContext/ensureActive도 기존에 없어 각각 한 번만 추가했다.

## 사례별 판별력 — 실측 전 기대

아래 RED/GREEN은 코드 경로 분석에 따른 기대이며 실행 결과가 아니다. MC는 최종 패치에서 한 가지 결함만 주입한다.

| 테스트 | 수정 전 | MC1: K1 catch 제거 | MC2: K2 try/catch 제거 | MC3: K2 ensureActive 제거 | MC4: K2 Exception→RuntimeException | 근거 |
|---|---|---|---|---|---|---|
| (a) OFFHOOK IOException | RED | RED | GREEN 기대 | GREEN 기대 | GREEN 기대 | 첫 설정 수집 예외가 수집 체인을 종료. immediate가 전달돼도 IDLE RESET/다음 통화가 없어 Channel timeout. IDLE 설정은 정상 false이므로 K2 변형에는 비판별 |
| (b) IDLE IOException | RED | GREEN 기대 | RED | GREEN 기대 | RED | IDLE 예외가 first() 밖으로 전파되면 RESET/다음 통화 수신이 timeout. IOException은 RuntimeException이 아니므로 MC4도 실패. 활성 job이므로 MC3 제거는 이 사례에서 영향 없음 |
| (c) IDLE 빈 Flow | RED | GREEN 기대 | RED | GREEN 기대 | GREEN 기대 | first()의 NoSuchElementException으로 감시 수집 종료. 이는 RuntimeException 하위이므로 MC4로도 처리됨. OFFHOOK 설정은 정상 |
| (d) 취소 직후 IOException | GREEN일 수 있음 | GREEN 기대 | GREEN일 수 있음 | RED | GREEN일 수 있음 | 수정 전/MC2/MC4는 IOException 전파 자체가 mapper 진입을 막을 수 있어 회귀 RED 보장 사례가 아님. MC3은 일반 예외를 false로 바꾼 뒤 epoch가 그대로인 상태에서 동기 mapper를 호출하므로 정확히 0회 단언이 깨짐 |

(a)~(c)는 IOException/NoSuchElementException이 `observeCallSignals()` 수집을 끝내기 때문에 수정 전 코드에서 RED여야 한다. 외부 Default collector의 예외 보고 방식에 따라 최초 실패 위치가 달라질 수 있으나, 뒤따르는 Channel 수신의 5초 timeout도 실패를 드러낸다. (d)는 MC3 검출용이며 수정 전 RED-first 근거로 세지 않는다. 예외가 별도 runner 실패로 보고되는지까지는 Claude 실측 사항이다.

## T2 전체 자체검토

### 취소 의미론

- K1 catch는 설정 Flow 바로 뒤에 위치한다. Flow.catch의 예외 투명성에 따라 하류 예외와 수집자 취소 원인은 fallback으로 변환하지 않고 재던진다. 취소와 겹친 일반 예외가 catch 본문에 도달해도 첫 문장 ensureActive가 취소를 다시 던져 로그/fallback 진행을 막는다.
- K2는 CancellationException을 먼저 명시적으로 재던진다. 일반 Exception 경로도 ensureActive가 false 반환·mapper 호출 전에 있다. first()의 정상 종료에 쓰이는 내부 제어 흐름은 first()가 처리한다.
- (d)는 `cancel()`과 IOException 사이에 suspend/launch가 없다. parent collector 취소가 IDLE 자식 flow에 전달된 상태에서 ensureActive를 만난다. withTimeout + join으로 종료를 기다린 후 verify하므로 mapper 검사가 너무 일찍 실행되는 것을 막는다.
- MC3에서 mapper는 다음 emit의 취소 검사보다 앞에 있는 일반 함수다. 따라서 추가 방출 0만으로는 부족하며 `verify(exactly = 0) { mapper.map(any(), any()) }`가 핵심 판별점이다.

### provenance 계약

- 공통 stale 진입 게이트와 SEED 중립 게이트는 변경하지 않았다. 네 테스트 모두 첫 IDLE seed 1회를 수신한 뒤 실제 통화를 시작한다.
- K2 다음 줄의 producedAtEpoch 재검증, LONG delay 이후 epoch 재검증, 방출에 사용하는 생산 epoch가 원본 그대로다. ensureActive는 job 취소를, 기존 epoch 게이트는 사용자 reset을 각각 차단한다.
- OFFHOOK `recordUnknownCall`·repeated snapshot·immediate 방출은 설정 Flow 바깥의 일회성 경계로 그대로 남는다. fallback이 이 경계를 재실행하지 않는다. 기존 testMode flip 회귀 테스트 본문·단언을 보존했다.
- IDLE anchor/safe marker/발신 선캡처 처리 순서, FINAL/RESET 코드는 그대로다. (d)의 anchor는 설정 조회 전에 이미 기록된 것이므로 취소 뒤에도 1_001_000L 유지가 기존 계약이다.
- 테스트 mock은 AtomicInteger 수집 순번으로 OFFHOOK와 IDLE를 구분한다. (b)~(d)는 첫 emit(false) 후 CompletableDeferred 완료를 기다린 다음 IDLE를 전달한다. (a)는 실패 진입 확인 뒤 기존 Channel 기반 무방출 timeout을 거쳐 IDLE를 전달한다.
- 새 sleep 폴링과 실시간 10초/180초 타이머 발화 단언은 없다. 기존 하네스/Default dispatcher를 그대로 사용한다.

### K1 fallback edge

- 직전 설정이 false이면 distinctUntilChanged가 fallback false를 제거하므로 진행 중 타이머가 재시작되지 않는다.
- 직전 설정이 true이면 false로 전환되어 운영 임계값 180초로 LONG 타이머가 재시작된다. 디버그 testMode ON 상황의 승인된 edge이며 1줄 주석으로 남겼다. 실제 10초/180초 발화는 이 라운드에서 검증하지 않았다.

### S2 / CTA 8항목

`.github/S2_REVIEW_CHECKLIST.md` §6 기준. 모두 소스 diff상 위반 추가 없음으로 판정하며 자동 guardrail 실행 결과는 아니다.

| 항목 | 영향 없음 근거 |
|---|---|
| 1. 일단 닫기 dismiss-only | overlay/cooldown dismiss 및 CTA 핸들러 변경 0. 새 safe-confirm 호출 없음 |
| 2. safe-confirm 전용 흐름 | 진입점/performSafeCtaSideEffects 변경 0. monitor의 safe marker 처리도 원본 유지 |
| 3. REC-REFIRE는 S2 orchestration | coordinator/S2 변경 0. CTA에서 S2 입력 접근을 추가하지 않음 |
| 4. TTL 경계 `>` 유지 | S2 TTL 상수·식·시계 변경 0. 수정 임계값은 기존 LONG 설정 선택뿐 |
| 5. 비통화 dismiss의 call-safe 효과 금지 | 비통화 핸들러 변경 0. anchor는 기존 IDLE 경계에서만 처리 |
| 6. 통화 중 safe-confirm 경로 제한 | markCurrentCallConfirmedSafe/snoozeForCall 호출 추가 0 |
| 7. α/S2 분리 | 상수·상태·함수·테스트 클래스 공용화 0. 해당 구현 파일 불변 |
| 8. UPGRADE_TRIGGERS follow-up | 세 참조의 정의/원소/소유 위치 변경 0 |

C1/C2/C3: 새 CTA 부수효과와 S2 입력면 직접 접근이 없다. 정상 설정 경로의 신호 생성·phase·epoch·scope/escape 규칙은 불변이다. 실패 시 감시가 이어지는 의도된 차이는 있으나 CTA에 의한 scope 또는 escape delta 조작은 추가하지 않았다.

### 정적 검증 결과 및 판정

- Python 읽기 전용 비교: 신규 테스트 블록을 제거한 파일이 HEAD 원본과 정확히 일치(줄바꿈 정규화). 기존 테스트 25건과 하네스 본문·단언 변경 0, 신규 4건.
- 같은 비교로 production 파일은 import 4개·K1 catch/주석·K2 try/catch를 제외하면 HEAD와 정확히 일치한다.
- CI 스크립트는 app floor와 ASCII 이력 1줄, workflow는 단계 이름 한 줄만 다름을 확인했다.
- `git diff --check`: exit 0. Git의 LF→CRLF 작업트리 경고만 출력됐다. 이것은 컴파일/lint 검증이 아니다.
- must-fix: 정적 자체검토에서 발견 0. recommend: 발견 0. info: 모든 실행 게이트와 mutation 판별력은 Claude 실측 대기.
- 정책/권한: Manifest·permission·service·DI·interface·coordinator·mapper·data/domain·Navigation 변경 0. 자동 연락/자동 SMS/외부 전송 추가 0.
- 컴파일 확신도가 특별히 낮은 새 구문은 발견하지 않았다. 기존 coroutine/MockK/CompletableDeferred/AtomicInteger 패턴을 사용하며 flow 타입은 Boolean emit으로 추론된다. 다만 컴파일 미실행이므로 성공을 보증하지 않는다.

## Claude 실행 요청

JAVA_HOME=JBR, 직렬 `--no-daemon --no-parallel --max-workers=1`. 아래는 실행 요청이며 Codex는 실행하지 않았다.

신규 4건 정확한 필터:

```powershell
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest `
  --tests 'com.example.seniorshield.monitoring.call.RealCallRiskMonitorProvenanceTest.test mode read failure at offhook keeps the call signal flow alive' `
  --tests 'com.example.seniorshield.monitoring.call.RealCallRiskMonitorProvenanceTest.test mode read failure at idle falls back to the production threshold' `
  --tests 'com.example.seniorshield.monitoring.call.RealCallRiskMonitorProvenanceTest.empty test mode flow at idle falls back to the production threshold' `
  --tests 'com.example.seniorshield.monitoring.call.RealCallRiskMonitorProvenanceTest.cancellation during idle test mode read prevents mapper and further emissions'
```

수정 hunk 역적용으로 (a)~(c) RED 확인, 복원 후 GREEN 확인. MC1→(a), MC2→(b)(c), MC3→(d), MC4→(b)를 각각 별도 결함으로 실측하고 다음 MC 전에 원복해 달라. (d)는 수정 전 GREEN 가능성을 정상으로 취급한다.

기존 monitor 회귀 전체 필터:

```powershell
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest --tests 'com.example.seniorshield.monitoring.call.RealCallRiskMonitor*Test'
```

이후 DIRECTIVE §6의 fresh 전체 직렬 게이트 및 검증기: app 581 / risk 7 / contracts 4, skipped 0, lint 신규 0, schema drift 0, verifier self-check 및 S2 guardrails. 필터 실행 결과만으로 전체 floor 검증을 하지 않도록 전체 실행 결과로 확인해 달라.


## T001 REVIEW 대응

- 근거: `20261010-2118-T001-REVIEW`, 사용자 승인 R1. 이번 라운드의 테스트 수정은 (a)/(d) 각 3줄, 합계 +6/-0이다. 프로덕션·(b)/(c)·기존 테스트·하네스·단언·floor는 변경하지 않았다.
- R1: 두 테스트에서 첫 UNKNOWN_CALLER 수신 직후 요청된 주석 2줄과 `tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())`를 추가해 실제 coordinator의 세션 개설을 재현했다.
- 원인 확인: `recordUnknownCall()`은 sessionState가 null이면 버퍼를 clear한 뒤 add한다. 기존 size 1 단언은 이런 상태에서 재기록을 구별하지 못했다. UNKNOWN_CALLER를 받은 tracker.update는 세션을 생성하므로, 활성 세션에서 fallback이 일회성 경계를 다시 실행하면 clear 없이 add되어 size 2가 되고 기존 size 1 단언이 RED가 된다. Claude가 MC5로 실측한다.
- MC5 실행 순서 확인: (a)의 collector는 Dispatchers.Default에서 돌아 첫 UNKNOWN 수신 이후 테스트의 tracker.update와 fallback 진행 사이에 별도 동기화 장벽은 없다. 세션 개설 뒤 재기록된 경우의 size 2 판별력은 확보하지만, 최초 fallback 재기록이 항상 세션 개설 뒤 실행된다고 정적으로 보장할 수는 없다. 요청된 3줄 외 본문은 변경하지 않았으며, MC5 실측에서 이 순서와 반복 실행 안정성을 확인해 달라.
- (a) 다음 통화: 활성 세션으로 첫 호출 기록이 유지되고 두 번째 OFFHOOK에서 size 2가 되므로 `[UNKNOWN_CALLER, REPEATED_UNKNOWN_CALLER]`를 방출한다. 마지막 단언은 목록 동등성이 아닌 UNKNOWN_CALLER 포함 검사라 그대로 성립한다.
- (d) anchor: IDLE의 `endedAtElapsedRealtime`는 monotonicClock의 콜백 생산 시각이며 anchor는 이를 그대로 대입한다. tracker.update는 fakeClock/monotonicClock, safe marker, 생산 epoch 또는 monitor anchor를 변경하지 않는다. 1_000_000L에서 1_000L 전진한 anchor `1_001_000L` 단언은 유지된다.
- 잔여 한계: K1 `emit(false)` 삭제 결함은 실시간 대기(180초) 금지 조건 때문에 이 4건으로 검출되지 않는다. 기존 테스트도 LIVE 타이머 발화를 관측하지 않는다. 이번 수정으로 이 한계가 해소됐다고 주장하지 않는다.
- DataStore 이름 정정: 실제 이름은 `senior_shield_settings`이다(`data/.../SettingsDataStore.kt`의 preferencesDataStore 선언 확인). 앞선 지시문의 `settings_store` 표기는 정정 대상이며 이 기록에서는 정본 기존 내용을 소급 편집하지 않았다.

### 범위 밖 info 백로그

| 항목 | 근거 및 후속 검토 |
|---|---|
| API31 registerReceiver 무가드 | RealCallRiskMonitor의 API31 callbackFlow에서 registerReceiver 호출에 예외 가드가 없다. sharingScope는 SupervisorJob + Dispatchers.Default이며 명시 handler가 없다. 등록 실패 시 shareIn 코루틴의 미처리 예외가 crash형 종료로 이어지는 결함을 별도 트랙에서 검토한다. 이번 프로덕션 수정 없음 |
| DebugViewModel smsMenu stateIn catch 부재 | observeSmsMenuEnabled() 바로 뒤 stateIn이며 catch가 없다. 설정 읽기 실패 시 UI 상태 Flow 처리 의미를 별도 트랙에서 검토한다. 이번 ViewModel 수정 없음 |

### 확인 및 Claude 실행 요청

- 리뷰에서 전달된 이전 검증 결과: MC0~MC4 기대 검출, 전체 581/7/4, lint 신규 0, schema 0, self-check 39/40, 독립 적대 검토 PASS(must-fix 0). 이는 Claude 보고이며 이번 R1 수정 후 Codex 실행 결과가 아니다. self-check를 전항목 통과로 바꾸어 기록하지 않는다.
- 정적 확인: 테스트에 요청된 3줄 블록 2개만 추가했음을 역제거 비교로 확인. 테스트 29건 유지. 기존 IMPL_LOG 바이트를 보존하고 이 절만 append했다. 시작 시 존재하던 다른 tracked 변경 파일은 바이트 단위로 보존했다.
- Claude 요청: 아래 두 필터로 R1 정상 경로 GREEN과 MC5 RED를 실측하고, 결함 원복 후 provenance 29건 및 필요한 전체 게이트를 재확인해 달라. MC5에서는 위 실행 순서 한계도 함께 확인한다.

```powershell
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest `
  --tests 'com.example.seniorshield.monitoring.call.RealCallRiskMonitorProvenanceTest.test mode read failure at offhook keeps the call signal flow alive' `
  --tests 'com.example.seniorshield.monitoring.call.RealCallRiskMonitorProvenanceTest.cancellation during idle test mode read prevents mapper and further emissions'
```

- Codex 실행: git 읽기·소스 확인·정적 보존 비교만 수행. git 쓰기·Gradle·컴파일·테스트·adb 0회. 실행 검증은 Claude에게 남긴다.


## T001 REVIEW2 대응

- 근거: `20261010-2124-T001-REVIEW2`. (a) 테스트에 CompletableDeferred 게이트 선언·await·complete 및 주석 1줄만 추가했다(+4/-0). (d)·다른 테스트·프로덕션·floor 변경 0, 테스트 수 29 유지.
- 게이트 추가 이유: 기존 수집 즉시 IOException은 테스트의 세션 개설보다 fallback이 먼저 실행되는 경합을 허용했다. 이제 첫 설정 수집은 started 완료 후 releaseOffhookFailure.await()에서 대기한다. 테스트는 첫 UNKNOWN 수신 → tracker.update → started 확인 → release 완료 순서로 실패를 허용한다.
- 기대 판별력: MC5(K1 catch에서 recordUnknownCall() 재실행)는 활성 세션이 만들어진 뒤에만 실행된다. 따라서 clean-slate clear 없이 기존 1건에 1건을 더해 size 2가 되고, 기존 size 1 단언이 결정적으로 RED가 될 것으로 기대한다. 이전 REVIEW 대응 절의 세션 개설 전 fallback 경합은 이 게이트로 해소했다. MC5 RED 및 정상 GREEN 실측은 Claude 담당이다.
- 정적 확인: 추가 4줄을 역제거하면 REVIEW2 시작 테스트 파일과 바이트 단위로 일치한다. 기존 IMPL_LOG는 바이트 보존 append, 다른 기존 tracked 변경 파일도 바이트 단위 불변이다. IDLE·다음 통화 단언과 (d)의 anchor/취소 단언은 변경하지 않았다.
- git 쓰기·Gradle·컴파일·테스트·adb 실행 0회. K1 emit(false) 삭제 미검출 및 범위 밖 백로그는 앞 절의 한계를 그대로 유지한다.
