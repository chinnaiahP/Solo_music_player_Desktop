package solo.music.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import solo.music.core.LibrarySnapshot
import solo.music.core.Playlist
import solo.music.core.Track
import solo.music.core.favoriteTrackIds
import solo.music.core.reconcileFavorites
import solo.music.core.setFavorite
import solo.music.core.withFavoriteStatus
import solo.music.library.LibraryRepository
import solo.music.library.LocalMusicScanner
import solo.music.playback.DesktopPlayer
import solo.music.playback.WindowsSmtcBridge
import solo.music.online.MoodCategory
import solo.music.online.MusicPlaylist
import solo.music.online.MusicShelf
import solo.music.online.YouTubeMusicService
import java.io.File
import java.util.UUID

class AppController {
  private val repository = LibraryRepository.default()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableLibrary = MutableStateFlow(repository.snapshot().reconcileFavorites())
  val library: StateFlow<LibrarySnapshot> = mutableLibrary.asStateFlow()
  val player = DesktopPlayer()
  private val mediaSession = WindowsSmtcBridge(
    onAction = { action ->
      mutableNotice.value = "Headset control received: ${action.lowercase().replaceFirstChar(Char::uppercase)}"
      when (action) {
        "PLAY" -> player.resume()
        "PAUSE" -> player.pause()
        "NEXT" -> player.next()
        "PREVIOUS" -> player.previous()
      }
    },
    onError = { error -> mutableNotice.value = "Windows media controls unavailable: $error" }
  )
  private val onlineService = YouTubeMusicService()
  private val lyricsService = LyricsService(youTubeMusic = onlineService)

  data class LyricsState(
    val trackId: String? = null,
    val lines: List<LyricLine> = emptyList(),
    val englishLines: List<LyricLine> = emptyList(),
    val provider: String? = null,
    val loading: Boolean = false,
    val translating: Boolean = false,
    val translationError: String? = null,
  )

  private val mutableLyrics = MutableStateFlow(LyricsState())
  val lyrics: StateFlow<LyricsState> = mutableLyrics.asStateFlow()
  private var activeLyricsTrack: Track? = null
  private var lyricsFetchJob: Job? = null

  private val mutableOnlineHome = MutableStateFlow<List<MusicShelf>>(emptyList())
  val onlineHome: StateFlow<List<MusicShelf>> = mutableOnlineHome.asStateFlow()
  private val mutableOnlineResults = MutableStateFlow<List<Track>>(emptyList())
  val onlineResults: StateFlow<List<Track>> = mutableOnlineResults.asStateFlow()
  private val mutableOnlineLoading = MutableStateFlow(false)
  val onlineLoading: StateFlow<Boolean> = mutableOnlineLoading.asStateFlow()
  private val mutableOnlineError = MutableStateFlow<String?>(null)
  val onlineError: StateFlow<String?> = mutableOnlineError.asStateFlow()
  private var onlineSearchJob: Job? = null
  private var onlineHomeJob: Job? = null
  private var onlineDetailJob: Job? = null
  private val mutableOnlineMoods = MutableStateFlow<List<MoodCategory>>(emptyList())
  val onlineMoods: StateFlow<List<MoodCategory>> = mutableOnlineMoods.asStateFlow()
  private val mutableOnlineDetail = MutableStateFlow<MusicShelf?>(null)
  val onlineDetail: StateFlow<MusicShelf?> = mutableOnlineDetail.asStateFlow()
  private val mutableOnlineDetailLoading = MutableStateFlow(false)
  val onlineDetailLoading: StateFlow<Boolean> = mutableOnlineDetailLoading.asStateFlow()
  private val mutableNotice = MutableStateFlow<String?>(null)
  val notice: StateFlow<String?> = mutableNotice.asStateFlow()

