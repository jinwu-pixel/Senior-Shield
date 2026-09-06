$ErrorActionPreference = 'Stop'
$temp = Join-Path $PSScriptRoot '../../.superpowers/integration/instrumentation-probes'
New-Item -ItemType Directory -Force $temp | Out-Null
$original = Get-Content "$PSScriptRoot/evidence/device-initial-instrumentation.txt" -Raw
$passed = 0
foreach ($case in @('valid','wrong-name','duplicate-name','skip','missing-start','missing-completion')) {
    $raw = $original
    switch ($case) {
        'wrong-name' { $raw = $raw.Replace('deniedPhonePermissionReturnsNoCall','unexpectedTest') }
        'duplicate-name' { $raw = $raw.Replace('deniedPhonePermissionReturnsNoCall','deniedPhonePermissionDoesNotRequestCallScreen') }
        'skip' { $raw = [regex]::new('INSTRUMENTATION_STATUS_CODE: 0').Replace($raw,'INSTRUMENTATION_STATUS_CODE: -3',1) }
        'missing-start' { $raw = [regex]::new('INSTRUMENTATION_STATUS_CODE: 1').Replace($raw,'REMOVED_START',1) }
        'missing-completion' { $raw = $raw.Replace('OK (17 tests)','INCOMPLETE') }
    }
    $path = Join-Path $temp "$case.txt"
    [IO.File]::WriteAllText($path,$raw)
    $accepted = $true
    try { & "$PSScriptRoot/verify-instrumentation.ps1" -Report $path | Out-Null }
    catch { $accepted = $false }
    if ($accepted -ne ($case -ceq 'valid')) { throw "Unexpected instrumentation probe result: $case" }
    $passed++
}
"PASS $passed instrumentation probes"
