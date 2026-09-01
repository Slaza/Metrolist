/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.imageLoader
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoSearchLocalLimitKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.mediaItems
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AndroidAutoYouTubePlaylistsKey
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.deserializeSections
import com.metrolist.music.ui.screens.settings.serializeSections
import kotlinx.coroutines.withContext

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()
    
    private val algorithmicCache = ConcurrentHashMap<String, List<SongItem>>()
    private val lastCacheTime = ConcurrentHashMap<String, Long>()
    private val CACHE_EXPIRATION = 15 * 60 * 1000L // 15 minutes

    private fun getCachedAlgorithmic(key: String): List<SongItem>? {
        val lastTime = lastCacheTime[key] ?: 0L
        if (System.currentTimeMillis() - lastTime > CACHE_EXPIRATION) {
            algorithmicCache.remove(key)
            return null
        }
        return algorithmicCache[key]
    }

    private fun setCachedAlgorithmic(key: String, songs: List<SongItem>) {
        algorithmicCache[key] = songs
        lastCacheTime[key] = System.currentTimeMillis()
    }

    private suspend fun fetchDiscoverSongs(): List<SongItem> {
        val cached = getCachedAlgorithmic("discover")
        if (cached != null) return cached

        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val home = YouTube.home().getOrNull()
        
        val recommendations = mutableListOf<SongItem>()

        // 1. Try Home Feed (Optimized filter)
        home?.sections?.filter { section ->
            val title = section.title.lowercase()
            title.contains("discover") || 
            title.contains("recommendation") || 
            title.contains("for you") ||
            title.contains("mixed for you") ||
            title.contains("pick up") ||
            title.contains("listen again")
        }?.flatMap { it.items }?.filterIsInstance<SongItem>()?.let {
            recommendations.addAll(it)
        }
        
        // 2. Fallback: Quick Picks (Fast)
        if (recommendations.size < 10) {
            home?.sections?.find { it.title.lowercase().contains("quick pick") }
                ?.items?.filterIsInstance<SongItem>()?.let {
                    recommendations.addAll(it)
                }
        }

        // 3. Fallback: Related songs based on 1 recent like (Parallel fetch)
        if (recommendations.size < 5) {
            val likedSongs = database.likedSongsByCreateDateAsc().first()
            if (likedSongs.isNotEmpty()) {
                val seed = likedSongs.shuffled().first()
                YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()?.relatedEndpoint?.let { endpoint ->
                    YouTube.related(endpoint).getOrNull()?.songs?.let { songs ->
                        recommendations.addAll(songs)
                    }
                }
            }
        }

        val result = recommendations
            .filter { !hideExplicit || !it.explicit }
            .filter { !hideVideoSongs || !it.isVideoSong }
            .distinctBy { it.id }
            .take(100)
        
        setCachedAlgorithmic("discover", result)
        return result
    }

    private suspend fun fetchNewReleaseSongs(): List<SongItem> {
        val cached = getCachedAlgorithmic("new_releases")
        if (cached != null) return cached

        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val list = mutableListOf<SongItem>()
        
        // 1. Fetch the official New Releases album list (the URL user provided)
        val albums = YouTube.newReleaseAlbums().getOrNull()?.take(15) ?: emptyList()
        
        // 2. Fetch songs from these albums in parallel
        if (albums.isNotEmpty()) {
            coroutineScope {
                albums.map { album ->
                    async {
                        YouTube.album(album.browseId).getOrNull()?.songs ?: emptyList()
                    }
                }.awaitAll().flatten().let { list.addAll(it) }
            }
        }
        
        // 3. Fallback to Home feed if needed
        if (list.size < 10) {
            YouTube.home().getOrNull()?.sections?.filter { 
                val t = it.title.lowercase()
                t.contains("new release") || t.contains("latest") || t.contains("new")
            }?.flatMap { it.items }?.filterIsInstance<SongItem>()?.let {
                list.addAll(it)
            }
        }
        
        val result = list.filter { !hideExplicit || !it.explicit }.distinctBy { it.id }.take(150)
        setCachedAlgorithmic("new_releases", result)
        return result
    }
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}

    fun release() {
        scope.cancel()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                .build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> addToTargetPlaylist()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> =
        Futures.immediateFuture(
            MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET),
        )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootExtras = Bundle().apply {
            putInt(
                androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
                androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
            // Hint for Android Auto to show the children as top-level tabs if supported
            putBoolean("androidx.media.utils.EXTRAS_KEY_CONTENT_STYLE_SUPPORTED", true)
            putInt("androidx.media.utils.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE", 1)
        }

        return Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setTitle("Metrolist")
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .setExtras(rootExtras)
                            .build(),
                    ).build(),
                params,
            ),
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            try {
            LibraryResult.ofItemList(
                when (parentId) {
                    MusicService.ROOT -> {
                        val sectionsRaw = context.dataStore.get(
                            AndroidAutoSectionsOrderKey,
                            serializeSections(deserializeSections(""))
                        )
                        val sections = deserializeSections(sectionsRaw)
                        sections
                            .filter { (_, enabled) -> enabled }
                            .ifEmpty { listOf(AndroidAutoSection.LIKED to true) }
                            .map { (section, _) ->
                                when (section) {
                                    AndroidAutoSection.DISCOVER -> browsableMediaItem(
                                        MusicService.DISCOVER,
                                        context.getString(R.string.discover),
                                        null,
                                        drawableUri(R.drawable.discover_tune),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                    AndroidAutoSection.NEW_RELEASES -> browsableMediaItem(
                                        MusicService.NEW_RELEASES,
                                        context.getString(R.string.new_releases),
                                        null,
                                        drawableUri(R.drawable.newspaper),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                    AndroidAutoSection.LIKED -> browsableMediaItem(
                                        "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                                        context.getString(R.string.liked_songs),
                                        null,
                                        drawableUri(R.drawable.favorite),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                   AndroidAutoSection.SONGS -> browsableMediaItem(
                                        MusicService.SONG,
                                        context.getString(R.string.songs),
                                        null,
                                        drawableUri(R.drawable.music_note),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                    AndroidAutoSection.ARTISTS -> browsableMediaItem(
                                        MusicService.ARTIST,
                                        context.getString(R.string.artists),
                                        null,
                                        drawableUri(R.drawable.artist),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                    )
                                    AndroidAutoSection.ALBUMS -> browsableMediaItem(
                                        MusicService.ALBUM,
                                        context.getString(R.string.albums),
                                        null,
                                        drawableUri(R.drawable.album),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                    )
                                    AndroidAutoSection.PLAYLISTS -> browsableMediaItem(
                                        MusicService.PLAYLIST,
                                        context.getString(R.string.playlists),
                                        null,
                                        drawableUri(R.drawable.queue_music),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                    )
                                    AndroidAutoSection.MIXES -> browsableMediaItem(
                                        MusicService.YOUTUBE_PLAYLIST,
                                        context.getString(R.string.mixes),
                                        null,
                                        drawableUri(R.drawable.explore_outlined),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                    )
                                }
                            }
                    }


                    MusicService.SONG -> database.songsByCreateDateAsc().first()
                        .drop(page * pageSize)
                        .take(pageSize)
                        .map { it.toMediaItem(parentId) }

                    MusicService.ARTIST ->
                        database.artistsByCreateDateAsc().first()
                            .drop(page * pageSize)
                            .take(pageSize)
                            .map { artist ->
                            browsableMediaItem(
                                "${MusicService.ARTIST}/${artist.id}",
                                artist.artist.name,
                                context.resources.getQuantityString(
                                    R.plurals.n_song,
                                    artist.songCount,
                                    artist.songCount
                                ),
                                artist.artist.thumbnailUrl?.toUri(),
                                MediaMetadata.MEDIA_TYPE_ARTIST,
                            )
                        }

                    MusicService.ALBUM ->
                        database.albumsByCreateDateAsc().first()
                            .drop(page * pageSize)
                            .take(pageSize)
                            .map { album ->
                            browsableMediaItem(
                                "${MusicService.ALBUM}/${album.id}",
                                album.album.title,
                                album.artists.joinToString {
                                    it.name
                                },
                                album.album.thumbnailUrl?.toUri(),
                                MediaMetadata.MEDIA_TYPE_ALBUM,
                            )
                        }

                    MusicService.DISCOVER -> {
                        val songs = fetchDiscoverSongs()
                        songs.map { songItem ->
                            MediaItem.Builder()
                                .setMediaId("${MusicService.DISCOVER}/${songItem.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(songItem.title)
                                        .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                        .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                        .setArtworkUri(songItem.thumbnail.toUri())
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        }.drop(page * pageSize).take(pageSize)
                    }

                    MusicService.NEW_RELEASES -> {
                        val songs = fetchNewReleaseSongs()
                        songs.map { songItem ->
                             MediaItem.Builder()
                                .setMediaId("${MusicService.NEW_RELEASES}/${songItem.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(songItem.title)
                                        .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                        .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                        .setArtworkUri(songItem.thumbnail.toUri())
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        }.drop(page * pageSize).take(pageSize)
                    }

                    MusicService.PLAYLIST -> {
                        val likedSongCount = database.likedSongsCount().first()
                        val downloadedSongCount = downloadUtil.downloads.value.size

                        val fixedItems = listOf(
                            browsableMediaItem(
                                "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                                context.getString(R.string.liked_songs),
                                context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                                drawableUri(R.drawable.favorite),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                                context.getString(R.string.downloaded_songs),
                                context.resources.getQuantityString(R.plurals.n_song, downloadedSongCount, downloadedSongCount),
                                drawableUri(R.drawable.download),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                        )
                        
                        val userPlaylists = database.playlistsByCreateDateAsc().first().map { playlist ->
                            browsableMediaItem(
                                "${MusicService.PLAYLIST}/${playlist.id}",
                                playlist.playlist.name,
                                context.resources.getQuantityString(R.plurals.n_song, playlist.songCount, playlist.songCount),
                                playlist.thumbnails.firstOrNull()?.toUri(),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            )
                        }
                        
                        (fixedItems + userPlaylists)
                            .drop(page * pageSize)
                            .take(pageSize)
                    }

                    MusicService.YOUTUBE_PLAYLIST -> {
                        try {
                            val playlists = YouTube.mixedForYou().getOrNull() ?: emptyList()

                            // Drop playlists already saved to the local library,
                            // which are exposed under MusicService.PLAYLIST.
                            val savedBrowseIds = database.playlistsByCreateDateAsc()
                                .first()
                                .mapNotNullTo(mutableSetOf()) { it.playlist.browseId }

                            playlists
                                .filterNot { it.id in savedBrowseIds }
                                .distinctBy { it.id }
                                .map { playlist ->
                                    browsableMediaItem(
                                        "${MusicService.YOUTUBE_PLAYLIST}/${playlist.id}",
                                        playlist.title,
                                        playlist.author?.name,
                                        playlist.thumbnail?.toUri(),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                }
                                .drop(page * pageSize)
                                .take(pageSize)
                        } catch (e: Exception) {
                            reportException(e)
                            emptyList()
                        }
                    }

                    MusicService.PODCAST -> {
                        val podcasts = database.subscribedPodcasts().first().map { podcast ->
                            browsableMediaItem(
                                "${MusicService.PLAYLIST}/${podcast.id}",
                                podcast.title,
                                podcast.author,
                                podcast.thumbnailUrl?.toUri(),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            )
                        }
                        
                        val ytPodcasts = YouTube.savedPodcastShows().getOrNull()?.map { podcast ->
                            browsableMediaItem(
                                "${MusicService.PLAYLIST}/${podcast.id}",
                                podcast.title,
                                podcast.author?.name,
                                podcast.thumbnail?.toUri(),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            )
                        }.orEmpty()
                        
                        (podcasts + ytPodcasts).distinctBy { it.mediaId }.drop(page * pageSize).take(pageSize)
                    }

                    "@queue@" -> {
                        val currentIndex = session.player.currentMediaItemIndex
                        session.player.mediaItems
                            .drop(currentIndex)
                            .take(50)
                            .drop(page * pageSize)
                            .take(pageSize)
                    }

                    else -> when {
                        parentId.endsWith("SESSION_QUEUE") -> {
                            val currentIndex = session.player.currentMediaItemIndex
                            session.player.mediaItems
                                .drop(currentIndex)
                                .take(50)
                                .drop(page * pageSize)
                                .take(pageSize)
                        }

                        parentId.startsWith("${MusicService.ARTIST}/") ->
                                database.artistSongsByCreateDateAsc(parentId.removePrefix("${MusicService.ARTIST}/"))
                                    .first()
                                    .drop(page * pageSize)
                                    .take(pageSize)
                                    .map {
                                        it.toMediaItem(parentId)
                                    }

                            parentId.startsWith("${MusicService.ALBUM}/") ->
                                database.albumSongs(parentId.removePrefix("${MusicService.ALBUM}/"))
                                    .first()
                                    .drop(page * pageSize)
                                    .take(pageSize)
                                    .map {
                                        it.toMediaItem(parentId)
                                    }

                            parentId.startsWith("${MusicService.PLAYLIST}/") -> {
                                val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")
                                val songs = when (playlistId) {
                                    PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(
                                        SongSortType.CREATE_DATE,
                                        true
                                    )

                                    PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                                        val downloads = downloadUtil.downloads.value
                                        database
                                            .allSongs()
                                            .flowOn(Dispatchers.IO)
                                            .map { songs ->
                                                songs.filter {
                                                    downloads[it.id]?.state == Download.STATE_COMPLETED
                                                }
                                            }.map { songs ->
                                                songs
                                                    .map { it to downloads[it.id] }
                                                    .sortedBy { it.second?.updateTimeMs ?: 0L }
                                                    .map { it.first }
                                            }
                                    }

                                    else ->
                                        database.playlistSongs(playlistId).map { list ->
                                            list.map { it.song }
                                        }
                                }.first()

                                val shuffleItem = MediaItem.Builder()
                                    .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(context.getString(R.string.shuffle))
                                            .setArtworkUri(drawableUri(R.drawable.shuffle))
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .build()
                                    ).build()

                                if (page == 0) {
                                    listOf(shuffleItem) + songs.take(pageSize - 1).map { it.toMediaItem(parentId) }
                                } else {
                                    songs.drop(page * pageSize - 1).take(pageSize).map { it.toMediaItem(parentId) }
                                }
                            }

                            parentId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/") -> {
                                val playlistId = parentId.removePrefix("${MusicService.YOUTUBE_PLAYLIST}/")
                                try {
                                    val songs = YouTube.playlist(playlistId).getOrNull()?.songs
                                        ?.take(100)
                                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        ?: emptyList()

                                    val shuffleItem = MediaItem.Builder()
                                        .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(context.getString(R.string.shuffle))
                                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                                .setIsPlayable(true)
                                                .setIsBrowsable(false)
                                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                .build()
                                        ).build()

                                    if (page == 0) {
                                        listOf(shuffleItem) + songs.take(pageSize - 1).map { songItem ->
                                            MediaItem.Builder()
                                                .setMediaId("$parentId/${songItem.id}")
                                                .setMediaMetadata(
                                                    MediaMetadata.Builder()
                                                        .setTitle(songItem.title)
                                                        .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                                        .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                                        .setArtworkUri(songItem.thumbnail.toUri())
                                                        .setIsPlayable(true)
                                                        .setIsBrowsable(false)
                                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                        .build()
                                                )
                                                .build()
                                        }
                                    } else {
                                        songs.drop(page * pageSize - 1).take(pageSize).map { songItem ->
                                            MediaItem.Builder()
                                                .setMediaId("$parentId/${songItem.id}")
                                                .setMediaMetadata(
                                                    MediaMetadata.Builder()
                                                        .setTitle(songItem.title)
                                                        .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                                        .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                                        .setArtworkUri(songItem.thumbnail.toUri())
                                                        .setIsPlayable(true)
                                                        .setIsBrowsable(false)
                                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                        .build()
                                                )
                                                .build()
                                        }
                                    }
                                } catch (e: Exception) {
                                    reportException(e)
                                    emptyList()
                                }
                            }

                            else -> emptyList()
                        }
                },
                params,
            )
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            try {
                database.song(mediaId).first()?.toMediaItem()?.let {
                    LibraryResult.ofItem(it, null)
                } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 1, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList(), params)
            }

            try {
                val searchResults = mutableListOf<MediaItem>()
                val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)
                val allLocalSongs = database.searchSongsExtended(query, limit).first()

                val totalLocalItems = allLocalSongs.size
                val localItemsToDrop = page * pageSize

                if (localItemsToDrop < totalLocalItems) {
                    allLocalSongs.drop(localItemsToDrop).take(pageSize).forEach { song ->
                        searchResults.add(song.toMediaItem(
                            path = "${MusicService.SEARCH}/$query",
                            isPlayable = true,
                            isBrowsable = true
                        ))
                    }
                }

                if (searchResults.size < pageSize) {
                    try {
                        val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            ?.filter { onlineSong ->
                                !allLocalSongs.any { localSong ->
                                    localSong.id == onlineSong.id ||
                                    (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                     localSong.artists.any { artist ->
                                         onlineSong.artists.any {
                                             it.name.equals(artist.name, ignoreCase = true)
                                         }
                                     })
                                }
                            } ?: emptyList()

                        val onlineItemsToSkip = (page * pageSize - totalLocalItems).coerceAtLeast(0)
                        val onlineItemsToTake = pageSize - searchResults.size

                        onlineResults.drop(onlineItemsToSkip).take(onlineItemsToTake).forEach { songItem ->
                            try {
                                database.query { insert(songItem.toMediaMetadata()) }
                            } catch (e: Exception) {
                            }
                            
                            searchResults.add(
                                MediaItem.Builder()
                                    .setMediaId("${MusicService.SEARCH}/$query/${songItem.id}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(songItem.title)
                                            .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                            .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                                            .setArtworkUri(songItem.thumbnail.toUri())
                                            .setIsPlayable(true)
                                            .setIsBrowsable(true)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
                
                LibraryResult.ofItemList(searchResults, params)
                
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future(Dispatchers.IO) {
            val defaultResult = MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val voiceQuery = mediaItems.firstOrNull()?.requestMetadata?.searchQuery

            val path = if (!voiceQuery.isNullOrBlank()) {
                listOf(MusicService.SEARCH, voiceQuery, "")
            } else {
                mediaItems.firstOrNull()?.mediaId?.split("/")
            } ?: return@future defaultResult

            val maxQueueSize = 1000 // Safely handle large playlists for Android Auto

            when (path.firstOrNull()) {
                MusicService.SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val allSongs = database.songsByCreateDateAsc().first()
                    
                    val index = allSongs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0
                    
                    // Windowing to prevent Binder transaction buffer overflow (1MB limit)
                    // 1000 items is a safe upper bound that provides a good user experience
                    val start = (index - 50).coerceAtLeast(0)
                    val end = (start + maxQueueSize).coerceAtMost(allSongs.size)
                    val actualStart = (end - maxQueueSize).coerceAtLeast(0)
                    
                    val window = allSongs.subList(actualStart, end)
                    val newIndex = index - actualStart
                    
                    MediaItemsWithStartPosition(
                        window.map { it.toMediaItem() },
                        newIndex,
                        startPositionMs
                    )
                }

                MusicService.ARTIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val artistId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = database.artistSongsByCreateDateAsc(artistId).first()

                    val index = songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0
                    val start = (index - 50).coerceAtLeast(0)
                    val end = (start + maxQueueSize).coerceAtMost(songs.size)
                    val actualStart = (end - maxQueueSize).coerceAtLeast(0)
                    
                    val window = songs.subList(actualStart, end)
                    val newIndex = index - actualStart

                    MediaItemsWithStartPosition(
                        window.map { it.toMediaItem() },
                        newIndex,
                        startPositionMs
                    )
                }

                MusicService.ALBUM -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val albumId = path.getOrNull(1) ?: return@future defaultResult
                    val albumWithSongs = database.albumWithSongs(albumId).first() ?: return@future defaultResult
                    
                    val songs = albumWithSongs.songs
                    val index = songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0
                    
                    MediaItemsWithStartPosition(
                        songs.map { it.toMediaItem() },
                        index,
                        startPositionMs
                    )
                }

                MusicService.PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = when (playlistId) {
                        PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
                        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                            val downloads = downloadUtil.downloads.value
                            database
                                .allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { songs ->
                                    songs.filter {
                                        downloads[it.id]?.state == Download.STATE_COMPLETED
                                    }
                                }.map { songs ->
                                    songs
                                        .map { it to downloads[it.id] }
                                        .sortedBy { it.second?.updateTimeMs ?: 0L }
                                        .map { it.first }
                                }
                        }
                        else -> database.playlistSongs(playlistId).map { list ->
                            list.map { it.song }
                        }
                    }.first()

                    if (songId == MusicService.SHUFFLE_ACTION) {
                        // For shuffle, pick 1000 random items to keep Binder transactions fast
                        val shuffled = songs.shuffled().take(maxQueueSize)
                        MediaItemsWithStartPosition(
                            shuffled.map { it.toMediaItem() },
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        val index = songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0
                        val start = (index - 50).coerceAtLeast(0)
                        val end = (start + maxQueueSize).coerceAtMost(songs.size)
                        val actualStart = (end - maxQueueSize).coerceAtLeast(0)
                        
                        val window = songs.subList(actualStart, end)
                        val newIndex = index - actualStart

                        MediaItemsWithStartPosition(
                            window.map { it.toMediaItem() },
                            newIndex,
                            startPositionMs
                        )
                    }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult

                    val playlistResult = YouTube.playlist(playlistId).getOrNull()
                    val songs = playlistResult?.songs ?: emptyList()

                    if (songId == MusicService.SHUFFLE_ACTION) {
                        val shuffled = songs.shuffled().take(maxQueueSize).map { it.toMediaItem() }
                        MediaItemsWithStartPosition(
                            shuffled,
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        val index = songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0
                        // Mixes/Playlists from YTM often return only 100 items initially anyway
                        val start = (index - 50).coerceAtLeast(0)
                        val end = (start + maxQueueSize).coerceAtMost(songs.size)
                        val actualStart = (end - maxQueueSize).coerceAtLeast(0)
                        
                        val window = songs.subList(actualStart, end)
                        val newIndex = index - actualStart

                        MediaItemsWithStartPosition(
                            window.map { it.toMediaItem() },
                            newIndex,
                            C.TIME_UNSET
                        )
                    }
                }

                MusicService.DISCOVER -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = fetchDiscoverSongs()
                    
                    // Ensure the selected song is in the list (Fast path)
                    val recommendations = songs.toMutableList()
                    if (recommendations.none { it.id == songId }) {
                        database.song(songId).first()?.let { s ->
                            recommendations.add(0, SongItem(
                                id = s.id,
                                title = s.song.title,
                                artists = s.artists.map { com.metrolist.innertube.models.Artist(it.name, it.id) },
                                thumbnail = s.song.thumbnailUrl ?: "",
                                explicit = s.song.explicit
                            ))
                        }
                    }
                    
                    val allSongs = recommendations.map { it.toMediaItem() }
                    
                    MediaItemsWithStartPosition(
                        allSongs,
                        allSongs.indexOfFirst { it.mediaId == songId || it.mediaId.endsWith(songId) }.coerceAtLeast(0),
                        C.TIME_UNSET
                    )
                }

                MusicService.NEW_RELEASES -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = fetchNewReleaseSongs()
                    
                    val list = songs.toMutableList()
                    // Ensure the selected song is in the list
                    if (list.none { it.id == songId }) {
                        database.song(songId).first()?.let { s ->
                            list.add(0, SongItem(
                                id = s.id,
                                title = s.song.title,
                                artists = s.artists.map { com.metrolist.innertube.models.Artist(it.name, it.id) },
                                thumbnail = s.song.thumbnailUrl ?: "",
                                explicit = s.song.explicit
                            ))
                        }
                    }
                    
                    val allSongs = list.map { it.toMediaItem() }
                    
                    MediaItemsWithStartPosition(
                        allSongs,
                        allSongs.indexOfFirst { it.mediaId == songId || it.mediaId.endsWith(songId) }.coerceAtLeast(0),
                        C.TIME_UNSET
                    )
                }

                MusicService.SEARCH -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val searchQuery = path.getOrNull(1) ?: return@future defaultResult

                    val isVoiceSearch = songId.isBlank() && searchQuery.isNotBlank()

                    if (isVoiceSearch) {
                        //Search if the voiceQuery is about a local playlist and play only the songs in that playlist
                        val localPlaylists = database.searchPlaylists(searchQuery).first()
                        val exactLocalPlaylist = localPlaylists.firstOrNull {
                            it.playlist.name.equals(searchQuery, ignoreCase = true)
                        }
                        if (exactLocalPlaylist != null) {
                            val playlistSongs = database.playlistSongs(exactLocalPlaylist.playlist.id).first()
                            if (playlistSongs.isNotEmpty()) {
                                return@future MediaItemsWithStartPosition(
                                    playlistSongs.map { it.song.toMediaItem() },
                                    0,
                                    C.TIME_UNSET
                                )
                            }
                        }
                    }

                    val searchResults = mutableListOf<Song>()
                    val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)

                    val allLocalSongs = database.searchSongsExtended(searchQuery, limit).first()
                    searchResults.addAll(allLocalSongs)
                    if (!isVoiceSearch && songId.isNotBlank() && searchResults.indexOfFirst { it.id == songId } == -1) {
                        database.song(songId).first()?.let { searchResults.add(it) }
                    }
                    
                    try {
                        val onlineResults = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            ?.filter { onlineSong ->
                                !allLocalSongs.any { localSong ->
                                    localSong.id == onlineSong.id ||
                                    (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                     localSong.artists.any { artist ->
                                         onlineSong.artists.any {
                                             it.name.equals(artist.name, ignoreCase = true)
                                         }
                                     })
                                }
                            } ?: emptyList()

                        onlineResults.forEach { songItem ->
                            try {
                                database.query { insert(songItem.toMediaMetadata()) }
                                database.song(songItem.id).first()?.let { newSong ->
                                    searchResults.add(newSong)
                                }
                            } catch (e: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                    
                    if (searchResults.isEmpty()) {
                        return@future defaultResult
                    }

                    val selectedSong =
                        if (isVoiceSearch) {    //Check if the voiceQuery is about a specific song
                            val snapshot: List<Song> =
                                synchronized(searchResults) { searchResults.toList() }
                            VoiceSearchMatcher.findBest(searchQuery, snapshot)
                        } else {
                            searchResults.firstOrNull { it.id == songId }
                        }

                    if(context.dataStore.get(AutoRadioQueueKey, true)) {
                        val radioQueue = YouTubeQueue.radio(selectedSong?.toMediaMetadata() ?: return@future defaultResult)
                        val radioStatus = runCatching {
                            withContext(Dispatchers.IO) {
                                radioQueue
                                    .getInitialStatus()
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            }
                        }.getOrNull()

                        if (radioStatus != null && radioStatus.items.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                service.adoptQueue(radioQueue, radioStatus.title, radioStatus.items.size) //Used to make the radio queue load more songs when near the end
                            }
                            return@future MediaItemsWithStartPosition(
                                radioStatus.items,
                                radioStatus.items.indexOfFirst { it.mediaId == selectedSong.id }.coerceAtLeast(0),
                                C.TIME_UNSET,
                            )
                        }
                    }

                    val items = listOf(selectedSong?.toMediaItem() ?: return@future defaultResult)
                    withContext(Dispatchers.Main) {
                        service.adoptQueue(
                            ListQueue(
                                title = selectedSong.song.title,
                                items = items,
                            ),
                            title = selectedSong.song.title,
                        )
                    }
                    MediaItemsWithStartPosition(
                        items,
                        0,
                        C.TIME_UNSET
                    )
                }

                else -> defaultResult
            }
        }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false): MediaItem {
        return MediaItem
            .Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                 MediaMetadata
                     .Builder()
                     .setTitle(song.title)
                     .setSubtitle(artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                     .setArtist(artists.joinToArtistString(getArtistSeparator(context)) { it.name })
                     .setArtworkUri(song.thumbnailUrl?.toUri())
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }
}
