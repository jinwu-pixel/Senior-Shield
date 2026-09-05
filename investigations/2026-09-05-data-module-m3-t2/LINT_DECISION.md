# M3 lint 개별 판정

2026-09-05 fresh `:data:lintDebug :app:lintDebug --continue` 분석 후 독립 검토 PASS.
app task는 기존 오류5 때문에 실패했다. lint 오류0 또는 Gradle 전체 성공으로 표현하지 않는다.

| 보고서 | 정확한 항목 | 횟수 | 근거 |
|---|---|---:|---|
| app 제거 | Room compiler `kapt` 선언의 GradleDependency / KaptUsageInsteadOfKsp / UseTomlInstead | 각1 | 승인된 processor 소유권 이동과 연결된 정확한3개 |
| app 유지 | frozen72에서 위3개를 뺀 canonical 진단 | 69 | 기존 오류5/경고64, 내용과 중복 횟수 동일 |
| app 추가 | 승인된 androidTestImplementation4/kaptAndroidTest1 선언의 UseTomlInstead | 5 | 실제 저장/Hilt 테스트 의존성 선언 형식. 버전·runtime 변경 없음. 정확한5개 문자열만 수용 |
| data | Room runtime/ktx/compiler, DataStore, coroutines의 GradleDependency | 5 | 기존 앱 동일 버전 선언의 진단과 일치 |
| data | Room compiler의 KaptUsageInsteadOfKsp | 1 | 기존 processor를 kapt 그대로 이동하는 승인 범위 |
| data | 승인된 의존성8줄의 UseTomlInstead | 8 | 경로를 data→app으로 바꾸면 기존 baseline의 ID/메시지/선언과 모두 일치 |

최종 app74건(오류5/경고69), data14 warnings, 신규 source/Manifest 진단0.
기존 catalog 진단5종 각3회의 중복 횟수도 유지됐다. Hilt 버전 경고는 실제로 발생하지 않았으며 허용 항목으로 추가하지 않는다.

정확한 dependency 선언·ID·severity·경로·메시지는 `evidence/app-lint-additions.json`과 `evidence/data-lint-records.json`에 각1행씩 보존했다. 전체 app delta는 `evidence/app-lint-delta.json`이다. 독립 검토자는 원본 XML에서 직접 재계산하여 이 기록과 대조했다.

`verify-lint-move.ps1`는 frozen72 hash, 정확한 제거3개, 추가5개/data14개의 고정 hash를 검사한 뒤 두 fresh 보고서의 multiset을 비교한다. 같은 ID의 미래 진단, 중복 증가, 다른 선언·source 경로는 허용하지 않는다. baseline 덮어쓰기·lint suppression·버전 업그레이드는 수행하지 않았다.

카탈로그와 KSP 전환은 선언·processor를 함께 정리하는 별도 작업으로 남긴다. 이 PR에서는 실제 저장 계약을 먼저 검증하고 구현을 그대로 이동하는 경계를 유지한다.
