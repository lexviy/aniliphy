package com.aniliphy.work

import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aniliphy.ui.LookWords
import com.aniliphy.ui.UiStyle
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Posts one new-episode notification worded by each look, under ids no title has, to look at them in the shade;
 * [clear] takes them away. Run with `am instrument`, which doesn't uninstall the app.
 */
@RunWith(AndroidJUnit4::class)
class LookNotificationsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fakeId(index: Int) = -900_000 - index

    @Test
    fun post() {
        val styles = listOf<UiStyle?>(null) + UiStyle.entries
        styles.forEachIndexed { index, style ->
            val chapter = index % 2 == 1
            val (head, body) = LookWords.release(style, chapter = chapter, title = "Frieren: Beyond Journey's End", number = if (chapter) "142" else "12")
            ReleaseNotifications.post(context, if (chapter) NotifiedReleases.Kind.Chapter else NotifiedReleases.Kind.Episode, fakeId(index), "[${style?.label ?: "plain"}] $head", body)
        }
    }

    @Test
    fun clear() {
        val manager = NotificationManagerCompat.from(context)
        (0..UiStyle.entries.size).forEach { index ->
            manager.cancel(fakeId(index) * 2)
            manager.cancel(fakeId(index) * 2 + 1)
        }
    }
}
