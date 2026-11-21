package solo.music.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoriteStateTest {
  @Test fun `setting and removing a favorite synchronizes track and liked playlist`() {
    val track = Track("song-1", "song.mp3", "Song", "Artist")
    val initial = LibrarySnapshot(tracks = listOf(track))

    val liked = initial.setFavorite(track, true)
    assertTrue(liked.tracks.single().isFavorite)
    assertEquals(listOf(track.id), liked.playlists.single { it.id == "liked" }.trackIds)

    val unliked = liked.setFavorite(track.copy(isFavorite = false), false)
    assertFalse(unliked.tracks.single().isFavorite)
    assertTrue(unliked.playlists.single { it.id == "liked" }.trackIds.isEmpty())
  }

  @Test fun `startup reconciliation restores legacy favorite state and stale track copies`() {
    val track = Track("online-1", "https://youtube.com/watch", "Song", "Artist")
    val snapshot = LibrarySnapshot(
      tracks = listOf(track),
      playlists = listOf(Playlist("liked", "Liked songs", listOf(track.id)))
    ).reconcileFavorites()

    assertTrue(snapshot.tracks.single().isFavorite)
    assertTrue(track.withFavoriteStatus(snapshot).isFavorite)
  }
}
