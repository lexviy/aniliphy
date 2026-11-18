package com.aniliphy.novels

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.aniliphy.anime.BackgroundAudio
import com.aniliphy.anime.SleepTimer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class AloudSleep(val label: String, val minutes: Int?) {
    Off("Off", null),
    EndOfChapter("End of chapter", null),
    M15("15", 15),
    M30("30", 30),
    M60("60", 60),
}

/** The book being read aloud; the reader screen gives it, and it lives as long as that screen. */
interface AloudBook {
    val title: String
    fun chapterTitle(chapter: Int): String
    suspend fun sentences(chapter: Int): List<Spoken>
    fun onSentence(chapter: Int, index: Int)

    /** Moves the book (and its count) past [chapter]; the next chapter, or null at the end of the volume. */
    fun onChapterEnd(chapter: Int): Int?
}

/**
 * Light novels read aloud with the phone's text-to-speech. One sentence at a time, a few queued ahead; the media
 * controls (notification, lock screen, headphones) come from a MediaSession on [AloudPlayer], shown through the same
 * service as the anime's background audio, so only one of them plays.
 */
object ReadAloud {
    var active by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var chapter by mutableIntStateOf(0)
        private set
    var index by mutableIntStateOf(0)
        private set
    var spoken by mutableStateOf<List<Spoken>>(emptyList())
        private set
    var sleep by mutableStateOf(AloudSleep.Off)
        private set
    var sleepLeftMs by mutableStateOf<Long?>(null)
        private set
    var voices by mutableStateOf<List<Voice>>(emptyList())
        private set
    var book: AloudBook? = null
        private set

