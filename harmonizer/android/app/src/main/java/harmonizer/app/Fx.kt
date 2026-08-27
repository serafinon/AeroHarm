package harmonizer.app

import harmonizer.core.*

/**
 * Configurazione degli effetti. Logica pura: nessuna dipendenza da Android,
 * come [Harmonizer] e [Runtime]. Spec §14.
 *
 * Un solo effetto alla volta e' attivo — due catene in serie raddoppierebbero
 * il lavoro sul percorso della nota, che e' l'unica cosa su cui l'app puo'
 * ancora peggiorare la latenza. Ma le configurazioni di **tutti** gli effetti
 * sono memorizzate per ogni brano: cambiare effetto non perde le regolazioni.
 */

enum class FxTipo(val etichetta: String) {
    HARMONIZER("harmonizer"),
    ARPEGGIATOR("arpeggiator");

    companion object {
        fun da(codice: String): FxTipo = if (codice == "a") ARPEGGIATOR else HARMONIZER
    }

    fun codice(): String = if (this == ARPEGGIATOR) "a" else "h"
}

/** Una parte per voce, dalla 2 in avanti: la 1 e' la melodia. Spec §3. */
const val MAX_VOCI = 4
const val CANALE_VOCE_BASE = 2

/** Estensione del selettore di grado: dall'ottava sotto all'ottava sopra. */
const val GRADO_MIN = -7
const val GRADO_MAX = 7

// ------------------------------------------------------------- harmonizer

/**
 * I due parametri che prima erano scritti nel codice: **quante voci** e **su
 * quale grado**.
 *
 * Il grado si sceglie in due modi diversi, perche' le due domande sono
 * diverse:
 *
 * - a **grado fisso** l'armonizzazione e' parallela e ogni voce ha un grado
 *   solo: serve [gradi], un valore per voce.
 * - negli **altri modi** le voci si muovono, e quello che serve e' *dove*
 *   ciascuna puo' muoversi: un **intervallo per voce**, [gradiMin] e
 *   [gradiMax]. Non uno globale — con un intervallo in comune le voci
 *   finiscono per pescare le stesse note e si accavallano; con uno per voce si
 *   decide che la prima sta fra 2a e 3a e la seconda fra 4a e 5a, e restano
 *   distinte per costruzione.
 *
 * Tutte e tre le liste conservano sempre [MAX_VOCI] valori: riducendo le voci
 * e poi rialzandole si ritrovano quelli di prima.
 */
