package com.aniliphy.core

import android.content.Context

/** When incognito turns itself off. */
enum class IncognitoOff(val label: String, val hours: Int?) {
    Never("Never", null),
    Hour("After 1 h", 1),
    ThreeHours("After 3 h", 3),
    Tomorrow("Tomorrow", null),
}

/** How long the app may be away before the lock asks again. */
enum class LockAfter(val label: String, val ms: Long) {
    Now("Right away", 0),
    Minute("1 min", 60_000),
    Five("5 min", 5 * 60_000),
    Fifteen("15 min", 15 * 60_000),
}

/** Privacy choices. Kept out of backups, so a restore never turns incognito or the lock on by surprise. */
class PrivacyPrefs(context: Context) : PrefStore(context, "privacy") {
    var incognito by boolPref("incognito", false)
    /** When it turns itself off (ms), 0 for never. */
    var incognitoUntil by stringPref("incognito_until", "0")
    var autoOff by enumPref("auto_off", IncognitoOff.Never, IncognitoOff.entries)
    var lock by boolPref("lock", false)
    var lockAfter by enumPref("lock_after", LockAfter.Minute, LockAfter.entries)
    var hideInRecents by boolPref("hide_recents", false)
}

/**
 * Incognito: while on, nothing you watch or read is kept (places, Continue, activity) or sent (AniList, the Gist).
 * Read from anywhere, including workers; [on] is Compose state, so screens follow it.
 */
object Incognito {
    @Volatile private var prefs: PrivacyPrefs? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) { if (prefs == null) prefs = PrivacyPrefs(context.applicationContext) }
    }

    fun prefs(context: Context): PrivacyPrefs {
        init(context)
        return prefs!!
    }

    val on: Boolean
        get() {
            val p = prefs ?: return false
            if (!p.incognito) return false
            val until = p.incognitoUntil.toLongOrNull() ?: 0
            if (until > 0 && System.currentTimeMillis() >= until) {
                p.incognito = false
                p.incognitoUntil = "0"
                return false
            }
            return true
        }

    fun set(context: Context, value: Boolean, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        p.incognitoUntil = (if (value) until(p.autoOff, now) else 0L).toString()
        p.incognito = value
    }

    /** When incognito started at [now] turns off with [off]; 0 for never. */
    fun until(off: IncognitoOff, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long = when (off) {
        IncognitoOff.Never -> 0
        IncognitoOff.Tomorrow -> java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        else -> now + off.hours!! * 3_600_000L
    }
}
