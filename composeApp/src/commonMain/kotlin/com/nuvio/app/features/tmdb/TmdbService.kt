package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object TmdbService {
    private val log = Logger.withTag("TmdbService")
    private val json = Json { ignoreUnknownKeys = true }
    private val imdbToTmdbCache = linkedMapOf<String, String>()
    private val tmdbToImdbCache = linkedMapOf<String, String>()
    private val cacheMutex = Mutex()

    suspend fun ensureTmdbId(videoId: String, mediaType: String, fallbackImdbId: String? = null): String? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()

        val normalized = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")
            .substringBefore(':')
            .substringBefore('/')
            .trim()

        if (normalized.isBlank()) return null
        if (normalized.all(Char::isDigit)) return normalized
        if (normalized.startsWith("tt", ignoreCase = true)) {
            return imdbToTmdb(imdbId = normalized, mediaType = mediaType, apiKey = apiKey)
        }

        // Fallback: use the IMDB ID supplied by the addon's meta response
        val normalizedFallback = fallbackImdbId
            ?.trim()
            ?.substringBefore(':')
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }
        if (normalizedFallback != null) {
            return imdbToTmdb(imdbId = normalizedFallback, mediaType = mediaType, apiKey = apiKey)
        }

        return null
    }

    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()

        val cacheKey = "$tmdbId:${normalizeMediaType(mediaType)}"
        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey]?.let { return it }
        }

        val endpoint = when (normalizeMediaType(mediaType)) {
            "tv" -> "tv/$tmdbId/external_ids"
            else -> "movie/$tmdbId/external_ids"
        }
        val body = fetch<TmdbExternalIdsResponse>(endpoint = endpoint, apiKey = apiKey) ?: return null
        val imdbId = body.imdbId?.trim()?.takeIf(String::isNotBlank) ?: return null

        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey] = imdbId
            imdbToTmdbCache["$imdbId:${normalizeMediaType(mediaType)}"] = tmdbId.toString()
        }
        return imdbId
    }

    suspend fun fetchArabicTitle(tmdbId: String, mediaType: String): String? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val endpoint = when (normalizeMediaType(mediaType)) {
            "tv" -> "tv/$tmdbId"
            else -> "movie/$tmdbId"
        }
        val body = fetch<TmdbTitleResponse>(
            endpoint = endpoint,
            apiKey = apiKey,
            query = mapOf("language" to "ar-SA"),
        ) ?: return null
        return body.title?.ifBlank { null } ?: body.name?.ifBlank { null }
    }

    suspend fun fetchEnglishTitle(tmdbId: String, mediaType: String): String? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val endpoint = when (normalizeMediaType(mediaType)) {
            "tv" -> "tv/$tmdbId"
            else -> "movie/$tmdbId"
        }
        val body = fetch<TmdbTitleResponse>(
            endpoint = endpoint,
            apiKey = apiKey,
            query = mapOf("language" to "en-US"),
        ) ?: return null
        return body.title?.ifBlank { null }
            ?: body.name?.ifBlank { null }
            ?: body.originalTitle?.ifBlank { null }
            ?: body.originalName?.ifBlank { null }
    }

    suspend fun searchMulti(query: String, language: String = "ar-SA"): List<com.nuvio.app.features.home.MetaPreview> = coroutineScope {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val trimmedQuery = query.trim()
        if (apiKey.isBlank() || trimmedQuery.isBlank()) return@coroutineScope emptyList()

        val effectiveLang = when {
            language.isBlank() -> "ar-SA"
            language.equals("ar", ignoreCase = true) -> "ar-SA"
            else -> language
        }

        val (primaryQueries, fallbackQueries) = buildSmartSearchQueries(trimmedQuery)

        suspend fun queryEndpoint(endpoint: String, q: String, forcedMediaType: String? = null): List<TmdbSearchMultiItem> {
            val resp = fetch<TmdbSearchMultiResponse>(
                endpoint = endpoint,
                apiKey = apiKey,
                query = mapOf(
                    "query" to q,
                    "language" to effectiveLang,
                    "include_adult" to "false",
                ),
            ) ?: return emptyList()
            return if (forcedMediaType != null) {
                resp.results.map { if (it.mediaType.isNullOrBlank()) it.copy(mediaType = forcedMediaType) else it }
            } else {
                resp.results
            }
        }

        val primaryJobs = primaryQueries.flatMap { q ->
            listOf(
                async { queryEndpoint("search/multi", q) },
                async { queryEndpoint("search/tv", q, "tv") },
                async { queryEndpoint("search/movie", q, "movie") },
            )
        }
        val collected = primaryJobs.awaitAll().flatten().toMutableList()

        // If primary + direct variant queries returned fewer than 3 strong matches, execute anchor/keyword fallback queries
        val normalizedUserQuery = normalizeArabicForCompare(trimmedQuery)
        val hasStrongPrimaryMatch = collected.any { item ->
            val candidateTitle = normalizeArabicForCompare(item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: "")
            candidateTitle == normalizedUserQuery || candidateTitle.contains(normalizedUserQuery)
        }

        if ((!hasStrongPrimaryMatch || collected.size < 3) && fallbackQueries.isNotEmpty()) {
            val fallbackJobs = fallbackQueries.flatMap { q ->
                listOf(
                    async { queryEndpoint("search/multi", q) },
                    async { queryEndpoint("search/tv", q, "tv") },
                )
            }
            collected.addAll(fallbackJobs.awaitAll().flatten())
        }

        val allVariantsNormalized = (primaryQueries + fallbackQueries).map(::normalizeArabicForCompare).distinct()
        val queryTokens = extractContentTokens(normalizedUserQuery)

        val deduplicated = linkedMapOf<String, TmdbSearchMultiItem>()
        for (item in collected) {
            val mType = when (item.mediaType?.lowercase()) {
                "tv", "series" -> "series"
                "movie" -> "movie"
                else -> continue
            }
            val key = "$mType:${item.id}"
            if (!deduplicated.containsKey(key)) {
                deduplicated[key] = item.copy(mediaType = mType)
            }
        }

        val scored = deduplicated.values.mapNotNull { item ->
            val mType = item.mediaType ?: return@mapNotNull null
            val title = item.title?.ifBlank { null }
                ?: item.name?.ifBlank { null }
                ?: item.originalTitle?.ifBlank { null }
                ?: item.originalName?.ifBlank { null }
                ?: return@mapNotNull null
            val origTitle = item.originalTitle?.ifBlank { null } ?: item.originalName?.ifBlank { null } ?: title

            val score = scoreSearchCandidate(
                title = title,
                originalTitle = origTitle,
                normalizedUserQuery = normalizedUserQuery,
                allVariantsNormalized = allVariantsNormalized,
                queryTokens = queryTokens,
                popularity = item.popularity ?: 0.0,
                voteCount = item.voteCount ?: 0,
            )
            if (score <= 0.0) return@mapNotNull null

            val date = item.releaseDate?.ifBlank { null } ?: item.firstAirDate?.ifBlank { null }
            val year = date?.take(4)
            val posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val bannerUrl = item.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val rating = item.voteAverage?.takeIf { it > 0.0 }?.let {
                ((it * 10).toInt() / 10.0).toString()
            }

            score to com.nuvio.app.features.home.MetaPreview(
                id = "tmdb:${item.id}",
                type = mType,
                name = title,
                poster = posterUrl,
                banner = bannerUrl,
                description = item.overview,
                releaseInfo = year,
                rawReleaseDate = date,
                imdbRating = rating,
                voteCount = item.voteCount,
            )
        }

        scored.sortedByDescending { it.first }.map { it.second }
    }

    private val arabicStopWords = setOf(
        "على", "علي", "ع", "في", "ف", "من", "عن", "الى", "إلى", "مع", "و", "او", "أو", "يا", "مسلسل", "فيلم", "فلم", "برنامج"
    )

    private val arabicSynonyms = mapOf(
        "صاعد" to listOf("طالع"),
        "طالع" to listOf("صاعد"),
        "هابط" to listOf("نازل"),
        "نازل" to listOf("هابط"),
        "حب" to listOf("حبر", "عشق"),
        "علي" to listOf("ع", "على"),
        "على" to listOf("ع"),
        "ع" to listOf("على"),
        "اولاد" to listOf("ولاد", "عيال"),
        "ولاد" to listOf("اولاد", "أولاد", "عيال"),
        "عائله" to listOf("عيلة", "عيله"),
        "عيله" to listOf("عائلة", "عائله"),
        "ابو" to listOf("أبو", "بابا"),
        "ام" to listOf("أم", "ماما"),
        "حكايه" to listOf("حكاية", "قصة"),
        "قصه" to listOf("قصة", "حكاية"),
        "بيت" to listOf("دار"),
    )

    internal fun normalizeArabicForCompare(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input.lowercase()) {
            when (ch) {
                in '\u064B'..'\u065F', '\u0670', '\u0640' -> {} // Strip tashkeel & tatweel
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                in 'a'..'z', in '0'..'9', in '\u0600'..'\u06FF' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun extractContentTokens(normalized: String): List<String> =
        normalized.split(' ')
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in arabicStopWords }
            .map { if (it.startsWith("ال") && it.length > 4) it.removePrefix("ال") else it }

    private fun buildSmartSearchQueries(rawQuery: String): Pair<List<String>, List<String>> {
        val clean = rawQuery.trim()
            .replace(Regex("^(?:مسلسل|فيلم|فلم)\\s+"), "")
            .trim()
            .ifBlank { rawQuery.trim() }

        val primary = linkedSetOf<String>()
        val fallbacks = linkedSetOf<String>()

        primary.add(clean)
        if (clean != rawQuery.trim()) primary.add(rawQuery.trim())

        val isArabic = clean.any { it in '\u0600'..'\u06FF' }
        if (!isArabic) {
            return primary.toList() to emptyList()
        }

        // 1. Preposition contraction/expansion: "حب على ورق" <-> "حب ع ورق"
        val onToContracted = clean.replace(Regex("\\b(?:على|علي)\\b"), "ع").replace(Regex("\\s+"), " ").trim()
        if (onToContracted != clean) primary.add(onToContracted)

        val contractedToOn = clean.replace(Regex("\\bع\\b"), "على").replace(Regex("\\s+"), " ").trim()
        if (contractedToOn != clean) primary.add(contractedToOn)

        // 2. Dialectal & synonym token replacements (e.g. "صاعد نازل" -> "طالع نازل", "حب على ورق" -> "حبر على ورق")
        val rawTokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (i in rawTokens.indices) {
            val normToken = normalizeArabicForCompare(rawTokens[i])
            val replacements = arabicSynonyms[normToken] ?: arabicSynonyms[rawTokens[i]] ?: emptyList()
            for (rep in replacements) {
                val mutated = rawTokens.toMutableList()
                mutated[i] = rep
                primary.add(mutated.joinToString(" "))
            }
        }

        // 3. Sub-phrases & anchor tokens for multi-word Arabic queries
        if (rawTokens.size >= 2) {
            // Last 2 words (e.g. "على ورق" from "حب على ورق")
            val tailTwo = rawTokens.takeLast(2).joinToString(" ")
            fallbacks.add(tailTwo)
            // First and last content word without middle preposition (e.g. "حب ورق")
            val contentWords = rawTokens.filter { normalizeArabicForCompare(it) !in arabicStopWords }
            if (contentWords.size >= 2) {
                fallbacks.add(contentWords.joinToString(" "))
            }
            // Individual longest/anchor content words (e.g. "ورق", "نازل")
            contentWords
                .filter { it.length >= 3 }
                .sortedByDescending { it.length }
                .take(2)
                .forEach { fallbacks.add(it) }
        }

        fallbacks.removeAll(primary)
        return primary.take(4).toList() to fallbacks.take(3).toList()
    }

    private fun scoreSearchCandidate(
        title: String,
        originalTitle: String,
        normalizedUserQuery: String,
        allVariantsNormalized: List<String>,
        queryTokens: List<String>,
        popularity: Double,
        voteCount: Int,
    ): Double {
        val normTitle = normalizeArabicForCompare(title)
        val normOrig = normalizeArabicForCompare(originalTitle)
        val candidateNorms = listOf(normTitle, normOrig).filter { it.isNotBlank() }.distinct()
        if (candidateNorms.isEmpty()) return 0.0

        var bestScore = 0.0
        for (cand in candidateNorms) {
            var s = 0.0
            if (cand == normalizedUserQuery) {
                s += 1000.0
            } else if (allVariantsNormalized.any { it == cand }) {
                s += 950.0
            } else if (cand.contains(normalizedUserQuery) || normalizedUserQuery.contains(cand)) {
                s += 650.0
            } else if (allVariantsNormalized.any { it.length >= 4 && (cand.contains(it) || it.contains(cand)) }) {
                s += 550.0
            }

            val candTokens = extractContentTokens(cand)
            if (queryTokens.isNotEmpty() && candTokens.isNotEmpty()) {
                var matchedTokens = 0.0
                for (qt in queryTokens) {
                    val qSyns = (arabicSynonyms[qt].orEmpty().map(::normalizeArabicForCompare) + qt).toSet()
                    val exactTokenMatch = candTokens.any { ct -> ct in qSyns }
                    val prefixOrCloseMatch = !exactTokenMatch && candTokens.any { ct ->
                        ct.startsWith(qt) || qt.startsWith(ct)
                    }
                    if (exactTokenMatch) {
                        matchedTokens += 1.0
                    } else if (prefixOrCloseMatch) {
                        matchedTokens += 0.75
                    }
                }
                val ratio = matchedTokens / queryTokens.size.toDouble()
                s += ratio * 400.0
            }

            if (s > bestScore) bestScore = s
        }

        if (bestScore < 150.0) return 0.0
        val popBonus = popularity.coerceIn(0.0, 50.0) * 0.5 + (voteCount.coerceAtMost(500) * 0.02)
        return bestScore + popBonus
    }

    private suspend fun imdbToTmdb(imdbId: String, mediaType: String, apiKey: String): String? {
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "$imdbId:$normalizedType"
        cacheMutex.withLock {
            imdbToTmdbCache[cacheKey]?.let { return it }
        }

        val body = fetch<TmdbFindResponse>(
            endpoint = "find/$imdbId",
            apiKey = apiKey,
            query = mapOf("external_source" to "imdb_id"),
        ) ?: return null

        val resultId = when (normalizedType) {
            "movie" -> body.movieResults.firstOrNull()?.id
            "tv" -> body.tvResults.firstOrNull()?.id
            else -> body.movieResults.firstOrNull()?.id ?: body.tvResults.firstOrNull()?.id
        }?.takeIf { it > 0 }?.toString()

        if (resultId != null) {
            cacheMutex.withLock {
                imdbToTmdbCache[cacheKey] = resultId
                tmdbToImdbCache["$resultId:$normalizedType"] = imdbId
            }
        } else {
            log.d { "No TMDB ID found for $imdbId ($normalizedType)" }
        }

        return resultId
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching {
            json.decodeFromString<T>(httpGetText(url))
        }.onFailure { error ->
            log.w { "TMDB request failed for $endpoint: ${error.message}" }
        }.getOrNull()
    }

    internal fun normalizeMediaType(mediaType: String): String =
        when (mediaType.trim().lowercase()) {
            "movie", "film" -> "movie"
            "tv", "series", "show", "tvshow" -> "tv"
            else -> mediaType.trim().lowercase()
        }
}

