package com.aniliphy.anilist

/** Where tapping a notification goes. */
sealed interface NoteTarget {
    data class User(val id: Int) : NoteTarget
    data class MediaPage(val id: Int) : NoteTarget
    data class Thread(val id: Int, val comment: Int?) : NoteTarget
    data class Activity(val id: Int) : NoteTarget
}

/** The inbox's filter groups. */
enum class NoteGroup(val label: String, val types: List<String>?) {
    All("All", null),
    Replies("Replies & mentions", listOf("ACTIVITY_REPLY", "ACTIVITY_MENTION", "ACTIVITY_MESSAGE", "ACTIVITY_REPLY_SUBSCRIBED", "THREAD_COMMENT_REPLY", "THREAD_COMMENT_MENTION", "THREAD_SUBSCRIBED")),
    Likes("Likes", listOf("ACTIVITY_LIKE", "ACTIVITY_REPLY_LIKE", "THREAD_LIKE", "THREAD_COMMENT_LIKE")),
    Follows("Follows", listOf("FOLLOWING")),
    Airing("Airing", listOf("AIRING")),
    Media("Media changes", listOf("RELATED_MEDIA_ADDITION", "MEDIA_DATA_CHANGE", "MEDIA_MERGE", "MEDIA_DELETION")),
}

object Inbox {
    /** Kinds worth a phone notification when they arrive (likes and site changes aren't). */
    val PHONE_TYPES = setOf(
        "ACTIVITY_REPLY", "ACTIVITY_MENTION", "ACTIVITY_MESSAGE", "THREAD_COMMENT_REPLY", "THREAD_COMMENT_MENTION", "FOLLOWING",
    )

    /** The line a notification reads as, like "mika liked your activity." or "Episode 7 of Frieren aired." */
    fun text(n: AniNotification): String {
        val who = n.user?.name.orEmpty()
        val title = n.media?.title?.display
        return when (n.type) {
            "AIRING" -> {
                val c = n.contexts.orEmpty()
                if (c.size >= 3) "${c[0]}${n.episode ?: ""}${c[1]}${title.orEmpty()}${c[2]}"
                else "Episode ${n.episode ?: "?"} of ${title.orEmpty()} aired."
            }
            "THREAD_COMMENT_REPLY", "THREAD_COMMENT_MENTION", "THREAD_SUBSCRIBED", "THREAD_COMMENT_LIKE", "THREAD_LIKE" ->
                "$who${clean(n.context)}${n.thread?.title?.trim()?.let { " $it" }.orEmpty()}"
            "RELATED_MEDIA_ADDITION", "MEDIA_DATA_CHANGE", "MEDIA_MERGE" -> "${title.orEmpty()}${clean(n.context)}"
            "MEDIA_DELETION" -> "${n.deletedMediaTitle.orEmpty()}${clean(n.context)}"
            else -> "$who${clean(n.context)}"
        }.replace(Regex("\\s+"), " ").trim()
    }

    /** AniList's context starts with a space (" liked your activity."); keep it, but never doubled. */
    private fun clean(context: String?): String = context?.let { if (it.startsWith(" ")) it else " $it" }.orEmpty()

    fun target(n: AniNotification): NoteTarget? = when {
        n.thread != null -> NoteTarget.Thread(n.thread.id, n.commentId)
        n.threadId != null -> NoteTarget.Thread(n.threadId, null)
        n.activityId != null -> NoteTarget.Activity(n.activityId)
        n.type == "FOLLOWING" && n.user != null -> NoteTarget.User(n.user.id)
        n.media != null -> NoteTarget.MediaPage(n.media.id)
        else -> null
    }

    /** Newer than [lastSeen] and worth telling on the phone; nothing on the first check. */
    fun forPhone(notes: List<AniNotification>, lastSeen: Int?): List<AniNotification> =
        if (lastSeen == null) emptyList() else notes.filter { it.id > lastSeen && it.type in PHONE_TYPES }.sortedByDescending { it.id }
}
