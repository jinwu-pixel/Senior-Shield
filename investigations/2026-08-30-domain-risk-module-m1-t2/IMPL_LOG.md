# M1 T2 implementation log

## Task 1 — Compose observability baseline and plugin preflight

### Conditional compiler reporting

- `app/build.gradle.kts` reads `composeCompilerReportsDir` only when the Gradle
  property is present.
- Only `compileDebugKotlin` receives Compose compiler `reportsDestination` and
  `metricsDestination`; both use the same normalized absolute path.
- With no property, report/metrics arguments are absent. The separately added
  stability configuration remains part of every Compose build.

### Baseline command and artifacts

Executed with JDK 21 (`C:\Program Files\Java\jdk-21`):

```text
./gradlew.bat --no-daemon --no-parallel --max-workers=1 :app:compileDebugKotlin --rerun-tasks -PcomposeCompilerReportsDir=C:/Users/momen/AndroidStudioProjects/Senior_Shield/.worktrees/domain-risk-module-m1/app/build/compose-baseline
```

Result: `BUILD SUCCESSFUL` (18 actionable tasks executed). Generated under
`app/build/compose-baseline`:

- `app_debug-module.json`
- `app_debug-composables.csv`
- `app_debug-composables.txt`
- `app_debug-classes.txt`

### Baseline metrics

| Metric | Value |
|---|---:|
| total composables | 226 |
| restartable composables | 225 |
| skippable composables | 142 |
| known unstable arguments | 36 |
| inferred unstable classes | 49 |
| total classes | 89 |

### Offline plugin-cache preflight

- Kotlin Gradle plugin `org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24`:
  cached (3 files).
- Android Gradle Plugin `com.android.tools.build:gradle:8.5.2`: cached (3
  files).
- AGP 8.5.2 declares Lint `31.5.2`; its
  `com.android.tools.lint:lint-gradle:31.5.2` cache is present (2 files).

The Gradle 8.7 wrapper distribution itself was not initially available inside
the sandbox, so the first required-command attempt stopped before Gradle
configuration. After the wrapper was made available, the required command
completed successfully; no plugin cache download was required.

### Property-absent verification

An offline, property-absent rerun of `:app:compileDebugKotlin` also completed
successfully (18 actionable tasks executed). The four baseline-report artifact
timestamps above were unchanged, confirming that this normal build did not
write Compose reports or metrics.

### Notes

- The successful baseline compile emitted two existing deprecation warnings for
  `UsageEvents.Event.MOVE_TO_FOREGROUND` in
  `RealAppUsageRiskMonitor.kt`; this task does not modify that source.

## Task 2 — first RED attempt stopped at S5

The first Task 2 attempt added the planned versioned root plugin declarations,
module skeleton, and compatibility test, then ran the required offline RED
command. Gradle stopped during plugin resolution before Kotlin test compilation:

```text
Plugin [id: 'com.android.lint', version: '8.5.2', apply: false] was not found
Could not resolve plugin artifact
'com.android.lint:com.android.lint.gradle.plugin:8.5.2'
```

Independent cache inspection also confirmed that both versioned marker
artifacts are absent:

- `com.android.lint:com.android.lint.gradle.plugin:8.5.2`
- `org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:1.9.24`

The implementation artifacts themselves are cached. AGP
`com.android.tools.build:gradle:8.5.2` contains the exact
`META-INF/gradle-plugins/com.android.lint.properties` descriptor. The Gradle
8.2+ variant `kotlin-gradle-plugin-1.9.24-gradle82.jar` contains the canonical
`META-INF/gradle-plugins/org.jetbrains.kotlin.jvm.properties` descriptor. This
is distinct from the absent versioned marker artifacts. The actual versionless
application is still accepted only if the next offline RED passes plugin
configuration and fails solely on the six absent model references.

The failed attempt changed no retained source or Gradle files, created no
commit, and restored HEAD `de1fbfa` to a clean state. Full failure and restore
evidence remains in the ignored SDD `task-2-report.md`.

## Independent-review remediation — Compose stability canary

`compose-stability.conf` now contains only the exact FQCNs of the four immutable
enums `AlertState`, `RiskLevel`, `RiskSignal`, and `SignalCategory`. It excludes
`RiskEvent` and `RiskScore`, whose collection properties are intentionally not
asserted stable. `app/build.gradle.kts` supplies this configuration to every
Compose compilation.

