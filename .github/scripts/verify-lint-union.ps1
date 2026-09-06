#!/usr/bin/env pwsh
# CI judgment for app/data lint against the SAME frozen inventories (same evidence files, same
# hashes) used by investigations/2026-09-05-integration-m3-permission/verify-integration-lint.ps1.
#
# Why this is not byte-for-byte the local exact-union verifier: run 34021941395 (fresh ubuntu-24.04
# runner) reproduced the frozen inventories except exactly 5 app + 1 data "A newer version of
# org.jetbrains.kotlin* is available" GradleDependency warnings. Their absence was observed in that
# environment; this comparison does not prove a particular network/cache failure. Rule: exact
# multiset equality with the frozen inventories, except that the
# enumerated keys below may be absent up to their frozen counts. Nothing new, nothing changed, nothing
# else missing. There is no id-level waiver and no baseline file.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppCurrent,
    [Parameter(Mandatory)][string]$DataCurrent,
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')),
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'

# -RepositoryRoot is only the prefix that lint locations in the CURRENT reports are relativized
# against. The frozen inventories always come from the repository this script lives in, so a report
# produced elsewhere (another checkout path, a probe root) can be judged without copying evidence.
$evidenceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$m2 = Join-Path $evidenceRoot 'investigations/2026-09-05-domain-contracts-m2-t2'
$m3 = Join-Path $evidenceRoot 'investigations/2026-09-05-data-module-m3-t2'
. (Join-Path $m3 'lint-records.ps1')

# Frozen inventories, derived and hash-checked exactly as verify-integration-lint.ps1 does.
$baseline = @(Read-LintRecords (Join-Path $m2 'evidence/lint/baseline-lint.sanitized.xml') $evidenceRoot)
if ($baseline.Count -ne 72 -or (Get-LintFingerprint $baseline.Key) -cne '8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD') {
    throw 'Frozen M1/M2 lint baseline changed.'
}
$room = @($baseline | Where-Object {
    $_.File -ceq 'app\build.gradle.kts' -and $_.Declaration -ceq '    kapt("androidx.room:room-compiler:2.6.1")' -and
    $_.Severity -ceq 'Warning' -and $_.Id -cin @('GradleDependency', 'KaptUsageInsteadOfKsp', 'UseTomlInstead')
})
if ($room.Count -ne 3 -or @($room.Id | Sort-Object -Unique).Count -ne 3) { throw 'Room warning inventory changed.' }
$retained = @($baseline | Where-Object { $_.Severity -ceq 'Warning' -and $room.Key -cnotcontains $_.Key })
if ($retained.Count -ne 64) { throw 'Expected 64 retained app warnings.' }
$additions = @(Get-Content (Join-Path $m3 'evidence/app-lint-additions.json') -Raw | ConvertFrom-Json)
if ($additions.Count -ne 5 -or (Get-LintFingerprint $additions.Key) -cne 'E59915627B661B55896310A6B4D9B4E2C36B3695DED44E3CA14261B89DAF92BA') {
    throw 'Frozen app lint additions changed.'
}
$dataExpected = @(Get-Content (Join-Path $m3 'evidence/data-lint-records.json') -Raw | ConvertFrom-Json)
if ($dataExpected.Count -ne 14 -or (Get-LintFingerprint $dataExpected.Key) -cne '8B8B8DBDD93425F073D1D928C27B5F9F29348173BD758F8C0E5F1A11821DBD32') {
    throw 'Frozen data lint warnings changed.'
}

