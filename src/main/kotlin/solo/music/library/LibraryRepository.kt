package solo.music.library

import kotlinx.serialization.json.Json
import solo.music.core.LibrarySnapshot
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

class LibraryRepository private constructor(private val file: File) {
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
  private val current = AtomicReference(load())

  @Synchronized fun snapshot(): LibrarySnapshot = current.get()

  @Synchronized fun update(transform: (LibrarySnapshot) -> LibrarySnapshot): LibrarySnapshot {
    val next = transform(current.get())
    current.set(next)
    file.parentFile?.mkdirs()
    val staging = File(file.parentFile, "${file.name}.tmp")
    staging.writeText(json.encodeToString(LibrarySnapshot.serializer(), next))
    try {
      Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Exception) {
      Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    return next
  }

  private fun load(): LibrarySnapshot = try {
    if (file.isFile) json.decodeFromString<LibrarySnapshot>(file.readText()) else LibrarySnapshot()
  } catch (_: Exception) {
    LibrarySnapshot()
  }

  companion object {
    fun default(): LibraryRepository {
      return LibraryRepository(File(solo.music.core.appDataDirectory(), "library.json"))
    }
  }
}
