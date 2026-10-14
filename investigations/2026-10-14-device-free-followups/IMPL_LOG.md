# 단말 없는 후속 작업 기록 — 2026-10-14

## 범위와 권한

- 사용자 요청: 제안한 우선순위 1~4를 단말 없이 진행.
- 시작 HEAD `6c8dfff`, main clean. 현재 checkout에서 작업한다. 프로젝트 Git stage/commit/push, adb, 단말 조작 없음. 검증기 자체검사는 임시 fixture 저장소에서만 git init/add/commit을 사용한다.
- Gradle wrapper는 샌드박스의 사용자 캐시 lock 접근 제한으로 처음 실패했다. 명시적 실행 권한 요청이 승인된 뒤 `--offline --no-daemon --no-parallel --max-workers=1`, Android Studio JBR로 실행한다. 네트워크 다운로드 없이 로컬 검증한다.
- 외부 조회는 캐시 action과 Robolectric 공식 소스 확인만 수행했다.

## 1. 이력 복구 — 구현 및 표적 검증 완료

### 원인

`RoomRiskEventStore.recentEvents`의 `.catch { emit(emptyList()) }`가 upstream을 끝내고 singleton Eagerly StateFlow에 빈 목록을 남긴다. 소비자의 재구독도 이 완료된 공유 작업을 다시 시작하지 않는다.

### baseline / RED

- 기존 `RoomStorageFailureTest`: 4 tests, failures/errors/skipped 0. BUILD SUCCESSFUL 3m43s. XML timestamp `2026-10-14T05:59:55`.
- 신규 `RoomHistoryRecoveryTest`: 8 tests 중 7 RED, 1 GREEN. BUILD FAILED 1m20s, 컴파일 오류 없음.
- 판별: 최초/중간 실패 후 복구 목록 없음(2), 반복 재시도 없음(1), 무구독 조회 발생(1), 구독 종료 후 upstream 유지(2), 활성 context의 명시 취소 예외를 기존 catch가 빈 목록으로 바꿈(1). 취소된 context+일반 예외 경합은 기존 ensureActive로 이미 GREEN.
- 기존 완료 게이트 실패가 아닌 의도된 RED-first이다.

### 호환성 판단

- Home init의 별도 이력 수집은 ViewModel 수명 동안 유지된다. History는 UI 구독 종료 후 5초 동안 자체 stateIn 구독을 유지한다. 따라서 저장소 구독과 화면 표시 여부는 같지 않다.
- `RoomRiskEventStoreStorageTest`의 마지막 `.value` 단언은 구독 종료 후에도 마지막 목록을 요구한다. 최초 초안의 replay 즉시 초기화는 불필요한 계약 변경이므로 제거한다. 재구독 시에는 DB를 새로 읽되, 직전 성공 목록 캐시를 유지한다. 이 판단을 신규 테스트와 DIRECTIVE에 반영한다.
- 명시 `CancellationException`은 ensureActive만으로 부족하다. 별도 재전파가 계약이며 신규 테스트로 확인한다.

### 중간 GREEN 시도와 검토

- 첫 수정(취소 guard 적용 전): 기존 4 + 신규 8 = 12건 중 11 GREEN / 명시 취소 1 RED, BUILD FAILED 2m47s. 명시 취소를 retryWhen이 일반 실패로 처리하는 경계를 실제로 확인했다.
- 이후 `CancellationException` 재전파를 추가하고 replayExpiration=0을 제거했다. 구독 취소 시 마지막 목록은 유지하고 새 지속 구독은 DAO를 재수집한다.
- 최종 표적 실행: 신규 8 + 기존 4 = **12/12 GREEN**, failures/errors/skipped 0, BUILD SUCCESSFUL 2m12s. 두 XML timestamp `2026-10-14T06:07:45`.
- 별도 읽기 전용 T2 검토: 최초 2개 지적(명시 취소·replay 계약)을 해소한 후 수정 필수 0 / 권장 0 / 정책·권한 리스크 0. Claude 독립 검토를 대체하지 않는다.

