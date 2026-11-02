package com.nuvio.app.features.streams.providers

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object AnimeProviders {

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun resolveStreams(
        title: String,
        arabicTitle: String? = null,
        episode: Int? = null,
    ): List<BuiltInStream> = coroutineScope {
        val query = (title.takeIf { it.isNotBlank() } ?: arabicTitle.orEmpty()).trim()
        if (query.isBlank()) return@coroutineScope emptyList()

        val encoded = ArabicProviders.encodeUrlParam(query)
        val baseUrl = "https://anime4up.rest"
        val searchUrl = "$baseUrl/?search_param=animes&s=$encoded"

        val searchHtml = fetchHtml(searchUrl, "$baseUrl/") ?: return@coroutineScope emptyList()
        if (ArabicProviders.isParkedOrChallengePage(searchHtml)) return@coroutineScope emptyList()

        val animeLinkRegex = Regex("""href=["'](https?://[^"']+/anime/[^"']+)["']""", RegexOption.IGNORE_CASE)
        val animeUrl = animeLinkRegex.find(searchHtml)?.groupValues?.getOrNull(1) ?: return@coroutineScope emptyList()

        val animeHtml = fetchHtml(animeUrl, searchUrl) ?: return@coroutineScope emptyList()
        val epNum = episode ?: 1
        val epLinkRegex = Regex(
            """href=["'](https?://[^"']+/episode/[^"']*?(?:%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9|episode)-0*$epNum(?:/|["']))""",
            RegexOption.IGNORE_CASE,
        )
        val fallbackEpLinkRegex = Regex("""href=["'](https?://[^"']+/episode/[^"']+)["']""", RegexOption.IGNORE_CASE)

        val epUrl = epLinkRegex.find(animeHtml)?.groupValues?.getOrNull(1)?.trimEnd('"', '\'')
            ?: fallbackEpLinkRegex.findAll(animeHtml).map { it.groupValues[1] }.firstOrNull()
            ?: return@coroutineScope emptyList()

        val epHtml = fetchHtml(epUrl, animeUrl) ?: return@coroutineScope emptyList()
        val embeds = ArabicProviders.extractEmbedIframeUrls(epHtml, baseUrl)

        embeds.take(4).mapIndexed { index, embedUrl ->
            async {
                ArabicProviders.resolveEmbedToBuiltInStreams(
                    providerId = "anime4up_${index + 1}",
                    providerLabel = "Anime4Up (أنمي مترجم)",
                    itemTitle = "$query - الحلقة $epNum",
                    embedUrl = embedUrl,
                    referer = epUrl,
                    serverIndex = index + 1,
                ).map { it.copy(category = ProviderCategory.ANIME) }
            }
        }.awaitAll().flatten()
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
                    ),
                    body = "",
                    followRedirects = true,
                    maxResponseBodyBytes = 256 * 1024,
                )
            }
            if (resp != null && resp.status in 200..399 && resp.body.isNotBlank()) resp.body else null
        } catch (_: Exception) {
            null
        }
}
