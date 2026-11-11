package com.imperialreader.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationMatcherTest {
    @Test
    fun ignoresCaseBracketsAndPunctuation() {
        assertEquals(1.0, MigrationMatcher.similarity("Tower of God", "TOWER OF GOD (Official)"), 0.0)
        assertEquals(1.0, MigrationMatcher.similarity("Re:Zero", "Re Zero"), 0.0)
    }

    @Test
    fun relatedButDifferentSeriesStayBelowTheAcceptThreshold() {
        assertTrue(MigrationMatcher.similarity("Solo Leveling", "Solo Leveling: Ragnarok") < MigrationMatcher.ACCEPT)
        assertTrue(MigrationMatcher.similarity("One Piece", "One Punch Man") < MigrationMatcher.ACCEPT)
    }

    @Test
    fun smallTypoStillMatches() {
        assertTrue(MigrationMatcher.similarity("The Beginning After The End", "Beginning After the End") >= MigrationMatcher.ACCEPT)
    }
}
