# Android Auto: Add "New Releases" and "My Mixes" Tabs

This plan adds two new sections to Android Auto: **New Releases** (populating from YouTube Music's new albums) and **My Mixes** (populating from the "Mixed for you" section). It also adds settings to enable/disable these tabs and reorder them alongside existing sections.

## User Review Required

> [!NOTE]
> The existing "YouTube suggested playlists" option (which currently populates from the entire Home page) will be kept, but I will add these two specific tabs as requested. They will be integrated into the "Visible sections" reorderable list for a more consistent experience.

## Proposed Changes

### [innertube] (Backend API)

#### [MODIFY] [YouTube.kt](file:///C:/Users/Slaza/AndroidStudioProjects/Metrolist/innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt)
- Add `mixedForYou()` method to fetch playlists from `FEmusic_mixed_for_you`.
- Ensure `newReleaseAlbums()` is correctly exposed for use in the app module.

---

### [app] (Main Application)

#### [MODIFY] [MusicService.kt](file:///C:/Users/Slaza/AndroidStudioProjects/Metrolist/app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt)
- Add constants for the new media IDs:
    ```kotlin
    const val NEW_RELEASES = "new_releases"
    const val MY_MIXES = "my_mixes"
    const val YOUTUBE_ALBUM = "youtube_album"
    ```

#### [MODIFY] [metrolist_strings.xml](file:///C:/Users/Slaza/AndroidStudioProjects/Metrolist/app/src/main/res/values/metrolist_strings.xml)
- Add strings for the new sections:
    ```xml
    <string name="new_releases">New Releases</string>
    <string name="my_mixes">My Mixes</string>
    ```

#### [MODIFY] [AndroidAutoSettings.kt](file:///C:/Users/Slaza/AndroidStudioProjects/Metrolist/app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AndroidAutoSettings.kt)
- Add `NEW_RELEASES` and `MY_MIXES` to the `AndroidAutoSection` enum.
- Update `label()` and icon selection to support the new sections.
- Ensure the default order and serialization/deserialization logic handles the new enums.

#### [MODIFY] [MediaLibrarySessionCallback.kt](file:///C:/Users/Slaza/AndroidStudioProjects/Metrolist/app/src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt)
- **Root Loading**: Update `onGetChildren` for `MusicService.ROOT` to include `NEW_RELEASES` and `MY_MIXES` if enabled in settings.
- **Section Content**:
    - Handle `MusicService.NEW_RELEASES`: Call `YouTube.newReleaseAlbums()` and map to browsable `YOUTUBE_ALBUM` items.
    - Handle `MusicService.MY_MIXES`: Call `YouTube.mixedForYou()` (or fetch specific section) and map to browsable `YOUTUBE_PLAYLIST` items.
    - Handle `MusicService.YOUTUBE_ALBUM`: Fetch songs for a YouTube album using `YouTube.albumSongs()`.
- **Playback**: Update `onSetMediaItems` to handle `YOUTUBE_ALBUM` paths so songs from new releases can be played.
- **Helper**: Update `isBrowsableMediaId` to include the new paths.

## Verification Plan

### Automated Tests
- Run existing `AndroidAutoDatabasePaginationTest` to ensure no regressions in pagination logic.
- (Optional) Create a small test case to verify `AndroidAutoSection` serialization if logic becomes complex.

### Manual Verification
- **Settings**: Open Settings > Android Auto and verify that "New Releases" and "My Mixes" appear in the reorderable list. Toggle them off/on and check if they disappear/reappear in the root list.
- **Android Auto (Emulator/Head Unit)**:
    - Open Metrolist in Android Auto.
    - Verify that the new tabs appear in the root menu.
    - Open "New Releases" and verify albums are listed.
    - Open an album and verify songs can be played.
    - Open "My Mixes" and verify personal mixes are listed.
    - Open a mix and verify songs can be played.
