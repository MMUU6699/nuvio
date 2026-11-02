package com.nuvio.app.features.settings

import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArabicLocalizationTest {

    @Test
    fun testAppLanguageRtlDetection() {
        assertTrue(AppLanguage.ARABIC.isRightToLeft(), "Arabic should be detected as Right-To-Left")
        assertTrue(AppLanguage.HEBREW.isRightToLeft(), "Hebrew should be detected as Right-To-Left")
        assertTrue(AppLanguage.URDU.isRightToLeft(), "Urdu should be detected as Right-To-Left")
        assertFalse(AppLanguage.ENGLISH.isRightToLeft(), "English should not be detected as RTL")
        assertFalse(AppLanguage.FRENCH.isRightToLeft(), "French should not be detected as RTL")
        assertFalse(AppLanguage.SPANISH.isRightToLeft(), "Spanish should not be detected as RTL")
    }

    @Test
    fun testLanguageCodes() {
        assertEquals("ar", AppLanguage.ARABIC.code)
        assertEquals("en", AppLanguage.ENGLISH.code)
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromCode("ar"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.DEVICE, AppLanguage.fromCode("invalid_code"))
    }

    @Test
    fun testTmdbLanguageSyncWithAppLanguage() {
        // When setting app language to Arabic, TMDB should sync to ar-SA
        ThemeSettingsRepository.setAppLanguage(AppLanguage.ARABIC)
        assertEquals("ar-SA", TmdbSettingsRepository.snapshot().language)

        // When setting app language to English, TMDB should sync to en-US
        ThemeSettingsRepository.setAppLanguage(AppLanguage.ENGLISH)
        assertEquals("en-US", TmdbSettingsRepository.snapshot().language)

        // Setting back to Arabic
        ThemeSettingsRepository.setAppLanguage(AppLanguage.ARABIC)
        assertEquals("ar-SA", TmdbSettingsRepository.snapshot().language)
    }

    @Test
    fun testTmdbLanguageManualOverride() {
        // Test that user can still manually change TMDB language in settings independently
        TmdbSettingsRepository.setLanguage("fr-FR")
        assertEquals("fr-FR", TmdbSettingsRepository.snapshot().language)

        TmdbSettingsRepository.setLanguage("ar-SA")
        assertEquals("ar-SA", TmdbSettingsRepository.snapshot().language)
    }
}
