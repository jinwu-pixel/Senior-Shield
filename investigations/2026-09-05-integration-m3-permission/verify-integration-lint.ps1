param(
    [Parameter(Mandatory)][string]$AppCurrent,
    [Parameter(Mandatory)][string]$DataCurrent,
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
)
$ErrorActionPreference = 'Stop'
$m3 = Join-Path $PSScriptRoot '../2026-09-05-data-module-m3-t2'
. "$m3/lint-records.ps1"
$baseline = @(Read-LintRecords "$PSScriptRoot/../2026-09-05-domain-contracts-m2-t2/evidence/lint/baseline-lint.sanitized.xml" $RepositoryRoot)
if ($baseline.Count -ne 72 -or (Get-LintFingerprint $baseline.Key) -cne '8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD') { throw 'Frozen baseline changed.' }
$approvedErrors = @(
    'MissingPermission|app\src\main\java\com\example\seniorshield\core\overlay\BankingCooldownManager.kt',
    'MissingPermission|app\src\main\java\com\example\seniorshield\core\util\CallEndHelper.kt',
    'MissingPermission|app\src\main\java\com\example\seniorshield\core\notification\RiskNotificationManager.kt',
    'MissingPermission|app\src\main\java\com\example\seniorshield\core\overlay\RiskOverlayManager.kt',
    'PermissionImpliesUnsupportedChromeOsHardware|app\src\main\AndroidManifest.xml'
)
$errors = @($baseline | Where-Object Severity -ceq 'Error')
$errorKeys = @($errors | ForEach-Object { $_.Id + '|' + $_.File })
if ($errors.Count -ne 5 -or (Get-LintFingerprint $errorKeys) -cne (Get-LintFingerprint $approvedErrors)) { throw 'Permission error inventory changed.' }
$room = @($baseline | Where-Object {
    $_.File -ceq 'app\build.gradle.kts' -and $_.Declaration -ceq '    kapt("androidx.room:room-compiler:2.6.1")' -and
    $_.Severity -ceq 'Warning' -and $_.Id -cin @('GradleDependency','KaptUsageInsteadOfKsp','UseTomlInstead')
})
if ($room.Count -ne 3 -or @($room.Id | Sort-Object -Unique).Count -ne 3) { throw 'Room warning inventory changed.' }
$retained = @($baseline | Where-Object { $_.Severity -ceq 'Warning' -and $room.Key -cnotcontains $_.Key })
$additions = @(Get-Content "$m3/evidence/app-lint-additions.json" -Raw | ConvertFrom-Json)
$dataExpected = @(Get-Content "$m3/evidence/data-lint-records.json" -Raw | ConvertFrom-Json)
if ($retained.Count -ne 64) { throw 'Expected 64 retained warnings.' }
if ($additions.Count -ne 5 -or (Get-LintFingerprint $additions.Key) -cne 'E59915627B661B55896310A6B4D9B4E2C36B3695DED44E3CA14261B89DAF92BA') { throw 'Frozen app additions changed.' }
if ($dataExpected.Count -ne 14 -or (Get-LintFingerprint $dataExpected.Key) -cne '8B8B8DBDD93425F073D1D928C27B5F9F29348173BD758F8C0E5F1A11821DBD32') { throw 'Frozen data warnings changed.' }
$app = @(Read-LintRecords $AppCurrent $RepositoryRoot)
$data = @(Read-LintRecords $DataCurrent $RepositoryRoot)
$expectedKeys = @($retained.Key) + @($additions.Key)
if ($app.Count -ne 69 -or (Get-LintFingerprint $app.Key) -cne (Get-LintFingerprint $expectedKeys)) { throw 'App exact lint union failed.' }
if ($data.Count -ne 14 -or (Get-LintFingerprint $data.Key) -cne (Get-LintFingerprint $dataExpected.Key)) { throw 'Data exact lint union failed.' }
[pscustomobject]@{Verdict='PASS';AppErrors=0;AppWarnings=69;DataErrors=0;DataWarnings=14;AppFingerprint=Get-LintFingerprint $app.Key;DataFingerprint=Get-LintFingerprint $data.Key}
