package harmonizer.app

import harmonizer.core.*

/**
 * Il runtime che lega motore, transport, mute e voci.
 *
 * Non conosce Android né il MIDI: emette attraverso [out], quindi è
 * collaudabile su JVM senza strumento. Spec §6, §7, §8.
 */

/** Destinazione dei messaggi. Implementata dal livello MIDI. */
interface MidiOut {
    fun noteOn(ch: Int, note: Int, vel: Int)
    fun noteOff(ch: Int, note: Int)
    fun cc(ch: Int, num: Int, value: Int)
    fun pitchBend(ch: Int, value14: Int)
    fun aftertouch(ch: Int, value: Int)
    fun programChange(ch: Int, pc: Int) {}
}

data class HarmConfig(
    var leadChannel: Int = 1,
    var voices: List<VoiceConfig> = listOf(
        VoiceConfig("armonia 1", listOf(Degrees.THIRD), SelectionMode.VOICE_LEADING, 48, 96, 2)
    ),
    /** CC inoltrati alle voci: spec §8.5. */
    var mirrorCCs: IntArray = intArrayOf(2, 11, 1),
    var initialExpression: Int = 100,
    var expressionScalePercent: Int = 85,
    var thinMs: Int = 5,
    var minDurationMs: Int = 0,
    var breathGate: Int = 0,
    var resyncMode: ResyncMode = ResyncMode.TO_BAR_1,
    /** CC di controllo, consumati e mai inoltrati: spec §6.1. */
    var ccMute: Int = 86,
    var ccResync: Int = 87,
    var longPressMs: Int = 800
)

