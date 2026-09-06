param(
    [Parameter(Mandatory)][string]$Classes,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$Javap = 'C:/Program Files/Java/jdk-21/bin/javap.exe'
)
$ErrorActionPreference = 'Stop'
$types = @(
    'di.DataModule', 'di.DatabaseModule',
    'local.GuardianDataStoreKt', 'local.GuardianKeys',
    'local.SettingsDataStoreKt', 'local.SettingsKeys',
    'local.LiveRiskEventStore', 'local.RoomRiskEventStore',
    'local.db.RiskEventDao', 'local.db.RiskEventEntity', 'local.db.SeniorShieldDatabase',
    'repository.GuardianRepositoryImpl', 'repository.SettingsRepositoryImpl', 'repository.RiskRepositoryImpl'
)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$signatures = @()
$versions = @()
foreach ($type in $types) {
    $fqcn = 'com.example.seniorshield.data.' + $type
    $classFile = Join-Path $Classes ($fqcn.Replace('.', '/') + '.class')
    if (-not (Test-Path -LiteralPath $classFile)) { throw "Missing class: $fqcn" }
    $bytes = [IO.File]::ReadAllBytes($classFile)
    $major = ([int]$bytes[6] * 256) + [int]$bytes[7]
    if ($major -ne 61) { throw "Unexpected class major ${major}: $fqcn" }
    $versions += [pscustomobject]@{Type=$fqcn;Major=$major}
    $output = @(& $Javap -public -s $classFile)
    if ($LASTEXITCODE -ne 0) { throw "javap failed: $fqcn" }
    $signatures += $output
}
# Preserve raw output. Comparison may remove only this exact Compose-generated field.
$text = ($signatures -join "`n") + "`n"
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'public-abi.txt'), $text)
$projected = $text -creplace '(?m)^  public static final int \$stable;\n    descriptor: I\n', ''
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'public-abi-without-compose-field.txt'), $projected)
$versions | ConvertTo-Json | Set-Content (Join-Path $OutputDirectory 'class-versions.json')
[pscustomobject]@{Types=$types.Count;Major=61;ProjectionSha256=(Get-FileHash (Join-Path $OutputDirectory 'public-abi-without-compose-field.txt')).Hash}
