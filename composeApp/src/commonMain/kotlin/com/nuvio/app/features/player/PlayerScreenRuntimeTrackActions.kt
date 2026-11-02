package com.nuvio.app.features.player

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
    videoQualityTracks = ctrl.getVideoQualityTracks()
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

internal fun PlayerScreenRuntime.selectVideoQualityOption(maxHeight: Int) {
    selectedVideoQualityHeight = maxHeight
    playerController?.selectVideoQuality(maxHeight)
    videoQualityTracks = playerController?.getVideoQualityTracks().orEmpty()
    showQualityModal = false
}

internal val PlayerScreenRuntime.activeVideoQualityLabel: String
    get() = when {
        selectedVideoQualityHeight == Int.MAX_VALUE || selectedVideoQualityHeight <= 0 -> "تلقائي"
        selectedVideoQualityHeight >= 2160 -> "4K"
        selectedVideoQualityHeight >= 1080 -> "1080p FHD"
        selectedVideoQualityHeight >= 720 -> "720p HD"
        selectedVideoQualityHeight >= 480 -> "480p"
        else -> "${selectedVideoQualityHeight}p"
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
