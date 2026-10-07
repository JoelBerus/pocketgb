# Capturas de CI
- Rama: `main` · commit: `51416b4fb99e3c05e04db1d4cb98471a788a12a6` · resultado: **success**
- Run: https://github.com/JoelBerus/pocketgb/actions/runs/37557872753
- Xcode 26.6 Build version 17F113 

## Errores y warnings del proyecto
```
(ninguno)
```

## Tests
```
✔ Test shutdownRunsAfterTheFinalFlush() passed after 10.071 seconds.
✔ Test newerMirrorRejectsAutomaticStateWithoutTouchingAnySave() passed after 10.400 seconds.
✔ Test startRestoringWithRTCDoesNotRewriteWhenOnlyTheFooterChanges() passed after 10.404 seconds.
✔ Test loadingAStateSavesItsSRAMWithBackupOfThePrevious() passed after 10.404 seconds.
✔ Test startRestoringResumesWhenStateSRAMMatchesTheSave() passed after 10.404 seconds.
✔ Test loadStateFailsVisiblyAndRollsBackWhenSavingFails() passed after 10.923 seconds.
✔ Test automaticEntryDoesNotReadTheThumbnail() passed after 10.925 seconds.
✔ Test coreRejectsCorruptAndForeignStatesWithoutTouchingSRAM() passed after 10.940 seconds.
✔ Test statesNeedAPausedSession() passed after 10.949 seconds.
​​​​​​​​​✔ Test staleStateRollsBackTheCore() passed after 10.979 seconds.
✔ Test mirrorWithoutLocalSaveRejectsAutomaticState() passed after 10.978 seconds.
✔ Test allColorAssetsExist() passed after 10.973 seconds.
✔ Test startRestoringRejectsCorruptAndForeignStateWithoutChangingSave() passed after 10.979 seconds.
✔ Test forcedEEPROM512IgnoresANewerMirrorOf8KiB() passed after 10.978 seconds.
✔ Test routerKnowsD1Screens() passed after 10.972 seconds.
✔ Test eepromAutoStateDifferingPastByte512IsRejectedWithoutWriting() passed after 10.978 seconds.
✔ Test automaticStateMustExistBeValidAndNotPredateTheSave() passed after 10.979 seconds.
✔ Test wrongSizeGBASaveIsNeverOverwritten() passed after 10.979 seconds.
✔ Test scannerListsGBAAndChecksItsLimit() passed after 10.979 seconds.
✔ Test adaptiveColorsChangeInDarkMode() passed after 10.972 seconds.
✔ Test sessionSavesGBASRAMThroughTheNormalPath() passed after 10.978 seconds.
✔ Test statesRoundTripAndRejectGBStates() passed after 10.979 seconds.
✔ Test forcedMismatchingMediumLeavesLocalMirrorAndBackupsUntouched(_:saveType:rtc:) with 9 test cases passed after 10.979 seconds.
✔ Test automaticStateOfAnotherConfigurationIsNotCurrent() passed after 10.978 seconds.
✔ Test eepromAutoStateMatchingTheSaveResumesWithoutWriting() passed after 10.980 seconds.
✔ Test noSaveWithClockAcceptsAndLoadsTheClockOnlySave() passed after 10.978 seconds.
✔ Test storedPerGameSettingsOutOfRangeAreDiscarded() passed after 10.980 seconds.
✔ Test forcedSRAMMatchingTheLocalSaveStillSavesNormally() passed after 10.979 seconds.
✔ Test switchSideChangesTheActiveGame() passed after 10.979 seconds.
✔ Test perGameOverridesForceSaveTypeAndRTC() passed after 10.980 seconds.
✔ Test headerParsesTitleAndChecksum() passed after 10.980 seconds.
✔ Test biosIsOnlyUsedWhenItIsTheOfficialDump() passed after 10.979 seconds.
✔ Test indexTitlesIgnoreTheAliasAndFallBackToTheFileName() passed after 10.980 seconds.
✔ Test coreRunsTheROMAndSavesSRAM() passed after 10.980 seconds.
✔ Test sameGameWithBatteryIsRefusedAndTouchesNothing() passed after 10.982 seconds.
✔ Test sameGameIsRefusedBeforeOpeningEvenWithANewerMirror() passed after 10.982 seconds.
✔ Test saveStateThrowsLinkUnsupported() passed after 10.982 seconds.
✔ Test sameGameWithoutBatteryOpens() passed after 10.982 seconds.
✔ Test advanceRomIsRefused() passed after 10.982 seconds.
✔ Test unavailableMirrorWithLocalOpensWithATitledNotice() passed after 10.982 seconds.
✔ Test wrongSizeSavRefusesWithoutChangingAByte() passed after 10.982 seconds.
✔ Test pauseSavesBothSRAMs() passed after 10.982 seconds.
✔ Test unavailableMirrorWithoutLocalRefuses() passed after 10.982 seconds.
✔ Test searchIsLiveCaseAndAccentInsensitiveWithinTheFilter() passed after 10.981 seconds.
✔ Test newerMirrorOfAnotherMediumNeverReplacesTheLocalSave() passed after 10.982 seconds.
✔ Test exchangeIsSavedInBothSavFilesThroughTheNormalPath() passed after 10.982 seconds.
✔ Test oldPreferencesFileWithMissingKeysStillLoads() passed after 11.025 seconds.
✔ Test hidingPersistsTheFingerprintAndNeverTouchesFiles() passed after 11.025 seconds.
✔ Test aliasIsLimitedToEightyCharactersAndEmptyRestoresOriginalTitle() passed after 11.025 seconds.
✔ Test favoritesRecentAndLayoutPersistAcrossLaunches() passed after 11.026 seconds.
✔ Test filtersAreAllGBGBCAndFavorites() passed after 11.025 seconds.
✔ Test changedSRAMIsWrittenAndLeavesTheBackup() passed after 11.026 seconds.
✔ Test rtcCartridgeAcceptsOnlyItsMediumWithOrWithoutTheClock() passed after 11.027 seconds.
✔ Test aliasesPreferFingerprintMigrateFromPathAndAffectSearchAndSort() passed after 11.026 seconds.
✔ Test statesOfAnotherConfigurationAreRejectedWithTheirOwnError() passed after 11.028 seconds.
✔ Test withoutASaveFileAFlushWritesNothing() passed after 11.026 seconds.
✔ Test debounceWritesOneSecondAfterTheGameSaved() passed after 11.025 seconds.
✔ Test blankFrameIsNotArtwork() passed after 11.027 seconds.
✔ Test factoryWithWrongSizeLocalSaveReturnsNoPersisterAndLeavesTheFileAlone() passed after 11.026 seconds.
✔ Test placeholderIsDeterministicPerSeed() passed after 11.026 seconds.
✔ Test safetyNetFlushesAfterSixtySecondsWithoutAnEdge() passed after 11.026 seconds.
✔ Test recentSortAndRecentRowSkipHiddenGames() passed after 11.026 seconds.
✔ Test factoryForwardsMirrorNotDownloaded() passed after 11.026 seconds.
✔ Test neverOpenedGameIsHiddenByPath() passed after 11.026 seconds.
✔ Test readOnlyFolderReturnsFalseAndTheNextFlushRetries() passed after 11.026 seconds.
✔ Test factoryWithoutBatteryHasNothingToSave() passed after 11.026 seconds.
✔ Test unchangedSRAMDoesNotRotateBackups() passed after 11.026 seconds.
✔ Test retryAfterAFailedAsyncWriteRewritesWithoutASyncFlush() passed after 11.026 seconds.
✔ Test frameBytesMapToRGBA() passed after 11.026 seconds.
✔ Test run with 172 tests in 17 suites passed after 11.071 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (803.567 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testCatalogCoversEverySpecScreenWithoutContradictions]' passed (0.084 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testGridReflowsToOneColumnAtAX5]' passed (5.318 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testLibraryLabelsAndTouchTargets]' passed (5.687 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (9.703 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (5.271 seconds).
Test Case '-[PocketGBUITests.ShellLibraryTests testDetailsAndHideGame]' passed (14.169 seconds).
Test Case '-[PocketGBUITests.ShellLinkTests testSwitchAndExitTheCable]' passed (12.096 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (19.719 seconds).
** TEST SUCCEEDED **
```