# The ONLY diagnostics whose absence is tolerated: the exact keys (Id|Severity|<LATEST>-normalized
# message|file|declaration) and maximum counts that run 34021941395 (fresh ubuntu-24.04 runner) could
# not produce. All are the observed Kotlin/coroutines "newer version" warnings. Every other
# frozen diagnostic, including the 23 app + 4 data Google Maven lookups, must be present exactly.
$coroutinesAndroid = 'GradleDependency|Warning|A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-android than 1.8.1 is available: <LATEST>'
$ToleratedAbsence = @{
    app  = @{
        'GradleDependency|Warning|A newer version of org.jetbrains.kotlin.plugin.compose than 2.0.21 is available: <LATEST>|gradle\libs.versions.toml|kotlin = "2.0.21"' = 3
        "$coroutinesAndroid|app\build.gradle.kts|    implementation(`"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`")" = 1
        'GradleDependency|Warning|A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-test than 1.8.1 is available: <LATEST>|app\build.gradle.kts|    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")' = 1
    }
    data = @{
        "$coroutinesAndroid|data\build.gradle.kts|    implementation(`"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`")" = 1
    }
}

function Get-Counts([string[]]$Keys) {
    $counts = [Collections.Generic.Dictionary[string,int]]::new([StringComparer]::Ordinal)
    foreach ($key in $Keys) {
        if ($counts.ContainsKey($key)) { $counts[$key]++ } else { $counts.Add($key, 1) }
    }
    return ,$counts
}

function Compare-Inventory([string]$Label, [string[]]$ExpectedKeys, $CurrentRecords) {
    $errors = @($CurrentRecords | Where-Object { $_.Severity -ceq 'Error' })
    if ($errors.Count -ne 0) { throw "$Label lint has $($errors.Count) error(s): $(@($errors.Key) -join '; ')" }
    $expected = Get-Counts $ExpectedKeys
    $current = Get-Counts @($CurrentRecords | ForEach-Object { $_.Key })
    $allowed = $ToleratedAbsence[$Label]
    # Fail closed if the tolerated keys ever stop matching the frozen inventory (e.g. after a re-freeze).
    foreach ($key in $allowed.Keys) {
        if ([int]$expected[$key] -ne $allowed[$key]) { throw "$Label tolerated-absence key count differs from its frozen count $($allowed[$key]): $key" }
    }
    $problems = @()
    $tolerated = @()
    foreach ($key in $expected.Keys) {
        $delta = $expected[$key] - [int]$current[$key]
        if ($delta -le 0) { continue }
        if ($allowed.ContainsKey($key) -and $delta -le $allowed[$key]) { $tolerated += "$key x$delta" }
        else { $problems += "MISSING diagnostic: $key x$delta" }
    }
    foreach ($key in $current.Keys) {
        $delta = $current[$key] - [int]$expected[$key]
        if ($delta -gt 0) { $problems += "EXTRA diagnostic: $key x$delta" }
    }
    if ($problems.Count -ne 0) { throw ("$Label lint inventory mismatch:`n" + ($problems -join "`n")) }
    $tolerableTotal = 0
    foreach ($count in $allowed.Values) { $tolerableTotal += $count }
    return [pscustomobject]@{
        Label = $Label; Expected = $ExpectedKeys.Count; Current = $CurrentRecords.Count
        ExactRequired = $ExpectedKeys.Count - $tolerableTotal
        TolerableAbsent = $tolerableTotal
        ToleratedAbsent = @($tolerated | Sort-Object)
    }
}

$app = @(Read-LintRecords $AppCurrent $RepositoryRoot)
$data = @(Read-LintRecords $DataCurrent $RepositoryRoot)
$results = @(
    (Compare-Inventory 'app' (@($retained.Key) + @($additions.Key)) $app),
    (Compare-Inventory 'data' @($dataExpected.Key) $data)
)
foreach ($result in $results) {
    $note = if ($result.ToleratedAbsent.Count -gt 0) { " (absent, tolerated: " + ($result.ToleratedAbsent -join '; ') + ')' } else { '' }
    Write-Output "$($result.Label): $($result.Current)/$($result.Expected) present; exact-required $($result.ExactRequired) all present; tolerable-absent set $($result.TolerableAbsent)$note"
}
Write-Output "Verdict: PASS app=$($results[0].Current)/69 data=$($results[1].Current)/14 (exact multiset except the 5 app + 1 data enumerated Maven Central lookups, errors 0)"
if ($EvidenceOut) {
    $parent = Split-Path -Parent $EvidenceOut
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $EvidenceOut -Encoding utf8
}
