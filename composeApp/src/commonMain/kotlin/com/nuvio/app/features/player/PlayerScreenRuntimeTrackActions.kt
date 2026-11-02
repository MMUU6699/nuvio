package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem

internal fun StreamItem.detectResolutionHeight(): Int {
    for (badge in badges) {
        val b = badge.name.trim().lowercase()
        when {
            b == "4k" || b == "2160p" || b == "uhd" -> return 2160
            b == "1440p" || b == "2k" || b == "qhd" -> return 1440
            b == "1080p" || b == "fhd" -> return 1080
            b == "720p" || b == "hd" -> return 720
            b == "480p" || b == "sd" -> return 480
            b == "360p" -> return 360
        }
    }
    val combinedText = buildString {
        name?.let { append(it).append(' ') }
        title?.let { append(it).append(' ') }
        description?.let { append(it).append(' ') }
        behaviorHints.filename?.let { append(it).append(' ') }
        url?.let { append(it) }
    }.lowercase()

    return when {
        Regex("""\b(?:2160p|4k|uhd)\b""").containsMatchIn(combinedText) -> 2160
        Regex("""\b(?:1440p|2k|qhd)\b""").containsMatchIn(combinedText) -> 1440
        Regex("""\b(?:1080p|fhd|1920x1080)\b""").containsMatchIn(combinedText) -> 1080
        Regex("""\b(?:720p|hd|1280x720)\b""").containsMatchIn(combinedText) -> 720
        Regex("""\b(?:480p|sd|854x480|640x480)\b""").containsMatchIn(combinedText) -> 480
        Regex("""\b(?:360p|640x360)\b""").containsMatchIn(combinedText) -> 360
        else -> 0
    }
}

internal val PlayerScreenRuntime.subtitleStyle: SubtitleStyleState
    get() = playerSettingsUiState.subtitleStyle

internal val PlayerScreenRuntime.activeAddonSubtitleType: String
    get() = contentType ?: parentMetaType

internal val PlayerScreenRuntime.effectiveSubtitleVideoId: String?
    get() {
        val rawVideoId = activeVideoId?.trim()?.takeIf { it.isNotBlank() }
        val rawMetaId = parentMetaId.trim().takeIf { it.isNotBlank() }
        val baseId = rawVideoId ?: rawMetaId ?: return null
        val isSeriesItem = activeAddonSubtitleType.equals("series", ignoreCase = true) ||
            activeAddonSubtitleType.equals("tv", ignoreCase = true) ||
            (activeSeasonNumber != null && activeEpisodeNumber != null)
        if (!isSeriesItem) return baseId
        val season = activeSeasonNumber
        val episode = activeEpisodeNumber
        val parts = baseId.split(":")
        val hasSeasonEpSuffix = parts.size >= 3 &&
            parts[parts.size - 2].all(Char::isDigit) &&
            parts[parts.size - 1].all(Char::isDigit)
        return if (!hasSeasonEpSuffix && season != null && episode != null) {
            "$baseId:$season:$episode"
        } else {
            baseId
        }
    }

internal val PlayerScreenRuntime.addonSubtitleFetchKey: String?
    get() = buildAddonSubtitleFetchKey(
        addons = addonsUiState.addons,
        type = activeAddonSubtitleType,
        videoId = effectiveSubtitleVideoId,
    )

internal val PlayerScreenRuntime.visibleAddonSubtitles: List<AddonSubtitle>
    get() {
        val filtered = filterAddonSubtitlesForSettings(
            subtitles = addonSubtitles,
            settings = playerSettingsUiState,
        )
        val selectedId = selectedAddonSubtitleId ?: return filtered
        if (filtered.any { it.matchesSelection(selectedId) }) return filtered
        val selectedSub = addonSubtitles.findSelectedAddon(selectedId) ?: return filtered
        return listOf(selectedSub) + filtered
    }

internal val PlayerScreenRuntime.selectedAddonSubtitle: AddonSubtitle?
    get() {
        val selectedId = selectedAddonSubtitleId ?: return null
        return addonSubtitles.findSelectedAddon(selectedId)
            ?: visibleAddonSubtitles.findSelectedAddon(selectedId)
    }

