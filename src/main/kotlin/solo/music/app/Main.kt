package solo.music.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import solo.music.core.Playlist
import solo.music.core.Track
import solo.music.online.MoodCategory
import solo.music.online.MusicPlaylist
import solo.music.online.MusicShelf
import solo.music.playback.DesktopPlayer
import java.io.File
import java.net.URL
import java.util.Locale

// Echo Music / Solo Player Atmospheric Dark-Plum Palette
private val BgCanvas = Color(0xFF161119)
private val PanelSurface = Color(0xFF211925)
private val CardGlass = Color(0xFF2C2131)
private val CardElevated = Color(0xFF382A3F)
private val BorderSubtle = Color(0xFF47374F)
private val TextPrimary = Color(0xFFF6EFF7)
private val TextMuted = Color(0xFFB5A6BA)
private val AccentRose = Color(0xFFF4C7D0)
private val HeroTopRose = Color(0xFF4C3338)
private val HeroMidRose = Color(0xFF37262C)
private val HeroBottomDeep = Color(0xFF211926)

private val appIconBitmap: ImageBitmap by lazy {
  SkiaImage.makeFromEncoded(
    AppController::class.java.getResourceAsStream("/solo_music_icon.png")?.readBytes()
      ?: error("Missing solo_music_icon.png resource")
  ).toComposeImageBitmap()
}

private object ShortcutFocusGuard {
  private val focusedEditors = mutableSetOf<Any>()
  val isTextEntryFocused: Boolean get() = synchronized(focusedEditors) { focusedEditors.isNotEmpty() }
  fun setFocused(token: Any, focused: Boolean) = synchronized(focusedEditors) {
    if (focused) focusedEditors.add(token) else focusedEditors.remove(token)
  }
}

private fun Modifier.trackTextEntryFocus(): Modifier = composed {
  val token = remember { Any() }
  var wasFocused by remember { mutableStateOf(false) }
  DisposableEffect(token) { onDispose { ShortcutFocusGuard.setFocused(token, false) } }
  onFocusChanged { state ->
    if (wasFocused != state.isFocused) {
      wasFocused = state.isFocused
      ShortcutFocusGuard.setFocused(token, wasFocused)
    }
  }
}

private enum class Page(val title: String, val glyph: String) {
  Home("Home", "⌂"),
  Search("Search", "⌕"),
  Library("Your Library", "▤"),
  Lyrics("Lyrics", "✎"),
  Playlists("Playlists", "▣"),
  OnlineShelf("Online playlist", "♫"),
  History("Listening history", "◷"),
  Settings("Settings", "⚙")
}

fun main() = application {
  val app = remember { AppController() }
  var page by remember { mutableStateOf(Page.Home) }
  var navHistory by remember { mutableStateOf(listOf(Page.Home)) }
  var navIndex by remember { mutableIntStateOf(0) }
  var query by remember { mutableStateOf("") }
  var playlistName by remember { mutableStateOf("") }
  var creatingPlaylist by remember { mutableStateOf(false) }
  var queueOpen by remember { mutableStateOf(false) }
  var replayBannerVisible by remember { mutableStateOf(true) }
  var playlistsExpanded by remember { mutableStateOf(true) }
  var selectedPlaylist by remember { mutableStateOf<String?>(null) }
  val onlineDetail by app.onlineDetail.collectAsState()
  val onlineDetailLoading by app.onlineDetailLoading.collectAsState()
  val onlineMoods by app.onlineMoods.collectAsState()
  var editingPlaylist by remember { mutableStateOf<Playlist?>(null) }
  var deletingPlaylist by remember { mutableStateOf<Playlist?>(null) }
  var songPickerPlaylist by remember { mutableStateOf<String?>(null) }
  var personalizing by remember { mutableStateOf(false) }

  val library by app.library.collectAsState()
  val playback by app.player.state.collectAsState()
  val lyrics by app.lyrics.collectAsState()
  val notice by app.notice.collectAsState()
  val onlineHome by app.onlineHome.collectAsState()
  val onlineResults by app.onlineResults.collectAsState()
  val onlineLoading by app.onlineLoading.collectAsState()
  val onlineError by app.onlineError.collectAsState()
  val preferences = library.preferences
  val previewLyricsTrack = onlineHome.firstOrNull()?.tracks?.firstOrNull()
  val lyricsTargetId = playback.current?.id ?: previewLyricsTrack?.id
  var position by remember { mutableLongStateOf(0L) }

  fun navigateTo(target: Page, playlistId: String? = null) {
    selectedPlaylist = playlistId
    if (page == target && playlistId == null) return
    val trimmed = navHistory.take(navIndex + 1) + target
    navHistory = trimmed
    navIndex = trimmed.lastIndex
    page = target
  }

  fun goBack() {
    if (navIndex > 0) {
      navIndex -= 1
      page = navHistory[navIndex]
      if (page != Page.Playlists) selectedPlaylist = null
    }
  }

  fun goForward() {
    if (navIndex < navHistory.lastIndex) {
      navIndex += 1
      page = navHistory[navIndex]
      if (page != Page.Playlists) selectedPlaylist = null
    }
  }

  LaunchedEffect(playback.positionMs) { position = playback.positionMs }
  LaunchedEffect(page, playback.current?.id, previewLyricsTrack?.id) {
    if ((page == Page.Home || page == Page.Lyrics) && playback.current == null) {
      previewLyricsTrack?.let(app::loadLyrics)
    }
  }
  LaunchedEffect(playback.isPlaying) {
    while (playback.isPlaying) {
      delay(150)
      position = app.player.state.value.positionMs
    }
  }
  LaunchedEffect(notice) {
    if (notice != null) {
      delay(3500)
      app.dismissNotice()
    }
  }
  LaunchedEffect(preferences.countryCode) {
    if (preferences.countryCode.isBlank()) personalizing = true
  }
  LaunchedEffect(query, page) {
    if (page == Page.Search) {
      if (query.isBlank()) app.searchOnline("")
      else {
        delay(420)
        app.searchOnline(query)
      }
    }
  }

  fun handleShortcut(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown || ShortcutFocusGuard.isTextEntryFocused) return false
    return when {
      e.isCtrlPressed && e.key == Key.K -> { navigateTo(Page.Search); true }
      e.isCtrlPressed && e.key == Key.O -> { app.addFiles(DesktopFilePicker.audioFiles()); true }
      e.isCtrlPressed && e.key == Key.L -> { navigateTo(if (page == Page.Lyrics) Page.Home else Page.Lyrics); true }
      e.isCtrlPressed && e.key == Key.DirectionRight -> { app.player.next(); true }
      e.isCtrlPressed && e.key == Key.DirectionLeft -> { app.player.previous(); true }
      e.key == Key.Spacebar -> { app.player.toggle(); true }
      else -> false
    }
  }

  Window(
    onCloseRequest = { app.close(); exitApplication() },
    title = "Solo Music Player",
    icon = painterResource("solo_music_icon.png"),
    state = rememberWindowState(size = DpSize(1360.dp, 840.dp)),
    onKeyEvent = ::handleShortcut
  ) {
    LaunchedEffect(Unit) {
      repeat(20) {
        val awtWindow = java.awt.Window.getWindows().firstOrNull { it.isShowing }
        if (awtWindow != null) { WindowsTitleBar.applyDark(awtWindow); return@LaunchedEffect }
        delay(100)
      }
    }
    MaterialTheme(
      colorScheme = darkColorScheme(
        background = BgCanvas,
        surface = PanelSurface,
        primary = AccentRose,
        secondary = AccentRose,
        onBackground = TextPrimary,
        onSurface = TextPrimary
      )
    ) {
      Surface(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
          if (ShortcutFocusGuard.isTextEntryFocused) false
          else if (event.key == Key.Spacebar) {
            if (event.type == KeyEventType.KeyDown) app.player.toggle()
            true
          } else handleShortcut(event)
        },
        color = BgCanvas
      ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
          val isWide = maxWidth >= 1220.dp
          val isMedium = maxWidth >= 880.dp
          val showRightRail = isWide && page != Page.Lyrics

          Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
              // 1. Left Navigation Sidebar (Full on medium/wide, compact icon rail on narrow windows)
              if (isMedium) {
                Sidebar(
                  page = page,
                  playlists = library.playlists,
                  tracks = library.tracks,
                  selectedPlaylist = selectedPlaylist,
                  playlistsExpanded = playlistsExpanded,
                  onTogglePlaylists = { playlistsExpanded = !playlistsExpanded },
                  onSelect = { navigateTo(it) },
                  onOpenPlaylist = { id -> navigateTo(Page.Playlists, id) },
                  onQuickMood = { mood ->
                    query = mood
                    navigateTo(Page.Search)
                  },
                  onCreatePlaylist = { creatingPlaylist = true },
                  onFiles = { app.addFiles(DesktopFilePicker.audioFiles()) },
                  onFolder = { app.addFiles(listOfNotNull(DesktopFilePicker.musicFolder())) }
                )
              } else {
                CompactSidebar(
                  page = page,
                  onSelect = { navigateTo(it) },
                  onFiles = { app.addFiles(DesktopFilePicker.audioFiles()) }
                )
              }

              Spacer(Modifier.width(12.dp))

              // 2. Center & Right Workspace
              Column(Modifier.weight(1f).fillMaxHeight()) {
                TopHeaderBar(
                  query = query,
                  canGoBack = navIndex > 0,
                  canGoForward = navIndex < navHistory.lastIndex,
                  onBack = ::goBack,
                  onForward = ::goForward,
                  onQuery = {
                    query = it
                    if (page != Page.Search) navigateTo(Page.Search)
                  },
                  onClear = { query = "" },
                  onHistory = { navigateTo(Page.History) },
                  onTrending = {
                    query = "Top Weekly"
                    navigateTo(Page.Search)
                  },
                  onPersonalize = { personalizing = true },
                  onSettings = { navigateTo(Page.Settings) }
                )

                Spacer(Modifier.height(12.dp))

                Row(Modifier.weight(1f).fillMaxWidth()) {
                  Box(Modifier.weight(1f).fillMaxHeight()) {
                    MainPanel(
                      page = page,
                      playlistId = selectedPlaylist,
                      selectedOnlineShelf = onlineDetail,
                      onlineDetailLoading = onlineDetailLoading,
                      onOpenOnlinePlaylist = { playlist -> app.openOnlinePlaylist(playlist) },
                      tracks = library.tracks,
                      playlists = library.playlists,
                      history = library.history,
                      queue = playback.queue,
                      query = query,
                      currentTrack = playback.current,
                      lyrics = lyrics.lines.takeIf { lyrics.trackId == lyricsTargetId }.orEmpty(),
                      englishLyrics = lyrics.englishLines.takeIf { lyrics.trackId == lyricsTargetId }.orEmpty(),
                      lyricsLoading = lyrics.loading && lyrics.trackId == lyricsTargetId,
                      translationLoading = lyrics.translating && lyrics.trackId == lyricsTargetId,
                      translationError = lyrics.translationError.takeIf { lyrics.trackId == lyricsTargetId },
                      lyricsProvider = lyrics.provider.takeIf { lyrics.trackId == lyricsTargetId },
                      lyricsMode = preferences.lyricsMode,
                      onLyricsModeChange = app::setLyricsMode,
                      deeplApiKey = preferences.deeplApiKey,
                      onDeepLApiKeyChange = app::setDeepLApiKey,
                      isPlaying = playback.isPlaying,
                      shuffleEnabled = playback.shuffle,
                      repeatMode = playback.repeatMode,
                      playbackPosition = position,
                      playbackDuration = playback.durationMs,
                      onSeek = app.player::seekTo,
                      onOpenLyrics = { navigateTo(if (page == Page.Lyrics) Page.Home else Page.Lyrics) },
                      onOpenQueue = { queueOpen = true },
                      onPlay = app::playSelection,
                      onFavorite = app::toggleFavorite,
                      onCreatePlaylist = { creatingPlaylist = true },
                      onOpenPlaylist = { selectedPlaylist = it },
                      onRemoveTrack = app::removeTrack,
                      onAddFiles = { app.addFiles(DesktopFilePicker.audioFiles()) },
                      onAddFolder = { app.addFiles(listOfNotNull(DesktopFilePicker.musicFolder())) },
                      onPlaylistTrack = app::addToPlaylist,
                      onRemoveFromPlaylist = app::removeFromPlaylist,
                      onRenamePlaylist = { editingPlaylist = it },
                      onDeletePlaylist = { deletingPlaylist = it },
                      onPlayPlaylist = { list -> if (list.isNotEmpty()) app.playSelection(list.first(), list) },
                      onAddSongs = { songPickerPlaylist = it },
                      onMovePlaylistTrack = app::movePlaylistTrack,
                      onlineHome = onlineHome,
                      onlineResults = onlineResults,
                      onlineLoading = onlineLoading,
                      onlineError = onlineError,
                      onPlayOnline = app::playOnline,
                      onPersonalize = { personalizing = true },
                      onTogglePlayback = app.player::toggle,
                      onPrevious = app.player::previous,
                      onNext = app.player::next,
                      onToggleShuffle = {
                        app.player.toggleShuffle()
                        app.savePreferences { it.copy(shuffle = !it.shuffle) }
                      },
                      onCycleRepeat = {
                        app.player.cycleRepeat()
                        app.savePreferences { it.copy(repeatMode = (it.repeatMode + 1) % 3) }
                      }
                    )
                  }

                  if (showRightRail) {
                    Spacer(Modifier.width(12.dp))
                    // 3. Right Discovery & Replay Rail
                    OnlineFeedPanel(
                      shelves = onlineHome,
                      loading = onlineLoading,
                      error = onlineError,
                      replayVisible = replayBannerVisible,
                      onDismissReplay = { replayBannerVisible = false },
                      onOpenReplay = { navigateTo(Page.History) },
                      countryCode = preferences.countryCode.ifBlank { Locale.getDefault().country },
                      languages = preferences.musicLanguages,
                      composers = preferences.musicComposers,
                      onPlay = app::playOnline,
                      moods = onlineMoods,
                      onOpenMood = { mood -> app.openMoodCategory(mood); navigateTo(Page.OnlineShelf) },
                      onOpenPlaylist = { playlist -> app.openOnlinePlaylist(playlist); navigateTo(Page.OnlineShelf) },
                      onOpenShelf = { shelf -> app.openOnlineShelf(shelf); navigateTo(Page.OnlineShelf) },
                      onRefresh = app::refreshOnlineHome,
                      onPersonalize = { personalizing = true }
                    )
                  }
                }
              }
            }

            Spacer(Modifier.height(10.dp))

            if (playback.error != null || notice != null) {
              Text(
                text = playback.error ?: notice.orEmpty(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp, start = 6.dp),
                color = if (playback.error != null) Color(0xFFFF8585) else AccentRose,
                fontSize = 12.sp
              )
            }

            // 4. Persistent Bottom Transport Bar with Expressive Scalloped Button
            PlayerBar(
              track = playback.current,
              playing = playback.isPlaying,
              position = position,
              duration = playback.durationMs,
              volume = playback.volume,
              shuffle = playback.shuffle,
              repeat = playback.repeatMode,
              lyricsActive = page == Page.Lyrics,
              compact = !isMedium,
              onToggle = app.player::toggle,
              onPrevious = app.player::previous,
              onNext = app.player::next,
              onSeek = app.player::seekTo,
              onVolume = { value -> app.player.setVolume(value) },
              onVolumeFinished = { value -> app.savePreferences { it.copy(volume = value) } },
              onShuffle = {
                app.player.toggleShuffle()
                app.savePreferences { it.copy(shuffle = !it.shuffle) }
              },
              onRepeat = {
                app.player.cycleRepeat()
                app.savePreferences { it.copy(repeatMode = (it.repeatMode + 1) % 3) }
              },
              onQueue = { queueOpen = true },
              onOpenLyrics = { navigateTo(if (page == Page.Lyrics) Page.Home else Page.Lyrics) },
              onFavorite = { playback.current?.let(app::toggleFavorite) },
              onOpenNowPlaying = { navigateTo(Page.Home) }
            )
          }
        }

        // Modals & Drawers
        if (creatingPlaylist) {
          AppDialog(onDismiss = { creatingPlaylist = false }) {
            Text("Create playlist", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
              value = playlistName,
              onValueChange = { playlistName = it },
              singleLine = true,
              label = { Text("Playlist name") },
              modifier = Modifier.fillMaxWidth().trackTextEntryFocus()
            )
            Spacer(Modifier.height(16.dp))
            DialogActions(
              onCancel = { creatingPlaylist = false },
              confirmLabel = "Create",
              onConfirm = {
                app.createPlaylist(playlistName)
                playlistName = ""
                creatingPlaylist = false
              }
            )
          }
        }

        editingPlaylist?.let { playlist ->
          var name by remember(playlist.id) { mutableStateOf(playlist.name) }
          AppDialog(onDismiss = { editingPlaylist = null }) {
            Text("Rename playlist", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
              value = name,
              onValueChange = { name = it },
              singleLine = true,
              label = { Text("Playlist name") },
              modifier = Modifier.fillMaxWidth().trackTextEntryFocus()
            )
            Spacer(Modifier.height(16.dp))
            DialogActions(
              onCancel = { editingPlaylist = null },
              confirmLabel = "Save",
              onConfirm = {
                app.renamePlaylist(playlist.id, name)
                editingPlaylist = null
              }
            )
          }
        }

        deletingPlaylist?.let { playlist ->
          AppDialog(onDismiss = { deletingPlaylist = null }) {
            Text("Delete playlist?", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
              "${playlist.name} and its playlist entry will be removed. Your music files will stay in your library.",
              color = TextMuted,
              fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            DialogActions(
              onCancel = { deletingPlaylist = null },
              confirmLabel = "Delete",
              onConfirm = {
                app.deletePlaylist(playlist.id)
                if (selectedPlaylist == playlist.id) selectedPlaylist = null
                deletingPlaylist = null
              }
            )
          }
        }

        songPickerPlaylist?.let { playlistId ->
          PlaylistSongPicker(
            tracks = library.tracks,
            alreadyAdded = library.playlists.firstOrNull { it.id == playlistId }?.trackIds.orEmpty(),
            onAdd = { track -> app.addToPlaylist(playlistId, track) },
            onDismiss = { songPickerPlaylist = null }
          )
        }

        if (personalizing) {
          PersonalizationDialog(
            countryCode = preferences.countryCode,
            languages = preferences.musicLanguages,
            composers = preferences.musicComposers,
            onDismiss = { personalizing = false },
            onSave = { country, languages, composers ->
              app.savePreferences {
                it.copy(countryCode = country, musicLanguages = languages, musicComposers = composers)
              }
              personalizing = false
              app.refreshOnlineHome()
            }
          )
        }

        if (queueOpen) {
          AppDialog(onDismiss = { queueOpen = false }, width = 620.dp, height = 560.dp) {
            Text("Up next · ${playback.queue.size} tracks", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (playback.queue.isEmpty()) {
              Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState("Queue is empty", "Play any track or playlist to populate Up Next.")
              }
            } else {
              LazyColumn(Modifier.weight(1f)) {
                items(playback.queue) { track ->
                  TrackRow(
                    track = track,
                    active = playback.current?.id == track.id,
                    onPlay = { app.playTrack(track, playback.queue) },
                    onFavorite = { app.toggleFavorite(track) },
                    onRemove = {}
                  )
                }
              }
            }
            Spacer(Modifier.height(12.dp))
            DialogActions(onCancel = { queueOpen = false }, confirmLabel = "Close", onConfirm = { queueOpen = false }, cancelLabel = "")
          }
        }
      }
    }
  }
}

