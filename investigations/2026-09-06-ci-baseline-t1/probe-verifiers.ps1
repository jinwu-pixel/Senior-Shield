#!/usr/bin/env pwsh
# Mutation probes for the CI verifiers (.github/scripts). Copies the fresh serial-gate outputs
# from the repository into a scratch root, mutates exactly one thing per case, and asserts that
# each verifier PASSES on faithful input and FAILS on every mutation. The schema-drift script is
# probed inside a throwaway git repository so the real working tree is never touched.
# Run after the serial gate (needs test-results and lint reports). Exit 1 if any probe is unexpected.
[CmdletBinding()]
param(
    [string]$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')),
    [string]$Scratch = (Join-Path ([IO.Path]::GetTempPath()) 'seniorshield-ci-probes'),
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'

$unitScript = Join-Path $RepositoryRoot '.github/scripts/verify-unit-xml.ps1'
$lintScript = Join-Path $RepositoryRoot '.github/scripts/verify-domain-lint.ps1'
$schemaScript = Join-Path $RepositoryRoot '.github/scripts/check-schema-drift.sh'
$copyDirs = @(
    'app/build/test-results/testDebugUnitTest',
    'domain/risk/build/test-results/test',
    'domain/contracts/build/test-results/test',
    'domain/risk/build/reports',
    'domain/contracts/build/reports'
)
$results = [Collections.Generic.List[string]]::new()
$script:unexpected = 0

function New-Probe([string]$Name) {
    $root = Join-Path $Scratch "probe-$Name"
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
    foreach ($dir in $copyDirs) {
        $target = Join-Path $root $dir
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot $dir) -File | Copy-Item -Destination $target -Force
    }
    # Lint reports carry absolute locations. A faithful copy is the same report generated at the
    # probe root, so rewrite the repository prefix (both separator forms) to the probe root.
    $sourcePrefix = $RepositoryRoot.TrimEnd('\', '/')
    $probePrefix = $root.TrimEnd('\', '/')
    foreach ($report in Get-ChildItem -LiteralPath $root -Recurse -Filter 'lint-results.xml' -File) {
        $content = Get-Content -LiteralPath $report.FullName -Raw
        $rewritten = $content.Replace($sourcePrefix, $probePrefix).Replace($sourcePrefix.Replace('\', '/'), $probePrefix)
        Set-Content -LiteralPath $report.FullName -Value $rewritten -NoNewline
    }
    return $root
}

function Get-Detail([string]$Output) {
    $lines = @($Output -split "`n" | ForEach-Object { ($_ -replace '\e\[[\d;]*m', '').TrimEnd() })
    # pwsh renders a thrown message as "|"-prefixed continuation lines (wrapped); join them all so the
    # leading part of the message (which field mismatched) survives truncation.
    $messages = @($lines | Where-Object { $_ -match '^\s*\|\s*\S' -and $_ -notmatch '^\s*\|\s*~' -and $_ -notmatch '^\s*\|\s*\d+ \|' } | ForEach-Object { ($_ -replace '^\s*\|\s*', '').Trim() })
    $detail = if ($messages.Count -gt 0) { $messages -join ' ' } else { @($lines | Where-Object { $_.Trim() })[-1] }
    if ($null -eq $detail) { $detail = '' }
    return $detail.Substring(0, [Math]::Min(170, $detail.Length))
}

function Record([string]$Label, [bool]$Passed, [bool]$ExpectPass, [string]$Detail) {
    $verdict = if ($Passed -eq $ExpectPass) { 'PROBE OK        ' } else { $script:unexpected++; 'PROBE UNEXPECTED' }
    $line = "$verdict | $Label | verifier=$(if ($Passed) { 'pass' } else { 'fail' }) expected=$(if ($ExpectPass) { 'pass' } else { 'fail' }) | $Detail"
    $results.Add($line)
    Write-Output $line
}

function Test-Pwsh([string]$Label, [string]$Script, [string]$Root, [bool]$ExpectPass) {
    $output = (& pwsh -NoProfile -File $Script -RepositoryRoot $Root 2>&1 | Out-String)
    Record $Label ($LASTEXITCODE -eq 0) $ExpectPass (Get-Detail $output)
}

function Edit-Contracts([string]$Root, [scriptblock]$Mutate) {
    $path = Join-Path $Root 'domain/contracts/build/reports/lint-results.xml'
    $content = Get-Content -LiteralPath $path -Raw
    $mutated = [string](& $Mutate $content)
    if ($mutated -ceq $content) { throw "Probe mutation had no effect on $path" }
    Set-Content -LiteralPath $path -Value $mutated -NoNewline
}

function Edit-Xml([string]$Path, [string]$Pattern, [string]$Replacement) {
    $content = Get-Content -LiteralPath $Path -Raw
    $mutated = $content -replace $Pattern, $Replacement
    if ($mutated -ceq $content) { throw "Probe mutation had no effect on $Path" }
    Set-Content -LiteralPath $Path -Value $mutated -NoNewline
}

# ---------- unit test XML verifier ----------
$p = New-Probe 'unit-valid'
Test-Pwsh 'unit: faithful copy' $unitScript $p $true
$p = New-Probe 'unit-skipped'
$f = Get-ChildItem (Join-Path $p 'app/build/test-results/testDebugUnitTest') -Filter 'TEST-*.xml' | Select-Object -First 1
Edit-Xml $f.FullName 'skipped="0"' 'skipped="1"'
Test-Pwsh 'unit: one app suite skipped=1' $unitScript $p $false
$p = New-Probe 'unit-removed'
$f = Get-ChildItem (Join-Path $p 'app/build/test-results/testDebugUnitTest') -Filter 'TEST-*.xml' | Select-Object -First 1
Remove-Item -LiteralPath $f.FullName
Test-Pwsh 'unit: one app suite file removed (below floor 544)' $unitScript $p $false
$p = New-Probe 'unit-failure'
$f = Get-ChildItem (Join-Path $p 'domain/risk/build/test-results/test') -Filter 'TEST-*.xml' | Select-Object -First 1
Edit-Xml $f.FullName 'failures="0"' 'failures="1"'
Test-Pwsh 'unit: risk failures=1' $unitScript $p $false
$p = New-Probe 'unit-errors'
$f = Get-ChildItem (Join-Path $p 'domain/contracts/build/test-results/test') -Filter 'TEST-*.xml' | Select-Object -First 1
Edit-Xml $f.FullName 'errors="0"' 'errors="1"'
Test-Pwsh 'unit: contracts errors=1' $unitScript $p $false
$p = New-Probe 'unit-missing-dir'
Remove-Item -LiteralPath (Join-Path $p 'domain/contracts/build/test-results') -Recurse -Force
Test-Pwsh 'unit: contracts results dir missing' $unitScript $p $false

# ---------- domain lint verifier ----------
$p = New-Probe 'lint-valid'
Test-Pwsh 'domain lint: faithful copy' $lintScript $p $true
$p = New-Probe 'lint-latest-drift'
Edit-Contracts $p { param($c) $c -replace '(is available: )[^"\s]+', '${1}9.9.9' }
Test-Pwsh 'domain lint: only the latest-version metadata changes (normalized, must pass)' $lintScript $p $true
$p = New-Probe 'lint-risk-issue'
Copy-Item (Join-Path $p 'domain/contracts/build/reports/lint-results.xml') (Join-Path $p 'domain/risk/build/reports/lint-results.xml') -Force
Test-Pwsh 'domain lint: risk gains 1 issue' $lintScript $p $false
$p = New-Probe 'lint-contracts-none'
Copy-Item (Join-Path $p 'domain/risk/build/reports/lint-results.xml') (Join-Path $p 'domain/contracts/build/reports/lint-results.xml') -Force
Test-Pwsh 'domain lint: contracts approved warning disappears' $lintScript $p $false
$p = New-Probe 'lint-contracts-error'
Edit-Contracts $p { param($c) $c -replace 'severity="Warning"', 'severity="Error"' }
Test-Pwsh 'domain lint: contracts warning escalated to Error' $lintScript $p $false
$p = New-Probe 'lint-contracts-dup'
Edit-Contracts $p { param($c) $c -replace '(?s)(<issue\b.*?</issue>)', '$1$1' }
Test-Pwsh 'domain lint: contracts warning duplicated (2 issues)' $lintScript $p $false
$p = New-Probe 'lint-contracts-otherid'
Edit-Contracts $p { param($c) $c -replace 'id="GradleDependency"', 'id="UseTomlInstead"' }
Test-Pwsh 'domain lint: contracts warning id changed' $lintScript $p $false
$p = New-Probe 'lint-other-library'
Edit-Contracts $p { param($c) ($c -replace 'org\.jetbrains\.kotlinx:kotlinx-coroutines-core than 1\.8\.1', 'junit:junit than 4.13.2') -replace 'api\(&quot;org\.jetbrains\.kotlinx:kotlinx-coroutines-core:1\.8\.1&quot;\)', 'testImplementation(&quot;junit:junit:4.13.2&quot;)' }
Test-Pwsh 'domain lint: same id/severity/file but a different library warning' $lintScript $p $false
$p = New-Probe 'lint-declaration-only'
Edit-Contracts $p { param($c) $c -replace 'errorLine1="    api\(', 'errorLine1="    implementation(' }
Test-Pwsh 'domain lint: declaration (errorLine1) changed only' $lintScript $p $false
$p = New-Probe 'lint-message-only'
Edit-Contracts $p { param($c) $c -replace 'kotlinx-coroutines-core than 1\.8\.1', 'kotlinx-coroutines-core than 1.7.3' }
Test-Pwsh 'domain lint: message pinned version changed only' $lintScript $p $false
$p = New-Probe 'lint-extra-location'
Edit-Contracts $p { param($c) $c -replace '(?s)(<location\b[^>]*/>)', '$1$1' }
Test-Pwsh 'domain lint: second location appended' $lintScript $p $false
$p = New-Probe 'lint-path-moved'
Edit-Contracts $p { param($c) $c -replace 'domain([\\/])contracts([\\/])build\.gradle\.kts', 'domain$1contracts$2src$2build.gradle.kts' }
Test-Pwsh 'domain lint: same file name under a different directory' $lintScript $p $false
$p = New-Probe 'lint-path-outside'
Edit-Contracts $p { param($c) $c -replace 'file="[^"]*build\.gradle\.kts"', 'file="/elsewhere/domain/contracts/build.gradle.kts"' }
Test-Pwsh 'domain lint: location outside the repository root' $lintScript $p $false
$p = New-Probe 'lint-report-missing'
Remove-Item -LiteralPath (Join-Path $p 'domain/risk/build/reports/lint-results.xml')
Test-Pwsh 'domain lint: risk report missing' $lintScript $p $false

# ---------- schema drift script (throwaway git repository) ----------
function Test-Schema([string]$Label, [string]$Repo, [bool]$ExpectPass) {
    Push-Location -LiteralPath $Repo
    try {
        $output = (& bash $schemaScript 'data/schemas' 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    Record $Label ($code -eq 0) $ExpectPass (Get-Detail $output)
}
$repo = Join-Path $Scratch 'schema-repo'
if (Test-Path -LiteralPath $repo) { Remove-Item -LiteralPath $repo -Recurse -Force }
$schemaDir = Join-Path $repo 'data/schemas/com.example.Db'
New-Item -ItemType Directory -Force -Path $schemaDir | Out-Null
Set-Content -LiteralPath (Join-Path $schemaDir '1.json') -Value '{"formatVersion":1,"database":{"version":1}}' -NoNewline
& git -C $repo init -q
& git -C $repo -c user.name=probe -c user.email=probe@example.invalid -c commit.gpgsign=false add -- data/schemas
& git -C $repo -c user.name=probe -c user.email=probe@example.invalid -c commit.gpgsign=false commit -q -m 'schema baseline'
if ($LASTEXITCODE -ne 0) { throw 'Probe repository setup failed.' }
Test-Schema 'schema drift: committed tree clean' $repo $true
Set-Content -LiteralPath (Join-Path $schemaDir '2.json') -Value '{"formatVersion":1,"database":{"version":2}}' -NoNewline
Test-Schema 'schema drift: new UNTRACKED schema JSON (version bump not committed)' $repo $false
Remove-Item -LiteralPath (Join-Path $schemaDir '2.json')
Set-Content -LiteralPath (Join-Path $schemaDir '1.json') -Value '{"formatVersion":1,"database":{"version":1,"entities":[]}}' -NoNewline
Test-Schema 'schema drift: tracked schema JSON modified' $repo $false
& git -C $repo checkout -q -- data/schemas
Remove-Item -LiteralPath (Join-Path $schemaDir '1.json')
Test-Schema 'schema drift: tracked schema JSON deleted' $repo $false
& git -C $repo checkout -q -- data/schemas
Test-Schema 'schema drift: restored tree clean again' $repo $true

# ---------- summary ----------
$summary = "probes=$($results.Count) ok=$($results.Count - $script:unexpected) unexpected=$script:unexpected"
$results.Add($summary)
Write-Output $summary
if ($EvidenceOut) {
    $parent = Split-Path -Parent $EvidenceOut
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Set-Content -LiteralPath $EvidenceOut -Value ($results -join "`n") -Encoding utf8
}
if ($script:unexpected -ne 0) { exit 1 }
