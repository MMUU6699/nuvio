package com.nuvio.app.features.streams.providers

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object BuiltInProvidersRegistry {
    private val log = Logger.withTag("BuiltInProviders")

    private val arabicCountryCodes = setOf(
        "EG", "SA", "SY", "LB", "KW", "AE", "QA", "BH", "OM", "JO", "IQ", "PS", "YE", "LY", "TN", "DZ", "MA", "SD"
    )

    fun hasProviders(type: String): Boolean {
        val norm = type.lowercase().trim()
        return norm == "movie" || norm == "film" || norm == "series" || norm == "tv" || norm == "anime"
    }

    suspend fun getCandidateStreams(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        title: String = "",
        season: Int? = null,
        episode: Int? = null,
    ): List<StreamItem> = withContext(Dispatchers.Default) {
        coroutineScope {
            val meta = MetaDetailsRepository.uiState.value.meta
            val fallbackImdb = parentMetaId?.takeIf { it.startsWith("tt", ignoreCase = true) }
                ?: meta?.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
                ?: meta?.id?.takeIf { it.startsWith("tt", ignoreCase = true) }

            val rawPrefix = videoId
                .removePrefix("tmdb:")
                .removePrefix("movie:")
                .removePrefix("series:")
                .removePrefix("tv:")
                .substringBefore(':')
                .substringBefore('/')
                .trim()

            // Never strip 'tt' from an IMDb ID to produce a fake TMDB ID!
            val tmdbId = TmdbService.ensureTmdbId(
                videoId = videoId,
                mediaType = type,
                fallbackImdbId = fallbackImdb,
            ) ?: rawPrefix.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: parentMetaId
                    ?.removePrefix("tmdb:")
                    ?.substringBefore(':')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

            val effectiveTitle = title.ifBlank { meta?.name.orEmpty() }.ifBlank { "Movie" }
            val arabicTitleDeferred = async {
                if (tmdbId != null) {
                    runCatching { TmdbService.fetchArabicTitle(tmdbId, type) }.getOrNull() ?: effectiveTitle
                } else {
                    effectiveTitle
                }
            }
            val englishTitleDeferred = async {
                if (tmdbId != null) {
                    runCatching { TmdbService.fetchEnglishTitle(tmdbId, type) }.getOrNull() ?: effectiveTitle
                } else {
                    effectiveTitle
                }
            }
            val arabicTitle = arabicTitleDeferred.await()
            val englishTitle = englishTitleDeferred.await()

            val isNativeArabicOrigin = isArabicOriginContent(meta, videoId, parentMetaId)

            val arabicStreamsDeferred = async {
                runCatching {
                    ArabicProviders.resolveStreams(
                        title = englishTitle,
                        arabicTitle = arabicTitle,
                        season = season,
                        episode = episode,
                    )
                }.getOrDefault(emptyList())
            }

            val animeStreamsDeferred = async {
                if (type.equals("anime", ignoreCase = true) || effectiveTitle.contains("anime", ignoreCase = true)) {
                    runCatching {
                        AnimeProviders.resolveStreams(
                            title = englishTitle,
                            arabicTitle = arabicTitle,
                            episode = episode,
                        )
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

            val indexStreams = if (tmdbId != null) {
                IndexWebProviders.getAll(tmdbId, season, episode)
            } else {
                emptyList()
            }
            val arabicStreams = arabicStreamsDeferred.await()
            val animeStreams = animeStreamsDeferred.await()

            val ordered = buildList {
                if (isNativeArabicOrigin) {
                    // Native Arabic series/movies: direct HLS/MP4 first, then Arabic embed servers, then global TMDB embeds
                    addAll(arabicStreams.filter { it.streamType == "hls" || it.streamType == "mp4" })
                    addAll(arabicStreams.filter { it.streamType != "hls" && it.streamType != "mp4" })
                    addAll(indexStreams)
                } else {
                    // Global movies/series (e.g. House of the Dragon): exact TMDB/season/episode servers FIRST
                    // so a foreign show never accidentally plays a mismatched Arabic scraper result!
                    addAll(indexStreams)
                    addAll(arabicStreams.filter { it.streamType == "hls" || it.streamType == "mp4" })
                    addAll(arabicStreams.filter { it.streamType != "hls" && it.streamType != "mp4" })
                }
                addAll(animeStreams.filter { it.streamType == "hls" || it.streamType == "mp4" })
                addAll(animeStreams.filter { it.streamType != "hls" && it.streamType != "mp4" })
            }.distinctBy { it.url }

            ordered.map { it.toStreamItem() }
        }
    }

    private fun isArabicOriginContent(
        meta: com.nuvio.app.features.details.MetaDetails?,
        videoId: String,
        parentMetaId: String?,
    ): Boolean {
        if (meta != null) {
            val lang = meta.language?.trim()?.lowercase().orEmpty()
            if (lang == "ar" || lang.startsWith("ar-") || lang == "arabic" || lang == "العربية") {
                return true
            }
            val countryTokens = meta.country.orEmpty().split(',', '/', '|').map { it.trim().uppercase() }
            if (countryTokens.any { it in arabicCountryCodes }) {
                return true
            }
            if (lang.isNotBlank() && lang != "ar") {
                return false
            }
        }
        // If no IMDb ID (tt...) is present and the ID is a standalone TMDB ID, check if title is Arabic
        val hasImdb = videoId.startsWith("tt", ignoreCase = true) ||
            (parentMetaId?.startsWith("tt", ignoreCase = true) == true) ||
            (meta?.imdbId?.startsWith("tt", ignoreCase = true) == true)
        return !hasImdb && (meta?.name?.any { it in '\u0600'..'\u06FF' } == true)
    }

    /**
     * Background Provider Hook:
     * Iterates through candidate providers in the background, verifies availability,
     * extracts direct .m3u8/.mp4 streams whenever exposed, and rejects dead/parked servers.
     */
    suspend fun resolveSequentialWorkingStream(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        title: String = "",
        season: Int? = null,
        episode: Int? = null,
        onStatus: ((String) -> Unit)? = null,
    ): StreamItem? = withContext(Dispatchers.Default) {
        val candidates = getCandidateStreams(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            title = title,
            season = season,
            episode = episode,
        )
        if (candidates.isEmpty()) return@withContext null

        for (candidate in candidates) {
            val url = candidate.url ?: continue
            if (isSearchOrInvalidUrl(url)) continue

            val providerName = candidate.addonName
            onStatus?.invoke("جاري التحقق من $providerName...")
            log.d { "Checking provider $providerName ($url)" }

            // Direct HLS/MP4 streams extracted by ArabicProviders are already verified
            if (candidate.streamType == "hls" || candidate.streamType == "mp4") {
                log.i { "Direct stream from $providerName is READY ($url)" }
                return@withContext candidate
            }

            val reqHeaders = candidate.behaviorHints.proxyHeaders?.request.orEmpty()
            val probeResult = probeProviderStream(url, reqHeaders)
            if (probeResult.isReachable) {
                log.i { "Provider $providerName is REACHABLE and READY!" }
                if (probeResult.extractedDirectUrl != null) {
                    val directUrl = probeResult.extractedDirectUrl
                    val isHls = directUrl.contains(".m3u8", ignoreCase = true)
                    return@withContext candidate.copy(
                        url = directUrl,
                        streamType = if (isHls) "hls" else "mp4",
                        behaviorHints = StreamBehaviorHints(
                            proxyHeaders = StreamProxyHeaders(
                                request = reqHeaders + mapOf("Referer" to url),
                            ),
                        ),
                    )
                }
                return@withContext candidate
            } else {
                log.w { "Provider $providerName unreachable or returned error, trying next..." }
            }
        }

        candidates.firstOrNull { !isSearchOrInvalidUrl(it.url.orEmpty()) }
    }

    internal fun isSearchOrInvalidUrl(url: String): Boolean {
        if (url.isBlank()) return true
        val lower = url.lowercase()
        return lower.contains("?s=") ||
            lower.contains("&s=") && lower.contains("search_param") ||
            lower.contains("/search?") ||
            lower.contains("/search/") ||
            lower.contains("/explore/?q=") ||
            lower.contains("egybest.lat") ||
            lower.contains("akwam.cx") ||
            lower.contains("alooytv.com") ||
            lower.contains("cima4u.vip") ||
            lower.contains("faselhd.club") ||
            lower.contains("wecima.show") ||
            lower.contains("karmalak") ||
            lower.contains("vidking.net") ||
            lower.contains("moviesapi.club") ||
            lower.contains("embed.su") ||
            lower.contains("witanime.pics") ||
            lower.contains("anitaku.to")
    }

    private data class ProviderProbeResult(
        val isReachable: Boolean,
        val extractedDirectUrl: String? = null,
    )

    private suspend fun probeProviderStream(url: String, headers: Map<String, String>): ProviderProbeResult {
        return try {
            val response = withTimeoutOrNull(3500L) {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    ) + headers,
                    body = "",
                    followRedirects = true,
                    maxResponseBodyBytes = 65536,
                )
            } ?: return ProviderProbeResult(false)

            if (response.status !in 200..399) return ProviderProbeResult(false)

            val rawBody = response.body
            if (ArabicProviders.isParkedOrChallengePage(rawBody)) {
                return ProviderProbeResult(false)
            }

            val body = rawBody.lowercase()
            if (
                body.contains("<title>redirecting...</title>") ||
                body.contains("video not found") ||
                body.contains("media not found") ||
                body.contains("file deleted") ||
                body.contains("video deleted") ||
                body.contains("404 not found") ||
                body.contains("لم يتم العثور")
            ) {
                return ProviderProbeResult(false)
            }

            // Check if a direct .m3u8 or .mp4 stream can be unpacked directly from the HTML
            val directMedia = ArabicProviders.extractDirectMediaFromHtml(rawBody)
            if (directMedia != null) {
                return ProviderProbeResult(isReachable = true, extractedDirectUrl = directMedia)
            }

            val hasPlayerOrMedia = body.contains("<video") ||
                body.contains("<iframe") ||
                body.contains("jwplayer") ||
                body.contains("videojs") ||
                body.contains(".m3u8") ||
                body.contains(".mp4") ||
                body.contains("vidlink") ||
                body.contains("vidfast") ||
                body.contains("vidsrc") ||
                body.contains("autoembed") ||
                body.contains("multiembed") ||
                body.contains("2embed") ||
                body.contains("nontongo") ||
                body.contains("smashystream") ||
                body.contains("player")

            ProviderProbeResult(isReachable = hasPlayerOrMedia)
        } catch (_: Exception) {
            ProviderProbeResult(false)
        }
    }
}
