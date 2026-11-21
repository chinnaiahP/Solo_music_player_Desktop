package solo.music.core

import kotlinx.serialization.Serializable

@Serializable
data class Track(
  val id: String,
  val path: String,
  val title: String,
  val artist: String = "Unknown artist",
  val album: String = "Unknown album",
  val durationMs: Long = 0,
  val artworkPath: String? = null,
  val addedAt: Long = System.currentTimeMillis(),
  val playCount: Int = 0,
  val lastPlayedAt: Long? = null,
  val isFavorite: Boolean = false,
  val videoId: String? = null,
  val remoteArtworkUrl: String? = null,
  val remoteStreamUrl: String? = null,
) {
  val isOnline: Boolean get() = videoId != null
  val durationLabel: String get() {
    val seconds = durationMs / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
  }
}

@Serializable
data class Playlist(val id: String, val name: String, val trackIds: List<String> = emptyList())

@Serializable
data class PlayerPreferences(
  val volume: Float = 0.78f,
  val shuffle: Boolean = false,
  val repeatMode: Int = 0,
  val musicFolders: List<String> = emptyList(),
  val countryCode: String = "",
  val musicLanguages: List<String> = emptyList(),
  val musicComposers: List<String> = emptyList(),
  val lyricsMode: String = "NATIVE",
  val deeplApiKey: String = "",
)

@Serializable
data class LibrarySnapshot(
  val tracks: List<Track> = emptyList(),
  val playlists: List<Playlist> = listOf(Playlist("liked", "Liked songs")),
  val history: List<String> = emptyList(),
  val preferences: PlayerPreferences = PlayerPreferences(),
)
