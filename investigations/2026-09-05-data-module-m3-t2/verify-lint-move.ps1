param(
    [string]$AppCurrent,
    [string]$DataCurrent,
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($AppCurrent) -or [string]::IsNullOrWhiteSpace($DataCurrent)) { throw 'Pass explicit fresh app and data reports.' }
. "$PSScriptRoot/lint-records.ps1"
$baseline = @(Read-LintRecords "$PSScriptRoot/../2026-09-05-domain-contracts-m2-t2/evidence/lint/baseline-lint.sanitized.xml" $RepositoryRoot)
if ($baseline.Count -ne 72 -or (Get-LintFingerprint $baseline.Key) -cne '8F301A319E9158B66072DAD70DEB4E72BDB3D08F2C9076B4E5ADAC636F3ACFCD') { throw 'Frozen baseline changed.' }
$removed = @($baseline | Where-Object {
    $_.File -ceq 'app\build.gradle.kts' -and $_.Declaration -ceq '    kapt("androidx.room:room-compiler:2.6.1")' -and
    $_.Severity -ceq 'Warning' -and $_.Id -cin @('GradleDependency','KaptUsageInsteadOfKsp','UseTomlInstead')
})
if ($removed.Count -ne 3 -or @($removed.Id | Sort-Object -Unique).Count -ne 3) { throw 'Room processor removal inventory changed.' }
$retained = @($baseline | Where-Object { $removed.Key -cnotcontains $_.Key })
if ($retained.Count -ne 69) { throw 'Expected 69 retained diagnostics.' }
$additions = @(Get-Content "$PSScriptRoot/evidence/app-lint-additions.json" -Raw | ConvertFrom-Json)
$dataExpected = @(Get-Content "$PSScriptRoot/evidence/data-lint-records.json" -Raw | ConvertFrom-Json)
if ($additions.Count -ne 5 -or (Get-LintFingerprint $additions.Key) -cne 'E59915627B661B55896310A6B4D9B4E2C36B3695DED44E3CA14261B89DAF92BA') { throw 'Approved app addition inventory changed.' }
if ($dataExpected.Count -ne 14 -or (Get-LintFingerprint $dataExpected.Key) -cne '8B8B8DBDD93425F073D1D928C27B5F9F29348173BD758F8C0E5F1A11821DBD32') { throw 'Approved data inventory changed.' }
$app = @(Read-LintRecords $AppCurrent $RepositoryRoot)
$data = @(Read-LintRecords $DataCurrent $RepositoryRoot)
$expectedAppKeys = @($retained.Key) + @($additions.Key)
if ($app.Count -ne 74 -or (Get-LintFingerprint $app.Key) -cne (Get-LintFingerprint $expectedAppKeys)) { throw 'App diagnostics differ from exact approved multiset.' }
if ($data.Count -ne 14 -or (Get-LintFingerprint $data.Key) -cne (Get-LintFingerprint $dataExpected.Key)) { throw 'Data diagnostics differ from exact approved multiset.' }
[pscustomobject]@{Verdict='Exact approved lint projection PASS';AppErrors=5;AppWarnings=69;RetainedBaselineIssues=69;RoomProcessorWarningsMoved=3;ExactTestDeclarationWarnings=5;DataWarnings=14;NewSourceOrManifestIssues=0;AppFingerprint=Get-LintFingerprint $app.Key;DataFingerprint=Get-LintFingerprint $data.Key}