  init {
    if (mutableLibrary.value != repository.snapshot()) {
      mutableLibrary.value = repository.update { it.reconcileFavorites() }
    }
    val preferences = mutableLibrary.value.preferences
    player.setVolume(preferences.volume)
    if (preferences.shuffle) player.toggleShuffle()
    repeat(preferences.repeatMode.coerceIn(0, 2)) { player.cycleRepeat() }
    player.trackResolver = { track, ok, err ->
      scope.launch {
        mutableNotice.value = "Buffering ${track.title}…"
        try {
          val audio = onlineService.downloadAudio(track.videoId!!)
          val resolved = track.copy(path = audio.absolutePath, remoteStreamUrl = null)
          player.updateQueueTrack(track.id) { resolved }
          mutableNotice.value = null
          ok(resolved)
        } catch (error: Exception) {
          mutableNotice.value = null
          err(error.message ?: "Could not load online audio")
        }
      }
    }
    scope.launch {
      player.state
        .map { state -> state.current?.let { it to state.isPlaying } }
        .distinctUntilChanged()
        .collect { state ->
          mediaSession.update(state?.first, state?.second ?: false)
          prefetchUpcoming()
        }
    }
    scope.launch {
      player.state.map { it.current?.id }.distinctUntilChanged().collectLatest { id ->
        val track = player.state.value.current?.takeIf { it.id == id }
        if (track == null) {
          activeLyricsTrack = null
          mutableLyrics.value = LyricsState()
        } else {
          fetchLyricsFor(track)
        }
      }
    }
    mediaSession.prepare()
    loadOnlineHome()
  }

  /** Warm the audio cache for the next online queue entries so skips feel instant. */
  private var prefetchJob: Job? = null
  private fun prefetchUpcoming() {
    if (prefetchJob?.isActive == true) return
    val state = player.state.value
    val pending = state.queue.drop(state.index + 1).filter { it.isOnline && it.remoteStreamUrl.isNullOrBlank() && !File(it.path).isFile }.take(2)
    if (pending.isEmpty()) return
    prefetchJob = scope.launch {
      pending.forEach { track ->
        try {
          val audio = onlineService.downloadAudio(track.videoId!!)
          player.updateQueueTrack(track.id) { it.copy(path = audio.absolutePath, remoteStreamUrl = null) }
        } catch (_: Exception) { }
      }
    }
  }

  fun loadOnlineHome() = requestOnlineHome(force = false)
  fun refreshOnlineHome() = requestOnlineHome(force = true)

  fun setLyricsMode(mode: String) {
    val safe = mode.takeIf { it in setOf("NATIVE", "ENGLISH", "OFF") } ?: "NATIVE"
    savePreferences { it.copy(lyricsMode = safe) }
    if (safe == "ENGLISH") {
      val track = player.state.value.current ?: activeLyricsTrack
      val lines = mutableLyrics.value.lines
      if (track != null && lines.isNotEmpty() && mutableLyrics.value.englishLines.isEmpty()) {
        scope.launch { translateLyrics(track, lines) }
      }
    }
  }

  fun setDeepLApiKey(key: String) {
    savePreferences { it.copy(deeplApiKey = key.trim()) }
    if (mutableLibrary.value.preferences.lyricsMode == "ENGLISH") {
      val track = player.state.value.current ?: activeLyricsTrack
      val lines = mutableLyrics.value.lines
      if (track != null && lines.isNotEmpty()) {
        scope.launch { translateLyrics(track, lines) }
      }
    }
  }

  fun loadLyrics(track: Track) {
    if (mutableLyrics.value.trackId == track.id && (mutableLyrics.value.loading || mutableLyrics.value.lines.isNotEmpty())) return
    fetchLyricsFor(track)
  }

  private fun fetchLyricsFor(track: Track) {
    if (mutableLyrics.value.trackId == track.id && mutableLyrics.value.lines.isNotEmpty()) {
      activeLyricsTrack = track
      if (mutableLibrary.value.preferences.lyricsMode == "ENGLISH" && mutableLyrics.value.englishLines.isEmpty()) {
        scope.launch { translateLyrics(track, mutableLyrics.value.lines) }
      }
      return
    }
    lyricsFetchJob?.cancel()
    activeLyricsTrack = track
    lyricsFetchJob = scope.launch {
      mutableLyrics.value = LyricsState(trackId = track.id, loading = true)
      val found = runCatching { lyricsService.get(track) }.getOrNull()
      currentCoroutineContext().ensureActive()
      val isStillTarget = player.state.value.current?.id == track.id ||
        (player.state.value.current == null && mutableLyrics.value.trackId == track.id)
      if (isStillTarget) {
        val loaded = LyricsState(
          trackId = track.id,
          lines = found?.lines.orEmpty(),
          provider = found?.provider,
          loading = false
        )
        mutableLyrics.value = loaded
        if (mutableLibrary.value.preferences.lyricsMode == "ENGLISH" && loaded.lines.isNotEmpty()) {
          translateLyrics(track, loaded.lines)
        }
      }
    }
  }

