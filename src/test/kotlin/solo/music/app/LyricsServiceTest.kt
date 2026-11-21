package solo.music.app

import com.sun.net.httpserver.HttpServer
import solo.music.core.Track
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LyricsServiceTest {
  @Test fun `fetches synced lyrics for online tracks and caches the result`() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/api/get") { exchange ->
        val response = """{"trackName":"Example Song","artistName":"Example Artist","duration":180,"syncedLyrics":"[00:01.25]First line\n[00:03.50]Second line","plainLyrics":"First line\\nSecond line"}"""
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
      }
      start()
    }
    val cache = Files.createTempDirectory("solo-lyrics-test").toFile()
    try {
      val track = Track("yt:video", "https://youtube.com/watch?v=video", "Example Song", "Example Artist", durationMs = 180_000, videoId = "video")
      val service = LyricsService(cache, "http://127.0.0.1:${server.address.port}")
      val first = assertNotNull(service.get(track))
      assertEquals("LRCLIB", first.provider)
      assertTrue(first.synced)
      assertEquals(listOf(1250L, 3500L), first.lines.map { it.timeMs })
      assertEquals(2, service.get(track)?.lines?.size)
    } finally {
      server.stop(0)
      cache.deleteRecursively()
    }
  }

  @Test fun `reads local sidecar lyrics before going online and supports plain text`() {
    val folder = Files.createTempDirectory("solo-local-lyrics").toFile()
    try {
      val audio = folder.resolve("track.mp3")
      audio.writeBytes(byteArrayOf(1))
      folder.resolve("track.lrc").writeText("[00:01.2][00:03.4]Repeated line\n[00:05.00]Final line")
      val lines = LrcLyrics.readFor(audio.absolutePath)
      assertEquals(listOf(1200L, 3400L, 5000L), lines.map { it.timeMs })
      assertEquals("Repeated line", lines.first().text)
      assertEquals(-1, LrcLyrics.parse("A plain lyric line").single().timeMs)
    } finally { folder.deleteRecursively() }
  }

  @Test fun `online lyrics lookup searches a cleaned title when channel is not the recording artist`() {
    val query = AtomicReference<String?>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/api/get") { exchange ->
        exchange.sendResponseHeaders(404, -1)
        exchange.close()
      }
      createContext("/api/search") { exchange ->
        query.set(exchange.requestURI.rawQuery)
        val response = """[{"id":7,"trackName":"Butta Bomma","artistName":"Armaan Malik","duration":254,"syncedLyrics":"[00:01.00]Native lyric"}]"""
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
      }
      start()
    }
    val cache = Files.createTempDirectory("solo-lyrics-title-search").toFile()
    try {
      val track = Track("yt:butta", "https://youtube.com/watch?v=butta", "Butta Bomma Full Song (From Ala Vaikunthapurramuloo) | Telugu Video Song", "Episode", durationMs = 254_000, videoId = "butta")
      val result = LyricsService(cache, "http://127.0.0.1:${server.address.port}").get(track)
      assertNotNull(result)
      assertEquals("Native lyric", result.lines.single().text)
      assertTrue(query.get().orEmpty().contains("track_name=Butta+Bomma"))
    } finally {
      server.stop(0)
      cache.deleteRecursively()
    }
  }

  @Test fun `falls back to lyrics ovh plain lyrics when LRCLIB misses`() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/api/get") { exchange ->
        exchange.sendResponseHeaders(404, -1); exchange.close()
      }
      createContext("/api/search") { exchange ->
        exchange.sendResponseHeaders(200, "[]".toByteArray().size.toLong())
        exchange.responseBody.use { it.write("[]".toByteArray()) }
      }
      createContext("/v1/") { exchange ->
        assertTrue(exchange.requestURI.path.contains("Example Artist"))
        val response = """{"lyrics":"Plain line one\nPlain line two"}"""
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
      }
      start()
    }
    val cache = Files.createTempDirectory("solo-lyrics-ovh").toFile()
    try {
      val port = server.address.port
      val track = Track("local", "track.mp3", "Example Song", "Example Artist", durationMs = 180_000)
      val result = LyricsService(cache, "http://127.0.0.1:$port", ovhBaseUrl = "http://127.0.0.1:$port").get(track)
      assertNotNull(result)
      assertEquals("lyrics.ovh", result.provider)
      assertTrue(!result.synced)
      assertEquals(listOf("Plain line one", "Plain line two"), result.lines.map { it.text })
      assertEquals(-1L, result.lines.first().timeMs)
    } finally {
      server.stop(0)
      cache.deleteRecursively()
    }
  }

  @Test fun `DeepL translations keep lyric timestamps and never substitute unrelated text`() {
    val authorization = AtomicReference<String?>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v2/translate") { exchange ->
        authorization.set(exchange.requestHeaders.getFirst("Authorization"))
        val response = """{"translations":[{"text":"English first"},{"text":"English second"}]}"""
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
      }
      start()
    }
    val cache = Files.createTempDirectory("solo-lyrics-translate").toFile()
    try {
      val track = Track("local", "local.mp3", "Example", "Artist")
      val translated = LyricsService(cache, translationBaseUrl = "http://127.0.0.1:${server.address.port}")
        .translateToEnglish(track, listOf(LyricLine(1000, "Native one"), LyricLine(2000, "Native two")), "test-key")
      assertEquals("DeepL-Auth-Key test-key", authorization.get())
      assertEquals(listOf(1000L, 2000L), translated.map { it.timeMs })
      assertEquals(listOf("English first", "English second"), translated.map { it.text })
    } finally {
      server.stop(0)
      cache.deleteRecursively()
    }
  }
}
