package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSubtitleUtilsTest {

    @Test
    fun testParseFromTextSrtWithEndTimes() {
        val srtText = """
            1
            00:00:01,000 --> 00:00:04,500
            Hello world!
            Second line

            2
            00:00:05,000 --> 00:00:08,000
            Testing sub
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseFromText(srtText, "sub.srt")
        assertEquals(2, cues.size)

        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(4500L, cues[0].endTimeMs)
        assertEquals("Hello world!\nSecond line", cues[0].text)

        assertEquals(5000L, cues[1].startTimeMs)
        assertEquals(8000L, cues[1].endTimeMs)
        assertEquals("Testing sub", cues[1].text)
    }

    @Test
    fun testParseFromTextWebVttMetadataSkipping() {
        val vttText = """
            WEBVTT

            STYLE
            ::cue {
              color: yellow;
            }

            NOTE This is a comment

            00:01.000 --> 00:04.000
            VTT Cue Text
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseFromText(vttText, "sub.vtt")
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(4000L, cues[0].endTimeMs)
        assertEquals("VTT Cue Text", cues[0].text)
    }

    @Test
    fun testZeroDurationCueFiltering() {
        val srtText = """
            1
            00:00:01,000 --> 00:00:01,000
            Zero duration cue

            2
            00:00:02,000 --> 00:00:05,000
            Valid cue
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseFromText(srtText, "test.srt")
        assertEquals(1, cues.size)
        assertEquals("Valid cue", cues[0].text)
    }

    @Test
    fun testParseMultilineTtmlCue() {
        val ttmlText = """
            <tt>
              <body>
                <div>
                  <p begin="00:00:01.000" end="00:00:04.500">
                    Hello<br/>world
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parse(ttmlText, "sub.ttml")

        assertEquals(1, cues.size)
        assertEquals(1000L, cues.single().startTimeMs)
        assertEquals(4500L, cues.single().endTimeMs)
        assertEquals("Hello\nworld", cues.single().text)
    }

    @Test
    fun testSubtitleSdhFilterArabicNoiseAndSpeakerLabels() {
        // Arabic SDH in parentheses and brackets
        val text1 = "[نباح الكلاب]\n(طقطقة الماء)\nمرحباً بك"
        assertEquals("مرحباً بك", SubtitleSdhFilter.filter(text1))

        // Arabic speaker name followed by colon
        val text2 = "الشخص يقول : كيف حالك؟"
        assertEquals("كيف حالك؟", SubtitleSdhFilter.filter(text2))

        // Dialogue dash with speaker label
        val text3 = "- أحمد: أين أنت الآن؟\n- سارة: أنا في البيت"
        assertEquals("- أين أنت الآن؟\n- أنا في البيت", SubtitleSdhFilter.filter(text3))

        // Music notes and asterisks
        val text4 = "♪ موسيقى هادئة ♪\n*صراخ*\nأسرع!"
        assertEquals("أسرع!", SubtitleSdhFilter.filter(text4))

        // ASS tags and formatting
        val text5 = "{\\an8}أهلاً بكم في البرنامج"
        assertEquals("أهلاً بكم في البرنامج", SubtitleSdhFilter.filter(text5))

        // English location header in Arabic subtitle
        val text6 = "LONDON, ENGLAND\nوصلنا إلى لندن"
        assertEquals("وصلنا إلى لندن", SubtitleSdhFilter.filter(text6))

        // Subtitle only containing noise should return null
        val noiseOnly = "[صوت رعد]\n(نباح الكلاب)\n♪♪♪"
        assertEquals(null, SubtitleSdhFilter.filter(noiseOnly))

        // Dialogue dash with SDH noise and mixed script
        val userReportedSDH = "-(شخير) Sheathe أن الصلب ، كنت twats!"
        assertEquals("- Sheathe أن الصلب ، كنت twats!", SubtitleSdhFilter.filter(userReportedSDH))

        // English location card in Arabic subtitle track
        assertEquals(null, SubtitleSdhFilter.filter("KING'S LANDING", isArabicTrack = true))
        assertEquals(null, SubtitleSdhFilter.filter("NEW YORK, 1985", isArabicTrack = true))
    }
}
