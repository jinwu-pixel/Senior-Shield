# Domain Contracts M2 implementation log

## Ruling 1 — selective contract boundary

`PermissionType`, `PermissionStatus`, and `PolicySummary` remain in `:app`.
They are screen-facing presentation models rather than shared domain ports.
M2 moves only `Guardian` and the four repository interfaces. The cost, if this
classification is wrong, is a later small follow-up move rather than locking UI
models into the contracts API now.

## Baseline

The isolated branch started from clean main
`7754ebf5bd457e7fabeb6e9357d178df95fd01e9`. With JDK 21 and serial workers:

```text
./gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :app:testDebugUnitTest :app:assembleDebug :app:checkDebugDuplicateClasses
```

Result: `BUILD SUCCESSFUL in 8m 31s`; 67 actionable tasks, 65 executed and
two up-to-date. Direct JUnit XML aggregation produced:

| Target | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| app | 35 | 454 | 0 | 0 | 0 |
| domain:risk | 1 | 6 | 0 | 0 | 0 |

The metadata-access baseline lint intentionally exited non-zero on the known
baseline and generated exactly 72 diagnostics: 5 errors / 67 warnings. The
durable ignored baseline XML raw SHA-256 is
`499DB3BD480687C5AB9C50FD45414FAF94574CF549E2A498944CEACCC038A588`.
The cross-worktree normalized canonical SHA remains
`8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD`.

The original M2 P0 Compose compile completed successfully in 2m 4s. Its
module metrics are `226 total / 225 restartable / 142 skippable / 36 known
unstable arguments / 37 inferred stable classes / 47 inferred unstable
classes / 3 inferred uncertain classes / 87 total classes`. Durable ignored
copies have these SHA-256 values:

| Artifact | SHA-256 |
|---|---|
| `app_debug-module.json` | `08D35BD5D9D7BB371B3399AFF1CC331A6EB329DBE49D00C412560E9683AAF243` |
| `app_debug-composables.csv` | `F8D8CEF8F38F4A66254DE4029A7E431A6AF2F21C0535E387717B2E7A2D455F37` |
| `app_debug-composables.txt` | `C392734170FA2A877005ADD953CABB216DE348BEC71AC5FA31F47F15C51E5B58` |
| `app_debug-classes.txt` | `BE2C9A2B55979C84816563EA33158A12B95B023F014806C533FCC112FE0A285E` |

## Plan review S2 remediation

The independent plan review found six blocking specification gaps before any
implementation: Compose-generated `Guardian.$stable`, repository nullability
coverage, temporary mutation scope, pre/post Compose baseline durability, lint
fingerprint reproducibility, and exact non-zero domain test counts. The design,
directive, and plan now make each item an explicit gate. Implementation remains
paused until a scoped re-review confirms these changes.

## Task 2 — pre-move ABI and Compose P1 / contracts RED

### Direct pre-move ABI capture

The current app debug classes were inspected directly from
`app/build/tmp/kotlin-classes/debug` before any production source was moved.
The full five-class `javap -public -s` output, including generic declarations
and JVM descriptors, is preserved outside `clean` under
`.superpowers/sdd/IMPLEMENTATION_PLAN/abi-p0/javap-public-s.txt` (SHA-256
`3471D4E650B72F06310954C1D4FF105590662E5EDB6863DFCB4B341BF00EF59B`).
The ABI projection includes Guardian's four-String primary constructor,
default-constructor marker overload, getters/components/copy, `MAX_COUNT:I`,
and Compose-generated `$stable:I`; it also includes every method of the four
repository interfaces with parameterized `Flow`/`List`/`Continuation` display
and raw descriptors.

`javap -v` was kept separate. Only major version and the class-level Compose
annotation were extracted to
`.superpowers/sdd/IMPLEMENTATION_PLAN/abi-p0/javap-targeted-verbose.txt`
(SHA-256 `AC40658B8F8AC3F7359A00E9F08AF894B08224F9EF7B739A8AF46095988ED194`):

| Class | Major | class-level `StabilityInferred(parameters=1)` |
|---|---:|---|
| `Guardian` | 61 | present |
| `GuardianRepository` | 61 | absent |
| `RiskRepository` | 61 | absent |
| `RiskEventSink` | 61 | absent |
| `SettingsRepository` | 61 | absent |

This intentionally excludes paths, timestamps, sizes, checksums, constant-pool
indexes, and bytecode indexes from the compatibility projection.

The pre-move implementation/DI hashes are also preserved in durable scratch
(manifest SHA-256
`0EB93970E817A88ED03C6AE3F5D634C29DCF6B44E34094EF3C6406DB82A3C01D`):