data class HarmonizerCfg(
    var voci: Int = 1,
    var modo: SelectionMode = SelectionMode.VOICE_LEADING,
    var gradi: MutableList<Int> = mutableListOf(Degrees.THIRD, Degrees.FIFTH, Degrees.SEVENTH, Degrees.SECOND),
    /** Estremo basso dell'intervallo di ogni voce, nei modi non fissi. */
    var gradiMin: MutableList<Int> = mutableListOf(Degrees.SECOND, Degrees.FOURTH, Degrees.SIXTH, Degrees.SEVENTH),
    /** Estremo alto. Fasce accostate per voce: 2a-3a, 4a-5a, 6a-7a, 7a-8va. */
    var gradiMax: MutableList<Int> = mutableListOf(Degrees.THIRD, Degrees.FIFTH, Degrees.SEVENTH, Degrees.OCTAVE)
) {
    fun normalizza() {
        voci = voci.coerceIn(1, MAX_VOCI)
        riempi(gradi, Degrees.THIRD)
        riempi(gradiMin, Degrees.SECOND)
        riempi(gradiMax, Degrees.THIRD)
    }

    private fun riempi(l: MutableList<Int>, difetto: Int) {
        while (l.size < MAX_VOCI) l.add(difetto)
        while (l.size > MAX_VOCI) l.removeAt(l.size - 1)
        for (i in l.indices) l[i] = l[i].coerceIn(GRADO_MIN, GRADO_MAX)
    }

    /** I gradi delle voci accese. Serve a grado fisso. */
    fun gradiAttivi(): List<Int> = gradi.take(voci.coerceIn(1, MAX_VOCI))

    fun estremoBasso(voce: Int): Int {
        val i = voce.coerceIn(0, MAX_VOCI - 1)
        return minOf(gradiMin[i], gradiMax[i])
    }

    fun estremoAlto(voce: Int): Int {
        val i = voce.coerceIn(0, MAX_VOCI - 1)
        return maxOf(gradiMin[i], gradiMax[i])
    }

    /**
     * L'insieme fra cui si muove **una** voce nei modi non fissi: tutti i
     * gradi fra i suoi due estremi.
     *
     * L'**unisono e' escluso** quando l'intervallo e' piu' largo di lui: una
     * voce all'unisono con la melodia e' una voce che sparisce, e come esito
     * di un tiro casuale suona come un buco. Resta solo se l'intervallo e'
     * esattamente l'unisono, che e' una scelta esplicita.
     */
    fun insiemeGradi(voce: Int): List<Int> {
        val a = estremoBasso(voce)
        val b = estremoAlto(voce)
        if (a == 0 && b == 0) return listOf(0)
        val l = (a..b).filter { it != 0 }
        return if (l.isEmpty()) listOf(if (a != 0) a else b) else l
    }

    /** C'e' una voce accesa il cui intervallo scavalca l'unisono? */
    fun saltaUnisono(): Boolean =
        (0 until voci.coerceIn(1, MAX_VOCI)).any {
            estremoBasso(it) != estremoAlto(it) && 0 in estremoBasso(it)..estremoAlto(it)
        }

    fun copia(): HarmonizerCfg = HarmonizerCfg(
        voci, modo, gradi.toMutableList(), gradiMin.toMutableList(), gradiMax.toMutableList())
}

/**
 * Modi di scelta del grado: nome mostrato e spiegazione. La spiegazione sta
 * qui e non nella documentazione perche' va letta **mentre si sceglie**:
 * "guida voci" non dice nulla a chi non conosce il termine inglese.
 */
val MODI_HARM: List<Triple<SelectionMode, String, String>> = listOf(
    Triple(SelectionMode.FIXED, "grado fisso",
        "Ogni voce tiene sempre il proprio grado. Due voci su 3a e 5a fanno " +
        "3a e 5a su ogni nota: l'armonia si muove parallela alla melodia, " +
        "cambiando qualita' secondo l'accordo."),
    Triple(SelectionMode.RANDOM, "casuale",
        "A ogni nota ogni voce pesca un grado a caso dentro il proprio " +
        "intervallo. Puo' capitare lo stesso grado due volte di fila, quindi " +
        "qualche nota resta ferma."),
    Triple(SelectionMode.RANDOM_NO_REPEAT, "casuale alternato",
        "Come casuale, ma una voce non ripesca il grado che ha appena usato: " +
        "a ogni nota l'intervallo cambia per forza. Piu' irrequieto."),
    Triple(SelectionMode.VOICE_LEADING, "movimento minimo",
        "Ogni voce sceglie, dentro il proprio intervallo, il grado la cui nota " +
        "e' piu' vicina a quella che ha appena suonato. La linea si muove poco " +
        "e resta cantabile: e' il voice leading, ed e' il modo consigliato.")
)

fun nomeModo(m: SelectionMode): String =
    MODI_HARM.firstOrNull { it.first == m }?.second ?: m.name

/** Nome breve del grado, per il selettore verticale. */
fun gradoBreve(d: Int): String {
    val n = when (kotlin.math.abs(d)) {
        0 -> "1"; 1 -> "2a"; 2 -> "3a"; 3 -> "4a"
        4 -> "5a"; 5 -> "6a"; 6 -> "7a"; else -> "8va"
    }
    return if (d < 0) "$n↓" else n
}

// ------------------------------------------------------------ arpeggiator

enum class ArpPattern(val etichetta: String) {
    SU("su"), GIU("giù"), SU_GIU("su-giù"), GIU_SU("giù-su"), CASUALE("casuale")
}

