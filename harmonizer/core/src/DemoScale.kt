package harmonizer.core

/**
 * La scala è una SCELTA per ogni accordo, non una proprietà della qualità.
 * Qui si vede cosa cambia nell'armonizzazione.
 */

private fun sep(t: String) {
    println()
    println("=".repeat(78))
    println("  $t")
    println("=".repeat(78))
}

private fun iName(s: Int): String = when (kotlin.math.abs(s)) {
    0 -> "unis"; 1 -> "2m"; 2 -> "2M"; 3 -> "3m"; 4 -> "3M"; 5 -> "4g"
    6 -> "trit"; 7 -> "5g"; 8 -> "6m"; 9 -> "6M"; 10 -> "7m"; 11 -> "7M"
    12 -> "8va"; else -> "$s"
}

fun main() {

    sep("1 · Scale proponibili per ciascuna qualità d'accordo")
    println()
    println("  Le prime della riga vanno in evidenza nella striscia di selezione.")
    println("  Su un accordo minore la ionica non compare: non è suonabile.")
    println()
    for (q in ChordQuality.values()) {
        val ch = Chord(10, q)
        val list = Scales.compatibleWith(q)
        println("  %-10s  %s".format(ch.toString(), list.joinToString(" · ") { it.name }))
    }

    sep("2 · Stessa melodia su Bb7, scale diverse — terza sopra")
    val melodia = listOf(70, 72, 73, 74, 75, 77, 79, 80)
    val ch = Chord(10, ChordQuality.DOM7)
    val cfg = VoiceConfig("3a", listOf(Degrees.THIRD), SelectionMode.FIXED)

    print("\n  melodia          ")
    for (n in melodia) print("%-6s".format(Spelling.note(n, ch)))
    println()
    println("  " + "-".repeat(66))

    for (sc in Scales.compatibleWith(ChordQuality.DOM7)) {
        val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
        eng.setContext(ch, sc)
        val st = VoiceState()
        print("  %-16s".format(sc.name))
        for (n in melodia) {
            val h = eng.harmonize(n, cfg, st, false)
            print("%-6s".format(h?.let { Spelling.pc(it, ch) } ?: "-"))
        }
        println()
    }
    println()
    println("  Stesso accordo, stessa melodia: cambia solo la scala scelta e")
    println("  l'armonia cambia di conseguenza. È la scelta che ti mancava.")

    sep("3 · Gli intervalli che ne escono")
    for (sc in listOf(Scales.MISOLIDIA, Scales.ALTERATA, Scales.BLUES, Scales.PENTA_MIN)) {
        val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
        eng.setContext(ch, sc)
        val st = VoiceState()
        print("  %-16s".format(sc.name))
        for (n in melodia) {
            val h = eng.harmonize(n, cfg, st, false)
            print("%-6s".format(h?.let { iName(it - n) } ?: "-"))
        }
        println()
    }
    println()
    println("  La blues ha 6 note e la pentatonica 5, quindi un 'grado' vale")
    println("  di più: le terze si allargano. È corretto, è come funzionano.")

    sep("4 · Blues in Bb con scale scelte per ogni accordo")
    val prog = Progression(
        "Blues in Bb — scale scelte",
        listOf(
            ChordStep.battute(Chord(10, ChordQuality.DOM7), Scales.MISOLIDIA, 4),
            ChordStep.battute(Chord(3, ChordQuality.DOM7), Scales.BLUES, 2),
            ChordStep.battute(Chord(10, ChordQuality.DOM7), Scales.MISOLIDIA, 2),
            ChordStep.battute(Chord(5, ChordQuality.DOM7), Scales.ALTERATA, 1),
            ChordStep.battute(Chord(3, ChordQuality.DOM7), Scales.MISOLIDIA, 1),
            ChordStep.battute(Chord(10, ChordQuality.DOM7), Scales.MISOLIDIA, 1),
            ChordStep.battute(Chord(5, ChordQuality.DOM7), Scales.ALTERATA, 1)
        )
    )
    println()
    for ((i, s) in prog.steps.withIndex()) {
        println("  passo %d   %-22s %d bt".format(i + 1, s.toString(), s.bars()))
    }
    println()
    println("  Il F7 di battuta 9 usa l'alterata: sul V grado è la scelta tipica,")
    println("  e l'armonia lo segue senza che tu debba dire altro.")
    println()
    println("  Nota tenuta Bb4, terza sopra, lungo tutto il giro:")
    println()
    val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
    val st = VoiceState()
    var last: ChordStep? = null
    var first = true
    for (bar in 0 until prog.totalBars()) {
        val step = prog.stepAt(bar * BATTITI_DEFAULT)
        if (step == last) continue
        last = step
        eng.setStep(step)
        val h = if (first) { first = false; eng.harmonize(70, cfg, st, false) }
                else eng.retune(70, cfg, st)
        val txt = h?.let { "${Spelling.note(it, step.chord)}  (${iName(it - 70)})" } ?: "invariata"
        println("  bt %-3d %-24s -> %s".format(bar + 1, step.toString(), txt))
    }
    println()
}
