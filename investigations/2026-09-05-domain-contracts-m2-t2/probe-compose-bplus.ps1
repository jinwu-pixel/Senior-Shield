$ErrorActionPreference = 'Stop'
$validator = Join-Path $PSScriptRoot 'validate-compose-bplus.ps1'
$source = Join-Path $PSScriptRoot 'evidence/compose-p2'
$results = @()

function Invoke-RejectionProbe([string]$Name, [scriptblock]$Mutation) {
    $probeRoot = Join-Path ([IO.Path]::GetTempPath()) ("senior-shield-m2-" + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $probeRoot
    try {
        Copy-Item -Path (Join-Path $source '*') -Destination $probeRoot
        & $Mutation $probeRoot
        $message = $null
        try {
            & $validator -EvidenceRoot $probeRoot | Out-Null
        } catch {
            $message = $_.Exception.Message
        }
        if ([string]::IsNullOrWhiteSpace($message)) {
            throw "Mutation probe was accepted: $Name"
        }
        [pscustomobject]@{ Probe = $Name; Verdict = 'REJECTED'; ValidatorMessage = $message }
    } finally {
        $resolved = [IO.Path]::GetFullPath($probeRoot)
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $resolved)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}

$jsonKeyCases = @(
    [pscustomobject]@{ Label = 'integer'; Literal = '1' },
    [pscustomobject]@{ Label = 'boolean'; Literal = 'true' },
    [pscustomobject]@{ Label = 'string'; Literal = '"probe"' },
    [pscustomobject]@{ Label = 'null'; Literal = 'null' }
)
foreach ($case in $jsonKeyCases) {
    $literal = $case.Literal
    $mutation = {
        param($root)
        $path = Join-Path $root 'app_debug-module.json'
        $raw = [IO.File]::ReadAllText($path)
        $mutated = [regex]::Replace($raw, '(?s)\}\s*$', ",`n `"unexpectedProbeKey`": $literal`n}`n")
        [IO.File]::WriteAllText($path, $mutated, [Text.UTF8Encoding]::new($false))
    }
    $mutation = $mutation.GetNewClosure()
    $results += Invoke-RejectionProbe "P2-only module JSON key ($($case.Label))" $mutation
}

$duplicateKeyCases = @(
    [pscustomobject]@{ Label = 'boolean'; Literal = 'true' },
    [pscustomobject]@{ Label = 'string'; Literal = '"86"' },
    [pscustomobject]@{ Label = 'null'; Literal = 'null' }
)
foreach ($case in $duplicateKeyCases) {
    $literal = $case.Literal
    $mutation = {
        param($root)
        $path = Join-Path $root 'app_debug-module.json'
        $raw = [IO.File]::ReadAllText($path)
        $mutated = [regex]::Replace($raw, '(?s)\}\s*$', ",`n `"totalClasses`": $literal`n}`n")
        [IO.File]::WriteAllText($path, $mutated, [Text.UTF8Encoding]::new($false))
    }
    $mutation = $mutation.GetNewClosure()
    $results += Invoke-RejectionProbe "duplicate totalClasses ($($case.Label))" $mutation
}

$results += Invoke-RejectionProbe 'unexpected runtime-to-unstable field transition' {
    param($root)
    $path = Join-Path $root 'app_debug-classes.txt'
    $raw = [IO.File]::ReadAllText($path)
    $sourceLine = '  runtime val evaluator: RiskEvaluator'
    if ([regex]::Matches($raw, [regex]::Escape($sourceLine)).Count -lt 1) {
        throw 'Probe fixture has no runtime evaluator field'
    }
    $mutated = $raw.Remove($raw.IndexOf($sourceLine), $sourceLine.Length).Insert(
        $raw.IndexOf($sourceLine),
        '  unstable val evaluator: RiskEvaluator'
    )
    [IO.File]::WriteAllText($path, $mutated, [Text.UTF8Encoding]::new($false))
}

$results | Format-Table -AutoSize
