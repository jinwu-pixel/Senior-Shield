param([string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')))
$ErrorActionPreference = 'Stop'
$m2 = '0d7b30990bf3f08eaf8d56b220e41019b9996c3a'
$m3 = '20cc9fc1504b3d5d036cb9d2ee12d58bc1bf2f96'
$permission = 'fcb2c35dbc618a9d852c2c76bec62684fcceb96a'
$expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
foreach ($line in @(git -C $RepositoryRoot ls-tree -r $m3)) {
    if ($line -cmatch '^\d+ blob ([0-9a-f]+)\t(.+)$') { $expected[$Matches[2]] = $Matches[1] }
}
if ($LASTEXITCODE -ne 0 -or $expected.Count -eq 0) { throw 'Cannot read M3 tree.' }
$changed = @(git -C $RepositoryRoot diff --name-only $m2 $permission)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read permission delta.' }
foreach ($path in $changed) {
    $blob = git -C $RepositoryRoot rev-parse "${permission}:$path"
    if ($LASTEXITCODE -ne 0) { throw "Unsupported deletion in permission delta: $path" }
    $expected[$path] = $blob
}
$records = @()
foreach ($entry in $expected.GetEnumerator()) {
    if ($entry.Key.StartsWith('investigations/')) { continue }
    if ($entry.Key -ceq 'app/build.gradle.kts') {
        $original = ((git -C $RepositoryRoot show "${m3}:app/build.gradle.kts") -join "`n") + "`n"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot read M3 app Gradle.' }
        $merged = $original.Replace('        unitTests.isReturnDefaultValues = true' + "`n", '        unitTests.isReturnDefaultValues = true' + "`n" + '        unitTests.isIncludeAndroidResources = true' + "`n").Replace('    testImplementation("io.mockk:mockk:1.13.13")' + "`n", '    testImplementation("io.mockk:mockk:1.13.13")' + "`n" + '    testImplementation(libs.robolectric)' + "`n")
        $actual = (Get-Content (Join-Path $RepositoryRoot $entry.Key) -Raw).Replace("`r`n","`n")
        if ($actual -cne $merged) { throw 'App Gradle is not the exact two approved additions to M3.' }
    } else {
        $actual = git -C $RepositoryRoot hash-object -- $entry.Key
        if ($LASTEXITCODE -ne 0 -or $actual -cne $entry.Value) { throw "Source union mismatch: $($entry.Key)" }
    }
    $records += [pscustomobject]@{Path=$entry.Key;GitBlob=(git -C $RepositoryRoot hash-object -- $entry.Key)}
}
$allowedNewTest = 'app/src/androidTest/java/com/example/seniorshield/permission/PermissionDeviceSmokeTest.kt'
$current = @(git -C $RepositoryRoot ls-files --cached --others --exclude-standard)
foreach ($path in $current) {
    if (-not $expected.ContainsKey($path) -and -not $path.StartsWith('investigations/2026-09-05-integration-m3-permission/') -and $path -cne $allowedNewTest) { throw "Unexpected new path: $path" }
}
$records += [pscustomobject]@{Path=$allowedNewTest;GitBlob=(git -C $RepositoryRoot hash-object -- $allowedNewTest)}
New-Item -ItemType Directory -Force "$PSScriptRoot/evidence" | Out-Null
$records | Sort-Object Path | ConvertTo-Json | Set-Content "$PSScriptRoot/evidence/verified-source-git-blobs.json"
"PASS exact source union: $($records.Count) tracked source/config/test/docs blobs; only app Gradle combined and permission smoke3 added"
