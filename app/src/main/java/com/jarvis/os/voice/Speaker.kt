package com.jarvis.os.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.os.debug.DebugLog
import java.util.Locale

/**
 * Text-to-speech wrapper. [onSpeechEnded] fires (on the main thread) when speech
 * ends, or immediately if TTS is unavailable. If [speak] is called before the
 * engine has finished initializing, the text is buffered and spoken once ready —
 * this matters because replies can arrive faster than TTS init completes.
 *
 * ## Why every utterance carries a sequence number
 *
 * [speak] uses `QUEUE_FLUSH`, so speaking again cuts the current utterance off.
 * The platform then reports the *old* utterance as finished — after the new one
 * has already started. Without a way to tell the two apart, that late report
 * reads as "the reply is over", and the caller starts the microphone while JARVIS
 * is still mid-sentence. On this app that is not a cosmetic bug: the recogniser
 * mutes `STREAM_MUSIC` to suppress its earcon, and TTS plays on `STREAM_MUSIC` —
 * so JARVIS would go silent halfway through his own answer.
 *
 * So each utterance gets a monotonic id, and [onSpeechStarted]/[onSpeechEnded]
 * report it, letting a caller that tracks the newest sequence discard the stale
 * one. A bare "speech ended" callback carrying no sequence cannot make that
 * distinction at all, which is why this class no longer offers one.
 */
class Speaker(context: Context) {

    /** One selectable voice, described for the picker. */
    data class Option(val id: String, val label: String, val quality: Int, val needsNetwork: Boolean)

    /** Fires when the engine is ready, so a picker can populate itself. */
    var onVoicesReady: () -> Unit = {}

    /** The utterance identified by this sequence has begun coming out of the speaker. */
    var onSpeechStarted: (seq: Long) -> Unit = {}

    /**
     * The utterance identified by this sequence is over, whether it finished,
     * errored, or was cut short (`interrupted` = true, from [stop] or a
     * `QUEUE_FLUSH`). Always fires exactly once per sequence handed to [speak],
     * including when there is no working TTS engine at all — a caller waiting on
     * it must never be left waiting.
     */
    var onSpeechEnded: (seq: Long, interrupted: Boolean) -> Unit = { _, _ -> }

    private val settings = VoiceSettings(context)

    /**
     * The user's languages, set by the owner (see [AssistantEngine]). Each reply
     * is spoken in the language of its own script — a Hindi or Arabic answer must
     * not come out of an English voice — falling back to the primary for the
     * Latin-script languages, which cannot be told apart from text. Default
     * English until the user chooses.
     */
    var languagePrefs: LanguagePrefs = LanguagePrefs.DEFAULT

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Pair<Long, String>? = null

    /** Which language the engine is currently configured for, to avoid re-setting it. */
    private var spokenLang: Language? = null

    /**
     * Allocated in [speak] rather than [doSpeak] so that a reply buffered before
     * the engine finished initialising keeps the same id it was promised.
     */
    private var nextSeq = 0L

