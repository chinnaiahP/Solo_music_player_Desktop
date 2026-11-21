package solo.music.app

import kotlinx.serialization.json.*
import solo.music.core.Track
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsDocument(val lines: List<LyricLine>, val provider: String, val synced: Boolean)

private fun JsonElement?.jsonObjectOrNull() = this as? JsonObject
private fun JsonElement?.jsonArrayOrNull() = this as? JsonArray
private fun JsonElement?.jsonBooleanOrNull() = (this as? JsonPrimitive)?.contentOrNull == "true"

object LrcLyrics {
  private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
  private val metadataTag = Regex("^\\[(ti|ar|al|au|lr|length|by|re|ve):.*]$", RegexOption.IGNORE_CASE)
  private val offsetTag = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]$", RegexOption.IGNORE_CASE)
  private val wordStamp = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")

  fun readFor(audioPath: String): List<LyricLine> {
    if (audioPath.isBlank()) return emptyList()
    val audio = File(audioPath)
    val parent = audio.parentFile ?: return emptyList()
    val candidates = listOf(
      File(parent, "${audio.nameWithoutExtension}.lrc"),
      File(parent, "${audio.nameWithoutExtension}.LRC")
    )
    val file = candidates.firstOrNull { it.isFile } ?: return emptyList()
    return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
  }

  fun parse(text: String): List<LyricLine> {
    var offsetMs = 0L
    val rawEntries = mutableListOf<LyricLine>()

    text.lineSequence().forEach { raw ->
      val line = raw.trim()
      if (line.isEmpty()) return@forEach

      offsetTag.matchEntire(line)?.let { match ->
        offsetMs = match.groupValues[1].toLongOrNull() ?: 0L
        return@forEach
      }
      if (metadataTag.matches(line)) return@forEach

      val stamps = timestamp.findAll(line).toList()
      // Strip inline word-timing tags (<mm:ss.xx> enhanced-LRC) so they don't show in the text
      val lyricText = wordStamp.replace(timestamp.replace(line, ""), "").trim()
      if (lyricText.isBlank()) return@forEach

      if (stamps.isEmpty()) {
        rawEntries += LyricLine(-1L, lyricText)
      } else {
        val firstStampEnd = stamps.last().range.last + 1
        val content = wordStamp.replace(line.substring(firstStampEnd), "").trim().ifBlank { lyricText }
        stamps.forEach { match ->
          val mins = match.groupValues[1].toLongOrNull() ?: 0L
          val secs = match.groupValues[2].toLongOrNull() ?: 0L
          val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
          val baseTime = (mins * 60 + secs) * 1000 + fraction
          rawEntries += LyricLine(baseTime, content)
        }
      }
    }

    return rawEntries
      .map { entry ->
        if (entry.timeMs >= 0L) entry.copy(timeMs = (entry.timeMs + offsetMs).coerceAtLeast(0L))
        else entry
      }
      .sortedBy { if (it.timeMs < 0L) Long.MAX_VALUE else it.timeMs }
  }

  fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int =
    lines.indices.lastOrNull { lines[it].timeMs >= 0L && lines[it].timeMs <= positionMs } ?: -1

  /** Returns 0f..1f progress across the currently active synced lyric line for karaoke fill. */
  fun lineProgress(lines: List<LyricLine>, activeIdx: Int, positionMs: Long, trackDurationMs: Long): Float {
    if (activeIdx !in lines.indices) return 0f
    val start = lines[activeIdx].timeMs
    if (start < 0L) return 0f
    val nextStart = lines.drop(activeIdx + 1).firstOrNull { it.timeMs > start }?.timeMs
      ?: trackDurationMs.takeIf { it > start }
      ?: (start + 4500L)
    val span = (nextStart - start).coerceAtLeast(400L)
    return ((positionMs - start).toFloat() / span.toFloat()).coerceIn(0f, 1f)
  }
}

