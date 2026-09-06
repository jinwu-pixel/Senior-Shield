$ErrorActionPreference = 'Stop'
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('ss-m3-compose-' + [guid]::NewGuid().ToString('N'))
$before = Join-Path $scratch 'evidence/compose-before'
$current = Join-Path $scratch 'current'
$results = [Collections.Generic.List[object]]::new()
function Check($name, $expected) {
    $accepted = $true
    try { & "$scratch/verify-compose-move.ps1" -Current $current | Out-Null } catch { $accepted = $false }
    $results.Add([pscustomobject]@{Name=$name;Expected=$expected;Accepted=$accepted})
}
try {
    New-Item -ItemType Directory -Force -Path $before,$current | Out-Null
    Copy-Item "$PSScriptRoot/verify-compose-move.ps1" $scratch
    Copy-Item "$PSScriptRoot/evidence/compose-before/*" $before
    Copy-Item "$before/app_debug-*" $current
    $classPath = Join-Path $current 'app_debug-classes.txt'
    $classes = (Get-Content $classPath -Raw) -replace "`r`n", "`n"
    $removed = 'DataModule|DatabaseModule|GuardianKeys|SettingsKeys|RoomRiskEventStore|RiskEventEntity|SeniorShieldDatabase|GuardianRepositoryImpl|RiskRepositoryImpl|SettingsRepositoryImpl'
    $classes = [regex]::Replace($classes, "(?ms)^(stable|unstable|runtime) class ($removed) \{\n.*?^\}(?:\n|`$)", '')
    [IO.File]::WriteAllText($classPath, $classes)
    $modulePath = Join-Path $current 'app_debug-module.json'
    $metrics = Get-Content $modulePath -Raw | ConvertFrom-Json
    $metrics.inferredStableClasses -= 5
    $metrics.inferredUnstableClasses -= 5
    $metrics.effectivelyStableClasses -= 5
    $metrics.totalClasses -= 10
    $validMetrics = $metrics | ConvertTo-Json
    [IO.File]::WriteAllText($modulePath, $validMetrics)
    Check 'Exact approved projection' $true
    [IO.File]::WriteAllText($classPath, ($classes + "`nunknown class Extra {}`n"))
    Check 'Unparsed class content' $false
    [IO.File]::WriteAllText($classPath, $classes)
    $hashPath = Join-Path $before 'sha256.json'
    $hashes = Get-Content $hashPath -Raw
    $duplicate = @($hashes | ConvertFrom-Json)[0]
    @($duplicate,$duplicate,$duplicate,$duplicate) | ConvertTo-Json | Set-Content $hashPath
    Check 'Duplicate frozen inventory' $false
    [IO.File]::WriteAllText($hashPath, $hashes)
    $metrics.skippableComposables += 1
    $metrics | ConvertTo-Json | Set-Content $modulePath
    Check 'Changed composable metric' $false
    [IO.File]::WriteAllText($modulePath, $validMetrics)
    [IO.File]::WriteAllText($modulePath, ($validMetrics -replace '\}\s*$', ', "unexpected": 0 }'))
    Check 'Added metric key' $false
    [IO.File]::WriteAllText($modulePath, $validMetrics)
    Add-Content "$current/app_debug-composables.csv" 'unexpected'
    Check 'Changed composables CSV' $false
    $results | Format-Table -AutoSize
    if (@($results | Where-Object { $_.Expected -ne $_.Accepted }).Count) { throw 'Compose probes failed.' }
    "PASS: $($results.Count) Compose probes (synthetic projection; not a production build claim)"
} finally {
    $resolved = [IO.Path]::GetFullPath($scratch)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
