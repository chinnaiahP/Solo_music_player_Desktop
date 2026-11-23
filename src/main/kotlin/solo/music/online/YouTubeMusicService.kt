package solo.music.online

import kotlinx.serialization.json.*
import solo.music.core.Track
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.io.File
import java.util.UUID

data class MusicShelf(
  val title: String,
  val tracks: List<Track> = emptyList(),
  val playlists: List<MusicPlaylist> = emptyList()
)

data class MusicPlaylist(
  val browseId: String,
  val title: String,
  val subtitle: String = "",
  val thumbnailUrl: String? = null
)

data class MoodCategory(val title: String, val browseId: String, val params: String? = null)

/** Anonymous YouTube Music catalog client. Search and browse use the same public catalog endpoints as the web client. */
class YouTubeMusicService(private val onlineCacheDirectory: File = defaultOnlineCacheDirectory()) {
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
  @Volatile private var guestVisitorData: String? = null

  fun search(query: String, countryCode: String = "US"): List<Track> {
    if (query.isBlank()) return emptyList()
    val body = buildJsonObject {
      put("context", context("WEB_REMIX", "1.20260707.12.00", countryCode))
      put("query", query)
    }
    return parseTracks(post("search", body)).distinctBy { it.id }
  }

  fun home(countryCode: String = "US", languages: List<String> = emptyList(), composers: List<String> = emptyList()): List<MusicShelf> {
    val body = buildJsonObject {
      put("context", context("WEB_REMIX", "1.20260707.12.00", countryCode))
      put("browseId", "FEmusic_charts")
    }
    val root = post("browse", body)
    val shelves = parseShelves(root).toMutableList()
    val normalizedCountry = countryCode.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"

    // These query-backed collections use the current catalog ranking and
    // releases from YouTube Music; no local/mock track lists are generated.
    val queries = buildList {
      add("trending songs in $normalizedCountry")
      add("new music releases in $normalizedCountry")
      languages.take(4).forEach { add("latest $it songs") }
      composers.map(String::trim).filter(String::isNotBlank).distinct().take(4).forEach { add("$it composer songs") }
    }.distinct()
    queries.forEach { query ->
      val results = runCatching { search(query, normalizedCountry) }.getOrDefault(emptyList())
      if (results.isNotEmpty()) shelves += MusicShelf(query.replaceFirstChar(Char::uppercase), results)
    }

    if (shelves.isNotEmpty()) return shelves.distinctBy { it.title to it.tracks.map(Track::id) }.take(8)

    val defaultBrowse = post("browse", buildJsonObject {
      put("context", context("WEB_REMIX", "1.20260707.12.00", normalizedCountry))
      put("browseId", "FEmusic_home")
    })
    val homeShelves = parseShelves(defaultBrowse)
    if (homeShelves.isNotEmpty()) return homeShelves
    val tracks = parseTracks(root).distinctBy { it.id }
    if (tracks.isNotEmpty()) return listOf(MusicShelf("Featured in $normalizedCountry", tracks))

    val rendererCounts = root.objects().flatMap { it.keys.asSequence() }
      .filter { it.endsWith("Renderer") }.groupingBy { it }.eachCount()
      .entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key}=${it.value}" }
    throw IllegalStateException("YouTube Music returned no playable songs. Try Search. Response renderers: ${rendererCounts.ifBlank { "none" }}")
  }

  private fun parseShelves(root: JsonObject): List<MusicShelf> = root.objects()
    .mapNotNull { it["musicCarouselShelfRenderer"]?.jsonObjectOrNull() }
    .mapNotNull { renderer ->
      val title = renderer["header"]?.findValue("title")?.let(::renderedText)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      val tracks = renderer["contents"]?.let(::parseTracks).orEmpty().distinctBy { it.id }
      val playlists = renderer["contents"]?.let(::parsePlaylists).orEmpty().distinctBy { it.browseId }
      if (tracks.isEmpty() && playlists.isEmpty()) null else MusicShelf(title, tracks, playlists)
    }.toList()

  /** YouTube Music mood & genre chips (Chill, Workout, Focus…) from the explore page. */
  fun moods(countryCode: String = "US"): List<MoodCategory> {
    val root = browse("FEmusic_moods_and_genres", countryCode = countryCode)
    val chips = root.objects().mapNotNull { obj ->
      obj["musicNavigationButtonRenderer"]?.jsonObjectOrNull()?.let(::moodCategory)
    }.toList()
    if (chips.isNotEmpty()) return chips.distinctBy { it.title }
    return root.objects().mapNotNull { obj ->
      obj["musicTwoRowItemRenderer"]?.jsonObjectOrNull()?.let(::moodCategory)
    }.toList().distinctBy { it.title }
  }

  private fun moodCategory(renderer: JsonObject): MoodCategory? {
    val endpoint = renderer.findValue("browseEndpoint")?.jsonObjectOrNull() ?: return null
    val browseId = endpoint["browseId"]?.jsonPrimitive?.contentOrNull
      ?.takeIf { it.startsWith("FEmusic_moods") } ?: return null
    val params = endpoint["params"]?.jsonPrimitive?.contentOrNull
    val title = renderer["buttonText"]?.let(::renderedText)?.takeIf(String::isNotBlank)
      ?: renderer["title"]?.let(::renderedText)?.takeIf(String::isNotBlank) ?: return null
    return MoodCategory(title, browseId, params)
  }

  /** Playlist shelves inside a mood/genre category page (e.g. Chill → "Chill hits", "Lofi beats"). */
  fun moodPlaylists(category: MoodCategory, countryCode: String = "US"): List<MusicShelf> =
    parseShelves(browse(category.browseId, category.params, countryCode))

  /** Full track list of a YouTube Music playlist or album page (browseId like VL…/OLAK5uy…). */
  fun playlistTracks(browseId: String, countryCode: String = "US"): List<Track> =
    parseTracks(browse(browseId, countryCode = countryCode)).distinctBy { it.id }

  /** Plain lyrics shown on the video's Lyrics tab, if YouTube Music offers one for this track. */
  fun lyricsFor(videoId: String): String? {
    val next = post("next", buildJsonObject {
      put("context", context("WEB_REMIX", "1.20260707.12.00"))
      put("videoId", videoId)
    })
    val lyricsBrowseId = next.objects()
      .mapNotNull { it["tabRenderer"]?.jsonObjectOrNull() }
      .firstOrNull { it.findValue("pageType")?.jsonPrimitive?.contentOrNull?.contains("LYRICS") == true }
      ?.let { it.findValue("browseId")?.jsonPrimitive?.contentOrNull }
      ?: next.objects().mapNotNull { it["browseId"]?.jsonPrimitive?.contentOrNull }
        .firstOrNull { it.startsWith("MPLYt") }
      ?: return null
    return browse(lyricsBrowseId).objects()
      .mapNotNull { it["musicDescriptionShelfRenderer"]?.jsonObjectOrNull() }
      .mapNotNull { it["description"]?.let(::renderedText)?.takeIf(String::isNotBlank) }
      .firstOrNull()
  }

  /** Synced, timestamped captions attached to the video, returned as LRC text (the transcript
   * provider Echo Music uses — many YouTube Music tracks carry lyric captions). */
  fun transcriptFor(videoId: String): String? {
    val params = java.util.Base64.getEncoder().encodeToString(
      byteArrayOf(0x0A, videoId.length.toByte()) + videoId.toByteArray(Charsets.UTF_8)
    )
    val body = buildJsonObject {
      put("context", context("WEB", "2.20250710.01.00"))
      put("params", params)
    }
    val root = runCatching {
      post("get_transcript", body, clientName = "1", clientVersion = "2.20250710.01.00")
    }.getOrNull() ?: return null

    val lines = root.objects()
      .mapNotNull { it["transcriptCueGroupRenderer"]?.jsonObjectOrNull() }
      .mapNotNull { group ->
        val cue = group["cues"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
          ?.get("transcriptCueRenderer")?.jsonObjectOrNull() ?: return@mapNotNull null
        val ms = cue["startOffsetMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
        val text = cue["cue"]?.jsonObjectOrNull()?.get("simpleText")?.jsonPrimitive?.contentOrNull
          ?.trim('♪')?.trim().orEmpty()
        if (text.isBlank()) null else ms to text
      }
      .sortedBy { it.first }
    if (lines.none()) return null
    return lines.joinToString("\n") { (ms, text) ->
      "[%02d:%02d.%02d]%s".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, text)
    }
  }

  private fun parsePlaylists(element: JsonElement): List<MusicPlaylist> = element.objects().mapNotNull { item ->
    val renderer = item["musicTwoRowItemRenderer"]?.jsonObjectOrNull() ?: return@mapNotNull null
    val endpoint = renderer["navigationEndpoint"]?.findValue("browseEndpoint")?.jsonObjectOrNull() ?: return@mapNotNull null
    val browseId = endpoint["browseId"]?.jsonPrimitive?.contentOrNull?.takeIf(::isPlaylistBrowseId) ?: return@mapNotNull null
    val title = renderer["title"]?.let(::renderedText)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    val subtitle = renderer["subtitle"]?.let(::renderedText).orEmpty()
    val thumbnail = (renderer["thumbnail"] ?: renderer["thumbnailRenderer"])?.findValue("thumbnails")?.jsonArrayOrNull()
      ?.mapNotNull { it.jsonObjectOrNull()?.get("url")?.jsonPrimitive?.contentOrNull }?.lastOrNull()
    MusicPlaylist(browseId, title, subtitle, thumbnail)
  }.toList()

  private fun isPlaylistBrowseId(id: String) = id.startsWith("VL") || id.startsWith("OLAK5uy")

  /** Resolve a direct AAC/MP4 URL. Try the same media profiles used by Echo's Android resolver. */
  fun resolveAudio(videoId: String): String {
    val profiles = listOf(
      PlayerProfile("VISIONOS", "0.1", "101", userAgent),
      PlayerProfile("ANDROID_VR", "1.65.10", "28", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", "Android", "12L", "Oculus", "Quest 3", 32),
    )
    var lastError: Exception? = null
    for (profile in profiles) {
      try { return resolveAudio(videoId, profile) }
      catch (error: Exception) { lastError = error }
    }
    throw IllegalStateException(lastError?.message ?: "YouTube did not provide a playable stream")
  }

  private data class PlayerProfile(val name: String, val version: String, val id: String, val agent: String,
    val os: String? = null, val osVersion: String? = null, val make: String? = null, val model: String? = null, val sdk: Int? = null)

  private fun resolveAudio(videoId: String, profile: PlayerProfile): String {
    val visitor = ensureVisitorData()
    val body = buildJsonObject {
      put("context", buildJsonObject {
        put("client", buildJsonObject {
          put("clientName", profile.name); put("clientVersion", profile.version)
          put("visitorData", visitor); put("gl", "US"); put("hl", "en")
          profile.os?.let { put("osName", it) }; profile.osVersion?.let { put("osVersion", it) }
          profile.make?.let { put("deviceMake", it) }; profile.model?.let { put("deviceModel", it) }
          profile.sdk?.let { put("androidSdkVersion", it) }
        })
      })
      put("videoId", videoId)
    }
    val request = HttpRequest.newBuilder(URI("https://music.youtube.com/youtubei/v1/player?prettyPrint=false"))
      .timeout(Duration.ofSeconds(30)).header("User-Agent", profile.agent).header("Accept", "application/json")
      .header("Content-Type", "application/json").header("X-Origin", "https://music.youtube.com")
      .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
      .header("X-Goog-Api-Format-Version", "1").header("X-YouTube-Client-Name", profile.id)
      .header("X-YouTube-Client-Version", profile.version).header("X-Goog-Visitor-Id", visitor)
      .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) throw IllegalStateException("YouTube player returned HTTP ${response.statusCode()}")
    val root = json.parseToJsonElement(response.body()).jsonObject
    val status = root["playabilityStatus"]?.jsonObjectOrNull()?.get("status")?.jsonPrimitive?.contentOrNull
    if (status != null && status != "OK") {
      val reason = root["playabilityStatus"]?.jsonObjectOrNull()?.get("reason")?.jsonPrimitive?.contentOrNull
      throw IllegalStateException(reason ?: "This track is unavailable for online playback ($status)")
    }
    val formats = root["streamingData"]?.jsonObjectOrNull()?.get("adaptiveFormats")?.jsonArrayOrNull().orEmpty()
    val candidates = formats.mapNotNull { it.jsonObjectOrNull() }
      .filter { it["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("audio/mp4") == true }
      .filter { it["url"]?.jsonPrimitive?.contentOrNull?.startsWith("https://") == true }
      .sortedByDescending { it["bitrate"]?.jsonPrimitive?.longOrNull ?: 0L }
    return candidates.firstOrNull()?.get("url")?.jsonPrimitive?.content
      ?: throw IllegalStateException("YouTube did not provide a directly playable audio stream for this track")
  }

  /** Download a short-lived signed stream URL to a reusable cache file. YouTube Music art tracks
   * reject range-less media requests, so fetch in bounded ranges as Echo's Android player does. */
  fun downloadAudio(videoId: String): File {
    val key = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val target = File(onlineCacheDirectory, "$key.m4a")
    if (target.isFile && target.length() > 0 && System.currentTimeMillis() - target.lastModified() < Duration.ofHours(18).toMillis()) return target
    return cacheAudio(videoId, resolveAudio(videoId))
  }

  internal fun cacheAudio(videoId: String, sourceUrl: String): File {
    onlineCacheDirectory.mkdirs()
    val key = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val target = File(onlineCacheDirectory, "$key.m4a")
    val temporary = File(onlineCacheDirectory, "$key-${UUID.randomUUID()}.part")
    try {
      val first = fetchRange(sourceUrl, 0, RANGE_SIZE - 1)
      if (first.bytes.isEmpty()) throw IllegalStateException("YouTube returned an empty audio stream")
      val total = first.total ?: first.bytes.size.toLong()
      java.io.RandomAccessFile(temporary, "rw").use { raf ->
        raf.write(first.bytes)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
          val tasks = mutableListOf<java.util.concurrent.Future<*>>()
          var next = first.bytes.size.toLong()
          while (next < total) {
            val start = next
            val end = minOf(start + RANGE_SIZE, total) - 1
            tasks += pool.submit<Unit> {
              val buf = ByteArray((end - start + 1).toInt())
              var filled = 0
              while (filled < buf.size) {
                val chunk = fetchRange(sourceUrl, start + filled, end)
                if (chunk.bytes.isEmpty()) throw IllegalStateException("YouTube returned an empty audio range")
                System.arraycopy(chunk.bytes, 0, buf, filled, chunk.bytes.size)
                filled += chunk.bytes.size
              }
              synchronized(raf) { raf.seek(start); raf.write(buf) }
            }
            next = end + 1
          }
          tasks.forEach { it.get() }
        } finally { pool.shutdownNow() }
      }
    } catch (error: Exception) {
      temporary.delete()
      throw if (error is IllegalStateException) error else IllegalStateException("Could not download online audio: ${error.message ?: "network error"}", error)
    }
    if (!temporary.isFile || temporary.length() == 0L) { temporary.delete(); throw IllegalStateException("YouTube returned an empty audio stream") }
    try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: Exception) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    return target
  }

  private data class RangeChunk(val bytes: ByteArray, val total: Long?)

  private fun fetchRange(sourceUrl: String, start: Long, end: Long): RangeChunk {
    val request = HttpRequest.newBuilder(URI(sourceUrl)).timeout(Duration.ofMinutes(2))
      .header("User-Agent", userAgent).header("Accept", "audio/mp4,application/octet-stream;q=0.9,*/*;q=0.8")
      .header("Referer", "https://music.youtube.com/").header("Range", "bytes=$start-$end").GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() == 200) return RangeChunk(response.body(), response.body().size.toLong())
    if (response.statusCode() != 206) throw IllegalStateException("YouTube audio CDN returned HTTP ${response.statusCode()} while requesting bytes $start-$end")
    val contentRange = response.headers().firstValue("Content-Range").orElse("")
    val match = Regex("bytes \\d+-\\d+/(\\d+)").matchEntire(contentRange)
      ?: throw IllegalStateException("YouTube audio CDN returned an invalid range response")
    return RangeChunk(response.body(), match.groupValues[1].toLong())
  }

  private fun browse(browseId: String, params: String? = null, countryCode: String = "US"): JsonObject {
    val body = buildJsonObject {
      put("context", context("WEB_REMIX", "1.20260707.12.00", countryCode))
      put("browseId", browseId)
      params?.takeIf(String::isNotBlank)?.let { put("params", it) }
    }
    return post("browse", body)
  }

  private fun post(path: String, body: JsonObject, clientName: String = "67", clientVersion: String = "1.20260707.12.00"): JsonObject {
    val visitor = ensureVisitorData()
    val requestBody = body.withVisitorData(visitor)
    val request = HttpRequest.newBuilder(URI("https://music.youtube.com/youtubei/v1/$path?prettyPrint=false"))
      .timeout(Duration.ofSeconds(30))
      .header("User-Agent", if (path == "player") "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip" else userAgent)
      .header("Accept", "application/json")
      .header("Content-Type", "application/json")
      .header("Origin", "https://music.youtube.com")
      .header("Referer", "https://music.youtube.com/")
      .header("X-YouTube-Client-Name", if (path == "player") "28" else clientName)
      .header("X-YouTube-Client-Version", if (path == "player") "1.65.10" else clientVersion)
      .header("X-Goog-Visitor-Id", visitor)
      .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
      .build()
    val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
      catch (e: Exception) { throw IllegalStateException("Could not reach YouTube Music: ${e.message ?: "network error"}", e) }
    if (response.statusCode() !in 200..299) throw IllegalStateException("YouTube Music returned HTTP ${response.statusCode()}")
    return try { json.parseToJsonElement(response.body()).jsonObject }
      catch (e: Exception) { throw IllegalStateException("YouTube Music returned an unreadable response", e) }
  }

  @Synchronized private fun ensureVisitorData(): String {
    guestVisitorData?.let { return it }
    val request = HttpRequest.newBuilder(URI("https://music.youtube.com/sw.js_data"))
      .timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent).header("Accept", "*/*").GET().build()
    val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
      catch (e: Exception) { throw IllegalStateException("Could not initialize a guest YouTube Music session: ${e.message ?: "network error"}", e) }
    if (response.statusCode() !in 200..299) throw IllegalStateException("YouTube Music guest session returned HTTP ${response.statusCode()}")
    val payload = response.body().removePrefix(")]}'").trimStart()
    val data = try { json.parseToJsonElement(payload) }
      catch (e: Exception) { throw IllegalStateException("YouTube Music guest session data could not be parsed", e) }
    guestVisitorData = data.primitives().firstOrNull { it.matches(Regex("^Cg[ts].*")) }
      ?: throw IllegalStateException("YouTube Music did not issue a guest visitor token")
    return guestVisitorData!!
  }

  private fun JsonObject.withVisitorData(visitor: String): JsonObject {
    val contextObject = this["context"]?.jsonObjectOrNull() ?: return this
    val clientObject = contextObject["client"]?.jsonObjectOrNull() ?: return this
    val updatedContext = contextObject.toMutableMap().apply { put("client", clientObject.toMutableMap().apply { put("visitorData", JsonPrimitive(visitor)) }.let(::JsonObject)) }
    return toMutableMap().apply { put("context", JsonObject(updatedContext)) }.let(::JsonObject)
  }

  private fun context(name: String, version: String, countryCode: String = "US") = buildJsonObject {
    put("client", buildJsonObject {
      put("clientName", name); put("clientVersion", version); put("hl", "en"); put("gl", countryCode.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US")
    })
  }

  private fun parseTracks(element: JsonElement): List<Track> = element.objects().mapNotNull { item ->
    val renderer = item["musicResponsiveListItemRenderer"]?.jsonObjectOrNull()
      ?: item["musicTwoRowItemRenderer"]?.jsonObjectOrNull()
      ?: item["musicMultiRowListItemRenderer"]?.jsonObjectOrNull()
      ?: return@mapNotNull null
    val videoId = renderer.findValue("videoId")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
    val columns = renderer["flexColumns"]?.jsonArrayOrNull().orEmpty()
    val title = columns.firstOrNull()?.let(::renderedText)?.takeIf(String::isNotBlank)
      ?: renderer["title"]?.let(::renderedText)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    val subtitle = columns.drop(1).firstOrNull()?.let(::renderedText).orEmpty()
    // Subtitles look like "Song • Artist • Album • 4:45" — drop the leading item-type
    // label and any trailing duration segment before mapping artist/album.
    val segments = subtitle.split(" • ", " · ").map(String::trim).filter(String::isNotBlank)
    val meta = segments.dropWhile { it.lowercase() in itemTypeLabels }
      .filter { !it.matches(Regex("\\d{1,2}:\\d{2}")) && !it.matches(engagementRegex) }
    val image = renderer.findValue("thumbnails")?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull()?.get("url")?.jsonPrimitive?.contentOrNull }?.lastOrNull()
    val duration = renderer.objects().flatMap { it.values.asSequence() }.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
      .firstOrNull { it.matches(Regex("\\d{1,2}:\\d{2}")) }?.let(::parseDuration) ?: 0L
    Track(id = "yt:$videoId", path = "https://www.youtube.com/watch?v=$videoId", title = title,
      artist = meta.firstOrNull() ?: "YouTube Music", album = meta.drop(1).firstOrNull() ?: "",
      durationMs = duration, videoId = videoId, remoteArtworkUrl = image)
  }.distinctBy { it.id }.toList()

  private val engagementRegex = Regex("(?i)\\d[\\d.,]*\\s*[kmb]?\\s*(views?|plays?|likes?|subscribers|followers)")

  private val itemTypeLabels = setOf(
    "song", "video", "album", "single", "ep", "artist", "playlist", "profile",
    "podcast", "episode", "mix", "station", "audio", "music"
  )

  private fun renderedText(element: JsonElement): String = when (element) {
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonArray -> element.joinToString("") { renderedText(it) }
    is JsonObject -> {
      val runs = element["runs"]?.jsonArrayOrNull()
      when {
        runs != null -> runs.joinToString("") { it.jsonObjectOrNull()?.get("text")?.let(::renderedText).orEmpty() }
        element["text"] != null -> renderedText(element.getValue("text"))
        else -> element.values.asSequence().map(::renderedText).firstOrNull(String::isNotBlank).orEmpty()
      }
    }
  }

  private fun JsonElement.objects(): Sequence<JsonObject> = sequence {
    when (this@objects) {
      is JsonObject -> { yield(this@objects); values.forEach { yieldAll(it.objects()) } }
      is JsonArray -> forEach { yieldAll(it.objects()) }
      else -> Unit
    }
  }

  private fun JsonElement.primitives(): Sequence<String> = sequence {
    when (this@primitives) {
      is JsonObject -> values.forEach { yieldAll(it.primitives()) }
      is JsonArray -> forEach { yieldAll(it.primitives()) }
      is JsonPrimitive -> contentOrNull?.let { yield(it) }
    }
  }

  private fun JsonElement.findValue(key: String): JsonElement? = objects().firstNotNullOfOrNull { it[key] }
  private fun JsonElement.jsonObjectOrNull() = this as? JsonObject
  private fun JsonElement.jsonArrayOrNull() = this as? JsonArray
  private fun JsonElement.jsonPrimitiveOrNull() = this as? JsonPrimitive
  private fun parseDuration(text: String): Long = text.split(':').fold(0L) { sum, part -> sum * 60 + (part.toLongOrNull() ?: 0) } * 1000L

  companion object {
    private const val RANGE_SIZE = 1024 * 1024L
    private fun defaultOnlineCacheDirectory(): File =
      File(solo.music.core.appDataDirectory(), "online-cache")
  }
}
