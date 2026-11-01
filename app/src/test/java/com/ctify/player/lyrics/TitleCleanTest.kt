package com.ctify.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanTest {
    @Test
    fun stripsNoise() {
        assertEquals("Song", TitleClean.title("Song (feat. Someone) [Remastered]"))
        assertEquals("Song", TitleClean.title("Song ft. Someone Else"))
        assertEquals("Here Comes the Sun", TitleClean.title("Here Comes the Sun - Remastered 2009"))
        assertEquals("Song", TitleClean.title("Song - 2011 Remaster"))
        assertEquals("Song", TitleClean.title("Song (Official Video)"))
        assertEquals("Song", TitleClean.title("Song (Lyrics)"))
        assertEquals("Song", TitleClean.title("Song | Official Music Video"))
        assertEquals("(I Can't Get No) Satisfaction", TitleClean.title("(I Can't Get No) Satisfaction"))
        assertEquals("Oasis - Live Forever", TitleClean.title("Oasis - Live Forever"))
    }

    @Test
    fun firstArtistAndChannels() {
        assertEquals("Smiley", TitleClean.artist("Smiley, Sore"))
        assertEquals("Mumford", TitleClean.artist("Mumford & Sons"))
        assertEquals("Phoenix", TitleClean.artist("Phoenix - Topic"))
        assertEquals("CarlasDreams", TitleClean.artist("CarlasDreamsVEVO"))
        assertEquals("A", TitleClean.artist("A feat. B"))
    }

    @Test
    fun youtubeTitlesSplitOnDash() {
        assertEquals(
            listOf("Acasa" to "Smiley", "Smiley - Acasa" to "Smiley"),
            TitleClean.guesses("Smiley - Acasa (Official Video)", "Smiley"),
        )
        assertEquals(
            listOf("Sub Pielea Mea" to "Carla's Dreams", "Carla's Dreams - Sub Pielea Mea" to "CarlasDreams"),
            TitleClean.guesses("Carla's Dreams - Sub Pielea Mea | Official Video", "CarlasDreamsVEVO"),
        )
        assertEquals(listOf("Blinding Lights" to "The Weeknd"), TitleClean.guesses("Blinding Lights", "The Weeknd"))
    }

    @Test
    fun keyIgnoresAccentsCaseAndNoise() {
        assertEquals(TitleClean.key("Acasa"), TitleClean.key("Acasă (Live Armonie)"))
    }
}
