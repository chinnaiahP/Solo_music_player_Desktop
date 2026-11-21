package solo.music.core

/** The track flag and Liked songs playlist are persisted mirrors of one favourite state. */
fun LibrarySnapshot.favoriteTrackIds(): Set<String> =
  tracks.asSequence().filter(Track::isFavorite).map(Track::id).toSet() +
    playlists.firstOrNull { it.id == "liked" }?.trackIds.orEmpty()

fun LibrarySnapshot.reconcileFavorites(): LibrarySnapshot {
  val favoriteIds = favoriteTrackIds()
  val syncedTracks = tracks.map { track ->
    if (track.isFavorite == (track.id in favoriteIds)) track else track.copy(isFavorite = track.id in favoriteIds)
  }
  val liked = playlists.firstOrNull { it.id == "liked" }
  val syncedLiked = (liked ?: Playlist("liked", "Liked songs")).copy(trackIds = favoriteIds.toList())
  val other = playlists.filterNot { it.id == "liked" }
  return copy(tracks = syncedTracks, playlists = listOf(syncedLiked) + other)
}

fun LibrarySnapshot.setFavorite(track: Track, favorite: Boolean): LibrarySnapshot {
  val nextTracks = if (tracks.any { it.id == track.id }) {
    tracks.map { if (it.id == track.id) it.copy(isFavorite = favorite) else it }
  } else tracks + track.copy(isFavorite = favorite)
  val currentLiked = playlists.firstOrNull { it.id == "liked" } ?: Playlist("liked", "Liked songs")
  val nextLiked = currentLiked.copy(
    trackIds = if (favorite) (currentLiked.trackIds + track.id).distinct() else currentLiked.trackIds - track.id
  )
  return copy(tracks = nextTracks, playlists = listOf(nextLiked) + playlists.filterNot { it.id == "liked" }).reconcileFavorites()
}

fun Track.withFavoriteStatus(snapshot: LibrarySnapshot): Track =
  copy(isFavorite = id in snapshot.favoriteTrackIds())
