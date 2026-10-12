# 저장소 실패 크래시 경로(앱 계층) 트랙 지시문 — 설정·보호자·이력 저장소 실패가 앱 크래시로 번지지 않게 한다 (v1.0)

> **협업 규약 헤더 (2026-07-21 확정, memory `feedback_collaboration_protocol`)**
> - **등급**: [이 트랙 = **T2**]. DataStore·Repository·ViewModel 계층을 함께 다룬다(CLAUDE.md 고위험 작업).
> - **검토**: Codex 전체 자체검토 + Claude 적대 검토 + 독립 빌드/lint.
> - **정지·질의 = S1~S5만**: S1 커밋·push·실기 / S2 지적 ≥recommend / S3 등급 상승 / S4 제품 결정 / S5 게이트 실패.
>
> 작성: Claude. 수행: Codex(코드·테스트·IMPL_LOG). Gradle·검증기: Claude.
> 사용자 승인: "권고대로 진행해"(2026-10-12) — `PLAN.md` v0.1의 D1=A, D2 수용, D3 넣지 않음, D4 권고대로. 커밋은 별도 S1이다.

## 0. 현재 상태와 근거

- 기준: main `b7978a3`(PR #29 머지 후). CI 기준선 app 592 / risk 7 / contracts 4.
- worktree `.worktrees/storage-failure`, 브랜치 `codex/storage-failure-fallback`.
- **결함**: DataStore(`senior_shield_settings`, `guardian_store`)와 Room(`risk_events`)의 읽기·쓰기에 예외 처리가 없다. `viewModelScope`와 `RoomRiskEventStore` scope에는 CoroutineExceptionHandler가 없으므로, 처리되지 않은 예외는 **앱 크래시**다.
  - DataStore 손상 처리기(`ReplaceFileCorruptionHandler`)가 없어서, 파일이 손상되면 읽기와 `edit`가 매번 `CorruptionException`(IOException)을 던진다.
  - **Splash**(`SplashViewModel`:24)는 `observeOnboardingCompleted().first()`로 읽는다. 설정 파일이 손상되면 **앱을 시작할 때마다 크래시**한다.
  - Home 보호자 구독(:78), 주간 집계(:209), History(:18), Debug 토글(:38/:42, :76/:80), Guardian(:27/:29, :51), GuardianAdd(:72, :84), Warning(:33/:47), Onboarding(:17)이 같은 경로에 있다.
  - coordinator(:595, :605)와 `RealCallRiskMonitor`(:408, :500)는 #18·#21·#22에서 이미 catch가 있다. 이번 트랙 이후에는 이중 방어로 남는다.
- **v1.0 추가 발견 (Claude 조사, 수정 필수)**: `GuardianRepositoryImpl.addGuardian`/`removeGuardian`은 "`getGuardians()`로 읽기 → 수정 → `edit`로 저장" 순서다.
  - D1=A로 읽기 실패를 빈 목록으로 바꾸면 다음 경로가 생긴다. 읽기가 일시적으로 실패해 빈 목록을 받고, 직후 쓰기가 성공하면 `[]` + 새 보호자가 저장된다. 결과는 **기존 보호자 전원 삭제**다.
  - 따라서 추가·삭제는 `edit { prefs -> }` 안에서 **실제 현재값**을 읽어 수정해야 한다(K4). 동시 추가 경쟁도 함께 닫힌다.

## 1. 불변 운영 제약

1. commit·push·stage = S1. broad add 금지.
2. Gradle 직렬, JBR. Codex 샌드박스는 Gradle 불가.
3. 금지: Manifest·권한·서비스·**DI 모듈(`DataModule`·`DatabaseModule`)**·domain interface(`SettingsRepository`·`GuardianRepository`·`RiskRepository`·`RiskEventSink`)·coordinator·monitor·`build.gradle(.kts)`·의존성 추가·`ReplaceFileCorruptionHandler`·사용자 데이터 삭제 코드.
4. RED-first: 신규 테스트는 수정 전 동작에서 RED여야 한다. Claude가 프로덕션 hunk를 역적용해 실측한다(K2 생성자 seam은 컴파일을 위해 남긴다).
5. 기존 테스트 삭제·약화 금지, 기존 단언 변경 0.
6. 로그에 보호자 이름·전화번호·저장값을 넣지 않는다(repo PUBLIC, 로그 수집 시 노출 위험).
7. Codex 문서는 worktree 안 `investigations/2026-10-12-storage-failure-ui/`에 쓴다.

## 2. 승인된 5줄 계획 (PLAN v0.1 + v1.0 보강)

1. **수정 파일**
   - `:data`: `SettingsRepositoryImpl`, `GuardianRepositoryImpl`, `RoomRiskEventStore`, `RiskRepositoryImpl`, 공용 helper 1개(신규)
   - `:app`: `GuardianAddViewModel`, `GuardianViewModel`
   - 신규 JVM 테스트(`app/src/test/.../data/`, `.../feature/...`), CI floor 2파일
2. **목적**: 저장소 읽기·쓰기 실패가 앱 크래시로 번지지 않게 한다. 읽기는 안전한 기본값으로(D2), 설정 쓰기는 로그만(D4), 보호자 추가·삭제는 실패를 안내하고(D4) 앱은 계속 동작한다. 파일은 지우지 않는다(D3).
3. **리스크**
   - 정책·권한 변화 0.
   - 읽기 실패 시 기본값이 보인다: 문자 메뉴 OFF, 테스트 모드 OFF, 온보딩 재표시, 보호자·이력 빈 목록, 주간 0(D2, 사용자 수용).
   - 손상 파일은 그대로 남으므로, 손상이 지속되면 쓰기도 계속 실패한다(D3의 대가, 사용자 수용).
   - 생성자 seam(K2)은 Hilt가 쓰는 의존성(Context)을 바꾸지 않는다. DI 모듈·바인딩 변경 0.
4. **테스트**: 실제 손상 파일 + 읽기/쓰기 분리 fake DataStore + Room 실패 fake DAO. RED-first, 결함 주입, 전체 직렬 게이트, lint 신규 0, schema 0, self-check.
5. **중단**: 다음 중 하나라도 해당하면 정지한다.
   - DI 모듈·domain interface 변경이 필요할 때
   - 손상 파일 자동 초기화나 사용자 데이터 삭제가 필요해질 때
   - coordinator·monitor 코드 변경이 필요할 때
   - 테스트에 새 의존성(build.gradle)이 필요할 때

## 3. 구현 계약

- **K1 (DataStore 읽기 공용 처리, D1=A)**: `data/local/`에 helper 1개를 둔다.
  - 형태: `Flow<Preferences>` 확장 함수. DataStore 공식 권장 패턴을 따른다.
    ```kotlin
    catch { e ->
        if (e is IOException) { Log.w(tag, "<store> read failed — defaults used", e); emit(emptyPreferences()) }
        else throw e
    }
    ```
  - **IOException만** 흡수한다(`CorruptionException` 포함). 그 밖의 예외는 다시 던진다.
  - `SettingsRepositoryImpl`의 `observe*` 4종과 `GuardianRepositoryImpl`의 `observeGuardians`·`getGuardians`가 모두 이 helper를 거친다.
  - 이름·가시성은 Codex가 정한다. 같은 모듈 안에서만 쓰면 `internal`로 둔다.
- **K2 (생성자 seam)**: `SettingsRepositoryImpl`과 `GuardianRepositoryImpl`을 다음 형태로 바꾼다.
  ```kotlin
  @Singleton
  class SettingsRepositoryImpl(private val dataStore: DataStore<Preferences>) : SettingsRepository {
      @Inject constructor(@ApplicationContext context: Context) : this(context.settingsDataStore)
  ```
  - Hilt 그래프는 Context를 계속 주입한다. DI 모듈 변경은 0이다.
  - 기존 androidTest(`SettingsStorageTest`·`GuardianStorageTest`의 `XxxImpl(context)`)는 그대로 컴파일돼야 한다.
  - 기본 생성자에 "테스트에서 DataStore를 주입하기 위한 것" KDoc 1줄을 단다.
- **K3 (설정 쓰기, D4)**: `set*` 4종의 `edit`를 `try { } catch (e: IOException) { Log.w(...) }`로 감싼다.
  - 다시 던지지 않는다. IOException 외 예외는 잡지 않는다.
  - 결과: Onboarding은 `onDone()`까지 진행하고, Debug 토글은 저장된 값 그대로 보인다.
- **K4 (보호자 쓰기, D4 + v1.0 데이터 보존)**:
  - `addGuardian`: **하나의 `dataStore.edit { prefs -> }` 안에서** `prefs`의 현재 JSON을 파싱한다.
    - `size >= MAX_COUNT`면 쓰지 않고 `false`를 반환한다.
    - 그렇지 않으면 현재 목록 + 새 보호자를 쓰고 `true`를 반환한다.
  - `removeGuardian`: 같은 방식으로, `edit` 안에서 현재값을 읽어 대상만 제거한다.
  - `getGuardians()`(K1 fallback)를 쓰기 경로에서 **쓰지 않는다**.
  - IOException은 repository에서 잡지 않고 호출자에게 전파한다. 구현 클래스 KDoc에 "저장소 실패 시 IOException"을 명시한다(interface 변경 0).
  - JSON 자체가 깨진 경우(`parseGuardians` → 빈 목록)의 기존 동작은 바꾸지 않는다(범위 밖, IMPL_LOG에 잔여로 기록).
- **K5 (Room)**
  - `RoomRiskEventStore.recentEvents`: `.map { }` 뒤, `.stateIn` 앞에 `.catch { e -> currentCoroutineContext().ensureActive(); Log.e(TAG, "...", e); emit(emptyList()) }`를 넣는다. `RealCallRiskMonitor`:409 #22 패턴과 같은 형태다.
  - `RiskRepositoryImpl.countEventsSince`: `try { dao... } catch (e: CancellationException) { throw e } catch (e: Exception) { currentCoroutineContext().ensureActive(); Log.w(...); 0 }`.
  - `currentEvent`, `pushRiskEvent`, `recordRiskEvent`(coordinator가 이미 catch), `clearAll`(디버그 빌드 전용)은 범위 밖이다.
- **K6 (ViewModel, D4)**: 안내 문구는 정확히 `"저장하지 못했습니다. 잠시 후 다시 시도해 주세요."`다.
  - `GuardianAddViewModel.save()`: `addGuardian` 호출을 `catch (e: IOException)`로 감싼다. 실패 시 `_error.value = 위 문구`를 넣고 `_savedEvent`를 보내지 않는다. 정원 초과(`false`) 안내는 그대로 둔다.
  - `GuardianViewModel.removeGuardian`: `catch (e: IOException)` 시 `_message.value = 위 문구`. 성공 시 "보호자가 삭제되었습니다."는 그대로 둔다.
  - 문구는 `feature/guardian` 패키지 안 상수 1개로 공유해도 되고, 각 ViewModel 상수로 둬도 된다.
  - Splash·Onboarding·Home·History·Debug·Warning ViewModel은 **수정하지 않는다**(K1·K3·K5가 보호한다).
- **K7 (신규 테스트, JVM — Robolectric 불필요)**
  - 손상 파일: `PreferenceDataStoreFactory.create(scope = 테스트 소유 scope, produceFile = { TemporaryFolder 아래 *.preferences_pb })`를 쓴다.
    - 파일에는 proto 파싱이 실패하는 바이트를 미리 쓴다.
    - **각 테스트는 먼저 raw `dataStore.data.first()`가 `CorruptionException`을 던지는지 단언한다**(손상이 실제로 재현되는지 확인하는 비공허 전제).
    - scope는 `@After`에서 cancel한다. 파일당 DataStore 인스턴스는 1개다.
  - 분리 fake: `DataStore<Preferences>` 구현에서 `data`는 IOException을 던지고 `updateData`는 보관 중인 현재값으로 정상 동작한다(쓰기 횟수를 센다).
  - 테스트 목록:
    - (a) 설정 손상: `observe*` 4종이 모두 `false`를 내고 예외 없이 완료한다.
    - (b) 설정 손상: `set*` 4종이 예외를 던지지 않는다. 파일 바이트는 변경 0이다(D3).
    - (c) 보호자 손상: `observeGuardians`·`getGuardians`는 빈 목록이다. `addGuardian`·`removeGuardian`은 IOException을 던진다. 파일 바이트는 변경 0이다(D3).
    - (d) 보호자 분리 fake: 현재 2명일 때 `add` 후 3명이며 기존 2명이 보존된다. `remove(id1)` 후 id1만 빠진다. 현재 3명일 때 `add`는 `false`이고 저장값이 바뀌지 않는다.
    - (e) 과잉 흡수 방지: `data`가 IOException이 아닌 예외(예: IllegalStateException)를 던지면 `observe*`와 `observeGuardians`가 그 예외를 그대로 전파한다.
    - (f) Room: `observeRecent()`가 예외를 던지는 fake DAO에서 `recentEvents.value == emptyList()`이고, **처리되지 않은 예외가 0**이다. 검출은 결정적이어야 한다(예: `Thread.setDefaultUncaughtExceptionHandler` 캡처 후 원복, 또는 runTest의 uncaught 수집). `countEventsSince`가 던지는 fake DAO에서는 `RiskRepositoryImpl.countEventsSince(...) == 0`이다.
    - (g) Splash: `SplashViewModel(SettingsRepositoryImpl(손상))` → `SplashUiState.Ready(ONBOARDING)`, 예외 0.
    - (h) Onboarding: `OnboardingViewModel(SettingsRepositoryImpl(손상)).completeOnboarding { }`에서 `onDone`이 호출된다.
    - (i) GuardianAdd: repository `addGuardian`이 IOException을 던지면 `error` = 안내 문구, saved 이벤트 0. 회귀: `false` 반환 시 "최대 3명" 문구 그대로.
    - (j) Guardian 삭제: `removeGuardian`이 IOException을 던지면 `message` = 안내 문구("삭제되었습니다" 아님).
    - (k) 정상 회귀: 건강한 임시 DataStore에서 설정 쓰기→읽기 왕복, 보호자 3명 추가 후 4번째 `false`, 삭제 동작을 확인한다.
  - ViewModel 테스트는 기존 `MainDispatcherRule`/`Dispatchers.setMain` 패턴을 따른다. 고정 sleep은 쓰지 않는다.
- **K8 (floor)**: app MinTests 592 → 실제 신규 testcase 수만큼 올린다. XML 실측 수 기준으로 Claude가 확정한다. Codex는 예상 수와 근거를 적는다.

## 4. 판별력 계획 (Claude 실측)

| 결함 주입 | 기대 검출 |
|---|---|
| ML1 수정 전 동작(K1·K3·K4·K5·K6 프로덕션 hunk 역적용, K2 seam 유지) = RED-first | (a)(b)(c)(f)(g)(h)(i)(j) RED. (d)는 수정 전 `add`가 IOException으로 실패하므로 RED |
| ML2 설정 쪽 K1 helper 제거 | (a)(g) RED |
| ML3 보호자 쪽 K1 helper 제거 | (c) RED |
| ML4 K4를 `getGuardians()` 기반 RMW로 되돌림(K1은 유지) | (d) RED (기존 2명 소실) |
| ML5 helper가 모든 예외를 흡수 | (e) RED |
| ML6 K3 catch 제거 | (b)(h) RED |
| ML7 K5 `recentEvents` catch 제거 / `countEventsSince` catch 제거 | (f) RED 각각 |
| ML8 K6 catch 제거 / 실패 문구를 정원 초과 문구로 | (i)(j) RED / (i) RED |

### 4.1 실측 (2026-10-12, 신규 20건, 6클래스 필터)

| 결함 주입 | RED | 대상 |
|---|---|---|
| ML0 수정 상태 | 0/20 | 20/20 GREEN, skip 0 |
| ML1 수정 전 동작(K2 seam 유지) = RED-first | **12** | 설정 손상 읽기·쓰기, 보호자 손상, 분리 fake 3(추가 보존·삭제 보존·정원), Room 이력·집계, Splash, Onboarding, 보호자 추가·삭제 실패 안내 |
| ML2 설정 K1 제거 | 2 | 설정 손상 읽기, Splash |
| ML3 보호자 K1 제거 | 1 | 보호자 손상 |
| ML4 `getGuardians()` 기반 RMW 복원(K1 유지) | 3 | 추가 보존·삭제 보존·정원 |
| ML5 helper 전 예외 흡수 | 2 | 설정·보호자 비-IO 전파 |
| ML6 K3 catch 제거 | 2 | 설정 손상 쓰기, Onboarding |
| ML7a `recentEvents` catch 제거 | 1 | Room 이력 |
| ML7b `countEventsSince` catch 제거 | 1 | Room 집계 |
| ML8a ViewModel catch 제거 | 2 | 추가·삭제 실패 안내 |
| ML8b 실패 문구를 정원 문구로 | 1 | 추가 실패 안내 |

- 대조군 8건(설정 정상 왕복, 쓰기 비-IO 전파, 보호자 정상·비-IO 전파, 집계 취소 전파·경합, 정원 안내 회귀)은 ML1에서도 GREEN이다. 의도된 회귀 대조군이다.
- 원본 해시 복원 일치. 컴파일 실패 0.

## 5. 파일 범위

`:data` 소스 4 + 신규 helper 1, `:app` ViewModel 2, 신규 테스트(파일 수는 Codex 재량, 하네스 공유 허용), CI 2. 이 밖은 S3 정지.

## 6. 완료 게이트

- fresh 직렬 GREEN·0 skipped·app = 새 floor
- `:app:kaptDebugKotlin`(Hilt 그래프)·`:app:assembleDebugAndroidTest`(기존 androidTest 생성자 호환) 통과
- lint 신규 0, schema 0, self-check
- 범위 무접촉
- IMPL_LOG 매핑 표

## 7. 범위 밖 (보고만)

- 손상 파일 자동 복구(D3 "넣음" 선택 시 별도 트랙)
- `parseGuardians`가 JSON 전체 파싱 실패 시 빈 목록을 돌려주고, 이후 추가가 그 위에 덮어쓰는 기존 경로
- 일시 IOException 후 `observe*` Flow는 기본값을 낸 뒤 완료되므로, 다시 구독할 때까지 복구된 값이 반영되지 않음(공식 패턴의 성질)
- 디버그 초기화(`clearAll`) 실패, 이력 기록 실패(coordinator가 이미 처리)
- Room Migration
- (적대 검토 info) Room 이력 읽기가 한 번 실패하면 프로세스 수명 동안 빈 목록으로 고정된다. `recentEvents`가 싱글톤 Eagerly StateFlow라 재구독으로 복구되지 않는다. foreground service 때문에 수명이 길 수 있다. 수정 전에는 같은 상황이 앱 크래시였다.
- (적대 검토 info) GuardianAdd 중복 확인은 fallback 읽기를 쓴다. 일시 읽기 실패 직후 쓰기가 성공하면 같은 번호가 중복 저장될 수 있다. 정원 제한은 `edit` 안에서 유지된다.

## 8. 완료 (2026-10-12)

- 판정 PASS(`collab/archive/20261012-2010-T001-REVIEW.md`). 전체 게이트 612/7/4, lint 신규 0, schema 0, self-check 39/40.
- 커밋 `a7a9e30`(fix) · `db6e56f`(ci floor 612) → PR #30, CI GREEN, 머지 `1a2de54`(2026-10-12).
