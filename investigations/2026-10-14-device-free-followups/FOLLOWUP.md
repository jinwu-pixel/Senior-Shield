# 후속 검증과 독립 검토 — 2026-10-14

사용자 요청: lint 환경 대조 → Claude 독립 검토 → 승인 후 commit/PR/원격 CI → 실기용 빌드 확정 순서에 대해 "그렇게 진행해".

## 온라인 lint 대조

가설: 앞선 `--offline` 실행에서 Maven 최신 버전 조회가 제한되어, frozen inventory에 있는 안내 경고들이 누락되었다. 앱 소스·의존성 버전·검증 기준은 변경하지 않고 `--offline` 옵션만 제외해 같은 lint task를 실행한다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain :data:lintDebug :app:lintDebug
```

실행 결과: **BUILD SUCCESSFUL in 6m15s**, 94 tasks (18 executed, 76 up-to-date). lintAnalyze가 실제 다시 실행되었다.

| 항목 | offline | online |
|---|---:|---:|
| app warnings | 51 | 68 |
| data warnings | 12 | 14 |
| app/data errors | 0/0 | 0/0 |
| 기준선 대비 app 누락 | 21건 | 4건 (기존 검증기의 명시 허용 범위) |
| 기준선 대비 data 누락 | 2건 | 0건 |
| app 추가 | Robolectric 최신 버전 안내 3건 | 동일 3건 |

온라인에서 app 17건/data 2건이 복원됐다. 남은 app 누락은 compose plugin 3건과 coroutines-test 1건으로, 검증기가 이미 허용한 정확한 키다. 따라서 **엄격 union의 남은 실패 원인은 Robolectric 3건뿐**이다. 이는 `2026-10-12-storage-failure-ui/collab/archive/20261012-2010-T001-REVIEW.md`의 로컬 환경 기준선과 일치한다. 새 코드 경고는 없다. 실제 스크립트 종료 코드는 여전히 1이며 PASS로 바꾸어 보고하지 않는다.

상세: `lint-online-diff.txt`. 버전·검증기 허용 목록·frozen inventory 변경 없음. 다음 원격 CI 결과와 독립 검토 판정을 분리해서 확인한다.

온라인 보고서로 verifier 자체검사 재실행: **39/40**, unexpected 1은 faithful-copy의 Robolectric 추가 3건뿐. `verifier-online-probes.txt`에 기록했다. 다른 결함 주입 39개는 기대 결과와 일치했다. 단위 테스트 입력은 기존 631건 전체 결과였으며 변경된 테스트 재실행 결과를 대신하지 않는다.

## 독립 검토

`requesting-code-review` 절차로, 로그인된 Claude CLI의 sonnet 모델에 새 독립 검토를 요청했다. 입력은 `CLAUDE_REVIEW_REQUEST.md`와 `review-input.diff`, 신규 테스트 원문 및 필요한 소비자 코드다. 대화 전체 이력은 전달하지 않았다.

- 실행 제한: safe-mode, restricted, strict-mcp-config, tools/allowedTools = Read·Glob·Grep, permission-mode plan, no-session-persistence.
- 파일 수정·명령 실행·Git 쓰기·단말 실행 도구 없음. 결과 원문은 부모 Codex가 추출했다. CLI raw JSON 두 개는 로컬 ignored `build/review-evidence/2026-10-14-device-free-followups/`에 보존하고, 검토 텍스트만 이 폴더에 남긴다.
- 온라인 lint가 병행 중임을 명시했고, 검토자가 테스트를 실행했다고 보고하지 않도록 했다.
- 기존 CROSS_CHECK runner는 stub임을 확인했으므로 검토 근거로 사용하지 않았다.
- 검토 당시 `review-input.diff` 원문도 위 ignored 증거 디렉터리에 보존했다. diff의 빈 context 줄 공백은 형식 의미가 있어 원문을 정리하지 않았으며 버전 관리에는 중복 patch를 넣지 않는다. probe 결과의 줄 끝 공백만 제거했다.

원본 offline 기록(IMPL_LOG/RESULT/lint-diff/verifier-probes)은 당시 증거로 보존한다. 후속 결과는 이 문서에서 구분한다.

### 1차 Claude 결과와 조치

실제 모델 `claude-sonnet-5`, 읽기 전용 31 turns, 474641ms, permission denials 0, subagent 실행 0. 원문 `CLAUDE_REVIEW.md`. 수정 필수 0 / 수정 권장 1 / 정책·권한 리스크 0. T1 로그·캐시는 결함 없음.

권장 1은 "취소 후 DAO가 정상 상태로 돌아왔을 때 같은 구독에서 복구되는지 직접 확인"이다. 계약은 취소 시 fallback/retry 금지이므로 앱 동작 변경은 하지 않았다. 기존 취소 테스트 2개의 fake를 첫 시도에서만 취소하고 후속 시도는 정상 데이터를 반환하도록 보강했다. 같은 구독에서 10초 후 collections==1, 이전 값 유지로 '정상 응답이 준비되어도 자동 재시작하지 않음'을 판별한다. 테스트 수 8개/floor620 유지.

`receiving-code-review` 절차에 따라 계약·코드·테스트를 대조했고, 이 보강과 계약 해석을 Claude에 좁은 범위로 재검토 요청했다. 전체 JVM 테스트도 재실행 중이다. 원격 CI/실기 통과를 선행 주장하지 않는다.

### 2차 Claude 결과

읽기 전용 8 turns / 101383ms. `CLAUDE_RECHECK.md`: **수정 필수 0 / 수정 권장 0 / 정책·권한 리스크 0**, 권장1 해소. 같은 구독에서 취소 후 자동 재시작 없음은 의도된 계약이며, DB 일반 오류의 재시도와 구분했다. 실행 결과와 실제 단말은 별도 검증임을 유지한다.

### 보강 후 실행 검증

`--offline --no-daemon --no-parallel --max-workers=1 :app:testDebugUnitTest :domain:risk:test :domain:contracts:test`: **BUILD SUCCESSFUL 4m41s**, 69 tasks (8 executed/61 up-to-date). app 테스트 실제 재실행, 변경 없는 domain tests는 up-to-date. XML 검증 **620/7/4 = 631**, failures/errors/skipped 0. schema tracked/untracked 0, diff 형식 PASS.

앱 production 소스는 최초 빌드 이후 변하지 않았고 debug APK SHA-256은 `78371E16B257AE13951BF0E687FDE0752A989D8C3596D8C768ED6683C7D64A41`로 동일하다.

## Git/원격 CI 인계

후속 진행 요청에 따라 `codex/history-recovery-followups` 브랜치로 commit/PR를 준비한다. 원격 `jinwu-pixel/Senior-Shield`, base `main`. 자동 병합·단말 실행은 하지 않는다. 로컬 strict lint의 실제 FAIL은 PR 설명에 명시하며 원격 결과로 덮어쓰지 않는다. main 성공 시에만 캐시가 저장되므로 PR CI로는 저장/후속 hit 성능까지 입증할 수 없다.
