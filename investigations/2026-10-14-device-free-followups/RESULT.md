# 단말 없는 후속 작업 결과 — 2026-10-14

이 문서는 최초 구현·offline 검증 시점의 기록이다. 당시 전체 완료 게이트는 S5 lint 불일치로 보류했다. **최신 온라인 대조·Claude 검토·후속 검증은 [FOLLOWUP.md](FOLLOWUP.md)** 참조. 기준 main `6c8dfff`에서 시작했다.

## 1. 변경 파일

- `data/src/main/java/com/example/seniorshield/data/local/RoomRiskEventStore.kt`
- `app/src/test/java/com/example/seniorshield/data/RoomHistoryRecoveryTest.kt` (신규), `RoomStorageFailureTest.kt`
- `app/src/main/java/com/example/seniorshield/monitoring/appusage/RealAppUsageRiskMonitor.kt`
- `.github/scripts/verify-unit-xml.ps1`, `.github/workflows/verify.yml`
- `investigations/2026-10-07-refactor-plan/PLAN.md`
- 이 폴더의 DIRECTIVE, IMPL_LOG, FIELD_CHECKLIST, RESULT 및 lint/self-check 증거 2개.

## 2. 핵심 변경

1. 이력 조회 일시 실패 후 동일 구독에서 5초 간격 재시도. 마지막 구독 종료 시 조회 중단, 마지막 목록 replay 유지. 현재 위험 이벤트와 DB 쓰기·스키마는 유지.
2. R1 리팩터링 완료 상태를 명시하고 최신 빌드용 실기 체크리스트 준비.
3. UsageStats raw 관측 없음 로그의 원인 단정을 제거. 문자열 한 개 외 감지 로직 변경 없음.
4. Robolectric 런타임 캐시 복원 및 성공한 main 실행에서 저장하도록 CI 설정. 테스트 floor app 620 / risk 7 / contracts 4.

## 3. 확인한 것

- 신규 테스트 RED 7/8 → 최종 표적 12/12 GREEN. 전체 단위 테스트 **631/631**, failures/errors/skipped 0.
- clean 전체 직렬 Gradle **BUILD SUCCESSFUL 13m8s**. 앱·계측 테스트 APK 빌드, Hilt, 중복 클래스 검사 통과.
- domain lint PASS, app/data lint errors 0, schema drift 0, diff 형식 검사 PASS.
- 검증기 자체검사 **39/40**. 실패 1개는 현재 lint 결과의 기준선 불일치. 테스트 누락·실패·오류 및 스키마 변이는 정상 검출.
- 정책 표면 추가 없음. 프로젝트 stage/commit/push, adb, 단말·에뮬레이터 실행 없음.

## 4. 미확인/리스크

- **lint union FAIL**: app 버전 안내 경고 21건 누락·Robolectric 3건 추가, data 버전 안내 경고 2건 누락. 허용된 app 누락 4건 외에도 차이가 존재한다. 이번 offline 결과를 기존 Robolectric 3건 차이와 동일하다고 처리하지 않았다. 기준선/검증기는 유지했다. 상세 `lint-diff.txt`.
- 다음 단말 없는 단계는 lint 조회 환경 대조와 Claude 독립 검토다. T2 자체검토의 남은 수정 필수/권장 0은 Claude 독립 승인과 다르다. T1 로그·캐시는 raw 인계.
- CI 캐시 실제 hit·속도와 원격 게이트는 push 전이라 미확인이다.
- Home 구독이 유지되는 동안 영구 DB 장애는 5초마다 조회 실패를 반복한다. 자동 DB 수리 기능은 없다. 실제 기기/Room 동작은 FIELD_CHECKLIST로 남긴다.

명령·판단·APK SHA-256은 IMPL_LOG, 자체검사 원문은 verifier-probes.txt에 기록했다.