### T2 자체검토

| 항목 | 확인 |
|---|---|
| 복구와 재시도 | 동일 Flow 재수집. error→empty→delay→retry 순서. 단일 sharing coroutine으로 중복 쿼리 없음 |
| 구독 수명 | 마지막 subscriber 종료 시 delay/DAO 수집 취소. 무구독 상태에는 쿼리 없음. Home은 VM 수명 동안 retry 유지 가능 |
| 취소 | 명시 취소를 먼저 재전파, 일반 예외+cancel은 ensureActive로 차단. delay도 cancellable |
| 공유 상태 | 기존 scope·stateIn 사용. 새 전역 mutable state·mutex·DI 추가 없음 |
| 호환성 | 최근 목록 StateFlow 타입 및 replay 유지. History/Home 정상 수집, Android 계측 first/value 사용처 검토 |
| 데이터 | query만 재시도. insert/delete/count, currentEvent, enum 직렬화·Room schema 변경 없음 |
| 정책 | 신규 권한·서비스·알림·외부 연락·전송 없음. 재시도는 기존 DAO 읽기 경로에 국한 |
| 한계 | 실제 Room 장애 복구는 합성 DAO 기반 검증. 영구 손상은 고치지 않으며 Home 살아 있는 동안 5초마다 실패 로그 가능 |

### 정적 검사

- `git diff --check` PASS.
- `.github` 스크립트 ASCII 유지, 신규 @Test 8개 → app floor 612→620, 총 기대 631.
- YAML 파싱 PASS. baseline 검증 단계와 권한은 floor 표시 변경 외 동일. 캐시 2단계만 추가.
- Manifest, DB 선언, DataModule, DatabaseModule HEAD 대비 내용 동일.

## 2. 문서 (T0)

- R1 PLAN 상단에 #15 완료와 과거 승인 대기 문구의 역사적 성격을 명시했다.
- FIELD_CHECKLIST에 최신 main 회귀, API 26~30, Android 16 원격 앱 E2E, production 안전 확인, 구독 수명, 아이콘 등을 기록했다.
- 과거 실기 RESULTS는 수정하지 않았다. 단말 PASS를 새로 주장하지 않는다.

## 3. 로그 문자열 (T1 raw)

- old: `모든 UsageStats 조회에서 raw 관측 없음 — 권한 미부여 또는 기기 호환성 문제: Unknown`
- new: `모든 UsageStats 조회에서 raw 관측 없음 — 최근 사용 기록 부재·조회 실패 등 원인 미확정: Unknown`
- 한 문자열 치환을 역변환하면 HEAD와 동일함을 바이트 정규화 비교로 확인했다. 분기·로그 레벨·태그·poll·세대·gate 코드는 변경 없음.
- 과거 raw 실기 로그는 그대로다. 새/구 로그 대응은 위 두 문자열을 사용한다. Claude 독립 검토 대기.

## 4. CI 캐시 (T1 raw)

