param(
    [string]$EvidenceRoot
)

$ErrorActionPreference = 'Stop'
$committedEvidence = Join-Path $PSScriptRoot 'evidence'
$p1 = Join-Path $committedEvidence 'compose-p1'

if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    $p2 = Join-Path $committedEvidence 'compose-p2'
} elseif (Test-Path -LiteralPath (Join-Path $EvidenceRoot 'app_debug-module.json')) {
    $p2 = $EvidenceRoot
} elseif (Test-Path -LiteralPath (Join-Path $EvidenceRoot 'compose-p2/app_debug-module.json')) {
    $p2 = Join-Path $EvidenceRoot 'compose-p2'
} else {
    throw "B+ failure: EvidenceRoot has no P2 report set: $EvidenceRoot"
}

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "B+ failure: $Message"
    }
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
}

function Get-StringSha256([string]$Value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        ([BitConverter]::ToString(
            $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))
        )).Replace('-', '')
    } finally {
        $sha.Dispose()
    }
}

function Read-Normalized([string]$Path) {
    ((Get-Content -LiteralPath $Path -Raw) -replace "`r`n", "`n").TrimEnd("`n") + "`n"
}

function Read-ExactModuleJson([string]$Path, [hashtable]$Expected, [string]$Label) {
    $raw = Read-Normalized $Path
    $json = $raw | ConvertFrom-Json
    $expectedNames = @($Expected.Keys)
    $jsonNames = @($json.PSObject.Properties.Name)
    $missingJsonKeys = @($expectedNames | Where-Object { -not ($jsonNames -ccontains $_) })
    $unexpectedJsonKeys = @($jsonNames | Where-Object { -not ($expectedNames -ccontains $_) })
    Assert-Condition ($missingJsonKeys.Count -eq 0) "$Label module JSON missing keys: $($missingJsonKeys -join ',')"
    Assert-Condition ($unexpectedJsonKeys.Count -eq 0) "$Label module JSON unexpected keys: $($unexpectedJsonKeys -join ',')"
    foreach ($name in $expectedNames) {
        $parsedValue = $json.PSObject.Properties[$name].Value
        $isInteger = ($parsedValue -is [int]) -or ($parsedValue -is [long])
        $parsedType = if ($null -eq $parsedValue) { 'null' } else { $parsedValue.GetType().FullName }
        Assert-Condition $isInteger "$Label module JSON $name has non-integer runtime type: $parsedType"
        Assert-Condition ([long]$parsedValue -eq [long]$Expected[$name]) "$Label parsed module JSON $name was $parsedValue, expected $($Expected[$name])"
    }

    $propertyMatches = [regex]::Matches(
        $raw,
        '(?m)^\s*"([^"]+)"\s*:\s*(-?\d+)\s*,?\s*$'
    )
    Assert-Condition ($propertyMatches.Count -eq $Expected.Count) "$Label module JSON property count was $($propertyMatches.Count), expected $($Expected.Count)"

    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $values = [Collections.Generic.Dictionary[string,long]]::new([StringComparer]::Ordinal)
    foreach ($match in $propertyMatches) {
        $name = $match.Groups[1].Value
        Assert-Condition ($seen.Add($name)) "$Label module JSON contains duplicate key: $name"
        $values.Add($name, [long]$match.Groups[2].Value)
    }

    $missing = @($expectedNames | Where-Object { -not $values.ContainsKey($_) })
    $unexpected = @($values.Keys | Where-Object { -not ($expectedNames -ccontains $_) })
    Assert-Condition ($missing.Count -eq 0) "$Label module JSON missing keys: $($missing -join ',')"
    Assert-Condition ($unexpected.Count -eq 0) "$Label module JSON unexpected keys: $($unexpected -join ',')"

    foreach ($name in $expectedNames) {
        Assert-Condition ($values[$name] -eq [long]$Expected[$name]) "$Label module JSON $name was $($values[$name]), expected $($Expected[$name])"
    }
}