internal fun buildTmdbUrl(
    endpoint: String,
    apiKey: String,
    query: Map<String, String> = emptyMap(),
): String {
    val params = linkedMapOf("api_key" to apiKey)
    query.forEach { (key, value) ->
        if (value.isNotBlank()) {
            params[key] = value
        }
    }
    return buildString {
        append("https://api.themoviedb.org/3/")
        append(endpoint.removePrefix("/"))
        if (params.isNotEmpty()) {
            append("?")
            append(params.entries.joinToString("&") { (key, value) -> "${key.encodeUrlComponent()}=${value.encodeUrlComponent()}" })
        }
    }
}

private fun String.encodeUrlComponent(): String =
    buildString {
        encodeToByteArray().forEach { byte ->
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
            } else {
                append('%')
                val hexChars = "0123456789ABCDEF"
                append(hexChars[value shr 4])
                append(hexChars[value and 0x0F])
            }
        }
    }

@Serializable
private data class TmdbFindResponse(
    @SerialName("movie_results") val movieResults: List<TmdbExternalResult> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbExternalResult> = emptyList(),
)

@Serializable
private data class TmdbExternalResult(
    val id: Int,
)

@Serializable
private data class TmdbExternalIdsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable
private data class TmdbTitleResponse(
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
)

@Serializable
private data class TmdbSearchMultiResponse(
    val results: List<TmdbSearchMultiItem> = emptyList(),
)

@Serializable
private data class TmdbSearchMultiItem(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
)
