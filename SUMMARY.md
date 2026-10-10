# Capturas de CI
- Rama: `siguiente-nivel` · commit: `01023920e290077249a482391e4620aad97109ab` · resultado: **failure**
- Run: https://github.com/JoelBerus/pocketgb/actions/runs/38023624023
- Xcode 26.6 Build version 17F113 

## Errores y warnings del proyecto
```
/Users/joelbermudez/actions-runner-pocketgb/_work/pocketgb/pocketgb/ios/PocketGBUITests/CategoriesUITests.swift:41: error: -[PocketGBUITests.CategoriesUITests testHomeSeeAllSubcategoryGameAndBack] : Failed to tap Button (Element at index 0): No matches found for Descendants matching type Button from input {(
/Users/joelbermudez/actions-runner-pocketgb/_work/pocketgb/pocketgb/ios/PocketGBUITests/CategoriesUITests.swift:89: error: -[PocketGBUITests.CategoriesUITests testHomeSeeAllSubcategoryGameAndBack] : XCTAssertTrue failed
```

## Tests
```
✔ Test scannerAttachesTheImageWithoutReadingIt() passed after 14.166 seconds.
✔ Test formatIsRecognizedByBytesNotExtension() passed after 14.166 seconds.
✔ Test truncatedImagesNeverCrash() passed after 14.166 seconds.
✔ Test forcedEEPROM512IgnoresANewerMirrorOf8KiB() passed after 14.166 seconds.
✔ Test newerMirrorOfAnotherMediumNeverReplacesTheLocalSave() passed after 14.166 seconds.
✔ Test wiringAdoptsMetadataAndReportsIssues() passed after 14.168 seconds.
✔ Test forcedSRAMMatchingTheLocalSaveStillSavesNormally() passed after 14.167 seconds.
✔ Test wrongSizeGBASaveIsNeverOverwritten() passed after 14.167 seconds.
✔ Test noSaveWithClockAcceptsAndLoadsTheClockOnlySave() passed after 14.167 seconds.
✔ Test eepromAutoStateDifferingPastByte512IsRejectedWithoutWriting() passed after 14.167 seconds.
✔ Test validImagesAreReducedToAtMost1024AsPNG() passed after 14.166 seconds.
✔ Test continueRailShowsResumableGamesWithoutAnyCover() passed after 14.166 seconds.
✔ Test sidecarMatchesNameExtensionPriorityAndSingleGameFolders() passed after 14.167 seconds.
✔ Test onlyACompleteScanAllowsPruning() passed after 14.167 seconds.
✔ Test priorityFollowsChoiceAndPreference() passed after 14.175 seconds.
✔ Test rtcCartridgeAcceptsOnlyItsMediumWithOrWithoutTheClock() passed after 14.166 seconds.
✔ Test coreRunsTheROMAndSavesSRAM() passed after 14.167 seconds.
✔ Test statesRoundTripAndRejectGBStates() passed after 14.229 seconds.
✔ Test storedPerGameSettingsOutOfRangeAreDiscarded() passed after 14.227 seconds.
✔ Test forcedMismatchingMediumLeavesLocalMirrorAndBackupsUntouched(_:saveType:rtc:) with 9 test cases passed after 14.168 seconds.
✔ Test sessionSavesGBASRAMThroughTheNormalPath() passed after 14.230 seconds.
✔ Test hugeDeclaredDimensionsAreRejectedBeforeDecoding() passed after 14.230 seconds.
✔ Test imagesOverTheByteLimitAreRejectedAndNotReadToTheEnd() passed after 14.230 seconds.
✔ Test loadingAStateSavesItsSRAMWithBackupOfThePrevious() passed after 14.225 seconds.
✔ Test staleStateRollsBackTheCore() passed after 14.229 seconds.
✔ Test mirrorWithoutLocalSaveRejectsAutomaticState() passed after 14.225 seconds.
✔ Test loadStateFailsVisiblyAndRollsBackWhenSavingFails() passed after 14.226 seconds.
✔ Test statesOfAnotherConfigurationAreRejectedWithTheirOwnError() passed after 14.231 seconds.
✔ Test aHostileFolderImageFallsBackToTheNextSourceAndIsNotRetried() passed after 14.232 seconds.
✔ Test newerMirrorRejectsAutomaticStateWithoutTouchingAnySave() passed after 14.229 seconds.
✔ Test automaticEntryDoesNotReadTheThumbnail() passed after 14.229 seconds.
✔ Test coreRejectsCorruptAndForeignStatesWithoutTouchingSRAM() passed after 14.226 seconds.
✔ Test automaticStateMustExistBeValidAndNotPredateTheSave() passed after 14.268 seconds.
✔ Test factoryWithoutBatteryHasNothingToSave() passed after 14.260 seconds.
✔ Test startRestoringRejectsCorruptAndForeignStateWithoutChangingSave() passed after 14.266 seconds.
✔ Test factoryWithWrongSizeLocalSaveReturnsNoPersisterAndLeavesTheFileAlone() passed after 14.260 seconds.
✔ Test startRestoringResumesWhenStateSRAMMatchesTheSave() passed after 14.265 seconds.
✔ Test readOnlyFolderReturnsFalseAndTheNextFlushRetries() passed after 14.260 seconds.
✔ Test startRestoringWithRTCDoesNotRewriteWhenOnlyTheFooterChanges() passed after 14.265 seconds.
✔ Test factoryForwardsMirrorNotDownloaded() passed after 14.260 seconds.
✔ Test changedSRAMIsWrittenAndLeavesTheBackup() passed after 14.260 seconds.
✔ Test safetyNetFlushesAfterSixtySecondsWithoutAnEdge() passed after 14.259 seconds.
✔ Test debounceWritesOneSecondAfterTheGameSaved() passed after 14.261 seconds.
✔ Test withoutASaveFileAFlushWritesNothing() passed after 14.260 seconds.
✔ Test unchangedSRAMDoesNotRotateBackups() passed after 14.260 seconds.
✔ Test retryAfterAFailedAsyncWriteRewritesWithoutASyncFlush() passed after 14.260 seconds.
✔ Test statesNeedAPausedSession() passed after 14.261 seconds.
✔ Test importChoosesImageAndRemovingReturnsToAuto() passed after 14.274 seconds.
✔ Test duplicatesShareMetadataAndShowOnce() passed after 14.275 seconds.
✔ Test hashingPausesWhileAGameIsOpenAndResumes() passed after 14.276 seconds.
✔ Test cancelledTechnicalLoadStopsBeforeReading() passed after 14.279 seconds.
✔ Test movingARomBetweenFoldersKeepsEverything() passed after 14.277 seconds.
✔ Test sameSizeAndModificationDateIsRecalculated() passed after 14.333 seconds.
✔ Test storedCopiesSurviveARestart() passed after 14.276 seconds.
✔ Test aGoodFolderImageIsCachedReducedAndPrunedWhenItChanges() passed after 14.288 seconds.
✔ Test otherLibraryFolderResetsTheCache() passed after 14.308 seconds.
✔ Test storePersistsAndInvalidatesTheCache() passed after 14.328 seconds.
✔ Test heicIsAlwaysRejected() passed after 14.309 seconds.
✔ Test run with 371 tests in 31 suites passed after 14.476 seconds.
Test Case '-[PocketGBUITests.AdaptiveLibraryUITests testAtRestTheToolsAreInTheNavigationBar]' passed (27.218 seconds).
Test Case '-[PocketGBUITests.AdaptiveLibraryUITests testContinueCardsAreAsWideAsAGridColumn]' passed (11.576 seconds).
Test Case '-[PocketGBUITests.AdaptiveLibraryUITests testGroupNeverCoincidesWithTheExpandedTabBar]' passed (45.471 seconds).
Test Case '-[PocketGBUITests.AdaptiveLibraryUITests testLandscapeSearchOpensFromTheBarAndGoesAwayOnClose]' passed (23.493 seconds).
Test Case '-[PocketGBUITests.AdaptiveLibraryUITests testPortraitKeepsTheSegmentedFilterAndAddsTheSearchButton]' passed (11.182 seconds).
Test Case '-[PocketGBUITests.AdaptiveLibraryUITests testRotatingWithTheSearchOpenKeepsIt]' passed (13.894 seconds).
Test Case '-[PocketGBUITests.AdaptiveLibraryUITests testScrolledTheGroupReplacesTheBarButtonsAndAlignsWithTheBubble]' passed (26.967 seconds).
Test Case '-[PocketGBUITests.CategoriesUITests testGameCenterRenamesTagsMovesAndReturns]' passed (56.447 seconds).
Test Case '-[PocketGBUITests.CategoriesUITests testHomeSeeAllSubcategoryGameAndBack]' failed (79.268 seconds).
Test Case '-[PocketGBUITests.CategoriesUITests testHomeSettingsHideAndPinCategories]' passed (28.706 seconds).
Test Case '-[PocketGBUITests.CategoriesUITests testLandscapeWithTheWholeHomeReachesAllGamesAndPanelsKeepTheTitle]' passed (41.880 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testCatalogCoversEverySpecScreenWithoutContradictions]' passed (0.771 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testGridReflowsToOneColumnAtAX5]' passed (7.654 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testLibraryLabelsAndTouchTargets]' passed (40.395 seconds).
Test Case '-[PocketGBUITests.ShellControlsTests testDpadAccessibilityAndArrowSpacingEditor]' passed (21.931 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (19.161 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (6.184 seconds).
Test Case '-[PocketGBUITests.ShellLibraryTests testDetailsAndHideGame]' passed (14.827 seconds).
Test Case '-[PocketGBUITests.ShellLinkTests testSwitchAndExitTheCable]' passed (13.898 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (25.913 seconds).
** TEST FAILED **
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
- gameplay-arrows-up-portrait-dark.png
- gameplay-controller-landscape-dark.png
- gameplay-dpad-up-clear-landscape-dark.png
- gameplay-dpad-up-landscape-dark.png
- gameplay-dpad-up-landscape-light.png
- gameplay-dpad-up-portrait-dark.png
- gameplay-dpad-up-portrait-light.png
- gameplay-dpad-up-reduce-transparency-landscape-dark.png
- gameplay-dpad-upright-portrait-dark.png
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
