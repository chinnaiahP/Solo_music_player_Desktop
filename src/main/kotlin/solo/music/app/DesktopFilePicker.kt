package solo.music.app

import java.io.File
import java.awt.Frame
import java.awt.EventQueue
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

object DesktopFilePicker {
  fun audioFiles(): List<File> = onAwt {
    val chooser = JFileChooser().apply {
      dialogTitle = "Add music to Solo Music Player"
      isMultiSelectionEnabled = true
      fileSelectionMode = JFileChooser.FILES_ONLY
      fileFilter = FileNameExtensionFilter("Audio files", "mp3", "m4a", "aac", "wav", "aif", "aiff", "flac", "ogg")
      isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) } else emptyList()
  }

  fun musicFolder(): File? = onAwt {
    val chooser = JFileChooser().apply { dialogTitle = "Choose a music folder"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; isAcceptAllFileFilterUsed = false }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
  }

  private fun <T> onAwt(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    val result = AtomicReference<T>()
    val latch = CountDownLatch(1)
    EventQueue.invokeLater { try { result.set(block()) } finally { latch.countDown() } }
    latch.await()
    return result.get()
  }
}
