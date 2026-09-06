$ErrorActionPreference = 'Stop'
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('ss-m3-lint-' + [guid]::NewGuid().ToString('N'))
$validator = Join-Path $PSScriptRoot 'verify-lint-move.ps1'
$app = Join-Path $PSScriptRoot 'evidence/app-lint-after.sanitized.xml'
$data = Join-Path $PSScriptRoot 'evidence/data-lint-after.sanitized.xml'
$results = [Collections.Generic.List[object]]::new()
function Check($name,$expected,$arguments) {
    $accepted = $true
    try { & $validator @arguments | Out-Null } catch { $accepted = $false }
    $results.Add([pscustomobject]@{Name=$name;Expected=$expected;Accepted=$accepted})
}
try {
    New-Item -ItemType Directory -Path $scratch | Out-Null
    Check 'Exact approved reports' $true @{AppCurrent=$app;DataCurrent=$data}
    Check 'Missing current reports' $false @{}
    [xml]$changed = Get-Content $app
    $issue = $changed.issues.issue[0].CloneNode($true)
    $issue.message = 'Unexpected source diagnostic'
    $null = $changed.issues.AppendChild($issue)
    $changed.Save("$scratch/extra.xml")
    Check 'Additional app diagnostic' $false @{AppCurrent="$scratch/extra.xml";DataCurrent=$data}
    [xml]$changed = Get-Content $app
    $null = $changed.issues.RemoveChild($changed.issues.issue[0])
    $changed.Save("$scratch/missing.xml")
    Check 'Missing existing app error' $false @{AppCurrent="$scratch/missing.xml";DataCurrent=$data}
    [xml]$changed = Get-Content $data
    $changed.issues.issue[0].message = 'Different declaration debt'
    $changed.Save("$scratch/data.xml")
    Check 'Changed data diagnostic' $false @{AppCurrent=$app;DataCurrent="$scratch/data.xml"}
    [xml]$changed = Get-Content $data
    $extra = $changed.issues.issue[0].location.CloneNode($true)
    $extra.file = 'C:/outside/New.kt'
    $null = $changed.issues.issue[0].AppendChild($extra)
    $changed.Save("$scratch/location.xml")
    Check 'Additional diagnostic location' $false @{AppCurrent=$app;DataCurrent="$scratch/location.xml"}
    $results | Format-Table -AutoSize
    if (@($results | Where-Object { $_.Expected -ne $_.Accepted }).Count) { throw 'Lint probes failed.' }
    "PASS: $($results.Count) lint probes"
} finally {
    $resolved = [IO.Path]::GetFullPath($scratch)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