  private suspend fun translateLyrics(track: Track, lines: List<LyricLine>) {
    val key = mutableLibrary.value.preferences.deeplApiKey
    if (key.isBlank()) {
      if (mutableLyrics.value.trackId == track.id) {
        mutableLyrics.update { it.copy(translating = false, translationError = "Add a DeepL API key in Settings for English lyrics.") }
      }
      return
    }
    mutableLyrics.update { if (it.trackId == track.id) it.copy(translating = true, translationError = null) else it }
    try {
      val translated = withContext(Dispatchers.IO) { lyricsService.translateToEnglish(track, lines, key) }
      if (mutableLyrics.value.trackId == track.id) {
        mutableLyrics.update { if (it.trackId == track.id) it.copy(englishLines = translated, translating = false, translationError = null) else it }
      }
    } catch (error: Exception) {
      if (mutableLyrics.value.trackId == track.id) {
        mutableLyrics.update { if (it.trackId == track.id) it.copy(translating = false, translationError = error.message ?: "English translation failed") else it }
      }
    }
  }

  @Synchronized private fun requestOnlineHome(force: Boolean) {
    if (mutableOnlineLoading.value && !force) return
    onlineHomeJob?.cancel()
    onlineHomeJob = scope.launch(start = CoroutineStart.LAZY) {
      mutableOnlineLoading.value = true
      mutableOnlineError.value = null
      try {
        val preferences = mutableLibrary.value.preferences
        val country = preferences.countryCode.ifBlank { java.util.Locale.getDefault().country.ifBlank { "US" } }
        val shelves = onlineService.home(country, preferences.musicLanguages, preferences.musicComposers)
        currentCoroutineContext().ensureActive()
        mutableOnlineHome.value = shelves.map { shelf -> shelf.copy(tracks = shelf.tracks.map { it.withFavoriteStatus(mutableLibrary.value) }) }
        if (shelves.isEmpty()) mutableOnlineError.value = "YouTube Music returned no featured tracks."
        runCatching { mutableOnlineMoods.value = onlineService.moods(country) }
      } catch (error: kotlinx.coroutines.CancellationException) { throw error
      } catch (error: Exception) {
        mutableOnlineError.value = error.message ?: "Could not load online music"
      } finally { if (currentCoroutineContext()[Job] === onlineHomeJob) mutableOnlineLoading.value = false }
    }
    onlineHomeJob?.start()
  }

  private fun preferredCountry() = mutableLibrary.value.preferences.countryCode
    .ifBlank { java.util.Locale.getDefault().country.ifBlank { "US" } }

  fun openOnlineShelf(shelf: MusicShelf) {
    mutableOnlineDetail.value = shelf
  }

  /** Open a YouTube Music playlist/album: loads its full track list into the online detail page. */
  fun openOnlinePlaylist(playlist: MusicPlaylist) {
    onlineDetailJob?.cancel()
    mutableOnlineDetail.value = MusicShelf(playlist.title, playlists = emptyList())
    onlineDetailJob = scope.launch {
      mutableOnlineDetailLoading.value = true
      try {
        val tracks = onlineService.playlistTracks(playlist.browseId, preferredCountry())
          .map { it.withFavoriteStatus(mutableLibrary.value) }
        mutableOnlineDetail.value = MusicShelf(playlist.title, tracks)
        if (tracks.isEmpty()) mutableNotice.value = "This playlist is empty or unavailable"
      } catch (error: Exception) {
        mutableOnlineError.value = error.message ?: "Could not load playlist"
        mutableNotice.value = mutableOnlineError.value
      } finally { mutableOnlineDetailLoading.value = false }
    }
  }

