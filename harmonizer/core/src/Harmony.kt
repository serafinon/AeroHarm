package harmonizer.core

/**
 * Core dell'armonizzatore per Aerophone AE-20.
 *
 * Logica pura: nessuna dipendenza da Android, da Max o dal MIDI.
 * Tutto ciò che sta qui è verificabile senza hardware.
 *
 * Riferimento: AE20_Harmonizer_Spec.md, sezioni 5 e 7.
 */

fun pitchClass(midi: Int): Int = ((midi % 12) + 12) % 12

/** Nome di nota senza contesto d'accordo: usa i bemolli. */
fun noteName(midi: Int): String = Spelling.note(midi, null)

// ---------------------------------------------------------------- grafia

/**
 * Grafia secondo il circolo delle quinte, non enarmonia arbitraria.
 *
 *   maggiori   C  G  D  A  E  B  F#  C#  Ab  Eb  Bb  F
 *   minori     a  e  b  f# c# g# eb  bb  f   c   g   d
 *
 * Le due sequenze occupano le stesse dodici posizioni del circolo: ogni
 * maggiore con la propria relativa minore. L'unica classe che cambia grafia
 * fra i due modi è la 8: Ab maggiore, g# minore.
 */
object Spelling {

    private val SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    private val MAJ_ROOT = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val MIN_ROOT = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "G#", "A", "Bb", "B")

    /** Fondamentali sul versante dei diesis, per ciascun modo. */
    private val SHARP_MAJ_ROOTS = setOf(7, 2, 9, 4, 11, 6, 1)          // G D A E B F# C#
    private val SHARP_MIN_ROOTS = setOf(9, 4, 11, 6, 1, 8)             // a e b f# c# g#

    /** Ordine delle posizioni sul circolo, senso orario a partire da C. */
    val CIRCLE_ORDER = intArrayOf(0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5)

    /** Relativa minore di una maggiore: una terza minore sotto. */
    fun relativeMinor(majorRoot: Int): Int = pitchClass(majorRoot + 9)

    /** Relativa maggiore di una minore. */
    fun relativeMajor(minorRoot: Int): Int = pitchClass(minorRoot + 3)

    fun rootName(pc: Int, minor: Boolean): String =
        if (minor) MIN_ROOT[pitchClass(pc)] else MAJ_ROOT[pitchClass(pc)]

    fun useSharps(root: Int, minor: Boolean): Boolean =
        if (minor) pitchClass(root) in SHARP_MIN_ROOTS else pitchClass(root) in SHARP_MAJ_ROOTS

    /** Nome di nota nel contesto dell'accordo corrente, con ottava. */
    fun note(midi: Int, chord: Chord?): String {
        val sharp = chord?.let { useSharps(it.root, it.quality.minor) } ?: false
        val table = if (sharp) SHARP else FLAT
        return table[pitchClass(midi)] + (midi / 12 - 1)
    }

    /** Nome di classe senza ottava, nel contesto dell'accordo. */
    fun pc(pcValue: Int, chord: Chord?): String {
        val sharp = chord?.let { useSharps(it.root, it.quality.minor) } ?: false
        val table = if (sharp) SHARP else FLAT
        return table[pitchClass(pcValue)]
    }
}

// ---------------------------------------------------------------- accordi

/** Spec §5.2. Chord tone e scala associata, in semitoni relativi alla fondamentale. */
enum class ChordQuality(
    val label: String,
    val minor: Boolean,
    val chordTones: List<Int>,
    val scale: List<Int>
) {
    MAJ("", false, listOf(0, 4, 7), listOf(0, 2, 4, 5, 7, 9, 11)),
    MAJ7("maj7", false, listOf(0, 4, 7, 11), listOf(0, 2, 4, 5, 7, 9, 11)),
    MAJ6("6", false, listOf(0, 4, 7, 9), listOf(0, 2, 4, 5, 7, 9, 11)),
    DOM7("7", false, listOf(0, 4, 7, 10), listOf(0, 2, 4, 5, 7, 9, 10)),
    DOM7SUS4("7sus4", false, listOf(0, 5, 7, 10), listOf(0, 2, 4, 5, 7, 9, 10)),
    DOM7SHARP11("7#11", false, listOf(0, 4, 6, 10), listOf(0, 2, 4, 6, 7, 9, 10)),
    DOM7ALT("7alt", false, listOf(0, 4, 8, 10), listOf(0, 1, 3, 4, 6, 8, 10)),
    MIN("m", true, listOf(0, 3, 7), listOf(0, 2, 3, 5, 7, 8, 10)),
    MIN7("m7", true, listOf(0, 3, 7, 10), listOf(0, 2, 3, 5, 7, 9, 10)),
    MIN6("m6", true, listOf(0, 3, 7, 9), listOf(0, 2, 3, 5, 7, 9, 11)),
    MIN7B5("m7b5", true, listOf(0, 3, 6, 10), listOf(0, 1, 3, 5, 6, 8, 10)),
    DIM7("dim7", true, listOf(0, 3, 6, 9), listOf(0, 2, 3, 5, 6, 8, 9, 11));
}