@Composable
private fun CompactSidebar(page: Page, onSelect: (Page) -> Unit, onFiles: () -> Unit) {
  Column(
    Modifier
      .width(66.dp)
      .fillMaxHeight()
      .background(PanelSurface, RoundedCornerShape(16.dp))
      .border(1.dp, BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
      .padding(vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Image(
      bitmap = appIconBitmap,
      contentDescription = "Solo Music",
      modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)),
      filterQuality = FilterQuality.High
    )
    Spacer(Modifier.height(18.dp))
    listOf(Page.Home, Page.Search, Page.Library, Page.Playlists, Page.Lyrics, Page.History).forEach { item ->
      val active = page == item
      Box(
        Modifier
          .padding(vertical = 4.dp)
          .size(42.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(if (active) CardElevated else Color.Transparent)
          .clickable { onSelect(item) },
        contentAlignment = Alignment.Center
      ) {
        Label(item.glyph, color = if (active) AccentRose else TextMuted, size = 17)
      }
    }
    Spacer(Modifier.weight(1f))
    SidebarUtilityIcon("＋", false, onFiles)
    Spacer(Modifier.height(8.dp))
    SettingsUtilityIcon(page == Page.Settings) { onSelect(Page.Settings) }
  }
}

@Composable
private fun Sidebar(
  page: Page,
  playlists: List<Playlist>,
  tracks: List<Track>,
  selectedPlaylist: String?,
  playlistsExpanded: Boolean,
  onTogglePlaylists: () -> Unit,
  onSelect: (Page) -> Unit,
  onOpenPlaylist: (String) -> Unit,
  onQuickMood: (String) -> Unit,
  onCreatePlaylist: () -> Unit,
  onFiles: () -> Unit,
  onFolder: () -> Unit
) {
  Column(
    Modifier
      .width(226.dp)
      .fillMaxHeight()
      .background(PanelSurface, RoundedCornerShape(16.dp))
      .border(1.dp, BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
      .padding(14.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
      Image(
        bitmap = appIconBitmap,
        contentDescription = "Solo Music",
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
        filterQuality = FilterQuality.High
      )
      Spacer(Modifier.width(10.dp))
      Column {
        Label("Solo Music", size = 16, weight = FontWeight.Bold)
        Label("DESKTOP EDITION", color = TextMuted, size = 9, weight = FontWeight.SemiBold)
      }
    }

    Spacer(Modifier.height(16.dp))

    listOf(Page.Home, Page.Search, Page.Library, Page.Playlists, Page.Lyrics).forEach { item ->
      val active = page == item
      Row(
        Modifier
          .fillMaxWidth()
          .padding(vertical = 2.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(if (active) CardElevated else Color.Transparent)
          .clickable { onSelect(item) }
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Label(item.glyph, color = if (active) AccentRose else TextMuted, size = 16)
        Spacer(Modifier.width(12.dp))
        Label(
          item.title,
          color = if (active) TextPrimary else TextMuted,
          size = 13,
          weight = if (active) FontWeight.SemiBold else FontWeight.Medium
        )
      }
    }

    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = BorderSubtle.copy(alpha = 0.5f))

    Row(
      Modifier.fillMaxWidth().clickable(onClick = onTogglePlaylists).padding(horizontal = 6.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Label("PLAYLISTS", Modifier.weight(1f), TextMuted, 10, FontWeight.Bold)
      Text(if (playlistsExpanded) "⌃" else "⌄", color = TextMuted, fontSize = 13.sp)
      Spacer(Modifier.width(8.dp))
      Text("＋", Modifier.clickable(onClick = onCreatePlaylist).padding(2.dp), color = AccentRose, fontSize = 16.sp)
    }

    if (playlistsExpanded) {
      listOf("Podcasts", "Workout", "Feel good", "Energize").forEach { genre ->
        Text(
          text = genre,
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onQuickMood(genre) }
            .padding(horizontal = 12.dp, vertical = 7.dp),
          color = TextPrimary.copy(alpha = 0.88f),
          fontSize = 12.sp
        )
      }
    }

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Label("CREATED PLAYLISTS", Modifier.weight(1f), TextMuted, 10, FontWeight.Bold)
      Text("All", Modifier.clickable { onSelect(Page.Playlists) }, color = AccentRose, fontSize = 10.sp)
    }
    Spacer(Modifier.height(6.dp))

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      playlists.take(6).forEach { playlist ->
        val isSelected = selectedPlaylist == playlist.id && page == Page.Playlists
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (isSelected) CardElevated else Color.Transparent)
            .clickable { onOpenPlaylist(playlist.id) }
            .padding(horizontal = 6.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          PlaylistArtwork(playlist, tracks, Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)))
          Spacer(Modifier.width(9.dp))
          Column(Modifier.weight(1f)) {
            Label(
              playlist.name,
              color = if (isSelected) TextPrimary else TextPrimary.copy(alpha = 0.9f),
              size = 11,
              weight = FontWeight.SemiBold,
              maxLines = 1
            )
            Label("${playlist.trackIds.size} songs", color = TextMuted, size = 9, maxLines = 1)
          }
        }
      }

      Spacer(Modifier.height(10.dp))
      Label("LOCAL LIBRARY", Modifier.padding(horizontal = 6.dp), TextMuted, 10, FontWeight.Bold)
      Spacer(Modifier.height(4.dp))
      SideAction("＋  Add audio files", onFiles)
      SideAction("▧  Scan music folder", onFolder)
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = BorderSubtle.copy(alpha = 0.5f))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      SidebarUtilityIcon("▤", page == Page.Library) { onSelect(Page.Library) }
      SidebarUtilityIcon("◷", page == Page.History) { onSelect(Page.History) }
      SidebarUtilityIcon("▣", page == Page.Playlists) { onSelect(Page.Playlists) }
      SettingsUtilityIcon(page == Page.Settings) { onSelect(Page.Settings) }
    }
  }
}