    private const val AHEAD = 3
    private const val PARAGRAPH_MS = 350L
    private const val TICK_MS = 1_000L

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var whenReady: (() -> Unit)? = null
    private var generation = 0
    private var queued = -1
    private var endQueued = false
    private var player: AloudPlayer? = null
    private var sleepEndsAt: Long? = null
    private var hasFocus = false
    private var resumeOnGain = false
    private var noisyRegistered = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (playing) {
                resumeOnGain = true
                pause(keepFocus = true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) {
                resumeOnGain = false
                play()
            }
        }
    }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val ends = sleepEndsAt ?: return
            val left = ends - SystemClock.elapsedRealtime()
            sleepLeftMs = left.coerceAtLeast(0)
            if (left <= 0) {
                chooseSleep(AloudSleep.Off)
                pause()
                return
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun settings(): NovelSettings? = app?.let(NovelSettings::get)

    /** Starts reading [book] at sentence [from] of [chapter]. */
    fun start(context: Context, book: AloudBook, chapter: Int, from: Int) {
        val appContext = context.applicationContext
        if (active && this.book !== book) stop()
        app = appContext
        this.book = book
        active = true
        val player = player ?: AloudPlayer(Looper.getMainLooper()).also { player = it }
        BackgroundAudio.start(appContext, player)
        scope.launch {
            val sentences = book.sentences(chapter)
            if (this@ReadAloud.book !== book) return@launch
            this@ReadAloud.chapter = chapter
            spoken = sentences
            index = from.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
            book.onSentence(chapter, index)
            withVoice { play() }
        }
    }

    /** Goes on from sentence [from] of another chapter the reader moved to by hand. */
    fun moveTo(chapter: Int, from: Int) {
        val book = book ?: return
        scope.launch {
            val sentences = book.sentences(chapter)
            if (this@ReadAloud.book !== book) return@launch
            this@ReadAloud.chapter = chapter
            spoken = sentences
            jumpTo(from)
        }
    }

    private fun withVoice(then: () -> Unit) {
        val context = app ?: return
        if (tts != null && ttsReady) {
            then()
            return
        }
        whenReady = then
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            handler.post {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    setUp()
                    whenReady?.invoke()
                    whenReady = null
                } else {
                    Toast.makeText(context, "No text-to-speech on this phone. Add one in Android settings › Text-to-speech.", Toast.LENGTH_LONG).show()
                    runCatching {
                        context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    stop()
                }
            }
        }
    }

    private fun setUp() {
        val tts = tts ?: return
        val settings = settings() ?: return
        tts.setAudioAttributes(attributes)
        tts.setSpeechRate(settings.aloudSpeed)
        tts.setPitch(settings.aloudPitch)
        val installed = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
        settings.aloudVoice.takeIf { it.isNotEmpty() }?.let { name -> installed.firstOrNull { it.name == name }?.let { tts.voice = it } }
        val language = (runCatching { tts.voice }.getOrNull() ?: runCatching { tts.defaultVoice }.getOrNull())?.locale?.language
        voices = installed.filter { language == null || it.locale.language == language }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.locale.toLanguageTag() }, { it.name }))
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                handler.post { started(utteranceId) }
            }

            override fun onDone(utteranceId: String) {
                handler.post { done(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                handler.post { done(utteranceId) }
            }
        })
    }

    /** The voice now in use, by name. */
    fun voiceName(): String? = runCatching { tts?.voice?.name }.getOrNull()

    private fun started(id: String) {
        val (from, what) = id.split(':', limit = 2).let { it[0].toIntOrNull() to it.getOrNull(1) }
        if (from != generation) return
        val sentence = what?.toIntOrNull() ?: return
        index = sentence
        book?.onSentence(chapter, sentence)
        player?.changed()
        fill()
    }

    private fun done(id: String) {
        val (from, what) = id.split(':', limit = 2).let { it[0].toIntOrNull() to it.getOrNull(1) }
        if (from != generation || what != "end") return
        chapterEnd()
    }

    private fun chapterEnd() {
        val book = book ?: return
        if (sleep == AloudSleep.EndOfChapter) {
            chooseSleep(AloudSleep.Off)
            pause()
            return
        }
        val next = book.onChapterEnd(chapter)
        if (next == null) {
            pause()
            index = 0
            return
        }
        scope.launch {
            val sentences = book.sentences(next)
            if (this@ReadAloud.book !== book) return@launch
            chapter = next
            spoken = sentences
            index = 0
            player?.changed()
            if (playing) speakFrom(0)
        }
    }

    fun play() {
        if (!active) return
        if (tts == null || !ttsReady) {
            withVoice { play() }
            return
        }
        if (!focus()) return
        playing = true
        if (!noisyRegistered) {
            app?.let { ContextCompat.registerReceiver(it, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) }
            noisyRegistered = true
        }
        speakFrom(index)
        player?.changed()
    }

    fun pause(keepFocus: Boolean = false) {
        playing = false
        generation++
        tts?.stop()
        if (!keepFocus) dropFocus()
        if (noisyRegistered) {
            runCatching { app?.unregisterReceiver(noisy) }
            noisyRegistered = false
        }
        player?.changed()
    }

    fun toggle() = if (playing) pause() else play()

    fun skip(direction: Int) {
        if (spoken.isEmpty()) return
        jumpTo((index + direction).coerceIn(0, spoken.size - 1))
    }

    /** Reads from sentence [sentence] (a tap on it in the page, ⏮ ⏭). */
    fun jumpTo(sentence: Int) {
        index = sentence.coerceIn(0, (spoken.size - 1).coerceAtLeast(0))
        book?.onSentence(chapter, index)
        if (playing) speakFrom(index)
        player?.changed()
    }

    /** Stops for good: the reader closed or ✕. */
    fun stop() {
        val context = app
        playing = false
        active = false
        generation++
        whenReady = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        dropFocus()
        if (noisyRegistered) {
            runCatching { context?.unregisterReceiver(noisy) }
            noisyRegistered = false
        }
        chooseSleep(AloudSleep.Off)
        book = null
        spoken = emptyList()
        player?.changed()
        if (context != null && BackgroundAudio.player === player) BackgroundAudio.stop(context)
    }

    private fun speakFrom(sentence: Int) {
        generation++
        tts?.stop()
        queued = sentence - 1
        endQueued = false
        fill()
    }

    private fun fill() {
        val tts = tts ?: return
        if (!playing) return
        val pauses = settings()?.aloudParagraphPause != false
        while (queued < spoken.size - 1 && queued < index + AHEAD) {
            queued++
            val sentence = spoken[queued]
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume()) }
            tts.speak(sentence.text, TextToSpeech.QUEUE_ADD, params, "$generation:$queued")
            if (sentence.paragraphEnd && pauses) tts.playSilentUtterance(PARAGRAPH_MS, TextToSpeech.QUEUE_ADD, "$generation:p")
        }
        if (queued >= spoken.size - 1 && !endQueued) {
            endQueued = true
            tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, "$generation:end")
        }
    }

    /** The sleep timer's fade, the same as the player's. */
    private fun volume(): Float = sleepEndsAt?.let { SleepTimer.volume(it - SystemClock.elapsedRealtime()) } ?: 1f

    private fun focus(): Boolean {
        if (hasFocus) return true
        val audio = app?.getSystemService(AudioManager::class.java) ?: return true
        hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun dropFocus() {
        if (!hasFocus) return
        app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun again() {
        if (playing) speakFrom(index)
    }

    fun setSpeed(speed: Float) {
        settings()?.aloudSpeed = speed
        tts?.setSpeechRate(speed)
        again()
        player?.changed()
    }

    fun setPitch(pitch: Float) {
        settings()?.aloudPitch = pitch
        tts?.setPitch(pitch)
        again()
    }

    fun setParagraphPause(on: Boolean) {
        settings()?.aloudParagraphPause = on
        again()
    }

    /** Uses [voice] from now on; says a sample when nothing is being read. */
    fun setVoice(voice: Voice) {
        settings()?.aloudVoice = voice.name
        val tts = tts ?: return
        tts.voice = voice
        if (playing) {
            again()
        } else {
            tts.speak("This is how this voice sounds.", TextToSpeech.QUEUE_FLUSH, null, "sample")
        }
    }

    fun chooseSleep(choice: AloudSleep) {
        sleep = choice
        handler.removeCallbacks(ticker)
        val minutes = choice.minutes
        if (minutes == null) {
            sleepEndsAt = null
            sleepLeftMs = null
            return
        }
        sleepEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepLeftMs = minutes * 60_000L
        handler.postDelayed(ticker, TICK_MS)
    }

    fun speed(): Float = settings()?.aloudSpeed ?: 1f
}

/** What the media session sees: play/pause, a sentence back or forward, and the chapter as the item. */
@OptIn(UnstableApi::class)
internal class AloudPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    fun changed() = invalidateState()

    override fun getState(): State {
        val aloud = ReadAloud
        val speed = aloud.speed()
        val chapterTitle = aloud.book?.chapterTitle(aloud.chapter).orEmpty()
        val sentence = aloud.spoken.getOrNull(aloud.index)?.text.orEmpty()
        val metadata = MediaMetadata.Builder()
            .setTitle(aloud.book?.title ?: "aniliphy")
            .setArtist(listOf(chapterTitle, sentence.take(SNIPPET).let { if (sentence.length > SNIPPET) "\"$it…\"" else "\"$it\"" })
                .filter { it.isNotEmpty() && it != "\"\"" }.joinToString(" · "))
            .setAlbumTitle(chapterTitle)
            .build()
        val duration = ReadAloudText.millis(aloud.spoken, 0, speed)
        val position = duration - ReadAloudText.millis(aloud.spoken, aloud.index, speed)
        // Items before and after, so the system shows ⏮ ⏭; they mean a sentence back or forward.
        val items = listOf(
            MediaItemData.Builder("before").build(),
            MediaItemData.Builder("now").setMediaMetadata(metadata).setDurationUs(duration * 1000).build(),
            MediaItemData.Builder("after").build(),
        )
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(aloud.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (aloud.active) Player.STATE_READY else Player.STATE_IDLE)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(1)
            .setContentPositionMs(position)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) ReadAloud.play() else ReadAloud.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> ReadAloud.skip(1)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> ReadAloud.skip(-1)
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        ReadAloud.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val SNIPPET = 40
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_RELEASE,
        ).build()
    }
}
