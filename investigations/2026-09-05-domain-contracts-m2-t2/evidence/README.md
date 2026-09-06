# M2 Task 4 tracked evidence

The four P1 and four P2 Compose compiler reports are byte-preserved copies of
the ignored artifacts under `.superpowers/sdd/IMPLEMENTATION_PLAN`. The local
`.gitattributes` disables checkout line-ending conversion for these frozen
reports and the other hashed evidence files.

Both frozen `app_debug-classes.txt` reports contain the compiler-emitted line
`<runtime stability> = ` with a trailing space. The two exact paths disable
Git's `blank-at-eol` whitespace check because removing that byte would change
the recorded raw hashes; the reports themselves remain unmodified.

| Evidence | SHA-256 |
|---|---|
| `compose-p1/app_debug-module.json` | `08D35BD5D9D7BB371B3399AFF1CC331A6EB329DBE49D00C412560E9683AAF243` |
| `compose-p1/app_debug-composables.csv` | `F8D8CEF8F38F4A66254DE4029A7E431A6AF2F21C0535E387717B2E7A2D455F37` |
| `compose-p1/app_debug-composables.txt` | `C392734170FA2A877005ADD953CABB216DE348BEC71AC5FA31F47F15C51E5B58` |
| `compose-p1/app_debug-classes.txt` | `BE2C9A2B55979C84816563EA33158A12B95B023F014806C533FCC112FE0A285E` |
| `compose-p2/app_debug-module.json` | `A9BB516B9B5AC59CA3BCD317BC4514D7FF3B3D4F10C422B2DC00389D9E800DBD` |
| `compose-p2/app_debug-composables.csv` | `F8D8CEF8F38F4A66254DE4029A7E431A6AF2F21C0535E387717B2E7A2D455F37` |
| `compose-p2/app_debug-composables.txt` | `C392734170FA2A877005ADD953CABB216DE348BEC71AC5FA31F47F15C51E5B58` |
| `compose-p2/app_debug-classes.txt` | `AFCC5BE57258D07B8098F9846AFFC052824A9D70E5323CCC027D964A21320EB9` |
| `abi-p0/implementation-sha256.txt` | `0EB93970E817A88ED03C6AE3F5D634C29DCF6B44E34094EF3C6406DB82A3C01D` |
| `abi-p0/javap-public-s.txt` | `3471D4E650B72F06310954C1D4FF105590662E5EDB6863DFCB4B341BF00EF59B` |
| `abi-p0/javap-targeted-verbose.txt` | `AC40658B8F8AC3F7359A00E9F08AF894B08224F9EF7B739A8AF46095988ED194` |

The ignored raw lint baseline remains at
`.superpowers/sdd/IMPLEMENTATION_PLAN/baseline-lint.xml`, SHA-256
`499DB3BD480687C5AB9C50FD45414FAF94574CF549E2A498944CEACCC038A588`.
`lint/baseline-lint.sanitized.xml` is a separately identified tracked copy. It
replaces the exact absolute worktree prefix in 72 `file` attributes with a
repository-relative path and makes no other semantic normalization; its raw
SHA-256 is
`FFD8F2A8D22A845C26C5794315441ABF9D58A3121724B63A562BF570167A1961`.
The canonical diagnostic fingerprint remains
`8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD`.

`../validate-compose-bplus.ps1` uses the committed P1 baseline and committed
P2 reports by default. Pass `-EvidenceRoot` as either a fresh post-report
directory or a parent containing `compose-p2` to validate a fresh P2 capture.
