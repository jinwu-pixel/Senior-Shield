$ErrorActionPreference = 'Stop'
$validator = Join-Path $PSScriptRoot 'verify-lint-fingerprint.ps1'
$baseline = Join-Path $PSScriptRoot 'evidence/lint/baseline-lint.sanitized.xml'
$probeRoot = Join-Path ([IO.Path]::GetTempPath()) ('senior-shield-lint-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $probeRoot
$failures = [Collections.Generic.List[string]]::new()
$results = [Collections.Generic.List[object]]::new()

function Assert-Verdict([string]$Name, [bool]$ShouldPass, [hashtable]$Arguments) {
    $message = $null
    try { & $validator @Arguments | Out-Null } catch { $message = $_.Exception.Message }
    $passed = $null -eq $message
    if ($passed -ne $ShouldPass) { $failures.Add("${Name}: expected pass=$ShouldPass; error=$message") }
    $results.Add([pscustomobject]@{ Probe = $Name; Expected = $ShouldPass; Actual = $passed })
}

function New-Report([string]$Name, [scriptblock]$Mutation) {
    [xml]$doc = Get-Content -LiteralPath $baseline -Raw
    & $Mutation $doc
    $path = Join-Path $probeRoot "$Name.xml"
    $doc.Save($path)
    return $path
}

try {
    Assert-Verdict 'Missing current report fails closed' $false @{}
    Assert-Verdict 'Nonexistent current report fails closed' $false @{ Current = (Join-Path $probeRoot 'absent.xml') }
    $relative = New-Report 'relative' { param($doc) }
    Assert-Verdict 'Relative Windows paths preserve frozen fingerprint' $true @{ Current = $relative }
    $unixRelative = New-Report 'unix-relative' {
        param($doc)
        foreach ($loc in $doc.SelectNodes('//location')) { $loc.file = $loc.file.Replace('\', '/') }
    }
    Assert-Verdict 'Relative Unix separators preserve fingerprint' $true @{ Current = $unixRelative }
    $windows = New-Report 'windows-clone' {
        param($doc)
        foreach ($loc in $doc.SelectNodes('//location')) { $loc.file = 'D:\Clones\Renamed App\' + $loc.file }
    }
    Assert-Verdict 'Arbitrary Windows checkout including spaces' $true @{ Current = $windows; RepositoryRoot = 'D:\Clones\Renamed App' }
    $unix = New-Report 'unix-clone' {
        param($doc)
        foreach ($loc in $doc.SelectNodes('//location')) { $loc.file = '/workspace/Senior-Shield/' + $loc.file.Replace('\', '/') }
    }
    Assert-Verdict 'Unix checkout independent of Windows host' $true @{ Current = $unix; RepositoryRoot = '/workspace/Senior-Shield' }
    Assert-Verdict 'Sibling directory is not the repository' $false @{ Current = $unix; RepositoryRoot = '/workspace/Senior' }
    $changed = New-Report 'changed-anchor' { param($doc) $doc.issues.issue[0].errorLine1 = 'different source call' }
    Assert-Verdict 'Changed source anchor rejects' $false @{ Current = $changed }
    $added = New-Report 'added-issue' { param($doc) $null = $doc.issues.AppendChild($doc.issues.issue[0].CloneNode($true)) }
    Assert-Verdict 'Additional diagnostic rejects' $false @{ Current = $added }
    $removed = New-Report 'removed-issue' { param($doc) $null = $doc.issues.RemoveChild($doc.issues.issue[0]) }
    Assert-Verdict 'Missing diagnostic rejects' $false @{ Current = $removed }
    $differentPath = New-Report 'different-source' { param($doc) $doc.issues.issue[0].location.file = 'app\src\main\java\Different.kt' }
    Assert-Verdict 'Different repository file rejects' $false @{ Current = $differentPath }
    $results | Format-Table -AutoSize
    if ($failures.Count -gt 0) { throw ($failures -join "`n") }
    "PASS: $($results.Count) lint fingerprint probes"
} finally {
    $resolved = [IO.Path]::GetFullPath($probeRoot)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe probe cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
