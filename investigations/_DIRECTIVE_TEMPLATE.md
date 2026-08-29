# <트랙명> 지시문 — <한 줄 목적>

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol` 참조)**
> - **등급**: T0 문서·보고 / T1 격리 코드 / **T2 고위험** — [이 트랙 = T_]
>   (애매하면 위로. 실행 중 부족 판명 = S3 자동 등급 상승·중단·질의.)
> - **검토**: T0=자기교정 / T1=Claude 독립검토 유일 게이트(Codex raw 핸드오프) /
>   T2=Codex 전체 자체검토 + Claude 적대 워크플로+독립 빌드/lint.
> - **정지·질의 = S1~S5만** (그 밖은 자동 전진, 한 줄 로그 `✓ <단위> [T?] — <수치>`):
>   S1 되돌릴 수 없는 게이트(커밋·push·실기·Manifest/권한/DI 실제 변경) /
>   S2 적대검증 통과 지적 ≥recommend / S3 등급 상승 /
>   S4 제품·정책 결정 필요 / S5 완료 게이트 실패(RED·lint 신규·범위 위반).
>
> 작성: Claude(계획·검토). 수행: Codex. 이 지시문으로 기동 = 아래 5줄 계획 승인.
> 해석 갈리면 중단·질의.

## 0. 현재 상태
- git HEAD / 기준선(테스트·lint·APK) / 전제된 확정 결정.

## 1. 불변 운영 제약
1. commit·push·stage = S1(사용자 명시 승인 전 금지). broad add 금지.
2. Gradle 직렬 `--no-parallel --max-workers=1`, JAVA_HOME=JBR.
3. 금지 범위: Manifest·permission·service·DI 모듈·monitor interface·NavGraph — 변경 시 S1/S3.
4. RED-first(재현 불가 시 invariant-pinning + KDoc).
5. 기존 테스트 삭제·약화 금지(계약 갱신은 전/후 명시).
6. 산출 문서는 이 트랙 폴더에만.

## 2. 승인된 5줄 계획
1. 수정 파일 / 2. 목적 / 3. 리스크(정책·권한 분리 서술) / 4. 테스트(완료 게이트) / 5. 중단 조건.

## 3. 구현 계약
<계약 항목 — 확정 결정을 구속 사항으로>

## 4. 라운드 분할 (선택)
<R1~Rn, 각 RED-first + 로그 append>

## 5. 파일 범위
<소스 N + 테스트. 밖은 S3 중단·질의.>

## 6. 완료 게이트
fresh 직렬 GREEN·0 skipped / lint 신규 0 / 범위 무접촉 / APK 기록 / IMPL_LOG 매핑 표.
