#!/usr/bin/env pwsh
# Aggregates Gradle JUnit XML per module and enforces the frozen unit-test floor.
# Baseline (main 7c6bfd1, 2026-09-06): app 544 / domain:risk 7 / domain:contracts 4 = 555 tests,
# failures/errors/skipped 0. Raising a floor is a deliberate follow-up edit, never automatic.
[CmdletBinding()]
param(
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')),
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'

$modules = @(
    @{ Name = 'app';              Dir = 'app/build/test-results/testDebugUnitTest'; MinTests = 544 },
    @{ Name = 'domain:risk';      Dir = 'domain/risk/build/test-results/test';      MinTests = 7 },
    @{ Name = 'domain:contracts'; Dir = 'domain/contracts/build/test-results/test'; MinTests = 4 }
)

$rows = @()
$total = 0
$totalSuites = 0
foreach ($module in $modules) {
    $dir = Join-Path $RepositoryRoot $module.Dir
    if (-not (Test-Path -LiteralPath $dir)) { throw "Missing test results for $($module.Name): $($module.Dir)" }
    $files = @(Get-ChildItem -LiteralPath $dir -Filter 'TEST-*.xml' -File)
    if ($files.Count -eq 0) { throw "No JUnit XML for $($module.Name) under $($module.Dir)" }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($file in $files) {
        [xml]$document = Get-Content -LiteralPath $file.FullName -Raw
        $suite = $document.testsuite
        if ($null -eq $suite) { throw "Not a JUnit testsuite: $($file.FullName)" }
        $tests += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
    }
    if ($failures -ne 0 -or $errors -ne 0) { throw "$($module.Name): failures=$failures errors=$errors" }
    if ($skipped -ne 0) { throw "$($module.Name): skipped=$skipped (skipped tests are not allowed)" }
    if ($tests -lt $module.MinTests) { throw "$($module.Name): tests=$tests is below the frozen floor $($module.MinTests)" }
    $rows += [pscustomobject]@{
        Module = $module.Name; Suites = $files.Count; Tests = $tests
        Failures = $failures; Errors = $errors; Skipped = $skipped; MinTests = $module.MinTests
    }
    $total += $tests
    $totalSuites += $files.Count
}

$rows | Format-Table -AutoSize | Out-String | Write-Output
Write-Output "Verdict: PASS total=$total suites=$totalSuites failures=0 errors=0 skipped=0"
if ($EvidenceOut) {
    $parent = Split-Path -Parent $EvidenceOut
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $rows | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $EvidenceOut -Encoding utf8
}
