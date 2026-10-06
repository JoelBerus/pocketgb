# Capturas de CI
- Rama: `g8-gba-controls` · commit: `8328fcc2b8669e885a2af5595c1113182b517845` · resultado: **success**
- Run: https://github.com/JoelBerus/pocketgb/actions/runs/37533897899
- Xcode 26.6 Build version 17F113 

## Errores y warnings del proyecto
```
(ninguno)
```

## Tests
```
✔ Test menuIsStartAndOptionsIsSelect() passed after 0.123 seconds.
✔ Test shouldersMapToLAndR() passed after 0.115 seconds.
✔ Test stickDeadZoneAndNoOpposites() passed after 0.115 seconds.
✔ Test faceButtonsMapByPosition() passed after 0.115 seconds.
✔ Test dpadAndLeftStickGiveTheSameMask() passed after 0.115 seconds.
✔ Test boundaryAnglesAt22Point5Degrees() passed after 0.115 seconds.
✔ Test positionsAreClampedInsideTheSafeArea() passed after 0.115 seconds.
✔ Test dpadCapturesItsFingerOutsideTheRadius() passed after 0.115 seconds.
✔ Test twoFingersAAndBAndSlideBToA() passed after 0.115 seconds.
✔ Test dpadStyleDefaultsToGameBoyAndPersists() passed after 0.115 seconds.
✔ Test shouldersStayTappableWhenDpadIsMovedOnTop() passed after 0.115 seconds.
✔ Test advanceLandscapeDefaultsDoNotCoverThe3x2Image() passed after 0.115 seconds.
✔ Test eightSectorsOf45Degrees() passed after 0.115 seconds.
✔ Test smallControlsKeepA44PointTouchTarget() passed after 0.115 seconds.
✔ Test invalidStoredOpacityFallsBackToDefault() passed after 0.115 seconds.
✔ Test oldStoredSettingsKeepGameBoyLayoutAndAdvanceStartsWithItsDefaults() passed after 0.115 seconds.
✔ Test menuOpensWithoutPressingButtons() passed after 0.115 seconds.
✔ Test advancePortraitDefaultsKeepShouldersClearOfHUDAndControls() passed after 0.115 seconds.
✔ Test abZoneBetweenAAndBPressesBoth() passed after 0.115 seconds.
✔ Test deadZoneIs25PercentOfRadius() passed after 0.115 seconds.
✔ Test slidingASingleFingerBetweenShouldersSwitchesOrReleases() passed after 0.115 seconds.
✔ Test shoulderButtonsExistOnlyOnGameBoyAdvance() passed after 0.115 seconds.
✔ Test rotationCancelsEveryFingerWithZeroMask() passed after 0.115 seconds.
✔ Test synchronousSessionFlushDoesNotWaitForBlockedRealMirror() passed after 0.198 seconds.
✔ Test neverOppositeDirections() passed after 0.116 seconds.
✔ Test clearingEveryOverrideRemovesTheGameEntry() passed after 0.135 seconds.
✔ Test newerExternalMirrorWinsAndBacksUpLocal() passed after 0.152 seconds.
✔ Test gameBoyCoreIgnoresShoulderBits() passed after 0.151 seconds.
✔ Test portraitAndLandscapeLayoutsPersistSeparately() passed after 0.148 seconds.
✔ Test editingGameBoyLayoutLeavesAdvanceUntouched() passed after 0.148 seconds.
✔ Test perControlSizeIsClampedAndPerOrientation() passed after 0.148 seconds.
✔ Test restoredHistoricalMirrorWithNewDateWinsAndBacksUpLocal() passed after 0.248 seconds.
✔ Test blockedMirrorDoesNotBlockLocalFlushAndCoalescesLatest() passed after 0.295 seconds.
✔ Test staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens() passed after 0.256 seconds.
✔ Test mirrorFailureKeepsLocalAndRetries() passed after 0.269 seconds.
✔ Test iCloudOnlyMirrorIsUnavailableAndNeverWritten() passed after 0.607 seconds.
✔ Test artworkIsSavedAsPNGAndReloaded() passed after 1.214 seconds.
✔ Test routerKnowsD1Screens() passed after 1.172 seconds.
✔ Test hidingPersistsTheFingerprintAndNeverTouchesFiles() passed after 1.224 seconds.
✔ Test adaptiveColorsChangeInDarkMode() passed after 1.218 seconds.
✔ Test searchIsLiveCaseAndAccentInsensitiveWithinTheFilter() passed after 1.225 seconds.
✔ Test recentSortAndRecentRowSkipHiddenGames() passed after 1.224 seconds.
✔ Test filtersAreAllGBGBCAndFavorites() passed after 1.220 seconds.
✔ Test blankFrameIsNotArtwork() passed after 1.225 seconds.
✔ Test oldPreferencesFileWithMissingKeysStillLoads() passed after 1.220 seconds.
✔ Test allColorAssetsExist() passed after 1.172 seconds.
✔ Test frameBytesMapToRGBA() passed after 1.220 seconds.
✔ Test favoritesRecentAndLayoutPersistAcrossLaunches() passed after 1.224 seconds.
✔ Test neverOpenedGameIsHiddenByPath() passed after 1.220 seconds.
✔ Test placeholderIsDeterministicPerSeed() passed after 1.225 seconds.
✔ Test coreRejectsCorruptAndForeignStatesWithoutTouchingSRAM() passed after 1.225 seconds.
✔ Test loadingAStateSavesItsSRAMWithBackupOfThePrevious() passed after 1.225 seconds.
✔ Test statesNeedAPausedSession() passed after 1.225 seconds.
✔ Test loadStateFailsVisiblyAndRollsBackWhenSavingFails() passed after 1.227 seconds.
​​​​​​​​​✔ Test headerParsesTitleAndChecksum() passed after 5.755 seconds.
✔ Test noSaveWithClockAcceptsAndLoadsTheClockOnlySave() passed after 5.789 seconds.
✔ Test statesOfAnotherConfigurationAreRejectedWithTheirOwnError() passed after 5.755 seconds.
✔ Test biosIsOnlyUsedWhenItIsTheOfficialDump() passed after 5.755 seconds.
✔ Test perGameOverridesForceSaveTypeAndRTC() passed after 5.789 seconds.
✔ Test statesRoundTripAndRejectGBStates() passed after 5.789 seconds.
✔ Test sessionSavesGBASRAMThroughTheNormalPath() passed after 5.755 seconds.
✔ Test forcedSRAMMatchingTheLocalSaveStillSavesNormally() passed after 5.755 seconds.
✔ Test newerMirrorOfAnotherMediumNeverReplacesTheLocalSave() passed after 5.789 seconds.
✔ Test storedPerGameSettingsOutOfRangeAreDiscarded() passed after 5.789 seconds.
✔ Test rtcCartridgeAcceptsOnlyItsMediumWithOrWithoutTheClock() passed after 5.755 seconds.
✔ Test wrongSizeGBASaveIsNeverOverwritten() passed after 5.754 seconds.
✔ Test scannerListsGBAAndChecksItsLimit() passed after 5.754 seconds.
✔ Test forcedEEPROM512IgnoresANewerMirrorOf8KiB() passed after 5.754 seconds.
✔ Test coreRunsTheROMAndSavesSRAM() passed after 5.754 seconds.
✔ Test forcedMismatchingMediumLeavesLocalMirrorAndBackupsUntouched(_:saveType:rtc:) with 9 test cases passed after 5.755 seconds.
✔ Test run with 117 tests in 12 suites passed after 5.901 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (776.828 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testCatalogCoversEverySpecScreenWithoutContradictions]' passed (0.089 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testGridReflowsToOneColumnAtAX5]' passed (5.641 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testLibraryLabelsAndTouchTargets]' passed (6.019 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (9.208 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (5.310 seconds).
Test Case '-[PocketGBUITests.ShellLibraryTests testDetailsAndHideGame]' passed (15.013 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (19.868 seconds).
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