function Read-ClassBlocks([string]$Path, [int]$ExpectedCount, [string]$Label) {
    $content = Read-Normalized $Path
    $headers = [regex]::Matches($content, '(?m)^(stable|unstable|runtime) class ')
    $blockMatches = [regex]::Matches(
        $content,
        '(?ms)^(stable|unstable|runtime) class ([^\s{]+) \{\n.*?^\}(?:\n|$)'
    )
    Assert-Condition ($headers.Count -eq $ExpectedCount) "$Label class header count was $($headers.Count), expected $ExpectedCount"
    Assert-Condition ($blockMatches.Count -eq $ExpectedCount) "$Label parsed class count was $($blockMatches.Count), expected $ExpectedCount"

    $blocks = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($match in $blockMatches) {
        $name = $match.Groups[2].Value
        Assert-Condition (-not $blocks.ContainsKey($name)) "$Label duplicate class name: $name"
        $blocks.Add($name, $match.Value.TrimEnd("`n") + "`n")
    }
    [pscustomobject]@{ Content = $content; Blocks = $blocks }
}

function Get-LiteralCount([string]$Value, [string]$Needle) {
    [regex]::Matches($Value, [regex]::Escape($Needle)).Count
}

function Get-GuardianCardBlock([string]$Path) {
    $content = Read-Normalized $Path
    $match = [regex]::Match(
        $content,
        '(?ms)^restartable skippable scheme\("\[androidx\.compose\.ui\.UiComposable\]"\) fun GuardianCard\(\n.*?^\)\n'
    )
    Assert-Condition $match.Success "GuardianCard restartable/skippable block missing in $Path"
    $match.Value
}

function Get-GuardianCardCsvRow([string]$Path) {
    $content = Read-Normalized $Path
    $matches = [regex]::Matches(
        $content,
        '(?m)^com\.example\.seniorshield\.feature\.guardian\.GuardianCard,GuardianCard,.*$'
    )
    Assert-Condition ($matches.Count -eq 1) "GuardianCard CSV row count was $($matches.Count) in $Path"
    $matches[0].Value + "`n"
}

$frozenP1Hashes = [ordered]@{
    'app_debug-module.json' = '08D35BD5D9D7BB371B3399AFF1CC331A6EB329DBE49D00C412560E9683AAF243'
    'app_debug-composables.csv' = 'F8D8CEF8F38F4A66254DE4029A7E431A6AF2F21C0535E387717B2E7A2D455F37'
    'app_debug-composables.txt' = 'C392734170FA2A877005ADD953CABB216DE348BEC71AC5FA31F47F15C51E5B58'
    'app_debug-classes.txt' = 'BE2C9A2B55979C84816563EA33158A12B95B023F014806C533FCC112FE0A285E'
}
foreach ($name in $frozenP1Hashes.Keys) {
    $path = Join-Path $p1 $name
    Assert-Condition (Test-Path -LiteralPath $path) "missing frozen P1 report: $name"
    Assert-Condition ((Get-Sha256 $path) -eq $frozenP1Hashes[$name]) "frozen P1 hash mismatch: $name"
}

$reportNames = @(
    'app_debug-module.json',
    'app_debug-composables.csv',
    'app_debug-composables.txt',
    'app_debug-classes.txt'
)
foreach ($name in $reportNames) {
    Assert-Condition (Test-Path -LiteralPath (Join-Path $p2 $name)) "missing P2 report: $name"
}

foreach ($name in 'app_debug-composables.csv','app_debug-composables.txt') {
    Assert-Condition ((Get-Sha256 (Join-Path $p2 $name)) -eq $frozenP1Hashes[$name]) "P2 $name differs from frozen P1"
}