internal fun PlayerScreenRuntime.updateTrackPreference(
    update: (PersistedPlayerTrackPreference) -> PersistedPlayerTrackPreference,
) {
    if (parentMetaId.isBlank()) return
    val current = PlayerTrackPreferenceStorage.load(parentMetaId) ?: PersistedPlayerTrackPreference()
    PlayerTrackPreferenceStorage.save(parentMetaId, update(current))
}

internal fun PlayerScreenRuntime.persistAudioPreference(track: AudioTrack?) {
    isUserExplicitAudioSelection = true
    preferredAudioSelectionApplied = true
    updateTrackPreference { current ->
        current.copy(
            audioLanguage = track?.language,
            audioName = track?.label,
            audioTrackId = track?.id,
        )
    }
}

internal fun PlayerScreenRuntime.persistInternalSubtitlePreference(track: SubtitleTrack?) {
    updateTrackPreference { current ->
        current.copy(
            subtitleType = if (track == null) {
                PersistedSubtitleSelectionType.DISABLED
            } else {
                PersistedSubtitleSelectionType.INTERNAL
            },
            subtitleLanguage = track?.language,
            subtitleName = track?.label,
            subtitleTrackId = track?.id,
            subtitleIsForced = track?.isForced,
            addonSubtitleId = null,
            addonSubtitleUrl = null,
            addonSubtitleAddonName = null,
        )
    }
}

internal fun PlayerScreenRuntime.persistAddonSubtitlePreference(subtitle: AddonSubtitle) {
    updateTrackPreference { current ->
        current.copy(
            subtitleType = PersistedSubtitleSelectionType.ADDON,
            subtitleLanguage = subtitle.language,
            subtitleName = subtitle.display,
            subtitleTrackId = null,
            addonSubtitleId = subtitle.id,
            addonSubtitleUrl = subtitle.url,
            addonSubtitleAddonName = subtitle.addonName,
            subtitleIsForced = null,
        )
    }
}

internal fun PlayerScreenRuntime.restorePersistedTrackPreferenceIfNeeded(): Boolean {
    if (trackPreferenceRestoreApplied) return true
    // A pending carry-over describes the selection playing right now; it wins over the stored
    // preference (same ordering as NuvioTV).
    if (pendingEngineSwitchTrackPreference?.sourceIdentity == activePlaybackIdentity) return true
    val preference = PlayerTrackPreferenceStorage.load(parentMetaId)
    if (preference == null) {
        trackPreferenceRestoreApplied = true
        return true
    }

    restorePersistedAudioPreference(preference)

    when (preference.subtitleType) {
        PersistedSubtitleSelectionType.DISABLED -> {
            // Do not lock out automatic Arabic subtitle selection from previous empty sessions;
            // allow tryAutoSelectPreferredSubtitleFromAvailableTracks to select Arabic by default.
        }
        PersistedSubtitleSelectionType.INTERNAL -> {
            if (subtitleTracks.isNotEmpty()) {
                val restoredSubtitleIndex = findPersistedSubtitleTrackIndex(subtitleTracks, preference)
                if (restoredSubtitleIndex >= 0) {
                    if (useCustomSubtitles) {
                        playerController?.clearExternalSubtitleAndSelect(restoredSubtitleIndex)
                    } else {
                        playerController?.selectSubtitleTrack(restoredSubtitleIndex)
                    }
                    selectedSubtitleIndex = restoredSubtitleIndex
                    selectedAddonSubtitleId = null
                    useCustomSubtitles = false
                    preferredSubtitleSelectionApplied = true
                    isUserExplicitSubtitleSelection = true
                }
            }
        }
        PersistedSubtitleSelectionType.ADDON -> {
            if (!isUserExplicitSubtitleSelection) {
                val fetchKey = addonSubtitleFetchKey.takeUnless {
                    activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()
                }
                if (fetchKey != null && autoFetchedAddonSubtitlesForKey != fetchKey) return false

                val subtitle = findPersistedAddonSubtitle(addonSubtitles, preference)
                val canRestoreWhileLoading = subtitle != null && (
                    subtitle.url == preference.addonSubtitleUrl ||
                        preference.addonSubtitleAddonName.isNullOrBlank() ||
                        subtitle.addonName.equals(preference.addonSubtitleAddonName, ignoreCase = true)
                    )
                if (isLoadingAddonSubtitles && !canRestoreWhileLoading) return false
                if (subtitle != null) {
                    selectedAddonSubtitleId = subtitle.selectionKey
                    selectedSubtitleIndex = -1
                    useCustomSubtitles = true
                    if (!maybeAutoSyncRestoredSubtitleAtStart(subtitle.url)) { // AutoSync hook
                        playerController?.setSubtitleUri(subtitle.url)
                    }
                    preferredSubtitleSelectionApplied = true
                    isUserExplicitSubtitleSelection = true
                }
            }
        }
    }

    trackPreferenceRestoreApplied = audioTracks.isNotEmpty() ||
        (preference.audioTrackId.isNullOrBlank() && preference.audioLanguage.isNullOrBlank() &&
            preference.audioName.isNullOrBlank())
    return true
}

