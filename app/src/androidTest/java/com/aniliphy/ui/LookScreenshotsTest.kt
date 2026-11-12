package com.aniliphy.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aniliphy.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opens the app once per look and tab and saves a screenshot of each, to look over for cut or crowded text after a
 * change. It only looks: nothing is tapped, so nothing opens or changes. The look and tab picked before are put back
 * at the end. Run it with scripts/look-shots.sh, which pulls the pictures; the arguments `looks` and `tabs` (comma
 * lists, e.g. `-e looks Modern,Eki -e tabs Anime`) narrow it down.
 */
@RunWith(AndroidJUnit4::class)
class LookScreenshotsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun everyLookAndTab() {
        val args = InstrumentationRegistry.getArguments()
        val looks = args.getString("looks")?.split(',')?.map { name -> UiStyle.valueOf(name.trim()) } ?: UiStyle.entries
        val tabs = args.getString("tabs")?.split(',')?.map { it.trim() } ?: listOf("AniList", "Anime", "Manga", "LightNovel", "Settings")
        val wait = args.getString("waitMs")?.toLongOrNull() ?: 7_000L
        val out = File(context.getExternalFilesDir(null), "look-shots").apply { deleteRecursively(); mkdirs() }

        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val state = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
        val hadStyle = appearance.contains("ui_style")
        val oldStyle = appearance.getString("ui_style", null)
        val oldTab = state.getString("last_tab", null)
        try {
            for (look in looks) {
                for (tab in tabs) {
                    appearance.edit().putString("ui_style", look.name).commit()
                    state.edit().putString("last_tab", tab).commit()
                    // Started fresh each time, so the look and tab are read again. Not through ActivityScenario, which
                    // waits for the app to go still, and the animated backgrounds never do.
                    context.startActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                    Thread.sleep(wait)
                    val shot: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
                    val file = File(out, "${look.name}_$tab.png")
                    shot?.let { picture -> file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    Log.i("LookShots", "saved ${file.name}")
                }
            }
        } finally {
            appearance.edit().apply { if (hadStyle) putString("ui_style", oldStyle) else remove("ui_style") }.commit()
            state.edit().apply { if (oldTab != null) putString("last_tab", oldTab) else remove("last_tab") }.commit()
        }
    }
}
