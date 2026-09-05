function Read-LintRecords([string]$Report, [string]$RepositoryRoot) {
    if ([string]::IsNullOrWhiteSpace($Report)) { throw 'Explicit fresh lint report required.' }
    $prefix = $RepositoryRoot.Replace('\','/').TrimEnd('/') + '/'
    if ($prefix -notmatch '^(?:[A-Za-z]:/|/)') { throw 'Absolute repository root required.' }
    [xml]$document = Get-Content -LiteralPath $Report -Raw
    foreach ($issue in @($document.issues.issue | Where-Object { $null -ne $_ })) {
        if (@($issue.location).Count -ne 1) { throw 'Expected one diagnostic location.' }
        $file = ([string]$issue.location.file).Replace('\','/')
        if ([string]::IsNullOrWhiteSpace($file)) { throw 'Diagnostic file missing.' }
        if ($file -match '^(?:[A-Za-z]:/|/)') {
            $comparison = if ($prefix -match '^[A-Za-z]:/' -or $prefix.StartsWith('//')) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
            if (-not $file.StartsWith($prefix,$comparison)) { throw "Outside repository: $file" }
            $file = $file.Substring($prefix.Length)
        }
        if ($file -match '(^|/)\.\.(/|$)|:') { throw "Unsupported path: $file" }
        $file = ($file -replace '^(\./)+','').Replace('/','\')
        $message = ([string]$issue.message) -replace '(?<=available: )[^\s]+','<LATEST>'
        $snippet = [string]$issue.errorLine1
        $key = (([string]$issue.id) + '|' + ([string]$issue.severity) + '|' + $message + '|' + $file + '|' + $snippet).Trim()
        [pscustomobject]@{Id=[string]$issue.id;Severity=[string]$issue.severity;File=$file;Declaration=$snippet;Message=$message;Key=$key}
    }
}
function Get-LintFingerprint([string[]]$Rows) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($Rows | Sort-Object) -join "`n")))).Replace('-','') }
    finally { $sha.Dispose() }
}
function Get-LintCounts($Records) {
    $counts = [Collections.Generic.Dictionary[string,int]]::new([StringComparer]::Ordinal)
    foreach ($record in $Records) {
        if ($counts.ContainsKey($record.Key)) { $counts[$record.Key]++ } else { $counts.Add($record.Key,1) }
    }
    return ,$counts
}