internal fun PlayerScreenRuntime.refreshTracks() {
    val ctrl = playerController ?: return
    val previousAudioIndex = selectedAudioIndex
    audioTracks = ctrl.getAudioTracks()
    subtitleTracks = ctrl.getSubtitleTracks()
    val controllerQualityTracks = ctrl.getVideoQualityTracks()
    videoQualityTracks = resolveEffectiveQualityTracks(controllerQualityTracks)
    val selectedAudio = audioTracks.firstOrNull { it.isSelected }
    if (selectedAudio != null) selectedAudioIndex = selectedAudio.index
    val selectedSub = subtitleTracks.firstOrNull { it.isSelected }
    if (selectedSub != null && !useCustomSubtitles) selectedSubtitleIndex = selectedSub.index
    if (!playbackSnapshot.isLoading) {
        hasScannedTextTracksOnce = true
    }

    if (selectedAudioIndex != previousAudioIndex && !isUserExplicitSubtitleSelection) {
        preferredSubtitleSelectionApplied = false
    }

    val subtitlePreferenceReady = restorePersistedTrackPreferenceIfNeeded()

    val preferredAudioTargets = preferredAudioLanguageTargets

    applyPreferredAudioTrack(preferredAudioTargets)

    if (subtitlePreferenceReady && (preferredAudioSelectionApplied || audioTracks.isEmpty())) {
        tryAutoSelectPreferredSubtitleFromAvailableTracks(preferredAudioTargets)
    }

    // The engine may have just switched: restore the selection captured before the switch.
    applyPendingEngineSwitchTrackPreference()
}

