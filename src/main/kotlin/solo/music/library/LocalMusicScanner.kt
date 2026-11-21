package solo.music.library

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import solo.music.core.Track
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

class LocalMusicScanner(private val artworkDirectory: File) {
  private val extensions = setOf("mp3", "m4a", "aac", "wav", "aif", "aiff", "flac", "ogg")

  fun scan(input: List<File>): List<Track> {
    val files = input.flatMap { entry ->
      when {
        entry.isFile && entry.extension.lowercase(Locale.ROOT) in extensions -> listOf(entry)
        entry.isDirectory -> entry.walkTopDown().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in extensions }.toList()
        else -> emptyList()
      }
    }.distinctBy { it.canonicalPath }
    return files.mapNotNull(::readTrack)
  }

  private fun readTrack(file: File): Track? = try {
    val audio = AudioFileIO.read(file)
    val tag = audio.tag
    val title = tag?.getFirst(FieldKey.TITLE)?.trim()?.takeIf(String::isNotEmpty) ?: file.nameWithoutExtension
    val artist = tag?.getFirst(FieldKey.ARTIST)?.trim()?.takeIf(String::isNotEmpty) ?: "Unknown artist"
    val album = tag?.getFirst(FieldKey.ALBUM)?.trim()?.takeIf(String::isNotEmpty) ?: "Unknown album"
    val id = hash(file.canonicalPath.lowercase(Locale.ROOT))
    val art = tag?.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() }?.let { bytes ->
      artworkDirectory.mkdirs()
      val output = File(artworkDirectory, "$id.jpg")
      if (!output.exists()) output.writeBytes(bytes)
      output.absolutePath
    }
    Track(id, file.canonicalPath, title, artist, album, audio.audioHeader.trackLength * 1000L, art)
  } catch (_: Exception) {
    val name = file.nameWithoutExtension
    Track(hash(file.absolutePath.lowercase(Locale.ROOT)), file.canonicalPath, name, durationMs = 0)
  }

  private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(Normalizer.normalize(text, Normalizer.Form.NFC).toByteArray())
    .take(16).joinToString("") { "%02x".format(it) }
}