$p1ModuleExpected = [ordered]@{
    skippableComposables = 142; restartableComposables = 225; readonlyComposables = 0
    totalComposables = 226; restartGroups = 225; totalGroups = 269
    staticArguments = 423; certainArguments = 50; knownStableArguments = 3008
    knownUnstableArguments = 36; unknownStableArguments = 1; totalArguments = 3045
    markedStableClasses = 0; inferredStableClasses = 37; inferredUnstableClasses = 47
    inferredUncertainClasses = 3; effectivelyStableClasses = 37; totalClasses = 87
    memoizedLambdas = 175; singletonLambdas = 37; singletonComposableLambdas = 47
    composableLambdas = 118; totalLambdas = 203
}
$p2ModuleExpected = [ordered]@{
    skippableComposables = 142; restartableComposables = 225; readonlyComposables = 0
    totalComposables = 226; restartGroups = 225; totalGroups = 269
    staticArguments = 423; certainArguments = 50; knownStableArguments = 3008
    knownUnstableArguments = 36; unknownStableArguments = 1; totalArguments = 3045
    markedStableClasses = 0; inferredStableClasses = 36; inferredUnstableClasses = 48
    inferredUncertainClasses = 2; effectivelyStableClasses = 36; totalClasses = 86
    memoizedLambdas = 175; singletonLambdas = 37; singletonComposableLambdas = 47
    composableLambdas = 118; totalLambdas = 203
}
Read-ExactModuleJson (Join-Path $p1 'app_debug-module.json') $p1ModuleExpected 'P1'
Read-ExactModuleJson (Join-Path $p2 'app_debug-module.json') $p2ModuleExpected 'P2'

$beforeReport = Read-ClassBlocks (Join-Path $p1 'app_debug-classes.txt') 87 'P1'
$afterReport = Read-ClassBlocks (Join-Path $p2 'app_debug-classes.txt') 86 'P2'
$before = $beforeReport.Blocks
$after = $afterReport.Blocks

$removed = @($before.Keys | Where-Object { -not $after.ContainsKey($_) } | Sort-Object)
$added = @($after.Keys | Where-Object { -not $before.ContainsKey($_) } | Sort-Object)
Assert-Condition (($removed -join ',') -ceq 'Guardian') "removed blocks were [$($removed -join ',')]"
Assert-Condition ($added.Count -eq 0) "added blocks were [$($added -join ',')]"
Assert-Condition ((Get-StringSha256 $before['Guardian']) -eq '4D4A12E5CB9A945D5479A362F96FBC4133AA92E2ACC51ED52EAF8556EE4F66F0') 'frozen Guardian class block hash changed'

$fieldTransitions = [ordered]@{
    DebugViewModel = [ordered]@{
        'runtime val eventSink: RiskEventSink' = 'unstable val eventSink: RiskEventSink'
        'runtime val settingsRepository: SettingsRepository' = 'unstable val settingsRepository: SettingsRepository'
    }
    DefaultRiskDetectionCoordinator = [ordered]@{
        'runtime val eventSink: RiskEventSink' = 'unstable val eventSink: RiskEventSink'
        'runtime val guardianRepository: GuardianRepository' = 'unstable val guardianRepository: GuardianRepository'
    }
    GuardianAddViewModel = [ordered]@{
        'runtime val guardianRepository: GuardianRepository' = 'unstable val guardianRepository: GuardianRepository'
    }
    GuardianViewModel = [ordered]@{
        'runtime val guardianRepository: GuardianRepository' = 'unstable val guardianRepository: GuardianRepository'
    }
    HomeViewModel = [ordered]@{
        'runtime val riskRepository: RiskRepository' = 'unstable val riskRepository: RiskRepository'
        'runtime val guardianRepository: GuardianRepository' = 'unstable val guardianRepository: GuardianRepository'
    }
    OnboardingViewModel = [ordered]@{
        'runtime val settingsRepository: SettingsRepository' = 'unstable val settingsRepository: SettingsRepository'
    }
    RealCallRiskMonitor = [ordered]@{
        'runtime val settingsRepository: SettingsRepository' = 'unstable val settingsRepository: SettingsRepository'
    }
    SplashViewModel = [ordered]@{
        'runtime val settingsRepository: SettingsRepository' = 'unstable val settingsRepository: SettingsRepository'
    }
}