internal fun PlayerScreenRuntime.resolveEffectiveQualityTracks(
    controllerTracks: List<VideoQualityTrack>
): List<VideoQualityTrack> {
    if (controllerTracks.size > 1) {
        // Multi-track adaptive stream: use real tracks reported by player engine
        return controllerTracks
    }

    val candidateStreams = availableStreams.ifEmpty {
        PlayerStreamsRepository.sourceState.value.groups.flatMap { it.streams }
    }
    if (candidateStreams.isEmpty()) {
        return controllerTracks
    }

    val activeHeight = controllerTracks.firstOrNull()?.height
        ?: activeStream?.detectResolutionHeight()
        ?: playbackSnapshot.videoHeight.takeIf { it > 0 }
        ?: 1080

    val streamsByHeight: Map<Int, List<StreamItem>> = candidateStreams
        .mapNotNull { s: StreamItem ->
            val h = s.detectResolutionHeight().takeIf { it > 0 }
            if (h != null) Pair(h, s) else null
        }
        .groupBy({ pair -> pair.first }, { pair -> pair.second })

    if (streamsByHeight.isEmpty() || streamsByHeight.keys.size <= 1) {
        // Only 1 real resolution available across all streams
        val singleHeight = streamsByHeight.keys.firstOrNull() ?: activeHeight
        val label = when {
            singleHeight >= 2160 -> "4K Ultra HD (${singleHeight}p)"
            singleHeight >= 1080 -> "1080p Full HD"
            singleHeight >= 720 -> "720p HD"
            singleHeight >= 480 -> "480p SD"
            else -> "${singleHeight}p"
        }
        return listOf(
            VideoQualityTrack(
                index = 1,
                id = "single_q_${singleHeight}p",
                label = "$label • الجودة الوحيدة المتوفرة",
                width = singleHeight * 16 / 9,
                height = singleHeight,
                isSelected = true,
                isAuto = false,
            )
        )
    }

    var idx = 1
    val tracks = mutableListOf<VideoQualityTrack>()
    val sortedHeights: List<Int> = streamsByHeight.keys.sortedDescending()
    for (h in sortedHeights) {
        val siblings: List<StreamItem> = streamsByHeight[h].orEmpty()
        val preferred: StreamItem = siblings.firstOrNull { it.addonId == activeProviderAddonId }
            ?: siblings.first()
        val label = when {
            h >= 2160 -> "4K Ultra HD (2160p)"
            h >= 1440 -> "2K Quad HD (1440p)"
            h >= 1080 -> "1080p Full HD"
            h >= 720 -> "720p HD"
            h >= 480 -> "480p SD"
            else -> "${h}p"
        }
        val detail = preferred.streamLabel.takeIf { it.isNotBlank() && it != "Stream" }
        tracks += VideoQualityTrack(
            index = idx++,
            id = "stream_q_${h}p",
            label = if (detail != null) "$label • $detail" else label,
            width = h * 16 / 9,
            height = h,
            isSelected = if (selectedVideoQualityHeight <= 0 || selectedVideoQualityHeight == Int.MAX_VALUE) {
                h == activeHeight
            } else {
                h == selectedVideoQualityHeight
            },
            isAuto = false,
        )
    }
    return tracks
}

internal fun PlayerScreenRuntime.selectVideoQualityOption(maxHeight: Int) {
    selectedVideoQualityHeight = maxHeight
    val ctrl = playerController
    val ctrlTracks = ctrl?.getVideoQualityTracks().orEmpty()
    if (ctrlTracks.size > 1) {
        // Multi-track adaptive stream: use ExoPlayer/MPV track selector
        ctrl?.selectVideoQuality(maxHeight)
    } else {
        // Single-stream progressive file: switch to matching stream from provider at current position!
        val candidateStreams: List<StreamItem> = availableStreams.ifEmpty {
            PlayerStreamsRepository.sourceState.value.groups.flatMap { it.streams }
        }
        val matchingStreams: List<StreamItem> = candidateStreams.filter { it.detectResolutionHeight() == maxHeight }
        if (matchingStreams.isNotEmpty()) {
            val targetStream: StreamItem = matchingStreams.firstOrNull { it.addonId == activeProviderAddonId }
                ?: matchingStreams.first()
            if (targetStream.playerSourceIdentityKey() != activeSourceIdentityKey) {
                switchToSource(targetStream)
            }
        } else {
            ctrl?.selectVideoQuality(maxHeight)
        }
    }
    refreshTracks()
    showQualityModal = false
}

internal val PlayerScreenRuntime.activeVideoQualityLabel: String
    get() {
        val selected = videoQualityTracks.firstOrNull { it.isSelected }
        if (selected != null && selected.height > 0) {
            return when {
                selected.height >= 2160 -> "4K"
                selected.height >= 1080 -> "1080p"
                selected.height >= 720 -> "720p"
                selected.height >= 480 -> "480p"
                else -> "${selected.height}p"
            }
        }
        return when {
            selectedVideoQualityHeight == Int.MAX_VALUE || selectedVideoQualityHeight <= 0 -> "تلقائي"
            selectedVideoQualityHeight >= 2160 -> "4K"
            selectedVideoQualityHeight >= 1080 -> "1080p FHD"
            selectedVideoQualityHeight >= 720 -> "720p HD"
            selectedVideoQualityHeight >= 480 -> "480p"
            else -> "${selectedVideoQualityHeight}p"
        }
    }