@Composable
private fun SidebarUtilityIcon(glyph: String, active: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .size(36.dp)
      .clip(CircleShape)
      .background(if (active) CardElevated else CardGlass.copy(alpha = 0.65f))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Label(glyph, color = if (active) AccentRose else TextMuted, size = 15)
  }
}

@Composable
private fun SettingsUtilityIcon(active: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .size(36.dp)
      .clip(CircleShape)
      .background(if (active) CardElevated else CardGlass.copy(alpha = 0.65f))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    GearIcon(tint = if (active) AccentRose else TextMuted, iconSize = 17.dp)
  }
}

/** Vector-drawn pair of intermeshing gears matching the hand-drawn transport icon style. */
@Composable
private fun GearIcon(tint: Color, iconSize: Dp = 18.dp) {
  Canvas(Modifier.size(iconSize)) {
    val w = size.minDimension
    fun gear(cx: Float, cy: Float, rOut: Float, teeth: Int, phaseDeg: Float): Path {
      val step = Math.PI * 2 / teeth
      val phase = Math.toRadians(phaseDeg.toDouble())
      val rRoot = rOut * 0.74f
      val toothHalf = step * 0.17
      val rootHalf = step * 0.33
      fun pt(r: Float, a: Double, off: Double) = Offset(
        cx + r * kotlin.math.cos(a + off).toFloat(),
        cy + r * kotlin.math.sin(a + off).toFloat()
      )
      val path = Path()
      path.fillType = PathFillType.EvenOdd
      for (i in 0 until teeth) {
        val a = phase + i * step
        val lead = pt(rRoot, a, -rootHalf)
        if (i == 0) path.moveTo(lead.x, lead.y) else path.lineTo(lead.x, lead.y)
        pt(rOut, a, -toothHalf).let { path.lineTo(it.x, it.y) }
        pt(rOut, a, toothHalf).let { path.lineTo(it.x, it.y) }
        pt(rRoot, a, rootHalf).let { path.lineTo(it.x, it.y) }
      }
      path.close()
      path.addOval(Rect(Offset(cx, cy), rOut * 0.34f))
      return path
    }
    drawPath(gear(w * 0.40f, w * 0.62f, w * 0.34f, 9, 0f), tint)
    drawPath(gear(w * 0.74f, w * 0.30f, w * 0.22f, 8, 20f), tint)
  }
}

@Composable
private fun SideAction(text: String, action: () -> Unit) = Text(
  text = text,
  modifier = Modifier
    .fillMaxWidth()
    .clip(RoundedCornerShape(8.dp))
    .clickable(onClick = action)
    .padding(horizontal = 8.dp, vertical = 7.dp),
  color = TextPrimary.copy(alpha = 0.85f),
  fontSize = 11.sp
)

@Composable
private fun TopHeaderBar(
  query: String,
  canGoBack: Boolean,
  canGoForward: Boolean,
  onBack: () -> Unit,
  onForward: () -> Unit,
  onQuery: (String) -> Unit,
  onClear: () -> Unit,
  onHistory: () -> Unit,
  onTrending: () -> Unit,
  onPersonalize: () -> Unit,
  onSettings: () -> Unit
) {
  Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
    CircleIconButton("‹", enabled = canGoBack, onClick = onBack)
    Spacer(Modifier.width(8.dp))
    CircleIconButton("›", enabled = canGoForward, onClick = onForward)
    Spacer(Modifier.width(12.dp))

    Row(
      Modifier
        .weight(1f, fill = false)
        .widthIn(min = 220.dp, max = 460.dp)
        .fillMaxHeight()
        .background(CardGlass, CircleShape)
        .border(1.dp, BorderSubtle.copy(alpha = 0.6f), CircleShape)
        .padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Label("⌕", color = TextMuted, size = 18)
      Spacer(Modifier.width(10.dp))
      Box(Modifier.weight(1f)) {
        if (query.isEmpty()) Label("Search songs, artists, albums...", color = TextMuted, size = 12, maxLines = 1)
        BasicTextField(
          value = query,
          onValueChange = onQuery,
          singleLine = true,
          cursorBrush = SolidColor(AccentRose),
          textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
          modifier = Modifier.fillMaxWidth().trackTextEntryFocus()
        )
      }
      if (query.isNotEmpty()) {
        Text("×", Modifier.clickable(onClick = onClear).padding(horizontal = 4.dp), color = TextMuted, fontSize = 17.sp)
      } else {
        Label("CTRL K", color = TextMuted.copy(alpha = 0.7f), size = 9, weight = FontWeight.Bold)
      }
    }

    Spacer(Modifier.weight(1f))
    Spacer(Modifier.width(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
      HeaderIconButton(onClick = onHistory, description = "Listening history") {
        Label("◷", color = TextMuted, size = 16)
      }
      HeaderIconButton(onClick = onTrending, description = "Trending") {
        Label("↗", color = TextMuted, size = 15)
      }
      HeaderIconButton(onClick = onPersonalize, description = "Music preferences") {
        TuneIcon(Modifier.size(17.dp), tint = TextMuted)
      }
      HeaderIconButton(onClick = onSettings, description = "Settings") {
        GearIcon(tint = TextMuted, iconSize = 19.dp)
      }
    }
  }
}

@Composable
private fun HeaderIconButton(onClick: () -> Unit, description: String = "", icon: @Composable () -> Unit) {
  Box(
    Modifier
      .size(36.dp)
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center
  ) {
    icon()
  }
}

@Composable
private fun CircleIconButton(glyph: String, enabled: Boolean = true, onClick: () -> Unit) {
  Box(
    Modifier
      .size(36.dp)
      .clip(CircleShape)
      .background(CardGlass.copy(alpha = if (enabled) 0.95f else 0.45f))
      .border(1.dp, BorderSubtle.copy(alpha = 0.5f), CircleShape)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Label(glyph, color = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.4f), size = 16)
  }
}

