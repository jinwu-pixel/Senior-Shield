## 코드 검토 결과

**- 수정 필수**
없음.

**- 수정 권장**

1. `data/src/main/java/com/example/seniorshield/data/local/RoomRiskEventStore.kt:49-50` (review-input.diff 동일 라인) — `retryWhen`에서 `if (e is CancellationException) throw e`로 즉시 재전파하는 분기는 `RiskRepositoryImpl.countEventsSince`(기존 코드, 변경 없음)와 동일한 "취소 우선 판별" 패턴을 재사용한 것이라 패턴 자체는 기존 관례와 일치한다. 다만 이 코드는 1회성 suspend 함수가 아니라 `stateIn(WhileSubscribed)`로 공유되는 장수명 Flow에 적용된다는 점이 다르다. `RoomHistoryRecoveryTest.kt`의 두 테스트("explicit upstream cancellation…", "an ordinary error racing cancellation…")는 모두 **구독자를 끝까지 유지한 채** 이 경로를 1회 통과시키고 `dao.collections`/`.value`가 더 늘지 않는 것만 확인한다. 구독자가 0으로 떨어지지 않고 그대로 남아 있는 상태에서, 이 경로를 통과한 뒤에도 DB가 다시 정상 응답하면 StateFlow가 갱신될 수 있는지를 검증하는 테스트는 없다. 최소 조치: 구독을 유지한 채 동일 경로를 통과시킨 후 DB가 회복되는 시나리오를 추가해, 실제로 멈춘 뒤 다시 살아나는지(혹은 구독 재진입 없이는 영구 정지인지)를 직접 확인하는 테스트를 추가 권장. (근거가 실행 확인에 의존하므로 "수정 필수"가 아니라 권장으로 분류했고, 상세는 미확인 사항에도 기록.)

**- 정책/권한 리스크**
없음. AndroidManifest, 권한, foreground/background service, DI, SMS/알림 관련 변경 없음. `PLAN.md` 수정은 2026-10-07 계획 문서 상단에 "역사 기록" 주석 한 줄을 추가한 것뿐이며 승인 대기 상태를 과거 시점 기준으로 명확히 구분하고 있어 현재 계약을 덮어쓰지 않는다.

**- 미확인 사항**

1. 위 CancellationException 재전파 경로에서, 동일 구독자가 유지된 채(구독 0→1 전환 없이) 실제로 복구되는지는 코루틴 런타임을 실행해야만 확정할 수 있어 정적 검토로는 판정하지 않았다.
2. 엄격 lint 오프라인 FAIL(app: 13종/21건 누락·1종/3건 Robolectric 추가, data: 2종/2건 누락)은 보고서상 전부 버전-안내 경고이며, 온라인 조회 환경과의 대조는 부모 Codex가 별도로 진행 중이라 이 검토에서 재실행하지 않았다.
3. CI 캐시의 실제 cold/warm 소요시간·cache-hit 여부는 push 전이라 당연히 미확인이며, 이는 설계 결함이 아니라 아직 실행되지 않았다는 사실의 기록이다.
4. 검증기 self-check 39/40 불일치(현재 lint report의 faithful-copy 비교 실패)의 근본 원인이 오프라인 lint FAIL과 동일 계열인지는 이번 코드 검토 범위 밖이다.

**T1 (로그 문자열 / CI 캐시) 검토**
- `RealAppUsageRiskMonitor.kt:509` — 분기·로그 레벨·태그·poll 주기 불변, "권한 미부여 또는 기기 호환성 문제"라는 단정적 원인 주장을 "원인 미확정"으로 교체. 실제 분기 구조(`sawRawObservation` 전부 false인 경우만 이 로그 도달)와 일치하는 정확한 서술이며 결함 없음.
- `.github/workflows/verify.yml` — 캐시 restore는 조건 없이 항상 실행(PR 포함), save는 `success() && main && non-PR && cache-hit != 'true'` 조건으로 main 성공 실행에만 저장. 경로/키가 restore·save 양쪽에서 동일하고, SHA는 동일 커밋으로 고정. 기존 gate task 목록·`permissions: contents: read`는 변경 없음. 계약과 일치, 결함 없음.
- `.github/scripts/verify-unit-xml.ps1` — floor `612→620`, 주석 추가는 diff상 신규 테스트 수(+8)와 정확히 일치.

**검증 게이트 상태 (코드 검토와 별도)**
직전 로컬 검증 결과(RED→GREEN 전환, 전체 631/631, 엄격 lint FAIL, self-check 39/40)는 보고서 내용과 일치하며 이 검토에서 재실행하지 않았다. lint 기준선/허용 범위 파일(`verify-lint-union.ps1` 등)은 diff에 포함되지 않아 완화되지 않았음을 확인했다.

**검토 범위**: AGENTS.md, DIRECTIVE.md, review-input.diff, RoomRiskEventStore.kt, RoomHistoryRecoveryTest.kt(8), RoomStorageFailureTest.kt, StorageFailureTestBase.kt, RoomRiskEventStoreStorageTest.kt, HomeViewModel.kt, HistoryViewModel.kt, RiskRepositoryImpl.kt, DataModule.kt, RiskEventDao.kt, RealAppUsageRiskMonitor.kt(해당 함수), verify.yml, verify-unit-xml.ps1, IMPL_LOG.md, RESULT.md, FIELD_CHECKLIST.md, lint-diff.txt, PLAN.md diff.

**한계**: 읽기 전용 정적 검토이며 코드를 실행하지 않았다. 공유 Flow의 취소 경로 및 `WhileSubscribed` 내부 재시작 동작은 실행/디버깅으로만 최종 확정 가능하다. 온라인 lint·CI 캐시 실측·실기 결과는 검증하지 않았다(각각 별도 트랙에서 진행 중). 이 응답은 새 DIRECTIVE 승인이나 제품 결정을 대신하지 않는다.
