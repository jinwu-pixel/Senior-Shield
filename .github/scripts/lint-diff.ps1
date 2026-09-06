#!/usr/bin/env pwsh
# Diagnostic only (never a gate): prints the multiset difference between the approved
# app/data lint inventories and fresh lint reports, so a failed exact-union check
# shows which diagnostics went missing or appeared. Reuses the frozen M2/M3 evidence.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppCurrent,
    [Parameter(Mandatory)][string]$DataCurrent,
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
)
$ErrorActionPreference = 'Continue'

$m2 = Join-Path $RepositoryRoot 'investigations/2026-09-05-domain-contracts-m2-t2'
$m3 = Join-Path $RepositoryRoot 'investigations/2026-09-05-data-module-m3-t2'
. (Join-Path $m3 'lint-records.ps1')

function Show-Diff([string]$Label, [string[]]$Expected, [string[]]$Current) {
    $expectedCounts = @{}
    foreach ($key in $Expected) { $expectedCounts[$key] = 1 + [int]$expectedCounts[$key] }
    $currentCounts = @{}
    foreach ($key in $Current) { $currentCounts[$key] = 1 + [int]$currentCounts[$key] }
    $missing = @(); $extra = @()
    foreach ($key in $expectedCounts.Keys) {
        $delta = $expectedCounts[$key] - [int]$currentCounts[$key]
        if ($delta -gt 0) { $missing += "$key x$delta" }
    }
    foreach ($key in $currentCounts.Keys) {
        $delta = $currentCounts[$key] - [int]$expectedCounts[$key]
        if ($delta -gt 0) { $extra += "$key x$delta" }
    }
    Write-Output "== $Label lint: expected=$($Expected.Count) current=$($Current.Count) missing=$($missing.Count) extra=$($extra.Count)"
    foreach ($row in ($missing | Sort-Object)) { Write-Output "  MISSING $row" }
    foreach ($row in ($extra | Sort-Object)) { Write-Output "  EXTRA   $row" }
}

try {
    $baseline = @(Read-LintRecords (Join-Path $m2 'evidence/lint/baseline-lint.sanitized.xml') $RepositoryRoot)
    $room = @($baseline | Where-Object {
        $_.File -ceq 'app\build.gradle.kts' -and $_.Declaration -ceq '    kapt("androidx.room:room-compiler:2.6.1")'
    })
    $retained = @($baseline | Where-Object { $_.Severity -ceq 'Warning' -and $room.Key -cnotcontains $_.Key })
    $additions = @(Get-Content (Join-Path $m3 'evidence/app-lint-additions.json') -Raw | ConvertFrom-Json)
    $dataExpected = @(Get-Content (Join-Path $m3 'evidence/data-lint-records.json') -Raw | ConvertFrom-Json)
} catch {
    Write-Output "lint-diff: cannot load frozen inventories: $_"
    exit 0
}

foreach ($target in @(@{ Label = 'app'; Report = $AppCurrent; Expected = @($retained.Key) + @($additions.Key) },
                      @{ Label = 'data'; Report = $DataCurrent; Expected = @($dataExpected.Key) })) {
    if (-not (Test-Path -LiteralPath $target.Report)) {
        Write-Output "== $($target.Label) lint: report not found ($($target.Report)); nothing to compare"
        continue
    }
    try {
        $current = @(Read-LintRecords $target.Report $RepositoryRoot)
        Show-Diff $target.Label $target.Expected @($current.Key)
    } catch {
        Write-Output "== $($target.Label) lint: cannot read report: $_"
    }
}
exit 0
