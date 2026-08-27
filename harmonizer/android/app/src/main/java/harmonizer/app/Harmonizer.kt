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
    /** Quale effetto e' attivo. Uno solo alla volta: spec §14. */
    var fx: FxTipo = FxTipo.HARMONIZER,
    /** Configurazioni dei due effetti: memorizzate entrambe, sempre. */
    var harm: HarmonizerCfg = HarmonizerCfg(),
    var arp: ArpCfg = ArpCfg(),
    /** Registro delle voci di armonia e dell'arpeggio. */
    var voiceLow: Int = 48,
    var voiceHigh: Int = 96,
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

    // ---- stato dell'arpeggiatore ----
    private val arpSeq = ArpSequenza()
    /** Nota da cui parte l'arpeggio: l'ultima suonata, priorita' all'ultima. */
    private var arpSorgente = -1
    private var arpVel = 100
    /** Nota dell'arpeggio in suono, o -1. */
    private var arpNota = -1
    private var arpOffAt = 0L
    private var arpUltimoStep = Long.MIN_VALUE
    private var arpSeqNota = -1
    private var arpSeqPasso = -2

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
        if (config.fx == FxTipo.ARPEGGIATOR) {
            // l'arpeggio non nasce qui: parte sulla griglia, nel tick
            arpSorgente = note
            arpVel = vel
            arpSeqNota = -1
        } else if (slot >= 0 && !muted && lastBreath >= config.breathGate) {
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

        // arpeggiatore: si passa a un'altra nota tenuta, se c'e'; altrimenti tace
        if (config.fx == FxTipo.ARPEGGIATOR && note == arpSorgente) {
            var altra = -1
            for (i in 0 until tracker.capacity)
                if (tracker.isActive(i)) { altra = tracker.melodyAt(i); break }
            arpSorgente = altra
            arpSeqNota = -1
            if (altra < 0) spegniArp()
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
            resync(now)
        } else {
            val held = (now - pendingResyncPressAt) / 1_000_000
            if (held >= config.longPressMs) stopAll(now)
        }
    }

    fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m
        if (m) { flushHarmony(); spegniArp() }
    }

    /**
     * S2: "questo istante e' la battuta 1". Passa dal runtime e non dal
     * transport perche' l'arpeggiatore deve ripartire dal primo step: il
     * riallineamento sposta l'origine, e la griglia dell'arpeggio ci sta
     * sopra.
     */
    fun resync(now: Long) {
        transport.resync(now, config.resyncMode)
        arpUltimoStep = Long.MIN_VALUE
    }

    /** Salto diretto a un passo della progressione, in quarti. */
    fun jump(now: Long, quarto: Int) {
        transport.jumpToQuarto(now, quarto)
        arpUltimoStep = Long.MIN_VALUE
    }

    /** Spec §6.3. Ferma progressione e armonia, MAI la melodia. */
    fun stopAll(now: Long) {
        transport.stop()
        flushHarmony()
        spegniArp()
        muted = false
        lastStepIndex = -1
        arpUltimoStep = Long.MIN_VALUE
        for (s in states) s.reset()
    }

    // ------------------------------------------------------------- effetti

    private fun ruota(l: List<Int>, di: Int): List<Int> {
        if (l.size < 2) return l
        val k = ((di % l.size) + l.size) % l.size
        return l.subList(k, l.size) + l.subList(0, k)
    }

    /**
     * Applica la configurazione degli effetti del brano. Le voci di armonia
     * si ricostruiscono da qui: numero, grado e modo non sono piu' scritti
     * nel codice.
     *
     * Con l'arpeggiatore attivo resta **una** parte, la 2: e' su quella che
     * viaggiano l'arpeggio, lo specchio dell'espressione e il bend.
     */
    fun applicaFx(tipo: FxTipo, h: HarmonizerCfg, a: ArpCfg) {
        flushHarmony()
        spegniArp()
        h.normalizza(); a.normalizza()
        config.fx = tipo
        config.harm = h
        config.arp = a
        config.voices = if (tipo == FxTipo.ARPEGGIATOR)
            listOf(VoiceConfig("arpeggio", listOf(Degrees.UNISON), SelectionMode.FIXED,
                               config.voiceLow, config.voiceHigh, CANALE_VOCE_BASE))
        else List(h.voci) { i ->
            // a grado fisso ogni voce tiene il suo; negli altri modi ognuna
            // pesca nel **proprio** intervallo. L'insieme e' ruotato per voce:
            // due voci con lo stesso intervallo, col movimento minimo,
            // partirebbero altrimenti dallo stesso grado — all'unisono
            val insieme = if (h.modo == SelectionMode.FIXED) listOf(h.gradi[i])
                          else ruota(h.insiemeGradi(i), i)
            VoiceConfig("armonia ${i + 1}", insieme, h.modo,
                        config.voiceLow, config.voiceHigh, CANALE_VOCE_BASE + i)
        }
        for (s in states) s.reset()
        arpSeqNota = -1
        arpSeqPasso = -2
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
        if (config.fx == FxTipo.ARPEGGIATOR) { tickArp(now); return }
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

    // ------------------------------------------------------- arpeggiatore

    /**
     * Spec §14.2. L'arpeggio sta sulla griglia del transport, non sulla nota:
     * la nota suonata dice solo *da dove* partire. Quindi vale tutta
     * l'architettura di prima — la progressione scorre a tempo d'esecuzione,
     * BATT. 1 riallinea, STOP ferma, e il tasto FX silenzia l'effetto
     * lasciando scorrere la progressione.
     *
     * A transport fermo non c'e' griglia e l'arpeggio tace: la melodia passa
     * comunque, come sempre.
     */
    private fun tickArp(now: Long) {
        val ch = config.voices[0].channel

        // gate: la durata della nota e' l'articolazione, e scade anche a fermo
        if (arpNota >= 0 && now >= arpOffAt) {
            out.noteOff(ch, arpNota); arpNota = -1
        }
        if (!transport.running) return

        val pos = transport.quartoDouble(now)
        applyStep(Math.floor(pos).toInt())
        if (muted || arpSorgente < 0 || lastBreath < config.breathGate) return

        val cfg = config.arp
        val npq = cfg.notePerQuarto.coerceIn(1, 16)
        val x = pos * npq
        val step = Math.floor(x).toLong()
        // swing: gli step dispari partono in ritardo di una frazione di step
        if (step % 2L != 0L && (x - step) < cfg.swing / 100.0) return
        if (step == arpUltimoStep) return
        arpUltimoStep = step

        if (arpSeqNota != arpSorgente || arpSeqPasso != lastStepIndex) {
            val ammesse = ammesseArp() ?: return
            arpSeq.costruisci(arpSorgente, ammesse, cfg)
            arpSeqNota = arpSorgente
            arpSeqPasso = lastStepIndex
        }
        val len = arpSeq.lunghezza
        if (len == 0) return

        val i = if (cfg.pattern == ArpPattern.CASUALE)
                    (Math.random() * len).toInt().coerceIn(0, len - 1)
                else (((step % len) + len) % len).toInt()
        val nota = arpSeq.note[i]

        // retrigger: note-off PRIMA del note-on, altrimenti con Legato
        // Retrigger Interval = OFF la parte cambierebbe intonazione senza
        // riattaccare — che per l'armonia serve, per l'arpeggio no
        if (arpNota >= 0) out.noteOff(ch, arpNota)
        if (config.initialExpression > 0) {
            sendCcThinned(ch, 11,
                config.initialExpression * config.expressionScalePercent / 100, now)
        }
        out.noteOn(ch, nota, arpVel)
        arpNota = nota
        val durataStep = transport.nanosPerQuarto() / npq
        arpOffAt = now + (durataStep * cfg.gate.coerceIn(5, 100) / 100.0).toLong()
    }

    /** Note su cui sale l'arpeggio: i chord tone, oppure la scala del passo. */
    private fun ammesseArp(): AllowedNotes? {
        val steps = progression.steps
        if (steps.isEmpty()) return null
        val step = steps[lastStepIndex.coerceIn(0, steps.size - 1)]
        return if (config.arp.soloAccordo) cache.getAccordo(step.chord)
               else cache.get(step.chord.root, step.scale)
    }

    private fun spegniArp() {
        if (arpNota >= 0) {
            out.noteOff(config.voices[0].channel, arpNota)
            arpNota = -1
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
        spegniArp()
        arpSorgente = -1
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
