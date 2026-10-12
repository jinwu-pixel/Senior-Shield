# 저장소 실패 크래시 경로(앱 계층) 계획 초안 — v0.1 (2026-10-12)

> 상태: **계획 단계, 구현 전 승인 필요.** CLAUDE.md 고위험 작업 "DataStore / Repository / ViewModel 계층을 동시에 건드리는 작업"에 해당한다.
> 등급 예정: **T2**.
> 배경: #21·#22·#24·#26·#28로 감지 경로(coordinator·monitor)의 크래시를 막았다. 그러나 **화면(ViewModel)과 저장소 계층에는 같은 종류의 경로가 남아 있다.** `viewModelScope`와 data 계층 scope에도 CoroutineExceptionHandler가 없으므로, 처리되지 않은 예외는 곧 **앱 크래시**다.

## 0. 현행 (조사 결과)

| 위치 | 저장소 | 예외 처리 | 실패 시 |
|---|---|---|---|
| `SettingsRepositoryImpl` 읽기 4종 (`settingsDataStore.data.map`) | DataStore | 없음 | 구독한 곳이 크래시 |
| `SettingsRepositoryImpl` 쓰기 4종 (`edit`) | DataStore | 없음 | 호출한 곳이 크래시 |
| `GuardianRepositoryImpl` 읽기(`observeGuardians`·`getGuardians`), 쓰기(`add`·`remove`) | DataStore | 없음(JSON 파싱만 흡수) | 크래시 |
| `RoomRiskEventStore.recentEvents` (`dao.observeRecent().stateIn(store scope)`) | Room | 없음 | store scope에서 크래시 |
| `RiskRepositoryImpl.countEventsSince` | Room | 없음 | Home 주간 집계에서 크래시 |
| DataStore 손상 처리기(`ReplaceFileCorruptionHandler`) | — | 미설정 | 손상되면 읽을 때마다 `CorruptionException`(IOException) |

이 저장소들을 쓰는 화면:
- **Splash**: 온보딩 여부를 `first()`로 읽는다. 설정 파일이 손상되면 **앱을 시작할 때마다 크래시**한다.
- **Home**: 보호자 목록 구독에 catch가 없다(문자 토글은 #18에서 처리함). 주간 집계도 마찬가지다.
- **History**, **Debug(설정 화면)** 토글 2종, **Guardian**(목록·토글·삭제), **GuardianAdd**(중복 확인·추가), **Warning**(보호자·토글), **Onboarding**(완료 저장)

추가 문제: GuardianAdd는 `addGuardian`이 `false`를 반환하면 "최대 3명까지 등록할 수 있습니다"를 띄운다. 저장 실패를 같은 신호로 처리하면 잘못된 안내가 나간다.

## 1. 5줄 계획 (초안, D1=A 기준)

1. **수정 파일**
   - `:data`: `SettingsRepositoryImpl`, `GuardianRepositoryImpl`, `RoomRiskEventStore`, `RiskRepositoryImpl`, 공용 helper 1개
   - `:app`: `GuardianAddViewModel`, `GuardianViewModel`(쓰기 실패 안내)
   - 테스트(Robolectric로 실제 손상 파일 사용), CI floor
   - interface·DI·Manifest는 바꾸지 않는다.
2. **목적**: 저장소 읽기·쓰기 실패가 앱 크래시로 번지지 않게 한다. 읽기는 안전한 기본값으로, 쓰기는 실패를 알리고 앱은 계속 동작한다.
3. **정책·권한 리스크**
   - 정책·권한 변화는 0이다.
   - 읽기 실패 시 기본값으로 돌아가므로 일부 기능이 꺼진 것처럼 보인다(D2).
   - 사용자 데이터는 지우지 않는다(D3).
4. **테스트**
   - 손상된 DataStore 파일과 Room 실패를 주입해 RED-first로 검증한다.
   - 결함 주입, 전체 게이트, 적대 검토를 거친다.
5. **중단 조건**
   - interface·DI 변경이 필요해질 때
   - 사용자 데이터를 자동 삭제해야 하는 설계가 필요해질 때
   - coordinator·monitor 동작이 바뀌어야 할 때

## 2. 결정 필요

| # | 항목 | 선택지 | Claude 권고 |
|---|---|---|---|
| D1 | **처리 위치** | A. data 계층에서 한 번에 처리: DataStore 공식 권장 패턴(`data.catch { IOException → emptyPreferences() }`)과 Room Flow `catch` / B. 화면마다 catch | **A.** 한 곳에서 모든 소비자(화면·coordinator·monitor)를 보호하고, 새 화면이 생겨도 자동으로 적용된다. 오늘 넣은 감지 경로 catch는 이중 방어로 남는다 |
| D2 | **읽기 실패 시 기본값** | 설정 = 기본값(문자 메뉴 OFF, 테스트 모드 OFF, **온보딩 미완료 → 온보딩 화면이 다시 뜸**), 보호자 = 빈 목록, 이력 = 빈 목록, 주간 건수 = 0 | **수용.** 모두 이미 정한 "실패 시 보수적으로" 원칙과 같다. 온보딩이 다시 뜨는 것은 불편하지만 안전하다 |
| D3 | **손상 파일 자동 초기화** (`ReplaceFileCorruptionHandler`) | 넣음(손상 시 파일을 비워 복구, **보호자 목록 등 사용자 데이터 손실**) / 넣지 않음(읽기는 기본값, 파일은 그대로) | **넣지 않음.** 데이터를 자동으로 지우지 않는다. 필요하면 별도 결정한다 |
| D4 | **쓰기 실패 처리** | 설정 토글: 로그만 남김(토글은 저장된 값 그대로 보임) / 보호자 추가·삭제: "저장하지 못했습니다. 잠시 후 다시 시도해 주세요." 안내 | **권고대로.** 보호자 추가 실패를 "최대 3명" 안내와 분리한다 |

### 2.1 승인 (2026-10-12)

- 사용자: "권고대로 진행해" → D1=A, D2 수용, D3 넣지 않음, D4 권고대로.
- v1.0 보강(Claude 조사): 보호자 추가·삭제가 `getGuardians()`로 읽은 뒤 저장한다. D1=A만 적용하면 일시 읽기 실패 → 빈 목록 + 새 보호자 저장으로 **기존 보호자 전원 삭제** 경로가 생긴다. 추가·삭제를 `edit` 안에서 현재값 기준으로 처리하도록 범위에 넣었다(`DIRECTIVE.md` K4).
- 테스트를 위해 두 저장소에 DataStore를 받는 생성자를 두고, Hilt는 기존처럼 Context를 주입한다(DI 모듈 변경 0, `DIRECTIVE.md` K2).
- 구현 계약: `DIRECTIVE.md` v1.0.

## 3. 범위 밖

- 프로세스 재시작 후 세션 지속성
- 손상 파일 자동 복구(D3에서 "넣음"을 고르는 경우는 별도 트랙)
- Room Migration(스키마 변경 전 필요)
