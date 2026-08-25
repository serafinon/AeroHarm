package harmonizer.core

/**
 * Banco di verifica del core, eseguibile SENZA l'Aerophone.
 * Serve a controllare a orecchio/occhio che la logica musicale sia giusta
 * prima ancora di avere lo strumento.
 */

private fun hr(title: String) {
    println()
    println("=".repeat(74))
    println("  $title")
    println("=".repeat(74))
}

private fun semis(a: Int, b: Int) = b - a

// Blues in Si bemolle, 12 battute
private val BLUES_BB = Progression(
    "Blues in Bb",
    listOf(
        ChordStep.battute(Chord(10, ChordQuality.DOM7), 1),   // Bb7
        ChordStep.battute(Chord(3, ChordQuality.DOM7), 1),    // Eb7
        ChordStep.battute(Chord(10, ChordQuality.DOM7), 2),   // Bb7
        ChordStep.battute(Chord(3, ChordQuality.DOM7), 2),    // Eb7
        ChordStep.battute(Chord(10, ChordQuality.DOM7), 2),   // Bb7
        ChordStep.battute(Chord(5, ChordQuality.DOM7), 1),    // F7
        ChordStep.battute(Chord(3, ChordQuality.DOM7), 1),    // Eb7
        ChordStep.battute(Chord(10, ChordQuality.DOM7), 1),   // Bb7
        ChordStep.battute(Chord(5, ChordQuality.DOM7), 1)     // F7
    )
)

// ---------------------------------------------------------------- test 1

/**
 * La verifica che conta: la SECONDA deve essere maggiore o minore a seconda
 * del grado, senza che nessuno gliel'abbia detto.
 */
private fun testSecondeDiatoniche() {
    hr("1 · Seconde diatoniche su Bb7 — la qualità deve emergere da sola")

    val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
    eng.setContext(Chord(10, ChordQuality.DOM7))
    val cfg = VoiceConfig("2a sopra", listOf(Degrees.SECOND), SelectionMode.FIXED)
    val st = VoiceState()

    println("  Accordo: ${eng.chord}   scala misolidia")
    print("  Note ammesse in un'ottava: ")
    println(eng.allowed.notes.filter { it in 70..81 }.joinToString(" ") { noteName(it) })
    println()
    println("  melodia   armonia   distanza   qualità attesa")
    println("  " + "-".repeat(50))

    for (n in eng.allowed.notes.filter { it in 70..81 }) {
        val h = eng.harmonize(n, cfg, st, newPhrase = false) ?: continue
        val d = semis(n, h)
        val q = if (d == 2) "2a MAGGIORE" else if (d == 1) "2a minore" else "?? ($d)"
        println("  %-9s %-9s %d semitoni  %s".format(noteName(n), noteName(h), d, q))
    }
    println()
    println("  Atteso: minore solo fra il 3° e il 4° grado (D->Eb) e fra il 6° e")
    println("  il 7° (G->Ab). Tutte le altre maggiori. Nessuna riga di codice")
    println("  decide 'maggiore o minore': esce dall'aritmetica sui gradi.")
}

// ---------------------------------------------------------------- test 2

/**
 * Spec §7: nota tenuta attraverso i cambi di accordo del blues.
 * La voce di armonia deve muoversi; se non cambia, non si invia nulla.
 */
private fun testNotaTenuta() {
    hr("2 · Nota tenuta attraverso il giro — le voci si muovono sotto")

    val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
    val held = 70  // Bb4, tenuto per tutte e 12 le battute

    val cfgTerza = VoiceConfig("3a sopra", listOf(Degrees.THIRD), SelectionMode.FIXED)
    val cfgSeconda = VoiceConfig("2a sopra", listOf(Degrees.SECOND), SelectionMode.FIXED)
    val stTerza = VoiceState()
    val stSeconda = VoiceState()

    println("  Il musicista tiene ${noteName(held)} per tutto il giro.")
    println()
    println("  bat  accordo   3a sopra          2a sopra")
    println("  " + "-".repeat(56))

    var first = true
    for (bar in 0 until BLUES_BB.totalBars()) {
        val ch = BLUES_BB.chordAt(bar * BATTITI_DEFAULT)
        if (ch == eng.chord && !first) {
            // stesso accordo della battuta precedente: nessun ricalcolo
            println("  %-4d %-9s %-17s %s".format(bar + 1, ch.toString(), "·", "·"))
            continue
        }
        eng.setContext(ch)

        val t: String
        val s: String
        if (first) {
            t = eng.harmonize(held, cfgTerza, stTerza, false)?.let { noteName(it) } ?: "-"
            s = eng.harmonize(held, cfgSeconda, stSeconda, false)?.let { noteName(it) } ?: "-"
            first = false
        } else {
            t = eng.retune(held, cfgTerza, stTerza)?.let { "-> " + noteName(it) } ?: "· invariata"
            s = eng.retune(held, cfgSeconda, stSeconda)?.let { "-> " + noteName(it) } ?: "· invariata"
        }
        println("  %-4d %-9s %-17s %s".format(bar + 1, ch.toString(), t, s))
    }

    println()
    println("  La terza si muove D -> Db -> D seguendo l'armonia: maggiore su Bb7,")
    println("  minore su Eb7. La seconda resta C su tutti e tre gli accordi, quindi")
    println("  non viene inviato alcun messaggio: è l'ottimizzazione della spec §7.3.")
}

