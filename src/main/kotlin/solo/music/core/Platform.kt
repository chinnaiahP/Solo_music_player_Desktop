package solo.music.core

import java.io.File

/** Per-user data directory that follows each OS's conventions:
 * Windows %APPDATA%, macOS Application Support, Linux XDG data dir. */
fun appDataDirectory(): File {
  val home = System.getProperty("user.home")
  val os = System.getProperty("os.name")
  return when {
    os.startsWith("Windows", ignoreCase = true) ->
      File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, "SoloMusicPlayer")
    os.startsWith("Mac", ignoreCase = true) ->
      File(home, "Library/Application Support/SoloMusicPlayer")
    else ->
      File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "SoloMusicPlayer")
  }
}
