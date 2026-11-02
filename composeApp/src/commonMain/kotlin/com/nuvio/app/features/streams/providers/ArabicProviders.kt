package com.nuvio.app.features.streams.providers

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object ArabicProviders {
    private val log = Logger.withTag("ArabicProviders")

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * Resolves real playable video streams (direct .m3u8/.mp4 or clean embed iframe URLs)
     * for Arabic movies and series by scraping active Arabic streaming portals in the background.
     * NEVER returns website homepages, search pages (?s=...), or parked domains.
     */
    suspend fun resolveStreams(
        title: String,
        arabicTitle: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): List<BuiltInStream> = coroutineScope {
        val queries = buildList {
            arabicTitle?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            title.trim().takeIf { it.isNotBlank() && it != arabicTitle?.trim() }?.let(::add)
            // Also add preposition/synonym variants for Arabic shows (e.g. "حب ع ورق" <-> "حب على ورق")
            val primary = firstOrNull().orEmpty()
            if (primary.contains(" على ")) add(primary.replace(" على ", " ع "))
            if (primary.contains(" ع ")) add(primary.replace(" ع ", " على "))
            if (primary.contains("صاعد")) add(primary.replace("صاعد", "طالع"))
        }.distinct().take(3)

        if (queries.isEmpty()) return@coroutineScope emptyList()

        val larozaMirrors = listOf("https://larozza.beauty", "https://laroza.now")
        val brstejMirrors = listOf("https://brstej.com", "https://brstejj.com")

        val jobs = listOf(
            async {
                scrapePhpMelodyMirrors(
                    providerId = "laroza",
                    providerLabel = "لاروزا (Laroza)",
                    mirrors = larozaMirrors,
                    queries = queries,
                    season = season,
                    episode = episode,
                )
            },
            async {
                scrapePhpMelodyMirrors(
                    providerId = "brstej",
                    providerLabel = "برستيج (Brstej)",
                    mirrors = brstejMirrors,
                    queries = queries,
                    season = season,
                    episode = episode,
                )
            },
        )

        val results = jobs.awaitAll().flatten()
        // Prioritize direct HLS/MP4 streams first, then clean embed iframes
        results.sortedBy { if (it.streamType == "hls" || it.streamType == "mp4") 0 else 1 }
            .distinctBy { it.url }
    }

    private suspend fun scrapePhpMelodyMirrors(
        providerId: String,
        providerLabel: String,
        mirrors: List<String>,
        queries: List<String>,
        season: Int?,
        episode: Int?,
    ): List<BuiltInStream> {
        for (baseUrl in mirrors) {
            for (query in queries) {
                val streams = runCatching {
                    scrapePhpMelodySite(
                        providerId = providerId,
                        providerLabel = providerLabel,
                        baseUrl = baseUrl,
                        query = query,
                        season = season,
                        episode = episode,
                    )
                }.getOrDefault(emptyList())
                if (streams.isNotEmpty()) return streams
            }
        }
        return emptyList()
    }

    private suspend fun scrapePhpMelodySite(
        providerId: String,
        providerLabel: String,
        baseUrl: String,
        query: String,
        season: Int?,
        episode: Int?,
    ): List<BuiltInStream> = coroutineScope {
        val encodedQuery = encodeUrlParam(query)
        val searchUrl = "$baseUrl/search.php?keywords=$encodedQuery"
        val searchHtml = fetchHtml(searchUrl, referer = "$baseUrl/") ?: return@coroutineScope emptyList()
        if (isParkedOrChallengePage(searchHtml)) return@coroutineScope emptyList()

        // Extract candidate video links: <a href="...video.php?vid=XYZ" title="...">
        val linkRegex = Regex(
            """<a[^>]+href=["'](https?://[^"']*(?:video|watch|play)\.php\?vid=([a-zA-Z0-9_-]+)[^"']*)["'][^>]*title=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val altLinkRegex = Regex(
            """<a[^>]+title=["']([^"']+)["'][^>]+href=["'](https?://[^"']*(?:video|watch|play)\.php\?vid=([a-zA-Z0-9_-]+)[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )

        data class VideoCandidate(val url: String, val vid: String, val title: String)

        val candidates = mutableListOf<VideoCandidate>()
        linkRegex.findAll(searchHtml).forEach { m ->
            candidates.add(VideoCandidate(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
        }
        altLinkRegex.findAll(searchHtml).forEach { m ->
            candidates.add(VideoCandidate(m.groupValues[2], m.groupValues[3], m.groupValues[1]))
        }

        if (candidates.isEmpty()) return@coroutineScope emptyList()

        // Strictly verify that the candidate title actually matches the searched series/movie title
        val verifiedCandidates = candidates.filter { cand ->
            matchesQueryTitle(candidateTitle = cand.title, query = query, season = season, episode = episode)
        }
        if (verifiedCandidates.isEmpty()) return@coroutineScope emptyList()

        val matchedCandidate = if (episode != null) {
            val epPatterns = listOf(
                Regex("""(?:الحلقة|حلقة|الحلقه|حلقه|ep|episode)\s*0*$episode\b""", RegexOption.IGNORE_CASE),
                Regex("""\bE0*$episode\b""", RegexOption.IGNORE_CASE),
            )
            verifiedCandidates.firstOrNull { cand -> epPatterns[0].containsMatchIn(cand.title) }
                ?: verifiedCandidates.firstOrNull { cand -> epPatterns[1].containsMatchIn(cand.title) }
        } else {
            verifiedCandidates.firstOrNull()
        } ?: return@coroutineScope emptyList()

        val playUrl = "$baseUrl/play.php?vid=${matchedCandidate.vid}"
        val playHtml = fetchHtml(playUrl, referer = matchedCandidate.url)
            ?: fetchHtml(matchedCandidate.url, referer = searchUrl)
            ?: return@coroutineScope emptyList()

        val embedUrls = extractEmbedIframeUrls(playHtml, baseUrl)
        if (embedUrls.isEmpty()) return@coroutineScope emptyList()

        val streamJobs = embedUrls.take(5).mapIndexed { index, embedUrl ->
            async {
                resolveEmbedToBuiltInStreams(
                    providerId = "${providerId}_${index + 1}",
                    providerLabel = providerLabel,
                    itemTitle = matchedCandidate.title,
                    embedUrl = embedUrl,
                    referer = playUrl,
                    serverIndex = index + 1,
                )
            }
        }

        streamJobs.awaitAll().flatten()
    }

    internal fun extractEmbedIframeUrls(html: String, baseUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        val attrRegex = Regex(
            """(?:<iframe[^>]+src|data-embed-url|data-url|data-src|data-link)=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        for (match in attrRegex.findAll(html)) {
            val raw = match.groupValues[1].trim()
            val full = when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> "${baseUrl.trimEnd('/')}$raw"
                else -> continue
            }
            val lower = full.lowercase()
            if (
                !lower.contains("facebook.com") &&
                !lower.contains("twitter.com") &&
                !lower.contains("google") &&
                !lower.contains("youtube.com") &&
                !lower.contains("ads") &&
                !lower.contains("search.php") &&
                !lower.contains("?s=")
            ) {
                urls.add(full)
            }
        }
        return urls.toList()
    }

    internal suspend fun resolveEmbedToBuiltInStreams(
        providerId: String,
        providerLabel: String,
        itemTitle: String,
        embedUrl: String,
        referer: String,
        serverIndex: Int,
    ): List<BuiltInStream> {
        val hostName = runCatching {
            embedUrl.substringAfter("://").substringBefore("/").removePrefix("www.")
        }.getOrDefault("Server $serverIndex")

        val results = mutableListOf<BuiltInStream>()
        val embedHtml = fetchHtml(embedUrl, referer = referer)
        if (embedHtml != null && !isParkedOrChallengePage(embedHtml)) {
            val directMediaUrl = extractDirectMediaFromHtml(embedHtml)
            if (directMediaUrl != null) {
                val isHls = directMediaUrl.contains(".m3u8", ignoreCase = true)
                val embedOrigin = runCatching {
                    val scheme = embedUrl.substringBefore("://")
                    val host = embedUrl.substringAfter("://").substringBefore("/")
                    "$scheme://$host"
                }.getOrDefault(referer)
                results.add(
                    BuiltInStream(
                        providerId = "${providerId}_direct",
                        providerName = providerLabel,
                        name = "$providerLabel • مباشر ($hostName)",
                        title = "$itemTitle • 1080p FHD مباشر",
                        description = "بث مباشر سريع عبر مشغل التطبيق الأصلي ($hostName)",
                        url = directMediaUrl,
                        streamType = if (isHls) "hls" else "mp4",
                        category = ProviderCategory.ARABIC,
                        quality = "1080p",
                        headers = mapOf(
                            "Referer" to embedUrl,
                            "Origin" to embedOrigin,
                            "User-Agent" to MOBILE_UA,
                        ),
                    )
                )
            }

            results.add(
                BuiltInStream(
                    providerId = providerId,
                    providerName = providerLabel,
                    name = "$providerLabel • سيرفر $serverIndex ($hostName)",
                    title = itemTitle,
                    description = "سيرفر عربي مضمن ($hostName)",
                    url = embedUrl,
                    streamType = "embed",
                    category = ProviderCategory.ARABIC,
                    headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to MOBILE_UA,
                    ),
                )
            )
        }
        return results
    }

    /**
     * Extracts direct .m3u8 or .mp4 stream URLs from raw HTML or Dean Edwards packed JS
     * `eval(function(p,a,c,k,e,d)...)`.
     */
    internal fun extractDirectMediaFromHtml(html: String): String? {
        val combined = buildString {
            append(html)
            unpackDeanEdwardsJs(html).forEach { unpacked ->
                append("\n")
                append(unpacked)
            }
        }

        val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        m3u8Regex.find(combined)?.value?.let { return it.replace("\\/", "/") }

        val mp4Regex = Regex("""https?://[^\s"'<>\\]+\.mp4(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        mp4Regex.find(combined)?.value?.let { return it.replace("\\/", "/") }

        return null
    }

    internal fun unpackDeanEdwardsJs(html: String): List<String> {
        val packedRegex = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        return packedRegex.findAll(html).mapNotNull { match ->
            runCatching {
                val payload = match.groupValues[1]
                val radix = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val count = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                val symtab = match.groupValues[4].split('|')
                if (radix !in 2..62 || count <= 0) return@mapNotNull null

                val wordRegex = Regex("""\b[0-9a-zA-Z]+\b""")
                wordRegex.replace(payload) { wordMatch ->
                    val token = wordMatch.value
                    val index = decodeBase62(token, radix)
                    if (index in 0 until count && index < symtab.size && symtab[index].isNotEmpty()) {
                        symtab[index]
                    } else {
                        token
                    }
                }
            }.getOrNull()
        }.toList()
    }

    private fun decodeBase62(token: String, radix: Int): Int {
        var result = 0
        for (ch in token) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> ch - 'A' + 36
                else -> return -1
            }
            if (digit >= radix) return -1
            result = result * radix + digit
        }
        return result
    }

    private val titleIgnoreWords = setOf(
        "مسلسل", "فيلم", "فلم", "برنامج", "انمي", "أنمي", "مترجم", "مدبلج", "كامل", "كامله", "كاملة",
        "حلقه", "حلقة", "الحلقه", "الحلقة", "موسم", "الموسم", "مشاهده", "مشاهدة", "اون", "أون", "لاين",
        "the", "of", "and", "in", "on", "to", "a", "an"
    )

    internal fun matchesQueryTitle(
        candidateTitle: String,
        query: String,
        season: Int?,
        episode: Int?,
    ): Boolean {
        val normCand = com.nuvio.app.features.tmdb.TmdbService.normalizeArabicForCompare(candidateTitle)
        val normQuery = com.nuvio.app.features.tmdb.TmdbService.normalizeArabicForCompare(query)
        if (normCand.isBlank() || normQuery.isBlank()) return false

        // Reject if we asked for a series episode and candidate is explicitly a movie ("فيلم ...")
        if (episode != null && normCand.startsWith("فيلم ") && !normQuery.startsWith("فيلم")) {
            return false
        }
        // Reject if we asked for a movie and candidate is explicitly a series episode ("مسلسل ... الحلقة ...")
        if (episode == null && (normCand.contains("الحلقه") || normCand.contains("حلقه"))) {
            return false
        }

        // Check season constraint if season > 1
        if (season != null && season > 1) {
            val arabicOrdinals = mapOf(
                2 to listOf("الثاني", "ثاني", "2"),
                3 to listOf("الثالث", "ثالث", "3"),
                4 to listOf("الرابع", "رابع", "4"),
                5 to listOf("الخامس", "خامس", "5"),
                6 to listOf("السادس", "سادس", "6"),
            )
            val seasonTokens = arabicOrdinals[season] ?: listOf(season.toString())
            val hasExplicitOtherSeason = (1..10).filter { it != season }.any { other ->
                normCand.contains("الموسم $other") || normCand.contains("موسم $other") || normCand.contains("s0$other") || normCand.contains("s$other")
            }
            if (hasExplicitOtherSeason && seasonTokens.none { normCand.contains(it) }) {
                return false
            }
        }

        if (normCand.contains(normQuery)) return true

        val queryWords = normQuery.split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in titleIgnoreWords }
            .map { if (it.startsWith("ال") && it.length > 4) it.removePrefix("ال") else it }

        val candWords = normCand.split(' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("ال") && it.length > 4) it.removePrefix("ال") else it }

        if (queryWords.isEmpty()) return false
        return queryWords.all { qw ->
            candWords.any { cw ->
                cw == qw ||
                    ((qw == "علي" || qw == "على") && cw == "ع") ||
                    (qw == "ع" && (cw == "علي" || cw == "على"))
            }
        }
    }

    internal fun isParkedOrChallengePage(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("namesilo") ||
            lower.contains("bmftzxnpbg8") || // base64 "namesilo"
            lower.contains("parklogic") ||
            lower.contains("domain has expired") ||
            lower.contains("this domain is for sale") ||
            lower.contains("buy this domain") ||
            lower.contains("sedoparking") ||
            lower.contains("just a moment...") ||
            lower.contains("cf-browser-verification")
    }

    private suspend fun fetchHtml(url: String, referer: String): String? =
        try {
            val resp = withTimeoutOrNull(4500L) {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = mapOf(
                        "User-Agent" to MOBILE_UA,
                        "Referer" to referer,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                    body = "",
                    followRedirects = true,
                    maxResponseBodyBytes = 256 * 1024,
                )
            }
            if (resp != null && resp.status in 200..399 && resp.body.isNotBlank()) resp.body else null
        } catch (e: Exception) {
            log.d { "Arabic provider fetch failed for $url: ${e.message}" }
            null
        }

    internal fun encodeUrlParam(input: String): String =
        buildString {
            input.encodeToByteArray().forEach { byte ->
                val value = byte.toInt() and 0xFF
                val char = value.toChar()
                if (
                    char in 'a'..'z' ||
                    char in 'A'..'Z' ||
                    char in '0'..'9' ||
                    char == '-' ||
                    char == '_' ||
                    char == '.' ||
                    char == '~'
                ) {
                    append(char)
                } else if (char == ' ') {
                    append('+')
                } else {
                    append('%')
                    val hex = "0123456789ABCDEF"
                    append(hex[value shr 4])
                    append(hex[value and 0x0F])
                }
            }
        }
}