/** Local sidecar first, then LRCLIB → YouTube Music lyrics tab → lyrics.ovh, with memory + disk caching. */
class LyricsService(
  private val cacheDirectory: File = defaultCacheDirectory(),
  private val baseUrl: String = "https://lrclib.net",
  private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build(),
  private val translationBaseUrl: String? = null,
  private val ovhBaseUrl: String = "https://api.lyrics.ovh",
  private val youTubeMusic: solo.music.online.YouTubeMusicService? = null,
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val memoryCache = ConcurrentHashMap<String, LyricsDocument>()
  private val englishMemoryCache = ConcurrentHashMap<String, List<LyricLine>>()

  fun get(track: Track): LyricsDocument? {
    val localLines = if (!track.isOnline) LrcLyrics.readFor(track.path) else emptyList()
    if (localLines.isNotEmpty()) {
      return LyricsDocument(localLines, "Local .lrc", localLines.any { it.timeMs >= 0L })
    }

    val key = cacheKey(track)
    memoryCache[key]?.let { return it }

    val cache = File(cacheDirectory, "$key.json")
    val missMarker = File(cacheDirectory, "$key.miss")

    if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < Duration.ofDays(30).toMillis()) {
      runCatching {
        val stored = json.parseToJsonElement(cache.readText()).jsonObject
        parseRecord(stored, stored["provider"]?.jsonPrimitive?.contentOrNull ?: "LRCLIB")
      }
        .getOrNull()
        ?.let {
          memoryCache[key] = it
          return it
        }
    }

    if (missMarker.isFile && System.currentTimeMillis() - missMarker.lastModified() < Duration.ofHours(12).toMillis()) {
      return null
    }

    val found = simpMusicResult(track)
      ?: unisonResult(track)
      ?: youLyPlusResult(track)
      ?: betterLyricsResult(track)
      ?: lrclibResult(track)
      ?: kugouResult(track)
      ?: transcriptResult(track)
      ?: youTubeResult(track)
      ?: ovhResult(track)
    if (found == null) {
      runCatching { cacheDirectory.mkdirs(); missMarker.writeText("miss") }
      return null
    }

    val (record, document) = found
    memoryCache[key] = document
    runCatching {
      cacheDirectory.mkdirs()
      if (missMarker.exists()) missMarker.delete()
      cache.writeText(record.toString())
    }
    return document
  }

  fun translateToEnglish(track: Track, nativeLines: List<LyricLine>, apiKey: String): List<LyricLine> {
    require(apiKey.isNotBlank()) { "Add a DeepL API key in Settings to enable English lyrics." }
    require(nativeLines.isNotEmpty()) { "Native lyrics are not available to translate." }

    val key = cacheKey(track)
    englishMemoryCache[key]?.takeIf { it.size == nativeLines.size }?.let { return it }

    val cache = File(cacheDirectory, "$key.en.json")
    if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < Duration.ofDays(90).toMillis()) {
      runCatching { json.parseToJsonElement(cache.readText()).jsonArray.map { it.jsonPrimitive.content } }
        .getOrNull()?.takeIf { it.size == nativeLines.size }?.let { texts ->
          val mapped = nativeLines.mapIndexed { index, line -> line.copy(text = texts[index]) }
          englishMemoryCache[key] = mapped
          return mapped
        }
    }

    val endpoint = translationBaseUrl ?: if (apiKey.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
    val translated = nativeLines.chunked(50).flatMap { batch ->
      val payload = buildJsonObject {
        put("text", JsonArray(batch.map { JsonPrimitive(it.text) }))
        put("target_lang", "EN-US")
      }
      val request = HttpRequest.newBuilder(URI("$endpoint/v2/translate")).timeout(Duration.ofSeconds(45))
        .header("Authorization", "DeepL-Auth-Key $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() !in 200..299) {
        throw IllegalStateException("DeepL translation failed (HTTP ${response.statusCode()}). Check your key and quota.")
      }
      val result = json.parseToJsonElement(response.body()).jsonObject["translations"]?.jsonArray
        ?: throw IllegalStateException("DeepL returned no translated text")
      result.map { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
    }
    if (translated.size != nativeLines.size || translated.any(String::isBlank)) {
      throw IllegalStateException("DeepL returned an incomplete translation")
    }
    runCatching { cacheDirectory.mkdirs(); cache.writeText(JsonArray(translated.map(::JsonPrimitive)).toString()) }
    val mapped = nativeLines.mapIndexed { index, line -> line.copy(text = translated[index]) }
    englishMemoryCache[key] = mapped
    return mapped
  }

  private fun lrclibResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val record = find(track)?.withProvider("LRCLIB") ?: return null
    val document = parseRecord(record, "LRCLIB") ?: return null
    return record to document
  }

  /** Exact-match plain lyrics from the track's own YouTube Music Lyrics tab. */
  private fun youTubeResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val videoId = track.videoId ?: return null
    val ytm = youTubeMusic ?: return null
    val text = runCatching { ytm.lyricsFor(videoId) }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
    val lines = LrcLyrics.parse(text).filter { it.text.isNotBlank() }
    if (lines.isEmpty()) return null
    val record = buildJsonObject {
      put("provider", "YouTube Music")
      put("plainLyrics", text)
    }
    return record to LyricsDocument(lines, "YouTube Music", false)
  }

  /** Last-resort plain lyrics, only when we have a confident artist + title to look up. */
  private fun ovhResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val title = cleanTitle(track.title.trim()).takeIf(String::isNotBlank) ?: return null
    val artist = track.artist.trim().takeIf {
      it.isNotBlank() && !it.equals("Unknown artist", true) && !it.equals("YouTube Music", true)
    } ?: return null
    val response = request("$ovhBaseUrl/v1/${pathEncode(artist)}/${pathEncode(title)}") as? JsonObject ?: return null
    val text = response["lyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
    val lines = LrcLyrics.parse(text).filter { it.text.isNotBlank() }
    if (lines.isEmpty()) return null
    val record = buildJsonObject {
      put("provider", "lyrics.ovh")
      put("plainLyrics", text)
    }
    return record to LyricsDocument(lines, "lyrics.ovh", false)
  }

  private fun JsonObject.withProvider(provider: String) =
    JsonObject(toMutableMap().apply { put("provider", JsonPrimitive(provider)) })

  /** Parse LRC or plain text into a cacheable record + document, or null when empty. */
  private fun documentFor(provider: String, text: String): Pair<JsonObject, LyricsDocument>? {
    val lines = LrcLyrics.parse(text).filter { it.text.isNotBlank() }
    if (lines.isEmpty()) return null
    val synced = lines.any { it.timeMs >= 0L }
    val record = buildJsonObject {
      put("provider", provider)
      put(if (synced) "syncedLyrics" else "plainLyrics", text)
    }
    return record to LyricsDocument(lines, provider, synced)
  }

  /** SimpMusic community lyrics — exact videoId match, prefers synced. */
  private fun simpMusicResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val videoId = track.videoId ?: return null
    val root = request("https://api-lyrics.simpmusic.org/v1/$videoId") as? JsonObject ?: return null
    if (root["type"]?.jsonPrimitive?.contentOrNull != "success") return null
    val entries = root["data"]?.jsonArrayOrNull()?.mapNotNull { it as? JsonObject }.orEmpty()
    val seconds = track.durationMs / 1000
    val valid = if (seconds > 0) entries.filter {
      abs((it["durationSeconds"]?.jsonPrimitive?.longOrNull ?: 0L) - seconds) <= 10
    } else entries
    val best = (valid.takeIf { it.isNotEmpty() } ?: entries).firstOrNull() ?: return null
    val text = best["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: best["plainLyric"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: best["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: return null
    return documentFor("SimpMusic", text)
  }

  /** Unison lyrics — videoId first, then metadata; content may be LRC or TTML. */
  private fun unisonResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val text = track.videoId?.let { videoId ->
      unisonFetch("v=${encode(videoId)}")
    } ?: run {
      val title = cleanTitle(track.title.trim()).takeIf(String::isNotBlank) ?: return null
      val artist = track.artist.trim().takeIf {
        it.isNotBlank() && !it.equals("Unknown artist", true) && !it.equals("YouTube Music", true)
      } ?: return null
      val params = buildString {
        append("song=${encode(title)}&artist=${encode(artist)}")
        if (track.album.isNotBlank() && !track.album.equals("Unknown album", true)) {
          append("&album=${encode(track.album.trim())}")
        }
        if (track.durationMs > 0) append("&duration=${track.durationMs / 1000}")
      }
      unisonFetch(params)
    } ?: return null
    val lrc = if (text.trimStart().startsWith("<tt", ignoreCase = true)) ttmlToLrc(text) else text
    return documentFor("Unison", lrc ?: return null)
  }

  private fun unisonFetch(params: String): String? {
    val root = request("https://unison.boidu.dev/lyrics?$params") as? JsonObject ?: return null
    if (root["success"]?.jsonPrimitive?.jsonBooleanOrNull() != true) return null
    return root["data"]?.jsonObjectOrNull()?.get("lyrics")?.jsonPrimitive?.contentOrNull
      ?.takeIf(String::isNotBlank)
  }

  /** YouLyPlus / KPoe — synced lyrics across several community mirrors (Echo's top provider). */
  private fun youLyPlusResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val title = track.title.trim().takeIf(String::isNotBlank) ?: return null
    val artist = track.artist.trim().takeIf {
      it.isNotBlank() && !it.equals("Unknown artist", true) && !it.equals("YouTube Music", true)
    } ?: return null
    val params = buildString {
      append("title=${encode(title)}&artist=${encode(artist)}")
      if (track.durationMs > 0) append("&duration=${track.durationMs / 1000}")
      if (track.album.isNotBlank() && !track.album.equals("Unknown album", true)) {
        append("&album=${encode(track.album.trim())}")
      }
      track.videoId?.let { append("&id=${encode(it)}") }
    }
    for (server in youLyPlusServers) {
      val root = request("$server/v2/lyrics/get?$params") as? JsonObject ?: continue
      val text = root["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: root["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: kpoeToLrc(root["lyrics"]?.jsonArrayOrNull())
        ?: continue
      documentFor("YouLyPlus", text)?.let { return it }
    }
    return null
  }

  /** KPoe timed-line format ([{text, time(ms)}]) → LRC text. */
  private fun kpoeToLrc(items: JsonArray?): String? {
    val lines = items.orEmpty().mapNotNull { item ->
      val obj = item as? JsonObject ?: return@mapNotNull null
      val ms = obj["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
      val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      if (text.isBlank()) null else "[%02d:%02d.%02d]%s".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, text)
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  /** BetterLyrics (boidu) — synced TTML matched on title/artist/duration/album. */
  private fun betterLyricsResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val title = track.title.trim().takeIf(String::isNotBlank) ?: return null
    val artist = track.artist.trim().takeIf {
      it.isNotBlank() && !it.equals("Unknown artist", true) && !it.equals("YouTube Music", true)
    } ?: return null
    val params = buildString {
      append("s=${encode(title)}&a=${encode(artist)}")
      if (track.durationMs > 0) append("&d=${track.durationMs / 1000}")
      if (track.album.isNotBlank() && !track.album.equals("Unknown album", true)) {
        append("&al=${encode(track.album.trim())}")
      }
    }
    val root = request("https://lyrics-api.boidu.dev/getLyrics?$params") as? JsonObject ?: return null
    val ttml = root["ttml"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
    return documentFor("BetterLyrics", ttmlToLrc(ttml) ?: return null)
  }

  /** KuGou lyrics library — synced LRC matched by title/artist with duration check. */
  private fun kugouResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val title = cleanTitle(track.title.trim())
      .replace(Regex("[(（\\[「『《〈<].*[]）\\]」』》〉>]"), "")
      .trim().takeIf(String::isNotBlank) ?: return null
    val artist = track.artist.trim()
      .replace(Regex("[,，]| & | feat\\.? | ft\\.? ", RegexOption.IGNORE_CASE), "、")
      .replace(Regex("\\(.*\\)|（.*）"), "")
      .trim().takeIf {
        it.isNotBlank() && !it.equals("Unknown artist", true) && !it.equals("YouTube Music", true)
      } ?: return null
    val seconds = track.durationMs / 1000
    val keyword = encode("$title - $artist" + if (track.album.isNotBlank() && !track.album.equals("Unknown album", true)) " ${track.album.trim()}" else "")

    val songs = (request(
      "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$keyword"
    ) as? JsonObject)
      ?.get("data")?.jsonObjectOrNull()?.get("info")?.jsonArrayOrNull()
      ?.mapNotNull { it as? JsonObject }.orEmpty()

    var candidate = songs.firstOrNull { song ->
      val duration = song["duration"]?.jsonPrimitive?.longOrNull ?: 0L
      seconds <= 0 || abs(duration - seconds) <= 8
    }?.let { song ->
      val hash = song["hash"]?.jsonPrimitive?.contentOrNull ?: return@let null
      (request("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=$hash") as? JsonObject)
        ?.get("candidates")?.jsonArrayOrNull()?.firstOrNull { it is JsonObject } as? JsonObject
    }

    if (candidate == null) {
      candidate = (request(
        "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$keyword" +
          if (seconds > 0) "&duration=${seconds * 1000}" else ""
      ) as? JsonObject)
        ?.get("candidates")?.jsonArrayOrNull()?.firstOrNull { it is JsonObject } as? JsonObject
    }
    val id = candidate?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
    val accesskey = candidate["accesskey"]?.jsonPrimitive?.contentOrNull ?: return null

    val download = request(
      "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=$id&accesskey=${encode(accesskey)}"
    ) as? JsonObject ?: return null
    val content = download["content"]?.jsonPrimitive?.contentOrNull ?: return null
    val lrc = runCatching { String(java.util.Base64.getDecoder().decode(content), Charsets.UTF_8) }.getOrNull() ?: return null
    return documentFor("KuGou", lrc)
  }

  /** Synced captions attached to the YouTube video (transcript), when present. */
  private fun transcriptResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val videoId = track.videoId ?: return null
    val text = runCatching { youTubeMusic?.transcriptFor(videoId) }.getOrNull()
      ?.takeIf(String::isNotBlank) ?: return null
    return documentFor("YouTube Captions", text)
  }

  private val youLyPlusServers = listOf(
    "https://lyricsplus.prjktla.my.id",
    "https://lyricsplus.atomix.one",
    "https://lyricsplus.binimum.org",
    "https://lyricsplus.prjktla.workers.dev",
    "https://lyricsplus-seven.vercel.app",
    "https://lyrics-plus-backend.vercel.app",
  )

  private val ttmlLineRegex = Regex("<p\\b[^>]*?\\bbegin=\"([^\"]+)\"[^>]*?>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

  /** Convert Apple-style TTML lyrics to LRC text. */
  private fun ttmlToLrc(ttml: String): String? {
    val lines = ttmlLineRegex.findAll(ttml).mapNotNull { match ->
      val ms = parseTtmlTime(match.groupValues[1]) ?: return@mapNotNull null
      val text = match.groupValues[2]
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .trim()
      if (text.isEmpty()) null else "[%02d:%02d.%02d]%s".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, text)
    }.toList()
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  private fun parseTtmlTime(value: String): Long? {
    val v = value.trim()
    Regex("^(\\d+):(\\d{2})(?::(\\d{2}))?(?:\\.(\\d{1,3}))?$").matchEntire(v)?.let { m ->
      val (a, b, c, f) = m.destructured
      return if (c.isEmpty()) {
        (a.toLong() * 60 + b.toLong()) * 1000 + f.padEnd(3, '0').toLong()
      } else {
        (a.toLong() * 3600 + b.toLong() * 60 + c.toLong()) * 1000 + f.padEnd(3, '0').toLong()
      }
    }
    Regex("^(\\d+(?:\\.\\d+)?)ms$").matchEntire(v)?.let { return it.groupValues[1].toDouble().toLong() }
    Regex("^(\\d+(?:\\.\\d+)?)s$").matchEntire(v)?.let { return (it.groupValues[1].toDouble() * 1000).toLong() }
    return null
  }

  private fun find(track: Track): JsonObject? {
    val title = track.title.trim()
    val artist = track.artist.trim()
    if (title.isBlank()) return null
    val seconds = track.durationMs / 1000
    val cleanedTitle = cleanTitle(title)
    val knownArtist = artist.takeIf {
      it.isNotBlank() && !it.equals("Unknown artist", true) && !it.equals("YouTube Music", true)
    }

    if (knownArtist != null) {
      val params = linkedMapOf("track_name" to cleanedTitle, "artist_name" to knownArtist)
      if (track.album.isNotBlank() && !track.album.equals("Unknown album", true)) params["album_name"] = track.album
      if (seconds in 1..3600) params["duration"] = seconds.toString()
      val exact = request("$baseUrl/api/get?${query(params)}")
      if (exact is JsonObject) parseRecord(exact, "LRCLIB")?.let { return exact }
    }

    if (cleanedTitle.isBlank()) return null
    val searchParams = buildList {
      knownArtist?.let { add(mapOf("track_name" to cleanedTitle, "artist_name" to it)) }
      knownArtist?.let { add(mapOf("q" to "$cleanedTitle $it")) }
      add(mapOf("track_name" to cleanedTitle))
      add(mapOf("q" to cleanedTitle))
    }.distinct()

    val normalizedTitle = normalize(cleanedTitle)
    val normalizedArtist = normalize(knownArtist.orEmpty())

    for (params in searchParams) {
      val results = (request("$baseUrl/api/search?${query(params)}") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
      val match = results
        .mapNotNull { candidate ->
          val parsed = parseRecord(candidate, "LRCLIB") ?: return@mapNotNull null
          val candidateTitle = normalize(candidate["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty())
          if (candidateTitle.isBlank()) return@mapNotNull null
          val titleMatches = candidateTitle == normalizedTitle ||
            candidateTitle.contains(normalizedTitle) ||
            normalizedTitle.contains(candidateTitle)
          if (!titleMatches) return@mapNotNull null

          val candidateArtist = normalize(candidate["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty())
          val artistMatches = normalizedArtist.isNotBlank() &&
            (candidateArtist == normalizedArtist || candidateArtist.contains(normalizedArtist) || normalizedArtist.contains(candidateArtist))

          // Avoid false positives on generic single-word titles if artist doesn't match and duration is far off
          val candDuration = candidate["duration"]?.jsonPrimitive?.longOrNull ?: 0L
          val durationDiff = if (seconds > 0 && candDuration > 0) kotlin.math.abs(candDuration - seconds) else 999L
          if (normalizedArtist.isNotBlank() && !artistMatches && durationDiff > 8L) return@mapNotNull null

          val titleScore = if (candidateTitle == normalizedTitle) 6 else 3
          val artistScore = if (artistMatches) 5 else 0
          val durationScore = when {
            durationDiff <= 2L -> 4
            durationDiff <= 5L -> 2
            else -> 0
          }
          val syncedBonus = if (parsed.synced) 3 else 0
          (titleScore + artistScore + durationScore + syncedBonus) to candidate
        }
        .maxByOrNull { it.first }
        ?.second
      if (match != null) return match
    }
    return null
  }

  private fun cleanTitle(title: String): String = title
    .substringBefore("|").substringBefore("｜")
    .replace(Regex("(?i)\\s*\\((?:from|official|lyric|lyrics|video|audio|full song|music video|feat\\.?|ft\\.?)[^)]*\\)"), " ")
    .replace(Regex("(?i)\\s*\\[(?:from|official|lyric|lyrics|video|audio|full song|music video)[^]]*]"), " ")
    .replace(Regex("(?i)\\b(official\\s+)?(music\\s+)?(video|audio|lyrics?|full\\s+song|4k|hd|lyric\\s+video)\\b"), " ")
    .replace(Regex("\\s+"), " ").trim()

  private fun request(url: String): JsonElement? = runCatching {
    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(12))
      .header("Accept", "application/json")
      .header("User-Agent", "SoloMusicPlayer/1.0 (desktop lyrics lookup)")
      .GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) return null
    json.parseToJsonElement(response.body())
  }.getOrNull()

  private fun parseRecord(record: JsonObject, provider: String): LyricsDocument? {
    val synced = record["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    val plain = record["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    val content = synced ?: plain ?: return null
    val lines = LrcLyrics.parse(content).filter { it.text.isNotBlank() }
    return lines.takeIf(List<LyricLine>::isNotEmpty)?.let {
      LyricsDocument(it, provider, synced != null && it.any { line -> line.timeMs >= 0L })
    }
  }

  private fun query(values: Map<String, String>) = values.entries.joinToString("&") {
    "${encode(it.key)}=${encode(it.value)}"
  }
  private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
  private fun pathEncode(value: String) = encode(value).replace("+", "%20")
  private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
  private fun cacheKey(track: Track): String = MessageDigest.getInstance("SHA-256")
    .digest("${track.title}|${track.artist}|${track.album}|${track.durationMs / 1000}".toByteArray())
    .take(16).joinToString("") { "%02x".format(it) }

  companion object {
    private fun defaultCacheDirectory(): File {
      return File(solo.music.core.appDataDirectory(), "lyrics-cache")
    }
  }
}