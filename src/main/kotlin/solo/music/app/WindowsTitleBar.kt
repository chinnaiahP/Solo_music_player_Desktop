package solo.music.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/** Asks Windows DWM to render the native title bar in the app's dark theme. */
object WindowsTitleBar {
  private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
  private const val DWMWA_BORDER_COLOR = 34
  private const val DWMWA_CAPTION_COLOR = 35
  private const val DWMWA_TEXT_COLOR = 36

  private val dwm: Dwmapi? = runCatching {
    if (System.getProperty("os.name").startsWith("Windows")) Native.load("dwmapi", Dwmapi::class.java) else null
  }.getOrNull()

  fun applyDark(window: Window) {
    val api = dwm ?: return
    val hwnd = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return

    // Dark chrome everywhere supported (Windows 10 1903+ / Windows 11)
    api.set(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 1)
    // Exact theme colors — Windows 11 22H2+ only, calls are ignored elsewhere
    api.set(hwnd, DWMWA_CAPTION_COLOR, colorRef(0x161119))   // BgCanvas
    api.set(hwnd, DWMWA_TEXT_COLOR, colorRef(0xF6EFF7))      // TextPrimary
    api.set(hwnd, DWMWA_BORDER_COLOR, colorRef(0x161119))    // seamless border
  }

  private fun Dwmapi.set(hwnd: Pointer, attribute: Int, value: Int) {
    runCatching { DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4) }
  }

  private fun colorRef(rgb: Int) =
    (rgb and 0xFF shl 16) or (rgb and 0x00FF00) or (rgb shr 16 and 0xFF)

  private interface Dwmapi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, dwAttribute: Int, pvAttribute: IntByReference, cbAttribute: Int): Int
  }
}
