package com.jarvis.os.desktop.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing only — no network here (CI has none, and a real fetch belongs in a live
 * check: `:desktop:ping --args=--youtube-test|<query>`, which costs no AI tokens either).
 * The fixtures below are simplified but structurally real: shapes confirmed against an
 * actual YouTube results page while fixing the "played a video that didn't exist" bug
 * (2026-09-29) — see YouTubeSearch's own doc comment for why this tool exists at all.
 */
class YouTubeSearchTest {

    private val videoResultsPage = """
        {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
        {"itemSectionRenderer":{"contents":[
        {"videoRenderer":{"videoId":"bzSTpdcs-EI","title":{"runs":[{"text":"Channa Mereya - Lyric Video | Arijit Singh"}]},
        "publishedTimeText":{"simpleText":"8 years ago"},"lengthText":{"simpleText":"4:49"}}},
        {"videoRenderer":{"videoId":"AAAAAAAAAAA","title":{"runs":[{"text":"Some other unrelated video"}]}}}
        ]}}
        ]}}}}}
    """.trimIndent()

    private val playlistResultsPage = """
        {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
        {"itemSectionRenderer":{"contents":[
        {"lockupViewModel":{"contentId":"PL0Z67tlyTaWq7xmJYR0Im1fwtIhc0T0_6",
        "metadata":{"lockupMetadataViewModel":{"title":{"content":"Best Of Arijit Singh"}}},
        "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":
        {"videoId":"ElZfdU54Cp8","playlistId":"PL0Z67tlyTaWq7xmJYR0Im1fwtIhc0T0_6"}}}}}}},
        {"lockupViewModel":{"contentId":"PLunrelated"}}
        ]}}
        ]}}}}}
    """.trimIndent()

    @Test
    fun theFirstRealVideoAndItsTitleAreFound() {
        val v = YouTubeSearch.parseFirstVideo(videoResultsPage)!!
        assertEquals("bzSTpdcs-EI", v.id)
        assertEquals("Channa Mereya - Lyric Video | Arijit Singh", v.title)
        assertNull(v.startVideoId)   // a plain video result has no playlist to start from
    }

    @Test
    fun onlyTheFirstVideoIsTaken() {
        val v = YouTubeSearch.parseFirstVideo(videoResultsPage)!!
        assertTrue(v.id != "AAAAAAAAAAA")
    }

    @Test
    fun aPlaylistCarriesItsOwnStartingVideoForAutoplay() {
        val p = YouTubeSearch.parseFirstPlaylist(playlistResultsPage)!!
        assertEquals("PL0Z67tlyTaWq7xmJYR0Im1fwtIhc0T0_6", p.id)
        assertEquals("ElZfdU54Cp8", p.startVideoId)
        assertEquals("Best Of Arijit Singh", p.title)
    }

    @Test
    fun noMatchIsNullNotAThrow() {
        assertNull(YouTubeSearch.parseFirstVideo("not a real youtube page at all"))
        assertNull(YouTubeSearch.parseFirstPlaylist(""))
    }

    @Test
    fun escapedCharactersInATitleComeBackClean() {
        val html = """{"videoRenderer":{"videoId":"bzSTpdcs-EI","title":{"runs":[{"text":"Tom & Jerry \/ a \"classic\""}]}}}"""
        assertEquals("Tom & Jerry / a \"classic\"", YouTubeSearch.parseFirstVideo(html)!!.title)
    }

    @Test
    fun urlsAreBuiltToStartPlayingOnTheirOwn() {
        assertEquals("https://www.youtube.com/watch?v=abc&autoplay=1", YouTubeSearch.videoUrl("abc"))
        // With a starting video, a playlist link is ALSO a watch link — a bare
        // /playlist?list= page is just a listing and would repeat the exact bug this
        // exists to fix (opens, but doesn't play).
        assertEquals("https://www.youtube.com/watch?v=v1&list=pl1&autoplay=1", YouTubeSearch.playlistUrl("pl1", "v1"))
        // Without one (search didn't find an item to anchor to), fall back honestly rather
        // than invent a video id.
        assertEquals("https://www.youtube.com/playlist?list=pl1", YouTubeSearch.playlistUrl("pl1", null))
    }
}