$changed = @()
$exactCommon = 0
foreach ($name in ($before.Keys | Where-Object { $after.ContainsKey($_) } | Sort-Object)) {
    if ($before[$name] -cne $after[$name]) {
        $changed += $name
    } else {
        $exactCommon++
    }
}
$expectedChanged = @($fieldTransitions.Keys | Sort-Object)
Assert-Condition (($changed -join ',') -ceq ($expectedChanged -join ',')) "changed common blocks were [$($changed -join ',')]"
Assert-Condition ($exactCommon -eq 78) "exact common block count was $exactCommon, expected 78"
Assert-Condition ($changed.Count -eq 8) "changed block count was $($changed.Count), expected 8"

$transitionCount = 0
foreach ($name in $expectedChanged) {
    $projected = $before[$name]
    foreach ($source in $fieldTransitions[$name].Keys) {
        $target = $fieldTransitions[$name][$source]
        Assert-Condition ((Get-LiteralCount $projected $source) -eq 1) "P1 source transition count was not one in ${name}: $source"
        Assert-Condition ((Get-LiteralCount $projected $target) -eq 0) "P1 already contained target transition in ${name}: $target"
        $projected = $projected.Replace($source, $target)
        $transitionCount++
    }
    if ($name -ceq 'OnboardingViewModel') {
        $resultTransitions = [ordered]@{
            'runtime class OnboardingViewModel' = 'unstable class OnboardingViewModel'
            '<runtime stability> = Uncertain(SettingsRepository)' = '<runtime stability> = Unstable'
        }
        foreach ($source in $resultTransitions.Keys) {
            $target = $resultTransitions[$source]
            Assert-Condition ((Get-LiteralCount $projected $source) -eq 1) "P1 Onboarding result source count was not one: $source"
            Assert-Condition ((Get-LiteralCount $projected $target) -eq 0) "P1 Onboarding result already contained target: $target"
            $projected = $projected.Replace($source, $target)
        }
    }
    Assert-Condition ($projected -ceq $after[$name]) "$name contains a delta beyond the exact B+ projection"
}
Assert-Condition ($transitionCount -eq 11) "repository field transition count was $transitionCount, expected 11"

$guardianCardHash = '454704B4868C507918831DD63F8386A9F61997FE5A9DF73664DE874F57A704AA'
$guardianRowHash = 'AE056D154F44CAC887345473E69C61F3A6F27EB783405555A2430FD394BB8988'
foreach ($phase in @(@{ Name = 'P1'; Root = $p1 }, @{ Name = 'P2'; Root = $p2 })) {
    $block = Get-GuardianCardBlock (Join-Path $phase.Root 'app_debug-composables.txt')
    Assert-Condition ($block.Contains('stable guardian: Guardian')) "$($phase.Name) GuardianCard Guardian parameter is not stable"
    Assert-Condition ((Get-StringSha256 $block) -eq $guardianCardHash) "$($phase.Name) GuardianCard block hash changed"
    $row = Get-GuardianCardCsvRow (Join-Path $phase.Root 'app_debug-composables.csv')
    Assert-Condition ((Get-StringSha256 $row) -eq $guardianRowHash) "$($phase.Name) GuardianCard CSV row hash changed"
}

[pscustomobject]@{
    Verdict = 'B+ exact projection PASS'
    PostReports = (Resolve-Path -LiteralPath $p2).Path
    RemovedBlocks = $removed -join ','
    AddedBlocks = $added.Count
    ExactCommonBlocks = $exactCommon
    ProjectedChangedBlocks = $changed.Count
    RepositoryFieldTransitions = $transitionCount
    AffectedTypeParameters = 0
    GuardianCard = 'restartable/skippable; stable Guardian; exact block and CSV row'
    ComposablesCsvSha256 = Get-Sha256 (Join-Path $p2 'app_debug-composables.csv')
    ComposablesTxtSha256 = Get-Sha256 (Join-Path $p2 'app_debug-composables.txt')
} | Format-List
