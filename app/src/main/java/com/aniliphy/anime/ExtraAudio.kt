package com.aniliphy.anime

import java.util.concurrent.ConcurrentHashMap

/** A spoken-language track served apart from the video, as some Aniyomi sources give them. */
data class AudioTrack(val url: String, val label: String)

/**
 * The separate audio tracks of a video, by the video's address. Kept here rather than in the stream itself, since the
 * stream class is part of the API extensions are built against, and adding to it would break the ones already built.
 */
object ExtraAudio {
    private val tracks = ConcurrentHashMap<String, List<AudioTrack>>()

    fun put(videoUrl: String, list: List<AudioTrack>) {
        if (list.isEmpty()) tracks.remove(videoUrl) else tracks[videoUrl] = list
    }

    fun of(videoUrl: String): List<AudioTrack> = tracks[videoUrl].orEmpty()

    /** The one to play with [videoUrl]: the language of the Sub or Dub picked, or else the first. */
    fun pick(videoUrl: String, audio: com.aniliphy.source.AudioVersion): AudioTrack? = choose(of(videoUrl), audio)

    fun choose(list: List<AudioTrack>, audio: com.aniliphy.source.AudioVersion): AudioTrack? {
        val wanted = AnimeRules.audioLanguageOf(audio)
        return list.firstOrNull { AnimeRules.languageOf(it.label) == wanted || AnimeRules.sameLanguage(it.label, wanted) } ?: list.firstOrNull()
    }
}

/** [video] played together with the separate audio at [audioUrl], when there is one, read through [factory]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun withAudio(
    factory: androidx.media3.exoplayer.source.MediaSource.Factory,
    video: androidx.media3.exoplayer.source.MediaSource,
    audioUrl: String?,
): androidx.media3.exoplayer.source.MediaSource {
    if (audioUrl == null) return video
    val audio = factory.createMediaSource(
        androidx.media3.common.MediaItem.Builder().setUri(audioUrl).setMimeType(videoMimeOf(audioUrl)).build(),
    )
    return androidx.media3.exoplayer.source.MergingMediaSource(video, audio)
}
