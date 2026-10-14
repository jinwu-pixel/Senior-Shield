# 취소 경계 권장 사항 재검토

사용자가 승인한 Claude 독립 검토 후속이다. Read/Glob/Grep만 사용하고 수정·명령 실행·재위임 없이 한국어 판정만 응답한다. 검토 범위를 아래 1건으로 좁혀라.

먼저 이 폴더 CLAUDE_REVIEW.md의 권장1과 DIRECTIVE.md의 취소 계약을 읽어라. 원래 계약은 명시 CancellationException 및 cancel+일반 예외에는 fallback/retry를 하지 않는 것이다. DB 일반 읽기 오류의 복구와 취소된 작업의 재시작을 구분한다. 취소 이후 같은 구독에서 자동 복구시키는 동작은 이번 요구사항이 아니다.

app/src/test/java/com/example/seniorshield/data/RoomHistoryRecoveryTest.kt의 마지막 두 테스트를 보강했다. 이전에는 모든 조회가 취소/실패하도록 fake를 설정했다. 이제 attempt==1에서만 취소하며, 후속 DAO 수집이 일어나면 healthy-after-cancel/healthy-after-race 데이터가 나온다. 같은 subscriber를 유지한 채 10초(재시도 간격 2배)를 진행하고 collections==1 및 이전 데이터 유지로 자동 재시도/복구 없음이라는 계약을 명시적으로 검증한다. 새 테스트 추가 없이 기존 8개 유지, production 코드 변경 없음. 전체 JVM suite 재실행은 부모가 수행 중이며 결과를 대신 주장하지 말 것.

권장 취지가 '취소 후 backend가 정상 준비돼 있어도 실제로 중단되는지 입증'이라면 위 테스트가 충분한지 판단하라. 반대로 정상 DB 오류가 CancellationException으로 실제 전달되어 복구되어야 할 재현 근거가 있다면 source/조건/영향을 제시하라. 근거 없이 취소를 재시도하도록 계약 변경을 요구하지 말 것.

온라인 lint는 별도 완료됐다: app68/data14/errors0, app 누락4는 기존 명시허용키, 엄격 비교 실패는 Robolectric 최신 버전 경고3건만 남았다. lint-online-diff.txt와 FOLLOWUP.md 참조. 기존 10-12 REVIEW에서도 같은 로컬 환경 차이를 기록했다. 스크립트를 PASS로 수정하거나 전체 gate가 성공했다고 주장하지 말 것.

출력: 수정 필수 / 수정 권장 / 정책·권한 리스크 / 미확인 사항. 기존 권장1의 해소 여부와 남은 구체적 조치만 간결히 명시하라. 읽을 파일은 기존 검토, 계약, 해당 테스트, RoomRiskEventStore.kt, 온라인 lint 결과 정도로 제한한다.
