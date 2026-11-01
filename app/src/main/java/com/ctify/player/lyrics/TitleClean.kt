package com.ctify.player.lyrics

import java.text.Normalizer

/** Title and artist guesses for a loose lyrics search, from messy tags or YouTube upload names. */
object TitleClean {
    private val ic = RegexOption.IGNORE_CASE
    private val brackets = Regex("""\s*(\[[^\]]*]|【[^】]*】)""")
    private val noisyParens = Regex("""\s*\([^)]*\b(feat|ft|featuring|official|video|audio|lyrics?|visuali[sz]er|remaster(ed)?|hd|hq|4k|explicit|clip|videoclip|live|prod|version|mv)\b[^)]*\)""", ic)
    private val tailFeat = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", ic)
    private val tailDash = Regex("""\s+[-–]\s+[^-–]*\b(remaster(ed)?|radio edit|(single|album|mono|stereo) version|live (at|from|in)|bonus track)\b.*$""", ic)
    private val tailPipe = Regex("""\s+[|｜].*$""")
    private val channel = Regex("""(\s*-\s*Topic|VEVO|\s+Official)$""", ic)
    private val moreArtists = Regex("""\s*(,|&|;|/|\s(feat\.?|ft\.?)\s).*$""", ic)
    private val artistDashTitle = Regex("""^(.+?)\s+[-–—]\s+(.+)$""")

    fun title(s: String): String = s.replace(brackets, "").replace(noisyParens, "")
        .replace(tailFeat, "").replace(tailDash, "").replace(tailPipe, "").trim()

    /** Only the first artist; a channel's "- Topic" or "VEVO" dropped. */
    fun artist(s: String): String = s.trim().replace(channel, "").replace(moreArtists, "").trim()

    /** Best first; YouTube uploads named "Artist - Title" come with the channel as artist, so that split goes first. */
    fun guesses(title: String, artist: String): List<Pair<String, String>> {
        val t = title(title)
        val split = artistDashTitle.find(t)?.destructured?.let { (l, r) -> r to artist(l) }
        return listOfNotNull(split, t to artist(artist)).filter { it.first.isNotBlank() }.distinct()
    }

    /** Lowercase letters and digits without accents, so "Acasă (Live)" and "acasa" compare equal. */
    fun key(s: String): String = Normalizer.normalize(title(s), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().filter { it.isLetterOrDigit() }
}
