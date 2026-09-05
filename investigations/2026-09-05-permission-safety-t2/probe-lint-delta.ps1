param([string]$Validator = "$PSScriptRoot/verify-lint-delta.ps1")
$ErrorActionPreference = 'Stop'
$baseline = "$PSScriptRoot/../2026-09-05-domain-contracts-m2-t2/evidence/lint/baseline-lint.sanitized.xml"
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('senior-shield-permission-lint-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $scratch
$results = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [bool]$Expected, [hashtable]$Arguments) {
    $accepted = $true
    try { & $Validator @Arguments | Out-Null } catch { $accepted = $false }
    $results.Add([pscustomobject]@{ Name=$Name; Expected=$Expected; Accepted=$accepted })
}
try {
    [xml]$report = Get-Content -LiteralPath $baseline -Raw
    foreach ($issue in @($report.issues.issue | Where-Object severity -eq 'Error')) { $null = $report.issues.RemoveChild($issue) }
    $expected = Join-Path $scratch 'expected.xml'
    $report.Save($expected)
    Check 'Exactly approved five errors removed' $true @{Current=$expected}
    Check 'Old errors retained' $false @{Current=$baseline}
    Check 'Missing current report' $false @{}
    $added = $report.Clone()
    $newIssue = $added.issues.issue[0].CloneNode($true)
    $newIssue.severity = 'Error'
    $null = $added.issues.AppendChild($newIssue)
    $added.Save((Join-Path $scratch 'added.xml'))
    Check 'New error rejected' $false @{Current=(Join-Path $scratch 'added.xml')}
    $changed = $report.Clone()
    $changed.issues.issue[0].message = 'Unexpected new diagnostic text'
    $changed.Save((Join-Path $scratch 'changed.xml'))
    Check 'Changed warning rejected' $false @{Current=(Join-Path $scratch 'changed.xml')}
    $removed = $report.Clone()
    $null = $removed.issues.RemoveChild($removed.issues.issue[0])
    $removed.Save((Join-Path $scratch 'removed.xml'))
    Check 'Missing warning rejected' $false @{Current=(Join-Path $scratch 'removed.xml')}
    $extraLocation = $report.Clone()
    $location = $extraLocation.issues.issue[0].location.CloneNode($true)
    $location.file = 'C:/outside-repository/New.kt'
    $null = $extraLocation.issues.issue[0].AppendChild($location)
    $extraLocation.Save((Join-Path $scratch 'extra-location.xml'))
    Check 'Additional diagnostic location rejected' $false @{Current=(Join-Path $scratch 'extra-location.xml')}
    $noLocation = $report.Clone()
    $null = $noLocation.issues.issue[0].RemoveChild($noLocation.issues.issue[0].location)
    $noLocation.Save((Join-Path $scratch 'no-location.xml'))
    Check 'Missing diagnostic location rejected' $false @{Current=(Join-Path $scratch 'no-location.xml')}
    $results | Format-Table -AutoSize
    if (@($results | Where-Object { $_.Expected -ne $_.Accepted }).Count) { throw 'Lint delta probe failed.' }
    "PASS: $($results.Count) lint delta probes"
} finally {
    $resolved = [IO.Path]::GetFullPath($scratch)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
