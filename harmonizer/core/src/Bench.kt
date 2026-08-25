package harmonizer.core

/** Quanto costa davvero il core per ogni nota. Numeri, non opinioni. */
fun main() {
    val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
    eng.setContext(Chord(10, ChordQuality.DOM7), Scales.MISOLIDIA)

    val voci = listOf(
        VoiceConfig("v1", listOf(Degrees.THIRD, Degrees.FIFTH, Degrees.SIXTH),
            SelectionMode.VOICE_LEADING, 55, 95, 2),
        VoiceConfig("v2", listOf(Degrees.SECOND, Degrees.FOURTH),
            SelectionMode.VOICE_LEADING, 55, 95, 3),
        VoiceConfig("v3", listOf(Degrees.OCTAVE), SelectionMode.FIXED, 40, 90, 4)
    )
    val stati = voci.map { VoiceState() }
    val note = IntArray(64) { 58 + (it * 7) % 30 }

    fun giro(n: Int): Long {
        var acc = 0
        val t0 = System.nanoTime()
        for (i in 0 until n) {
            val m = note[i and 63]
            for (v in voci.indices) {
                acc += eng.harmonize(m, voci[v], stati[v], false) ?: 0
            }
        }
        val t = System.nanoTime() - t0
        if (acc == Int.MIN_VALUE) println("")   // impedisce l'eliminazione
        return t
    }

    giro(200_000)   // riscaldamento JIT
    giro(200_000)

    val n = 1_000_000
    val t = giro(n)
    val perNota = t.toDouble() / n

    println()
    println("  Costo del core per NOTA (3 voci, voice leading su 3 gradi)")
    println("  " + "-".repeat(58))
    println("  %,d note in %.1f ms".format(n, t / 1e6))
    println("  per nota: %.3f microsecondi".format(perNota / 1000.0))
    println("  cioè %.5f millisecondi".format(perNota / 1e6))

    // costo del cambio di accordo: ricostruzione dell'insieme ammesso
    val m = 200_000
    val t1 = System.nanoTime()
    for (i in 0 until m) {
        eng.setContext(Chord(i % 12, ChordQuality.DOM7), Scales.MISOLIDIA)
    }
    val t2 = System.nanoTime() - t1
    println()
    println("  Costo di un CAMBIO DI ACCORDO (ricostruzione dell'insieme)")
    println("  " + "-".repeat(58))
    println("  per cambio: %.3f microsecondi".format((t2.toDouble() / m) / 1000.0))
    println("  avviene qualche volta al minuto, non per nota")

    println()
    println("  Riferimento: un evento MIDI ha un budget di qualche MILLISECONDO.")
    println("  Il core sta tre o quattro ordini di grandezza sotto.")
    println()
}