- 공식 [Robolectric 4.16.1 resolver](https://github.com/robolectric/robolectric/blob/robolectric-4.16.1/plugins/maven-dependency-resolver/src/main/java/org/robolectric/internal/dependency/MavenDependencyResolver.java)와 로컬 `.m2/repository/org/robolectric/android-all-instrumented` artifact로 별도 runtime 경로를 확인했다.
- 공식 [actions/cache v6.1.0](https://github.com/actions/cache/releases/tag/v6.1.0) commit `55cc8345863c7cc4c66a329aec7e433d2d1c52a9`에 restore/save를 고정. 해당 commit의 action.yml에서 Node 24, 입력·출력 이름 확인.
- 복원은 main/PR, 저장은 전체 게이트 성공 + main + non-PR + exact miss 조건이다. 경로는 runtime artifact 디렉터리만 포함한다.
- key는 OS/arch + 버전 카탈로그/앱 build/테스트 소스·Robolectric 설정 hash. SDK annotation 변경도 새 key로 저장한다. prefix 복원으로 이전 runtime을 재사용한다. 테스트 변경마다 새 cache가 생길 수 있는 용량 tradeoff는 있다.
- PyYAML 파싱 및 HEAD 단계 비교: 캐시 2단계 외 기존 단계 동일, permissions 동일. floor 변경 전 수행한 비교이며 최종에는 floor 이름 변경도 명시적으로 대조한다.
- push/원격 workflow 실행은 하지 않았다. cold/warm 소요시간·실제 cache hit는 미확인. Claude 독립 검토 대기.

## 최종 검증

clean부터 전체 직렬 Gradle 실행: **BUILD SUCCESSFUL in 13m 8s**, 173 tasks (167 executed, 6 up-to-date). 명령:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses :app:assembleDebugAndroidTest :data:lintDebug :app:lintDebug
```

| 검사 | 이번 실행 결과 |
|---|---|
| 전체 단위 테스트 XML | PASS app 620 / risk 7 / contracts 4 = **631**, 53 suites, failures/errors/skipped 0 |
| 컴파일·Hilt·중복 클래스·앱 APK | PASS |
| 계측 테스트 APK 빌드 | PASS, 단말에서 계측 테스트를 실행한 결과는 아님 |
| domain lint | PASS risk 0 / contracts 0, errors 0 |
| app/data lint task | PASS, app 51 warnings / data 12 warnings, errors 0 |
| 엄격한 lint union | **FAIL**. app 기준 69 대비 현재 51: 13종/21건 누락, Robolectric 1종/3건 추가. data 기준 14 대비 현재 12: 2종/2건 누락. 모든 차이는 의존성 최신 버전 안내이며 상세는 `lint-diff.txt` |
| Room schema drift | PASS tracked/untracked 0 (`check-schema-drift.sh`) |
| 검증기 자체검사 | **39/40**, unexpected 1 = 현재 lint report의 faithful-copy 비교 실패. 나머지 변이·schema 검사는 기대대로 동작, app floor 변이는 608 < 620으로 검출 |
| diff 형식 | `git diff --check` PASS |

lint 차이 해석: 10-12 REVIEW에도 Robolectric 추가 3건은 기록되어 있다. 그러나 **이번 결과를 기존 차이와 동일하다고 볼 수 없다**. `--offline` 실행에서 여러 버전 안내 경고가 추가로 누락되었다. 조회 환경 영향과 일치하지만 원인을 별도 온라인 재실행으로 입증하지는 않았다. 검증기는 app의 compose plugin 3건·coroutines-test 1건 누락만 허용하며, 나머지 누락은 실패 대상이다. 검증기·frozen baseline은 수정하지 않았다. S5에 따라 완료/병합 통과 판정은 보류하고 진단 결과를 인계한다.

### 빌드 산출물

기준 HEAD `6c8dfff` + 이 작업의 미커밋 diff. 단말 설치 없음.

| APK | bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 20915839 | `78371E16B257AE13951BF0E687FDE0752A989D8C3596D8C768ED6683C7D64A41` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 544756 | `737B3A6BB1E5015D27CFE6C57305D77A561AEF8809A5BAAE12BD01A71AD456CB` |

### 남은 게이트

- 검증기 자체검사 전체 결과는 `verifier-probes.txt`. 절대 경로가 임시 폴더 하위임을 확인한 고유 scratch에서 실행했다. 임시 fixture git 외 프로젝트 Git 쓰기는 없다.
- lint 기준선 불일치 처리, Claude 독립 검토(T2 전체/T1 raw), commit/push 및 원격 CI 캐시 cold/warm 검증은 미완료다.
- 실제 Room/단말 회귀는 FIELD_CHECKLIST에 남긴다. 단위 테스트의 합성 장애 복구를 실제 손상 DB 복구로 해석하지 않는다.
