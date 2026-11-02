package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import org.jetbrains.compose.resources.getString

object SubtitleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow<SubtitleLoadingProgress?>(null)
    internal val loadingProgress: StateFlow<SubtitleLoadingProgress?> = _loadingProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var activeFetchJob: Job? = null

    fun fetchAddonSubtitles(
        type: String,
        videoId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        fallbackMetaId: String? = null,
    ) {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        activeFetchJob = scope.launch {
            _isLoading.value = true
            _error.value = null

            val normalizedType = if (type.equals("tv", ignoreCase = true)) "series" else type.trim().lowercase()
            val effectiveVideoId = resolveEffectiveImdbVideoId(
                type = normalizedType,
                videoId = videoId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                fallbackMetaId = fallbackMetaId,
            )
            val requests = addonSubtitleRequests(normalizedType, effectiveVideoId).toMutableList()
            if (requests.none { it.addonId.contains("opensubtitles", ignoreCase = true) }) {
                requests.add(
                    SubtitleAddonRequest(
                        url = "https://opensubtitles-v3.strem.io/subtitles/$normalizedType/$effectiveVideoId.json",
                        addonId = "org.stremio.opensubtitles",
                        addonName = "OpenSubtitles v3",
                    )
                )
            }

            _loadingProgress.value = SubtitleLoadingProgress(total = requests.size + 1)

            val wyzieDeferred = async {
                fetchWyzieSubtitles(
                    type = normalizedType,
                    effectiveVideoId = effectiveVideoId,
                    rawVideoId = videoId,
                    fallbackMetaId = fallbackMetaId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                )
            }

            loadAddonSubtitles(requests) { request, subtitles ->
                if (subtitles.isNotEmpty()) {
                    _addonSubtitles.update { current -> (current + subtitles).distinctBy { it.url } }
                }
                _loadingProgress.update { progress ->
                    progress?.copy(completed = progress.completed + 1, addonName = request.addonName)
                }
            }

            val wyzieSubs = wyzieDeferred.await()
            if (wyzieSubs.isNotEmpty()) {
                _addonSubtitles.update { current -> (wyzieSubs + current).distinctBy { it.url } }
            }
            _loadingProgress.update { progress ->
                progress?.copy(completed = (progress.completed + 1).coerceAtMost(progress.total), addonName = "Wyzie Subs")
            }

            // Sort Arabic subtitles first so they are immediately visible and selected
            _addonSubtitles.update { list ->
                list.sortedWith(
                    compareBy<AddonSubtitle> { sub ->
                        val lang = sub.language.lowercase()
                        when {
                            lang == "ar" || lang == "ara" || lang.startsWith("ar-") -> 0
                            lang == "en" || lang == "eng" || lang.startsWith("en-") -> 1
                            else -> 2
                        }
                    }
                )
            }

            if (_addonSubtitles.value.isEmpty()) {
                _error.value = runCatching { getString(Res.string.compose_player_no_subtitles_found) }
                    .getOrDefault("لم يتم العثور على ترجمات")
            }
            _isLoading.value = false
        }
    }

    fun addInterceptedSubtitle(url: String, language: String? = null, label: String? = null) {
        if (url.isBlank()) return
        val urlLang = language?.takeIf { it.isNotBlank() } ?: detectSubtitleLanguageFromUrl(url)
        val initialLang = urlLang ?: "en"
        val initialDisplay = label?.takeIf { it.isNotBlank() } ?: "${arabicLanguageName(initialLang)} • مدمجة"
        val subId = "intercepted_${url.hashCode()}"
        val sub = AddonSubtitle(
            id = subId,
            url = url,
            language = initialLang,
            display = initialDisplay,
            addonName = "Nuvio Stream",
        )
        _addonSubtitles.update { current ->
            if (current.any { it.url == url }) return@update current
            val updated = if (initialLang == "ar") listOf(sub) + current else current + sub
            sortSubtitlesArabicFirst(updated)
        }

        // Inspect actual subtitle content asynchronously to classify its true language (Arabic vs Persian vs Russian vs French/Portuguese/Spanish/English)
        scope.launch {
            val contentSample = withTimeoutOrNull(4_000L) {
                runCatching { httpGetText(url) }.getOrNull()
            }?.take(3500)
            if (!contentSample.isNullOrBlank()) {
                val detectedFromContent = detectSubtitleLanguageFromContent(contentSample, urlLang)
                if (detectedFromContent != null) {
                    val refinedDisplay = "${arabicLanguageName(detectedFromContent)} • مدمجة"
                    _addonSubtitles.update { current ->
                        val mapped = current.map { item ->
                            if (item.url == url) {
                                item.copy(language = detectedFromContent, display = refinedDisplay)
                            } else {
                                item
                            }
                        }
                        sortSubtitlesArabicFirst(mapped)
                    }
                }
            }
        }
    }

    private fun sortSubtitlesArabicFirst(list: List<AddonSubtitle>): List<AddonSubtitle> =
        list.sortedWith(
            compareBy<AddonSubtitle> { sub ->
                val lang = sub.language.lowercase()
                when {
                    lang == "ar" || lang == "ara" || lang.startsWith("ar-") -> 0
                    lang == "en" || lang == "eng" || lang.startsWith("en-") -> 1
                    else -> 2
                }
            }
        )

    internal fun detectSubtitleLanguageFromUrl(url: String): String? {
        val lower = url.lowercase()
        val pathAndQuery = lower.substringAfter("://").substringAfter("/")
        val tokens = pathAndQuery.split('/', '_', '-', '.', '?', '&', '=').filter { it.isNotBlank() }

        for (t in tokens) {
            when (t) {
                "arabic", "ara", "ar" -> return "ar"
                "persian", "farsi", "per", "fas", "fa" -> return "fa"
                "french", "francais", "fre", "fra", "fr" -> return "fr"
                "russian", "rus", "ru" -> return "ru"
                "portuguese", "portugues", "por", "pob", "pt", "brazil", "brasil", "br" -> return "pt-br"
                "spanish", "espanol", "spa", "esp", "es", "latino" -> return "es"
                "english", "eng", "en" -> return "en"
                "turkish", "tur", "tr" -> return "tr"
                "german", "deutsch", "ger", "deu", "de" -> return "de"
                "italian", "italiano", "ita", "it" -> return "it"
                "hindi", "hin", "hi" -> return "hi"
                "indonesian", "ind", "id" -> return "id"
            }
        }
        return null
    }

    internal fun detectSubtitleLanguageFromContent(sample: String, fallbackUrlLang: String?): String? {
        // Strip WEBVTT headers and timestamp lines
        val lines = sample.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("WEBVTT", ignoreCase = true) &&
                    !line.contains("-->") &&
                    !line.all(Char::isDigit)
            }
            .joinToString(" ")

        if (lines.isBlank()) return fallbackUrlLang

        var arabicScriptCount = 0
        var persianSpecificCount = 0
        var cyrillicCount = 0
        var latinCount = 0

        for (ch in lines) {
            when (ch) {
                'پ', 'چ', 'ژ', 'گ', 'ک', 'ی', 'ۀ' -> {
                    arabicScriptCount++
                    persianSpecificCount++
                }
                in '\u0600'..'\u06FF' -> arabicScriptCount++
                in '\u0400'..'\u04FF' -> cyrillicCount++
                in 'a'..'z', in 'A'..'Z', in '\u00C0'..'\u00FF' -> latinCount++
            }
        }

        if (arabicScriptCount >= 10) {
            // Persian uses ک (U+06A9), ی (U+06CC), پ, چ, ژ, گ frequently, whereas standard Arabic uses ك (U+0643) and ي (U+064A)
            val lowerText = lines.lowercase()
            val hasPersianWords = lowerText.contains(" است ") || lowerText.contains(" من ") && persianSpecificCount >= 2 ||
                lowerText.contains(" شما ") || lowerText.contains(" این ") || lowerText.contains(" برای ")
            return if (persianSpecificCount >= 3 || hasPersianWords) "fa" else "ar"
        }

        if (cyrillicCount >= 10) {
            return "ru"
        }

        if (latinCount >= 10) {
            if (fallbackUrlLang != null && fallbackUrlLang != "ar" && fallbackUrlLang != "fa" && fallbackUrlLang != "ru") {
                return fallbackUrlLang
            }
            val lower = " ${lines.lowercase()} "
            val ptScore = listOf(" você", " não", " está", " para ", " uma ", " com ", " que ", " isso", " meu ", " babaca").count { lower.contains(it) }
            val frScore = listOf(" c'est", " je ", " vous ", " pas ", " nous ", " est ", " dans ", " pour ", " avec ", " mais ", " qu'").count { lower.contains(it) }
            val esScore = listOf(" qué", " está", " para ", " pero ", " como ", " por ", " una ", " los ", " las ", " esto").count { lower.contains(it) }
            val trScore = listOf(" bir ", " الج", " için", " değil", " بن ", " ama ", " çok ", " gibi ").count { lower.contains(it) }
            val enScore = listOf(" the ", " you ", " that ", " with ", " have ", " this ", " what ", " your ", " from ").count { lower.contains(it) }

            val best = listOf(
                "pt-br" to ptScore,
                "fr" to frScore,
                "es" to esScore,
                "tr" to trScore,
                "en" to enScore,
            ).maxByOrNull { it.second }

            if (best != null && best.second >= 2) {
                return best.first
            }
            return fallbackUrlLang ?: "en"
        }

        return fallbackUrlLang
    }

    private fun arabicLanguageName(code: String): String =
        when (code.lowercase()) {
            "ar", "ara", "arabic" -> "العربية"
            "en", "eng", "english" -> "الإنجليزية"
            "fr", "fre", "fra", "french" -> "الفرنسية"
            "fa", "per", "fas", "persian", "farsi" -> "الفارسية"
            "ru", "rus", "russian" -> "الروسية"
            "pt", "pt-br", "por", "pob", "portuguese" -> "البرتغالية"
            "es", "es-419", "spa", "spanish" -> "الإسبانية"
            "tr", "tur", "turkish" -> "التركية"
            "de", "ger", "deu", "german" -> "الألمانية"
            "it", "ita", "italian" -> "الإيطالية"
            "hi", "hin", "hindi" -> "الهندية"
            "id", "ind", "indonesian" -> "الإندونيسية"
            else -> code.uppercase()
        }

    private suspend fun fetchWyzieSubtitles(
        type: String,
        effectiveVideoId: String,
        rawVideoId: String,
        fallbackMetaId: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): List<AddonSubtitle> {
        return try {
            val parts = effectiveVideoId.split(":")
            val baseImdb = parts.firstOrNull { it.startsWith("tt", ignoreCase = true) }
            val tmdbId = com.nuvio.app.features.tmdb.TmdbService.ensureTmdbId(
                videoId = rawVideoId,
                mediaType = type,
                fallbackImdbId = fallbackMetaId ?: baseImdb,
            )
            val lookupId = baseImdb ?: tmdbId ?: return emptyList()
            val season = seasonNumber ?: parts.getOrNull(parts.size - 2)?.toIntOrNull()
            val episode = episodeNumber ?: parts.getOrNull(parts.size - 1)?.toIntOrNull()

            val url = if (type == "series" && season != null && episode != null) {
                "https://sub.wyzie.ru/search?id=$lookupId&season=$season&episode=$episode"
            } else {
                "https://sub.wyzie.ru/search?id=$lookupId"
            }

            val responseText = withTimeoutOrNull(8_000L) {
                httpGetText(url)
            } ?: return emptyList()

            val array = runCatching { json.parseToJsonElement(responseText) as? JsonArray }.getOrNull()
                ?: return emptyList()

            array.mapIndexedNotNull { idx, elem ->
                val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
                val subUrl = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                val langCode = obj["language"]?.jsonPrimitive?.contentOrNull
                    ?: obj["lang"]?.jsonPrimitive?.contentOrNull
                    ?: "en"
                val displayLang = obj["display"]?.jsonPrimitive?.contentOrNull
                    ?: langCode.uppercase()
                val normalized = normalizeLanguageCode(langCode) ?: langCode
                AddonSubtitle(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "wyzie_$idx",
                    url = subUrl,
                    language = normalized,
                    display = "$displayLang • Wyzie",
                    addonName = "Wyzie Subs",
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveEffectiveImdbVideoId(
        type: String,
        videoId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        fallbackMetaId: String?,
    ): String {
        val isSeries = type.equals("series", ignoreCase = true) ||
            type.equals("tv", ignoreCase = true) ||
            (seasonNumber != null && episodeNumber != null)

        val parts = videoId.split(":")
        val parsedSeason = seasonNumber ?: if (parts.size >= 3) parts[parts.size - 2].toIntOrNull() else null
        val parsedEpisode = episodeNumber ?: if (parts.size >= 3) parts[parts.size - 1].toIntOrNull() else null
        val seasonEpisodeSuffix = if (isSeries && parsedSeason != null && parsedEpisode != null) {
            ":$parsedSeason:$parsedEpisode"
        } else {
            ""
        }

        // 1. Direct IMDb ID in videoId
        val imdbFromVideoId = parts.firstOrNull { it.startsWith("tt", ignoreCase = true) }
        if (imdbFromVideoId != null) {
            return "$imdbFromVideoId$seasonEpisodeSuffix"
        }

        // 2. IMDb ID from fallbackMetaId or MetaDetailsRepository
        val imdbFromFallback = fallbackMetaId
            ?.split(":")
            ?.firstOrNull { it.startsWith("tt", ignoreCase = true) }
        if (imdbFromFallback != null) {
            return "$imdbFromFallback$seasonEpisodeSuffix"
        }

        val meta = com.nuvio.app.features.details.MetaDetailsRepository.uiState.value.meta
        val imdbFromMeta = meta?.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: meta?.id?.takeIf { it.startsWith("tt", ignoreCase = true) }
        if (imdbFromMeta != null) {
            return "$imdbFromMeta$seasonEpisodeSuffix"
        }

        // 3. Resolve TMDB numeric ID -> IMDb ID via TmdbService
        val tmdbNumeric = extractTmdbNumericId(videoId)
            ?: extractTmdbNumericId(fallbackMetaId.orEmpty())
            ?: extractTmdbNumericId(meta?.id.orEmpty())
        if (tmdbNumeric != null) {
            val resolved = com.nuvio.app.features.tmdb.TmdbService.tmdbToImdb(tmdbNumeric, type)
            if (resolved != null && resolved.startsWith("tt", ignoreCase = true)) {
                return "$resolved$seasonEpisodeSuffix"
            }
        }

        return if (seasonEpisodeSuffix.isNotEmpty() && !videoId.endsWith(seasonEpisodeSuffix)) {
            "$videoId$seasonEpisodeSuffix"
        } else {
            videoId
        }
    }

    private fun extractTmdbNumericId(rawId: String): Int? {
        if (rawId.isBlank()) return null
        val cleaned = rawId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")
            .removePrefix("tv:")
            .substringBefore(':')
            .trim()
        return cleaned.toIntOrNull()?.takeIf { it > 0 }
    }

    fun clear() {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }
}

internal data class SubtitleLoadingProgress(
    val total: Int,
    val completed: Int = 0,
    val addonName: String? = null,
)
