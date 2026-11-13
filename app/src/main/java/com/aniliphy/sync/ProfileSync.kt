package com.aniliphy.sync

import com.aniliphy.core.ActivityDay
import com.aniliphy.ui.ProfileLocalData
import kotlinx.serialization.Serializable

/**
 * The profile's own file in the sync gist (next to anilixy's, which it never touches): goals, showcase, celebrated
 * moments and each phone's day-by-day activity.
 */
@Serializable
internal data class ProfilePayload(
    val version: Int = VERSION,
    val profile: ProfileLocalData = ProfileLocalData(),
    /** Activity by device id, then by day ("2026-11-13"). Each phone only writes its own. */
    val activity: Map<String, Map<String, ActivityDay>> = emptyMap(),
) {
    companion object {
        const val VERSION = 1
        const val FILE_NAME = "aniliphy-profile.json"

        fun parse(text: String): ProfilePayload? = runCatching { SyncPayload.json.decodeFromString(serializer(), text) }
            .getOrNull()?.takeIf { it.version <= VERSION }
    }
}

internal object ProfileMerge {
    /**
     * Goals and the showcase: the newer whole set wins (so a goal removed on one phone stays removed); a tie keeps
     * [local]. Celebrated moments add up. Activity: this phone's from [local], every other phone's from [remote].
     */
    fun merge(local: ProfilePayload, remote: ProfilePayload, device: String): ProfilePayload {
        val l = local.profile
        val r = remote.profile
        val goals = if (r.goalsUpdated > l.goalsUpdated) r.goals to r.goalsUpdated else l.goals to l.goalsUpdated
        val showcase = if (r.showcaseUpdated > l.showcaseUpdated) r.showcase to r.showcaseUpdated else l.showcase to l.showcaseUpdated
        val profile = ProfileLocalData(
            goals = goals.first,
            goalsUpdated = goals.second,
            showcase = showcase.first,
            showcaseUpdated = showcase.second,
            celebrated = l.celebrated + r.celebrated,
            seeded = l.seeded + r.seeded,
        )
        val activity = remote.activity - device + local.activity.filterKeys { it == device }
        return ProfilePayload(ProfilePayload.VERSION, profile, activity)
    }
}
