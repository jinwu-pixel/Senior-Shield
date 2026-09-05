param([Parameter(Mandatory)][string]$AppCurrent,[Parameter(Mandatory)][string]$DataCurrent)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$temp = Join-Path $root '.superpowers/integration/lint-probes'
New-Item -ItemType Directory -Force $temp | Out-Null
$passed = 0
foreach ($case in @('valid','missing','extra','duplicate','changed-path','extra-location','missing-data')) {
    [xml]$app = Get-Content $AppCurrent -Raw
    [xml]$data = Get-Content $DataCurrent -Raw
    $first = @($app.issues.issue)[0]
    switch ($case) {
        'missing' { [void]$app.issues.RemoveChild($first) }
        'extra' { $clone = $first.CloneNode($true); $clone.SetAttribute('id','UnexpectedIssue'); [void]$app.issues.AppendChild($clone) }
        'duplicate' { [void]$app.issues.AppendChild($first.CloneNode($true)) }
        'changed-path' { $first.location.SetAttribute('file','unexpected.kt') }
        'extra-location' { [void]$first.AppendChild($first.location.CloneNode($true)) }
        'missing-data' { [void]$data.issues.RemoveChild(@($data.issues.issue)[0]) }
    }
    $appPath = Join-Path $temp "$case-app.xml"
    $dataPath = Join-Path $temp "$case-data.xml"
    $app.Save($appPath)
    $data.Save($dataPath)
    $accepted = $true
    try { & "$PSScriptRoot/verify-integration-lint.ps1" -AppCurrent $appPath -DataCurrent $dataPath | Out-Null }
    catch { $accepted = $false }
    if ($accepted -ne ($case -ceq 'valid')) { throw "Incorrect lint probe result: $case accepted=$accepted" }
    $passed++
}
"PASS $passed integration lint probes"