    /** Sequences already reported ended, so a double report cannot fire twice. */
    private var lastEndedSeq = -1L

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                configureVoiceFor(engine, languagePrefs.primary)
                engine.setPitch(VoicePreference.PITCH)
                engine.setSpeechRate(VoicePreference.SPEECH_RATE)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val seq = seqOf(utteranceId) ?: return
                        main.post { onSpeechStarted(seq) }
                    }

                    override fun onDone(utteranceId: String?) {
                        // A preview is not a reply — it must not advance the loop.
                        if (utteranceId == PREVIEW_ID) return
                        reportEnded(utteranceId, interrupted = false)
                    }

                    /**
                     * Delivered when an utterance is cut off — by [stop], or by the
                     * `QUEUE_FLUSH` of a following [speak]. **Overriding this is the
                     * whole point of the sequence work**: without it the platform
                     * reports nothing at all for an interrupted utterance, so a
                     * caller that arms itself on [onSpeechStarted] and disarms on
                     * [onSpeechEnded] would stay armed forever after the first
                     * interruption. Non-abstract since API 23, so this is free at
                     * `minSdk 26`.
                     *
                     * Reports `interrupted` rather than folding it into a plain
                     * "finished", because the two mean opposite things to a caller:
                     * a reply that ran to its end should be followed by whatever was
                     * queued behind it, and one the user cut off should not.
                     */
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == PREVIEW_ID) return
                        reportEnded(utteranceId, interrupted = interrupted)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == PREVIEW_ID) return
                        reportEnded(utteranceId, interrupted = false)
                    }
                })
                ready = true
                main.post { onVoicesReady() }
                pending?.let { (seq, text) ->
                    pending = null
                    doSpeak(seq, text)
                }
            } else {
                failed = true
                pending?.let { (seq, _) ->
                    pending = null
                    endNow(seq)
                }
            }
        }
    }

    /**
     * Point the engine at [lang], but only when it is not already there — so a
     * run of replies in one language does not re-select the voice each time.
     *
     * The voice ranker and the user's saved voice are English choices (the ranking
     * in [VoicePreference] is tuned for English), so they are applied only when
     * English is being spoken; the other four languages take the engine's own
     * default voice for their locale, which is the right one for them and the only
     * one this off-device code can safely assume exists. A language with no data
     * installed falls back to US English rather than going silent — the wrong
     * accent is still better than nothing while the user installs the voice.
     */
    private fun configureVoiceFor(engine: TextToSpeech, lang: Language) {
        if (spokenLang == lang) return
        spokenLang = lang
        val res = engine.setLanguage(Locale.forLanguageTag(lang.tag))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
            if (lang != Language.English) return
        }
        if (lang == Language.English) selectBestVoice(engine)
    }

    /**
     * The engine's default voice is usually the blandest one installed. Rank the
     * available voices and take the best — this is most of the difference between
     * "robot" and "assistant", at no cost and no added latency.
     */
    private fun selectBestVoice(engine: TextToSpeech) {
        // An explicit choice always wins over ranking — the user has heard both.
        settings.chosenVoice?.let { saved ->
            val match = try {
                engine.voices?.firstOrNull { it.name == saved }
            } catch (e: Exception) {
                null
            }
            if (match != null) {
                engine.voice = match
                DebugLog.log(DebugLog.Stage.SPOKE, "voice: $saved (chosen by the user)")
                return
            }
        }
        val best = try {
            engine.voices
                ?.filter { it.name != null && it.locale != null }
                ?.maxByOrNull {
                    VoicePreference.score(
                        language = it.locale?.language.orEmpty(),
                        country = it.locale?.country.orEmpty(),
                        name = it.name.orEmpty(),
                        quality = it.quality,
                        networkRequired = it.isNetworkConnectionRequired,
                    )
                }
        } catch (e: Exception) {
            // Some engines throw when queried too early; the default voice is fine.
            null
        } ?: return

        val score = VoicePreference.score(
            language = best.locale?.language.orEmpty(),
            country = best.locale?.country.orEmpty(),
            name = best.name.orEmpty(),
            quality = best.quality,
            networkRequired = best.isNetworkConnectionRequired,
        )
        if (score == VoicePreference.REJECT) return
        engine.voice = best
        DebugLog.log(DebugLog.Stage.SPOKE, "voice: ${best.name} (${best.locale}), quality ${best.quality}")
    }

    /** Voices the device can actually use, best first. */
    fun options(): List<Option> {
        val engine = tts ?: return emptyList()
        return try {
            engine.voices.orEmpty()
                .filter { it.name != null && it.locale != null }
                .map { voice ->
                    voice to VoicePreference.score(
                        language = voice.locale?.language.orEmpty(),
                        country = voice.locale?.country.orEmpty(),
                        name = voice.name.orEmpty(),
                        quality = voice.quality,
                        networkRequired = voice.isNetworkConnectionRequired,
                    )
                }
                .filter { it.second != VoicePreference.REJECT }
                .sortedByDescending { it.second }
                .map { (voice, _) ->
                    Option(
                        id = voice.name,
                        label = VoicePreference.describe(
                            voice.locale?.country.orEmpty(),
                            voice.name,
                            voice.quality,
                        ),
                        quality = voice.quality,
                        needsNetwork = voice.isNetworkConnectionRequired,
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The voice in use right now, if the engine will say. */
    fun currentVoiceId(): String? = try {
        tts?.voice?.name
    } catch (e: Exception) {
        null
    }

    /** Switch voice and remember it. Returns false if the engine refused. */
    fun useVoice(id: String): Boolean {
        val engine = tts ?: return false
        val voice = try {
            engine.voices?.firstOrNull { it.name == id }
        } catch (e: Exception) {
            null
        } ?: return false
        engine.voice = voice
        settings.chosenVoice = id
        return true
    }

    /** True when nothing installed is good enough to be worth keeping. */
    fun bestQualityAvailable(): Int = options().maxOfOrNull { it.quality } ?: 0

    fun shouldOfferBetterVoices(): Boolean =
        !settings.offeredVoiceDownload && bestQualityAvailable() < VoicePreference.GOOD_QUALITY

    fun markVoiceDownloadOffered() {
        settings.offeredVoiceDownload = true
    }

    /** Speak a sample without disturbing the assistant loop. */
    fun preview(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, PREVIEW_ID)
    }

    /**
     * Speak [text], cutting off anything already being spoken.
     *
     * Returns the sequence number for this utterance. Every returned sequence is
     * followed by exactly one [onSpeechEnded] — on every path, including a device
     * with no usable TTS engine — so a caller may safely treat it as a promise.
     */
    fun speak(text: String): Long {
        val seq = nextSeq++
        when {
            failed -> endNow(seq)
            ready -> doSpeak(seq, text)
            // Buffered until init finishes. The sequence is already allocated, so
            // the caller can start tracking it before a word has been spoken.
            else -> pending = seq to text
        }
        return seq
    }

    private fun doSpeak(seq: Long, text: String) {
        val engine = tts
        if (engine == null) {
            endNow(seq)
            return
        }
        // Speak this reply in the language of its own script, so a Hindi or Arabic
        // answer is not read out by an English voice.
        configureVoiceFor(engine, spokenLanguageFor(text, languagePrefs))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceIdFor(seq))
    }

    /**
     * Cut off whatever is being spoken.
     *
     * **The caller must not wait for a callback after calling this.** The platform
     * only delivers `onStop` when an utterance is actually in progress; if TTS is
     * already idle, `stop()` produces no callback of any kind. Anything that has
     * to become true once speech is over must be set synchronously here by the
     * caller, not deferred to [onSpeechEnded].
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Whether the engine believes it is speaking right now. Used to reconcile
     * state after a gap the callbacks could have fallen into — a returning
     * Activity asking the engine rather than assuming.
     */
    fun isSpeaking(): Boolean = try {
        tts?.isSpeaking == true
    } catch (e: Exception) {
        false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /** Reports an end for a real utterance id, ignoring previews and duplicates. */
    private fun reportEnded(utteranceId: String?, interrupted: Boolean) {
        val seq = seqOf(utteranceId) ?: return
        main.post { deliverEnded(seq, interrupted) }
    }

    /** Reports an end for an utterance that never reached the engine at all. */
    private fun endNow(seq: Long) {
        main.post { deliverEnded(seq, interrupted = false) }
    }

    /**
     * Some engines deliver both `onDone` and `onStop` for the same utterance, so
     * the sequence is checked here rather than trusting the platform to report
     * once. Main thread only, which is what makes the bare comparison safe.
     */
    private fun deliverEnded(seq: Long, interrupted: Boolean) {
        if (seq <= lastEndedSeq) return
        lastEndedSeq = seq
        onSpeechEnded(seq, interrupted)
    }

    private fun utteranceIdFor(seq: Long): String = "$UTTERANCE_PREFIX$seq"

    /** The sequence in an utterance id, or null for a preview or anything foreign. */
    private fun seqOf(utteranceId: String?): Long? {
        val id = utteranceId ?: return null
        if (!id.startsWith(UTTERANCE_PREFIX)) return null
        return id.removePrefix(UTTERANCE_PREFIX).toLongOrNull()
    }

    private companion object {
        const val UTTERANCE_PREFIX = "jarvis#"
        const val PREVIEW_ID = "jarvis_preview"
    }
}
