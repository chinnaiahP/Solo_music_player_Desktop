package solo.music.playback

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import solo.music.core.Track
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Binds Solo's Windows System Media Transport Controls session to its own HWND. */
class WindowsSmtcBridge(
  private val onAction: (String) -> Unit,
  private val onError: (String) -> Unit,
) : AutoCloseable {
  private val enabled = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "Solo-Windows-SMTC").apply { isDaemon = true }
  }
  @Volatile private var closed = false
  private var api: SmtcNative? = null
  private var initialized = false
  private var lastState: String? = null
  private var reportedError = false
  // JNA needs a strong reference for as long as native code may call the delegate.
  private val callback = ActionCallback { action ->
    val name = when (action) {
      1 -> "PLAY"
      2 -> "PAUSE"
      3 -> "NEXT"
      4 -> "PREVIOUS"
      else -> return@ActionCallback
    }
    runCatching { onAction(name) }
  }

  /** Initialize the media session as soon as the app window exists, retrying briefly.
   * Registering early makes keyboard media keys and Bluetooth headset buttons work
   * even before the first track has played. */
  fun prepare() {
    if (!enabled || closed) return
    runCatching {
      executor.execute {
        repeat(20) { attempt ->
          if (closed || initialized) return@execute
          try { ensureInitialized() } catch (error: Throwable) {
            log("init attempt ${attempt + 1} failed: ${error.message}")
          }
          if (!initialized && !closed) Thread.sleep(500)
        }
        if (!initialized) {
          log("media controls could not initialize after retries")
          report("Could not initialize Windows media controls")
        }
      }
    }
  }

  private fun log(message: String) {
    runCatching {
      val appData = System.getenv("APPDATA") ?: return
      File(appData, "SoloMusicPlayer/smtc.log")
        .appendText("${java.time.LocalTime.now()}  $message\n")
    }
  }

  fun update(track: Track?, playing: Boolean) {
    if (!enabled || closed) return
    val state = track?.let { Triple(it, playing, "${it.id}|$playing|${it.title}|${it.artist}") }
    runCatching {
      executor.execute {
        try {
          if (state != null) {
            val (current, isPlaying, key) = state
            ensureInitialized()
            if (key != lastState) {
              checkResult(api!!.SoloSmtcSetState(if (isPlaying) 1 else 0, WString(current.title), WString(current.artist)))
              lastState = key
            }
          }
          // When the queue empties, keep the session registered so Windows keeps routing
          // media/headset buttons to Solo — just don't push any state.
        } catch (error: Throwable) {
          report(error.message ?: "Could not initialize Windows media controls")
          shutdownNative()
        }
      }
    }.onFailure { if (!closed) report(it.message ?: "Could not queue Windows media controls update") }
  }

  private fun ensureInitialized() {
    if (initialized) return
    val appData = System.getenv("APPDATA") ?: File(System.getProperty("user.home"), "AppData/Roaming").path
    val folder = File(appData, "SoloMusicPlayer/native")
    check(folder.exists() || folder.mkdirs()) { "Cannot create the Solo Music Player native folder" }
    val target = File(folder, DLL_NAME)
    val staging = File(folder, "$DLL_NAME.tmp")
    val resource = javaClass.getResourceAsStream("/native/$DLL_NAME")
      ?: error("Windows media controls are missing from this build. Rebuild on Windows with Visual Studio C++ tools installed.")
    resource.use { source -> staging.outputStream().use { destination -> source.copyTo(destination) } }
    if (!staging.renameTo(target)) {
      staging.copyTo(target, overwrite = true)
      check(staging.delete() || !staging.exists()) { "Cannot replace the Windows media controls library" }
    }
    api = Native.load(target.absolutePath, SmtcNative::class.java)
    val window = findAppWindow()
      ?: error("Could not find the Solo Music Player window for Windows media controls")
    checkResult(api!!.SoloSmtcInitialize(window, callback))
    initialized = true
    lastState = null
    reportedError = false
  }

  /** Locate our top-level window by process id — more reliable than matching the window title,
   * which Compose renders on a helper HWND that FindWindowW may not return. */
  private fun findAppWindow(): Pointer? {
    val pid = ProcessHandle.current().pid()
    val found = arrayOfNulls<Pointer>(1)
    val enumProc = object : User32.WndEnumProc {
      override fun callback(hwnd: Pointer, extra: Pointer?): Boolean {
        val ref = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, ref)
        if (ref.value.toLong() == pid && User32.INSTANCE.IsWindowVisible(hwnd)) {
          found[0] = hwnd
          return false
        }
        return true
      }
    }
    runCatching { User32.INSTANCE.EnumWindows(enumProc, null) }
    return found[0] ?: runCatching {
      User32.INSTANCE.FindWindowW(null, WString("Solo Music Player"))
    }.getOrNull()
  }

  private fun shutdownNative() {
    if (initialized) runCatching { api?.SoloSmtcShutdown() }
    initialized = false
    lastState = null
    api = null
  }

  private fun checkResult(code: Int) {
    if (code < 0) error("Windows SMTC returned HRESULT 0x${code.toUInt().toString(16)}")
  }

  @Synchronized private fun report(message: String) {
    if (!reportedError) {
      reportedError = true
      onError(message)
    }
  }

  override fun close() {
    if (!enabled || closed) return
    closed = true
    runCatching {
      executor.submit { shutdownNative() }.get(2, TimeUnit.SECONDS)
    }
    executor.shutdownNow()
  }

  private interface SmtcNative : Library {
    fun SoloSmtcInitialize(window: Pointer, callback: ActionCallback): Int
    fun SoloSmtcSetState(state: Int, title: WString, artist: WString): Int
    fun SoloSmtcShutdown(): Int
  }

  private fun interface ActionCallback : Callback {
    fun invoke(action: Int)
  }

  private interface User32 : Library {
    fun FindWindowW(className: WString?, windowName: WString): Pointer?
    fun EnumWindows(callback: WndEnumProc, extra: Pointer?): Boolean
    fun GetWindowThreadProcessId(hwnd: Pointer, processId: IntByReference): Int
    fun IsWindowVisible(hwnd: Pointer): Boolean

    interface WndEnumProc : StdCallCallback {
      fun callback(hwnd: Pointer, extra: Pointer?): Boolean
    }

    companion object {
      val INSTANCE: User32 = Native.load("user32", User32::class.java)
    }
  }

  companion object { private const val DLL_NAME = "SoloSmtcBridge.dll" }
}
