#!/usr/bin/env pwsh
# Enforces the frozen lint state of the two pure JVM modules.
# domain:risk must report zero issues. domain:contracts may report zero issues, or ONE that matches
# the approved warning in every field that identifies it: id, severity, normalized message,
# offending declaration (errorLine1), exactly one location, and the exact repository-relative path.
# When present, only the "available: <version>" metadata may drift (normalized to <LATEST>, same rule as the
# frozen app/data lint fingerprints in investigations/2026-09-05-data-module-m3-t2/lint-records.ps1).
[CmdletBinding()]
param(
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
)
$ErrorActionPreference = 'Stop'

# Frozen contract (main 7c6bfd1, 2026-09-06): the deliberate coroutines-core 1.8.1 pin in :domain:contracts.
$Approved = [ordered]@{
    Id            = 'GradleDependency'
    Severity      = 'Warning'
    Message       = 'A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-core than 1.8.1 is available: <LATEST>'
    Declaration   = '    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")'
    LocationCount = 1
    File          = 'domain/contracts/build.gradle.kts'
}

$prefix = $RepositoryRoot.Replace('\', '/').TrimEnd('/') + '/'
if ($prefix -notmatch '^(?:[A-Za-z]:/|/)') { throw 'Absolute repository root required.' }

function Get-RepositoryPath([string]$Path) {
    $normalized = $Path.Replace('\', '/')
    if ($normalized -match '^(?:[A-Za-z]:/|/)') {
        $comparison = if ($prefix -match '^[A-Za-z]:/' -or $prefix.StartsWith('//')) {
            [StringComparison]::OrdinalIgnoreCase
        } else { [StringComparison]::Ordinal }
        if (-not $normalized.StartsWith($prefix, $comparison)) { throw "Lint location outside repository root: $Path" }
        $normalized = $normalized.Substring($prefix.Length)
    }
    if ($normalized -match '(^|/)\.\.(/|$)|:') { throw "Unsupported lint location: $Path" }
    return ($normalized -replace '^(\./)+', '')
}

function Read-Issues([string]$RelativePath) {
    $path = Join-Path $RepositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing lint report: $RelativePath" }
    [xml]$document = Get-Content -LiteralPath $path -Raw
    if ($null -eq $document.issues) { throw "Not a lint report: $RelativePath" }
    return @($document.issues.issue | Where-Object { $null -ne $_ })
}

function Describe($Issues) {
    return (($Issues | ForEach-Object { "$($_.id)/$($_.severity)@$(@($_.location)[0].file)" }) -join '; ')
}

# @() at the call sites keeps single-issue reports as arrays (PowerShell unrolls one-element returns).
$risk = @(Read-Issues 'domain/risk/build/reports/lint-results.xml')
if ($risk.Count -ne 0) { throw "domain:risk lint expected 0 issues, found $($risk.Count): $(Describe $risk)" }

$contracts = @(Read-Issues 'domain/contracts/build/reports/lint-results.xml')
if ($contracts.Count -eq 0) {
    # The approved warning is a "newer version available" lookup against Maven Central. Its presence is
    # environment-dependent (run 34021941395 on a fresh ubuntu runner reported 0 issues here), so absence
    # is tolerated. Anything else present is still a failure below.
    Write-Output 'Verdict: PASS domain:risk=0 issues; domain:contracts=0 issues (approved lookup-dependent GradleDependency warning absent in this environment, tolerated), 0 errors'
    exit 0
}
if ($contracts.Count -ne 1) {
    throw "domain:contracts lint expected at most the one approved issue, found $($contracts.Count): $(Describe $contracts)"
}
$issue = $contracts[0]
$locations = @($issue.location | Where-Object { $null -ne $_ })
$actual = [ordered]@{
    Id            = [string]$issue.id
    Severity      = [string]$issue.severity
    Message       = ([string]$issue.message) -replace '(?<=available: )[^\s]+', '<LATEST>'
    Declaration   = [string]$issue.errorLine1
    LocationCount = $locations.Count
    File          = if ($locations.Count -eq 1) { Get-RepositoryPath ([string]$locations[0].file) } else { '' }
}
$mismatches = @()
foreach ($key in $Approved.Keys) {
    if ([string]$actual[$key] -cne [string]$Approved[$key]) {
        $mismatches += "$key expected [$($Approved[$key])] actual [$($actual[$key])]"
    }
}
if ($mismatches.Count -ne 0) {
    throw "domain:contracts lint issue does not match the approved warning: $($mismatches -join '; ')"
}

Write-Output "Verdict: PASS domain:risk=0 issues; domain:contracts=1 approved GradleDependency warning ($($Approved.File): coroutines-core 1.8.1 pin), 0 errors"
