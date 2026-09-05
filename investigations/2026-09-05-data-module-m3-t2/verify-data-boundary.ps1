param([string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')))
$ErrorActionPreference = 'Stop'
function Assert($condition, $message) { if (-not $condition) { throw $message } }
$frozen = Get-Content "$PSScriptRoot/evidence/source-before-sha256.json" -Raw | ConvertFrom-Json
Assert (@($frozen).Count -eq 12) 'Expected twelve source files'
$relative = @(
    'di/DataModule.kt','di/DatabaseModule.kt',
    'local/GuardianDataStore.kt','local/SettingsDataStore.kt','local/LiveRiskEventStore.kt','local/RoomRiskEventStore.kt',
    'local/db/RiskEventDao.kt','local/db/RiskEventEntity.kt','local/db/SeniorShieldDatabase.kt',
    'repository/GuardianRepositoryImpl.kt','repository/SettingsRepositoryImpl.kt','repository/RiskRepositoryImpl.kt'
)
$oldPaths = @($relative | ForEach-Object { 'app/src/main/java/com/example/seniorshield/data/' + $_ })
$newPaths = @($oldPaths | ForEach-Object { $_ -creplace '^app/src/', 'data/src/' })
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($source in $frozen) {
    Assert ($oldPaths -ccontains $source.Path) "Unexpected frozen source path: $($source.Path)"
    Assert ($seen.Add($source.Path)) "Duplicate frozen source path: $($source.Path)"
    $old = Join-Path $RepositoryRoot $source.Path
    $new = Join-Path $RepositoryRoot ($source.Path -creplace '^app/src/', 'data/src/')
    Assert (-not (Test-Path -LiteralPath $old)) "Old source retained: $($source.Path)"
    Assert ((Get-FileHash -LiteralPath $new).Hash -ceq $source.Sha256) "Source content changed: $($source.Path)"
}
$sources = @(Get-ChildItem (Join-Path $RepositoryRoot 'data/src') -Recurse -File | Where-Object { $_.Extension -in @('.kt','.java') })
Assert ($sources.Count -eq 12) 'Unexpected production data source count'
foreach ($source in $sources) {
    $path = $source.FullName.Substring($RepositoryRoot.TrimEnd('\','/').Length + 1).Replace('\','/')
    Assert ($newPaths -ccontains $path) "Unexpected data source path: $path"
}
$schemaRelative = 'schemas/com.example.seniorshield.data.local.db.SeniorShieldDatabase/1.json'
Assert (-not (Test-Path (Join-Path $RepositoryRoot "app/$schemaRelative"))) 'Old schema retained'
$schema = Join-Path $RepositoryRoot "data/$schemaRelative"
Assert ((Get-FileHash $schema).Hash -ceq '07F86105728D156F454253C5A252E5E7D5B2FEFB6E9819216BBC1AD5A31F1E46') 'Schema bytes changed'
$json = Get-Content $schema -Raw | ConvertFrom-Json
Assert ($json.database.version -eq 1 -and $json.database.identityHash -ceq 'bec47e2ef0393e24083a677a42dcbf74') 'Schema identity changed'
$baselineAbi = Join-Path $PSScriptRoot 'evidence/abi-before/public-abi-without-compose-field.txt'
$currentAbi = Join-Path $PSScriptRoot 'evidence/abi-after/public-abi-without-compose-field.txt'
Assert ((Get-FileHash $baselineAbi).Hash -ceq (Get-FileHash $currentAbi).Hash) 'Public ABI changed beyond exact Compose field removal'
[xml]$before = Get-Content "$PSScriptRoot/evidence/manifest-before.xml"
[xml]$after = Get-Content (Join-Path $RepositoryRoot 'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml')
Assert ($before.OuterXml -ceq $after.OuterXml) 'Merged app manifest changed'
$reverse = @($sources | Select-String -Pattern '^import com\.example\.seniorshield\.(core|feature|monitoring)\.')
Assert ($reverse.Count -eq 0) 'Data source imports app layer'
foreach ($path in 'data/build.gradle.kts','domain/risk/build.gradle.kts','domain/contracts/build.gradle.kts') {
    $text = Get-Content (Join-Path $RepositoryRoot $path) -Raw
    Assert ($text -notmatch 'project\("\:app"\)') "Reverse app project dependency in $path"
}
[pscustomobject]@{SourceFiles=12;SourceHashes='Exact';Schema='Exact v1';PublicAbiTypes=14;MergedManifest='Exact';ReverseAppDependency=0}
