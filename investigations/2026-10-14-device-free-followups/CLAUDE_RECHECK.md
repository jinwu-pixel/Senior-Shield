## 검토 결과

**- 수정 필수**
없음.

**- 수정 권장**
없음 — CLAUDE_REVIEW.md 권장1은 이번 보강으로 해소됨.

보강된 테스트 7("explicit upstream cancellation…")·8("an ordinary error racing cancellation…")는 **동일 구독을 끝까지 유지한 채** `ScriptedDao`가 `attempt==1`에서만 취소/경합을 일으키고, `attempt==2`에서는 실제로 healthy 데이터(`healthy-after-cancel`/`healthy-after-race`)를 반환할 수 있도록 구성했다. 그런데도 재시도 간격(5,000ms)의 2배인 10,000ms를 진행한 뒤 `dao.collections == 1`, `recentEvents.value`가 취소 직전 데이터(`before-cancel`/`before-race`)에서 변하지 않음을 단언한다. 즉 "DB가 다시 정상 응답할 수 있어도 같은 구독에서는 재시도·복구되지 않고 영구 정지한다"는, 권장1이 요구한 바로 그 실행 가능한 증거를 제공한다.

이는 DIRECTIVE.md의 계약("명시 취소와 취소 후 일반 예외 모두 fallback·재시도하지 않는다")과 정확히 일치하며, `RoomRiskEventStore.kt:50`의 `if (e is CancellationException) throw e`가 재시도 없이 즉시 상위로 전파되는 구현과도 부합한다. DB 일반 읽기 오류의 복구(테스트 1·2가 이미 담당)와 취소 경로의 영구 정지(테스트 7·8)가 명확히 분리되어 검증되므로, 같은 구독에서 취소 이후 자동 복구시키라는 계약 변경 요구는 근거가 없다. production 코드 변경 없음, 기존 8개 테스트 구성 유지(7·8번만 attempt 분기 보강) 주장은 파일 내용과 일치한다.

**- 정책/권한 리스크**
없음. 변경 범위가 테스트 파일 1건으로 한정되며 Manifest·권한·DI·서비스 변경 없음.

**- 미확인 사항**
1. 온라인 lint 수치(app68/data14, 누락4=기존 허용 compose/coroutines-test 키, extra=Robolectric 최신 버전 안내 3건)는 `lint-online-diff.txt`·`FOLLOWUP.md` 기록과 일치하나, lint 실행 자체는 이번 검토 범위 밖이라 재실행·재확인하지 않았다. 스크립트 종료 코드가 여전히 1이고 PASS로 보고되지 않은 점은 기록대로다.
2. "같은 구독에서는 자동 복구 없음"이 단위테스트가 아닌 실제 프로세스-수명 singleton·Home 상시구독 환경에서도 동일하게 성립하는지는 실기 검증 범위이며, DIRECTIVE.md에서 이번 작업이 단말 없이 진행됨을 이미 명시했으므로 이 검토에서도 확정하지 않는다.
3. 검증기 self-check 39/40 불일치, CI 캐시 실측은 이번 테스트 보강과 무관한 별도 트랙으로 기존 기록 그대로 남아 있다.
