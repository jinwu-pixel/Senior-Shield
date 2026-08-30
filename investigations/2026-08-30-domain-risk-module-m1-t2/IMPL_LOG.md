# M1 T2 implementation log

## Task 1 — Compose observability baseline and plugin preflight

### Conditional compiler reporting

- `app/build.gradle.kts` reads `composeCompilerReportsDir` only when the Gradle
  property is present.
- Only `compileDebugKotlin` receives Compose compiler `reportsDestination` and
  `metricsDestination`; both use the same normalized absolute path.
- With no property, this block does not configure Kotlin compile tasks or add
  compiler arguments.

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
