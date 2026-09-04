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