// ---------------------------------------------------------------- test 3

/** Spec §5.4: voice leading contro random puro, a parità di insieme di gradi. */
private fun testVoiceLeading() {
    hr("3 · Voice leading contro random — perché il default non è il random")

    val melodia = listOf(70, 72, 74, 75, 77, 75, 74, 72, 70)  // frase su Bb7
    val set = listOf(Degrees.THIRD, Degrees.FIFTH, Degrees.SIXTH)

    // random deterministico, così il confronto è riproducibile
    var seed = 12345L
    val rnd = {
        seed = (seed * 6364136223846793005L + 1442695040888963407L)
        ((seed ushr 33).toDouble() / (1L shl 31).toDouble()).coerceIn(0.0, 0.999999)
    }

    for (mode in listOf(SelectionMode.RANDOM, SelectionMode.VOICE_LEADING)) {
        seed = 12345L
        val eng = HarmonyEngine(snapMode = SnapMode.SCALE, random = rnd)
        eng.setContext(Chord(10, ChordQuality.DOM7))
        val cfg = VoiceConfig("voce", set, mode, low = 60, high = 90)
        val st = VoiceState()

        val out = ArrayList<String>()
        var salto = 0
        var prev: Int? = null
        for (n in melodia) {
            val h = eng.harmonize(n, cfg, st, false)
            if (h == null) { out.add("-"); continue }
            out.add(noteName(h))
            prev?.let { salto += kotlin.math.abs(h - it) }
            prev = h
        }
        println()
        println("  %-14s %s".format(mode.name, out.joinToString(" ")))
        println("  %-14s movimento totale: %d semitoni".format("", salto))
    }

    println()
    println("  Stesso insieme di gradi, stessa frase, stesso seme. Il voice leading")
    println("  sceglie ogni volta il candidato più vicino alla nota precedente:")
    println("  meno movimento, linea più cantabile. Il random salta.")
}

// ---------------------------------------------------------------- test 4

/** Spec §5.2: la qualità dell'intervallo cambia col tipo di accordo. */
private fun testQualitaPerAccordo() {
    hr("4 · Stessa melodia, accordi diversi — terza sopra")

    val nota = 70  // Bb4
    val cfg = VoiceConfig("3a", listOf(Degrees.THIRD), SelectionMode.FIXED)

    println("  Melodia fissa: ${noteName(nota)}")
    println()
    println("  accordo      armonia   distanza   ")
    println("  " + "-".repeat(44))

    val prove = listOf(
        Chord(10, ChordQuality.MAJ7), Chord(10, ChordQuality.DOM7),
        Chord(10, ChordQuality.MIN7), Chord(10, ChordQuality.MIN7B5),
        Chord(3, ChordQuality.DOM7), Chord(5, ChordQuality.DOM7),
        Chord(7, ChordQuality.MIN7), Chord(8, ChordQuality.DOM7ALT)
    )
    for (ch in prove) {
        val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
        eng.setContext(ch)
        val st = VoiceState()
        val h = eng.harmonize(nota, cfg, st, false)
        if (h == null) { println("  %-12s -".format(ch.toString())); continue }
        val d = semis(nota, h)
        val q = when (d) { 3 -> "minore"; 4 -> "MAGGIORE"; 2 -> "seconda!"; 5 -> "quarta!"; else -> "$d st" }
        println("  %-12s %-9s %d semitoni  %s".format(ch.toString(), noteName(h), d, q))
    }
}

// ---------------------------------------------------------------- test 5

/** Spec §5.4: il registro della voce non deve sfondare. */
private fun testRegistro() {
    hr("5 · Clamp di registro — la voce non sfonda in acuto")

    val eng = HarmonyEngine(snapMode = SnapMode.SCALE)
    eng.setContext(Chord(10, ChordQuality.DOM7))
    val cfg = VoiceConfig("2a", listOf(Degrees.SECOND), SelectionMode.FIXED, low = 60, high = 79)
    val st = VoiceState()

    println("  Registro della voce: ${noteName(60)} – ${noteName(79)}")
    println()
    println("  melodia   armonia   nota")
    println("  " + "-".repeat(40))
    for (n in listOf(65, 70, 75, 77, 79, 81, 84)) {
        val h = eng.harmonize(n, cfg, st, false)
        val nota = if (h == null) "scartata" else if (h < n) "ripiegata di un'ottava" else ""
        println("  %-9s %-9s %s".format(noteName(n), h?.let { noteName(it) } ?: "-", nota))
    }
    println()
    println("  Sopra il limite la voce si ripiega di un'ottava invece di sparire")
    println("  in cima al registro.")
}

// ---------------------------------------------------------------- main

fun main() {
    println()
    println("  ARMONIZZATORE AE-20 — verifica del core, senza hardware")
    println("  Kotlin puro, nessuna dipendenza da Android o MIDI")

    testSecondeDiatoniche()
    testNotaTenuta()
    testVoiceLeading()
    testQualitaPerAccordo()
    testRegistro()

    println()
    println("=".repeat(74))
    println("  Se questi cinque blocchi ti convincono musicalmente, il motore è")
    println("  a posto e resta solo il guscio MIDI da costruire.")
    println("=".repeat(74))
    println()
}
