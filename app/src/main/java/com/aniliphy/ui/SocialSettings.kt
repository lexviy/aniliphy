package com.aniliphy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Social settings kept between launches (the background check reads them too). */
object SocialPrefs {
    private const val FILE = "social"
    private const val NOTIFY = "notify_friends"
    private const val LAST = "last_feed_id"
    private const val NOTIFY_INBOX = "notify_inbox"
    private const val LAST_NOTE = "last_note_id"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun notifyFriends(context: Context): Boolean = prefs(context).getBoolean(NOTIFY, true)

    fun setNotifyFriends(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(NOTIFY, value).apply()
        // Turning it back on starts fresh, so old news isn't told late.
        if (value) prefs(context).edit().remove(LAST).apply()
    }

    fun lastFeedId(context: Context): Int? = prefs(context).getInt(LAST, -1).takeIf { it >= 0 }

    fun setLastFeedId(context: Context, id: Int) {
        prefs(context).edit().putInt(LAST, id).apply()
    }

    fun notifyInbox(context: Context): Boolean = prefs(context).getBoolean(NOTIFY_INBOX, true)

    fun setNotifyInbox(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(NOTIFY_INBOX, value).apply()
        if (value) prefs(context).edit().remove(LAST_NOTE).apply()
    }

    fun lastNoteId(context: Context): Int? = prefs(context).getInt(LAST_NOTE, -1).takeIf { it >= 0 }

    fun setLastNoteId(context: Context, id: Int) {
        prefs(context).edit().putInt(LAST_NOTE, id).apply()
    }
}

@Composable
internal fun SocialSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var notify by remember { mutableStateOf(SocialPrefs.notifyFriends(context)) }
    var inbox by remember { mutableStateOf(SocialPrefs.notifyInbox(context)) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("Social", onBack)
        SectionTitle("Notifications")
        SwitchRow(
            "When a friend finishes something on your list",
            notify,
            onChange = {
                notify = it
                SocialPrefs.setNotifyFriends(context, it)
            },
            description = "Checked with new episodes and chapters, about every half hour. AniList doesn't say their score in activity.",
        )
        SwitchRow(
            "New replies, mentions, messages and follows",
            inbox,
            onChange = {
                inbox = it
                SocialPrefs.setNotifyInbox(context, it)
            },
            description = "From your AniList inbox. Likes and airing stay in the Inbox tab.",
        )
        SectionTitle("AniList links")
        Hint(
            "aniliphy can open anilist.co links to titles and people. Android only lets that happen once you allow it: " +
                "in the next screen, tap “Add link” and tick anilist.co.",
            Modifier.padding(horizontal = 20.dp),
        )
        ActionRow("Open links by default…", onClick = { context.openByDefaultSettings() }, color = LocalAppTheme.current.primary)
    }
}

private fun Context.openByDefaultSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$packageName"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    }
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
