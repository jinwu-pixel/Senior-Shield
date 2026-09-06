[CmdletBinding()]
param(
    [string]$Current,
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Current)) { throw 'Pass the freshly generated lint report with -Current.' }
$baseline = "$PSScriptRoot/../2026-09-05-domain-contracts-m2-t2/evidence/lint/baseline-lint.sanitized.xml"
$rootPrefix = $RepositoryRoot.Replace('\', '/').TrimEnd('/') + '/'
if ($rootPrefix -notmatch '^(?:[A-Za-z]:/|/)') { throw 'RepositoryRoot must be absolute.' }
function Get-RepositoryPath([string]$Path) {
    $normalized = $Path.Replace('\', '/')
    if ($normalized -match '^(?:[A-Za-z]:/|/)') {
        $comparison = if ($rootPrefix -match '^[A-Za-z]:/' -or $rootPrefix.StartsWith('//')) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
        if (-not $normalized.StartsWith($rootPrefix, $comparison)) { throw "Outside repository: $Path" }
        $normalized = $normalized.Substring($rootPrefix.Length)
    }
    if ($normalized -match '(^|/)\.\.(/|$)|:') { throw "Unsupported lint location: $Path" }
    return ($normalized -replace '^(\./)+', '').Replace('/', '\')
}
function Get-Rows($Issues) {
    foreach ($issue in $Issues) {
        if (@($issue.location).Count -ne 1) { throw 'Expected exactly one diagnostic location.' }
        $loc = @($issue.location)[0]
        $file = if ($loc.file) { Get-RepositoryPath ([string]$loc.file) } else { '' }
        $message = ([string]$issue.message) -replace '(?<=available: )[^\s]+', '<LATEST>'
        (([string]$issue.id) + '|' + ([string]$issue.severity) + '|' + $message + '|' + $file + '|' + ([string]$issue.errorLine1)).Trim()
    }
}
function Get-Sha([string[]]$Rows) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($Rows | Sort-Object) -join "`n")))).Replace('-', '') }
    finally { $sha.Dispose() }
}
[xml]$before = Get-Content -LiteralPath $baseline -Raw
[xml]$after = Get-Content -LiteralPath $Current -Raw
$allBefore = @(Get-Rows $before.issues.issue)
if ($allBefore.Count -ne 72 -or (Get-Sha $allBefore) -ne '8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD') { throw 'Frozen M2 baseline changed.' }
# This fixed baseline has exactly these five errors; no general future-error waiver.
$approved = @(
    'MissingPermission|app\src\main\java\com\example\seniorshield\core\overlay\BankingCooldownManager.kt',
    'MissingPermission|app\src\main\java\com\example\seniorshield\core\util\CallEndHelper.kt',
    'MissingPermission|app\src\main\java\com\example\seniorshield\core\notification\RiskNotificationManager.kt',
    'MissingPermission|app\src\main\java\com\example\seniorshield\core\overlay\RiskOverlayManager.kt',
    'PermissionImpliesUnsupportedChromeOsHardware|app\src\main\AndroidManifest.xml'
)
$errors = @($before.issues.issue | Where-Object severity -eq 'Error')
$errorKeys = @($errors | ForEach-Object { ([string]$_.id) + '|' + (Get-RepositoryPath ([string](@($_.location)[0].file))) })
if ($errors.Count -ne 5 -or @(Compare-Object ($approved | Sort-Object) ($errorKeys | Sort-Object)).Count) { throw 'Unexpected approved-removal inventory.' }
$expected = @(Get-Rows @($before.issues.issue | Where-Object severity -eq 'Warning'))
$actual = @(Get-Rows $after.issues.issue)
$differences = @(Compare-Object ($expected | Sort-Object) ($actual | Sort-Object))
$frozen = '338C12F4515B3CC90E3337173B36FC74215E0764804FB6188181061787C7D332'
if ($expected.Count -ne 67 -or $actual.Count -ne 67 -or $differences.Count -or (Get-Sha $expected) -ne $frozen -or (Get-Sha $actual) -ne $frozen) {
    $differences | Format-Table
    throw "Lint delta mismatch: expected67/current$($actual.Count)/differences$($differences.Count)"
}
[pscustomobject]@{Baseline=72;ApprovedErrorsRemoved=5;Current=67;Differences=0;Fingerprint=$frozen}
