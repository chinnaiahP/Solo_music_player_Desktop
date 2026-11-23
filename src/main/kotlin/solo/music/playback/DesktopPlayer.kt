package solo.music.playback

import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaPlayer.Status
import javafx.util.Duration
import solo.music.core.Track
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DesktopPlayer {
  data class State(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = .78f,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val shufflePlayed: Set<Int> = emptySet(),
    val error: String? = null,
  ) { val current: Track? get() = queue.getOrNull(index) }

  private val flow = MutableStateFlow(State())
  val state: StateFlow<State> = flow.asStateFlow()
  private var mediaPlayer: MediaPlayer? = null
  private var desiredPlayback = false
  private val random = java.util.Random()
  private var fxReady = AtomicBoolean(false)

  /** Resolves an online track to a local file/stream asynchronously (ok or err callback). */
  var trackResolver: ((track: Track, ok: (Track) -> Unit, err: (String) -> Unit) -> Unit)? = null

  private fun Track.needsResolution() =
    videoId != null && remoteStreamUrl.isNullOrBlank() && !File(path).isFile

  /** Replace a queue entry after it was resolved to a playable file. */
  fun updateQueueTrack(trackId: String, transform: (Track) -> Track) {
    flow.update { current -> current.copy(queue = current.queue.map { if (it.id == trackId) transform(it) else it }) }
  }

  init {
    val latch = CountDownLatch(1)
    try {
      Platform.startup { fxReady.set(true); latch.countDown() }
    } catch (_: IllegalStateException) {
      fxReady.set(true); latch.countDown()
    }
    latch.await(15, TimeUnit.SECONDS)
  }

  fun playQueue(tracks: List<Track>, startAt: Int = 0) {
    val valid = tracks.filter { File(it.path).isFile || !it.remoteStreamUrl.isNullOrBlank() || it.videoId != null }
    if (valid.isEmpty()) { flow.update { it.copy(error = "No available audio files in this queue") }; return }
    val bounded = startAt.coerceIn(0, valid.lastIndex)
    fx {
      flow.update { it.copy(queue = valid, index = bounded, shufflePlayed = if (it.shuffle) setOf(bounded) else emptySet(), error = null) }
      load(valid[bounded], true)
    }
  }

  fun play(track: Track) {
    val existing = flow.value.queue.indexOfFirst { it.id == track.id }
    val list = if (existing >= 0) flow.value.queue else flow.value.queue + track
    val index = list.indexOfFirst { it.id == track.id }
    flow.update { it.copy(queue = list, index = index, shufflePlayed = if (it.shuffle) setOf(index) else emptySet(), error = null) }
    fx { load(track, true) }
  }

  fun toggle() = fx {
    val player = mediaPlayer
    if (player == null) {
      // Still buffering/loading — cancel the pending playback instead of starting it.
      if (desiredPlayback) {
        desiredPlayback = false
        flow.update { it.copy(isPlaying = false) }
      } else {
        flow.value.current?.let { load(it, true) }
      }
      return@fx
    }
    when (player.status) {
      Status.PLAYING, Status.STALLED -> pauseOnFx(player)
      Status.READY, Status.PAUSED, Status.UNKNOWN -> if (desiredPlayback) pauseOnFx(player) else resumeOnFx(player)
      Status.STOPPED -> flow.value.current?.let { load(it, true) }
      else -> Unit
    }
  }

  fun resume() = fx { resumeOnFx(mediaPlayer) }

  fun pause() = fx {
    desiredPlayback = false
    val player = mediaPlayer
    if (player == null) flow.update { it.copy(isPlaying = false) } else pauseOnFx(player)
  }

  private fun resumeOnFx(player: MediaPlayer?) {
    if (player == null) {
      flow.value.current?.let { load(it, true) }
      return
    }
    desiredPlayback = true
    if (player.status == Status.STOPPED) flow.value.current?.let { load(it, true) } else player.play()
  }

  private fun pauseOnFx(player: MediaPlayer) {
    desiredPlayback = false
    val pausedAt = player.currentTime.toMillis().toLong().coerceAtLeast(0)
    if (player.status == Status.PLAYING || player.status == Status.STALLED || player.status == Status.UNKNOWN) player.pause()
    flow.update { it.copy(positionMs = pausedAt, isPlaying = false) }
  }

  fun stop() = fx {
    desiredPlayback = false
    runCatching { mediaPlayer?.stop() }
    runCatching { mediaPlayer?.dispose() }
    mediaPlayer = null
    flow.update { it.copy(queue = emptyList(), index = -1, isPlaying = false, positionMs = 0, durationMs = 0) }
  }

  fun release() = fx {
    runCatching { mediaPlayer?.dispose() }
    mediaPlayer = null
    Platform.exit()
  }

  fun next() = fx { advanceNext(automatic = false) }

  private fun advanceNext(automatic: Boolean) {
    val current = flow.value
    when (val decision = PlaybackModeEngine.next(
      size = current.queue.size,
      current = current.index,
      shuffle = current.shuffle,
      repeatMode = current.repeatMode,
      previouslyPlayed = current.shufflePlayed,
      automatic = automatic,
      random = random,
    )) {
      PlaybackModeEngine.Decision.Exhausted -> {
        if (automatic) flow.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
      }
      PlaybackModeEngine.Decision.ReplayCurrent -> restartCurrent()
      is PlaybackModeEngine.Decision.Play -> {
        flow.update { it.copy(index = decision.index, shufflePlayed = decision.played, error = null) }
        load(current.queue[decision.index], true)
      }
    }
  }

  private fun restartCurrent() {
    val player = mediaPlayer
    if (player == null) flow.value.current?.let { load(it, true) }
    else {
      flow.update { it.copy(positionMs = 0, isPlaying = true, error = null) }
      player.seek(Duration.ZERO)
      player.play()
    }
  }

  fun previous() = fx {
    if (flow.value.positionMs > 3000) { mediaPlayer?.seek(Duration.ZERO); return@fx }
    val current = flow.value
    if (current.queue.isEmpty()) return@fx
    val index = (current.index - 1).coerceAtLeast(0)
    flow.update { it.copy(index = index, error = null) }
    load(current.queue[index], true)
  }

  fun seekTo(positionMs: Long) = fx {
    val player = mediaPlayer ?: return@fx
    val duration = flow.value.durationMs
    val target = positionMs.coerceIn(0, if (duration > 0) duration else positionMs)
    player.seek(Duration.millis(target.toDouble()))
    flow.update { it.copy(positionMs = target) }
  }
  fun setVolume(value: Float) = fx {
    val safe = value.coerceIn(0f, 1f)
    mediaPlayer?.volume = safe.toDouble()
    flow.update { it.copy(volume = safe) }
  }
  /** Keep every queue/current-track copy in sync with the library favourite state. */
  fun setFavoriteStatus(trackId: String, isFavorite: Boolean) {
    flow.update { current ->
      val updated = current.queue.map { track -> if (track.id == trackId) track.copy(isFavorite = isFavorite) else track }
      if (updated == current.queue) current else current.copy(queue = updated)
    }
  }
  fun toggleShuffle() {
    flow.update { current ->
      val enabled = !current.shuffle
      current.copy(shuffle = enabled, shufflePlayed = if (enabled && current.index >= 0) setOf(current.index) else emptySet())
    }
  }
  fun cycleRepeat() { flow.update { it.copy(repeatMode = (it.repeatMode + 1) % 3) } }

  private fun load(track: Track, autoPlay: Boolean) {
    desiredPlayback = autoPlay
    // A MediaPlayer whose underlying Media failed has a null jfxPlayer internally —
    // stop()/dispose() then throw NPE, which would kill every later load. Guard both.
    runCatching { mediaPlayer?.stop() }
    runCatching { mediaPlayer?.dispose() }
    mediaPlayer = null
    if (track.needsResolution()) {
      val resolver = trackResolver
      if (resolver == null) {
        flow.update { it.copy(isPlaying = false, error = "Online audio is unavailable for this track") }
        return
      }
      flow.update { it.copy(error = null) }
      resolver(track,
        { resolved -> fx { if (flow.value.current?.id == resolved.id) load(resolved, desiredPlayback) } },
        { message -> fx { if (flow.value.current?.id == track.id) flow.update { it.copy(isPlaying = false, error = message) } } })
      return
    }
    try {
      val remote = track.remoteStreamUrl
      val source = if (!remote.isNullOrBlank()) remote else File(track.path).toURI().toString()
      val media = Media(source)
      val player = MediaPlayer(media)
      mediaPlayer = player
      player.volume = flow.value.volume.toDouble()
      player.setOnReady {
        if (mediaPlayer !== player) return@setOnReady
        flow.update { it.copy(durationMs = player.totalDuration.toMillis().toLong().coerceAtLeast(0), positionMs = 0, isPlaying = false, error = null) }
        if (desiredPlayback) player.play()
      }
      player.setOnPlaying {
        if (mediaPlayer === player && desiredPlayback) flow.update { it.copy(isPlaying = true) }
        else if (mediaPlayer === player) player.pause()
      }
      player.setOnPaused {
        if (mediaPlayer === player && player.status == Status.PAUSED) {
          val pausedAt = player.currentTime.toMillis().toLong().coerceAtLeast(0)
          flow.update { it.copy(isPlaying = false, positionMs = pausedAt) }
        }
      }
      player.setOnStopped { if (mediaPlayer === player) flow.update { it.copy(isPlaying = false) } }
      player.setOnEndOfMedia {
        if (mediaPlayer === player) {
          if (flow.value.repeatMode == REPEAT_ONE) restartCurrent()
          else advanceNext(automatic = true)
        }
      }
      player.currentTimeProperty().addListener { _, _, value ->
        if (mediaPlayer === player && player.status == Status.PLAYING) {
          flow.update { it.copy(positionMs = value.toMillis().toLong().coerceAtLeast(0)) }
        }
      }
      player.setOnError {
        if (mediaPlayer === player) {
          val mediaError = player.error ?: media.error
          val detail = mediaError?.message?.takeIf { it.isNotBlank() }
            ?: mediaError?.type?.name
          flow.update { it.copy(isPlaying = false, error = friendlyMediaError(detail) ?: "Could not play this file") }
        }
      }
      media.setOnError { flow.update { it.copy(error = friendlyMediaError(media.error?.message) ?: "Unsupported audio format") } }
    } catch (error: Exception) {
      flow.update { it.copy(isPlaying = false, error = friendlyMediaError(error.message) ?: "Could not open audio") }
    }
  }

  /** JavaFX media on Linux needs a system libavcodec matching its bundled plugins; when
   * it is absent the engine just reports "Could not create player!" — surface the fix. */
  private fun friendlyMediaError(message: String?): String? = when {
    message.isNullOrBlank() -> null
    message.contains("Could not create player", true) &&
      System.getProperty("os.name").startsWith("Linux", ignoreCase = true) ->
      "Audio codecs missing — install FFmpeg libraries (e.g. sudo apt install libavcodec61 libavformat61) and restart"
    else -> message
  }

  private fun fx(action: () -> Unit) {
    if (!fxReady.get()) { flow.update { it.copy(error = "Audio engine could not start") }; return }
    if (Platform.isFxApplicationThread()) action() else Platform.runLater(action)
  }

  companion object {
    const val SEQUENTIAL = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2
  }
}
