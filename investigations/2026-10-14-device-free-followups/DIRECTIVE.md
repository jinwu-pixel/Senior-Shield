# 단말 없는 후속 작업 — 이력 조회 복구와 검증 준비

> **협업 규약 헤더 (2026-07-21)**
> - **등급**: 조회 수명·재시도 T2 / 문서 T0 / 로그 문자열·CI 캐시 T1(동작 변경 없음).
> - **검토**: T2 전체 자체검토 및 Claude 독립 검토 대기. T1은 raw 인계.
> - **정지·질의 = S1~S5**: commit·push·단말·Manifest/권한/DI 변경, 검토 지적, 등급 상승, 제품 결정, 완료 게이트 실패.
> - 근거: 사용자 2026-10-14 "우선 순위대로 단말 없이 진행해." 기존 제안 1~4를 수행한다.
> - 작성·수행: Codex. Claude가 작성하거나 승인한 지시문으로 표기하지 않는다.

## 기준선과 범위

- HEAD `6c8dfff`, main, 시작 시 working tree clean. 사용자 요청은 단말 없는 로컬 수정·검증이다. stage/commit/push/단말 조작은 수행하지 않는다.
- 기존 완료 기록: app 612 + risk 7 + contracts 4 = 623. 오늘 실행 결과와 구분한다.
- 신규 기능(재알림·episode·세션 영속화), DB 삭제·자동 손상 복구, 스키마·interface·DI·권한·서비스 변경은 범위 밖이다.

## 1. 이력 읽기 복구 — 5줄 계획

1. 파일: `RoomRiskEventStore.kt`, 기존 `RoomStorageFailureTest.kt`, 신규 `RoomHistoryRecoveryTest.kt`, CI floor 2파일, 이 문서와 IMPL_LOG.
2. 목적: DB 읽기 일시 실패 후 동일 저장소 객체와 동일 소비자에서 목록 복구. 저장된 데이터는 수정하지 않는다.
3. 리스크: Eagerly에서 WhileSubscribed로 조회 수명이 바뀜. 실제 소비자는 History뿐 아니라 Home의 상시 수집도 있으므로 화면 표시 여부와 동일하지 않다. 정책·권한 변경 없음.
4. 검증: 기존 실패 방어 baseline, 신규 실패→복구 RED/GREEN, 반복 실패 간격, 구독 해제/재진입, 다중 소비자, 취소 경합, currentEvent 보존. 전체 직렬 게이트·XML·lint·schema·self-check.
5. 중단: interface/DI/DB 스키마 수정 필요, 예상 밖 기존 계약 실패, 범위 밖 사용자 데이터 조작 필요.

### 계약

- 최초 읽기는 구독이 생기면 수행한다. 구독이 없으면 조회·재시도하지 않는다.
- Room Flow 또는 mapping 실패 시 기존과 같이 빈 목록을 표시한다. 동일 구독을 5,000ms 후 재시도한다. 중첩 쿼리·즉시 반복 없음.
- `retryWhen` 안에서도 `ensureActive()`를 먼저 수행한다. 명시 취소와 취소 후 일반 예외 모두 fallback·재시도하지 않는다.
- 마지막 구독 해제 시 upstream과 retry delay를 취소하되 replay는 보존한다(기존 계측 테스트의 `.value` 계약). 지속 재구독은 새 DAO 수집을 시작한다. `first()`는 캐시를 즉시 반환할 수 있으므로 항상 새 DB 값임을 보장하지 않는다. 현재 Home/History 소비자는 지속 수집이다.
- Home ViewModel의 상시 구독이 살아 있으면 재시도도 유지된다. 따라서 영구 장애에서 5초 간격 반복이 가능하다. 새 service/timer 작업을 만들지 않으며 coroutine cancellation을 따른다.
- currentEvent, insert/delete/count, enum 저장값, Room v1 스키마는 그대로다.
- 기존 실패 테스트는 '구독 없이 Eagerly 시작, 실패 후 완료'에서 '명시 구독, 실패 후 빈 목록'으로 계약을 갱신하되 예외 유출·실제 DAO 수집 단언은 보존한다.

### 대안 판단

- Eagerly 무한 retry: 소비자가 없어도 앱 수명 동안 반복하므로 제외.
- 고정 횟수 retry 후 완료: 마지막 실패 이후 같은 singleton에서 다시 복구되지 않아 제외.
- 사용자 재시도 버튼: repository interface·ViewModel·UI 확장이 필요해 이번 범위에서 제외.

## 2. 문서와 실기 준비 (T0)

- 리팩터링 PLAN의 과거 승인 대기 상태를 역사 기록으로 표시하고 #15 완료 근거를 연결한다.
- 현재 작업 기록과 최신 빌드 대상의 후속 실기 체크리스트를 작성한다. 10-10 기존 결과는 역사 증거로 보존한다.
- 단말 점유·권한은 과거 합의를 재사용하지 않는다. 이번에는 adb/단말 호출 0.

## 3. 앱 사용 감지 진단 문구 (T1)

- `RealAppUsageRiskMonitor.kt`의 Unknown 경고 문자열 1개만 변경한다.
- 'raw 관측 없음' 사실은 유지하고 권한/호환성 원인 단정을 제거한다. `Observed(emptySet)`과 `Unknown`의 분기·로그 레벨·poll 주기는 보존한다.
- 기존 로그 증거 파일은 수정하지 않는다. old/new 문자열 대응과 한 줄 변경을 기록한다.

## 4. CI Robolectric 캐시 (T1)

- 기존 Gradle 캐시와 별도로 Robolectric 런타임 artifact 위치를 확인한 후 해당 디렉터리만 캐시한다.
- 실행 가능한 공식 action을 SHA로 고정한다. PR은 복원만, main 성공 실행만 저장한다.
- 게이트·테스트 집합·버전·권한·병렬도는 변경하지 않는다. cold/warm 원격 실측은 push 승인 전까지 미확인이다.

## 실행 순서와 기록

- [x] 1. baseline → 판별력 있는 테스트 RED → 최소 수정 GREEN → floor 갱신.
- [x] 2. 문서 및 미행사 실기 목록 갱신.
- [x] 3. 로그 문자열 한 줄 정정과 raw diff 인계.
- [x] 4. 캐시 위치/공식 action 확인, YAML 변경·정적 검증.
- [x] 최종 검증 실행·T2 자체검토·결과 기록. Gradle 및 631 tests PASS, lint union FAIL, verifier 39/40. 상세 IMPL_LOG 참조.
- [x] 후속 온라인 대조: 미허용 누락 경고 해소, strict lint 실패는 기존 Robolectric 경고 3건만 남음. FOLLOWUP 참조.
- [x] Claude 독립 검토: 권장1 테스트 보강 후 수정 필수/권장 0. 보강 후 631 tests PASS.
- [ ] 원격 CI 판정과 실기용 빌드 고정. 캐시 저장/후속 hit는 main 병합 이후 별도 확인. 단말 검증 미수행.