/**
 * Suddivisioni in **note per quarto**. Oltre alle binarie ci sono i gruppi
 * irregolari: 3 = terzine di ottavi, 5 = quintine, 6 = sestine, 7 = gruppi di
 * sette, 12 = terzine di sedicesimi.
 *
 * Il quarto e' il movimento in qualsiasi tempo (spec §7.3.3), quindi la
 * suddivisione non dipende da 3/4, 4/4 o 5/4.
 */
object Suddivisioni {
    val VALORI = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 12)
    val NOMI = arrayOf("1/4", "1/8", "terzine", "1/16", "quintine",
                       "sestine", "settimine", "1/32", "terz. 1/16")

    fun indiceDi(notePerQuarto: Int): Int =
        VALORI.indexOf(notePerQuarto).let { if (it < 0) 1 else it }
}

val GATE_AMMESSI = intArrayOf(10, 25, 40, 55, 70, 85, 100)
val SWING_AMMESSI = intArrayOf(0, 10, 20, 30, 40, 50)
val OTTAVE_AMMESSE = intArrayOf(1, 2, 3, 4)

data class ArpCfg(
    var pattern: ArpPattern = ArpPattern.SU,
    /** Note per quarto: vedi [Suddivisioni]. */
    var notePerQuarto: Int = 2,
    var ottave: Int = 1,
    /** Durata della nota in percentuale dello step: e' l'articolazione. */
    var gate: Int = 70,
    /** Ritardo degli step dispari, in percentuale dello step. */
    var swing: Int = 0,
    /** Note dell'accordo (vero arpeggio) oppure tutta la scala del passo. */
    var soloAccordo: Boolean = true
) {
    fun normalizza() {
        if (notePerQuarto !in Suddivisioni.VALORI) notePerQuarto = 2
        ottave = ottave.coerceIn(1, 4)
        gate = gate.coerceIn(5, 100)
        swing = swing.coerceIn(0, 50)
    }

    fun copia(): ArpCfg = ArpCfg(pattern, notePerQuarto, ottave, gate, swing, soloAccordo)
}

/**
 * La sequenza di note dell'arpeggio, **a partire dalla nota suonata**.
 *
 * Si sale per note ammesse dal passo corrente della progressione: quindi
 * l'arpeggio segue l'armonia da solo, senza una riga di logica sull'accordo.
 * Gli array sono preallocati: si ricostruisce a ogni cambio di nota o di
 * accordo, non a ogni step.
 */
class ArpSequenza(private val capacita: Int = 32) {

    private val base = IntArray(capacita)
    val note = IntArray(capacita * 2)
    var lunghezza = 0
        private set

    fun costruisci(notaSuonata: Int, ammesse: AllowedNotes, cfg: ArpCfg) {
        lunghezza = 0
        if (ammesse.notes.isEmpty()) return

        // la nota suonata e' il punto di partenza: se e' estranea si aggancia
        val i0 = ammesse.snapIndex(notaSuonata)
        val quante = (ammesse.stepsPerOctave * cfg.ottave.coerceAtLeast(1)).coerceIn(1, capacita)
        var k = 0
        for (j in 0 until quante) {
            val idx = i0 + j
            if (idx >= ammesse.notes.size) break
            base[k++] = ammesse.notes[idx]
        }
        if (k == 0) return

        when (cfg.pattern) {
            ArpPattern.SU, ArpPattern.CASUALE -> {
                for (j in 0 until k) note[j] = base[j]
                lunghezza = k
            }
            ArpPattern.GIU -> {
                for (j in 0 until k) note[j] = base[k - 1 - j]
                lunghezza = k
            }
            // gli estremi non si ripetono: su 3 note fa 1-2-3-2, non 1-2-3-3-2-1
            ArpPattern.SU_GIU -> {
                var n = 0
                for (j in 0 until k) note[n++] = base[j]
                for (j in k - 2 downTo 1) note[n++] = base[j]
                lunghezza = n
            }
            ArpPattern.GIU_SU -> {
                var n = 0
                for (j in 0 until k) note[n++] = base[k - 1 - j]
                for (j in 1..k - 2) note[n++] = base[j]
                lunghezza = n
            }
        }
    }
}
