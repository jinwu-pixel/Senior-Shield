# Domain Contracts M2 설계

## 목적

M1은 위험 정책 모델을 `:domain:risk`로 분리해 순수 JVM 경계가 이 저장소에서 동작함을 증명했다. M2는 앱 내부의 domain port 가운데 Android/Hilt와 무관하고 여러 계층이 공유하는 최소 집합을 `:domain:contracts`로 이동한다. 런타임 동작을 바꾸는 리팩터링이 아니라 컴파일 의존 방향을 명시하는 작업이다.

## 검토한 대안

| 안 | 경계 | 장점 | 리스크 | 판단 |
|---|---|---|---|---|
| A. 선택적 contracts | `Guardian` + repository interface 4종 | 의미 있는 port 경계, UI 모델 누출 방지, 후속 data/feature 분리 seam | app의 domain/model 폴더가 남음 | 채택 |
| B. 잔여 domain 전체 이동 | A + Permission/Policy 모델 | 기계적으로 단순, app domain 폴더 정리 | 화면 표현 모델을 장기 공개 계약으로 고착, Compose canary 확대 | 보류 |
| C. capability별 여러 모듈 | guardian/settings/risk contracts 분리 | 장기 응집도 최대 | 현재 앱 규모에서 Gradle 비용·cycle·T2 범위 과다 | 후속 소비 모듈이 생길 때 재검토 |

선택적 A안은 디렉터리를 완전히 비우는 것보다 경계의 의미를 우선한다. `PermissionStatus/PermissionType`은 Android 권한 화면의 표현 모델이고 `PolicySummary`도 화면용 목록이므로 app에 남긴다.

## 의존성 설계

`RiskRepository`와 `RiskEventSink`의 공개 시그니처가 `RiskEvent`를 노출하므로 contracts는 `:domain:risk`를 `api`로 사용한다. 세 repository가 공개 `Flow`를 노출하므로 `kotlinx-coroutines-core:1.8.1`도 `api`여야 한다. `implementation`으로 숨기면 downstream Kotlin compile classpath가 공개 타입을 보장하지 못한다.

구현체와 Hilt `@Binds`는 `:app`에 남는다. FQCN을 유지하므로 import나 DI 소스 수정은 필요하지 않아야 한다. 이 가정이 깨지면 모듈 경계가 잘못된 것이므로 DI를 따라 옮기지 않고 S3로 판정한다.

## 호환성과 정책

이동은 source root만 바꾸고 package/FQCN 및 공개 API를 보존한다. Guardian JSON 저장 형식, RiskEvent Room 저장, Flow의 cold/hot 특성, suspend 경계는 구현체가 그대로이므로 변하지 않는다.

`SettingsRepository`에는 공개판에서 사용하지 않는 자동 SMS 설정 API가 남아 있다. M2는 이 API를 활성화하거나 새 호출자를 만들지 않고 그대로 이동한다. 제거는 DataStore·구현체·테스트를 함께 다루는 별도 정책 T2로 분리한다.

## 검증 전략

- 현재 동작을 잠그는 계약 테스트는 실제 FQCN, data class default/component/immutability, 구현자 관점 compile fake와 소비자 관점의 명시적 non-null/local-type 대입을 함께 검사한다. 후자는 공변 반환형 때문에 fake만으로 놓칠 수 있는 nullability 확장을 차단한다.
- M1 후속 불변성 테스트는 사전 SHA를 기록하고 각 모델의 대표 `val -> var` mutation으로 실패 능력을 증명한 뒤 동일 SHA와 production diff 0으로 복원한다.
- M2 계약은 모듈 skeleton에서 대상 선언이 없어 RED가 난 뒤 실제 파일 이동으로 GREEN이 되어야 한다.
- Compose는 P0 원본, P1 Guardian config, P2 이동의 세 단계를 고정한다. P1에서 제거 가능 block을 사전 동결하고, UI metrics와 composable 보고서를 exact 비교하며 class report의 분석 범위 변화만 그 block으로 설명한다. artifacts는 ignored SDD workspace와 추적 로그에 보존한다.
- ABI는 `javap -public -s`의 공개 선언·generic 표기·descriptor를 비교하고 Kotlin nullability는 양방향 compile harness로 보완한다. pre 출력에서 exact `Guardian.$stable:I` block만 제거한 투영이 post와 같아야 한다. `javap -v`는 major version과 class-level Compose `StabilityInferred(parameters=1)`만 추출하며, 두 Compose 생성 요소의 제거를 예상 compiler-boundary delta로 기록한다. raw verbose의 path·checksum·constant-pool·bytecode index는 비교하지 않는다.
- 전체 app unit/Hilt kapt/assemble/duplicate/lint와 domain standalone lint를 함께 실행한다.

## 리뷰 반영 판정

초기 계획 독립 검토의 S2 여섯 건을 구현 전에 반영했다. ABI의 `$stable`·`StabilityInferred` 예상 delta와 deterministic projection, repository 소비자 관점 nullability harness, mutation SHA 복원, P0/P1/P2 Compose 동결, canonical lint hash, exact domain test 수를 완료 게이트로 승격했다. `api` 의존은 Gradle `api` configuration 출력으로 별도 확인하고, DI·정확히 열거한 구현체 hash 및 자동 SMS production invocation 0도 기록한다.
