[CmdletBinding()]
param(
    [string]$Baseline = "$PSScriptRoot/evidence/lint/baseline-lint.sanitized.xml",
    [string]$Current,
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Current)) {
    throw 'Current is required: pass -Current app/build/reports/lint-results-debug.xml after running lint.'
}
if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) { throw 'RepositoryRoot must not be empty.' }
$rootPrefix = $RepositoryRoot.Replace('\', '/').TrimEnd('/') + '/'
if ($rootPrefix -notmatch '^(?:[A-Za-z]:/|/)') { throw 'RepositoryRoot must be absolute.' }

function Get-RepositoryPath([string]$Path) {
    $normalized = $Path.Replace('\', '/')
    if ($normalized -match '^(?:[A-Za-z]:/|/)') {
        $comparison = if ($rootPrefix -match '^[A-Za-z]:/' -or $rootPrefix.StartsWith('//')) {
            [StringComparison]::OrdinalIgnoreCase
        } else { [StringComparison]::Ordinal }
        if (-not $normalized.StartsWith($rootPrefix, $comparison)) {
            throw "Lint location is outside RepositoryRoot: $Path"
        }
        $normalized = $normalized.Substring($rootPrefix.Length)
    }
    if ($normalized -match '(^|/)\.\.(/|$)|:') { throw "Unsupported lint location: $Path" }
    # Keep the frozen M1 representation; separator normalization must not rebaseline diagnostics.
    return ($normalized -replace '^(\./)+', '').Replace('/', '\')
}
function Get-NormalizedLint([string]$Path) {
    [xml]$doc = Get-Content -LiteralPath $Path -Raw
    foreach ($issue in $doc.issues.issue) {
        $loc = @($issue.location)[0]
        $file = if ($loc.file) {
            Get-RepositoryPath ([string]$loc.file)
        } else { '' }
        $message = ([string]$issue.message) -replace '(?<=available: )[^\s]+', '<LATEST>'
        (([string]$issue.id) + '|' + ([string]$issue.severity) + '|' + $message + '|' + $file + '|' + ([string]$issue.errorLine1)).Trim()
    }
}
function Get-Sha([string[]]$Rows) {
    $canonical = @($Rows | Sort-Object) -join "`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-', '') }
    finally { $sha.Dispose() }
}
$before = @(Get-NormalizedLint $Baseline)
$after = @(Get-NormalizedLint $Current)
$difference = @(Compare-Object ($before | Sort-Object) ($after | Sort-Object))
$expected = '8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD'
$beforeHash = Get-Sha $before
$afterHash = Get-Sha $after
if ($before.Count -ne 72 -or $after.Count -ne 72 -or $difference.Count -ne 0 -or $beforeHash -ne $expected -or $afterHash -ne $expected) {
    $difference | Format-Table
    throw "Lint fingerprint mismatch: baseline=$($before.Count)/$beforeHash current=$($after.Count)/$afterHash delta=$($difference.Count)"
}
[pscustomobject]@{BaselineCount=$before.Count; CurrentCount=$after.Count; Differences=$difference.Count; Fingerprint=$afterHash}
