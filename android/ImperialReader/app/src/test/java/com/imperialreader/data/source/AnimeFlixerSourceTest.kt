package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnimeFlixerSourceTest {
    @Test
    fun catalogUsesSeriesAndExcludesMovies() {
        val entries = JSONArray("""[{"id":10,"title":"Example","is_series":true,"thumbnail_url":"https://example.com/cover.jpg"},{"id":20,"title":"Movie","is_series":false}]""")
        val page = AnimeFlixerSource.parseCatalog(entries, 1)
        assertEquals("https://animeflixer.com/series/10", page.mangas.single().url)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun parsesReleasedEpisodesNewestFirst() {
        val entries = JSONArray("""[{"id":1,"season_number":1,"episode_number":1,"title":"Beginning","is_released":true},{"id":2,"season_number":1,"episode_number":2,"title":"Next","is_released":true},{"id":3,"episode_number":3,"is_released":false}]""")
        val chapters = AnimeFlixerSource.parseEpisodes(entries, 10)
        assertEquals(listOf("Season 1 - Episode 2 - Next", "Season 1 - Episode 1 - Beginning"), chapters.map { it.name })
        assertEquals("https://animeflixer.com/watch/2?series=10", chapters.first().url)
    }

    @Test
    fun prefersTheSitesDefaultPlayerAndRejectsInvalidUrls() {
        val episode = JSONObject("""{"video_url":"https://player.example/working","video_versions":[{"label":"Old Sub","video_url":"https://player.example/old","is_working":true},{"label":"Sub","video_url":"https://player.example/working","is_working":true},{"label":"Unsafe","video_url":"javascript:alert(1)"}]}""")
        val players = AnimeFlixerSource.parsePlayers(episode)
        assertEquals(listOf("Sub", "Old Sub"), players.map { it.first })
    }
}
