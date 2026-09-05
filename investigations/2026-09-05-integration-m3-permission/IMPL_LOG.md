# 통합 실행 로그

- 사용자 승인: 게시된 두 후속 PR의 남은 통합/실기기 검증 진행. 기존 GitHub 저장소별 push/Ready PR 승인 지속, main 병합 제외.
- T2 DIRECTIVE 독립 리뷰 조건 반영: target/test 두 package absent 확인, 실패 시 finally cleanup.
- M3 기준 별도 worktree에서 권한 commit 결합. app Gradle 한 충돌의 테스트 의존성을 합집합으로 유지. production 추가 변경0.
- 실제 기기용 denied smoke3 추가. 이름 집합 검증·실제 APK identity 검사를 독립 도구 리뷰에 따라 보강.
- fresh clean build/unit555/lint0E69W+data14W/domain1W PASS. ABI14/Compose4/Manifest내용/provenance196/정책 PASS.
- 설치 전 모델 표기 차이(목록 AT_M150, getprop AT-M150)로 1회 사전검사 중단, 실제 값 교정 후 동일 기기 실행. 설치 전 중단이므로 해당 실패에서 기기 변경 없음.
- 실제 기기17 tests PASS, system grant→revoke 확인 후3 tests PASS, 이번 설치분 두 package 정리 PASS.
- lint probes7 및 instrumentation probes6 PASS. 독립 최종 리뷰 필수0/권장0.
- 게시 대상: integration branch를 기존 M3 base로 Ready PR에 제시한다. 원본 #11/#12와 main은 보존한다. 최종 게시 URL/원격 SHA는 GitHub PR 및 완료 응답으로 확인한다.
