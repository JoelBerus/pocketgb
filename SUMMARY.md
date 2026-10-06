# Capturas de CI
- Rama: `codex/d8-1-corrections` · commit: `adb5749d56717f191414c662a0c21d3481aa3bd6` · resultado: **success**
- Run: https://github.com/JoelBerus/pocketgb/actions/runs/37533898147
- Xcode 26.6 Build version 17F113 

## Errores y warnings del proyecto
```
(ninguno)
```

## Tests
```
✔ Test menuOpensWithoutPressingButtons() passed after 0.189 seconds.
✔ Test dpadCapturesItsFingerOutsideTheRadius() passed after 0.195 seconds.
✔ Test noShoulderButtons() passed after 0.263 seconds.
✔ Test dpadStyleDefaultsToGameBoyAndPersists() passed after 0.256 seconds.
✔ Test underrunRepeatsLastStereoFrameAndCountsOnce() passed after 0.257 seconds.
✔ Test writeAndReadPreserveOrderAcrossWraparound() passed after 0.257 seconds.
✔ Test neverOppositeDirections() passed after 0.267 seconds.
✔ Test convertsInt16ExtremesToFloat() passed after 0.257 seconds.
✔ Test fullBufferAcceptsOnlyFreeFrames() passed after 0.257 seconds.
✔ Test wrongSizeLocalIsNotLoaded() passed after 0.204 seconds.
✔ Test wrongSizeMirrorIsNeverTouched() passed after 0.204 seconds.
✔ Test wrongSizeLocalWithValidMirrorIsQuarantined() passed after 0.203 seconds.
✔ Test mirrorSaveDateIsReported() passed after 0.377 seconds.
✔ Test restoreBacksUpCurrentFirst() passed after 0.206 seconds.
✔ Test absentMirrorSnapshot() passed after 0.443 seconds.
✔ Test rejectsTooLargeAndInvalidFiles() passed after 0.616 seconds.
✔ Test addBackupDoesNotTouchCurrent() passed after 0.435 seconds.
✔ Test scanningDoesNotModifyROMs() passed after 0.616 seconds.
✔ Test fourManualSlotsAndOneAuto() passed after 0.435 seconds.
✔ Test savesIndexListsGamesWithTitles() passed after 0.435 seconds.
✔ Test saveListLoadAndDelete() passed after 0.406 seconds.
✔ Test foreignFileIsListedAsCorrupt() passed after 0.406 seconds.
✔ Test nothingSaved() passed after 0.406 seconds.
✔ Test findsOnlyGameBoyFilesUpToDepthOne() passed after 0.617 seconds.
✔ Test newerMirrorWinsAndLocalIsKeptByTheInstall() passed after 0.406 seconds.
✔ Test missingDatesPreferLocal() passed after 0.406 seconds.
✔ Test replacingASlotKeepsOnlyTheNewState() passed after 0.407 seconds.
✔ Test newerLocalWinsAndMirrorIsBackedUp() passed after 0.406 seconds.
✔ Test perControlSizeIsClampedAndPerOrientation() passed after 0.513 seconds.
✔ Test mirrorOnlyIsImportedIntoLocal() passed after 0.406 seconds.
✔ Test localOnlyIsUsedAndMirrorCreated() passed after 0.406 seconds.
✔ Test losingMirrorGoesToBackupOnceOnly() passed after 0.406 seconds.
✔ Test wrongSizeFilesAreKeptByteForByte() passed after 0.436 seconds.
✔ Test portraitAndLandscapeLayoutsPersistSeparately() passed after 0.497 seconds.
✔ Test persistWritesBothCopies() passed after 0.638 seconds.
✔ Test synchronousSessionFlushDoesNotWaitForBlockedRealMirror() passed after 0.616 seconds.
✔ Test allColorAssetsExist() passed after 0.803 seconds.
✔ Test routerKnowsD1Screens() passed after 0.896 seconds.
✔ Test newerExternalMirrorWinsAndBacksUpLocal() passed after 0.777 seconds.
✔ Test adaptiveColorsChangeInDarkMode() passed after 0.901 seconds.
✔ Test searchIsLiveCaseAndAccentInsensitiveWithinTheFilter() passed after 0.909 seconds.
✔ Test artworkIsSavedAsPNGAndReloaded() passed after 0.911 seconds.
✔ Test placeholderIsDeterministicPerSeed() passed after 0.911 seconds.
✔ Test aliasesPreferFingerprintMigrateFromPathAndAffectSearchAndSort() passed after 0.911 seconds.
✔ Test frameBytesMapToRGBA() passed after 0.911 seconds.
✔ Test neverOpenedGameIsHiddenByPath() passed after 0.911 seconds.
✔ Test recentSortAndRecentRowSkipHiddenGames() passed after 0.911 seconds.
✔ Test favoritesRecentAndLayoutPersistAcrossLaunches() passed after 0.909 seconds.
✔ Test filtersAreAllGBGBCAndFavorites() passed after 0.912 seconds.
✔ Test hidingPersistsTheFingerprintAndNeverTouchesFiles() passed after 0.992 seconds.
✔ Test aliasIsLimitedToEightyCharactersAndEmptyRestoresOriginalTitle() passed after 0.992 seconds.
✔ Test blankFrameIsNotArtwork() passed after 0.992 seconds.
✔ Test oldPreferencesFileWithMissingKeysStillLoads() passed after 0.951 seconds.
✔ Test iCloudOnlyMirrorIsUnavailableAndNeverWritten() passed after 0.989 seconds.
✔ Test mirrorFailureKeepsLocalAndRetries() passed after 1.315 seconds.
✔ Test blockedMirrorDoesNotBlockLocalFlushAndCoalescesLatest() passed after 1.744 seconds.
✔ Test staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens() passed after 1.774 seconds.
✔ Test restoredHistoricalMirrorWithNewDateWinsAndBacksUpLocal() passed after 1.994 seconds.
✔ Test mirrorWithoutLocalSaveRejectsAutomaticState() passed after 5.088 seconds.
✔ Test startRestoringResumesWhenStateSRAMMatchesTheSave() passed after 5.088 seconds.
✔ Test automaticStateMustExistBeValidAndNotPredateTheSave() passed after 5.088 seconds.
✔ Test startRestoringWithRTCDoesNotRewriteWhenOnlyTheFooterChanges() passed after 5.090 seconds.
✔ Test newerMirrorRejectsAutomaticStateWithoutTouchingAnySave() passed after 5.091 seconds.
✔ Test statesNeedAPausedSession() passed after 5.038 seconds.
✔ Test loadStateFailsVisiblyAndRollsBackWhenSavingFails() passed after 5.091 seconds.
✔ Test staleStateRollsBackTheCore() passed after 5.038 seconds.
✔ Test startRestoringRejectsCorruptAndForeignStateWithoutChangingSave() passed after 5.038 seconds.
✔ Test loadingAStateSavesItsSRAMWithBackupOfThePrevious() passed after 5.038 seconds.
✔ Test automaticEntryDoesNotReadTheThumbnail() passed after 5.038 seconds.
✔ Test coreRejectsCorruptAndForeignStatesWithoutTouchingSRAM() passed after 5.038 seconds.
✔ Test run with 103 tests in 11 suites passed after 5.214 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (883.989 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testCatalogCoversEverySpecScreenWithoutContradictions]' passed (0.116 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testGridReflowsToOneColumnAtAX5]' passed (17.704 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testLibraryLabelsAndTouchTargets]' passed (6.923 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (24.289 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (6.470 seconds).
Test Case '-[PocketGBUITests.ShellLibraryTests testDetailsAndHideGame]' passed (20.142 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (24.315 seconds).
** TEST SUCCEEDED **
```

## Capturas
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
- game-paused-portrait-dark.png
- game-settings-portrait-dark.png
- game-settings-portrait-light.png
- gameplay-controller-landscape-dark.png
- gameplay-fast-forward-portrait-dark.png
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
