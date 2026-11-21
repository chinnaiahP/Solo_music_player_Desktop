package solo.music.online

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeAudioCacheTest {
  @Test fun `downloads online audio to a local playback file with browser headers`() {
    val payload = ByteArray(8192) { (it % 251).toByte() }
    val userAgent = AtomicReference<String?>()
    val referer = AtomicReference<String?>()
    val range = AtomicReference<String?>()
    val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/audio") { exchange ->
        userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
        referer.set(exchange.requestHeaders.getFirst("Referer"))
        range.set(exchange.requestHeaders.getFirst("Range"))
        exchange.responseHeaders.set("Content-Type", "audio/mp4")
        exchange.responseHeaders.set("Content-Range", "bytes 0-${payload.lastIndex}/${payload.size}")
        exchange.sendResponseHeaders(206, payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
      }
      start()
    }
    val cache = java.nio.file.Files.createTempDirectory("solo-audio-cache-test").toFile()
    try {
      val service = YouTubeMusicService(cache)
      val file = service.cacheAudio("test-video", "http://127.0.0.1:${origin.address.port}/audio")
      assertTrue(file.isFile)
      assertContentEquals(payload, file.readBytes())
      assertTrue(userAgent.get()?.contains("Mozilla") == true)
      assertEquals("https://music.youtube.com/", referer.get())
      assertEquals("bytes=0-1048575", range.get())
    } finally {
      origin.stop(0)
      cache.deleteRecursively()
    }
  }
}
