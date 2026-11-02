package com.nuvio.app.features.streams.providers

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamSubtitle

enum class ProviderCategory {
    GLOBAL,
    ARABIC,
    ANIME,
}

data class BuiltInStream(
    val providerId: String,
    val providerName: String,
    val name: String,
    val title: String,
    val description: String? = null,
    val url: String,
    val streamType: String = "embed",
    val category: ProviderCategory = ProviderCategory.GLOBAL,
    val quality: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<StreamSubtitle> = emptyList(),
) {
    fun toStreamItem(): StreamItem = StreamItem(
        name = name,
        title = title,
        description = description ?: "$providerName ($streamType)",
        url = url,
        addonName = providerName,
        addonId = "builtin:$providerId",
        streamType = streamType,
        behaviorHints = StreamBehaviorHints(
            proxyHeaders = StreamProxyHeaders(request = headers),
        ),
        externalSubtitles = subtitles,
    )
}
