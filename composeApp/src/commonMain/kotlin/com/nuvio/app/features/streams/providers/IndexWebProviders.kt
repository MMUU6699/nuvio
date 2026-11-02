package com.nuvio.app.features.streams.providers

object IndexWebProviders {

    // 1. VidLink (Primary - Fast JWPlayer/HLS with Wyzie/OpenSubtitles Arabic support)
    fun vidLink(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://vidlink.pro/tv/$tmdbId/$season/$episode?player=jw&primaryColor=006fee&autoplay=true"
        } else {
            "https://vidlink.pro/movie/$tmdbId?player=jw&primaryColor=006fee&autoplay=true"
        }
        return BuiltInStream(
            providerId = "vidlink",
            providerName = "VidLink",
            name = "VidLink 1080p (سيرفر 1)",
            title = "VidLink FHD • متعدد الجودات والترجمة",
            description = "سيرفر سريع بجودة 1080p ودعم الترجمة العربية",
            url = url,
            headers = mapOf("Referer" to "https://vidlink.pro/"),
        )
    }

    // 2. VidFast (High-speed HLS multi-quality stream)
    fun vidFast(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://vidfast.pro/tv/$tmdbId/$season/$episode?autoPlay=true"
        } else {
            "https://vidfast.pro/movie/$tmdbId?autoPlay=true"
        }
        return BuiltInStream(
            providerId = "vidfast",
            providerName = "VidFast",
            name = "VidFast 1080p (سيرفر 2)",
            title = "VidFast Pro • بث فائق السرعة",
            description = "سيرفر مباشر متعدد الجودات بدون إعلانات",
            url = url,
            headers = mapOf("Referer" to "https://vidfast.pro/"),
        )
    }

    // 3. VidSrc PM (Active VidSrc mirror with default Arabic subtitle language hint)
    fun vidSrcPm(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://vidsrc.pm/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode&ds_lang=ar"
        } else {
            "https://vidsrc.pm/embed/movie?tmdb=$tmdbId&ds_lang=ar"
        }
        return BuiltInStream(
            providerId = "vidsrc_pm",
            providerName = "VidSrc PM",
            name = "VidSrc HD (سيرفر 3)",
            title = "VidSrc Cloud • ترجمة عربية",
            description = "سيرفر سحابي يدعم الترجمة العربية التلقائية",
            url = url,
            headers = mapOf("Referer" to "https://vidsrc.pm/"),
        )
    }

    // 4. AutoEmbed
    fun autoEmbed(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://autoembed.co/tv/tmdb/$tmdbId-$season-$episode"
        } else {
            "https://autoembed.co/movie/tmdb/$tmdbId"
        }
        return BuiltInStream(
            providerId = "autoembed",
            providerName = "AutoEmbed",
            name = "AutoEmbed (سيرفر 4)",
            title = "AutoEmbed • بث مباشر",
            description = "مزود تشغيل تلقائي مباشر",
            url = url,
            headers = mapOf("Referer" to "https://autoembed.co/"),
        )
    }

    // 5. VidSrc SU
    fun vidSrcSu(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://vidsrc.su/embed/tv/$tmdbId/$season/$episode"
        } else {
            "https://vidsrc.su/embed/movie/$tmdbId"
        }
        return BuiltInStream(
            providerId = "vidsrc_su",
            providerName = "VidSrc SU",
            name = "VidSrc SU (سيرفر 5)",
            title = "VidSrc SU • جودات متعددة",
            description = "سيرفر سحابي سريع عالي الدقة",
            url = url,
            headers = mapOf("Referer" to "https://vidsrc.su/"),
        )
    }

    // 6. SuperEmbed / MultiEmbed
    fun superEmbed(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
        } else {
            "https://multiembed.mov/?video_id=$tmdbId&tmdb=1"
        }
        return BuiltInStream(
            providerId = "superembed",
            providerName = "SuperEmbed",
            name = "SuperEmbed (سيرفر 6)",
            title = "SuperEmbed • متعدد المصادر",
            description = "يدعم مصادر بث متزامنة",
            url = url,
            headers = mapOf("Referer" to "https://multiembed.mov/"),
        )
    }

    // 7. NontonGo
    fun nontonGo(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://www.nontongo.win/embed/tv/$tmdbId/$season/$episode"
        } else {
            "https://www.nontongo.win/embed/movie/$tmdbId"
        }
        return BuiltInStream(
            providerId = "nontongo",
            providerName = "NontonGo",
            name = "NontonGo (سيرفر 7)",
            title = "NontonGo HD",
            description = "سيرفر عالمي سريع",
            url = url,
            headers = mapOf("Referer" to "https://www.nontongo.win/"),
        )
    }

    // 8. 2Embed (Fixed TV query string ?s=$season&e=$episode)
    fun twoEmbed(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://www.2embed.cc/embedtv/$tmdbId?s=$season&e=$episode"
        } else {
            "https://www.2embed.cc/embed/$tmdbId"
        }
        return BuiltInStream(
            providerId = "2embed",
            providerName = "2Embed",
            name = "2Embed (سيرفر 8)",
            title = "2Embed Server",
            description = "سيرفر بديل احتياطي",
            url = url,
            headers = mapOf("Referer" to "https://www.2embed.cc/"),
        )
    }

    // 9. SmashyStream
    fun smashyStream(tmdbId: String, season: Int? = null, episode: Int? = null): BuiltInStream {
        val url = if (season != null && episode != null) {
            "https://player.smashystream.com/tv/$tmdbId?s=$season&e=$episode"
        } else {
            "https://player.smashystream.com/movie/$tmdbId"
        }
        return BuiltInStream(
            providerId = "smashystream",
            providerName = "SmashyStream",
            name = "SmashyStream (سيرفر 9)",
            title = "SmashyStream Multi",
            description = "مزود تشغيل إضافي",
            url = url,
            headers = mapOf("Referer" to "https://player.smashystream.com/"),
        )
    }

    fun getAll(tmdbId: String, season: Int? = null, episode: Int? = null): List<BuiltInStream> = listOf(
        vidLink(tmdbId, season, episode),
        vidFast(tmdbId, season, episode),
        vidSrcPm(tmdbId, season, episode),
        autoEmbed(tmdbId, season, episode),
        vidSrcSu(tmdbId, season, episode),
        superEmbed(tmdbId, season, episode),
        nontonGo(tmdbId, season, episode),
        twoEmbed(tmdbId, season, episode),
        smashyStream(tmdbId, season, episode),
    )
}
