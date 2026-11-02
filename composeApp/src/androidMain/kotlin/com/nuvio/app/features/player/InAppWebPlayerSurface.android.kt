package com.nuvio.app.features.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.streams.providers.AdBlockEngine
import com.nuvio.app.features.streams.providers.ArabicProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_WEB_UA =
    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private val CROSS_ORIGIN_BRIDGE_JS = """
(function() {
    if (window.__nuvioBridgeInstalled) return;
    window.__nuvioBridgeInstalled = true;
    window.__nuvioLastVideoState = null;
    if (typeof window.__nuvioTargetQuality !== 'number') {
        window.__nuvioTargetQuality = 1080;
    }
    if (!window.__nuvioResizeMode) {
        window.__nuvioResizeMode = 'Fit';
    }

    function applyWebResizeMode(mode) {
        try {
            var objFit = 'contain';
            var scaleTransform = 'none';
            if (mode === 'Fill') {
                objFit = 'fill';
                scaleTransform = 'scale(1.0, 1.0)';
            } else if (mode === 'Zoom') {
                objFit = 'cover';
                scaleTransform = 'scale(1.18)';
            } else {
                objFit = 'contain';
                scaleTransform = 'none';
            }
            var css = 'video { object-fit: ' + objFit + ' !important; transform: ' + scaleTransform + ' !important; transform-origin: center center !important; width: 100vw !important; height: 100vh !important; }';
            var st = document.getElementById('nuvio-resize-style');
            if (!st) {
                st = document.createElement('style');
                st.id = 'nuvio-resize-style';
                if (document.head) document.head.appendChild(st);
            }
            if (st && st.textContent !== css) st.textContent = css;
            var vids = document.querySelectorAll('video');
            for (var i = 0; i < vids.length; i++) {
                vids[i].style.setProperty('object-fit', objFit, 'important');
                vids[i].style.setProperty('transform', scaleTransform, 'important');
                vids[i].style.setProperty('transform-origin', 'center center', 'important');
            }
        } catch(e) {}
    }

    function applyWebPlayerQuality(targetHeight) {
        try {
            if (typeof jwplayer === 'function') {
                var jw = jwplayer();
                if (jw && typeof jw.getQualityLevels === 'function') {
                    var levels = jw.getQualityLevels() || [];
                    if (targetHeight <= 0) {
                        jw.setCurrentQuality(0);
                    } else {
                        var bestIdx = -1;
                        var bestDiff = 99999;
                        for (var k = 0; k < levels.length; k++) {
                            var h = levels[k].height || parseInt((levels[k].label || '').replace(/\D/g, ''), 10) || 0;
                            if (h > 0) {
                                var diff = Math.abs(h - targetHeight);
                                if (diff < bestDiff) { bestDiff = diff; bestIdx = k; }
                            }
                        }
                        if (bestIdx < 0 && levels.length > 1) {
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
            var hlsInst = window.hls || window.hlsPlayer || (window.player && window.player.hls);
            if (hlsInst && hlsInst.levels) {
                if (targetHeight <= 0) {
                    hlsInst.currentLevel = -1;
                } else {
                    var bestL = -1;
                    var minDiff = 99999;
                    for (var l = 0; l < hlsInst.levels.length; l++) {
                        var lh = hlsInst.levels[l].height || 0;
                        if (lh > 0 && Math.abs(lh - targetHeight) < minDiff) {
                            minDiff = Math.abs(lh - targetHeight);
                            bestL = l;
                        }
                    }
                    if (bestL < 0 && hlsInst.levels.length > 0) {
                        bestL = hlsInst.levels.length - 1;
                    }
                    if (bestL >= 0 && hlsInst.currentLevel !== bestL) {
                        hlsInst.currentLevel = bestL;
                        hlsInst.nextLevel = bestL;
                        hlsInst.loadLevel = bestL;
                    }
                }
            }
        } catch(e) {}
    }

    // Receive video state in top frame from any nested cross-origin iframe
    window.addEventListener('message', function(ev) {
        try {
            var d = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data;
            if (!d) return;
            if (d.__nuvioVideoState) {
                window.__nuvioLastVideoState = d;
            }
            if (d.__nuvioCmd) {
                if (d.cmd === 'quality' && typeof d.val === 'number') {
                    window.__nuvioTargetQuality = d.val;
                    applyWebPlayerQuality(d.val);
                }
                if (d.cmd === 'resizeMode' && typeof d.val === 'string') {
                    window.__nuvioResizeMode = d.val;
                    applyWebResizeMode(d.val);
                }
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    var v = vids[i];
                    if (d.cmd === 'play') {
                        window.__nuvioUserPaused = false;
                        if (!window.__nuvioUserMuted) { v.muted = false; if (v.volume === 0) v.volume = 1.0; }
                        v.play().catch(function(){});
                    }
                    else if (d.cmd === 'pause') { window.__nuvioUserPaused = true; v.pause(); }
                    else if (d.cmd === 'seekTo' && typeof d.val === 'number') { v.currentTime = d.val; }
                    else if (d.cmd === 'seekBy' && typeof d.val === 'number') { v.currentTime = Math.max(0, v.currentTime + d.val); }
                    else if (d.cmd === 'speed' && typeof d.val === 'number') { v.playbackRate = d.val; }
                    else if (d.cmd === 'muted') { window.__nuvioUserMuted = !!d.val; v.muted = !!d.val; if (!d.val && v.volume === 0) v.volume = 1.0; }
                }
                // Forward command to child iframes
                if (window.frames) {
                    for (var f = 0; f < window.frames.length; f++) {
                        try { window.frames[f].postMessage(d, '*'); } catch(e) {}
                    }
                }
            }
        } catch(e) {}
    });

    function autoClickPlayButtons() {
        if (window.__nuvioUserPaused) return;
        var selectors = [
            '#pl_but', '#pl_but_background', '.vjs-big-play-button',
            '.jw-display-icon-container', '.jw-icon-display',
            '.plyr__control--overlaid', '.art-state', '.dplayer-play-icon',
            '.play-button', '.btn-play', '[aria-label*="Play" i]',
            '.loading_container svg', '#play-button'
        ];
        for (var s = 0; s < selectors.length; s++) {
            try {
                var els = document.querySelectorAll(selectors[s]);
                for (var j = 0; j < els.length; j++) {
                    els[j].click();
                }
            } catch(e) {}
        }
    }

    setInterval(function() {
        try {
            applyWebPlayerQuality(window.__nuvioTargetQuality || 1080);
            applyWebResizeMode(window.__nuvioResizeMode || 'Fit');
            var v = document.querySelector('video');
            if (v) {
                v.controls = false;
                v.removeAttribute('controls');
                v.setAttribute('playsinline', 'true');
                v.setAttribute('webkit-playsinline', 'true');
                if (!window.__nuvioUserMuted) {
                    if (v.muted) v.muted = false;
                    if (v.volume === 0) v.volume = 1.0;
                    try {
                        if (typeof jwplayer === 'function') {
                            var jw = jwplayer();
                            if (jw && typeof jw.setMute === 'function') { jw.setMute(false); jw.setVolume(100); }
                        }
                    } catch(e) {}
                }
                if (window.__nuvioUserPaused) {
                    if (!v.paused) v.pause();
                } else if (v.paused && v.readyState >= 2 && !v.ended) {
                    v.play().catch(function(){});
                }
                var stateMsg = {
                    __nuvioVideoState: true,
                    found: true,
                    paused: v.paused,
                    currentTime: v.currentTime || 0,
                    duration: (isNaN(v.duration) || !isFinite(v.duration)) ? 0 : v.duration,
                    ended: !!v.ended,
                    videoWidth: v.videoWidth || 1920,
                    videoHeight: v.videoHeight || 1080
                };
                window.__nuvioLastVideoState = stateMsg;
                try { window.top.postMessage(stateMsg, '*'); } catch(e) {}
            } else {
                autoClickPlayButtons();
            }
        } catch(e) {}
    }, 300);
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppWebPlayerSurface(
    sourceUrl: String,
    sourceHeaders: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    initialPositionMs: Long? = null,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    onStreamExtracted: (url: String, headers: Map<String, String>, streamType: String) -> Unit = { _, _, _ -> },
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnStreamExtracted = rememberUpdatedState(onStreamExtracted)
    val streamHandedOff = remember(sourceUrl) { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var userPaused by remember { mutableStateOf(!playWhenReady) }
    var isVideoPlaying by remember { mutableStateOf(false) }
    var hasConfirmedVideoPlayback by remember(sourceUrl) { mutableStateOf(false) }
    var currentDurationMs by remember { mutableStateOf(0L) }
    var currentPosMs by remember { mutableStateOf(initialPositionMs ?: 0L) }
    var selectedQualityHeight by remember { mutableStateOf(1080) }

    // External Subtitle rendering state
    val addonSubtitles by SubtitleRepository.addonSubtitles.collectAsStateWithLifecycle()
    var activeSubtitleCues by remember { mutableStateOf<List<SubtitleSyncCue>>(emptyList()) }
    var activeCueText by remember { mutableStateOf<String?>(null) }

    fun sendBridgeCommand(cmd: String, value: Any? = null) {
        val valJson = when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
        webViewRef?.evaluateJavascript(
            """
            (function() {
                var msg = { __nuvioCmd: true, cmd: '$cmd', val: $valJson };
                window.postMessage(msg, '*');
                if (window.frames) {
                    for (var i = 0; i < window.frames.length; i++) {
                        try { window.frames[i].postMessage(msg, '*'); } catch(e) {}
                    }
                }
            })();
            """.trimIndent(),
            null
        )
    }

    val controller = remember(sourceUrl) {
        object : PlayerEngineController {
            override fun play() {
                userPaused = false
                isVideoPlaying = true
                sendBridgeCommand("play")
                latestOnSnapshot.value(
                    PlayerPlaybackSnapshot(
                        positionMs = currentPosMs,
                        durationMs = currentDurationMs,
                        bufferedPositionMs = currentDurationMs,
                        isPlaying = true,
                        isLoading = !hasConfirmedVideoPlayback,
                        isEnded = false,
                        playbackSpeed = 1.0f,
                    )
                )
            }

            override fun pause() {
                userPaused = true
                isVideoPlaying = false
                sendBridgeCommand("pause")
                latestOnSnapshot.value(
                    PlayerPlaybackSnapshot(
                        positionMs = currentPosMs,
                        durationMs = currentDurationMs,
                        bufferedPositionMs = currentDurationMs,
                        isPlaying = false,
                        isLoading = false,
                        isEnded = false,
                        playbackSpeed = 1.0f,
                    )
                )
            }

            override fun seekTo(positionMs: Long) {
                val targetMs = positionMs.coerceAtLeast(0L)
                currentPosMs = targetMs
                sendBridgeCommand("seekTo", targetMs / 1000.0)
            }

            override fun seekBy(offsetMs: Long) {
                val targetMs = (currentPosMs + offsetMs).coerceAtLeast(0L)
                currentPosMs = targetMs
                sendBridgeCommand("seekBy", offsetMs / 1000.0)
            }

            override fun retry() {
                webViewRef?.reload()
            }

            override fun setPlaybackSpeed(speed: Float) {
                sendBridgeCommand("speed", speed)
            }

            override fun setMuted(muted: Boolean) {
                sendBridgeCommand("muted", muted)
            }

            override fun getAudioTracks(): List<AudioTrack> = emptyList()

            // Return empty built-in tracks so addonSubtitles are shown cleanly in the Addon section
            override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()

            override fun getVideoQualityTracks(): List<VideoQualityTrack> =
                defaultVideoQualityOptions(selectedQualityHeight)

            override fun selectVideoQuality(maxHeight: Int) {
                selectedQualityHeight = maxHeight
                sendBridgeCommand("quality", maxHeight)
            }

            override fun applyAudioLanguagePreferences(languages: List<String>) {}
            override fun selectAudioTrack(index: Int) {}
            override fun selectSubtitleTrack(index: Int) {
                if (index < 0) {
                    clearExternalSubtitle()
                }
            }

            override fun setSubtitleUri(url: String) {
                coroutineScope.launch {
                    val content = withContext(Dispatchers.IO) {
                        // Try without embed headers first (OpenSubtitles / Wyzie reject foreign Referers)
                        runCatching {
                            httpGetTextWithHeaders(url = url, headers = emptyMap())
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                            ?: runCatching {
                                httpGetTextWithHeaders(url = url, headers = sourceHeaders)
                            }.getOrNull()
                    }
                    if (!content.isNullOrBlank()) {
                        activeSubtitleCues = PlayerSubtitleCueParser.parse(content, url)
                    }
                }
            }

            override fun clearExternalSubtitle() {
                activeSubtitleCues = emptyList()
                activeCueText = null
            }

            override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                clearExternalSubtitle()
            }
        }
    }

    LaunchedEffect(controller) {
        onControllerReady(controller)
    }

    // Auto-load Arabic subtitle ONLY when a verified Arabic subtitle is present
    LaunchedEffect(addonSubtitles, externalSubtitles) {
        val arabicAddonSub = addonSubtitles.firstOrNull { s ->
            val lang = s.language.lowercase()
            lang == "ar" || lang == "ara" || lang == "arabic"
        }

        if (arabicAddonSub != null) {
            controller.setSubtitleUri(arabicAddonSub.url)
            return@LaunchedEffect
        }

        val target = externalSubtitles.firstOrNull { s ->
            val lang = s.language.lowercase()
            lang == "ar" || lang == "ara" || lang == "arabic"
        }

        if (target != null) {
            controller.setSubtitleUri(target.url)
        }
    }

    // Aspect ratio & resize mode injection across top document and all nested iframes
    LaunchedEffect(resizeMode) {
        sendBridgeCommand("resizeMode", resizeMode.name)
        val objFit = when (resizeMode) {
            PlayerResizeMode.Fill -> "fill"
            PlayerResizeMode.Zoom -> "cover"
            PlayerResizeMode.Fit -> "contain"
        }
        val scaleTransform = when (resizeMode) {
            PlayerResizeMode.Zoom -> "scale(1.18)"
            else -> "none"
        }
        webViewRef?.evaluateJavascript(
            """
            (function() {
                window.__nuvioResizeMode = '${resizeMode.name}';
                var css = 'video { object-fit: $objFit !important; transform: $scaleTransform !important; transform-origin: center center !important; width: 100vw !important; height: 100vh !important; }';
                var st = document.getElementById('nuvio-resize-style');
                if (!st) {
                    st = document.createElement('style');
                    st.id = 'nuvio-resize-style';
                    if (document.head) document.head.appendChild(st);
                }
                if (st) st.textContent = css;
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    vids[i].style.setProperty('object-fit', '$objFit', 'important');
                    vids[i].style.setProperty('transform', '$scaleTransform', 'important');
                    vids[i].style.setProperty('transform-origin', 'center center', 'important');
                }
            })();
            """.trimIndent(),
            null
        )
    }

    // Continuous sterilization, parked-domain detection, and cross-origin playback state polling
    LaunchedEffect(sourceUrl) {
        val startedAtMs = System.currentTimeMillis()
        while (isActive && !streamHandedOff.get()) {
            delay(350L)
            webViewRef?.evaluateJavascript(AdBlockEngine.CLEAN_PLAYER_JS, null)
            webViewRef?.evaluateJavascript(CROSS_ORIGIN_BRIDGE_JS, null)

            if (userPaused) {
                sendBridgeCommand("pause")
            }

            webViewRef?.evaluateJavascript(
                """
                (function() {
                    var bodyText = (document.body && document.body.innerText) ? document.body.innerText.toLowerCase() : '';
                    if (bodyText.indexOf('domain has expired') !== -1 ||
                        bodyText.indexOf('namesilo') !== -1 ||
                        bodyText.indexOf('looking for a domain') !== -1 ||
                        bodyText.indexOf('sedoparking') !== -1) {
                        return JSON.stringify({ parked: true });
                    }
                    if (window.__nuvioLastVideoState && window.__nuvioLastVideoState.found) {
                        return JSON.stringify(window.__nuvioLastVideoState);
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        return JSON.stringify({
                            found: true,
                            paused: v.paused,
                            currentTime: v.currentTime || 0,
                            duration: (isNaN(v.duration) || !isFinite(v.duration)) ? 0 : v.duration,
                            ended: !!v.ended
                        });
                    }
                    return JSON.stringify({ found: false });
                })();
                """.trimIndent()
            ) { resultJson ->
                try {
                    val raw = resultJson?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")
                    if (!raw.isNullOrBlank() && raw != "null") {
                        val obj = JSONObject(raw)
                        if (obj.optBoolean("parked", false)) {
                            latestOnError.value("Dead or parked provider domain")
                            return@evaluateJavascript
                        }
                        if (obj.optBoolean("found", false)) {
                            val paused = obj.optBoolean("paused", true)
                            val sec = obj.optDouble("currentTime", 0.0)
                            val durSec = obj.optDouble("duration", 0.0)
                            val ended = obj.optBoolean("ended", false)

                            if (durSec > 0.5 || sec > 0.1) {
                                hasConfirmedVideoPlayback = true
                            }
                            isVideoPlaying = if (userPaused) false else (!paused && !ended)
                            currentPosMs = (sec * 1000).toLong()
                            if (durSec > 0.0) {
                                currentDurationMs = (durSec * 1000).toLong()
                            }

                            activeCueText = if (activeSubtitleCues.isNotEmpty()) {
                                activeSubtitleCues.firstOrNull { cue ->
                                    currentPosMs in cue.startTimeMs..cue.endTimeMs
                                }?.text
                            } else null

                            latestOnSnapshot.value(
                                PlayerPlaybackSnapshot(
                                    positionMs = currentPosMs,
                                    durationMs = currentDurationMs,
                                    bufferedPositionMs = currentDurationMs,
                                    isPlaying = isVideoPlaying,
                                    isLoading = !hasConfirmedVideoPlayback,
                                    isEnded = ended,
                                    playbackSpeed = 1.0f,
                                )
                            )
                        } else if (!hasConfirmedVideoPlayback && System.currentTimeMillis() - startedAtMs > 18_000L) {
                            latestOnError.value("Timed out waiting for stream from provider")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
    ) {
        AndroidView(
            // Keep WebView invisible until actual video playback is confirmed (if not already handed off to ExoPlayer)
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (hasConfirmedVideoPlayback) 1f else 0f),
            factory = { context ->
                object : WebView(context) {
                    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean = false
                    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false
                }.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.BLACK)
                    isFocusable = false
                    isClickable = false

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        allowFileAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        userAgentString = DEFAULT_WEB_UA
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean = false
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return true
                            if (AdBlockEngine.isAdUrl(url)) return true
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            if (AdBlockEngine.isAdUrl(url)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            val lowerUrl = url.lowercase()

                            // 1. Intercept external subtitles (.vtt / .srt) loaded by the embed player
                            if ((lowerUrl.contains(".vtt") || lowerUrl.contains(".srt")) &&
                                !lowerUrl.endsWith(".js") && !lowerUrl.endsWith(".css")
                            ) {
                                SubtitleRepository.addInterceptedSubtitle(url)
                            }

                            // 2. Intercept direct HLS (.m3u8) or MP4 (.mp4) streams and hand off to Native ExoPlayer!
                            // Ignore demuxed sub-manifests or low-res variant child playlists so ExoPlayer always receives a complete/master stream with 1080p + audio
                            val isSubPlaylistWithoutAudio = lowerUrl.contains("index-v") ||
                                lowerUrl.contains("video_") ||
                                lowerUrl.contains("/video/") && !lowerUrl.contains("master") ||
                                lowerUrl.contains("audio_") ||
                                lowerUrl.contains("-a1.m3u8") ||
                                lowerUrl.contains("-v1.m3u8") ||
                                lowerUrl.contains("chunklist") ||
                                lowerUrl.contains("level_0") ||
                                lowerUrl.contains("/240/") || lowerUrl.contains("240p") ||
                                lowerUrl.contains("/360/") || lowerUrl.contains("360p") ||
                                lowerUrl.contains("/480/") || lowerUrl.contains("480p")
                            val isHlsManifest = (lowerUrl.contains(".m3u8") || lowerUrl.contains("/hls/") && lowerUrl.endsWith("master.txt")) &&
                                !isSubPlaylistWithoutAudio &&
                                !lowerUrl.endsWith(".ts") && !lowerUrl.endsWith(".js") && !lowerUrl.endsWith(".json")
                            val isDirectMp4 = (lowerUrl.endsWith(".mp4") || lowerUrl.contains(".mp4?")) &&
                                !lowerUrl.contains("blank.mp4") && !lowerUrl.contains("preroll") && !lowerUrl.contains("ad_") && !lowerUrl.contains("intro.mp4")

                            if ((isHlsManifest || isDirectMp4) && streamHandedOff.compareAndSet(false, true)) {
                                val reqHeaders = LinkedHashMap<String, String>()
                                request.requestHeaders?.forEach { (k, v) ->
                                    if (!k.equals("Range", ignoreCase = true) &&
                                        !k.equals("Accept-Encoding", ignoreCase = true)
                                    ) {
                                        reqHeaders[k] = v
                                    }
                                }
                                if (!reqHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) {
                                    reqHeaders["Referer"] = sourceUrl
                                }
                                if (!reqHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                                    reqHeaders["User-Agent"] = DEFAULT_WEB_UA
                                }
                                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                                if (!cookie.isNullOrBlank()) {
                                    reqHeaders["Cookie"] = cookie
                                }
                                val detectedType = if (isHlsManifest) "hls" else "mp4"
                                mainHandler.post {
                                    view?.stopLoading()
                                    latestOnStreamExtracted.value(url, reqHeaders, detectedType)
                                }
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            // 3. Intercept ONLY HTML navigation documents (main frame or iframe document requests)
                            // Never intercept XHR/fetch API requests, JSON endpoints, or media segments!
                            val acceptHeader = request.requestHeaders?.entries
                                ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                                ?.value?.lowercase().orEmpty()
                            val secFetchDest = request.requestHeaders?.entries
                                ?.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
                                ?.value?.lowercase().orEmpty()
                            val isHtmlDocumentRequest = request.isForMainFrame ||
                                secFetchDest == "iframe" ||
                                secFetchDest == "document" ||
                                acceptHeader.contains("text/html") ||
                                acceptHeader.contains("application/xhtml+xml")

                            val path = request.url?.path?.lowercase().orEmpty()
                            val isStaticAsset = path.endsWith(".js") || path.endsWith(".css") ||
                                path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".m4s") ||
                                path.endsWith(".mp4") || path.endsWith(".png") || path.endsWith(".jpg") ||
                                path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".svg") ||
                                path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                                path.endsWith(".json") || path.endsWith(".vtt") || path.endsWith(".srt")

                            if (request.method.equals("GET", ignoreCase = true) && isHtmlDocumentRequest && !isStaticAsset) {
                                try {
                                    val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                                    val cookieHeaders = if (!cookie.isNullOrBlank()) mapOf("Cookie" to cookie) else emptyMap()
                                    val response = runBlocking {
                                        httpRequestRaw(
                                            method = "GET",
                                            url = url,
                                            headers = (request.requestHeaders ?: emptyMap()) + mapOf(
                                                "User-Agent" to DEFAULT_WEB_UA
                                            ) + cookieHeaders,
                                            body = "",
                                            followRedirects = true,
                                            maxResponseBodyBytes = 1024 * 1024,
                                        )
                                    }
                                    val contentType = response.headers["content-type"]
                                        ?: response.headers["Content-Type"]
                                        ?: "text/html"
                                    if (response.status in 200..399 && contentType.contains("html", ignoreCase = true)) {
                                        val setCookie = response.headers["set-cookie"] ?: response.headers["Set-Cookie"]
                                        if (!setCookie.isNullOrBlank()) {
                                            runCatching { CookieManager.getInstance().setCookie(url, setCookie) }
                                        }

                                        // Check if the HTML itself contains a packed or direct .m3u8/.mp4 stream!
                                        val unpackedUrl = ArabicProviders.extractDirectMediaFromHtml(response.body)
                                        if (!unpackedUrl.isNullOrBlank() && streamHandedOff.compareAndSet(false, true)) {
                                            val streamType = if (unpackedUrl.contains(".m3u8", ignoreCase = true)) "hls" else "mp4"
                                            val unpackedHeaders = mapOf(
                                                "Referer" to url,
                                                "User-Agent" to DEFAULT_WEB_UA,
                                            ) + cookieHeaders
                                            mainHandler.post {
                                                view?.stopLoading()
                                                latestOnStreamExtracted.value(unpackedUrl, unpackedHeaders, streamType)
                                            }
                                            return WebResourceResponse(
                                                "text/html",
                                                "UTF-8",
                                                ByteArrayInputStream(ByteArray(0))
                                            )
                                        }

                                        val injectedHead = buildString {
                                            append("<style id=\"nuvio-clean-player-injected\">")
                                            append(AdBlockEngine.CLEAN_PLAYER_CSS)
                                            append("</style>")
                                            append("<script id=\"nuvio-iframe-bridge\">")
                                            append(CROSS_ORIGIN_BRIDGE_JS)
                                            append("</script>")
                                        }
                                        val modifiedHtml = when {
                                            response.body.contains("</head>", ignoreCase = true) ->
                                                response.body.replace("</head>", "$injectedHead</head>", ignoreCase = true)
                                            response.body.contains("<body", ignoreCase = true) ->
                                                response.body.replace("<body", "$injectedHead<body", ignoreCase = true)
                                            else -> injectedHead + response.body
                                        }
                                        val mimeType = contentType.substringBefore(";").trim()
                                        val encoding = if (contentType.contains("charset=", ignoreCase = true)) {
                                            contentType.substringAfter("charset=").substringBefore(";").trim()
                                        } else "UTF-8"

                                        return WebResourceResponse(
                                            mimeType,
                                            encoding,
                                            response.status,
                                            "OK",
                                            response.headers,
                                            ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
                                        )
                                    }
                                } catch (_: Exception) {}
                            }

                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(AdBlockEngine.CLEAN_PLAYER_JS, null)
                            view?.evaluateJavascript(CROSS_ORIGIN_BRIDGE_JS, null)

                            if (initialPositionMs != null && initialPositionMs > 0) {
                                sendBridgeCommand("seekTo", initialPositionMs / 1000.0)
                            }
                        }
                    }

                    val reqHeaders = HashMap<String, String>()
                    sourceHeaders.forEach { (k, v) -> reqHeaders[k] = v }
                    loadUrl(sourceUrl, reqHeaders)
                    webViewRef = this
                }
            },
            update = { webView ->
                webViewRef = webView
            }
        )

        // Native Arabic Subtitle overlay when playing inside WebView fallback
        activeCueText?.takeIf { it.isNotBlank() && hasConfirmedVideoPlayback }?.let { cue ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp, start = 28.dp, end = 28.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = cue,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp,
                )
            }
        }

        // Transparent overlay so touches pass exclusively to Nuvio native controls
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Transparent)
        )
    }

    DisposableEffect(sourceUrl) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.loadUrl("about:blank")
            webViewRef?.destroy()
            webViewRef = null
        }
    }
}
