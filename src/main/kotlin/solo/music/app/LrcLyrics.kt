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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
  private val agentTag = Regex("\\{(bg|agent:[^}]*)}")

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
      val lyricText = agentTag.replace(wordStamp.replace(timestamp.replace(line, ""), ""), "").trim()
      if (lyricText.isBlank()) return@forEach

      if (stamps.isEmpty()) {
        rawEntries += LyricLine(-1L, lyricText)
      } else {
        val firstStampEnd = stamps.last().range.last + 1
        val content = agentTag.replace(wordStamp.replace(line.substring(firstStampEnd), ""), "").trim().ifBlank { lyricText }
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

/** Local sidecar first, then providers queried in parallel (first synced in priority order wins,
 * else first plain), with memory + disk caching. Provider chain modeled on Echo Music's. */
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
  private val paxsenixBaseUrl: String = "https://lyrics.paxsenix.org",
  private val enabledProviders: Set<String>? = null,
) {
  private data class NamedProvider(val name: String, val run: () -> Pair<JsonObject, LyricsDocument>?)

  private val providerPool = Executors.newFixedThreadPool(10) { runnable ->
    Thread(runnable, "lyrics-provider").apply { isDaemon = true }
  }

  /** Echo's exact default order (LyricsProviderRegistry.getDefaultProviderOrder). */
  private fun providerChain(track: Track): List<NamedProvider> = listOf(
    NamedProvider("youlyplus") { youLyPlusResult(track) },
    NamedProvider("paxsenix") { paxsenixResult(track) },
    NamedProvider("unison") { unisonResult(track) },
    NamedProvider("betterlyrics") { betterLyricsResult(track) },
    NamedProvider("simpmusic") { simpMusicResult(track) },
    NamedProvider("lrclib") { lrclibResult(track) },
    NamedProvider("kugou") { kugouResult(track) },
    NamedProvider("transcript") { transcriptResult(track) },
    NamedProvider("youtube") { youTubeResult(track) },
    NamedProvider("ovh") { ovhResult(track) },
  )
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val memoryCache = ConcurrentHashMap<String, LyricsDocument>()
  private val englishMemoryCache = ConcurrentHashMap<String, List<LyricLine>>()

  fun get(track: Track): LyricsDocument? {
    val localLines = if (!track.isOnline) LrcLyrics.readFor(track.path) else emptyList()
    if (localLines.isNotEmpty()) {
      return applyEstimatedSync(LyricsDocument(localLines, "Local .lrc", localLines.any { it.timeMs >= 0L }), track)
    }

    val key = cacheKey(track)
    memoryCache[key]?.let { return applyEstimatedSync(it, track) }

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
          return applyEstimatedSync(it, track)
        }
    }

    if (missMarker.isFile && System.currentTimeMillis() - missMarker.lastModified() < Duration.ofHours(12).toMillis()) {
      return null
    }

    // Query all providers in parallel (Echo Music's model) and evaluate results as they
    // arrive instead of waiting on each provider in order. A synced hit beats a plain one;
    // between same-kind hits the earlier provider in priority order wins. A synced result
    // can be selected as soon as every earlier provider has resolved without a synced hit.
    val chain = providerChain(track)
    val active = enabledProviders?.let { allowed -> chain.filter { it.name in allowed } } ?: chain
    val outcomes = arrayOfNulls<Int>(active.size) // null=pending 0=failed 1=plain 2=synced
    val results = arrayOfNulls<Pair<JsonObject, LyricsDocument>>(active.size)
    val completion = java.util.concurrent.ExecutorCompletionService<Pair<Int, Pair<JsonObject, LyricsDocument>?>>(providerPool)
    active.forEachIndexed { index, provider ->
      completion.submit(java.util.concurrent.Callable {
        index to runCatching { provider.run() }.getOrNull()
      })
    }

    fun evaluate(): Pair<JsonObject, LyricsDocument>? {
      var firstPlain: Pair<JsonObject, LyricsDocument>? = null
      for (i in active.indices) {
        when (outcomes[i]) {
          null -> return null // an earlier provider is still pending — can't decide yet
          2 -> return results[i]
          1 -> if (firstPlain == null) firstPlain = results[i]
        }
      }
      return firstPlain
    }

    var found: Pair<JsonObject, LyricsDocument>? = null
    var resolved = 0
    val deadline = System.currentTimeMillis() + 20_000
    while (resolved < active.size) {
      val waitMs = deadline - System.currentTimeMillis()
      if (waitMs <= 0) break
      val future = completion.poll(waitMs, TimeUnit.MILLISECONDS) ?: break
      val completed = runCatching { future.get() }.getOrNull() ?: continue
      val index = completed.first
      val result = completed.second
      resolved++
      when {
        result == null || result.second.lines.isEmpty() -> outcomes[index] = 0
        result.second.synced -> { outcomes[index] = 2; results[index] = result }
        else -> { outcomes[index] = 1; results[index] = result }
      }
      evaluate()?.let { found = it; break }
    }
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
    return applyEstimatedSync(document, track)
  }

  /** Plain-text providers give no timestamps, which leaves the lyrics stage dead.
   * Spread lines evenly across the track (8% intro margin) so users still get a
   * moving highlight, the offset nudge, and click-to-seek — clearly marked as estimated. */
  private fun applyEstimatedSync(document: LyricsDocument, track: Track): LyricsDocument {
    if (document.synced || track.durationMs <= 0 || document.lines.size < 3) return document
    val intro = (track.durationMs * 0.08).toLong()
    val step = ((track.durationMs * 0.84).toLong() / document.lines.size).coerceAtLeast(1L)
    val lines = document.lines.mapIndexed { index, line ->
      if (line.timeMs >= 0L) line else line.copy(timeMs = intro + index * step)
    }
    return document.copy(lines = lines, provider = "${document.provider} (estimated)", synced = true)
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
    val meta = cleanForQuery(track)
    val title = meta.title.takeIf(String::isNotBlank) ?: return null
    val artist = meta.artist.takeIf(String::isNotBlank) ?: return null
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

  /** SimpMusic community lyrics — exact videoId match; Echo prefers richSyncLyrics
   * (word-sync) > syncedLyrics > plain, filters to ±10s duration, and retries on a
   * fallback mirror when the primary host errors. */
  private fun simpMusicResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val videoId = track.videoId ?: return null
    val root = (request("https://api-lyrics.simpmusic.org/v1/$videoId") as? JsonObject)
      ?: (request("https://vivi-yt-music-server.onrender.com/v1/$videoId") as? JsonObject)
      ?: return null
    if (root["type"]?.jsonPrimitive?.contentOrNull != "success") return null
    val entries = root["data"]?.jsonArrayOrNull()?.mapNotNull { it as? JsonObject }.orEmpty()
    val seconds = track.durationMs / 1000
    val valid = if (seconds > 0) entries.filter {
      abs((it["durationSeconds"]?.jsonPrimitive?.longOrNull ?: 0L) - seconds) <= 10
    } else entries
    val best = (if (seconds > 0 && valid.size > 1) {
      valid.minByOrNull { abs((it["durationSeconds"]?.jsonPrimitive?.longOrNull ?: 0L) - seconds) }
    } else null) ?: valid.firstOrNull() ?: entries.firstOrNull() ?: return null
    val text = best["richSyncLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: best["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
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
      val meta = cleanForQuery(track)
      val title = meta.title.takeIf(String::isNotBlank) ?: return null
      val artist = meta.artist.takeIf(String::isNotBlank) ?: return null
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

  /** YouLyPlus / KPoe — synced lyrics across several community mirrors (Echo's top provider).
   * Echo races all mirrors in parallel, keeps the winner first next time, uses the raw
   * (uncleaned) title/artist, and prefers the timed lyrics[] array over plainLyrics. */
  @Volatile private var lastWorkingYouLyPlus: String? = null

  private val youLyPlusPool = Executors.newFixedThreadPool(6) { runnable ->
    Thread(runnable, "youlyplus-race").apply { isDaemon = true }
  }

  private fun youLyPlusResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val title = sanitizeMeta(track.title).takeIf(String::isNotBlank) ?: return null
    val artist = sanitizeMeta(track.artist).takeIf { isRealArtist(it) } ?: return null
    val params = buildString {
      append("title=${encode(title)}&artist=${encode(artist)}")
      append("&duration=${track.durationMs / 1000}")
      if (track.album.isNotBlank() && !track.album.equals("Unknown album", true)) {
        append("&album=${encode(track.album.trim())}")
      }
      track.videoId?.let { append("&id=${encode(it)}") }
    }
    val servers = lastWorkingYouLyPlus?.let { listOf(it) + youLyPlusServers.filter { s -> s != it } }
      ?: youLyPlusServers
    val tasks = servers.map { server ->
      java.util.concurrent.Callable {
        youLyPlusFetch(server, params) ?: throw IllegalStateException("no lyrics")
      }
    }
    return runCatching {
      val (server, document) = youLyPlusPool.invokeAny(tasks, 12, TimeUnit.SECONDS)
      lastWorkingYouLyPlus = server
      document
    }.getOrNull()
  }

  private fun youLyPlusFetch(server: String, params: String): Pair<String, Pair<JsonObject, LyricsDocument>>? {
    val root = request("$server/v2/lyrics/get?$params") as? JsonObject ?: return null
    val text = root["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: kpoeToLrc(root["lyrics"]?.jsonArrayOrNull())
      ?: root["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: return null
    val doc = documentFor("YouLyPlus", text) ?: return null
    return server to doc
  }

  /** KPoe timed-line format ([{text, time(ms)}]) → LRC text; emits word-level
   * <mm:ss.mmm> tags when syllable data is present (our parser strips them to line text). */
  private fun kpoeToLrc(items: JsonArray?): String? {
    val lines = items.orEmpty().mapNotNull { item ->
      val obj = item as? JsonObject ?: return@mapNotNull null
      val ms = obj["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
      val stamp = "[%02d:%02d.%03d]".format(ms / 60000, (ms / 1000) % 60, ms % 1000)
      val syllabus = obj["syllabus"]?.jsonArrayOrNull()?.mapNotNull { it as? JsonObject }.orEmpty()
      if (syllabus.isNotEmpty()) {
        val isBg = syllabus.any { it["isBackground"]?.jsonPrimitive?.jsonBooleanOrNull() == true }
        val sb = StringBuilder(stamp)
        if (isBg) sb.append("{bg}")
        var word = StringBuilder()
        var wordTime = 0L
        fun flush() {
          val text = word.toString().trim()
          if (text.isNotEmpty()) {
            sb.append("<%02d:%02d.%03d>%s ".format(wordTime / 60000, (wordTime / 1000) % 60, wordTime % 1000, text))
          }
          word = StringBuilder()
        }
        syllabus.forEach { syl ->
          val text = syl["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
          if (text.startsWith(" ")) flush()
          if (word.isBlank()) wordTime = syl["time"]?.jsonPrimitive?.longOrNull ?: 0L
          word.append(text)
          if (text.endsWith(" ")) flush()
        }
        flush()
        sb.toString().trim().takeIf { it.length > stamp.length }
      } else {
        val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) null else "$stamp$text"
      }
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  /** BetterLyrics (boidu) — synced TTML. Echo passes the exact title/artist with no
   * normalization: cleaning can return the wrong song's lyrics (e.g. radio edit vs original). */
  private fun betterLyricsResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val title = sanitizeMeta(track.title).takeIf(String::isNotBlank) ?: return null
    val artist = sanitizeMeta(track.artist).takeIf { isRealArtist(it) } ?: return null
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

  /** PaxSenix — Apple Music lyrics mirror with TTML/eLRC/word-sync (Echo's Paxsenix provider).
   * Search results are scored on duration/title/artist before fetching the best candidates. */
  private fun paxsenixResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val meta = cleanForQuery(track)
    if (meta.title.isBlank()) return null
    val artist = meta.primaryArtist.ifBlank { meta.artist }
    val seconds = track.durationMs / 1000
    val album = track.album.trim().takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }

    val queries = buildList {
      if (artist.isNotBlank()) add("${meta.title} $artist")
      if (meta.artist.isNotBlank() && meta.artist != artist) add("${meta.title} ${meta.artist}")
      if (album != null && artist.isNotBlank()) add("${meta.title} $artist $album")
      add(meta.title)
    }.distinct()

    var candidates: List<JsonObject> = emptyList()
    for (q in queries) {
      val results = (request("$paxsenixBaseUrl/apple-music/search?q=${encode(q)}") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }.orEmpty()
      if (results.isEmpty()) continue
      val scored = scorePaxsenix(results, meta.title, artist, track.title, seconds)
      if (scored.isNotEmpty()) { candidates = scored.map { it.first }; break }
    }
    if (candidates.isEmpty()) return null

    for (candidate in candidates.take(3)) {
      val id = candidate["id"]?.jsonPrimitive?.contentOrNull ?: continue
      val lrc = paxsenixLyrics(id) ?: continue
      documentFor("Apple Music", lrc)?.let { return it }
    }
    return null
  }

  /** Echo's scoring: duration proximity dominates, then title/artist match, with remix penalties. */
  private fun scorePaxsenix(
    results: List<JsonObject>,
    cleanTitle: String,
    cleanArtist: String,
    rawTitle: String,
    seconds: Long,
  ): List<Pair<JsonObject, Int>> {
    val bracket = Regex("""\s*\(.*?\)|\s*\[.*?]""")
    val targetTitle = cleanTitle.replace(bracket, "").lowercase().trim()
    val targetMixed = rawTitle.contains("mixed", true)
    val targetRemix = rawTitle.contains("remix", true)
    val artistLower = cleanArtist.lowercase()

    return results.map { result ->
      var score = 0
      val durationMs = result["duration"]?.jsonPrimitive?.longOrNull
      if (durationMs != null && seconds > 0) {
        score += when (val diff = abs(durationMs - seconds * 1000)) {
          in 0..2000 -> 100
          in 2001..5000 -> 50
          in 5001..10000 -> 10
          else -> -50
        }
      }
      val name = result["trackName"]?.jsonPrimitive?.contentOrNull
        ?: result["songName"]?.jsonPrimitive?.contentOrNull.orEmpty()
      val resultTitle = name.replace(bracket, "").lowercase().trim()
      score += when {
        resultTitle == targetTitle -> 80
        resultTitle.contains(targetTitle) || targetTitle.contains(resultTitle) -> 40
        else -> 0
      }
      if (name.contains("mixed", true) && !targetMixed) score -= 60
      if (name.contains("remix", true) && !targetRemix) score -= 40
      if (artistLower.isNotBlank()) {
        val resultArtist = result["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        score += when {
          resultArtist.contains(artistLower) -> 50
          artistLower.split(Regex("\\s+")).any { it.length > 2 && resultArtist.contains(it) } -> 25
          else -> 0
        }
      }
      result to score
    }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
  }

  /** Fetch lyrics for a PaxSenix track id; prefers TTML, then eLRC, then plain, then timed content. */
  private fun paxsenixLyrics(id: String): String? {
    val root = request("$paxsenixBaseUrl/apple-music/lyrics?id=${encode(id)}") as? JsonObject ?: return null
    root["ttmlContent"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?.let { ttmlToLrc(it) }?.takeIf(String::isNotBlank)?.let { return it }
    for (key in listOf("elrcMultiPerson", "elrc", "plain")) {
      root[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
    }
    val content = root["content"]?.jsonArrayOrNull() ?: return null
    val lrc = content.mapNotNull { item ->
      val line = item as? JsonObject ?: return@mapNotNull null
      val ms = line["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
      val text = line["text"]?.jsonArrayOrNull()
        ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        ?.joinToString(" ")?.trim().orEmpty()
      if (text.isBlank()) null else "[%02d:%02d.%02d]%s".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, text)
    }
    return lrc.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  /** KuGou lyrics library — Echo's normalization (all bracket styles stripped from the
   * title, artist separators unified to 、), then duration-matched LRC download with the
   * credit-lines (作词/作曲/…) stripped from the head and tail of the lyric body. */
  private fun kugouResult(track: Track): Pair<JsonObject, LyricsDocument>? {
    val title = kugouNormalizeTitle(track.title.takeIf(String::isNotBlank) ?: return null)
      .trim().takeIf(String::isNotBlank) ?: return null
    val artist = kugouNormalizeArtist(track.artist.takeIf { isRealArtist(sanitizeMeta(it)) } ?: return null)
      .trim().takeIf(String::isNotBlank) ?: return null
    val seconds = track.durationMs / 1000
    val keyword = encode("$title $artist" +
      if (track.album.isNotBlank() && !track.album.equals("Unknown album", true)) " ${track.album.trim()}" else "")

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
    return documentFor("KuGou", kugouStripCredits(lrc).ifBlank { lrc })
  }

  private fun kugouNormalizeTitle(title: String): String =
    title.replace(Regex("\\(.*\\)"), "")
      .replace(Regex("（.*）"), "")
      .replace(Regex("「.*」"), "")
      .replace(Regex("『.*』"), "")
      .replace(Regex("<.*>"), "")
      .replace(Regex("《.*》"), "")
      .replace(Regex("〈.*〉"), "")
      .replace(Regex("＜.*＞"), "")

  private fun kugouNormalizeArtist(artist: String): String =
    artist.replace(", ", "、")
      .replace(" & ", "、")
      .replace(".", "")
      .replace("和", "、")
      .replace(Regex("\\(.*\\)"), "")
      .replace(Regex("（.*）"), "")

  /** Echo's KuGou line filter: keep strictly-timestamped lines, then drop credit lines
   * (e.g. "[00:00.00]作词：X") from the head and tail of the lyric body. */
  private val kugouAcceptedLine = Regex("\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})].*")
  private val kugouCreditLine = Regex(".+].+[:：].+")

  private fun kugouStripCredits(lrc: String): String {
    val lines = lrc.lines().filter { it.matches(kugouAcceptedLine) }
    var head = 0
    for (i in minOf(8, lines.lastIndex) downTo 0) {
      if (lines[i].matches(kugouCreditLine)) { head = i + 1; break }
    }
    val body = lines.drop(head)
    var tail = 0
    for (i in minOf(body.size - 8, body.lastIndex) downTo 0) {
      if (body[body.lastIndex - i].matches(kugouCreditLine)) { tail = i + 1; break }
    }
    return body.dropLast(tail).joinToString("\n")
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
    val meta = cleanForQuery(track)
    if (track.title.isBlank()) return null
    val seconds = track.durationMs / 1000
    val cleanedTitle = meta.title
    val knownArtist = meta.artist.takeIf(String::isNotBlank)
    val album = track.album.trim().takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }

    if (knownArtist != null) {
      val params = linkedMapOf("track_name" to cleanedTitle.ifBlank { track.title.trim() }, "artist_name" to knownArtist)
      if (album != null) params["album_name"] = album
      if (seconds in 1..3600) params["duration"] = seconds.toString()
      val exact = request("$baseUrl/api/get?${query(params)}")
      if (exact is JsonObject) parseRecord(exact, "LRCLIB")?.let { return exact }
    }

    if (cleanedTitle.isBlank()) return null
    val primaryArtist = meta.primaryArtist.takeIf(String::isNotBlank)

    // Echo's LrcLib strategy order: primary artist, full artist, title only,
    // q="artist title", q=title, then raw metadata when cleaning changed it.
    val searchParams = buildList {
      primaryArtist?.let {
        add(mutableMapOf("track_name" to cleanedTitle, "artist_name" to it).apply {
          if (album != null) put("album_name", album)
        })
      }
      if (knownArtist != null && !knownArtist.equals(primaryArtist, true)) {
        add(mutableMapOf("track_name" to cleanedTitle, "artist_name" to knownArtist).apply {
          if (album != null) put("album_name", album)
        })
      }
      add(mapOf("track_name" to cleanedTitle))
      knownArtist?.let { add(mapOf("q" to "$it $cleanedTitle")) }
      add(mapOf("q" to cleanedTitle))
      val rawTitle = sanitizeMeta(track.title).trim()
      val rawArtist = sanitizeMeta(track.artist).trim()
      if (rawTitle != cleanedTitle || (rawArtist.isNotBlank() && rawArtist != knownArtist)) {
        add(mapOf("track_name" to rawTitle, "artist_name" to rawArtist))
      }
    }.distinct()

    for (params in searchParams) {
      val results = (request("$baseUrl/api/search?${query(params)}") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }.orEmpty()
      val match = bestLrcLibMatch(results, seconds, cleanedTitle, knownArtist.orEmpty())
      if (match != null) return match
    }
    return null
  }

  /** Echo's bestMatchingForRelaxed: when duration is known, synced results within ±5s win,
   * then any lyrics within ±5s. With no duration, pick by title/artist similarity (>0.6). */
  private fun bestLrcLibMatch(
    results: List<JsonObject>,
    seconds: Long,
    title: String,
    artist: String,
  ): JsonObject? {
    val usable = results.filter {
      !it["syncedLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() ||
        !it["plainLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
    }
    if (usable.isEmpty()) return null

    fun durationDiff(candidate: JsonObject) =
      abs((candidate["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) - seconds)

    if (seconds > 0) {
      usable.filter { !it["syncedLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
        .minByOrNull(::durationDiff)?.takeIf { durationDiff(it) <= 5 }?.let { return it }
      return usable.minByOrNull(::durationDiff)?.takeIf { durationDiff(it) <= 5 }
    }

    return usable.maxByOrNull { candidate ->
      val titleSim = stringSimilarity(title.trim().lowercase(),
        candidate["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase())
      val artistSim = stringSimilarity(artist.trim().lowercase(),
        candidate["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase())
      (titleSim + artistSim) / 2.0 + (if (candidate["syncedLyrics"] != null) 0.1 else 0.0)
    }?.takeIf { candidate ->
      val titleSim = stringSimilarity(title.trim().lowercase(),
        candidate["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase())
      val artistSim = stringSimilarity(artist.trim().lowercase(),
        candidate["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase())
      (titleSim + artistSim) / 2.0 > 0.6
    }
  }

  private fun stringSimilarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val containsScore = if (a.contains(b) || b.contains(a)) 0.8 else 0.0
    val distance = levenshtein(a, b)
    return maxOf(containsScore, 1.0 - distance.toDouble() / maxOf(a.length, b.length))
  }

  private fun levenshtein(a: String, b: String): Int {
    val row = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      var prev = row[0]
      row[0] = i
      for (j in 1..b.length) {
        val cur = row[j]
        row[j] = minOf(row[j] + 1, row[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
      }
    }
    return row[b.length]
  }

  /** Cleaned title/artist metadata for provider queries — a compact port of Echo's MetadataCleaner:
   * entity/emoji sanitize, "Artist - Title" split, junk/version/feat bracket removal,
   * pipe & hashtag junk, channel-name suffixes, and multi-artist primary split. */
  private data class QueryMeta(val title: String, val artist: String, val primaryArtist: String)

  private val junkBracketWords = Regex(
    """(?i)\b(official\s+music\s+video|official\s+video|official\s+audio|music\s+video|lyrics?\s+video|official\s+visualizer|visualizer|video|audio|mv|pv|hd|hq|4k|8k|1080p|720p|60fps|uhd|full\s+song|from|teaser|trailer|promo)\b"""
  )
  private val versionWords = Regex(
    """(?i)\b(remix|flip|bootleg|vip|re-edit|club\s+mix|dub|live|session|unplugged|acoustic|orchestral|symphonic|remaster(?:ed)?|deluxe|anniversary|instrumental|karaoke|cover|radio\s+edit|edit|extended|slowed|reverb|sped\s+up|nightcore|version|reprise|lofi|8d)\b"""
  )
  private val featMarker = Regex("""(?i)^(?:feat\.?|ft\.?|featuring)\s+(.+)$""")
  private val bracketGroup = Regex("""(\(([^()]*)\)|\[([^\[\]]*)\]|【([^【】]*)】|「([^「」]*)」)""")
  private val artistSeparators = listOf(
    " feat. ", " feat ", " ft. ", " ft ", " featuring ",
    " & ", ", ", "、", " and ", " x ", " X "
  )

  private fun sanitizeMeta(value: String): String = value
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
    .replace(Regex("[‘’´`]"), "'").replace(Regex("[“”]"), "\"")
    .replace(Regex("[\\p{So}\\p{Cn}]"), "")
    .trim()

  private fun cleanArtistName(rawArtist: String): String {
    var artist = sanitizeMeta(rawArtist)
    artist = artist.replace(Regex("""(?i)\s*-\s*Topic$"""), "")
    artist = artist.replace(Regex("""(?i)VEVO$"""), "").trim()
    artist = artist.replace(Regex("""(?i)\s+(Records|Official)$"""), "")
    return artist.trim()
  }

  private fun primaryArtistOf(artist: String): String {
    if (artist.isBlank()) return ""
    var tokens = listOf(artist.trim())
    for (sep in artistSeparators) {
      tokens = tokens.flatMap { it.split(sep, ignoreCase = true) }
    }
    return tokens.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
  }

  private val genericArtistLabels = setOf(
    "song", "video", "album", "single", "ep", "episode", "playlist", "podcast", "mix", "track", "audio"
  )

  private fun isRealArtist(artist: String): Boolean =
    artist.isNotBlank() && !artist.equals("Unknown artist", true) &&
      !artist.equals("YouTube Music", true) && artist.lowercase() !in genericArtistLabels

  private fun cleanForQuery(track: Track): QueryMeta {
    var title = sanitizeMeta(track.title)
    var artist = cleanArtistName(track.artist)
    if (!isRealArtist(artist)) artist = ""

    // YouTube-style "Artist - Title" prefix: split only when it genuinely looks like one.
    val dashParts = title.split(Regex("""\s+[-–—]\s+"""))
    if (dashParts.size >= 2) {
      val left = dashParts[0].trim()
      val rest = dashParts.drop(1).joinToString(" - ").trim()
      val restJunk = rest.startsWith("(") || rest.startsWith("[") || rest.startsWith("【") || rest.startsWith("「") ||
        versionWords.matches(rest) || junkBracketWords.matches(rest)
      val plausible = artist.isBlank() || left.contains(artist, true) || artist.contains(left, true) ||
        track.artist.contains("Topic", true) || track.artist.contains("VEVO", true)
      if (rest.isNotBlank() && !restJunk && plausible) {
        if (artist.isBlank()) artist = left
        title = rest
      }
    }

    // Trailing pipe / slash / hashtag junk
    title = title.replace(Regex("""\s*[|｜].*$"""), "")
    title = title.replace(Regex("""\s*//.*$"""), "")
    title = title.replace(Regex("""\s*#\w+"""), "")

    // Bracket groups: feat. markers are removed, junk/version groups too; neutral brackets stay.
    title = bracketGroup.replace(title) { match ->
      val content = match.groups.drop(2).firstOrNull { it?.value != null }?.value.orEmpty().trim()
      when {
        featMarker.containsMatchIn(content) -> " "
        junkBracketWords.containsMatchIn(content) || versionWords.containsMatchIn(content) -> " "
        else -> match.value
      }
    }

    // Inline " feat. X" (unbracketed) and "- official ..." suffixes
    title = title.replace(Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+[^()\[\]|]+$"""), "")
    title = title.replace(Regex("""(?i)\s*-\s*(official|video|audio|lyrics?|visualizer)\b.*$"""), "")
    title = title.replace(Regex("(?i)\\b(official\\s+)?(music\\s+)?(video|audio|lyrics?|full\\s+song|4k|hd|lyric\\s+video)\\b"), " ")
    title = title.replace(Regex("\\s+"), " ").trim().trim('-', '–', '—', '|', '/').trim()

    return QueryMeta(title, artist, primaryArtistOf(artist))
  }

  private fun cleanTitle(title: String, artist: String = ""): String =
    cleanForQuery(Track("", "", title, artist)).title

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
  // v3: online-track artist/album mapping fixed; results cached under the bad key should refetch.
  private fun cacheKey(track: Track): String = MessageDigest.getInstance("SHA-256")
    .digest("v3|${track.title}|${track.artist}|${track.album}|${track.durationMs / 1000}".toByteArray())
    .take(16).joinToString("") { "%02x".format(it) }

  companion object {
    private fun defaultCacheDirectory(): File {
      return File(solo.music.core.appDataDirectory(), "lyrics-cache")
    }
  }
}