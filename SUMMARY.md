# Capturas de CI
- Rama: `d2-a1-wip` · commit: `7f0fd0fb51faf8e3d6a58aec31189bc89f61d3f5` · resultado: **success**
- Run: https://github.com/JoelBerus/pocketgb/actions/runs/36670005931
- Xcode 26.6 Build version 17F113 

## Errores y warnings del proyecto
```
(ninguno)
```

## Tests
```
✔ Test underrunRepeatsLastStereoFrameAndCountsOnce() passed after 0.207 seconds.
✔ Test missingDatesPreferLocal() passed after 0.207 seconds.
✔ Test absentMirrorSnapshot() passed after 0.207 seconds.
✔ Test wrongSizeFilesAreKeptByteForByte() passed after 0.207 seconds.
✔ Test rejectsTooLargeAndInvalidFiles() passed after 0.207 seconds.
✔ Test headerTitleRules() passed after 0.207 seconds.
✔ Test losingMirrorGoesToBackupOnceOnly() passed after 0.215 seconds.
✔ Test mirrorSaveDateIsReported() passed after 0.212 seconds.
✔ Test wrongSizeLocalIsNotLoaded() passed after 0.212 seconds.
✔ Test faceButtonsMapByPosition() passed after 0.212 seconds.
✔ Test padMaskCombinesWithTouchByOR() passed after 0.212 seconds.
✔ Test scanningDoesNotModifyROMs() passed after 0.212 seconds.
✔ Test findsOnlyGameBoyFilesUpToDepthOne() passed after 0.212 seconds.
✔ Test replaceKeepsPreviousInBackupOne() passed after 0.212 seconds.
✔ Test dpadAndLeftStickGiveTheSameMask() passed after 0.212 seconds.
✔ Test menuIsStartAndOptionsIsSelect() passed after 0.211 seconds.
✔ Test firstSaveCreatesFileWithoutBackups() passed after 0.212 seconds.
✔ Test stickDeadZoneAndNoOpposites() passed after 0.212 seconds.
✔ Test orphanTmpWithExistingSaveIsDeleted() passed after 0.212 seconds.
✔ Test fastForwardCyclesOneTwoFour() passed after 0.212 seconds.
✔ Test orphanTmpWithoutSaveIsInstalled() passed after 0.212 seconds.
✔ Test sameContentDoesNotRotate() passed after 0.212 seconds.
✔ Test interruptedFirstSaveIsRecoveredOnLaunch() passed after 0.212 seconds.
✔ Test rotatesAtMostFiveBackups() passed after 0.212 seconds.
✔ Test orphanTmpWithWrongSizeIsDeleted() passed after 0.212 seconds.
✔ Test clearingEveryOverrideRemovesTheGameEntry() passed after 0.256 seconds.
✔ Test rotationCancelsEveryFingerWithZeroMask() passed after 0.212 seconds.
✔ Test menuOpensWithoutPressingButtons() passed after 0.212 seconds.
✔ Test deadZoneIs25PercentOfRadius() passed after 0.212 seconds.
✔ Test abZoneBetweenAAndBPressesBoth() passed after 0.206 seconds.
✔ Test dpadCapturesItsFingerOutsideTheRadius() passed after 0.204 seconds.
✔ Test neverOppositeDirections() passed after 0.212 seconds.
✔ Test dpadStyleDefaultsToGameBoyAndPersists() passed after 0.199 seconds.
✔ Test eightSectorsOf45Degrees() passed after 0.199 seconds.
✔ Test noShoulderButtons() passed after 0.191 seconds.
✔ Test invalidStoredOpacityFallsBackToDefault() passed after 0.191 seconds.
✔ Test smallControlsKeepA44PointTouchTarget() passed after 0.179 seconds.
✔ Test boundaryAnglesAt22Point5Degrees() passed after 0.179 seconds.
✔ Test positionsAreClampedInsideTheSafeArea() passed after 0.179 seconds.
✔ Test twoFingersAAndBAndSlideBToA() passed after 0.179 seconds.
✔ Test sevenSavesKeepBackupsOneToFive() passed after 0.214 seconds.
✔ Test failureAfterStepKeepsPreviousSave(step:) with 3 test cases passed after 0.212 seconds.
✔ Test synchronousSessionFlushDoesNotWaitForBlockedRealMirror() passed after 0.218 seconds.
✔ Test persistWritesBothCopies() passed after 0.227 seconds.
✔ Test blockedMirrorDoesNotBlockLocalFlushAndCoalescesLatest() passed after 0.253 seconds.
✔ Test portraitAndLandscapeLayoutsPersistSeparately() passed after 0.246 seconds.
✔ Test newerExternalMirrorWinsAndBacksUpLocal() passed after 0.262 seconds.
✔ Test perControlSizeIsClampedAndPerOrientation() passed after 0.245 seconds.
✔ Test staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens() passed after 0.332 seconds.
✔ Test adaptiveColorsChangeInDarkMode() passed after 0.293 seconds.
✔ Test allColorAssetsExist() passed after 0.293 seconds.
✔ Test routerKnowsD1Screens() passed after 0.298 seconds.
✔ Test artworkIsSavedAsPNGAndReloaded() passed after 0.298 seconds.
✔ Test restoredHistoricalMirrorWithNewDateWinsAndBacksUpLocal() passed after 0.304 seconds.
✔ Test mirrorFailureKeepsLocalAndRetries() passed after 0.306 seconds.
✔ Test iCloudOnlyMirrorIsUnavailableAndNeverWritten() passed after 0.509 seconds.
✔ Test blankFrameIsNotArtwork() passed after 0.994 seconds.
✔ Test placeholderIsDeterministicPerSeed() passed after 0.992 seconds.
✔ Test recentSortAndRecentRowSkipHiddenGames() passed after 0.992 seconds.
✔ Test oldPreferencesFileWithMissingKeysStillLoads() passed after 0.992 seconds.
✔ Test neverOpenedGameIsHiddenByPath() passed after 0.992 seconds.
✔ Test hidingPersistsTheFingerprintAndNeverTouchesFiles() passed after 0.992 seconds.
✔ Test frameBytesMapToRGBA() passed after 0.992 seconds.
✔ Test searchIsLiveCaseAndAccentInsensitiveWithinTheFilter() passed after 0.992 seconds.
✔ Test favoritesRecentAndLayoutPersistAcrossLaunches() passed after 0.992 seconds.
✔ Test filtersAreAllGBGBCAndFavorites() passed after 0.992 seconds.
✔ Test coreRejectsCorruptAndForeignStatesWithoutTouchingSRAM() passed after 0.993 seconds.
✔ Test loadStateFailsVisiblyAndRollsBackWhenSavingFails() passed after 0.993 seconds.
✔ Test loadingAStateSavesItsSRAMWithBackupOfThePrevious() passed after 0.993 seconds.
✔ Test statesNeedAPausedSession() passed after 0.993 seconds.
✔ Test run with 93 tests in 11 suites passed after 1.039 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (756.717 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testCatalogCoversEverySpecScreenWithoutContradictions]' passed (0.093 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testGridReflowsToOneColumnAtAX5]' passed (11.017 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testLibraryLabelsAndTouchTargets]' passed (6.259 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (9.657 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (5.138 seconds).
Test Case '-[PocketGBUITests.ShellLibraryTests testDetailsAndHideGame]' passed (14.808 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (20.252 seconds).
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