  /** Open a mood/genre page: shows the YouTube Music playlists curated for it. */
  fun openMoodCategory(category: MoodCategory) {
    onlineDetailJob?.cancel()
    mutableOnlineDetail.value = MusicShelf(category.title)
    onlineDetailJob = scope.launch {
      mutableOnlineDetailLoading.value = true
      try {
        val shelves = onlineService.moodPlaylists(category, preferredCountry())
        val playlists = shelves.flatMap { it.playlists }.distinctBy { it.browseId }
        val tracks = shelves.flatMap { it.tracks }.distinctBy { it.id }
          .map { it.withFavoriteStatus(mutableLibrary.value) }
        mutableOnlineDetail.value = MusicShelf(category.title, tracks, playlists)
        if (playlists.isEmpty() && tracks.isEmpty()) mutableNotice.value = "No playlists found for this mood"
      } catch (error: Exception) {
        mutableOnlineError.value = error.message ?: "Could not load mood playlists"
        mutableNotice.value = mutableOnlineError.value
      } finally { mutableOnlineDetailLoading.value = false }
    }
  }

  fun searchOnline(query: String) {
    onlineSearchJob?.cancel()
    if (query.isBlank()) { mutableOnlineResults.value = emptyList(); mutableOnlineError.value = null; return }
    onlineSearchJob = scope.launch {
      mutableOnlineLoading.value = true
      mutableOnlineError.value = null
      try {
        val country = mutableLibrary.value.preferences.countryCode.ifBlank { java.util.Locale.getDefault().country.ifBlank { "US" } }
        mutableOnlineResults.value = onlineService.search(query, country).map { it.withFavoriteStatus(mutableLibrary.value) }
        if (mutableOnlineResults.value.isEmpty()) mutableOnlineError.value = "No online songs matched this search."
      } catch (error: Exception) { mutableOnlineError.value = error.message ?: "Online search failed" }
      finally { mutableOnlineLoading.value = false }
    }
  }

  fun playOnline(track: Track) {
    if (track.videoId == null) return
    player.play(track.withFavoriteStatus(mutableLibrary.value))
  }

  /** Online queue members resolve lazily through DesktopPlayer.trackResolver. */
  fun playSelection(track: Track, source: List<Track>? = null) {
    playTrack(track, source)
  }

  fun addFiles(files: List<File>) {
    if (files.isEmpty()) return
    scope.launch {
      mutableNotice.value = "Adding music to your library…"
      val artDirectory = File(solo.music.core.appDataDirectory(), "artwork")
      val imported = withContext(Dispatchers.IO) { LocalMusicScanner(artDirectory).scan(files) }
      val old = repository.snapshot()
      val byId = old.tracks.associateBy { it.id }.toMutableMap()
      imported.forEach { found -> byId[found.id] = byId[found.id]?.copy(path = found.path, artworkPath = found.artworkPath ?: byId[found.id]?.artworkPath) ?: found }
      val next = repository.update { it.copy(tracks = byId.values.sortedBy { track -> track.title.lowercase() }, preferences = it.preferences.copy(musicFolders = (it.preferences.musicFolders + files.filter(File::isDirectory).map(File::getCanonicalPath)).distinct())) }
      mutableLibrary.value = next
      mutableNotice.value = if (imported.isEmpty()) "No supported music files found" else "Added ${imported.size} track${if (imported.size == 1) "" else "s"}"
    }
  }

  fun playTrack(track: Track, source: List<Track>? = null) {
    val tracks = (source ?: mutableLibrary.value.tracks).map { it.withFavoriteStatus(mutableLibrary.value) }
    val playTrack = track.withFavoriteStatus(mutableLibrary.value)
    val index = tracks.indexOfFirst { it.id == playTrack.id }.coerceAtLeast(0)
    player.playQueue(tracks, index)
    val next = repository.update { state ->
      val updated = if (state.tracks.any { it.id == playTrack.id }) state.tracks.map { if (it.id == playTrack.id) it.copy(playCount = it.playCount + 1, lastPlayedAt = System.currentTimeMillis()) else it }
        else state.tracks + playTrack.copy(playCount = 1, lastPlayedAt = System.currentTimeMillis())
      state.copy(tracks = updated, history = (listOf(playTrack.id) + state.history.filterNot { it == playTrack.id }).take(100)).reconcileFavorites()
    }
    mutableLibrary.value = next
  }

