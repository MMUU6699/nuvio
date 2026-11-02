package com.nuvio.app.features.player

internal object SubtitleSdhFilter {
    // Matches square brackets [...] and unicode brackets 【...】, 〔...〕, ［...］, «...»
    private val brackets = Regex("""(?:\[[^]]*]|【[^】]*】|〔[^〕]*〕|［[^］]*］|«[^»]*»)[ \t]*""")

    // Matches parenthetical sound/SDH descriptions in (...) or （...） that are not purely numbers
    private val parenthesesSdh = Regex(
        """(?:\((?![0-9\s]*\))[^)\r\n]{1,120}\)|（(?![0-9\s]*）)[^）\r\n]{1,120}）)[ \t]*"""
    )

    // Matches asterisk sound effects like *صراخ* or *sighs*
    private val asterisksSdh = Regex("""\*[^*\r\n]{1,80}\*[ \t]*""")

    // Matches musical notes and sung lyrics: ♪, ♫, ♬, ♩, #
    private val musicSymbols = Regex("""(?:[♪♫♬♩#]+[^♪♫♬♩#\r\n]*[♪♫♬♩#]+|[♪♫♬♩]+)[ \t]*""")

    // Matches speaker names followed by a colon (e.g. "أحمد:", "الضابط:", "JOHN:", "- MARY:", "[MAN]:")
    // Preserves the leading dialogue dash "-" if present.
    private val speakerLabel = Regex(
        """(?m)^([ \t]*-[ \t]*)?(?:[\p{L}\p{M}0-9 _'#.,()/-]{1,40}|\[[^]\r\n]{1,40}\])[:：](?=\s|$)[ \t]*"""
    )

    // Matches ASS/SSA formatting overrides like {\an8}, {\pos(x,y)}, {\fad(100,100)}
    private val assTags = Regex("""\{[^}]*\}""")

    // Matches HTML tags like <i>, </i>, <font ...>, </font>
    private val htmlTags = Regex("""</?[a-zA-Z0-9._-]+(?: [^>]*)?>""")

    // Location banners / title cards in English (e.g. "LOCATION: ...", "SCENE: ...", "NEW YORK", "LONDON, 1985")
    private val explicitLocationPrefix = Regex(
        """^(?:LOCATION|SCENE|TIME|YEAR|DATE|PLACE)[\s:：].*$""",
        RegexOption.IGNORE_CASE
    )

    // Matches uppercase Latin location / time / scene title cards like "PARIS, FRANCE", "LOS ANGELES - 1984"
    private val latinLocationCard = Regex("""^[A-Z0-9\s,.'’\-–—/:;]{3,60}$""")

    // Subtitle site advertisements / watermarks
    private val subtitleWatermarks = listOf(
        "subtitles by", "opensubtitles", "downloaded from", "yify", "yts.mx",
        "podnapisi", "addic7ed", "subscene", "ترجمة وتعديل", "قام بالترجمة"
    )

    fun filter(text: String): String? {
        // Strip ASS and HTML tags first
        var filtered = text
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
        filtered = assTags.replace(filtered, "")
        filtered = htmlTags.replace(filtered, "")

        // Remove speaker labels while preserving dialogue dashes
        filtered = speakerLabel.replace(filtered) { match ->
            match.groups[1]?.value.orEmpty()
        }

        // Remove brackets, parentheses SDH, asterisks, music
        filtered = brackets.replace(filtered, "")
        filtered = parenthesesSdh.replace(filtered, "")
        filtered = asterisksSdh.replace(filtered, "")
        filtered = musicSymbols.replace(filtered, "")

        val containsArabic = filtered.any { it in '\u0600'..'\u06FF' || it in '\uFB50'..'\uFEFF' }

        return filtered.lines()
            .map { it.trim() }
            .filter { line ->
                if (line.isBlank()) return@filter false
                // Drop lines that are only dashes or punctuation
                if (line.all { it.isWhitespace() || it == '-' || it == '—' || it == '–' || it == '*' || it == '.' }) {
                    return@filter false
                }
                // Drop subtitle site watermarks
                val lower = line.lowercase()
                if (subtitleWatermarks.any { lower.contains(it) }) return@filter false

                // Drop explicit location prefixes (e.g. "LOCATION: PARIS")
                if (explicitLocationPrefix.matches(line)) return@filter false

                // If this is an Arabic subtitle track, drop untranslated English location cards (e.g. "WASHINGTON D.C.")
                if (containsArabic && latinLocationCard.matches(line) && line.any { it.isLetter() }) {
                    return@filter false
                }

                true
            }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }
}