Executed with JDK 21, offline dependency resolution, and serial Gradle workers:

```text
./gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 :app:compileDebugKotlin --rerun-tasks -PcomposeCompilerReportsDir=C:/Users/momen/AndroidStudioProjects/Senior_Shield/.worktrees/domain-risk-module-m1/app/build/compose-stability-pre-move
```

Result: `BUILD SUCCESSFUL` (18 actionable tasks executed). The configured
pre-move canary exactly matches the original debug baseline:

| Metric | Original baseline | Stability-configured pre-move |
|---|---:|---:|
| total composables | 226 | 226 |
| restartable composables | 225 | 225 |
| skippable composables | 142 | 142 |
| known unstable arguments | 36 | 36 |
| inferred unstable classes | 49 | 49 |
| total classes | 89 | 89 |

All four paired report artifacts have identical full SHA-256 values:

| Artifact | Baseline SHA-256 | Stability-configured pre-move SHA-256 |
|---|---|---|
| `app_debug-module.json` | `1BA7F1EE417E37D64ADDC438A188C47EB41897621DC741A52E9B74A7D69F22F2` | `1BA7F1EE417E37D64ADDC438A188C47EB41897621DC741A52E9B74A7D69F22F2` |
| `app_debug-composables.csv` | `F8D8CEF8F38F4A66254DE4029A7E431A6AF2F21C0535E387717B2E7A2D455F37` | `F8D8CEF8F38F4A66254DE4029A7E431A6AF2F21C0535E387717B2E7A2D455F37` |
| `app_debug-composables.txt` | `C392734170FA2A877005ADD953CABB216DE348BEC71AC5FA31F47F15C51E5B58` | `C392734170FA2A877005ADD953CABB216DE348BEC71AC5FA31F47F15C51E5B58` |
| `app_debug-classes.txt` | `9183AAE277F0309CC7F8241265558547605F5563554953D22F145777DBA56CD9` | `9183AAE277F0309CC7F8241265558547605F5563554953D22F145777DBA56CD9` |

These debug numbers are a same-variant relative regression canary, not an
absolute assessment of release performance.

## Task 2 — versionless RED compatibility checkpoint

The approved retry retained root `build.gradle.kts` unchanged, added only
`:domain:risk` to settings, and used versionless `java-library`, Kotlin JVM,
and Android Lint module plugins. Its module production dependency set is empty;
JUnit 4.13.2 is test-only.

With JDK 21, offline dependency resolution, and serial Gradle workers:

```text
./gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 :domain:risk:test
```

Gradle configured the versionless plugins and resolved JUnit. The expected RED
then occurred at `:domain:risk:compileTestKotlin`: only the six not-yet-moved
production models were unresolved — `AlertState`, `RiskEvent`, `RiskLevel`,
`RiskScore`, `RiskSignal`, and `SignalCategory`. Any lambda or assertion
overload diagnostics followed from those missing model types. No plugin,
configuration, or dependency-resolution failure occurred.

## Task 3 — move six risk models and restore GREEN

Exactly six production models moved from the app source set to
`:domain:risk`: `AlertState`, `RiskEvent`, `RiskLevel`, `RiskScore`,
`RiskSignal`, and `SignalCategory`. Their package/FQCN, declarations, public
constructor/component API, enum order/category, and KDoc were preserved. The
app now has a one-way dependency on `:domain:risk`.

The four moved files that previously lacked a terminal newline were normalized
to end with a newline. This was newline-only normalization; their Kotlin
declarations and behavior did not change.

With JDK 21, offline dependency resolution, and serial Gradle workers:

```text
./gradlew.bat :domain:risk:test :domain:risk:lint --offline --no-daemon --no-parallel --max-workers=1 --console=plain --info
```

Result: `BUILD SUCCESSFUL`; 5 tests, 0 failures, 0 errors, and 0 skipped. The
standalone lint graph executed `lintAnalyzeJvmMain` against all six Kotlin
production sources (not `NO-SOURCE`), and its report contains 0 issues.

App integration was checked separately:

```text
./gradlew.bat :app:compileDebugKotlin :app:checkDebugDuplicateClasses --offline --no-daemon --no-parallel --max-workers=1 --console=plain
```

Result: `BUILD SUCCESSFUL`; compilation and duplicate-class checking both
completed successfully. Task 5 fresh full-build, test, lint-fingerprint, and
post-move Compose canary verification remain pending.