@Composable
private fun TuneIcon(modifier: Modifier = Modifier, tint: Color = AccentRose) {
  Canvas(modifier) {
    val left = size.width * .12f
    val right = size.width * .88f
    val rows = listOf(.22f to .38f, .5f to .66f, .78f to .48f)
    rows.forEach { (fraction, knob) ->
      val y = size.height * fraction
      drawLine(tint.copy(alpha = .72f), Offset(left, y), Offset(right, y), strokeWidth = size.width * .1f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
      drawCircle(tint, radius = size.width * .14f, center = Offset(size.width * knob, y))
    }
  }
}

@Composable
private fun MainPanel(
  page: Page,
  playlistId: String?,
  selectedOnlineShelf: MusicShelf?,
  onlineDetailLoading: Boolean,
  onOpenOnlinePlaylist: (MusicPlaylist) -> Unit,
  tracks: List<Track>,
  playlists: List<Playlist>,
  history: List<String>,
  queue: List<Track>,
  query: String,
  currentTrack: Track?,
  lyrics: List<LyricLine>,
  englishLyrics: List<LyricLine>,
  lyricsLoading: Boolean,
  translationLoading: Boolean,
  translationError: String?,
  lyricsProvider: String?,
  lyricsMode: String,
  onLyricsModeChange: (String) -> Unit,
  deeplApiKey: String,
  onDeepLApiKeyChange: (String) -> Unit,
  isPlaying: Boolean,
  shuffleEnabled: Boolean,
  repeatMode: Int,
  playbackPosition: Long,
  playbackDuration: Long,
  onSeek: (Long) -> Unit,
  onOpenLyrics: () -> Unit,
  onOpenQueue: () -> Unit,
  onPlay: (Track, List<Track>?) -> Unit,
  onFavorite: (Track) -> Unit,
  onCreatePlaylist: () -> Unit,
  onOpenPlaylist: (String) -> Unit,
  onRemoveTrack: (String) -> Unit,
  onAddFiles: () -> Unit,
  onAddFolder: () -> Unit,
  onPlaylistTrack: (String, Track) -> Unit,
  onRemoveFromPlaylist: (String, String) -> Unit,
  onRenamePlaylist: (Playlist) -> Unit,
  onDeletePlaylist: (Playlist) -> Unit,
  onPlayPlaylist: (List<Track>) -> Unit,
  onAddSongs: (String) -> Unit,
  onMovePlaylistTrack: (String, String, Int) -> Unit,
  onlineHome: List<MusicShelf>,
  onlineResults: List<Track>,
  onlineLoading: Boolean,
  onlineError: String?,
  onPlayOnline: (Track) -> Unit,
  onPersonalize: () -> Unit,
  onTogglePlayback: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onToggleShuffle: () -> Unit,
  onCycleRepeat: () -> Unit
) {
  if (page == Page.OnlineShelf) {
    val shelf = selectedOnlineShelf
    if (shelf == null) {
      EmptyState("Playlist unavailable", "Refresh the online feed and open a playlist again.")
    } else {
      OnlineShelfDetailPanel(
        shelf = shelf,
        loading = onlineDetailLoading,
        playlists = playlists,
        onPlay = { track -> onPlay(track, shelf.tracks) },
        onPlayAll = { onPlayPlaylist(shelf.tracks) },
        onAdd = onPlaylistTrack,
        onOpenPlaylist = onOpenOnlinePlaylist
      )
    }
    return
  }

  if (page == Page.Home || page == Page.Lyrics) {
    val featured = onlineHome.firstOrNull()?.tracks?.firstOrNull()
    val focusTrack = currentTrack ?: featured
    val nextUpTrack = remember(queue, currentTrack?.id) {
      val idx = queue.indexOfFirst { it.id == currentTrack?.id }
      if (idx >= 0 && idx + 1 < queue.size) queue[idx + 1] else queue.firstOrNull { it.id != focusTrack?.id }
    }
    NowPlayingHeroPanel(
      track = focusTrack,
      nextUpTrack = nextUpTrack,
      isCurrent = currentTrack != null && currentTrack.id == focusTrack?.id,
      isPlaying = isPlaying,
      position = playbackPosition,
      duration = playbackDuration,
      lyrics = when (lyricsMode) {
        "ENGLISH" -> englishLyrics
        "OFF" -> emptyList()
        else -> lyrics
      },
      lyricsLoading = lyricsLoading,
      lyricsProvider = lyricsProvider,
      lyricsMode = lyricsMode,
      translationLoading = translationLoading,
      translationError = translationError,
      onLyricsModeChange = onLyricsModeChange,
      fullLyricsMode = page == Page.Lyrics,
      onSeek = onSeek,
      onOpenLyrics = onOpenLyrics,
      onOpenQueue = onOpenQueue,
      onPlay = { track -> if (track.isOnline) onPlayOnline(track) else onPlay(track, listOf(track)) },
      onFavorite = onFavorite,
      onAddFiles = onAddFiles
    )
    return
  }

  if (page == Page.Search) {
    OnlineSearchResults(
      query = query,
      localTracks = tracks.filter { listOf(it.title, it.artist, it.album).any { s -> s.contains(query, true) } },
      onlineTracks = onlineResults,
      playlists = playlists,
      loading = onlineLoading,
      error = onlineError,
      onPlayLocal = { track -> onPlay(track, tracks) },
      onPlayOnline = onPlayOnline,
      onAdd = { id, track -> onPlaylistTrack(id, track) }
    )
    return
  }

  val selected = playlists.firstOrNull { it.id == playlistId }
  val list = when {
    page == Page.History -> history.mapNotNull { id -> tracks.firstOrNull { it.id == id } }
    selected != null -> selected.trackIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }
    page == Page.Library -> tracks
    else -> tracks.sortedByDescending { it.lastPlayedAt ?: 0 }.take(20)
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(PanelSurface, RoundedCornerShape(16.dp))
      .border(1.dp, BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
      .padding(22.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Label(
          text = if (selected != null) "PLAYLIST" else page.title.uppercase(),
          color = AccentRose,
          size = 10,
          weight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Label(selected?.name ?: page.title, size = 24, weight = FontWeight.Bold)
      }
      if (page == Page.Playlists && selected == null) ActionButton("＋ New playlist", onCreatePlaylist)
      if (selected != null && selected.id != "liked") {
        ActionButton("✎ Rename", { onRenamePlaylist(selected) })
        Spacer(Modifier.width(7.dp))
        ActionButton("Delete", { onDeletePlaylist(selected) })
      }
      Spacer(Modifier.width(8.dp))
      ActionButton("＋ Add music", onAddFiles)
    }

    if (page == Page.Playlists && selected == null) {
      Spacer(Modifier.height(18.dp))
      if (playlists.isEmpty()) {
        EmptyState("No playlists yet", "Create a playlist and add songs from your library or search results.")
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 185.dp),
          modifier = Modifier.weight(1f),
          contentPadding = PaddingValues(bottom = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          gridItems(playlists, key = { it.id }) { playlist ->
            PlaylistGridCard(
              playlist = playlist,
              tracks = tracks,
              onOpen = { onOpenPlaylist(playlist.id) },
              onRename = { onRenamePlaylist(playlist) },
              onDelete = { onDeletePlaylist(playlist) }
            )
          }
        }
      }
    } else if (page == Page.Playlists && selected != null) {
      Spacer(Modifier.height(14.dp))
      Row(
        Modifier.fillMaxWidth().background(CardGlass, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        PlaylistArtwork(selected, tracks, Modifier.size(124.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
          Label(if (selected.id == "liked") "AUTO PLAYLIST" else "PLAYLIST", color = AccentRose, size = 9, weight = FontWeight.Bold)
          Spacer(Modifier.height(4.dp))
          Label(selected.name, size = 22, weight = FontWeight.Bold, maxLines = 1)
          Label("${list.size} songs  ·  ${list.sumOf { it.durationMs }.let(::time)}", color = TextMuted, size = 11)
          Spacer(Modifier.height(12.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("▶  Play all", { onPlayPlaylist(list) })
            ActionButton("Shuffle", { onPlayPlaylist(list.shuffled()) })
            ActionButton("＋  Add songs", { onAddSongs(selected.id) })
          }
        }
      }
      Spacer(Modifier.height(12.dp))
      if (list.isEmpty()) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
          EmptyState("This playlist is empty", "Click '+ Add songs' or use the menu on any song to add tracks.")
        }
      } else {
        TrackTableHeader()
        LazyColumn(Modifier.weight(1f)) {
          items(list, key = { it.id }) { track ->
            val idx = list.indexOf(track)
            TrackRow(
              track = track,
              active = currentTrack?.id == track.id,
              onPlay = { onPlay(track, list) },
              onFavorite = { onFavorite(track) },
              onRemove = { onRemoveFromPlaylist(selected.id, track.id) },
              playlists = playlists,
              onAdd = { id -> onPlaylistTrack(id, track) },
              onMoveUp = { onMovePlaylistTrack(selected.id, track.id, -1) },
              onMoveDown = { onMovePlaylistTrack(selected.id, track.id, 1) },
              showReorder = idx > 0 || idx < list.lastIndex,
              removeLabel = "Remove from playlist"
            )
          }
        }
      }
    } else if (page == Page.Settings) {
      Spacer(Modifier.height(16.dp))
      LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
          SettingsSection("PLAYBACK ENGINE", "Local & Online Streaming", "Plays local audio files and YouTube Music tracks.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              ActionButton("＋ Import audio", onAddFiles)
              ActionButton("▧ Scan music folder", onAddFolder)
            }
          }
        }
        item {
          SettingsSection("LIBRARY", "Collection Status", if (tracks.isEmpty()) "No local tracks imported yet." else "${tracks.size} tracks indexed across ${playlists.size} playlists.")
        }
        item {
          SettingsSection("PERSONALIZATION", "Country, languages & composers", "Choose the regions and artists used to personalize current music recommendations.") {
            ActionButton("⚙  Update music preferences", onPersonalize)
          }
        }
        item {
          SettingsSection("LYRICS", "Local .lrc + SimpMusic + Unison + BetterLyrics + LRCLIB + KuGou + YouTube + lyrics.ovh", "Solo checks for a matching .lrc sidecar first, then falls back through the online sources — synced lyrics are preferred over plain text.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf("NATIVE" to "Native", "ENGLISH" to "English", "OFF" to "Off").forEach { (mode, label) ->
                ActionButton(if (lyricsMode == mode) "✓ $label" else label) { onLyricsModeChange(mode) }
              }
            }
          }
        }
        item {
          var deepLKeyDraft by remember(deeplApiKey) { mutableStateOf(deeplApiKey) }
          SettingsSection("ENGLISH LYRICS", "DeepL translation", "English mode translates each native lyric line through DeepL and keeps its playback timestamp. Add a DeepL API key to use this option.") {
            OutlinedTextField(
              value = deepLKeyDraft,
              onValueChange = { deepLKeyDraft = it },
              modifier = Modifier.fillMaxWidth().trackTextEntryFocus(),
              label = { Text("DeepL API key") },
              placeholder = { Text("Paste your API key") },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(10.dp))
            ActionButton("Save DeepL key", { onDeepLApiKeyChange(deepLKeyDraft) })
          }
        }
        item {
          SettingsSection("PLAYBACK CONTROLS", "Control the current song", "These buttons operate the same playback queue as the persistent player bar and headset media keys.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              ActionButton("Previous", onPrevious)
              ActionButton(if (isPlaying) "Pause" else "Play / Resume", onTogglePlayback)
              ActionButton("Next", onNext)
              ActionButton("${if (shuffleEnabled) "✓ " else ""}Shuffle", onToggleShuffle)
              ActionButton(
                when (repeatMode) {
                  DesktopPlayer.REPEAT_ONE -> "Repeat: One"
                  DesktopPlayer.REPEAT_ALL -> "Repeat: All"
                  else -> "Repeat: Sequential"
                },
                onCycleRepeat
              )
            }
          }
        }
        item { SettingsSection("KEYBOARD SHORTCUTS", "Desktop Controls", "Space: Play/Pause  ·  Ctrl + ←/→: Prev/Next  ·  Ctrl + K: Search  ·  Ctrl + L: Toggle Lyrics  ·  Ctrl + O: Import  ·  Tab: move focus") }
      }
    } else if (list.isEmpty()) {
      Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        EmptyState("Your library is empty", "Add audio files or scan a music folder to build your local collection.")
      }
    } else {
      Spacer(Modifier.height(12.dp))
      TrackTableHeader()
      LazyColumn(Modifier.weight(1f)) {
        items(list, key = { it.id }) { track ->
          TrackRow(
            track = track,
            active = currentTrack?.id == track.id,
            onPlay = { onPlay(track, list) },
            onFavorite = { onFavorite(track) },
            onRemove = { onRemoveTrack(track.id) },
            onAdd = { id -> onPlaylistTrack(id, track) },
            playlists = playlists,
            removeLabel = "Remove from library"
          )
        }
      }
    }

    if (page == Page.Library) {
      Spacer(Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("▧  Scan a folder", onAddFolder)
        ActionButton("＋  Browse files", onAddFiles)
      }
    }
  }
}

@Composable
private fun TrackTableHeader() {
  Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
    Label("TITLE", Modifier.weight(1f), TextMuted, 9, FontWeight.Bold)
    Label("ALBUM", Modifier.weight(0.7f), TextMuted, 9, FontWeight.Bold)
    Label("TIME", Modifier.width(52.dp), TextMuted, 9, FontWeight.Bold)
  }
}

/**
 * Center Stage: Responsive Now Playing + Smooth Auto-Scrolling Synced Lyrics
 */
@Composable
private fun NowPlayingHeroPanel(
  track: Track?,
  nextUpTrack: Track?,
  isCurrent: Boolean,
  isPlaying: Boolean,
  position: Long,
  duration: Long,
  lyrics: List<LyricLine>,
  lyricsLoading: Boolean,
  lyricsProvider: String?,
  lyricsMode: String,
  translationLoading: Boolean,
  translationError: String?,
  onLyricsModeChange: (String) -> Unit,
  fullLyricsMode: Boolean,
  onSeek: (Long) -> Unit,
  onOpenLyrics: () -> Unit,
  onOpenQueue: () -> Unit,
  onPlay: (Track) -> Unit,
  onFavorite: (Track) -> Unit,
  onAddFiles: () -> Unit
) {
  // Plain-text providers carry no timestamps, so spread lines across the real
  // playback duration to still get a moving highlight, offset nudge, and click-to-seek.
  val displayLyrics = remember(lyrics, duration) {
    if (lyrics.any { it.timeMs >= 0L } || lyrics.size < 3 || duration <= 0L) lyrics else {
      val intro = (duration * 0.08).toLong()
      val step = ((duration * 0.84).toLong() / lyrics.size).coerceAtLeast(1L)
      lyrics.mapIndexed { index, line -> line.copy(timeMs = intro + index * step) }
    }
  }
  val estimatedSync = displayLyrics !== lyrics
  val displayProvider = if (estimatedSync && !lyricsProvider.isNullOrBlank() && !lyricsProvider.contains("estimated", ignoreCase = true)) {
    "$lyricsProvider (estimated)"
  } else lyricsProvider
  val hasTimestamps = remember(displayLyrics) { displayLyrics.any { it.timeMs >= 0 } }
  var lyricsOffsetMs by remember(track?.id) { mutableLongStateOf(0L) }
  val lyricPosition = (position - lyricsOffsetMs).coerceAtLeast(0L)
  val activeIdx = remember(displayLyrics, lyricPosition, hasTimestamps) {
    if (hasTimestamps) LrcLyrics.activeIndex(displayLyrics, lyricPosition) else -1
  }
  val lyricsListState = rememberLazyListState()

  // Smoothly keep the currently playing line centered in the lyrics viewport
  LaunchedEffect(activeIdx, displayLyrics.size, lyricsMode) {
    if (activeIdx >= 0 && displayLyrics.isNotEmpty() && lyricsMode != "OFF") {
      val target = (activeIdx - 2).coerceIn(0, (displayLyrics.size - 1).coerceAtLeast(0))
      runCatching { lyricsListState.animateScrollToItem(target) }
    }
  }

  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(listOf(HeroTopRose, HeroMidRose, HeroBottomDeep)),
        RoundedCornerShape(16.dp)
      )
      .border(1.dp, BorderSubtle.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
      .padding(20.dp)
  ) {
    val stackVertically = maxWidth < 640.dp
    val showNextUpCard = maxHeight >= 560.dp && nextUpTrack != null

    Column(Modifier.fillMaxSize()) {
      // Top Header Bar: Status Badge + Segmented Lyrics Mode Switcher + Stage Toggle
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(
            Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(if (isCurrent && isPlaying) AccentRose else TextMuted.copy(alpha = 0.5f))
          )
          Label(
            text = when {
              fullLyricsMode -> "LYRICS STAGE"
              isCurrent && isPlaying -> "NOW PLAYING"
              isCurrent -> "PAUSED"
              else -> "FEATURED TRACK"
            },
            color = AccentRose,
            size = 10,
            weight = FontWeight.Bold
          )
        }

        Spacer(Modifier.weight(1f))

        // Lyrics On/Off toggle (language is chosen inside the lyrics card)
        val lyricsOn = lyricsMode != "OFF"
        Row(
          Modifier
            .clip(CircleShape)
            .background(if (lyricsOn) AccentRose.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.28f))
            .border(1.dp, if (lyricsOn) AccentRose.copy(alpha = 0.5f) else BorderSubtle.copy(alpha = 0.6f), CircleShape)
            .clickable { onLyricsModeChange(if (lyricsOn) "OFF" else "NATIVE") }
            .padding(horizontal = 11.dp, vertical = 5.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("✎", color = if (lyricsOn) AccentRose else TextMuted, fontSize = 11.sp)
          Text(
            text = if (lyricsOn) "Lyrics On" else "Lyrics Off",
            color = if (lyricsOn) AccentRose else TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
          )
        }

        if (lyricsMode != "OFF") {
          Text(
            text = if (fullLyricsMode) "Split View ↙" else "Expand ↗",
            modifier = Modifier
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.09f))
              .border(1.dp, BorderSubtle.copy(alpha = 0.5f), CircleShape)
              .clickable(onClick = onOpenLyrics)
              .padding(horizontal = 11.dp, vertical = 6.dp),
            color = TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }

      Spacer(Modifier.height(16.dp))

      if (track == null) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState("Your music starts here", "Pick any track on the right, search YouTube Music, or import local audio.")
            Spacer(Modifier.height(10.dp))
            ActionButton("＋  Import Local Music", onAddFiles)
          }
        }
      } else if (lyricsMode == "OFF") {
        // Focused Artwork Showcase when Lyrics are turned Off
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
          Column(
            Modifier.widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            TrackArtwork(
              track = track,
              hiRes = true,
              modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                .clickable { onPlay(track) }
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Label(track.title, size = 21, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Label(
                  text = listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                  color = TextMuted,
                  size = 12,
                  maxLines = 1
                )
              }
              Spacer(Modifier.width(10.dp))
              FavoritePillButton(isFavorite = track.isFavorite, onClick = { onFavorite(track) })
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
              if (!isCurrent || !isPlaying) {
                ActionButton(if (!isCurrent) "▶  Play Now" else "▶  Resume", { onPlay(track) })
              }
              ActionButton("✎  Show Lyrics", { onLyricsModeChange("NATIVE") })
            }
          }
        }
      } else if (stackVertically) {
        // Compact Vertical Stack for Narrow Windows
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TrackArtwork(
              track = track,
              modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onPlay(track) }
            )
            Column(Modifier.weight(1f)) {
              Label(track.title, size = 16, weight = FontWeight.Bold, maxLines = 1)
              Spacer(Modifier.height(3.dp))
              Label(
                text = listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                color = TextMuted,
                size = 11,
                maxLines = 1
              )
              if (!isCurrent || !isPlaying) {
                Spacer(Modifier.height(6.dp))
                ActionButton(if (!isCurrent) "▶  Play" else "▶  Resume", { onPlay(track) })
              }
            }
            FavoritePillButton(isFavorite = track.isFavorite, onClick = { onFavorite(track) })
          }

          LyricsViewportCard(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            lyrics = displayLyrics,
            activeIdx = activeIdx,
            hasTimestamps = hasTimestamps,
            lyricsLoading = lyricsLoading,
            translationLoading = translationLoading,
            translationError = translationError,
            lyricsMode = lyricsMode,
            lyricsProvider = displayProvider,
            fullLyricsMode = fullLyricsMode,
            listState = lyricsListState,
            lyricsOffsetMs = lyricsOffsetMs,
            onLyricsOffset = { delta -> lyricsOffsetMs = (lyricsOffsetMs + delta).coerceIn(-30000L, 30000L) },
            onSeek = onSeek
          )
        }
      } else {
        // Balanced Desktop Split-Stage (Artwork Column + Synced Lyrics Stage)
        Row(
          Modifier.weight(1f).fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(22.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Left Column: Artwork + Track Info + Up Next Context
          Column(
            Modifier
              .weight(if (fullLyricsMode) 0.78f else 0.96f)
              .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
          ) {
            Box(
              Modifier
                .weight(1f, fill = false)
                .fillMaxWidth(),
              contentAlignment = Alignment.Center
            ) {
              TrackArtwork(
                track = track,
                hiRes = true,
                modifier = Modifier
                  .widthIn(max = if (fullLyricsMode) 310.dp else 370.dp)
                  .fillMaxWidth()
                  .aspectRatio(1f)
                  .clip(RoundedCornerShape(16.dp))
                  .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                  .clickable { onPlay(track) }
              )
            }

            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Label(track.title, size = 18, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Label(
                  text = listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                  color = TextMuted,
                  size = 12,
                  maxLines = 1
                )
              }
              Spacer(Modifier.width(8.dp))
              FavoritePillButton(isFavorite = track.isFavorite, onClick = { onFavorite(track) })
            }

            if (!isCurrent || !isPlaying) {
              Spacer(Modifier.height(10.dp))
              ActionButton(if (!isCurrent) "▶  Play Now" else "▶  Resume", { onPlay(track) })
            }

            if (showNextUpCard && nextUpTrack != null) {
              Spacer(Modifier.height(12.dp))
              Row(
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(11.dp))
                  .background(Color.Black.copy(alpha = 0.22f))
                  .border(1.dp, BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
                  .clickable(onClick = onOpenQueue)
                  .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Label("UP NEXT", color = AccentRose, size = 8, weight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Label(
                  text = "${nextUpTrack.title} · ${nextUpTrack.artist}",
                  modifier = Modifier.weight(1f),
                  color = TextPrimary.copy(alpha = 0.85f),
                  size = 10,
                  maxLines = 1
                )
                Label("☷", color = TextMuted, size = 12)
              }
            }
          }

          // Right Column: Dedicated Synced Lyrics Stage
          LyricsViewportCard(
            modifier = Modifier
              .weight(if (fullLyricsMode) 1.22f else 1.04f)
              .fillMaxHeight(),
            lyrics = displayLyrics,
            activeIdx = activeIdx,
            hasTimestamps = hasTimestamps,
            lyricsLoading = lyricsLoading,
            translationLoading = translationLoading,
            translationError = translationError,
            lyricsMode = lyricsMode,
            lyricsProvider = displayProvider,
            fullLyricsMode = fullLyricsMode,
            listState = lyricsListState,
            lyricsOffsetMs = lyricsOffsetMs,
            onLyricsOffset = { delta -> lyricsOffsetMs = (lyricsOffsetMs + delta).coerceIn(-30000L, 30000L) },
            onSeek = onSeek
          )
        }
      }
    }
  }
}