private fun PlayerScreenRuntime.tryAutoSelectPreferredSubtitleFromAvailableTracks(
    preferredAudioTargets: List<String>,
) {
    if (isUserExplicitSubtitleSelection) return

    val preferredSubtitleLanguage = normalizeLanguageCode(
        playerSettingsUiState.preferredSubtitleLanguage,
    ) ?: "ar"
    val resolvedTargets = if (
        preferredSubtitleLanguage == SubtitleLanguageOption.NONE ||
        preferredSubtitleLanguage == SubtitleLanguageOption.FORCED
    ) {
        listOf("ar")
    } else {
        resolvePreferredSubtitleLanguageTargets(
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        )
    }
    val preferredSubtitleTargets = if (resolvedTargets.any { it.startsWith("ar") }) {
        resolvedTargets
    } else {
        listOf("ar") + resolvedTargets
    }
    val primaryTarget = preferredSubtitleTargets.firstOrNull() ?: "ar"

    if (preferredSubtitleSelectionApplied) {
        val currentSelectedLang = when {
            selectedAddonSubtitle != null -> selectedAddonSubtitle?.language
            selectedSubtitleIndex >= 0 -> subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }?.language
            else -> null
        }
        val isPrimarySatisfied = primaryTarget != null && currentSelectedLang != null &&
            SubtitleLanguageMatching.matchesLanguageCode(currentSelectedLang, primaryTarget)
        val hasBetterAddonMatch = !isPrimarySatisfied && primaryTarget != null &&
            addonSubtitles.any { SubtitleLanguageMatching.matchesLanguageCode(it.language, primaryTarget) }
        if (!hasBetterAddonMatch) return
    }

    val selectedAudioTrack = audioTracks.firstOrNull { track -> track.index == selectedAudioIndex }
        ?: audioTracks.firstOrNull { it.isSelected }
    val selectionPlan = resolveSubtitleAutoSelectionPlan(
        selectedAudioTrack = selectedAudioTrack,
        preferredAudioTargets = preferredAudioTargets,
        preferredSubtitleTargets = preferredSubtitleTargets,
        useForcedSubtitles = subtitleStyle.useForcedSubtitles,
    )
    if (selectionPlan == null) {
        disableAutomaticSubtitleSelection()
        return
    }
    if (selectionPlan.targets.isEmpty()) {
        disableAutomaticSubtitleSelection()
        preferredSubtitleSelectionApplied = true
        return
    }

    val internalIndex = findPreferredSubtitleTrackIndex(
        tracks = subtitleTracks,
        targets = selectionPlan.targets,
        mode = selectionPlan.mode,
        selectedAudioTrack = selectedAudioTrack,
    )
    if (internalIndex >= 0 && hasScannedTextTracksOnce) {
        val matchedTrack = subtitleTracks[internalIndex]
        val trackVariant = SubtitleLanguageMatching.detectTrackLanguageVariant(
            language = matchedTrack.language,
            name = matchedTrack.label,
            trackId = matchedTrack.id,
        )
        val matchedTargetPosition = selectionPlan.targets.indexOfFirst { target ->
            val normalizedTarget = SubtitleLanguageMatching.normalizeLanguageCode(target)
            trackVariant == normalizedTarget ||
                SubtitleLanguageMatching.matchesLanguageCode(trackVariant, target)
        }
        if (matchedTargetPosition > 0 && isLoadingAddonSubtitles) {
            return
        }
        if (matchedTargetPosition > 0 && !isLoadingAddonSubtitles) {
            val primaryAddonMatch = addonSubtitles.firstOrNull { subtitle ->
                SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, selectionPlan.targets.first())
            }
            if (primaryAddonMatch != null) {
                preferredSubtitleSelectionApplied = true
                selectedAddonSubtitleId = primaryAddonMatch.selectionKey
                selectedSubtitleIndex = -1
                useCustomSubtitles = true
                if (!maybeAutoSyncPreferredSubtitleAtStart(primaryAddonMatch)) { // AutoSync hook
                    playerController?.setSubtitleUri(primaryAddonMatch.url)
                }
                return
            }
        }
        preferredSubtitleSelectionApplied = true
        if (selectedSubtitleIndex != internalIndex || selectedAddonSubtitleId != null) {
            if (useCustomSubtitles) {
                playerController?.clearExternalSubtitleAndSelect(internalIndex)
            } else {
                playerController?.selectSubtitleTrack(internalIndex)
            }
            selectedSubtitleIndex = internalIndex
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
        }
        return
    }

    if (selectionPlan.mode == SubtitleAutoSelectionMode.FORCED_ONLY) {
        val requiredForcedTarget = selectionPlan.targets.firstOrNull() ?: return
        if (!hasScannedTextTracksOnce) return
        if (isLoadingAddonSubtitles) {
            val currentTrack = subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }
            if (currentTrack != null && !currentTrack.isForced) {
                disableAutomaticSubtitleSelection()
            }
            return
        }
        val forcedAddonMatch = if (selectedAudioTrack != null) {
            addonSubtitles.firstOrNull { subtitle ->
                addonSubtitleIsForced(subtitle) &&
                    addonSubtitleMatchesLanguage(subtitle, requiredForcedTarget) &&
                    addonSubtitleMatchesSelectedAudioLanguage(subtitle, selectedAudioTrack)
            }
        } else {
            null
        }
        preferredSubtitleSelectionApplied = true
        if (forcedAddonMatch != null) {
            selectedAddonSubtitleId = forcedAddonMatch.selectionKey
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            playerController?.setSubtitleUri(forcedAddonMatch.url)
        } else {
            disableAutomaticSubtitleSelection()
        }
        return
    }

    val selectedAddon = selectedAddonSubtitle
    val selectedAddonMatchesTarget = selectedAddon != null &&
        (!subtitleStyle.useForcedSubtitles || !addonSubtitleIsForced(selectedAddon)) &&
        selectionPlan.targets.any { target ->
            SubtitleLanguageMatching.matchesLanguageCode(selectedAddon.language, target)
        }
    if (selectedAddonMatchesTarget && selectedAddon != null) {
        val selectedMatchesPrimary = SubtitleLanguageMatching.matchesLanguageCode(
            selectedAddon.language,
            selectionPlan.targets.first(),
        )
        if (selectedMatchesPrimary) {
            preferredSubtitleSelectionApplied = true
            maybeAutoSyncPreferredSubtitleAtStart(selectedAddon) // AutoSync hook
            return
        }
    }

    val addonMatch = selectionPlan.targets.firstNotNullOfOrNull { target ->
        addonSubtitles.firstOrNull { subtitle ->
            (!subtitleStyle.useForcedSubtitles || !addonSubtitleIsForced(subtitle)) &&
                SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, target)
        }
    }
    if (addonMatch != null) {
        preferredSubtitleSelectionApplied = true
        selectedAddonSubtitleId = addonMatch.selectionKey
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        if (!maybeAutoSyncPreferredSubtitleAtStart(addonMatch)) { // AutoSync hook
            playerController?.setSubtitleUri(addonMatch.url)
        }
    } else if (!isLoadingAddonSubtitles && !preferredSubtitleSelectionApplied && hasScannedTextTracksOnce && !playbackSnapshot.isLoading) {
        disableAutomaticSubtitleSelection()
        preferredSubtitleSelectionApplied = true
    }
}

private fun PlayerScreenRuntime.disableAutomaticSubtitleSelection() {
    if (selectedSubtitleIndex != -1 || subtitleTracks.any { it.isSelected }) {
        playerController?.selectSubtitleTrack(-1)
    }
    selectedSubtitleIndex = -1
    selectedAddonSubtitleId = null
    useCustomSubtitles = false
}