  fun toggleFavorite(track: Track) {
    val next = repository.update { state -> state.setFavorite(track, track.id !in state.favoriteTrackIds()) }
    mutableLibrary.value = next
    syncFavoriteCopies(track.id, track.id in next.favoriteTrackIds())
  }

  private fun syncFavoriteCopies(trackId: String, isFavorite: Boolean) {
    mutableOnlineResults.value = mutableOnlineResults.value.map { if (it.id == trackId) it.copy(isFavorite = isFavorite) else it }
    mutableOnlineHome.value = mutableOnlineHome.value.map { shelf ->
      shelf.copy(tracks = shelf.tracks.map { if (it.id == trackId) it.copy(isFavorite = isFavorite) else it })
    }
    player.setFavoriteStatus(trackId, isFavorite)
  }

  fun createPlaylist(name: String) {
    val clean = name.trim().take(60)
    if (clean.isBlank()) return
    val next = repository.update { it.copy(playlists = it.playlists + Playlist(UUID.randomUUID().toString(), clean)) }
    mutableLibrary.value = next
  }

  fun addToPlaylist(playlistId: String, track: Track) {
    val next = repository.update { state -> state.copy(
      tracks = if (state.tracks.any { it.id == track.id }) state.tracks.map { existing ->
        if (existing.id == track.id && playlistId == "liked") existing.copy(isFavorite = true) else existing
      } else state.tracks + track.copy(isFavorite = track.isFavorite || playlistId == "liked"),
      playlists = state.playlists.map { list -> if (list.id == playlistId) list.copy(trackIds = (list.trackIds + track.id).distinct()) else list }
    ).reconcileFavorites() }
    mutableLibrary.value = next
    if (playlistId == "liked") syncFavoriteCopies(track.id, true)
    mutableNotice.value = "Added to playlist"
  }

  fun renamePlaylist(playlistId: String, name: String) {
    val clean = name.trim().take(60)
    if (clean.isBlank()) return
    mutableLibrary.value = repository.update { state -> state.copy(playlists = state.playlists.map { if (it.id == playlistId && it.id != "liked") it.copy(name = clean) else it }) }
  }

  fun deletePlaylist(playlistId: String) {
    if (playlistId == "liked") return
    mutableLibrary.value = repository.update { state -> state.copy(playlists = state.playlists.filterNot { it.id == playlistId }) }
  }

  fun movePlaylistTrack(playlistId: String, trackId: String, offset: Int) {
    mutableLibrary.value = repository.update { state -> state.copy(playlists = state.playlists.map { playlist ->
      if (playlist.id != playlistId) playlist else {
        val from = playlist.trackIds.indexOf(trackId)
        if (from < 0) return@map playlist
        val to = (from + offset).coerceIn(0, playlist.trackIds.lastIndex)
        if (from == to) playlist else playlist.copy(trackIds = playlist.trackIds.toMutableList().apply { add(to, removeAt(from)) })
      }
    }) }
  }

  fun removeFromPlaylist(playlistId: String, trackId: String) {
    val next = repository.update { state ->
      val tracks = if (playlistId == "liked") state.tracks.map { if (it.id == trackId) it.copy(isFavorite = false) else it } else state.tracks
      state.copy(tracks = tracks, playlists = state.playlists.map { list -> if (list.id == playlistId) list.copy(trackIds = list.trackIds - trackId) else list }).reconcileFavorites()
    }
    mutableLibrary.value = next
    if (playlistId == "liked") syncFavoriteCopies(trackId, false)
  }

  fun removeTrack(trackId: String) {
    if (player.state.value.current?.id == trackId) player.stop()
    val next = repository.update { state -> state.copy(tracks = state.tracks.filterNot { it.id == trackId }, playlists = state.playlists.map { it.copy(trackIds = it.trackIds - trackId) }, history = state.history - trackId) }
    mutableLibrary.value = next
  }

  fun savePreferences(transform: (solo.music.core.PlayerPreferences) -> solo.music.core.PlayerPreferences) {
    val next = repository.update { it.copy(preferences = transform(it.preferences)) }
    mutableLibrary.value = next
  }

  fun dismissNotice() { mutableNotice.value = null }

  fun close() {
    mediaSession.close()
    scope.cancel()
    player.release()
  }
}