@Composable
private fun FavoritePillButton(isFavorite: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .size(36.dp)
      .clip(CircleShape)
      .background(if (isFavorite) AccentRose.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
      .border(1.dp, if (isFavorite) AccentRose.copy(alpha = 0.5f) else BorderSubtle.copy(alpha = 0.5f), CircleShape)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = if (isFavorite) "♥" else "♡",
      color = if (isFavorite) AccentRose else TextPrimary,
      fontSize = 16.sp
    )
  }
}

@Composable
private fun LyricsOffsetChip(glyph: String, onClick: () -> Unit) {
  Box(
    Modifier
      .clip(CircleShape)
      .background(Color.White.copy(alpha = 0.08f))
      .border(1.dp, BorderSubtle.copy(alpha = 0.5f), CircleShape)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 2.dp),
    contentAlignment = Alignment.Center
  ) {
    Label(glyph, color = TextPrimary.copy(alpha = 0.9f), size = 11, weight = FontWeight.Bold)
  }
}

@Composable
private fun LyricsViewportCard(
  modifier: Modifier,
  lyrics: List<LyricLine>,
  activeIdx: Int,
  hasTimestamps: Boolean,
  lyricsLoading: Boolean,
  translationLoading: Boolean,
  translationError: String?,
  lyricsMode: String,
  lyricsProvider: String?,
  fullLyricsMode: Boolean,
  listState: androidx.compose.foundation.lazy.LazyListState,
  lyricsOffsetMs: Long,
  onLyricsOffset: (Long) -> Unit,
  onSeek: (Long) -> Unit
) {
  Column(
    modifier
      .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
      .border(1.dp, BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
      .padding(horizontal = 20.dp, vertical = 16.dp)
  ) {
    // Lyrics Header Metadata
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label(
        text = if (lyricsMode == "ENGLISH") "ENGLISH TRANSLATION" else "SYNCED LYRICS",
        color = TextMuted,
        size = 10,
        weight = FontWeight.Bold
      )
      Spacer(Modifier.weight(1f))
      if (hasTimestamps && lyrics.isNotEmpty()) {
        LyricsOffsetChip("−") { onLyricsOffset(-500L) }
        Label(
          text = if (lyricsOffsetMs == 0L) "±0.0s" else "%+.1fs".format(lyricsOffsetMs / 1000f),
          modifier = Modifier.padding(horizontal = 5.dp),
          color = if (lyricsOffsetMs == 0L) TextMuted.copy(alpha = 0.7f) else AccentRose,
          size = 9,
          weight = FontWeight.Bold
        )
        LyricsOffsetChip("+") { onLyricsOffset(500L) }
        Spacer(Modifier.width(8.dp))
      }
      val badgeText = when {
        lyricsLoading -> "LOADING…"
        translationLoading -> "TRANSLATING…"
        !lyricsProvider.isNullOrBlank() -> lyricsProvider.uppercase()
        hasTimestamps && lyrics.isNotEmpty() -> "CLICK LINE TO SEEK"
        lyrics.isNotEmpty() -> "PLAIN LYRICS"
        else -> "NO LYRICS"
      }
      Box(
        Modifier
          .clip(CircleShape)
          .background(AccentRose.copy(alpha = 0.14f))
          .padding(horizontal = 9.dp, vertical = 3.dp)
      ) {
        Label(badgeText, color = AccentRose, size = 8, weight = FontWeight.Bold)
      }
    }

    Spacer(Modifier.height(12.dp))

    if (lyrics.isEmpty()) {
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
          Modifier.widthIn(max = 360.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Label("✎", color = AccentRose, size = 26)
          Spacer(Modifier.height(10.dp))
          Label(
            text = when {
              lyricsLoading -> "Finding lyrics…"
              translationLoading -> "Translating lyrics to English…"
              lyricsMode == "ENGLISH" && translationError != null -> "English lyrics unavailable"
              else -> "Lyrics unavailable"
            },
            color = TextPrimary,
            size = 17,
            weight = FontWeight.Bold
          )
          Spacer(Modifier.height(6.dp))
          Text(
            text = when {
              lyricsLoading -> "Checking local .lrc files and searching LRCLIB, YouTube Music and lyrics.ovh."
              translationLoading -> "Translating matched lines while preserving playback timestamps."
              lyricsMode == "ENGLISH" && translationError != null -> translationError
              else -> "No matching lyrics were found on the available sources. Place a matching .lrc file beside local audio to add them manually."
            },
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
          )
        }
      }
    } else {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(top = 24.dp, bottom = 72.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          itemsIndexed(lyrics, key = { idx, line -> "$idx-${line.timeMs}-${line.text.hashCode()}" }) { index, line ->
            val isSelected = activeIdx >= 0 && index == activeIdx
            val isPast = activeIdx >= 0 && index < activeIdx
            val targetColor by animateColorAsState(
              targetValue = when {
                isSelected -> TextPrimary
                !hasTimestamps -> TextPrimary.copy(alpha = 0.85f)
                isPast -> TextPrimary.copy(alpha = 0.30f)
                else -> TextPrimary.copy(alpha = 0.56f)
              },
              animationSpec = tween(220)
            )
            val rowBg by animateColorAsState(
              targetValue = if (isSelected) AccentRose.copy(alpha = 0.14f) else Color.Transparent,
              animationSpec = tween(220)
            )

            Row(
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(rowBg)
                .then(if (line.timeMs >= 0) Modifier.clickable { onSeek((line.timeMs + lyricsOffsetMs).coerceAtLeast(0L)) } else Modifier)
                .padding(horizontal = 12.dp, vertical = if (isSelected) 9.dp else 5.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (hasTimestamps) {
                Box(
                  Modifier
                    .width(3.dp)
                    .height(if (isSelected) 22.dp else 0.dp)
                    .clip(CircleShape)
                    .background(AccentRose)
                )
                if (isSelected) Spacer(Modifier.width(10.dp))
              }
              Text(
                text = line.text,
                modifier = Modifier.weight(1f),
                color = targetColor,
                fontSize = when {
                  isSelected && fullLyricsMode -> 23.sp
                  isSelected -> 20.sp
                  fullLyricsMode -> 16.sp
                  else -> 14.sp
                },
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                lineHeight = when {
                  isSelected && fullLyricsMode -> 31.sp
                  isSelected -> 27.sp
                  else -> 21.sp
                }
              )
              if (line.timeMs >= 0 && isSelected) {
                Spacer(Modifier.width(8.dp))
                Label(time(line.timeMs), color = AccentRose.copy(alpha = 0.85f), size = 10, weight = FontWeight.SemiBold)
              }
            }
          }
        }

        // Subtle bottom fade so scrolling lyrics blend cleanly into the card
        Box(
          Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(34.dp)
            .background(
              Brush.verticalGradient(
                listOf(Color.Transparent, Color(0xFF1D1521).copy(alpha = 0.88f))
              )
            )
        )
      }
    }
  }
}