enum class SnapMode { CHORD, SCALE }

enum class SnapPreference { NEAREST, DOWN, UP }

data class Chord(val root: Int, val quality: ChordQuality) {

    /** Solo i chord tone: usato quando SnapMode.CHORD. */
    fun chordPitchClasses(): Set<Int> =
        quality.chordTones.map { pitchClass(it + root) }.toSet()

    override fun toString(): String =
        Spelling.rootName(root, quality.minor) + quality.label
}

/**
 * Insieme delle classi ammesse. Dipende dall'accordo E dalla SCALA SCELTA per
 * quell'accordo, non dalla sola qualita': su Bb7 misolidia, alterata, lidia
 * dominante e blues danno armonizzazioni diverse.
 */
fun allowedPitchClasses(chord: Chord, scale: Scale, mode: SnapMode): Set<Int> =
    if (mode == SnapMode.CHORD) chord.chordPitchClasses()
    else scale.intervals.map { pitchClass(it + chord.root) }.toSet()

// ------------------------------------------------------- insieme note ammesse

/**
 * Spec §5. Array piatto ordinato di tutte le note MIDI ammesse dall'accordo
 * corrente. Si ricostruisce a ogni CAMBIO DI ACCORDO, non a ogni nota.
 */
class AllowedNotes(pitchClasses: Set<Int>) {

    val notes: IntArray = (0..127).filter { pitchClass(it) in pitchClasses }.toIntArray()

    /** Quante note distinte per ottava: serve a stimare l'ampiezza di un grado. */
    val stepsPerOctave: Int = pitchClasses.size.coerceAtLeast(1)

