param([Parameter(Mandatory)][string]$Report,[switch]$SmokeOnly)
$ErrorActionPreference = 'Stop'
$storage = @(
    'GuardianStorageTest#missingKeyIsEmptyThreeAreAllowedFourthRejectedAndRemovalFreesASlot',
    'GuardianStorageTest#savedJsonKeepsFourFieldsAndAnotherRepositoryReadsTheSameFile',
    'GuardianStorageTest#malformedJsonAndNonArrayRootProduceAnEmptyList',
    'GuardianStorageTest#invalidEntriesAreSkippedAndMissingRelationshipDefaultsToEmpty',
    'HiltRiskStorageTest#productionBindingsShareOneRoomStoreAndInjectedRepositoryObservesSinkWrites',
    'RoomDaoStorageTest#recentQueryKeepsOnlyLatestFiftyInDescendingTimeOrder',
    'RoomDaoStorageTest#countIncludesTheExactSinceTimestamp',
    'RoomDaoStorageTest#duplicateIdReplacesThePersistedRow',
    'RoomDaoStorageTest#isolatedFileDatabaseReopensWithTheSameRowAndSchemaIdentity',
    'RoomRiskEventStoreStorageTest#highAndUnknownCallerRoundTripThroughSqliteEnumNames',
    'RoomRiskEventStoreStorageTest#pushRecordUpdateAndClearKeepTheirDistinctPersistenceAndCurrentSemantics',
    'RoomRiskEventStoreStorageTest#invalidStoredEnumsFallBackOrAreSkippedAndBlankSignalsRemainEmpty',
    'SettingsStorageTest#fourKeysRoundTripAndAnotherRepositoryObservesTheSameActiveFile',
    'SettingsStorageTest#allFourMissingKeysDefaultToFalse'
) | ForEach-Object { 'com.example.seniorshield.data.' + $_ }
$smoke = @('deniedPhonePermissionReturnsNoCall','deniedPhonePermissionDoesNotRequestCallScreen','deniedNotificationPermissionDoesNotPost') | ForEach-Object { 'com.example.seniorshield.permission.PermissionDeviceSmokeTest#' + $_ }
$expected = if ($SmokeOnly) { @($smoke) } else { @($storage) + @($smoke) }
$raw = Get-Content -LiteralPath $Report -Raw
if ($raw -match 'FAILURES|INSTRUMENTATION_FAILED' -or $raw -notmatch ('OK \(' + $expected.Count + ' tests\)')) { throw 'Instrumentation completion failed.' }
$started = [Collections.Generic.List[string]]::new()
$passed = [Collections.Generic.List[string]]::new()
$class = $null
$test = $null
foreach ($line in ($raw -split '\r?\n')) {
    if ($line -cmatch '^INSTRUMENTATION_STATUS: class=(.+)$') { $class = $Matches[1].Trim() }
    if ($line -cmatch '^INSTRUMENTATION_STATUS: test=(.+)$') { $test = $Matches[1].Trim() }
    if ($line -cmatch '^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$') {
        $code = [int]$Matches[1]
        if (-not $class -or -not $test -or $code -notin @(0,1)) { throw 'Incomplete, failed, or skipped test status.' }
        $id = $class + '#' + $test
        if ($code -eq 1) { $started.Add($id) } else { $passed.Add($id) }
        $class = $null
        $test = $null
    }
}
foreach ($observed in @(@{Items=$started},@{Items=$passed})) {
    $items = @($observed.Items.ToArray())
    if ($items.Count -ne $expected.Count -or @(Compare-Object ($items | Sort-Object) ($expected | Sort-Object) -CaseSensitive).Count -ne 0) { throw 'Exact instrumentation class#test multiset mismatch.' }
}
[pscustomobject]@{Verdict='PASS';Tests=$expected.Count;Starts=$started.Count;Passes=$passed.Count;ExactTestNames=$true}