@Composable
private fun OnlineShelfDetailPanel(
  shelf: MusicShelf,
  loading: Boolean,
  playlists: List<Playlist>,
  onPlay: (Track) -> Unit,
  onPlayAll: () -> Unit,
  onAdd: (String, Track) -> Unit,
  onOpenPlaylist: (MusicPlaylist) -> Unit
) {
  Column(
    Modifier.fillMaxSize().background(PanelSurface, RoundedCornerShape(16.dp))
      .border(1.dp, BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(22.dp)
  ) {
    Label("ONLINE PLAYLIST", color = AccentRose, size = 10, weight = FontWeight.Bold)
    Spacer(Modifier.height(5.dp))
    Label(shelf.title, size = 24, weight = FontWeight.Bold, maxLines = 2)
    Spacer(Modifier.height(15.dp))
    when {
      loading && shelf.tracks.isEmpty() && shelf.playlists.isEmpty() -> {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
          EmptyState("Loading…", "Fetching music from YouTube Music.")
        }
      }
      shelf.playlists.isNotEmpty() -> {
        Row(
          Modifier.fillMaxWidth().background(CardGlass, RoundedCornerShape(14.dp)).padding(15.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          RemoteImage(shelf.playlists.firstOrNull()?.thumbnailUrl, Modifier.size(108.dp).clip(RoundedCornerShape(10.dp)))
          Spacer(Modifier.width(16.dp))
          Column(Modifier.weight(1f)) {
            Label("YouTube Music · ${shelf.playlists.size} playlists", color = TextMuted, size = 12)
            Spacer(Modifier.height(10.dp))
            Label("Tap a playlist to open its songs", color = TextMuted, size = 10)
          }
        }
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
          columns = GridCells.Adaptive(150.dp),
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(14.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          gridItems(shelf.playlists, key = { it.browseId }) { playlist ->
            PlaylistCard(playlist, Modifier.fillMaxWidth()) { onOpenPlaylist(playlist) }
          }
          if (shelf.tracks.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { TrackTableHeader() }
            gridItems(shelf.tracks, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { track ->
              OnlineResultRow(
                track = track,
                playlists = playlists,
                badge = "ONLINE",
                onPlay = { onPlay(track) },
                onAdd = { playlistId -> onAdd(playlistId, track) }
              )
            }
          }
        }
      }
      else -> {
        Row(
          Modifier.fillMaxWidth().background(CardGlass, RoundedCornerShape(14.dp)).padding(15.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          shelf.tracks.firstOrNull()?.let { TrackArtwork(it, Modifier.size(108.dp).clip(RoundedCornerShape(10.dp))) }
          Spacer(Modifier.width(16.dp))
          Column(Modifier.weight(1f)) {
            Label("YouTube Music · ${shelf.tracks.size} songs", color = TextMuted, size = 12)
            Spacer(Modifier.height(10.dp))
            ActionButton("▶  Play all", onPlayAll)
          }
        }
        Spacer(Modifier.height(14.dp))
        TrackTableHeader()
        LazyColumn(Modifier.weight(1f)) {
          items(shelf.tracks, key = { it.id }) { track ->
            OnlineResultRow(
              track = track,
              playlists = playlists,
              badge = "ONLINE",
              onPlay = { onPlay(track) },
              onAdd = { playlistId -> onAdd(playlistId, track) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PlaylistCard(playlist: MusicPlaylist, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Column(modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick)) {
    RemoteImage(playlist.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(9.dp)))
    Spacer(Modifier.height(6.dp))
    Label(playlist.title, size = 10, weight = FontWeight.SemiBold, maxLines = 1)
    Label(playlist.subtitle.ifBlank { "YouTube Music playlist" }, color = TextMuted, size = 9, maxLines = 1)
  }
}

@Composable
private fun OnlineFeedPanel(
  shelves: List<MusicShelf>,
  moods: List<MoodCategory>,
  loading: Boolean,
  error: String?,
  replayVisible: Boolean,
  onDismissReplay: () -> Unit,
  onOpenReplay: () -> Unit,
  countryCode: String,
  languages: List<String>,
  composers: List<String>,
  onPlay: (Track) -> Unit,
  onOpenShelf: (MusicShelf) -> Unit,
  onOpenMood: (MoodCategory) -> Unit,
  onOpenPlaylist: (MusicPlaylist) -> Unit,
  onRefresh: () -> Unit,
  onPersonalize: () -> Unit
) {
  Column(
    Modifier
      .width(332.dp)
      .fillMaxHeight()
      .background(PanelSurface, RoundedCornerShape(16.dp))
      .border(1.dp, BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
      .padding(14.dp)
  ) {
    if (replayVisible) {
      Row(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(CardGlass)
          .border(1.dp, BorderSubtle.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
          .clickable(onClick = onOpenReplay)
          .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          Modifier.size(42.dp).background(AccentRose, RoundedCornerShape(11.dp)),
          contentAlignment = Alignment.Center
        ) {
          Label("♫", color = Color(0xFF4A252E), size = 20, weight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Label("2026 REPLAY", color = AccentRose, size = 9, weight = FontWeight.Bold)
          Label("Your Year in Music", color = TextPrimary, size = 13, weight = FontWeight.Bold)
          Label("Tap to explore your top songs & history", color = TextMuted, size = 10, maxLines = 1)
        }
        Text(
          text = "×",
          modifier = Modifier.clickable(onClick = onDismissReplay).padding(4.dp),
          color = TextMuted,
          fontSize = 16.sp
        )
      }
      Spacer(Modifier.height(10.dp))
    }

    Row(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(CardGlass.copy(alpha = 0.7f))
        .clickable(onClick = onPersonalize)
        .padding(horizontal = 11.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TuneIcon(Modifier.size(15.dp))
      Spacer(Modifier.width(8.dp))
      val regionName = Locale("", countryCode).getDisplayCountry(Locale.ENGLISH).ifBlank { "Global" }
      val sub = (languages + composers).distinct().takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "Personalize region & artists"
      Column(Modifier.weight(1f)) {
        Label("DISCOVER · ${regionName.uppercase()}", color = TextMuted, size = 8, weight = FontWeight.Bold)
        Label(sub, color = TextPrimary, size = 10, weight = FontWeight.Medium, maxLines = 1)
      }
      Text("↻", Modifier.clickable(onClick = onRefresh).padding(4.dp), color = TextMuted, fontSize = 15.sp)
    }

    if (moods.isNotEmpty()) {
      Spacer(Modifier.height(10.dp))
      LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(moods.take(14), key = { it.browseId + (it.params ?: "") }) { mood ->
          Text(
            text = mood.title,
            modifier = Modifier
              .clip(CircleShape)
              .background(CardGlass)
              .border(1.dp, BorderSubtle.copy(alpha = 0.5f), CircleShape)
              .clickable { onOpenMood(mood) }
              .padding(horizontal = 10.dp, vertical = 5.dp),
            color = TextPrimary,
            fontSize = 9.sp
          )
        }
      }
    }

    Spacer(Modifier.height(12.dp))

    if (shelves.isEmpty() && loading) {
      Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        EmptyState("Loading featured music…", "Fetching playlists and charts.")
      }
    } else if (shelves.isEmpty()) {
      Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          EmptyState("Featured playlists", error ?: "Connect to load online shelves.")
          ActionButton("Retry", onRefresh)
        }
      }
    } else {
      LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        shelves.forEachIndexed { index, shelf ->
          item(key = "shelf-${shelf.title}-$index") {
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Label(
                  text = shelf.title.uppercase(),
                  modifier = Modifier.weight(1f),
                  color = TextPrimary,
                  size = 11,
                  weight = FontWeight.Bold,
                  maxLines = 1
                )
                Box(
                  Modifier.size(32.dp).clip(CircleShape).clickable { onOpenShelf(shelf) },
                  contentAlignment = Alignment.Center
                ) {
                  Text("→", color = TextPrimary, fontSize = 17.sp)
                }
              }
              Spacer(Modifier.height(8.dp))
              LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shelf.tracks.take(8), key = { it.id }) { track ->
                  Column(
                    Modifier
                      .width(100.dp)
                      .clip(RoundedCornerShape(9.dp))
                      .clickable { onPlay(track) }
                  ) {
                    TrackArtwork(track, Modifier.size(100.dp).clip(RoundedCornerShape(9.dp)))
                    Spacer(Modifier.height(6.dp))
                    Label(track.title, size = 10, weight = FontWeight.SemiBold, maxLines = 1)
                    Label(track.artist, color = TextMuted, size = 9, maxLines = 1)
                  }
                }
              }
              if (shelf.playlists.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  items(shelf.playlists.take(8), key = { it.browseId }) { playlist ->
                    PlaylistCard(playlist, Modifier.width(100.dp)) { onOpenPlaylist(playlist) }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerBar(
  track: Track?,
  playing: Boolean,
  position: Long,
  duration: Long,
  volume: Float,
  shuffle: Boolean,
  repeat: Int,
  lyricsActive: Boolean,
  compact: Boolean,
  onToggle: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
  onVolume: (Float) -> Unit,
  onVolumeFinished: (Float) -> Unit,
  onShuffle: () -> Unit,
  onRepeat: () -> Unit,
  onQueue: () -> Unit,
  onOpenLyrics: () -> Unit,
  onFavorite: () -> Unit,
  onOpenNowPlaying: () -> Unit
) {
  var scrubbing by remember(track?.id) { mutableStateOf(false) }
  var scrubFraction by remember(track?.id) { mutableFloatStateOf(0f) }
  val playbackFraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
  Row(
    Modifier
      .fillMaxWidth()
      .height(78.dp)
      .background(PanelSurface, RoundedCornerShape(16.dp))
      .border(1.dp, BorderSubtle.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Left: Now Playing Info
    Row(Modifier.width(if (compact) 180.dp else 280.dp), verticalAlignment = Alignment.CenterVertically) {
      if (track != null) {
        TrackArtwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenNowPlaying))
      } else {
        Box(Modifier.size(48.dp).background(CardGlass, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
          Label("♫", color = AccentRose, size = 20)
        }
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).clickable(enabled = track != null, onClick = onOpenNowPlaying).padding(vertical = 4.dp)) {
        Label(track?.title ?: "Nothing playing", size = 12, weight = FontWeight.Bold, maxLines = 1)
        Label(track?.artist ?: "Select a song to start", color = TextMuted, size = 10, maxLines = 1)
      }
      Spacer(Modifier.width(6.dp))
      Text(
        text = if (track?.isFavorite == true) "♥" else "♡",
        modifier = Modifier.clickable(onClick = onFavorite).padding(6.dp),
        color = if (track?.isFavorite == true) AccentRose else TextMuted,
        fontSize = 16.sp
      )
    }

    // Center: Transport Controls + Seekbar
    Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        TransportButton(TransportIconKind.Shuffle, selected = shuffle, description = if (shuffle) "Shuffle on" else "Shuffle off", onClick = onShuffle)
        TransportButton(TransportIconKind.Previous, description = "Previous", onClick = onPrevious)

        TransportPlayButton(playing = playing, enabled = track != null, onClick = onToggle)

        TransportButton(TransportIconKind.Next, description = "Next", onClick = onNext)
        TransportButton(
          if (repeat == DesktopPlayer.REPEAT_ONE) TransportIconKind.RepeatOne else TransportIconKind.Repeat,
          selected = repeat > 0,
          description = when (repeat) {
            DesktopPlayer.REPEAT_ONE -> "Repeat one"
            DesktopPlayer.REPEAT_ALL -> "Repeat all"
            else -> "Sequential play"
          },
          onClick = onRepeat
        )
      }

      Spacer(Modifier.height(6.dp))

      Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(time(position), color = TextMuted, size = 10)
        Spacer(Modifier.width(8.dp))
        Slider(
          value = if (scrubbing) scrubFraction else playbackFraction,
          onValueChange = { scrubFraction = it; scrubbing = true },
          onValueChangeFinished = {
            if (scrubbing) onSeek((scrubFraction * duration).toLong())
            scrubbing = false
          },
          modifier = Modifier.weight(1f).height(14.dp),
          enabled = duration > 0,
          track = { sliderState ->
            SliderDefaults.Track(
              sliderState = sliderState,
              modifier = Modifier.height(3.dp),
              colors = SliderDefaults.colors(
                activeTrackColor = AccentRose,
                inactiveTrackColor = BorderSubtle
              )
            )
          },
          colors = SliderDefaults.colors(
            thumbColor = AccentRose,
            activeTrackColor = AccentRose,
            inactiveTrackColor = BorderSubtle
          )
        )
        Spacer(Modifier.width(8.dp))
        Label(time(duration), color = TextMuted, size = 10)
      }
    }

    // Right: Lyrics Stage Toggle, Queue & Volume
    Row(
      Modifier.width(if (compact) 110.dp else 230.dp),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        Modifier
          .clip(CircleShape)
          .background(if (lyricsActive) AccentRose.copy(alpha = 0.2f) else Color.Transparent)
          .clickable(onClick = onOpenLyrics)
          .padding(horizontal = 8.dp, vertical = 5.dp)
      ) {
        Text("✎", color = if (lyricsActive) AccentRose else TextMuted, fontSize = 15.sp)
      }
      Spacer(Modifier.width(4.dp))
      Box(
        Modifier
          .clip(CircleShape)
          .clickable(onClick = onQueue)
          .padding(7.dp),
        contentAlignment = Alignment.Center
      ) {
        TransportIcon(TransportIconKind.Queue, tint = TextPrimary.copy(alpha = 0.85f))
      }
      if (!compact) {
        Spacer(Modifier.width(6.dp))
        // Local drag state keeps the thumb glued to the pointer while the real
        // volume state round-trips through the JavaFX player thread.
        var volumeDrag by remember { mutableStateOf<Float?>(null) }
        Row(
          Modifier
            .clip(CircleShape)
            .background(CardGlass)
            .border(1.dp, BorderSubtle.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          TransportIcon(
            if ((volumeDrag ?: volume) <= 0f) TransportIconKind.VolumeMute else TransportIconKind.Volume,
            tint = TextMuted,
            iconSize = 13.dp
          )
          Slider(
            value = volumeDrag ?: volume,
            onValueChange = { volumeDrag = it; onVolume(it) },
            onValueChangeFinished = {
              volumeDrag?.let(onVolumeFinished)
              volumeDrag = null
            },
            modifier = Modifier.width(96.dp),
            colors = SliderDefaults.colors(
              thumbColor = TextPrimary,
              activeTrackColor = AccentRose,
              inactiveTrackColor = BorderSubtle.copy(alpha = 0.55f)
            )
          )
        }
      }
    }
  }
}

private enum class TransportIconKind { Shuffle, Previous, Play, Pause, Next, Repeat, RepeatOne, Volume, VolumeMute, Queue }

/** Vector-drawn transport icons so the player bar follows the theme instead of system emoji fonts. */
@Composable
private fun TransportIcon(kind: TransportIconKind, tint: Color, iconSize: Dp = 16.dp) {
  Canvas(Modifier.size(iconSize)) {
    val w = size.width
    val h = size.height
    val sw = w * 0.11f
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
      drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), strokeWidth = sw, cap = StrokeCap.Round)
    when (kind) {
      TransportIconKind.Play -> drawPath(
        Path().apply {
          moveTo(0.32f * w, 0.18f * h); lineTo(0.32f * w, 0.82f * h); lineTo(0.8f * w, 0.5f * h); close()
        },
        tint
      )
      TransportIconKind.Pause -> {
        drawRoundRect(tint, Offset(0.24f * w, 0.18f * h), Size(0.18f * w, 0.64f * h), CornerRadius(sw / 2))
        drawRoundRect(tint, Offset(0.58f * w, 0.18f * h), Size(0.18f * w, 0.64f * h), CornerRadius(sw / 2))
      }
      TransportIconKind.Previous -> {
        drawRoundRect(tint, Offset(0.14f * w, 0.2f * h), Size(0.11f * w, 0.6f * h), CornerRadius(sw / 2))
        drawPath(Path().apply {
          moveTo(0.84f * w, 0.2f * h); lineTo(0.84f * w, 0.8f * h); lineTo(0.34f * w, 0.5f * h); close()
        }, tint)
      }
      TransportIconKind.Next -> {
        drawRoundRect(tint, Offset(0.75f * w, 0.2f * h), Size(0.11f * w, 0.6f * h), CornerRadius(sw / 2))
        drawPath(Path().apply {
          moveTo(0.16f * w, 0.2f * h); lineTo(0.16f * w, 0.8f * h); lineTo(0.66f * w, 0.5f * h); close()
        }, tint)
      }
      TransportIconKind.Shuffle -> {
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply {
          moveTo(0.06f * w, 0.74f * h)
          cubicTo(0.42f * w, 0.74f * h, 0.58f * w, 0.26f * h, 0.9f * w, 0.26f * h)
        }, tint, style = stroke)
        line(0.9f, 0.26f, 0.7f, 0.14f); line(0.9f, 0.26f, 0.7f, 0.38f)
        drawPath(Path().apply {
          moveTo(0.06f * w, 0.26f * h)
          cubicTo(0.42f * w, 0.26f * h, 0.58f * w, 0.74f * h, 0.9f * w, 0.74f * h)
        }, tint, style = stroke)
        line(0.9f, 0.74f, 0.7f, 0.62f); line(0.9f, 0.74f, 0.7f, 0.86f)
      }
      TransportIconKind.Repeat, TransportIconKind.RepeatOne -> {
        line(0.9f, 0.3f, 0.2f, 0.3f); line(0.2f, 0.3f, 0.38f, 0.17f); line(0.2f, 0.3f, 0.38f, 0.43f)
        line(0.1f, 0.7f, 0.8f, 0.7f); line(0.8f, 0.7f, 0.62f, 0.57f); line(0.8f, 0.7f, 0.62f, 0.83f)
      }
      TransportIconKind.Volume -> {
        drawPath(Path().apply {
          moveTo(0.08f * w, 0.36f * h); lineTo(0.3f * w, 0.36f * h); lineTo(0.54f * w, 0.16f * h)
          lineTo(0.54f * w, 0.84f * h); lineTo(0.3f * w, 0.64f * h); lineTo(0.08f * w, 0.64f * h); close()
        }, tint)
        val stroke = Stroke(sw * 0.8f, cap = StrokeCap.Round)
        drawArc(tint, -55f, 110f, false, Offset(0.5f * w, 0.32f * h), Size(0.34f * w, 0.36f * h), style = stroke)
        drawArc(tint, -55f, 110f, false, Offset(0.5f * w, 0.2f * h), Size(0.52f * w, 0.6f * h), style = stroke)
      }
      TransportIconKind.VolumeMute -> {
        drawPath(Path().apply {
          moveTo(0.08f * w, 0.36f * h); lineTo(0.3f * w, 0.36f * h); lineTo(0.54f * w, 0.16f * h)
          lineTo(0.54f * w, 0.84f * h); lineTo(0.3f * w, 0.64f * h); lineTo(0.08f * w, 0.64f * h); close()
        }, tint)
        line(0.62f, 0.36f, 0.9f, 0.64f)
        line(0.62f, 0.64f, 0.9f, 0.36f)
      }
      TransportIconKind.Queue -> {
        line(0.16f, 0.28f, 0.84f, 0.28f)
        line(0.16f, 0.5f, 0.84f, 0.5f)
        line(0.16f, 0.72f, 0.84f, 0.72f)
      }
    }
  }
}

@Composable
private fun TransportButton(
  icon: TransportIconKind,
  selected: Boolean = false,
  description: String = "",
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .size(34.dp)
      .clip(CircleShape)
      .background(if (selected) AccentRose.copy(alpha = 0.2f) else Color.Transparent)
      .clickable(onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center
  ) {
    TransportIcon(icon, tint = if (selected) AccentRose else TextMuted)
    if (icon == TransportIconKind.RepeatOne) {
      Text(
        text = "1",
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 4.dp),
        color = AccentRose,
        fontSize = 7.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

@Composable
private fun TransportPlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(46.dp)
      .clip(CircleShape)
      .background(if (enabled) AccentRose else CardGlass)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    TransportIcon(
      kind = if (playing) TransportIconKind.Pause else TransportIconKind.Play,
      tint = if (enabled) Color(0xFF26192D) else TextMuted,
      iconSize = 19.dp
    )
  }
}

@Composable
private fun OnlineSearchResults(
  query: String,
  localTracks: List<Track>,
  onlineTracks: List<Track>,
  playlists: List<Playlist>,
  loading: Boolean,
  error: String?,
  onPlayLocal: (Track) -> Unit,
  onPlayOnline: (Track) -> Unit,
  onAdd: (String, Track) -> Unit
) {
  Column(
    Modifier
      .fillMaxSize()
      .background(PanelSurface, RoundedCornerShape(16.dp))
      .border(1.dp, BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
      .padding(22.dp)
  ) {
    Label("SEARCH CATALOG", color = AccentRose, size = 10, weight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Label(if (query.isBlank()) "Search local & online music" else "Results for ‘$query’", size = 24, weight = FontWeight.Bold)
    Spacer(Modifier.height(14.dp))

    if (loading && onlineTracks.isEmpty() && localTracks.isEmpty()) {
      EmptyState("Searching YouTube Music…", "Looking through songs, albums, and artists.")
    } else if (error != null && onlineTracks.isEmpty() && localTracks.isEmpty()) {
      EmptyState("Could not load online results", error)
    } else if (onlineTracks.isEmpty() && localTracks.isEmpty()) {
      EmptyState("Start typing above", "Search any song, artist, Tamil/Telugu/Bollywood hit, or mood.")
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (localTracks.isNotEmpty()) {
          item { Label("IN YOUR LOCAL LIBRARY", color = AccentRose, size = 10, weight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)) }
          items(localTracks.take(6), key = { "local-${it.id}" }) { track ->
            OnlineResultRow(track, playlists, badge = "LOCAL", onPlay = { onPlayLocal(track) }, onAdd = { id -> onAdd(id, track) })
          }
        }
        if (onlineTracks.isNotEmpty()) {
          item { Label("ONLINE CATALOG", color = AccentRose, size = 10, weight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) }
          items(onlineTracks, key = { "online-${it.id}" }) { track ->
            OnlineResultRow(track, playlists, badge = null, onPlay = { onPlayOnline(track) }, onAdd = { id -> onAdd(id, track) })
          }
        }
      }
    }
  }
}

@Composable
private fun OnlineResultRow(track: Track, playlists: List<Playlist>, badge: String?, onPlay: () -> Unit, onAdd: (String) -> Unit) {
  var menu by remember { mutableStateOf(false) }
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(CardGlass.copy(alpha = 0.45f))
      .clickable(onClick = onPlay)
      .padding(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    TrackArtwork(track, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Label(track.title, size = 12, weight = FontWeight.SemiBold, maxLines = 1)
      Label(listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "), color = TextMuted, size = 10, maxLines = 1)
    }
    if (badge != null) {
      Text(
        text = badge,
        modifier = Modifier.background(CardElevated, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = AccentRose,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(Modifier.width(8.dp))
    }
    Label(track.durationLabel, color = TextMuted, size = 10)
    Spacer(Modifier.width(10.dp))
    ActionButton("▶", onPlay)
    Box {
      Text("•••", Modifier.clickable { menu = true }.padding(8.dp), color = TextMuted)
      DropdownMenu(menu, { menu = false }) {
        playlists.forEach { playlist ->
          DropdownMenuItem(text = { Text("Add to ${playlist.name}") }, onClick = { onAdd(playlist.id); menu = false })
        }
      }
    }
  }
}

@Composable
private fun PersonalizationDialog(
  countryCode: String,
  languages: List<String>,
  composers: List<String>,
  onDismiss: () -> Unit,
  onSave: (String, List<String>, List<String>) -> Unit
) {
  val countries = remember {
    Locale.getISOCountries()
      .map { code -> code to Locale("", code).getDisplayCountry(Locale.ENGLISH) }
      .filter { it.second.isNotBlank() }
      .sortedBy { it.second }
  }
  var country by remember(countryCode) { mutableStateOf(countryCode.ifBlank { Locale.getDefault().country.ifBlank { "IN" } }) }
  val selectedLanguages = remember(countryCode) { mutableStateListOf<String>().apply { addAll(languages) } }
  val selectedComposers = remember(countryCode) { mutableStateListOf<String>().apply { addAll(composers) } }
  var countryMenu by remember { mutableStateOf(false) }
  var countryQuery by remember { mutableStateOf("") }
  var composerText by remember { mutableStateOf("") }

  val languageOptions = remember(country) {
    Locale.getAvailableLocales()
      .filter { it.country.equals(country, ignoreCase = true) && it.language.isNotBlank() }
      .map { it.getDisplayLanguage(Locale.ENGLISH) }
      .filter(String::isNotBlank)
      .distinct()
      .sorted()
      .ifEmpty { listOf("English", "Tamil", "Hindi", "Telugu") }
  }

  AppDialog(onDismiss = onDismiss, width = 620.dp, height = 620.dp) {
    Text("Personalize your music", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    Text("Choose a region, languages, and composers for live YouTube Music shelves.", color = TextMuted, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Label("COUNTRY / REGION", color = AccentRose, size = 9, weight = FontWeight.Bold)
      Spacer(Modifier.height(5.dp))
      ActionButton("${Locale("", country).getDisplayCountry(Locale.ENGLISH)}   ▾", { countryMenu = !countryMenu })
      if (countryMenu) {
        Spacer(Modifier.height(6.dp))
                OutlinedTextField(countryQuery, { countryQuery = it }, singleLine = true, label = { Text("Find a country") }, modifier = Modifier.fillMaxWidth().trackTextEntryFocus())
        val filteredCountries = countries.filter { it.second.contains(countryQuery, true) || it.first.contains(countryQuery, true) }
        LazyColumn(Modifier.fillMaxWidth().height(200.dp).background(CardGlass, RoundedCornerShape(8.dp))) {
          items(filteredCountries) { (code, name) ->
            Row(Modifier.fillMaxWidth().clickable { country = code; countryMenu = false; countryQuery = "" }.padding(horizontal = 12.dp, vertical = 8.dp)) {
              Text(name, color = TextPrimary, fontSize = 12.sp)
            }
          }
        }
      }
      Spacer(Modifier.height(16.dp))
      Label("MUSIC LANGUAGES", color = AccentRose, size = 9, weight = FontWeight.Bold)
      Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
        languageOptions.forEach { language ->
          Row(
            Modifier.fillMaxWidth().clickable {
              if (language in selectedLanguages) selectedLanguages.remove(language) else selectedLanguages.add(language)
            }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Checkbox(language in selectedLanguages, onCheckedChange = { checked ->
              if (checked && language !in selectedLanguages) selectedLanguages.add(language)
              else if (!checked) selectedLanguages.remove(language)
            })
            Text(language, color = TextPrimary, fontSize = 12.sp)
          }
        }
      }
      Spacer(Modifier.height(14.dp))
      Label("FAVOURITE COMPOSERS", color = AccentRose, size = 9, weight = FontWeight.Bold)
      Spacer(Modifier.height(5.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = composerText,
          onValueChange = { composerText = it },
          modifier = Modifier.weight(1f).trackTextEntryFocus(),
          singleLine = true,
          label = { Text("Add a composer (e.g., A.R. Rahman, Anirudh)") },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = {
            composerText.trim().takeIf(String::isNotBlank)?.let { if (it !in selectedComposers) selectedComposers.add(it) }
            composerText = ""
          })
        )
        Spacer(Modifier.width(6.dp))
        ActionButton("＋ Add", {
          composerText.trim().takeIf(String::isNotBlank)?.let { if (it !in selectedComposers) selectedComposers.add(it) }
          composerText = ""
        })
      }
      if (selectedComposers.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          selectedComposers.forEach { name ->
            Text(
              text = "$name  ×",
              modifier = Modifier.clip(CircleShape).background(CardElevated).clickable { selectedComposers.remove(name) }.padding(horizontal = 10.dp, vertical = 6.dp),
              color = TextPrimary,
              fontSize = 10.sp
            )
          }
        }
      }
    }
    Spacer(Modifier.height(12.dp))
    DialogActions(onCancel = onDismiss, confirmLabel = "Save preferences", onConfirm = {
      onSave(country, selectedLanguages.distinct(), selectedComposers.distinct())
    })
  }
}

@Composable
private fun PlaylistGridCard(playlist: Playlist, tracks: List<Track>, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
  var menu by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardGlass).clickable(onClick = onOpen).padding(10.dp)) {
    Box {
      PlaylistArtwork(playlist, tracks, Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(9.dp)))
      Box(Modifier.align(Alignment.TopEnd)) {
        Text("•••", Modifier.clickable { menu = true }.padding(7.dp), color = TextPrimary)
        DropdownMenu(menu, { menu = false }) {
          DropdownMenuItem(text = { Text("Open playlist") }, onClick = { menu = false; onOpen() })
          if (playlist.id != "liked") {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
          }
        }
      }
    }
    Spacer(Modifier.height(9.dp))
    Label(playlist.name, size = 13, weight = FontWeight.SemiBold, maxLines = 1)
    Label("${playlist.trackIds.size} songs", color = TextMuted, size = 10)
  }
}

@Composable
private fun PlaylistArtwork(playlist: Playlist, tracks: List<Track>, modifier: Modifier = Modifier) {
  val covers = playlist.trackIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }.take(4)
  if (covers.isEmpty()) {
    Box(modifier.background(CardElevated), contentAlignment = Alignment.Center) {
      Label("♫", color = AccentRose, size = 28, weight = FontWeight.Bold)
    }
  } else if (covers.size == 1) {
    TrackArtwork(covers.first(), modifier)
  } else {
    Row(modifier) {
      Column(Modifier.weight(1f).fillMaxHeight()) {
        TrackArtwork(covers[0], Modifier.weight(1f).fillMaxWidth())
        if (covers.size > 2) TrackArtwork(covers[2], Modifier.weight(1f).fillMaxWidth()) else Box(Modifier.weight(1f).fillMaxWidth().background(CardElevated))
      }
      Column(Modifier.weight(1f).fillMaxHeight()) {
        TrackArtwork(covers[1], Modifier.weight(1f).fillMaxWidth())
        if (covers.size > 3) TrackArtwork(covers[3], Modifier.weight(1f).fillMaxWidth()) else Box(Modifier.weight(1f).fillMaxWidth().background(CardElevated))
      }
    }
  }
}

@Composable
private fun PlaylistSongPicker(tracks: List<Track>, alreadyAdded: List<String>, onAdd: (Track) -> Unit, onDismiss: () -> Unit) {
  var query by remember { mutableStateOf("") }
  val filtered = tracks.filter { listOf(it.title, it.artist, it.album).any { value -> value.contains(query, true) } }
  AppDialog(onDismiss = onDismiss, width = 620.dp, height = 560.dp) {
    Text("Add songs", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.weight(1f)) {
      OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Filter your library") }, modifier = Modifier.fillMaxWidth().trackTextEntryFocus())
      Spacer(Modifier.height(8.dp))
      if (filtered.isEmpty()) EmptyState("No songs found", "Import music or change the search.")
      else LazyColumn(Modifier.weight(1f)) {
        items(filtered, key = { it.id }) { track ->
          Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(track, Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
              Label(track.title, size = 11, weight = FontWeight.SemiBold, maxLines = 1)
              Label(track.artist, color = TextMuted, size = 9, maxLines = 1)
            }
            val added = track.id in alreadyAdded
            ActionButton(if (added) "Added" else "＋ Add", { if (!added) onAdd(track) })
          }
        }
      }
    }
    Spacer(Modifier.height(12.dp))
    DialogActions(onCancel = onDismiss, confirmLabel = "Done", onConfirm = onDismiss, cancelLabel = "")
  }
}

@Composable
private fun TrackArtwork(track: Track, modifier: Modifier = Modifier, hiRes: Boolean = false) =
  RemoteImage(track.remoteArtworkUrl ?: track.artworkPath, modifier, hiRes)

// YouTube thumbnail URLs carry a size spec (…=w120-h120-l90-rj); bump it so the
// hero panel doesn't upscale a 120px thumbnail. ~1400px is the server cap.
private val ytThumbSize = Regex("=w\\d+-h\\d+")

private fun hiResSource(source: String, hiRes: Boolean): String =
  if (source.contains("googleusercontent.com") || source.contains("ggpht.com")) {
    ytThumbSize.replace(source, if (hiRes) "=w1200-h1200" else "=w544-h544")
  } else {
    source
  }

@Composable
private fun RemoteImage(source: String?, modifier: Modifier = Modifier, hiRes: Boolean = false) {
  var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(source) {
    bitmap = if (source.isNullOrBlank()) null else withContext(Dispatchers.IO) {
      runCatching {
        val url = if (source.startsWith("http://") || source.startsWith("https://")) hiResSource(source, hiRes) else source
        val bytes = if (url.startsWith("http://") || url.startsWith("https://")) {
          URL(url).openStream().use { it.readBytes() }
        } else {
          File(url).readBytes()
        }
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
      }.getOrNull()
    }
  }
  Box(modifier.background(CardElevated), contentAlignment = Alignment.Center) {
    if (bitmap != null) {
      Image(bitmap!!, contentDescription = "Artwork", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
    } else {
      Label("♫", color = AccentRose, size = 28, weight = FontWeight.Bold)
    }
  }
}

@Composable
private fun TrackRow(
  track: Track,
  active: Boolean,
  onPlay: () -> Unit,
  onFavorite: () -> Unit,
  onRemove: () -> Unit,
  onAdd: (String) -> Unit = {},
  playlists: List<Playlist> = emptyList(),
  onMoveUp: () -> Unit = {},
  onMoveDown: () -> Unit = {},
  showReorder: Boolean = false,
  removeLabel: String? = null
) {
  var menu by remember { mutableStateOf(false) }
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(9.dp))
      .background(if (active) CardElevated else Color.Transparent)
      .clickable(onClick = onPlay)
      .padding(horizontal = 9.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    TrackArtwork(track, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Label(track.title, color = if (active) AccentRose else TextPrimary, size = 12, weight = FontWeight.SemiBold, maxLines = 1)
      Label(track.artist, color = TextMuted, size = 10, maxLines = 1)
    }
    Label(track.album, Modifier.weight(0.7f), TextMuted, 10, maxLines = 1)
    Label(track.durationLabel, Modifier.width(52.dp), TextMuted, 10)
    Text(
      text = if (track.isFavorite) "♥" else "♡",
      modifier = Modifier.clickable(onClick = onFavorite).padding(horizontal = 8.dp),
      color = if (track.isFavorite) AccentRose else TextMuted,
      fontSize = 15.sp
    )
    Box {
      Text("•••", Modifier.clickable { menu = true }.padding(horizontal = 5.dp), color = TextMuted)
      DropdownMenu(menu, { menu = false }) {
        playlists.forEach { p ->
          DropdownMenuItem(text = { Text("Add to ${p.name}") }, onClick = { onAdd(p.id); menu = false })
        }
        if (showReorder) {
          DropdownMenuItem(text = { Text("Move up") }, onClick = { onMoveUp(); menu = false })
          DropdownMenuItem(text = { Text("Move down") }, onClick = { onMoveDown(); menu = false })
        }
        removeLabel?.let { label ->
          DropdownMenuItem(text = { Text(label) }, onClick = { onRemove(); menu = false })
        }
      }
    }
  }
}

@Composable
private fun ActionButton(text: String, action: () -> Unit) = Text(
  text = text,
  modifier = Modifier
    .clip(CircleShape)
    .background(CardElevated)
    .border(1.dp, BorderSubtle.copy(alpha = 0.6f), CircleShape)
    .clickable(onClick = action)
    .padding(horizontal = 13.dp, vertical = 7.dp),
  color = TextPrimary,
  fontSize = 11.sp,
  fontWeight = FontWeight.SemiBold
)

@Composable
private fun AppDialog(onDismiss: () -> Unit, width: Dp = 520.dp, height: Dp = 260.dp, content: @Composable ColumnScope.() -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.width(width).height(height), color = PanelSurface, shape = RoundedCornerShape(18.dp)) {
      Column(Modifier.fillMaxSize().padding(22.dp), content = content)
    }
  }
}

