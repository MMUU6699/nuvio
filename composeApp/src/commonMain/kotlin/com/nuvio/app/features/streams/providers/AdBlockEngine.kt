package com.nuvio.app.features.streams.providers

object AdBlockEngine {

    private val adDomainKeywords = setOf(
        "doubleclick",
        "googlesyndication",
        "adservice",
        "popads",
        "popcash",
        "adcash",
        "propellerads",
        "exoclick",
        "juicyads",
        "trafficjunky",
        "onclick",
        "syndication",
        "adnxs",
        "scorecardresearch",
        "outbrain",
        "taboola",
        "adsterra",
        "clickadu",
        "monetag",
        "hilltopads",
        "richpush",
        "a-ads",
        "bet365",
        "1xbet",
        "melbet",
        "mostbet",
        "parimatch",
        "adsupply",
        "bidgear",
        "revcontent",
        "mgid",
        "yandex.ru/ads",
        "aniview",
        "primis",
        "connatix",
        "criteo",
        "adroll",
        "adsystem",
        "advert",
        "trackers",
        "analytics",
    )

    fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return adDomainKeywords.any { lower.contains(it) }
    }

    const val CLEAN_PLAYER_CSS = """
        html, body, #app, #root, .container, .wrapper, .player-container, .player, [class*="player"], [id*="player"] {
            margin: 0 !important;
            padding: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            max-width: 100vw !important;
            max-height: 100vh !important;
            overflow: hidden !important;
            background-color: #000000 !important;
            user-select: none !important;
            -webkit-user-select: none !important;
        }
        .jw-controls, .jw-controlbar, .jw-display-icon-container, .jw-settings-menu, .jw-title, .jw-preview,
        .vjs-control-bar, .vjs-big-play-button, .vjs-loading-spinner, .vjs-modal-dialog, .vjs-poster,
        .plyr__controls, .plyr__control, .plyr__poster, .plyr--video .plyr__controls,
        #servers, .servers, .server-list, .server-item, .servers-list, .server_item, .server-btn,
        .player-controls, .ctrl-bar, .controls-overlay, .video-controls,
        .art-controls, .art-bottom, .art-layers, .art-contextmenu,
        .dplayer-controller, .dplayer-controller-mask, .dplayer-notice, .dplayer-menu,
        [class*="vidapi"], [id*="vidapi"], [class*="vid-api"],
        [class*="toolbar"], [class*="control-bar"], [class*="controlBar"], [class*="controls"]:not([class*="caption"]):not([class*="subtitle"]),
        [class*="Controls"]:not([class*="caption"]):not([class*="subtitle"]), [id*="controls"], [id*="Controls"],
        [class*="playbar"], [class*="play-bar"], [class*="bottom-bar"], [class*="bottomBar"],
        [class*="progress"]:not(body), [class*="scrubber"], [class*="timeline"],
        [class*="time"]:not([class*="subtitle"]):not([class*="caption"]),
        [class*="volume"], [class*="sound"], [class*="mute"],
        [class*="pip"], [class*="cast"], [class*="airplay"],
        [class*="watermark"], [class*="logo"], [class*="brand"], [id*="watermark"], [id*="logo"],
        [class*="title"]:not([class*="caption"]):not([class*="sub"]), header, .header, .top-bar, [class*="topbar"],
        [class*="setting"], [class*="gear"], [class*="fullscreen"]:not(video),
        video::-webkit-media-controls, video::-webkit-media-controls-enclosure, video::-webkit-media-controls-panel,
        a[href*="bet"], a[href*="casino"], .adsbygoogle {
            display: none !important;
            opacity: 0 !important;
            visibility: hidden !important;
            pointer-events: none !important;
            height: 0 !important;
            width: 0 !important;
            max-height: 0 !important;
            max-width: 0 !important;
        }
        iframe {
            position: fixed !important;
            top: 0 !important;
            left: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            max-width: 100vw !important;
            max-height: 100vh !important;
            border: none !important;
            outline: none !important;
            z-index: 10 !important;
        }
        video {
            position: fixed !important;
            top: 0 !important;
            left: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            max-width: 100vw !important;
            max-height: 100vh !important;
            z-index: 2147483640 !important;
            background: #000000 !important;
        }
    """

    const val CLEAN_PLAYER_JS = """
        (function() {
            var css = `
                html, body, #app, #root, .container, .wrapper, .player-container, .player, [class*="player"], [id*="player"] {
                    margin: 0 !important; padding: 0 !important;
                    width: 100vw !important; height: 100vh !important;
                    max-width: 100vw !important; max-height: 100vh !important;
                    overflow: hidden !important; background-color: #000000 !important;
                    user-select: none !important; -webkit-user-select: none !important;
                }
                .jw-controls, .jw-controlbar, .jw-display-icon-container, .jw-settings-menu, .jw-title, .jw-preview,
                .vjs-control-bar, .vjs-big-play-button, .vjs-loading-spinner, .vjs-modal-dialog, .vjs-poster,
                .plyr__controls, .plyr__control, .plyr__poster, .plyr--video .plyr__controls,
                #servers, .servers, .server-list, .server-item, .servers-list, .server_item, .server-btn,
                .player-controls, .ctrl-bar, .controls-overlay, .video-controls,
                .art-controls, .art-bottom, .art-layers, .art-contextmenu,
                .dplayer-controller, .dplayer-controller-mask, .dplayer-notice, .dplayer-menu,
                [class*="vidapi"], [id*="vidapi"], [class*="vid-api"],
                [class*="toolbar"], [class*="control-bar"], [class*="controlBar"], [class*="controls"]:not([class*="caption"]):not([class*="subtitle"]),
                [class*="Controls"]:not([class*="caption"]):not([class*="subtitle"]), [id*="controls"], [id*="Controls"],
                [class*="playbar"], [class*="play-bar"], [class*="bottom-bar"], [class*="bottomBar"],
                [class*="progress"]:not(body), [class*="scrubber"], [class*="timeline"],
                [class*="time"]:not([class*="subtitle"]):not([class*="caption"]),
                [class*="volume"], [class*="sound"], [class*="mute"],
                [class*="pip"], [class*="cast"], [class*="airplay"],
                [class*="watermark"], [class*="logo"], [class*="brand"], [id*="watermark"], [id*="logo"],
                [class*="title"]:not([class*="caption"]):not([class*="sub"]), header, .header, .top-bar, [class*="topbar"],
                [class*="setting"], [class*="gear"], [class*="fullscreen"]:not(video),
                video::-webkit-media-controls, video::-webkit-media-controls-enclosure, video::-webkit-media-controls-panel,
                a[href*="bet"], a[href*="casino"], .adsbygoogle {
                    display: none !important; opacity: 0 !important;
                    visibility: hidden !important; pointer-events: none !important;
                    height: 0 !important; width: 0 !important;
                }
                iframe {
                    position: fixed !important; top: 0 !important; left: 0 !important;
                    width: 100vw !important; height: 100vh !important;
                    max-width: 100vw !important; max-height: 100vh !important;
                    border: none !important; outline: none !important;
                    z-index: 10 !important;
                }
                video {
                    position: fixed !important; top: 0 !important; left: 0 !important;
                    width: 100vw !important; height: 100vh !important;
                    max-width: 100vw !important; max-height: 100vh !important;
                    z-index: 2147483640 !important;
                    background: #000000 !important;
                }
            `;

            function sterilizeDoc(d) {
                if (!d) return;
                try {
                    var existing = d.getElementById('nuvio-clean-css');
                    if (!existing && d.head) {
                        var st = d.createElement('style');
                        st.id = 'nuvio-clean-css';
                        st.textContent = css;
                        d.head.appendChild(st);
                    }
                    var vids = d.querySelectorAll('video');
                    for (var i = 0; i < vids.length; i++) {
                        var v = vids[i];
                        v.controls = false;
                        v.removeAttribute('controls');

                        // Disable native video textTracks so our native Arabic subtitle overlay renders cleanly without foreign overlaps
                        if (v.textTracks && v.textTracks.length > 0) {
                            for (var t = 0; t < v.textTracks.length; t++) {
                                try {
                                    v.textTracks[t].mode = 'disabled';
                                } catch(e) {}
                            }
                        }
                    }

                    // Auto-select highest quality (1080p) when user has not explicitly picked a lower quality
                    var targetH = window.__nuvioTargetQuality || 1080;
                    try {
                        if (window.jwplayer && typeof window.jwplayer === 'function') {
                            var jw = window.jwplayer();
                            var levels = jw && jw.getQualityLevels ? jw.getQualityLevels() : null;
                            if (levels && levels.length > 0) {
                                if (targetH <= 0) {
                                    jw.setCurrentQuality(0);
                                } else {
                                    var bestIdx = -1;
                                    var bestScore = -1;
                                    for (var k = 0; k < levels.length; k++) {
                                        var h = levels[k].height || parseInt((levels[k].label || '').replace(/\D/g, ''), 10) || 0;
                                        var br = levels[k].bitrate || 0;
                                        if (h > 0 && h <= targetH && h > bestScore) {
                                            bestScore = h;
                                            bestIdx = k;
                                        } else if (bestIdx < 0 && br > bestScore) {
                                            bestScore = br;
                                            bestIdx = k;
                                        }
                                    }
                                    if (bestIdx < 0 && levels.length > 1) {
                                        // In JWPlayer, index 0 is Auto and index 1 is typically the highest resolution (1080p)
                                        bestIdx = 1;
                                    }
                                    if (bestIdx >= 0 && jw.getCurrentQuality && jw.getCurrentQuality() !== bestIdx) {
                                        jw.setCurrentQuality(bestIdx);
                                    }
                                }
                            }
                        }
                    } catch(e) {}

                    try {
                        if (window.videojs && typeof window.videojs.getAllPlayers === 'function') {
                            var players = window.videojs.getAllPlayers();
                            if (players && players.length > 0 && players[0].qualityLevels) {
                                var qls = players[0].qualityLevels();
                                if (qls && qls.length > 0) {
                                    var maxH = 0;
                                    for (var q0 = 0; q0 < qls.length; q0++) {
                                        var qh = qls[q0].height || 0;
                                        if (qh > maxH && (targetH <= 0 || qh <= targetH)) maxH = qh;
                                    }
                                    for (var q = 0; q < qls.length; q++) {
                                        qls[q].enabled = (maxH <= 0) ? true : ((qls[q].height || 0) === maxH);
                                    }
                                }
                            }
                        }
                    } catch(e) {}

                    var iframes = d.querySelectorAll('iframe');
                    for (var j = 0; j < iframes.length; j++) {
                        try {
                            var idoc = iframes[j].contentDocument || iframes[j].contentWindow.document;
                            if (idoc) sterilizeDoc(idoc);
                        } catch(err) {}
                    }
                } catch(e) {}
            }

            try {
                window.open = function() { return null; };
                window.alert = function() {};
                window.confirm = function() { return false; };
                sterilizeDoc(document);
            } catch(e) {}
        })();
    """
}
