param([Parameter(Mandatory)][string]$Serial)
$ErrorActionPreference = 'Stop'
$adb = 'C:/Users/momen/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidence = Join-Path $PSScriptRoot 'evidence'
New-Item -ItemType Directory -Force $evidence | Out-Null
$packages = @('com.example.seniorshield','com.example.seniorshield.test')
$permissions = @('android.permission.READ_PHONE_STATE','android.permission.POST_NOTIFICATIONS')
$runner = 'com.example.seniorshield.test.M3TestRunner'
function Invoke-Adb([string[]]$Arguments) {
    $result = @(& $adb -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' '): $($result -join ' ')" }
    return $result
}
function Has-Package([string]$Package) {
    $result = Invoke-Adb @('shell','pm','list','packages','-u',$Package)
    return @($result | Where-Object { $_.Trim() -ceq "package:$Package" }).Count -gt 0
}
function Assert-Permissions([bool]$Granted) {
    $dump = (Invoke-Adb @('shell','dumpsys','package',$packages[0])) -join "`n"
    foreach ($permission in $permissions) {
        $matches = [regex]::Matches($dump, '(?m)^\s*' + [regex]::Escape($permission) + ': granted=(true|false)')
        if ($matches.Count -ne 1 -or ($matches[0].Groups[1].Value -ceq 'true') -ne $Granted) { throw "Unexpected permission state: $permission" }
    }
}
function Run-Instrumentation([string]$Phase,[int]$Expected,[bool]$SmokeOnly) {
    $arguments = @('shell','am','instrument','-w','-r')
    if ($SmokeOnly) { $arguments += @('-e','class','com.example.seniorshield.permission.PermissionDeviceSmokeTest') }
    $arguments += "com.example.seniorshield.test/$runner"
    $raw = (Invoke-Adb $arguments) -join "`n"
    [IO.File]::WriteAllText((Join-Path $evidence "$Phase-instrumentation.txt"), $raw + "`n")
    $starts = [regex]::Matches($raw,'(?m)^INSTRUMENTATION_STATUS_CODE: 1\s*$').Count
    $passes = [regex]::Matches($raw,'(?m)^INSTRUMENTATION_STATUS_CODE: 0\s*$').Count
    if ($starts -ne $Expected -or $passes -ne $Expected -or $raw -notmatch "OK \($Expected tests\)" -or $raw -match 'FAILURES|INSTRUMENTATION_FAILED') { throw "Instrumentation failed: $Phase starts=$starts passes=$passes" }
    & "$PSScriptRoot/verify-instrumentation.ps1" -Report (Join-Path $evidence "$Phase-instrumentation.txt") -SmokeOnly:$SmokeOnly
    Write-Output "$Phase PASS $passes/$Expected"
}
$model = (Invoke-Adb @('shell','getprop','ro.product.model')) -join ''
$sdk = (Invoke-Adb @('shell','getprop','ro.build.version.sdk')) -join ''
if ($model.Trim() -cne 'AT-M150' -or $sdk.Trim() -cne '34') { throw 'Device identity/API changed.' }
$features = Invoke-Adb @('shell','pm','list','features')
if ($features -cnotcontains 'feature:android.hardware.telephony') { throw 'Telephony feature missing.' }
if (((Invoke-Adb @('shell','am','get-current-user')) -join '').Trim() -cne '0') { throw 'Unexpected active user; do not modify another profile.' }
foreach ($package in $packages) { if (Has-Package $package) { throw "Existing package/data registration: $package; refuse overwrite." } }
[xml]$testManifest = Get-Content "$root/app/build/intermediates/packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml"
$ns = 'http://schemas.android.com/apk/res/android'
if ($testManifest.manifest.package -cne $packages[1] -or $testManifest.manifest.instrumentation.GetAttribute('name',$ns) -cne $runner -or $testManifest.manifest.instrumentation.GetAttribute('targetPackage',$ns) -cne $packages[0]) { throw 'APK instrumentation identity mismatch.' }
$runnerSource = Get-Content "$root/app/src/androidTest/java/com/example/seniorshield/test/M3TestRunner.kt" -Raw
if ($runnerSource -notmatch 'super.newApplication\(cl, HiltTestApplication::class.java.name, context\)') { throw 'Test Application safety contract changed.' }
$attempted = [Collections.Generic.List[string]]::new()
$cleanupErrors = [Collections.Generic.List[string]]::new()
$phases = [Collections.Generic.List[string]]::new()
try {
    $apks = @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    for ($i = 0; $i -lt $packages.Count; $i++) {
        $attempted.Add($packages[$i])
        $install = Invoke-Adb @('install',(Join-Path $root $apks[$i]))
        if ($install -cnotcontains 'Success') { throw 'Install did not report Success.' }
    }
    Assert-Permissions $false
    $phases.Add('Initial READ_PHONE_STATE/POST_NOTIFICATIONS denied verified')
    Run-Instrumentation 'device-initial' 17 $false
    foreach ($permission in $permissions) { Invoke-Adb @('shell','pm','grant',$packages[0],$permission) | Out-Null }
    Assert-Permissions $true
    $phases.Add('Both runtime permissions granted; actual system state verified')
    foreach ($permission in $permissions) { Invoke-Adb @('shell','pm','revoke',$packages[0],$permission) | Out-Null }
    Assert-Permissions $false
    $phases.Add('Both runtime permissions revoked; actual system state verified')
    Run-Instrumentation 'device-after-revoke' 3 $true
    $services = (Invoke-Adb @('shell','dumpsys','activity','services',$packages[0])) -join "`n"
    if ($services -match 'ServiceRecord.*com\.example\.seniorshield') { throw 'Unexpected production service running.' }
    $phases.Add('No target ServiceRecord after test execution')
} finally {
    foreach ($package in @('com.example.seniorshield.test','com.example.seniorshield')) {
        if ($attempted.Contains($package)) {
            try {
                if (Has-Package $package) {
                    $removed = Invoke-Adb @('uninstall',$package)
                    if ($removed -cnotcontains 'Success') { throw 'Uninstall did not report Success.' }
                }
                if (Has-Package $package) { throw 'Package still registered after cleanup.' }
            } catch { $cleanupErrors.Add("${package}: $_") }
        }
    }
    [pscustomobject]@{Model=$model.Trim();SDK=$sdk.Trim();Telephony=$true;TargetAndTestAbsentBefore=$true;Phases=@($phases.ToArray());AttemptedPackages=@($attempted.ToArray());CleanupErrors=@($cleanupErrors.ToArray());CleanupPass=($cleanupErrors.Count -eq 0)} | ConvertTo-Json -Depth 5 | Set-Content "$evidence/device-run.json"
    if ($cleanupErrors.Count -gt 0) { throw ($cleanupErrors -join '; ') }
}
'Physical device verification and cleanup PASS'