| Source | SHA-256 |
|---|---|
| `DataModule.kt` | `1B086CC21CB7C612B9D89691DAAE3429EAAD549B815AA18BA9D13313C2F66661` |
| `RiskRepositoryImpl.kt` | `A3A581055FD92212E09A351DE5FBEC11075005C9DE25FDA93634027AFF9AC5A5` |
| `SettingsRepositoryImpl.kt` | `297833792C188E4A4214090E94469EAEEBFEA1BE3E4E6C120D3E6A3921D47027` |
| `GuardianRepositoryImpl.kt` | `3614743067E39F44FB43C3A7B8A0818B2D6DF9DE2DC65C6B9CA24731A51147DF` |
| `RoomRiskEventStore.kt` | `CA41BD5371E485AA4668EE9A679F0FD7ACE89CCE3048AC258138DF90DFF2490B` |

### P0/P1 Compose freeze

The controller-produced P0 reports were retained and verified. P1 added exactly
`com.example.seniorshield.domain.model.Guardian` to the stability config and was
generated with JDK 21, offline mode, no daemon, no parallel execution, and one
worker. `:app:compileDebugKotlin --rerun-tasks` completed successfully in 2m 34s.
The original P0 metrics remain `226 total / 225 restartable / 142 skippable /
36 known unstable arguments / 37 inferred stable / 47 inferred unstable / 3
inferred uncertain / 87 total classes`.

All four P0/P1 artifacts were explicitly compared as byte arrays and are equal:

| Artifact | P0/P1 SHA-256 | Byte equality |
|---|---|---|
| `app_debug-module.json` | `08D35BD5D9D7BB371B3399AFF1CC331A6EB329DBE49D00C412560E9683AAF243` | true |
| `app_debug-composables.csv` | `F8D8CEF8F38F4A66254DE4029A7E431A6AF2F21C0535E387717B2E7A2D455F37` | true |
| `app_debug-composables.txt` | `C392734170FA2A877005ADD953CABB216DE348BEC71AC5FA31F47F15C51E5B58` | true |
| `app_debug-classes.txt` | `BE2C9A2B55979C84816563EA33158A12B95B023F014806C533FCC112FE0A285E` | true |

For class-block hashing, blocks are extracted whole, CRLF is normalized to LF,
exactly one terminal LF is retained, and UTF-8 bytes without BOM are hashed.
Among the five move targets, the pre-frozen P1 block set is exactly `{Guardian}`;
its 174-byte block SHA-256 is
`4D4A12E5CB9A945D5479A362F96FBC4133AA92E2ACC51ED52EAF8556EE4F66F0`.
The other four interfaces each have zero class report blocks.

The named `GuardianCard` anchor remains `restartable skippable` with a
`stable guardian: Guardian` argument. Its 150-byte block SHA-256 is
`454704B4868C507918831DD63F8386A9F61997FE5A9DF73664DE874F57A704AA`;
the exact CSV row (89 canonical bytes) hashes to
`AE056D154F44CAC887345473E69C61F3A6F27EB783405555A2430FD394BB8988`.
P0/P1 manifests and all eight report artifacts live in the ignored durable SDD
workspace under `compose-p0` and `compose-p1`.

### Contracts skeleton and expected RED

`settings.gradle.kts` now registers `:domain:contracts`. The module uses only
versionless `java-library`, `org.jetbrains.kotlin.jvm`, and `com.android.lint`
plugins, targets Java/Kotlin bytecode 17, exports `:domain:risk` and
`kotlinx-coroutines-core:1.8.1` through `api`, and adds JUnit 4.13.2 for tests
only. The `api` dependency report completed successfully and listed exactly the
risk project and coroutines core dependency.

`ContractCompatibilityTest.kt` contains exactly four JUnit tests. They lock the
Guardian FQCN/default/component order/constant/final backing fields/no setters;
all four repository FQCNs and signatures; exact-signature implementer fakes;
and explicit consumer-side non-null/local-type assignments. In particular,
`clearCurrentRiskEvent()` is called directly in an ordinary test body before any
`runBlocking` block, so making it suspend breaks compilation.

The mandated command
`:domain:contracts:test --rerun-tasks` reached and passed plugin configuration,
compiled `:domain:risk`, and recognized contracts main as `NO-SOURCE`. It then
failed at `:domain:contracts:compileTestKotlin`, as intended, because `Guardian`
and the four repository interfaces have not moved yet. The additional
`overrides nothing` diagnostics are compiler cascades from those unresolved
interface supertypes; there was no plugin, dependency, JUnit, coroutines, or
risk-model resolution failure. No contracts production source was created.
