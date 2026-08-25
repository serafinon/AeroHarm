package harmonizer.app

import harmonizer.core.*

/**
 * Macchine a stati e tracciamento voci. Spec §6 e §8.3.
 *
 * Nessuna allocazione nel percorso della nota: tutte le strutture sono
 * preallocate alla costruzione. È l'unica promessa seria che si possa fare
 * sulla latenza, dato che Android non è un sistema real-time.
 */

// ------------------------------------------------------------ voice tracker

/**
 * Spec §8.3. Mappa nota-in-ingresso -> note emesse, con slot separati per
 * voce: il mute agisce sulle voci di armonia, la melodia resta.
 *
 * Senza questa mappa, sulle linee legato i note-off finiscono sulla nota
 * sbagliata e restano note appese. È il punto dove un'implementazione
 * affrettata si rompe.
 */
class VoiceTracker(private val maxNotes: Int = 8, private val maxVoices: Int = 4) {

    private val melody = IntArray(maxNotes) { -1 }
    private val velocity = IntArray(maxNotes)
    private val harmony = Array(maxNotes) { IntArray(maxVoices) { -1 } }

    /** Indice dello slot per quella nota, o -1. */
    fun indexOf(note: Int): Int {
        for (i in 0 until maxNotes) if (melody[i] == note) return i
        return -1
    }

    fun freeSlot(): Int {
        for (i in 0 until maxNotes) if (melody[i] == -1) return i
        return -1
    }

    fun open(note: Int, vel: Int): Int {
        val i = freeSlot()
        if (i < 0) return -1
        melody[i] = note
        velocity[i] = vel
        for (v in 0 until maxVoices) harmony[i][v] = -1
        return i
    }

    fun close(i: Int) {
        if (i < 0 || i >= maxNotes) return
        melody[i] = -1
        for (v in 0 until maxVoices) harmony[i][v] = -1
    }

    fun melodyAt(i: Int): Int = if (i in 0 until maxNotes) melody[i] else -1
    fun velocityAt(i: Int): Int = if (i in 0 until maxNotes) velocity[i] else 0
    fun harmonyAt(i: Int, v: Int): Int = harmony[i][v]
    fun setHarmony(i: Int, v: Int, note: Int) { harmony[i][v] = note }

    fun isActive(i: Int): Boolean = melody[i] != -1
    val capacity: Int get() = maxNotes
    val voices: Int get() = maxVoices

    fun activeCount(): Int {
        var n = 0
        for (i in 0 until maxNotes) if (melody[i] != -1) n++
        return n
    }
}

// ---------------------------------------------------------------- transport

enum class ResyncMode { TO_BAR_1, PHASE_ALIGN }

/**
 * Spec §6. Il transport è indipendente dal mute: la progressione continua a
 * scorrere anche ad armonia silenziata.
 */
class Transport(var beatsPerBar: Int = 4) {

    var running = false
        private set
    var bpm = 120.0
        set(v) { field = v.coerceIn(40.0, 300.0) }

    /** Istante in cui cade la battuta 1, in nanosecondi monotoni. */
    private var originNanos = 0L
    /** Sfasamento in battute, per PHASE_ALIGN. */
    private var barOffset = 0

    /** Un quarto = un movimento. Non dipende dal tempo: 3/4, 4/4 e 5/4
     *  hanno tutti il quarto come unita'. */
    private fun nanosPerQuarto(): Double = 60.0 / bpm * 1_000_000_000.0

    private fun nanosPerBar(): Double = nanosPerQuarto() * beatsPerBar

    fun start(now: Long) {
        originNanos = now
        barOffset = 0
        running = true
    }

    fun stop() {
        running = false
        originNanos = 0L
        barOffset = 0
    }

    /** S2: "questo istante è la battuta 1". Da fermo avvia, in moto riallinea. */
    fun resync(now: Long, mode: ResyncMode) {
        if (!running) { start(now); return }
        when (mode) {
            ResyncMode.TO_BAR_1 -> { originNanos = now; barOffset = 0 }
            ResyncMode.PHASE_ALIGN -> {
                val current = absoluteBar(now)
                originNanos = now
                barOffset = current
            }
        }
    }

