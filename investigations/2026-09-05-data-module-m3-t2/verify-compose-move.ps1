param([Parameter(Mandatory)][string]$Current)
$ErrorActionPreference = 'Stop'
$before = Join-Path $PSScriptRoot 'evidence/compose-before'
function Assert($condition, $message) { if (-not $condition) { throw $message } }
function Read-Text($path) { ((Get-Content -LiteralPath $path -Raw) -replace "`r`n", "`n").TrimEnd("`n") + "`n" }
function Blocks($path) {
    $raw = Read-Text $path
    $matches = [regex]::Matches($raw, '(?ms)^(stable|unstable|runtime) class ([^\s{]+) \{\n.*?^\}(?:\n|$)')
    Assert ($matches.Count -eq [regex]::Matches($raw, '(?m)^(stable|unstable|runtime) class ').Count) 'Unparsed class header'
    $residual = [regex]::Replace($raw, '(?ms)^(stable|unstable|runtime) class ([^\s{]+) \{\n.*?^\}(?:\n|$)', '')
    Assert ([string]::IsNullOrWhiteSpace($residual)) 'Unparsed class report content'
    $result = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($match in $matches) { $result.Add($match.Groups[2].Value, $match.Value.TrimEnd("`n") + "`n") }
    return ,$result
}
function Metrics($path) {
    $raw = Read-Text $path
    $json = $raw | ConvertFrom-Json
    $properties = @($json.PSObject.Properties)
    $matches = [regex]::Matches($raw, '(?m)^\s*"([^"]+)"\s*:\s*(-?\d+)\s*,?\s*$')
    Assert ($matches.Count -eq $properties.Count) 'Duplicate or non-integer metrics'
    $result = [Collections.Generic.Dictionary[string,long]]::new([StringComparer]::Ordinal)
    foreach ($match in $matches) {
        $key = $match.Groups[1].Value
        $parsed = $json.PSObject.Properties[$key].Value
        Assert (($parsed -is [int]) -or ($parsed -is [long])) "Non-integer metric $key"
        Assert ([long]$parsed -eq [long]$match.Groups[2].Value) "Parsed metric mismatch $key"
        $result.Add($key, [long]$parsed)
    }
    return ,$result
}
$frozen = Get-Content (Join-Path $before 'sha256.json') -Raw | ConvertFrom-Json
Assert (@($frozen).Count -eq 4) 'Expected four frozen reports'
$expectedNames = @('app_debug-module.json','app_debug-classes.txt','app_debug-composables.csv','app_debug-composables.txt')
$names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($entry in $frozen) {
    Assert ($expectedNames -ccontains $entry.Name) "Unexpected frozen report name $($entry.Name)"
    Assert ($names.Add($entry.Name)) "Duplicate frozen report name $($entry.Name)"
}
foreach ($file in $frozen) {
    Assert ((Get-FileHash (Join-Path $before $file.Name)).Hash -ceq $file.Sha256) "Frozen report changed: $($file.Name)"
    Assert (Test-Path (Join-Path $Current $file.Name)) "Current report missing: $($file.Name)"
}
foreach ($file in 'app_debug-composables.csv','app_debug-composables.txt') {
    Assert ((Get-FileHash (Join-Path $before $file)).Hash -ceq (Get-FileHash (Join-Path $Current $file)).Hash) "Composable report changed: $file"
}
$a = Blocks (Join-Path $before 'app_debug-classes.txt')
$b = Blocks (Join-Path $Current 'app_debug-classes.txt')
$removed = @('DataModule','DatabaseModule','GuardianKeys','SettingsKeys','RoomRiskEventStore','RiskEventEntity','SeniorShieldDatabase','GuardianRepositoryImpl','RiskRepositoryImpl','SettingsRepositoryImpl')
Assert ($a.Count -eq 86 -and $b.Count -eq 76) 'Unexpected class counts'
foreach ($name in $removed) { Assert ($a.ContainsKey($name) -and -not $b.ContainsKey($name)) "Expected removed class $name" }
foreach ($name in $b.Keys) {
    Assert ($a.ContainsKey($name)) "Added class $name"
    Assert ($a[$name] -ceq $b[$name]) "Changed common class $name"
}
$am = Metrics (Join-Path $before 'app_debug-module.json')
$bm = Metrics (Join-Path $Current 'app_debug-module.json')
Assert ($am.Count -eq $bm.Count -and $am.Count -eq 23) 'Metric key count changed'
$delta = @{inferredStableClasses=-5;inferredUnstableClasses=-5;effectivelyStableClasses=-5;totalClasses=-10}
foreach ($key in $am.Keys) {
    Assert ($bm.ContainsKey($key)) "Metric key missing $key"
    $change = if ($delta.ContainsKey($key)) { $delta[$key] } else { 0 }
    Assert ($bm[$key] -eq ($am[$key] + $change)) "Unexpected metric delta $key"
}
[pscustomobject]@{RemovedClasses=10;UnchangedClasses=76;AddedClasses=0;Composables='Exact';Metrics='Exact approved class-count deltas'}
