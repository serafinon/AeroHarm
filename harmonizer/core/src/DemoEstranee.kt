package harmonizer.core

/**
 * Confronto fra i due modi di trasporre per gradi, sulle note estranee.
 * Serve a decidere con i numeri, non a parole.
 */

private fun line(n: Int = 78) = println("-".repeat(n))

private fun title(t: String) {
    println()
    println("=".repeat(78))
    println("  $t")
    println("=".repeat(78))
}

/** Nome dell'intervallo dato il numero di semitoni. */
private fun intervalName(semis: Int): String = when (kotlin.math.abs(semis)) {
    0 -> "unisono"; 1 -> "2a min"; 2 -> "2a MAG"; 3 -> "3a min"; 4 -> "3a MAG"
    5 -> "4a giusta"; 6 -> "tritono"; 7 -> "5a giusta"; 8 -> "6a min"; 9 -> "6a MAG"
    10 -> "7a min"; 11 -> "7a MAG"; 12 -> "ottava"; else -> "$semis st"
}

private fun confronto(chord: Chord, melodia: List<Int>, grado: Int) {
    val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
    eng.setContext(chord)
    val a = eng.allowed

    print("  ${chord}   scala: ")
    println(a.notes.filter { it in 69..80 }.joinToString(" ") { Spelling.pc(it, chord) })
    println()
    println("  melodia    in scala   PRIMA (conteggio)      ADESSO (bersaglio)")
    line(72)

    for (n in melodia) {
        val inScala = if (a.contains(n)) "sì " else "NO "

        val vecchio = a.transposeByDegreesLegacy(n, grado, SnapPreference.NEAREST)
        val nuovo = a.transposeByDegrees(n, grado, SnapPreference.NEAREST)

        val sv = vecchio?.let { "%-5s %-12s".format(Spelling.note(it, chord), intervalName(it - n)) } ?: "-"
        val sn = nuovo?.let { "%-5s %-12s".format(Spelling.note(it, chord), intervalName(it - n)) } ?: "-"
        val marca = if (vecchio != nuovo) "  <<<" else ""

        println("  %-10s %s  %-22s %-22s%s".format(Spelling.note(n, chord), inScala, sv, sn, marca))
    }
}

fun main() {
    title("Note estranee — il caso che non avevi capito, con i numeri")

    println()
    println("  Il grado richiesto è sempre la TERZA SOPRA.")
    println("  Le righe marcate <<< sono quelle in cui i due metodi divergono.")
    println()
    println("  PRIMA:  aggancio la melodia alla nota di scala più vicina, poi conto")
    println("          due gradi da lì. Se l'aggancio sposta la melodia, l'errore si")
    println("          propaga e l'intervallo che esce non è più una terza.")
    println()
    println("  ADESSO: calcolo dove cadrebbe una terza a partire dalla nota REALE,")
    println("          poi aggancio la nota di scala più vicina a quel bersaglio.")
    println("          L'intervallo resta il più vicino possibile a una terza.")

    title("1 · Bb7 — la scala misolidia, con dentro le note cromatiche")
    confronto(
        Chord(10, ChordQuality.DOM7),
        listOf(70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81),
        Degrees.THIRD
    )
    println()
    println("  Le note in scala danno lo stesso risultato con entrambi i metodi:")
    println("  la correzione non tocca il comportamento che ti era già piaciuto.")
    println("  Cambia solo dove la melodia è cromatica.")

    title("2 · Il caso di prima: Bb su G#7alt")
    confronto(
        Chord(8, ChordQuality.DOM7ALT),
        listOf(69, 70, 71, 72),
        Degrees.THIRD
    )
    println()
    println("  Bb non appartiene alla scala alterata di G#. Prima veniva agganciato")
    println("  ad A e la terza contata da lì cadeva su C — una seconda maggiore")
    println("  sopra la melodia. Adesso il bersaglio è calcolato da Bb e la nota")
    println("  scelta è quella dell'accordo più vicina a una vera terza.")

    title("3 · Blue note su Bb7 — il caso che ti capiterà davvero")
    confronto(
        Chord(10, ChordQuality.DOM7),
        listOf(73, 76, 80),   // Db (terza minore blues), E (quinta bemolle), Ab
        Degrees.THIRD
    )
    println()
    println("  Db ed E sono la terza e la quinta abbassate del blues in Bb.")
    println("  Sono esattamente le note su cui la vecchia regola sbagliava di più.")

    title("4 · Grafia sul circolo delle quinte")
    println()
    println("  maggiori:")
    print("   ")
    for (pc in Spelling.CIRCLE_ORDER) print(" %-4s".format(Spelling.rootName(pc, false)))
    println()
    println("  relative minori:")
    print("   ")
    for (pc in Spelling.CIRCLE_ORDER)
        print(" %-4s".format(Spelling.rootName(Spelling.relativeMinor(pc), true).lowercase() + "-"))
    println()
    println()
    println("  Le note dentro gli accordi seguono il versante della fondamentale:")
    for (ch in listOf(
        Chord(10, ChordQuality.DOM7), Chord(3, ChordQuality.DOM7),
        Chord(6, ChordQuality.DOM7), Chord(8, ChordQuality.MIN7),
        Chord(8, ChordQuality.DOM7)
    )) {
        val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
        eng.setContext(ch)
        print("   %-8s ".format(ch.toString()))
        println(eng.allowed.notes.filter { it in 60..71 }.joinToString(" ") { Spelling.pc(it, ch) })
    }
    println()
    println("  Ab7 usa i bemolli, G#m7 i diesis: stessa classe, grafia diversa")
    println("  secondo il modo, come nelle tue due liste.")
    println()
}