@Composable
private fun DialogActions(onCancel: () -> Unit, confirmLabel: String, onConfirm: () -> Unit, cancelLabel: String = "Cancel") {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
    if (cancelLabel.isNotEmpty()) TextButton(onClick = onCancel) { Text(cancelLabel, color = TextMuted) }
    TextButton(onClick = onConfirm) { Text(confirmLabel, color = AccentRose, fontWeight = FontWeight.Bold) }
  }
}

@Composable
private fun EmptyState(title: String, detail: String) = Column(
  Modifier.fillMaxWidth().padding(vertical = 26.dp),
  horizontalAlignment = Alignment.CenterHorizontally
) {
  Label("♫", color = AccentRose, size = 28)
  Spacer(Modifier.height(8.dp))
  Label(title, size = 14, weight = FontWeight.SemiBold)
  Spacer(Modifier.height(5.dp))
  Label(detail, color = TextMuted, size = 11)
}

@Composable
private fun SettingsSection(
  section: String,
  title: String,
  detail: String,
  content: (@Composable ColumnScope.() -> Unit)? = null
) = Column(
  Modifier
    .fillMaxWidth()
    .background(CardGlass.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
    .padding(horizontal = 16.dp, vertical = 14.dp)
) {
  Label(section, color = AccentRose, size = 9, weight = FontWeight.Bold)
  Spacer(Modifier.height(5.dp))
  Label(title, size = 13, weight = FontWeight.SemiBold)
  Spacer(Modifier.height(4.dp))
  Label(detail, color = TextMuted, size = 11)
  if (content != null) {
    Spacer(Modifier.height(12.dp))
    content()
  }
}

@Composable
private fun Label(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = TextPrimary,
  size: Int = 12,
  weight: FontWeight = FontWeight.Normal,
  maxLines: Int = Int.MAX_VALUE
) = Text(
  text = text,
  modifier = modifier,
  color = color,
  fontSize = size.sp,
  fontWeight = weight,
  lineHeight = (size + 5).sp,
  maxLines = maxLines,
  overflow = TextOverflow.Ellipsis
)

private fun time(ms: Long): String {
  val seconds = (ms / 1000).coerceAtLeast(0)
  return "%d:%02d".format(seconds / 60, seconds % 60)
}
