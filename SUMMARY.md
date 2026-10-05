# Capturas de CI
- Rama: `g7-gba-app` · commit: `c1ab4122fd885ed8d2d7260664f501dc036703a3` · resultado: **failure**
- Run: https://github.com/JoelBerus/pocketgb/actions/runs/37340562392
- Xcode 26.6 Build version 17F113 

## Errores y warnings del proyecto
```
(ninguno)
```

## Tests
```
✔ Test invalidStoredOpacityFallsBackToDefault() passed after 0.091 seconds.
✔ Test sameContentDoesNotRotate() passed after 0.092 seconds.
✔ Test noShoulderButtons() passed after 0.091 seconds.
✔ Test smallControlsKeepA44PointTouchTarget() passed after 0.091 seconds.
✔ Test dpadStyleDefaultsToGameBoyAndPersists() passed after 0.091 seconds.
✔ Test neverOppositeDirections() passed after 0.091 seconds.
✔ Test localOnlyIsUsedAndMirrorCreated() passed after 0.091 seconds.
✔ Test newerLocalWinsAndMirrorIsBackedUp() passed after 0.091 seconds.
✔ Test clearingEveryOverrideRemovesTheGameEntry() passed after 0.093 seconds.
✔ Test mirrorOnlyIsImportedIntoLocal() passed after 0.091 seconds.
✔ Test sevenSavesKeepBackupsOneToFive() passed after 0.092 seconds.
✔ Test rotatesAtMostFiveBackups() passed after 0.096 seconds.
✔ Test stickDeadZoneAndNoOpposites() passed after 0.057 seconds.
✔ Test mirrorSaveDateIsReported() passed after 0.096 seconds.
✔ Test menuIsStartAndOptionsIsSelect() passed after 0.057 seconds.
✔ Test scanningDoesNotModifyROMs() passed after 0.096 seconds.
✔ Test padMaskCombinesWithTouchByOR() passed after 0.057 seconds.
✔ Test fastForwardCyclesOneTwoFour() passed after 0.057 seconds.
✔ Test addBackupDoesNotTouchCurrent() passed after 0.095 seconds.
✔ Test faceButtonsMapByPosition() passed after 0.057 seconds.
✔ Test missingDatesPreferLocal() passed after 0.057 seconds.
✔ Test dpadAndLeftStickGiveTheSameMask() passed after 0.057 seconds.
✔ Test nothingSaved() passed after 0.057 seconds.
✔ Test savesIndexListsGamesWithTitles() passed after 0.057 seconds.
✔ Test rejectsTooLargeAndInvalidFiles() passed after 0.097 seconds.
✔ Test wrongSizeMirrorIsNeverTouched() passed after 0.057 seconds.
✔ Test newerMirrorWinsAndLocalIsKeptByTheInstall() passed after 0.055 seconds.
✔ Test wrongSizeLocalIsNotLoaded() passed after 0.055 seconds.
✔ Test wrongSizeLocalWithValidMirrorIsQuarantined() passed after 0.055 seconds.
✔ Test restoreBacksUpCurrentFirst() passed after 0.055 seconds.
✔ Test findsOnlyGameBoyFilesUpToDepthOne() passed after 0.097 seconds.
✔ Test wrongSizeFilesAreKeptByteForByte() passed after 0.058 seconds.
✔ Test losingMirrorGoesToBackupOnceOnly() passed after 0.095 seconds.
✔ Test portraitAndLandscapeLayoutsPersistSeparately() passed after 0.096 seconds.
✔ Test perControlSizeIsClampedAndPerOrientation() passed after 0.096 seconds.
✔ Test absentMirrorSnapshot() passed after 0.052 seconds.
✔ Test persistWritesBothCopies() passed after 0.189 seconds.
✔ Test blockedMirrorDoesNotBlockLocalFlushAndCoalescesLatest() passed after 0.204 seconds.
✔ Test newerExternalMirrorWinsAndBacksUpLocal() passed after 0.170 seconds.
✔ Test synchronousSessionFlushDoesNotWaitForBlockedRealMirror() passed after 0.179 seconds.
✔ Test staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens() passed after 0.194 seconds.
✔ Test routerKnowsD1Screens() passed after 0.237 seconds.
✔ Test allColorAssetsExist() passed after 0.237 seconds.
✔ Test restoredHistoricalMirrorWithNewDateWinsAndBacksUpLocal() passed after 0.212 seconds.
✔ Test mirrorFailureKeepsLocalAndRetries() passed after 0.217 seconds.
✔ Test adaptiveColorsChangeInDarkMode() passed after 0.545 seconds.
✔ Test artworkIsSavedAsPNGAndReloaded() passed after 0.546 seconds.
✔ Test frameBytesMapToRGBA() passed after 1.118 seconds.
✔ Test hidingPersistsTheFingerprintAndNeverTouchesFiles() passed after 1.094 seconds.
✔ Test favoritesRecentAndLayoutPersistAcrossLaunches() passed after 1.094 seconds.
✔ Test filtersAreAllGBGBCAndFavorites() passed after 1.094 seconds.
✔ Test recentSortAndRecentRowSkipHiddenGames() passed after 1.118 seconds.
✔ Test placeholderIsDeterministicPerSeed() passed after 1.118 seconds.
✔ Test searchIsLiveCaseAndAccentInsensitiveWithinTheFilter() passed after 1.094 seconds.
✔ Test oldPreferencesFileWithMissingKeysStillLoads() passed after 1.119 seconds.
✔ Test neverOpenedGameIsHiddenByPath() passed after 1.094 seconds.
✔ Test blankFrameIsNotArtwork() passed after 1.094 seconds.
✔ Test iCloudOnlyMirrorIsUnavailableAndNeverWritten() passed after 1.610 seconds.
✔ Test rtcCartridgeAcceptsOnlyItsMediumWithOrWithoutTheClock() passed after 1.768 seconds.
✔ Test headerParsesTitleAndChecksum() passed after 1.768 seconds.
✔ Test biosIsOnlyUsedWhenItIsTheOfficialDump() passed after 1.768 seconds.
✔ Test newerMirrorOfAnotherMediumNeverReplacesTheLocalSave() passed after 1.768 seconds.
✔ Test sessionSavesGBASRAMThroughTheNormalPath() passed after 1.768 seconds.
✔ Test loadStateFailsVisiblyAndRollsBackWhenSavingFails() passed after 1.762 seconds.
✔ Test coreRunsTheROMAndSavesSRAM() passed after 1.768 seconds.
✔ Test coreRejectsCorruptAndForeignStatesWithoutTouchingSRAM() passed after 1.762 seconds.
✔ Test loadingAStateSavesItsSRAMWithBackupOfThePrevious() passed after 1.762 seconds.
✔ Test wrongSizeGBASaveIsNeverOverwritten() passed after 1.768 seconds.
✔ Test statesNeedAPausedSession() passed after 1.763 seconds.
✔ Test statesRoundTripAndRejectGBStates() passed after 1.768 seconds.
✔ Test scannerListsGBAAndChecksItsLimit() passed after 1.768 seconds.
✔ Test run with 102 tests in 12 suites passed after 1.798 seconds.
Test Case '-[PocketGBUITests.ShellAccessibilityTests testCatalogCoversEverySpecScreenWithoutContradictions]' passed (0.376 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testGridReflowsToOneColumnAtAX5]' passed (5.418 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testLibraryLabelsAndTouchTargets]' passed (9.049 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (8.555 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (5.028 seconds).
Test Case '-[PocketGBUITests.ShellLibraryTests testDetailsAndHideGame]' passed (15.073 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (19.960 seconds).
** TEST FAILED **
```

## Capturas
- launch-portrait-dark.png
- library-empty-portrait-light.png
- library-no-folder-portrait-dark.png
- library-no-folder-portrait-light.png