    /** Primo indice con notes[i] >= valore. */
    private fun lowerBound(value: Double): Int {
        var lo = 0
        var hi = notes.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (notes[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Indice della nota ammessa più vicina al valore indicato. */
    fun nearestIndexTo(value: Double, pref: SnapPreference = SnapPreference.NEAREST): Int {
        if (notes.isEmpty()) return -1
        val up = lowerBound(value)
        if (notes[up].toDouble() == value) return up
        val down = (up - 1).coerceAtLeast(0)
        val dUp = kotlin.math.abs(notes[up] - value)
        val dDown = kotlin.math.abs(notes[down] - value)
        return when (pref) {
            SnapPreference.UP -> up
            SnapPreference.DOWN -> down
            SnapPreference.NEAREST -> if (dUp < dDown) up else down
        }
    }

    fun snapIndex(note: Int, pref: SnapPreference = SnapPreference.NEAREST): Int =
        nearestIndexTo(note.toDouble(), pref)

    fun contains(note: Int): Boolean {
        val i = snapIndex(note)
        return i >= 0 && notes[i] == note
    }

    /**
     * Trasposizione per GRADI. Convenzione (spec §5.1): 2a = +1, 3a = +2, 5a = +4.
     *
     * Algoritmo: si punta all'ampiezza teorica del grado misurata DALLA NOTA
     * REALE, poi si aggancia la nota ammessa più vicina a quel bersaglio.
     *
     * Sulle note dentro la scala il risultato coincide con il conteggio per
     * indici. Sulle note estranee — blue note, cromatismi — restituisce la nota
     * dell'accordo più vicina all'intervallo atteso, invece di propagare
     * l'errore di agganciamento della melodia.
     */
    fun transposeByDegrees(melodyNote: Int, degrees: Int, pref: SnapPreference): Int? {
        if (notes.isEmpty()) return null
        if (degrees == 0) return notes[snapIndex(melodyNote, pref)]

        val ideal = 12.0 * degrees / stepsPerOctave
        var idx = nearestIndexTo(melodyNote + ideal, pref)
        if (idx < 0) return null

        // Non restituire la melodia stessa quando è richiesto un intervallo.
        if (notes[idx] == melodyNote) {
            idx += if (degrees > 0) 1 else -1
            if (idx < 0 || idx >= notes.size) return null
        }
        return notes[idx]
    }

    /** Conteggio per indici: conservato per confronto e diagnosi. */
    fun transposeByDegreesLegacy(melodyNote: Int, degrees: Int, pref: SnapPreference): Int? {
        if (notes.isEmpty()) return null
        val i = snapIndex(melodyNote, pref)
        val t = i + degrees
        if (t < 0 || t >= notes.size) return null
        return notes[t]
    }

    /**
     * Ripiega di ottave finché la nota rientra nel registro della voce.
     * Sicuro perché l'insieme è periodico per ottava.
     */
    fun foldIntoRange(note: Int, low: Int, high: Int): Int? {
        if (low > high) return null
        var n = note
        while (n < low && n + 12 <= 127) n += 12
        while (n > high && n - 12 >= 0) n -= 12
        return if (n in low..high) n else null
    }
}

// ------------------------------------------------------------ scelta intervallo

enum class SelectionMode { FIXED, RANDOM, RANDOM_NO_REPEAT, VOICE_LEADING, PHRASE }

/** Nomi musicali -> offset in gradi. Spec §5.1. */
object Degrees {
    const val UNISON = 0
    const val SECOND = 1
    const val THIRD = 2
    const val FOURTH = 3
    const val FIFTH = 4
    const val SIXTH = 5
    const val SEVENTH = 6
    const val OCTAVE = 7

    fun name(d: Int): String {
        val n = when (kotlin.math.abs(d)) {
            0 -> "unisono"; 1 -> "2a"; 2 -> "3a"; 3 -> "4a"
            4 -> "5a"; 5 -> "6a"; 6 -> "7a"; 7 -> "8va"
            else -> "${kotlin.math.abs(d)} gradi"
        }
        return if (d < 0) "$n sotto" else n
    }
}

data class VoiceConfig(
    val name: String,
    val degreeSet: List<Int>,
    val selectionMode: SelectionMode = SelectionMode.VOICE_LEADING,
    val low: Int = 0,
    val high: Int = 127,
    val channel: Int = 2
)

class VoiceState {
    var lastDegree: Int? = null
    var lastNote: Int? = null
    var phraseDegree: Int? = null

    fun reset() {
        lastDegree = null
        lastNote = null
        phraseDegree = null
    }
}

// ----------------------------------------------------------------- il motore

class HarmonyEngine(
    var snapMode: SnapMode = SnapMode.SCALE,
    var snapPreference: SnapPreference = SnapPreference.NEAREST,
    var strictMode: Boolean = false,
    private val random: () -> Double = { Math.random() }
) {
    var chord: Chord = Chord(0, ChordQuality.MAJ)
        private set

    /** La scala scelta per l'accordo corrente. E' lei a definire i gradi. */
    var scale: Scale = Scales.defaultFor(ChordQuality.MAJ)
        private set

    var allowed: AllowedNotes = AllowedNotes(allowedPitchClasses(chord, scale, snapMode))
        private set

    /** Imposta accordo e scala insieme. Senza scala usa la predefinita. */
    fun setContext(newChord: Chord, newScale: Scale = Scales.defaultFor(newChord.quality)) {
        chord = newChord
        scale = newScale
        rebuild()
    }

    fun setStep(step: ChordStep) = setContext(step.chord, step.scale)

    fun rebuild() {
        allowed = AllowedNotes(allowedPitchClasses(chord, scale, snapMode))
    }

    /** Inietta un insieme precalcolato: evita l'allocazione nel percorso caldo. */
    fun useAllowed(a: AllowedNotes) { allowed = a }


    fun harmonize(melodyNote: Int, cfg: VoiceConfig, st: VoiceState, newPhrase: Boolean): Int? {
        if (strictMode && !allowed.contains(melodyNote)) return null
        if (cfg.degreeSet.isEmpty()) return null

        val degree = chooseDegree(melodyNote, cfg, st, newPhrase) ?: return null
        val raw = allowed.transposeByDegrees(melodyNote, degree, snapPreference) ?: return null
        val folded = allowed.foldIntoRange(raw, cfg.low, cfg.high) ?: return null

        st.lastDegree = degree
        st.lastNote = folded
        return folded
    }

    /**
     * Spec §7.3. Ricalcolo di una voce già in suono dopo un cambio di accordo:
     * la voce CONSERVA il proprio grado. Null se non c'è nulla da cambiare.
     */
    fun retune(melodyNote: Int, cfg: VoiceConfig, st: VoiceState): Int? {
        val degree = st.lastDegree ?: return null
        val raw = allowed.transposeByDegrees(melodyNote, degree, snapPreference) ?: return null
        val folded = allowed.foldIntoRange(raw, cfg.low, cfg.high) ?: return null
        if (folded == st.lastNote) return null
        st.lastNote = folded
        return folded
    }

    private fun chooseDegree(melodyNote: Int, cfg: VoiceConfig, st: VoiceState, newPhrase: Boolean): Int? {
        val set = cfg.degreeSet
        if (set.size == 1) return set[0]

        return when (cfg.selectionMode) {
            SelectionMode.FIXED -> set[0]

            SelectionMode.RANDOM -> set[(random() * set.size).toInt().coerceIn(0, set.size - 1)]

            SelectionMode.RANDOM_NO_REPEAT -> {
                val pool = if (set.size > 1 && st.lastDegree != null)
                    set.filter { it != st.lastDegree } else set
                pool[(random() * pool.size).toInt().coerceIn(0, pool.size - 1)]
            }

            SelectionMode.PHRASE -> {
                if (newPhrase || st.phraseDegree == null) {
                    st.phraseDegree = set[(random() * set.size).toInt().coerceIn(0, set.size - 1)]
                }
                st.phraseDegree
            }

            SelectionMode.VOICE_LEADING -> {
                val prev = st.lastNote
                if (prev == null) {
                    set[set.size / 2]
                } else {
                    var best = set[0]
                    var bestDist = Int.MAX_VALUE
                    for (d in set) {
                        val cand = allowed.transposeByDegrees(melodyNote, d, snapPreference)
                            ?.let { allowed.foldIntoRange(it, cfg.low, cfg.high) } ?: continue
                        val dist = kotlin.math.abs(cand - prev)
                        if (dist < bestDist) { bestDist = dist; best = d }
                    }
                    best
                }
            }
        }
    }
}

// ------------------------------------------------------------- progressione

/**
 * L'unita' minima e' il quarto, cioe' il movimento. Quanti quarti fanno una
 * battuta dipende dal tempo del pezzo: 3, 4 o 5. Non e' una costante.
 */
const val BATTITI_DEFAULT = 4

val TEMPI_AMMESSI = intArrayOf(3, 4, 5)

/** Numeratore del tempo, per l'interfaccia: "4/4". */
fun nomeTempo(battiti: Int) = "$battiti/4"

/**
 * Durata leggibile. Il denominatore resta sempre 4, perche' un quarto e' un
 * quarto in qualsiasi tempo; cambia quanti ne servono per fare una battuta.
 * In 3/4 tre quarti sono "1"; in 5/4 sono "3/4".
 */
fun formattaQuarti(q: Int, battiti: Int = BATTITI_DEFAULT): String {
    val intere = q / battiti
    val resto = q % battiti
    return when {
        resto == 0 -> "$intere"
        intere == 0 -> "$resto/4"
        else -> "$intere+$resto/4"
    }
}

/**
 * Un passo della progressione: accordo, SCALA da suonarci sopra, durata in
 * QUARTI di battuta. La scala e' una scelta per singolo accordo; se non
 * indicata si usa la predefinita della qualita'.
 */
data class ChordStep(
    val chord: Chord,
    val scale: Scale,
    val quarti: Int
) {
    /** Durata in battute intere, per la sola visualizzazione. */
    fun bars(battiti: Int = BATTITI_DEFAULT): Int = quarti / battiti

    /** Scale proponibili per questo accordo, in ordine di probabilita'. */
    fun scaleChoices(): List<Scale> = Scales.compatibleWith(chord.quality)

    fun durataTesto(battiti: Int = BATTITI_DEFAULT): String =
        formattaQuarti(quarti, battiti) + " bt"

    override fun toString(): String = "$chord / ${scale.name}"

    companion object {
        /** Comodita': durata espressa in battute intere. */
        fun battute(chord: Chord, scale: Scale, bars: Int, battiti: Int = BATTITI_DEFAULT) =
            ChordStep(chord, scale, bars * battiti)
        fun battute(chord: Chord, bars: Int, battiti: Int = BATTITI_DEFAULT) =
            ChordStep(chord, Scales.defaultFor(chord.quality), bars * battiti)
    }
}

class Progression(val name: String, val steps: List<ChordStep>) {

    /** Lunghezza totale in quarti di battuta. */
    val totalQuarti: Int = steps.sumOf { it.quarti }.coerceAtLeast(1)

    /** Solo per visualizzazione. */
    fun totalBars(battiti: Int = BATTITI_DEFAULT): Int = totalQuarti / battiti

    fun chordAt(quarto: Int): Chord = stepAt(quarto).chord

    fun scaleAt(quarto: Int): Scale = stepAt(quarto).scale

    fun stepAt(quarto: Int): ChordStep {
        if (steps.isEmpty()) return ChordStep(Chord(0, ChordQuality.MAJ), Scales.IONICA, 4)
        var q = ((quarto % totalQuarti) + totalQuarti) % totalQuarti
        for (s in steps) {
            if (q < s.quarti) return s
            q -= s.quarti
        }
        return steps.last()
    }

    /** Indice del passo attivo al quarto indicato. */
    fun stepIndexAt(quarto: Int): Int {
        if (steps.isEmpty()) return 0
        var q = ((quarto % totalQuarti) + totalQuarti) % totalQuarti
        for ((i, s) in steps.withIndex()) {
            if (q < s.quarti) return i
            q -= s.quarti
        }
        return steps.size - 1
    }

    /** Quarto d'inizio del passo indicato. */
    fun inizioDi(indice: Int): Int {
        var q = 0
        for (k in 0 until indice.coerceIn(0, steps.size)) q += steps[k].quarti
        return q
    }
}