class Harmonizer(
    private val out: MidiOut,
    var config: HarmConfig = HarmConfig()
) {
    val transport = Transport()
    val tapTempo = TapTempo()
    private val tracker = VoiceTracker(maxNotes = 8, maxVoices = 4)
    private val cache = AllowedCache()
    private val engine = HarmonyEngine(snapMode = SnapMode.SCALE)
    private val states = Array(4) { VoiceState() }

    var progression: Progression = progressioneDefault()
        set(v) { field = v; lastStepIndex = -1 }

    var muted = false
        private set

    private var lastStepIndex = -1
    private var lastBreath = 0
    private val lastCcSent = Array(17) { IntArray(128) { -1 } }
    private val lastCcTime = Array(17) { LongArray(128) }
    private var pendingResyncPressAt = 0L
    private var mutePressAt = 0L

    /** Statistiche per la diagnostica. */
    var lastProcessNanos = 0L
        private set
    var messagesIn = 0L
        private set

    // ------------------------------------------------------------- ingresso

    fun onNoteOn(note: Int, vel: Int, now: Long) {
        val t0 = System.nanoTime()
        messagesIn++

        out.noteOn(config.leadChannel, note, vel)

        val slot = tracker.open(note, vel)
        if (slot >= 0 && !muted && lastBreath >= config.breathGate) {
            emitHarmony(slot, note, vel, now)
        }
        lastProcessNanos = System.nanoTime() - t0
    }

    fun onNoteOff(note: Int, now: Long) {
        val t0 = System.nanoTime()
        messagesIn++

        out.noteOff(config.leadChannel, note)

        val slot = tracker.indexOf(note)
        if (slot >= 0) {
            for (v in config.voices.indices) {
                val h = tracker.harmonyAt(slot, v)
                if (h >= 0) out.noteOff(config.voices[v].channel, h)
            }
            tracker.close(slot)
        }
        lastProcessNanos = System.nanoTime() - t0
    }

    fun onControlChange(num: Int, value: Int, now: Long) {
        messagesIn++

        // CC di controllo: consumati, mai inoltrati (spec §6.1)
        if (num == config.ccMute) { handleMuteCc(value, now); return }
        if (num == config.ccResync) { handleResyncCc(value, now); return }

        if (num == 2 || num == 11) lastBreath = value

        // specchio dell'espressione verso le voci
        if (config.mirrorCCs.contains(num)) {
            forwardCc(num, value, now)
        }
    }

    fun onPitchBend(value14: Int) {
        messagesIn++
        out.pitchBend(config.leadChannel, value14)
        for (v in config.voices) out.pitchBend(v.channel, value14)
    }

    fun onAftertouch(value: Int) {
        messagesIn++
        out.aftertouch(config.leadChannel, value)
        for (v in config.voices) out.aftertouch(v.channel, value)
    }

    // -------------------------------------------------------------- comandi

    private fun handleMuteCc(value: Int, now: Long) {
        if (value >= 64) {                       // solo fronte di salita
            mutePressAt = now
            setMuted(!muted)
        }
    }

    private fun handleResyncCc(value: Int, now: Long) {
        if (value >= 64) {
            pendingResyncPressAt = now
            transport.resync(now, config.resyncMode)
        } else {
            val held = (now - pendingResyncPressAt) / 1_000_000
            if (held >= config.longPressMs) stopAll(now)
        }
    }

    fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m
        if (m) flushHarmony()
    }

    /** Spec §6.3. Ferma progressione e armonia, MAI la melodia. */
    fun stopAll(now: Long) {
        transport.stop()
        flushHarmony()
        muted = false
        lastStepIndex = -1
        for (s in states) s.reset()
    }

    private fun flushHarmony() {
        for (i in 0 until tracker.capacity) {
            if (!tracker.isActive(i)) continue
            for (v in config.voices.indices) {
                val h = tracker.harmonyAt(i, v)
                if (h >= 0) {
                    out.noteOff(config.voices[v].channel, h)
                    tracker.setHarmony(i, v, -1)
                }
            }
        }
    }

    // ------------------------------------------------------------- armonia

    private fun applyStep(quarto: Int) {
        val idx = progression.stepIndexAt(quarto)
        if (idx == lastStepIndex) return
        lastStepIndex = idx
        val step = progression.steps[idx]
        val cached = cache.get(step.chord.root, step.scale)
        engine.setContext(step.chord, step.scale)
        if (cached != null) engine.useAllowed(cached)
    }

    private fun emitHarmony(slot: Int, note: Int, vel: Int, now: Long) {
        applyStep(transport.quarto(now))
        for (v in config.voices.indices) {
            val cfg = config.voices[v]
            val h = engine.harmonize(note, cfg, states[v], newPhrase = false) ?: continue
            if (config.initialExpression > 0) {
                sendCcThinned(cfg.channel, 11,
                    config.initialExpression * config.expressionScalePercent / 100, now)
            }
            out.noteOn(cfg.channel, h, vel)
            tracker.setHarmony(slot, v, h)
        }
    }

    /**
     * Spec §7. Da chiamare periodicamente: se la battuta ha cambiato passo,
     * le voci già in suono si spostano conservando il proprio grado.
     */
    fun tick(now: Long) {
        if (!transport.running) return
        val q = transport.quarto(now)
        val idx = progression.stepIndexAt(q)
        if (idx == lastStepIndex) return
        applyStep(q)
        if (muted) return

        for (i in 0 until tracker.capacity) {
            if (!tracker.isActive(i)) continue
            val melody = tracker.melodyAt(i)
            val vel = tracker.velocityAt(i)
            for (v in config.voices.indices) {
                val old = tracker.harmonyAt(i, v)
                if (old < 0) continue
                val cfg = config.voices[v]
                val neu = engine.retune(melody, cfg, states[v]) ?: continue  // null = invariata
                // legato: nuova nota PRIMA del note-off, spec §7.1
                out.noteOn(cfg.channel, neu, vel)
                out.noteOff(cfg.channel, old)
                tracker.setHarmony(i, v, neu)
            }
        }
    }

    // ------------------------------------------------------------ utilità

    private fun forwardCc(num: Int, value: Int, now: Long) {
        for (v in config.voices) {
            val scaled = if (num == 11 || num == 2)
                value * config.expressionScalePercent / 100 else value
            sendCcThinned(v.channel, num, scaled, now)
        }
    }

    /** Spec §8.5: solo se il valore è cambiato e non più di una volta ogni thinMs. */
    private fun sendCcThinned(ch: Int, num: Int, value: Int, now: Long) {
        val c = ch.coerceIn(0, 16)
        val n = num.coerceIn(0, 127)
        val v = value.coerceIn(0, 127)
        if (lastCcSent[c][n] == v) return
        if (now - lastCcTime[c][n] < config.thinMs * 1_000_000L) return
        lastCcSent[c][n] = v
        lastCcTime[c][n] = now
        out.cc(ch, n, v)
    }

    fun panic() {
        for (i in 0 until tracker.capacity) tracker.close(i)
        for (ch in intArrayOf(config.leadChannel, *config.voices.map { it.channel }.toIntArray())) {
            out.cc(ch, 123, 0)
            out.cc(ch, 121, 0)
        }
    }

    fun activeVoices(): Int = tracker.activeCount()

    fun currentStep(now: Long): ChordStep =
        progression.stepAt(transport.quarto(now))

    fun currentStepIndex(now: Long): Int =
        progression.stepIndexAt(transport.quarto(now))

    fun nextStep(now: Long): ChordStep {
        val idx = currentStepIndex(now)
        return progression.steps[(idx + 1) % progression.steps.size]
    }
}

/**
 * Progressione predefinita: IV-II-V-I in Do maggiore, una battuta per accordo,
 * con il **modo relativo** di ciascun grado come scala.
 *
 *   F   lidia (4o modo)      D-  dorica (2o modo)
 *   G7  misolidia (5o modo)  C   ionica (1o modo)
 *
 * Sono tutte la scala di Do maggiore lette da gradi diversi: le voci di
 * armonia restano dentro la tonalita' e si muovono di poco, che e' proprio
 * quello che serve per sentire il meccanismo funzionare.
 */
fun progressioneDefault(): Progression = Progression(
    "IV-II-V-I in Do",
    listOf(
        ChordStep.battute(Chord(5, ChordQuality.MAJ), Scales.LIDIA, 1),      // F
        ChordStep.battute(Chord(2, ChordQuality.MIN), Scales.DORICA, 1),     // D-
        ChordStep.battute(Chord(7, ChordQuality.DOM7), Scales.MISOLIDIA, 1), // G7
        ChordStep.battute(Chord(0, ChordQuality.MAJ), Scales.IONICA, 1)      // C
    )
)
