package io.github.haigerman.hyperradio

import io.github.haigerman.hyperradio.playback.PodcastFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastFeedTest {

    @Test
    fun picksNewestEpisodeByDate() {
        // absichtlich nicht chronologisch sortiert
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>F.A.Z. Frühdenker</title>
              <item>
                <title><![CDATA[Montag: Merz & Co.]]></title>
                <pubDate>Mon, 16 Nov 2026 05:00:00 +0100</pubDate>
                <enclosure url="https://cdn.example.com/ep-mon.mp3?a=1&amp;b=2" length="1" type="audio/mpeg"/>
              </item>
              <item>
                <title>Dienstag &#8211; neu</title>
                <pubDate>Tue, 17 Nov 2026 05:00:00 +0100</pubDate>
                <enclosure type='audio/mpeg' url='https://cdn.example.com/ep-tue.mp3' length='1'/>
              </item>
              <item>
                <title>Sonntag</title>
                <pubDate>Sun, 15 Nov 2026 05:00:00 +0100</pubDate>
                <enclosure url="https://cdn.example.com/ep-sun.mp3" type="audio/mpeg"/>
              </item>
            </channel></rss>
        """.trimIndent()
        val ep = PodcastFeed.parseLatest(xml)!!
        assertEquals("https://cdn.example.com/ep-tue.mp3", ep.url)
        assertEquals("Dienstag – neu", ep.title)
    }

    @Test
    fun prefersAudioAndUnescapesUrl() {
        val xml = """
            <rss><channel><item>
              <title>Die 20-Uhr-tagesschau</title>
              <enclosure url="https://media.example.com/ts.mp4" type="video/mp4"/>
              <enclosure url="https://media.example.com/ts.mp3?x=1&amp;y=2" type="audio/mpeg"/>
            </item></channel></rss>
        """.trimIndent()
        val ep = PodcastFeed.parseLatest(xml)!!
        assertEquals("https://media.example.com/ts.mp3?x=1&y=2", ep.url)
        assertEquals("Die 20-Uhr-tagesschau", ep.title)
    }

    @Test
    fun withoutDatesTakesFirstAndIgnoresBrokenItems() {
        val xml = "<rss><channel>" +
            "<item><title>ohne Datei</title></item>" +
            "<item><title>Erste</title><enclosure url=\"https://a/1.mp3\"/></item>" +
            "<item><title>Zweite</title><enclosure url=\"https://a/2.mp3\"/></item>" +
            "</channel></rss>"
        assertEquals("https://a/1.mp3", PodcastFeed.parseLatest(xml)!!.url)
        assertNull(PodcastFeed.parseLatest("<html>kein Feed</html>"))
        assertNull(PodcastFeed.parseLatest("<rss><channel><item><enclosure url=\"ftp://x\"/></item></channel></rss>"))
    }
}
