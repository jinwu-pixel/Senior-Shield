# Domain Contracts M2 Task 5 Verification

Verification source was commit `38cec4f`; the production implementation remains commit `5391210`. Commands used the configured JDK 21 and Android SDK, one Gradle executor, `--no-daemon --no-parallel --max-workers=1 --console=plain`, and the isolated `domain-contracts-m2` worktree. Full raw logs and generated reports remain in ignored `.superpowers/sdd/IMPLEMENTATION_PLAN/task5-scratch/`.

## Fresh build and tests

The following offline command completed with exit 0 in 402.945 seconds (`BUILD SUCCESSFUL in 6m 42s`; 80 actionable tasks, 79 executed, 1 up-to-date):

```text
./gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain clean :domain:risk:check :domain:contracts:check :app:testDebugUnitTest :app:kaptDebugKotlin :app:assembleDebug :app:checkDebugDuplicateClasses -PcomposeCompilerReportsDir=<ignored-scratch>/compose-final
```

Fresh JUnit XML counts were:

| Target | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| app | 35 | 454 | 0 | 0 | 0 |
| domain:risk | 1 | 7 | 0 | 0 | 0 |
| domain:contracts | 1 | 4 | 0 | 0 | 0 |

The build emitted the previously documented Android API deprecation warnings and unit-test kapt processor-option warning. No compile, test, kapt, assemble, or duplicate-class gate failed.

## Lint

Metadata-enabled `:domain:contracts:lint --rerun-tasks` ran without `--offline` and completed with exit 0 in 52.666 seconds; all 15 tasks executed, including contracts main/test analysis. The full fresh check executed both modules' main/test analyses, while the metadata-enabled app lint rerun executed both modules' main analyses. Parsed partial results are risk main 0/test 0 incidents and contracts main 0/test 0 incidents. Risk aggregate lint has 0 diagnostics. Contracts aggregate lint has exactly one warning: the narrowly approved `GradleDependency` debt for `kotlinx-coroutines-core:1.8.1` (latest observed metadata value `1.11.0`), with 0 errors and no source diagnostics.

Metadata-enabled `:app:lintDebug --rerun-tasks` ran separately without `--offline`; it completed all 49 tasks and returned the expected exit 1 in 428.655 seconds with exactly 5 errors and 67 warnings. The existing M1 fingerprint script and the portable copy in this investigation both compared the current report against their respective raw and sanitized baselines and returned baseline 72/current 72/difference 0/SHA-256 `8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD`.

Portable reproduction:

```powershell
./investigations/2026-09-05-domain-contracts-m2-t2/verify-lint-fingerprint.ps1 -Current app/build/reports/lint-results-debug.xml
```

## Compose, ABI, and module boundary

The tracked B+ validator against the fresh `compose-final` reports returned PASS: Guardian removed, additions 0, 78 exact common blocks, 8 projected changed blocks, 11 repository field transitions, and 0 affected type parameters. `GuardianCard` remains restartable/skippable with a stable Guardian argument. All four fresh reports are byte-identical to tracked P2: module JSON `A9BB516B9B5AC59CA3BCD317BC4514D7FF3B3D4F10C422B2DC00389D9E800DBD`, composables CSV `F8D8CEF8F38F4A66254DE4029A7E431A6AF2F21C0535E387717B2E7A2D455F37`, composables TXT `C392734170FA2A877005ADD953CABB216DE348BEC71AC5FA31F47F15C51E5B58`, and classes TXT `AFCC5BE57258D07B8098F9846AFFC052824A9D70E5323CCC027D964A21320EB9`. All eight negative probes rejected the injected JSON key/type and extra field-transition mutations.

Fresh `javap -public -s` output for the five moved declarations matches the frozen pre-move output after removing exactly Guardian's `$stable:I` block and normalizing the command header/blank separators/newlines. Both ordered declaration/generic/descriptor projections hash to `DDF3E6C869DF2FBCC93A55D77354BEACFB7B594605E6E560708FC2CCA0B72DAE`. The contracts output contains exactly six class files, including `Guardian$Companion`; all six have major version 61, and `StabilityInferred` is absent from Guardian.

All five moved sources match the baseline `7754ebf5` copies modulo line endings and terminal newline. Contracts main has 0 Android, AndroidX, Compose, Hilt, Inject, or coroutines-android imports. Fresh dependency reports show contracts `api` exports exactly `project risk` and `kotlinx-coroutines-core:1.8.1`; risk `compileClasspath` contains only Kotlin stdlib/annotations and has no contracts reverse edge. All five implementation/DI files match `evidence/abi-p0/implementation-sha256.txt` exactly.

## Policy and diff scope

Production search finds exactly two legacy SMS interface declarations and two implementation overrides; all other `observeSmsAlertEnabled`/`setSmsAlertEnabled` invocation sites remain 0. Searches found 0 `SEND_SMS`, `READ_SMS`, `ACTION_CALL`, `SmsManager`, or `sendTextMessage` matches. Existing user-started contact paths remain `ACTION_DIAL` and manual `ACTION_SENDTO`.

The baseline-to-verification-source diff contains 34 files, all inside the DIRECTIVE allowlist; adding this report, portable verifier, and compact Task 5 evidence brings the review diff to 37 allowlisted files. It contains no Manifest, data, monitoring, core, DI, navigation, service, or other sensitive app source change. Both the committed diff and working-tree `git diff --check` passed. The controller independently inspected the unchanged policy-sensitive paths and confirmed the existing `MonitoringForegroundService` and `SeniorShieldApp` initialization remain only the AGENTS-approved exceptions.

This evidence supports no detected API, module-boundary, lint-fingerprint, or Compose compiler-report contract regression. Runtime recomposition was not measured, and the metadata/ABI checks do not claim generated-bytecode identity. Whole-branch independent final review and Ready PR publication are recorded as complete in `IMPL_LOG.md`; merge is outside the authorized scope.

## PR #10 follow-up: lint verifier reproduction

The post-publication review found two verifier defects: omitting `-Current` compared the baseline against itself, and absolute-path normalization assumed one Windows checkout name. The follow-up fixes only investigation tooling and documents; production source, Gradle configuration, and frozen evidence remain unchanged.

`-Current` is now required by an explicit fail-closed check (also safe under noninteractive PowerShell). `-RepositoryRoot` defaults to this checkout's root, independent of working directory. When comparing a report generated in another checkout, supply that report's absolute repository root. Windows and POSIX separators are normalized to the original M1 backslash-relative representation, preserving the frozen fingerprint; paths outside the supplied repository are rejected.

```powershell
./investigations/2026-09-05-domain-contracts-m2-t2/verify-lint-fingerprint.ps1 -Current app/build/reports/lint-results-debug.xml
./investigations/2026-09-05-domain-contracts-m2-t2/verify-lint-fingerprint.ps1 -Current /path/to/copied-report.xml -RepositoryRoot /workspace/Senior-Shield
./investigations/2026-09-05-domain-contracts-m2-t2/probe-lint-fingerprint.ps1
```

Before the fix, probes reproduced baseline self-comparison PASS and false failures for POSIX separators and arbitrary Windows/POSIX roots. After the fix, all 11 probes returned their expected verdicts: four equivalent-path cases accepted; omitted/nonexistent input, outside-root location, changed anchor, added/removed diagnostic, and changed source path rejected. The actual Task 5 report was reread and compared at 72/72 diagnostics, difference 0, with the unchanged frozen SHA above. Path portability is exercised with synthetic Windows/POSIX reports using PowerShell on Windows; no Linux-host execution or new Gradle run is claimed. An explicit report path does not prove report freshness: run lint before using this verifier as a completion gate.