## Capturas
- customize-controls-gba-landscape-landscape-dark.png
- customize-controls-gba-portrait-ax5-portrait-dark.png
- customize-controls-gba-portrait-portrait-dark.png
- customize-controls-landscape-landscape-dark.png
- customize-controls-portrait-portrait-dark.png
- customize-controls-size-portrait-dark.png
- favorites-portrait-dark.png
- favorites-portrait-light.png
- game-acid-landscape-dark.png
- game-acid-portrait-dark.png
- game-acid-portrait-light.png
- game-context-menu-portrait-dark.png
- game-context-menu-portrait-light.png
- game-details-portrait-dark.png
- game-details-portrait-light.png
- game-gba-landscape-dark.png
- game-gba-portrait-dark.png
- game-paused-portrait-dark.png
- game-settings-gba-ax5-portrait-light.png
- game-settings-gba-portrait-dark.png
- game-settings-gba-portrait-light.png
- game-settings-portrait-dark.png
- game-settings-portrait-light.png
- gameplay-controller-landscape-dark.png
- gameplay-fast-forward-portrait-dark.png
- gameplay-gba-hidden-landscape-dark.png
- gameplay-gba-landscape-clear-landscape-dark.png
- gameplay-gba-reduce-transparency-landscape-dark.png
- gameplay-landscape-arrows-landscape-dark.png
- gameplay-landscape-clear-landscape-dark.png
- gameplay-landscape-hidden-landscape-dark.png
- gameplay-landscape-landscape-dark.png
- gameplay-link-landscape-landscape-dark.png
- gameplay-link-pause-portrait-dark.png
- gameplay-link-portrait-portrait-dark.png
- gameplay-link-reduce-transparency-landscape-dark.png
- gameplay-link-switched-portrait-dark.png
- gameplay-pause-portrait-dark.png
- gameplay-portrait-arrows-portrait-dark.png
- gameplay-portrait-portrait-dark.png
- gameplay-reduce-transparency-landscape-dark.png
- launch-portrait-dark.png
- library-ax5-portrait-light.png
- library-cloud-downloading-portrait-dark.png
- library-cloud-downloading-portrait-light.png
- library-cloud-pending-portrait-dark.png
- library-cloud-pending-portrait-light.png
- library-continue-portrait-dark.png
- library-continue-portrait-light.png
- library-continue-reduce-motion-portrait-dark.png
- library-empty-portrait-dark.png
- library-empty-portrait-light.png
- library-folder-unavailable-portrait-dark.png
- library-folder-unavailable-portrait-light.png
- library-grid-portrait-dark.png
- library-grid-portrait-light.png
- library-list-portrait-dark.png
- library-list-portrait-light.png
- library-no-folder-portrait-dark.png
- library-no-folder-portrait-light.png
- library-reduce-transparency-portrait-dark.png
- library-rom-error-portrait-dark.png
- library-rom-error-portrait-light.png
- library-scan-progress-portrait-dark.png
- library-scan-progress-portrait-light.png
- library-scan-summary-portrait-dark.png
- library-scan-summary-portrait-light.png
- link-continue-warning-portrait-light.png
- link-open-refused-portrait-dark.png
- link-open-refused-portrait-light.png
- link-partner-picker-ax5-portrait-light.png
- link-partner-picker-portrait-dark.png
- link-partner-picker-portrait-light.png
- load-state-confirm-portrait-dark.png
- remove-game-confirm-portrait-dark.png
- remove-game-confirm-portrait-light.png
- replace-state-confirm-portrait-dark.png
- save-data-error-portrait-dark.png
- save-data-error-portrait-light.png
- save-states-portrait-dark.png
- search-active-portrait-dark.png
- search-active-portrait-light.png
- search-no-results-portrait-dark.png
- search-no-results-portrait-light.png
- search-results-portrait-dark.png
- search-results-portrait-light.png
- settings-about-portrait-dark.png
- settings-about-portrait-light.png
- settings-appearance-portrait-dark.png
- settings-appearance-portrait-light.png
- settings-audio-portrait-dark.png
- settings-audio-portrait-light.png
- settings-controls-portrait-dark.png
- settings-controls-portrait-light.png
- settings-display-portrait-dark.png
- settings-display-portrait-light.png
- settings-emulation-portrait-dark.png
- settings-emulation-portrait-light.png
- settings-library-portrait-dark.png
- settings-library-portrait-light.png
- settings-main-portrait-dark.png
- settings-main-portrait-light.png
- settings-saves-portrait-dark.png
- settings-saves-portrait-light.png
- settings-storage-portrait-dark.png
- settings-storage-portrait-light.png
