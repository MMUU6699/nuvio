package com.nuvio.app.features.streams.providers

import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.player.SubtitleRepository
import com.nuvio.app.features.tracking.DEFAULT_WATCH_PROGRESS_SOURCE
import com.nuvio.app.features.tracking.DEFAULT_LIBRARY_SOURCE_MODE
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderAndSubtitleLogicTest {

    @Test
    fun `ArabicProviders matchesQueryTitle rejects unrelated movies when searching for House of the Dragon`() {
        // Searching for "آل التنين" (House of the Dragon S1E1) must reject "فيلم فخ التنين" and "فيلم مهمة التنين البحري"
        assertFalse(
            ArabicProviders.matchesQueryTitle(
                candidateTitle = "مشاهدة فيلم فخ التنين 2025 مترجم",
                query = "آل التنين",
                season = 1,
                episode = 1,
            )
        )
        assertFalse(
            ArabicProviders.matchesQueryTitle(
                candidateTitle = "فيلم مهمة التنين البحري الحلقة 1",
                query = "آل التنين",
                season = 1,
                episode = 1,
            )
        )
        // Must accept exact series match
        assertTrue(
            ArabicProviders.matchesQueryTitle(
                candidateTitle = "مسلسل آل التنين الموسم الاول الحلقة 1 مترجمة",
                query = "آل التنين",
                season = 1,
                episode = 1,
            )
        )
        assertTrue(
            ArabicProviders.matchesQueryTitle(
                candidateTitle = "مسلسل House of the Dragon الموسم الاول الحلقة 1",
                query = "House of the Dragon",
                season = 1,
                episode = 1,
            )
        )
    }

    @Test
    fun `ArabicProviders matchesQueryTitle handles Arabic prepositions ع and على`() {
        assertTrue(
            ArabicProviders.matchesQueryTitle(
                candidateTitle = "مسلسل حب ع ورق الحلقة 1",
                query = "حب على ورق",
                season = 1,
                episode = 1,
            )
        )
    }

    @Test
    fun `SubtitleRepository detects true language from URL and content instead of labeling all as Arabic`() {
        assertEquals("ar", SubtitleRepository.detectSubtitleLanguageFromUrl("https://cdn.vidlink.pro/subs/arabic_1.vtt"))
        assertEquals("fr", SubtitleRepository.detectSubtitleLanguageFromUrl("https://cdn.vidlink.pro/subs/fre_2.vtt"))
        assertEquals("fa", SubtitleRepository.detectSubtitleLanguageFromUrl("https://cdn.vidlink.pro/subs/persian.vtt"))
        assertEquals("ru", SubtitleRepository.detectSubtitleLanguageFromUrl("https://cdn.vidlink.pro/subs/rus.vtt"))
        assertEquals("pt-br", SubtitleRepository.detectSubtitleLanguageFromUrl("https://cdn.vidlink.pro/subs/pob.vtt"))

        // Content-based script detection:
        val arabicVtt = """
            WEBVTT
            00:00:01.000 --> 00:00:04.000
            مرحباً بكم في العاصمة الملكية، هذا يوم عظيم في تاريخ المملكة
        """.trimIndent()
        assertEquals("ar", SubtitleRepository.detectSubtitleLanguageFromContent(arabicVtt, null))

        val persianVtt = """
            WEBVTT
            00:00:01.000 --> 00:00:04.000
            این یک آزمایش برای تشخیص زبان فارسی در زیرنویس است و شما می‌توانید ببینید
        """.trimIndent()
        assertEquals("fa", SubtitleRepository.detectSubtitleLanguageFromContent(persianVtt, null))

        val russianVtt = """
            WEBVTT
            00:00:01.000 --> 00:00:04.000
            Привет, это проверка русских субтитров для видеоплеера
        """.trimIndent()
        assertEquals("ru", SubtitleRepository.detectSubtitleLanguageFromContent(russianVtt, null))

        val portugueseVtt = """
            WEBVTT
            00:00:01.000 --> 00:00:04.000
            ULTRON: Você não está pronto para uma luta com isso, babaca!
        """.trimIndent()
        assertEquals("pt-br", SubtitleRepository.detectSubtitleLanguageFromContent(portugueseVtt, null))

        val frenchVtt = """
            WEBVTT
            00:00:01.000 --> 00:00:04.000
            C'est incroyable, je ne suis pas dans le château avec vous mais nous sommes prêts.
        """.trimIndent()
        assertEquals("fr", SubtitleRepository.detectSubtitleLanguageFromContent(frenchVtt, null))
    }

    @Test
    fun `Tracking sources default to local Nuvio sync and local library without forcing Trakt`() {
        assertEquals(WatchProgressSource.NUVIO_SYNC, DEFAULT_WATCH_PROGRESS_SOURCE)
        assertEquals(LibrarySourceMode.LOCAL, DEFAULT_LIBRARY_SOURCE_MODE)
    }
}
