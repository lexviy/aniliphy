package com.aniliphy.widget

import com.aniliphy.WidgetOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetIntentsTest {
    private val open = WidgetIntents.ACTION_OPEN

    @Test
    fun readsWhatTheWidgetAsksFor() {
        assertEquals(WidgetOpen(WidgetKind.Manga, "1234"), WidgetIntents.parse(open, "Manga", "1234"))
        assertEquals(WidgetOpen(WidgetKind.Anime, "5"), WidgetIntents.parse(open, "Anime", "5"))
        assertEquals(WidgetOpen(WidgetKind.Recommendation, "77"), WidgetIntents.parse(open, "Recommendation", "77"))
    }

    @Test
    fun lightNovelsAreKeyedByTheirSeries() {
        assertEquals(WidgetOpen(WidgetKind.Novel, "local:tbate"), WidgetIntents.parse(open, "Novel", "local:tbate"))
    }

    @Test
    fun ignoresAnythingElse() {
        assertNull(WidgetIntents.parse(null, "Manga", "1"))
        assertNull(WidgetIntents.parse("android.intent.action.MAIN", "Manga", "1"))
        assertNull(WidgetIntents.parse(open, null, "1"))
        assertNull(WidgetIntents.parse(open, "Podcast", "1"))
        assertNull(WidgetIntents.parse(open, "Manga", null))
        assertNull(WidgetIntents.parse(open, "Manga", ""))
        assertNull(WidgetIntents.parse(open, "Anime", "not-a-number"))
    }
}
