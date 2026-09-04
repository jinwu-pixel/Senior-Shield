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
`META-INF/gradle-plugins/com.android.lint.properties` descriptor. Kotlin
`org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24` contains the JVM plugin
wrapper descriptors `META-INF/gradle-plugins/kotlin.properties` and
`META-INF/gradle-plugins/kotlin-platform-jvm.properties`; it does not contain a
file literally named `org.jetbrains.kotlin.jvm.properties`. Therefore the
revised plan does not claim marker availability: the actual versionless
application is accepted only if the next offline RED passes plugin
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

The paired module JSON, composables CSV, and classes TXT files also have
identical SHA-256 hashes. These debug numbers are a same-variant relative
regression canary, not an absolute assessment of release performance.
