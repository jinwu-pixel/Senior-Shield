# CROSS_CHECK Gate Log

CROSS_CHECK 는 자동 승인 게이트가 아니다. 이 로그는 **의사결정 이력 추적용**이다.

## 원칙

- 같은 `decision_key` 의 이력이 연속으로 이어져야 한다.
- 나중에 "이 의사결정이 왜 바뀌었는가" 를 역추적할 수 있어야 한다.
- `suggested_action` 은 권고값이며 여기에 기록되더라도 자동 진행 근거가 아니다.
- 실제 사람 판단 결과는 `human_decision` 열에 별도로 기록한다.
- 실패한 provider 는 `failed_reviewers` 에 이름만 남기고, objection severity 로 승격하지 않는다.

## 컬럼 정의

| 컬럼 | 의미 |
|---|---|
| `timestamp` | run 완료 UTC ISO |
| `decision_key` | 의사결정 축 식별자 (고정) |
| `packet_version` | 패킷 수정 버전 |
| `run_id` | 실행 단위 id |
| `agreement_state` | STRONG_CONSENSUS / WEAK_CONSENSUS / MATERIAL_CONFLICT / INSUFFICIENT |
| `degraded` | reviewer 실패로 축소 집계됐으면 true |
| `suggested_action` | adopt / revise / reject / needs_human_decision (권고) |
| `user_decision_required` | true/false |
| `failed_reviewers` | 실패 provider 쉼표 목록 또는 `-` |
| `artifact_path` | summary/run_meta 가 저장된 경로 |
| `supersedes_run_id` | 이 run 이 갱신하는 이전 run_id (있으면) |
| `human_decision` | 실제 사람 판단 결과. 미기록이면 `-` |
| `note` | 한 줄 메모 (선택) |

## 기록 방식

- `/ws-crosscheck --auto` 실행 후 runner 가 summary 를 만들면 이 파일에 한 줄 append.
- 사람이 최종 판단했으면 같은 줄의 `human_decision` 컬럼을 수기 업데이트한다.
  (adopt-as-is / adopt-with-changes / reject / deferred / superseded-by:<run_id>)
- 같은 `decision_key` 를 재실행하면 새 run 의 `supersedes_run_id` 에 직전 run_id 를 넣는다.

## 이력

<!-- AUTO_APPEND_START -->
| timestamp | decision_key | packet_version | run_id | agreement_state | degraded | suggested_action | user_decision_required | failed_reviewers | artifact_path | supersedes_run_id | human_decision | note |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| _example_ | SS-EXAMPLE-KEY | 1 | 20260101-000000 | WEAK_CONSENSUS | false | revise | true | - | 09-gates/cross-check-runs/SS-EXAMPLE-KEY/20260101-000000/ | - | - | 예시 행 |
| 2026-04-15T06:00:29.474109+00:00 | SS-TTL-EXPIRY-POLICY | 1 | 20260415-150029 | MATERIAL_CONFLICT | false | revise | True | - | 09-gates/cross-check-runs/SS-TTL-EXPIRY-POLICY/20260415-150029/ | - | - |  |
| 2026-04-15T06:02:40.329224+00:00 | SS-TTL-EXPIRY-POLICY | 1 | 20260415-150240 | WEAK_CONSENSUS | true | revise | True | gemini-1.5-pro | 09-gates/cross-check-runs/SS-TTL-EXPIRY-POLICY/20260415-150240/ | - | - | gemini-1.5-pro(partial); 1명의 full_reviewer + 1명의 advisory 로 축소 집계. suggested_action 은 참고값이며 user_decision_required 가 우선. |
| 2026-04-15T08:00:48.269601+00:00 | SS-SMOKE-PROBE | 1 | 20260415-170039 | INSUFFICIENT | true | needs_human_decision | True | gpt-4o-mini | 09-gates/cross-check-runs/SS-SMOKE-PROBE/20260415-170039/ | - | - | full_reviewer 가 하나도 성공하지 못했습니다. advisory 리뷰만으로는 판단하지 않음. gpt-4o-mini(failed); full_reviewer=0, advisory=1 로 축소 집계. su... |
| 2026-04-15T08:08:50.411580+00:00 | SS-SMOKE-PROBE | 1 | 20260415-170839 | INSUFFICIENT | true | needs_human_decision | True | gpt-4o-mini, gemini-1.5-pro-latest | 09-gates/cross-check-runs/SS-SMOKE-PROBE/20260415-170839/ | - | - | full_reviewer 가 하나도 성공하지 못했습니다. advisory 리뷰만으로는 판단하지 않음. gpt-4o-mini(partial), gemini-1.5-pro-latest(failed); full_re... |
| 2026-04-15T08:10:09.743481+00:00 | SS-SMOKE-PROBE | 1 | 20260415-171001 | STRONG_CONSENSUS | true | adopt | True | gemini-2.5-pro | 09-gates/cross-check-runs/SS-SMOKE-PROBE/20260415-171001/ | - | - | gemini-2.5-pro(failed); full_reviewer=1, advisory=1 로 축소 집계. suggested_action 은 참고값이며 user_decision_required 가 우선. |
| 2026-04-15T08:10:34.819068+00:00 | SS-SMOKE-PROBE | 1 | 20260415-171025 | STRONG_CONSENSUS | false | adopt | False | - | 09-gates/cross-check-runs/SS-SMOKE-PROBE/20260415-171025/ | - | - |  |
| 2026-04-16T01:22:57.393509+00:00 | SS-SMOKE-PROBE | 1 | 20260416-102248 | STRONG_CONSENSUS | false | adopt | False | - | 09-gates/cross-check-runs/SS-SMOKE-PROBE/20260416-102248/ | - | - |  |
| 2026-04-16T01:27:58.409531+00:00 | SS-SMOKE-PROBE | 1 | 20260416-102749 | STRONG_CONSENSUS | false | adopt | False | - | 09-gates/cross-check-runs/SS-SMOKE-PROBE/20260416-102749/ | - | - |  |
<!-- AUTO_APPEND_END -->

<!-- runner 는 AUTO_APPEND_END 마커 바로 앞에 한 줄씩 append 한다. 예시 행은 제거하지 말 것. -->

