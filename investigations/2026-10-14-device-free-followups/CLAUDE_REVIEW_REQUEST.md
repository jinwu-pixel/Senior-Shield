# Claude 독립 검토 요청

사용자가 2026-10-14에 lint 환경 대조 → Claude 독립 검토 → 승인 후 Git/원격 CI → 실기용 빌드 확정 순서를 승인했다. 이번 요청은 **읽기 전용 코드 검토**다. 파일 수정, 명령 실행, Git 쓰기, 외부 연락, 단말 실행은 하지 말고 검토 결과만 응답한다. 다른 에이전트를 실행하지 않는다.

저장소: C:/Users/momen/AndroidStudioProjects/Senior_Shield
기준 HEAD: 6c8dfff86e90f96b8ed0617285a1379480b5b7a7 + 현재 미커밋 변경.

먼저 AGENTS.md와 이 폴더의 DIRECTIVE.md를 읽는다. 제품 원칙의 승인된 예외 및 수동 SMS 정책은 최신 AGENTS.md를 따른다. 역사 문서의 오래된 제약으로 현재 계약을 덮어쓰지 않는다.

검토 입력:
- review-input.diff: tracked 변경의 정확한 patch (신규 테스트는 별도 원문 읽기). 검토 종료 후 원문은 로컬 ignored `build/review-evidence/2026-10-14-device-free-followups/review-input.diff`로 이동해 보존했다.
- data/src/main/java/com/example/seniorshield/data/local/RoomRiskEventStore.kt
- app/src/test/java/com/example/seniorshield/data/RoomHistoryRecoveryTest.kt (신규 8개)
- app/src/test/java/com/example/seniorshield/data/RoomStorageFailureTest.kt 및 StorageFailureTestBase.kt
- 필요한 실제 소비자 HomeViewModel, HistoryViewModel 및 RoomRiskEventStoreStorageTest를 Glob/Grep으로 찾아 읽기.
- .github/workflows/verify.yml, .github/scripts/verify-unit-xml.ps1
- IMPL_LOG.md, RESULT.md, FIELD_CHECKLIST.md 및 lint-diff.txt.

T2 이력 복구를 독립적으로 적대 검토한다: failure→empty→5초 retry, 마지막 subscriber 종료, 재구독, 다중 subscriber, CancellationException, cancel+일반 예외, replay/value 호환성, Home 상시 subscriber, 실제 Room DAO Flow 동작, currentEvent/쓰기 비변경, 테스트 판별력과 누락. 근거 없는 미세 개선을 recommend로 만들지 말되 실제 결함은 구체적 트리거·영향·파일/줄로 제시한다.

T1 로그 문자열·CI 캐시는 raw 변경으로 독립 검토한다. 캐시 키/path/action 입출력, PR restore-only/main success save, 기존 gate/permission 보존, 버전 안내를 단정하지 않는 로그 문구를 확인한다.

직전 로컬 검증: 신규 RED 7/8 → 표적 12/12 GREEN → 전체 clean 631/631, 앱/계측 APK 성공, schema0. 엄격 lint FAIL: 오프라인 app missing21/extra3(Robolectric), data missing2. self-check39/40. 현재 온라인 lint를 부모 Codex가 별도로 실행 중이며, 이 보고서에서는 새 실행을 했다고 주장하지 말 것. 기존 lint baseline을 변경하거나 허용 범위를 넓혀 통과시키지 않는다.

응답 형식(한국어):
- 수정 필수
- 수정 권장
- 정책/권한 리스크
- 미확인 사항
각 발견은 파일:줄·조건·영향·최소 조치를 포함한다. 없으면 없음. 코드 검토 판정과 lint/실기 등 검증 게이트 상태를 분리한다. 마지막에 검토한 파일 범위와 독립 검토 한계를 짧게 명시한다. 새 DIRECTIVE 승인이나 사용자 제품 결정을 대신하지 않는다.