    private fun absoluteBar(now: Long): Int {
        if (!running) return 0
        val d = (now - originNanos).toDouble()
        return Math.floor(d / nanosPerQuarto()).toInt() + barOffset
    }

    /** Posizione in QUARTI di battuta. */
    fun quarto(now: Long): Int = if (running) absoluteBar(now) else barOffset

    fun bar(now: Long): Int = quarto(now) / beatsPerBar.coerceAtLeast(1)

    /** Posizione dentro la battuta, 0.0 .. 1.0 */
    fun barPhase(now: Long): Double {
        if (!running) return 0.0
        val np = nanosPerBar()
        val d = (now - originNanos).toDouble() % np
        return (if (d < 0) d + np else d) / np
    }

    /** Salto diretto a un passo, in quarti. */
    fun jumpToQuarto(now: Long, targetBar: Int) {
        originNanos = now
        barOffset = targetBar
        if (!running) running = true
    }
}

// --------------------------------------------------------------- tap tempo

/**
 * Spec §9.3. Media mobile sugli ultimi intervalli, con scarto degli outlier.
 * Molto più stabile dell'ultimo intervallo, e conta ai tempi lenti dove un
 * colpo impreciso pesa di più.
 */
class TapTempo(private val maxTaps: Int = 5) {

    private val times = LongArray(maxTaps)
    private var count = 0
    private var head = 0

    /** Unità battuta: 1 = quarto, 2 = minima, 4 = battuta intera. */
    var unit: Int = 1

    fun reset() { count = 0; head = 0 }

    /** Restituisce il BPM stimato, o null se servono altri colpi. */
    fun tap(now: Long): Double? {
        if (count > 0) {
            val prev = times[(head - 1 + maxTaps) % maxTaps]
            val gap = now - prev
            val avg = averageInterval()
            // colpo troppo lontano: apre un nuovo gruppo
            if (avg != null && gap > avg * 2.0) { count = 0; head = 0 }
        }
        times[head] = now
        head = (head + 1) % maxTaps
        if (count < maxTaps) count++

        val avg = averageInterval() ?: return null
        val bpm = 60.0 * 1_000_000_000.0 / avg * unit
        return if (bpm in 40.0..300.0) bpm else null
    }

    private fun averageInterval(): Double? {
        if (count < 2) return null
        val n = count - 1
        val gaps = DoubleArray(n)
        for (k in 0 until n) {
            val a = times[(head - 1 - k - 1 + 2 * maxTaps) % maxTaps]
            val b = times[(head - 1 - k + 2 * maxTaps) % maxTaps]
            gaps[k] = (b - a).toDouble()
        }
        if (n == 1) return gaps[0]
        // scarto degli outlier: mediana +/- 40%
        val sorted = gaps.copyOf(); sorted.sort()
        val median = sorted[n / 2]
        var sum = 0.0; var used = 0
        for (g in gaps) if (g > median * 0.6 && g < median * 1.4) { sum += g; used++ }
        return if (used > 0) sum / used else median
    }
}

// -------------------------------------------------------------- precalcolo

/**
 * Spec: percorso caldo senza allocazioni. Tutti gli insiemi di note ammesse
 * per ogni fondamentale e ogni scala vengono costruiti una volta all'avvio,
 * così il cambio d'accordo è una lettura da tabella.
 *
 * 12 fondamentali x N scale, circa 64 KB.
 */
class AllowedCache(scales: List<Scale> = Scales.ALL) {

    private val index = HashMap<String, Array<AllowedNotes>>(scales.size * 2)

    init {
        for (sc in scales) {
            val perRoot = Array(12) { root ->
                AllowedNotes(sc.intervals.map { pitchClass(it + root) }.toSet())
            }
            index[sc.id] = perRoot
        }
    }

    fun get(root: Int, scale: Scale): AllowedNotes? =
        index[scale.id]?.get(pitchClass(root))
}
