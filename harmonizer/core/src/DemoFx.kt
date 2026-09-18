package harmonizer.app

import harmonizer.core.*

/**
 * Banco di verifica degli effetti: armonizzatore a piu' voci e arpeggiatore,
 * **senza Android e senza strumento**.
 *
 * Sta in core/src perche' e' logica pura e si compila su JVM, ma dichiara
 * `package harmonizer.app`: e' quello di [Harmonizer], [Fx] e [Runtime], che
 * di Android non sanno nulla.
 *
 *   K="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc"
 *   java -cp "$K/lib/kotlin-compiler.jar" \
 *     org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -d outfx \
 *     core/src/Harmony.kt core/src/Scales.kt core/src/DemoFx.kt \
 *     android/app/src/main/java/harmonizer/app/Fx.kt \
 *     android/app/src/main/java/harmonizer/app/Runtime.kt \
 *     android/app/src/main/java/harmonizer/app/Harmonizer.kt
 *   java -cp "outfx:$K/lib/kotlin-stdlib.jar" harmonizer.app.DemoFxKt
 *
 * Il tempo e' un parametro, non l'orologio: le prove chiamano tick() agli
 * istanti che vogliono, anche irregolari. Cosi' si verificano le sestine, lo
 * swing e il comportamento sotto una pausa del sistema senza aspettare.
 */

class Spia : MidiOut {
    val righe = ArrayList<String>()
    var noteAttive = HashMap<Int, MutableList<Int>>()
    override fun noteOn(ch: Int, note: Int, vel: Int) {
        righe.add("on  ch$ch ${noteName(note)}($note) v$vel")
        noteAttive.getOrPut(ch) { mutableListOf() }.add(note)
    }
    override fun noteOff(ch: Int, note: Int) {
        righe.add("off ch$ch ${noteName(note)}($note)")
        noteAttive[ch]?.remove(note)
    }
    override fun cc(ch: Int, num: Int, value: Int) {}
    override fun pitchBend(ch: Int, value14: Int) {}
    override fun aftertouch(ch: Int, value: Int) {}
    fun appese(): Int = noteAttive.values.sumOf { it.size }
    fun pulisci() { righe.clear() }
    fun soloNoteOn() = righe.filter { it.startsWith("on") }
}

fun titolo(t: String) = println("\n=== $t ===")

fun main() {
    val NS = 1_000_000_000L

    // ------------------------------------------------- harmonizer: 3 voci
    titolo("harmonizer, 3 voci su gradi 3a/5a/7a, grado fisso")
    var spia = Spia()
    var h = Harmonizer(spia)
    h.applicaFx(FxTipo.HARMONIZER,
        HarmonizerCfg(3, SelectionMode.FIXED, mutableListOf(2, 4, 6, 1)), ArpCfg())
    println("voci: " + h.config.voices.joinToString { "${it.name} ch${it.channel} ${it.degreeSet}" })
    var t = 0L
    h.resync(t)
    h.onNoteOn(60, 100, t)                      // C4 sul primo passo (F lidia)
    spia.righe.forEach { println("  $it") }
    check(spia.righe.count { it.startsWith("on") } == 4) { "melodia + 3 voci" }
    h.onNoteOff(60, t)
    check(spia.appese() == 0) { "note appese: ${spia.appese()}" }

    titolo("harmonizer: un intervallo PER VOCE, non uno in comune")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.HARMONIZER, HarmonizerCfg(
        voci = 3, modo = SelectionMode.VOICE_LEADING,
        gradiMin = mutableListOf(1, 3, 5, 6),      // 2a  4a  6a
        gradiMax = mutableListOf(2, 4, 6, 7)       // 3a  5a  7a
    ), ArpCfg())
    for ((i, v) in h.config.voices.withIndex())
        println("  voce ${i + 1}: insieme ${v.degreeSet}")
    // ogni voce ha il suo, e ruotato: due voci con lo stesso intervallo non
    // partirebbero altrimenti dallo stesso grado
    check(h.config.voices.map { it.degreeSet } ==
          listOf(listOf(1, 2), listOf(4, 3), listOf(5, 6)))
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    spia.righe.forEach { println("  $it") }
    check(spia.appese() == 4)
    h.onNoteOff(60, 0L)
    check(spia.appese() == 0)

    titolo("harmonizer: le voci restano in fasce distinte")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.HARMONIZER, HarmonizerCfg(
        voci = 2, modo = SelectionMode.RANDOM,
        gradiMin = mutableListOf(1, 4, 5, 6),      // voce 1: 2a-3a, voce 2: 5a-6a
        gradiMax = mutableListOf(2, 5, 6, 7)
    ), ArpCfg())
    h.resync(0L)
    val v1 = HashSet<Int>(); val v2 = HashSet<Int>()
    for (k in 0 until 200) {
        spia.pulisci()
        h.onNoteOn(60, 100, 0L)
        for (r in spia.soloNoteOn()) {
            val n = r.substringAfter("(").substringBefore(")").toInt()
            if (r.contains("ch2")) v1.add(n)
            if (r.contains("ch3")) v2.add(n)
        }
        h.onNoteOff(60, 0L)
    }
    println("  voce 1 (2a-3a): ${v1.sorted()}")
    println("  voce 2 (5a-6a): ${v2.sorted()}")
    check(v1.size >= 2 && v2.size >= 2) { "casuale che non si muove" }
    check(v1.max() < v2.min()) { "le fasce si sovrappongono" }

    titolo("harmonizer: l'intervallo non dipende dal numero di voci")
    // il bug: legando l'insieme alle voci, con UNA voce l'insieme aveva un
    // elemento e "casuale" non poteva muovere niente
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.HARMONIZER, HarmonizerCfg(
        voci = 1, modo = SelectionMode.RANDOM,
        gradiMin = mutableListOf(1, 1, 1, 1),
        gradiMax = mutableListOf(4, 4, 4, 4)
    ), ArpCfg())
    println("insieme della voce unica: ${h.config.voices[0].degreeSet}")
    check(h.config.voices[0].degreeSet.size == 4) { "una voce sola non si muove" }

    titolo("harmonizer: l'unisono dentro un intervallo piu' largo viene saltato")
    val cfgU = HarmonizerCfg(1, SelectionMode.RANDOM,
        gradiMin = mutableListOf(-2, -2, -2, -2), gradiMax = mutableListOf(2, 2, 2, 2))
    println("  intervallo -2..2 -> insieme ${cfgU.insiemeGradi(0)}")
    check(0 !in cfgU.insiemeGradi(0))
    check(cfgU.saltaUnisono())
    val cfgU0 = HarmonizerCfg(1, SelectionMode.RANDOM,
        gradiMin = mutableListOf(0, 0, 0, 0), gradiMax = mutableListOf(0, 0, 0, 0))
    println("  intervallo 0..0  -> insieme ${cfgU0.insiemeGradi(0)}  (scelta esplicita)")
    check(cfgU0.insiemeGradi(0) == listOf(0))
    check(!cfgU0.saltaUnisono())

    titolo("harmonizer: le voci si spostano al cambio di accordo, conservando il grado")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.HARMONIZER,
        HarmonizerCfg(2, SelectionMode.FIXED, mutableListOf(2, 4, 6, 1)), ArpCfg())
    h.transport.bpm = 120.0                     // un quarto = 0,5 s
    // la progressione predefinita e' tutta in Do: per vedere lo spostamento
    // serve un passo che cambi davvero insieme di note
    h.progression = Progression("prova", listOf(
        ChordStep.battute(Chord(0, ChordQuality.MAJ), Scales.IONICA, 1),
        ChordStep.battute(Chord(3, ChordQuality.DOM7), Scales.MISOLIDIA, 1)))
    h.resync(0L)
    h.onNoteOn(60, 100, 0L)
    spia.pulisci()
    h.tick(2L * NS + 1)                         // battuta 2: D- dorica
    println("  passo ${h.currentStepIndex(2L*NS+1)}: ${h.currentStep(2L*NS+1)}")
    spia.righe.forEach { println("  $it") }
    check(spia.righe.any { it.startsWith("on") }) { "nessuno spostamento" }
    h.onNoteOff(60, 3L * NS)
    check(spia.appese() == 0) { "note appese dopo il cambio: ${spia.appese()}" }

    // ------------------------------------------------------- arpeggiatore
    titolo("arpeggiatore: 1/8 su, note dell'accordo, da C4 su F (progressione default)")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(),
        ArpCfg(ArpPattern.SU, 2, 1, 70, 0, true))
    h.transport.bpm = 120.0
    h.resync(0L)
    h.onNoteOn(60, 100, 0L)
    spia.pulisci()
    // due battute a passi di 10 ms
    var passi = 0
    for (ms in 0..999) { h.tick(ms * 1_000_000L); passi++ }
    spia.soloNoteOn().forEach { println("  $it") }
    // 120 bpm, 1/8 = 250 ms -> 4 note in un secondo
    check(spia.soloNoteOn().size == 4) { "attese 4 note, ${spia.soloNoteOn().size}" }

    titolo("arpeggiatore: sestine (6 per quarto) — l'articolazione irregolare esiste")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(),
        ArpCfg(ArpPattern.SU_GIU, 6, 2, 70, 0, true))
    h.transport.bpm = 120.0
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    spia.pulisci()
    for (us in 0 until 1_000_000) if (us % 500 == 0) h.tick(us * 1000L)  // 1 s, ogni 0,5 ms
    println("  note emesse in 1 s: ${spia.soloNoteOn().size} (attese 12)")
    check(spia.soloNoteOn().size == 12)
    spia.soloNoteOn().take(14).forEach { println("  $it") }

    titolo("arpeggiatore: swing 30% ritarda gli step dispari")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(),
        ArpCfg(ArpPattern.SU, 2, 1, 70, 30, true))
    h.transport.bpm = 120.0
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    val istanti = ArrayList<Long>()
    val spia2 = spia
    for (ms in 0 until 1000) {
        val prima = spia2.soloNoteOn().size
        h.tick(ms * 1_000_000L)
        if (spia2.soloNoteOn().size > prima) istanti.add(ms.toLong())
    }
    println("  attacchi (ms): $istanti   — atteso 0, 325, 500, 825")
    check(istanti.size == 4 && Math.abs(istanti[1] - 325L) <= 1 && Math.abs(istanti[3] - 825L) <= 1)

    titolo("arpeggiatore: retrigger — off prima di on, e nessuna nota appesa")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(), ArpCfg(ArpPattern.SU, 4, 1, 100, 0, true))
    h.transport.bpm = 120.0
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    spia.pulisci()
    for (ms in 0..600) h.tick(ms * 1_000_000L)
    var precedente = ""
    for (r in spia.righe) {
        if (r.startsWith("on") && precedente.startsWith("on"))
            error("due note-on di fila sull'arpeggio: nessun riattacco")
        precedente = r
    }
    h.onNoteOff(60, 700L * 1_000_000L)
    h.tick(701L * 1_000_000L)
    check(spia.appese() == 0) { "arpeggio appeso: ${spia.appese()}" }
    println("  ok: sequenza off/on alternata, niente note appese")

    titolo("arpeggiatore: segue la progressione")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(), ArpCfg(ArpPattern.SU, 1, 1, 50, 0, true))
    h.transport.bpm = 120.0
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    // 120 bpm, 4/4: una battuta dura 2 s, quindi un passo ogni 2 s
    for (b in 0..3) {
        spia.pulisci()
        for (ms in b * 2000 until b * 2000 + 500) h.tick(ms * 1_000_000L)
        println("  passo ${h.currentStepIndex(b * 2000L * 1_000_000L)} " +
                "(${h.currentStep(b * 2000L * 1_000_000L)}): " +
                spia.soloNoteOn().joinToString())
    }

    titolo("arpeggiatore: FX muto silenzia, la progressione continua")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(), ArpCfg(ArpPattern.SU, 2, 1, 70, 0, true))
    h.transport.bpm = 120.0
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    for (ms in 0..300) h.tick(ms * 1_000_000L)
    h.setMuted(true)
    check(spia.appese() == 1) { "resta la melodia" }
    spia.pulisci()
    for (ms in 301..4300) h.tick(ms * 1_000_000L)
    check(spia.soloNoteOn().isEmpty()) { "muto ma suona" }
    val passoDuranteMuto = h.currentStepIndex(4300L * 1_000_000L)
    println("  a muto la progressione e' arrivata al passo $passoDuranteMuto (atteso 2)")
    check(passoDuranteMuto == 2)
    h.setMuted(false)
    for (ms in 4301..4600) h.tick(ms * 1_000_000L)
    check(spia.soloNoteOn().isNotEmpty()) { "riacceso non rientra" }
    println("  riacceso rientra: ${spia.soloNoteOn().first()}")

    titolo("arpeggiatore: STOP spegne, BATT.1 riparte dal primo step")
    h.stopAll(4700L * 1_000_000L)
    check(spia.appese() == 1) { "dopo lo stop resta solo la melodia, appese=${spia.appese()}" }
    spia.pulisci()
    for (ms in 4701..4800) h.tick(ms * 1_000_000L)
    check(spia.soloNoteOn().isEmpty()) { "a transport fermo l'arpeggio tace" }
    h.resync(4900L * 1_000_000L)
    h.tick(4901L * 1_000_000L)
    println("  dopo BATT.1: ${spia.soloNoteOn()}")
    check(spia.soloNoteOn().size == 1)

    titolo("arpeggiatore: scala invece dell'accordo, 2 ottave giù")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(), ArpCfg(ArpPattern.GIU, 4, 2, 60, 0, false))
    h.transport.bpm = 120.0
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    for (ms in 0..500) h.tick(ms * 1_000_000L)
    println("  " + spia.soloNoteOn().joinToString())

    titolo("cambio di effetto a caldo: niente note appese")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.HARMONIZER, HarmonizerCfg(3, SelectionMode.FIXED,
        mutableListOf(2, 4, 6, 1)), ArpCfg())
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    check(spia.appese() == 4)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(3, SelectionMode.FIXED,
        mutableListOf(2, 4, 6, 1)), ArpCfg())
    check(spia.appese() == 1) { "resta solo la melodia, appese=${spia.appese()}" }
    println("  ok")

    titolo("arpeggiatore: clock irregolare (jitter 0-4 ms) — nessuno step perso")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(), ArpCfg(ArpPattern.SU, 6, 1, 70, 0, true))
    h.transport.bpm = 200.0                     // sestine a 200 bpm: uno step ogni 50 ms
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    spia.pulisci()
    var orario = 0L
    val rnd = java.util.Random(7)
    while (orario < 3_000_000_000L) {
        h.tick(orario)
        orario += 500_000L + rnd.nextInt(3_500_000).toLong()   // 0,5 - 4 ms
    }
    val attese = (3.0 * 200.0 / 60.0 * 6).toInt()              // 60 note in 3 s
    println("  note emesse: ${spia.soloNoteOn().size}, attese ~$attese")
    check(Math.abs(spia.soloNoteOn().size - attese) <= 1)

    titolo("arpeggiatore: pausa del sistema di 200 ms — una nota, non una raffica")
    spia = Spia(); h = Harmonizer(spia)
    h.applicaFx(FxTipo.ARPEGGIATOR, HarmonizerCfg(), ArpCfg(ArpPattern.SU, 4, 1, 70, 0, true))
    h.transport.bpm = 120.0                     // 1/16: uno step ogni 125 ms
    h.resync(0L); h.onNoteOn(60, 100, 0L)
    h.tick(0L)
    spia.pulisci()
    h.tick(500_000_000L)                        // 500 ms dopo: quattro step saltati
    println("  note emesse dopo la pausa: ${spia.soloNoteOn().size}")
    check(spia.soloNoteOn().size == 1) { "raffica di recupero" }

    // ------------------------------------------------------------- voicing

    titolo("voicing: la tabella dei gradi — il nome dell'accordo, poi la scala")
    fun grado(g: Int, root: Int, q: ChordQuality, sc: Scale): String {
        val pc = GradiAccordo.classe(g, Chord(root, q), sc)
        return if (pc == null) "-" else Spelling.pc(pc, Chord(root, q))
    }
    println("  C7 misolidia:  9=${grado(9,0,ChordQuality.DOM7,Scales.MISOLIDIA)}" +
            "  13=${grado(13,0,ChordQuality.DOM7,Scales.MISOLIDIA)}" +
            "  3=${grado(3,0,ChordQuality.DOM7,Scales.MISOLIDIA)}")
    println("  C7 alterata:   9=${grado(9,0,ChordQuality.DOM7,Scales.ALTERATA)}" +
            "  13=${grado(13,0,ChordQuality.DOM7,Scales.ALTERATA)}" +
            "  3=${grado(3,0,ChordQuality.DOM7,Scales.ALTERATA)}")
    println("  C ionica:      7=${grado(7,0,ChordQuality.MAJ,Scales.IONICA)}")
    println("  Cm eolia:      7=${grado(7,0,ChordQuality.MIN,Scales.EOLIA)}")
    println("  Cm frigia:     9=${grado(9,0,ChordQuality.MIN,Scales.FRIGIA)}")
    println("  C7sus4:        3=${grado(3,0,ChordQuality.DOM7SUS4,Scales.MISOLIDIA)} (diventa 4)")
    println("  Cm7b5 locria:  5=${grado(5,0,ChordQuality.MIN7B5,Scales.LOCRIA)}")
    // il nome dell'accordo non si fa contraddire dalla scala
    check(GradiAccordo.classe(3, Chord(0, ChordQuality.DOM7), Scales.ALTERATA) == 4)
    // i gradi non nel nome escono dalla scala
    check(GradiAccordo.classe(9, Chord(0, ChordQuality.DOM7), Scales.MISOLIDIA) == 2)
    check(GradiAccordo.classe(9, Chord(0, ChordQuality.DOM7), Scales.ALTERATA) == 1)
    check(GradiAccordo.classe(13, Chord(0, ChordQuality.DOM7), Scales.ALTERATA) == 8)
    check(GradiAccordo.classe(7, Chord(0, ChordQuality.MAJ), Scales.IONICA) == 11)
    check(GradiAccordo.classe(7, Chord(0, ChordQuality.MIN), Scales.EOLIA) == 10)
    check(GradiAccordo.classe(3, Chord(0, ChordQuality.DOM7SUS4), Scales.MISOLIDIA) == 5)
    // scala a poche note: si ripiega sulla scala di riferimento della qualita'
    check(GradiAccordo.classe(9, Chord(0, ChordQuality.DOM7), Scales.BLUES) == 2)

    val voicer = Voicer()
    val nessuna = IntArray(MAX_VOCI_VOICING + 2) { -1 }

    fun mostra(lead: Int, step: ChordStep, cfg: VoicingCfg,
               prec: IntArray = nessuna, leadPrec: Int = -1): IntArray {
        val n = voicer.costruisci(lead, step, cfg, prec, leadPrec)
        println("  ${cfg.tipo.etichetta} su ${step.chord}, lead ${noteName(lead)}: " +
                voicer.descrizione(step) + "   ($n voci)")
        val out = IntArray(n) { voicer.note[it] }
        // invarianti che valgono per ogni tipo e ogni accordo
        for (v in out) if (v >= 0) {
            check(v < lead) { "voce sopra o pari alla lead: $v >= $lead" }
            check(v >= cfg.notaMinima()) { "voce sotto il registro" }
        }
        for (i in out.indices) for (k in 0 until i)
            check(out[i] < 0 || out[k] < 0 || out[i] != out[k]) { "due voci sulla stessa nota" }
        return out
    }

    titolo("voicing: four-way close e la famiglia drop, su C7")
    val c7 = ChordStep.battute(Chord(0, ChordQuality.DOM7), Scales.MISOLIDIA, 1)
    val close = mostra(72, c7, VoicingCfg(TipoVoicing.CLOSE, 3))
    check(close.toList() == listOf(70, 67, 64)) { "atteso Bb4 G4 E4, trovato ${close.toList()}" }
    val drop2 = mostra(72, c7, VoicingCfg(TipoVoicing.DROP2, 3))
    check(drop2.toList() == listOf(58, 67, 64)) { "drop 2: la seconda voce giu' di un'ottava" }
    mostra(72, c7, VoicingCfg(TipoVoicing.DROP3, 3))
    mostra(72, c7, VoicingCfg(TipoVoicing.DROP24, 3))
    val ance = mostra(72, c7, VoicingCfg(TipoVoicing.CLOSE_ANCE, 4))
    check(ance.contains(60)) { "la lead va raddoppiata un'ottava sotto" }

    titolo("voicing: rootless, e il 5 che diventa 13 sui dominanti")
    val dm7 = ChordStep.battute(Chord(2, ChordQuality.MIN7), Scales.DORICA, 1)
    val g7 = ChordStep.battute(Chord(7, ChordQuality.DOM7), Scales.MISOLIDIA, 1)
    val ra = mostra(69, dm7, VoicingCfg(TipoVoicing.ROOTLESS_A, 4))
    check(ra.none { pitchClass(it) == 2 }) { "il rootless non ha la fondamentale" }
    val rg = mostra(71, g7, VoicingCfg(TipoVoicing.ROOTLESS_A, 4))
    check(rg.any { pitchClass(it) == 4 }) { "sul dominante il 5 diventa 13: serve la E" }
    check(rg.none { pitchClass(it) == 2 }) { "sul dominante il 5 (D) non ci va" }
    mostra(71, g7, VoicingCfg(TipoVoicing.ROOTLESS_B, 4))

    titolo("voicing: shell, quartal, cluster, spread, corale")
    val sh = mostra(72, c7, VoicingCfg(TipoVoicing.SHELL_STRETTO, 3))
    check(sh.any { pitchClass(it) == 0 }) { "lo shell ha la fondamentale" }
    mostra(72, c7, VoicingCfg(TipoVoicing.SHELL_LARGO, 3))
    mostra(72, dm7, VoicingCfg(TipoVoicing.QUARTAL, 4))
    mostra(72, c7, VoicingCfg(TipoVoicing.CLUSTER, 3))
    mostra(72, c7, VoicingCfg(TipoVoicing.SPREAD, 4))
    val cm = ChordStep.battute(Chord(0, ChordQuality.MAJ), Scales.IONICA, 1)
    mostra(76, cm, VoicingCfg(TipoVoicing.CORALE_STRETTO, 3))
    mostra(76, cm, VoicingCfg(TipoVoicing.CORALE_LARGO, 3))

    titolo("voicing: l'apertura apre, su ogni tipo")
    val cfgAp = VoicingCfg(TipoVoicing.CLOSE, 3)
    var ampiezzaPrec = 0
    for (a in APERTURE.indices) {
        cfgAp.apertura = a
        val o = mostra(72, c7, cfgAp)
        val amp = 72 - (o.filter { it >= 0 }.minOrNull() ?: 72)
        println("      apertura ${NOMI_APERTURA[a]}: ampiezza $amp semitoni")
        check(amp >= ampiezzaPrec) { "apertura piu' larga ma ampiezza minore" }
        ampiezzaPrec = amp
    }
    check(ampiezzaPrec > 12) { "all'apertura massima il close deve spalancarsi" }

    titolo("voicing: le voci in piu' raddoppiano la struttura per ottave")
    // nessun tetto alle voci: un raddoppio d'ottava non e' un raddoppio di
    // volume, e i voicing grandi si scrivono cosi'
    for (t in listOf(TipoVoicing.SHELL_STRETTO, TipoVoicing.CLOSE,
                     TipoVoicing.CORALE_STRETTO)) {
        val cfg = VoicingCfg(t, voci = 8, apertura = 0, registro = 0)
        val o = mostra(76, c7, cfg)
        val classi = o.filter { it >= 0 }.map { pitchClass(it) }.toSet()
        val strutturali = if (t.struttura.isEmpty()) t.ammessi else t.struttura
        check(o.size >= 6) { "${t.etichetta}: con 8 voci ne suonano solo ${o.size}" }
        check(classi.size <= strutturali.size) {
            "${t.etichetta}: sono comparse classi che il tipo non prevede"
        }
        // scendono e non si ripetono: l'ordine della DP lo garantisce
        val vive = o.filter { it >= 0 }
        for (i in 1 until vive.size) check(vive[i] < vive[i - 1]) { "non discendono" }
    }

    titolo("voicing: il tipo comanda dove ha qualcosa da dire")
    // strutturale: un drop 3 senza la terza posizione non sposta niente
    val fuori = VoicingCfg(TipoVoicing.DROP24, voci = 1, apertura = 4)
    fuori.normalizza()
    println("  drop 2+4 chiesto con 1 voce -> ${fuori.voci} voci, " +
            "apertura ${NOMI_APERTURA[fuori.apertura]} (l'apertura non si tocca)")
    check(fuori.voci == TipoVoicing.DROP24.vociMin)
    check(fuori.apertura == 4)
    // l'apertura resta libera su ogni tipo: "shell largo" non e' "shell stretto
    // aperto", e' un altro ordine dei gradi, quindi uno shell 1-3-7 spaziato
    // largo e' un terzo voicing che va lasciato disponibile
    val stretto = VoicingCfg(TipoVoicing.SHELL_STRETTO, apertura = 4)
    stretto.normalizza()
    check(stretto.apertura == 4) { "l'apertura non va limitata dal tipo" }
    val s0 = mostra(72, c7, VoicingCfg(TipoVoicing.SHELL_STRETTO, 3, apertura = 0))
    val s4 = mostra(72, c7, VoicingCfg(TipoVoicing.SHELL_STRETTO, 3, apertura = 4))
    val l0 = mostra(72, c7, VoicingCfg(TipoVoicing.SHELL_LARGO, 3, apertura = 4))
    check(s0.toList() != s4.toList()) { "aprire lo shell stretto non cambia niente" }
    check(s4.toList() != l0.toList()) { "shell stretto aperto e shell largo coincidono" }
    println("  shell 1-3-7 serrato, 1-3-7 ampio e 1-7-3 ampio sono tre voicing diversi")
    // otto voci restano otto: in alto non c'e' tetto
    val tante = VoicingCfg(TipoVoicing.CORALE_STRETTO, voci = 8)
    tante.normalizza()
    check(tante.voci == 8) { "non ci deve essere un tetto alle voci" }
    for (t in TipoVoicing.values()) {
        check(t.vociMin in 1..MAX_VOCI_VOICING) { "${t.etichetta}: minimo assurdo" }
        check(t.vociTipiche >= t.vociMin) { "${t.etichetta}: il tipico e' sotto il minimo" }
        // il minimo strutturale e' quello che serve al drop piu' profondo
        val serve = (t.drop.maxOrNull() ?: 1) - 1
        check(t.vociMin >= serve) { "${t.etichetta}: il drop non avrebbe posizione" }
    }
    println("  tutti e ${TipoVoicing.values().size} i tipi dichiarano vincoli coerenti")

    titolo("voicing: le voci si muovono il meno possibile su un II-V-I")
    // la lead si muove come si muoverebbe davvero: A B C
    val cfgVl = VoicingCfg(TipoVoicing.ROOTLESS_A, 4)
    var prec = IntArray(MAX_VOCI_VOICING + 2) { -1 }
    var totale = 0
    var leadPrec = -1
    for ((k, coppia) in listOf(dm7 to 69, g7 to 71, cm to 72).withIndex()) {
        val passo = coppia.first
        val lead = coppia.second
        val n = voicer.costruisci(lead, passo, cfgVl, prec, leadPrec)
        val nuove = IntArray(MAX_VOCI_VOICING + 2) { -1 }
        for (i in 0 until n) nuove[i] = voicer.note[i]
        var ferme = 0
        if (k > 0) for (i in 0 until n) {
            if (nuove[i] < 0) continue
            var d = 99
            for (pv in prec) if (pv >= 0) d = minOf(d, Math.abs(nuove[i] - pv))
            if (d == 0) ferme++
            if (d < 99) totale += d
        }
        println("  ${passo.chord} (lead ${noteName(lead)}): ${voicer.descrizione(passo)}" +
                (if (k > 0) "   [$ferme note tenute]" else ""))
        prec = nuove
        leadPrec = lead
    }
    println("  movimento totale su due cambi d'accordo, 4 voci: $totale semitoni")
    println("  (misurato sull'insieme: quanto si sposta ogni nota nuova rispetto alla " +
            "piu' vicina di prima, non chi tiene cosa)")
    check(totale <= 24) { "le voci si muovono troppo: $totale" }

    titolo("voicing: con la lead ferma il voicing non si muove affatto")
    val cfgF = VoicingCfg(TipoVoicing.CLOSE, 3)
    val precF = IntArray(MAX_VOCI_VOICING + 2) { -1 }
    var n1 = voicer.costruisci(72, c7, cfgF, precF, -1)
    for (i in 0 until n1) precF[i] = voicer.note[i]
    val primo = precF.copyOf()
    n1 = voicer.costruisci(72, c7, cfgF, precF, 72)
    for (i in 0 until n1) check(voicer.note[i] == primo[i]) { "si e' mosso senza motivo" }
    println("  due volte la stessa lead sullo stesso accordo: nessuna nota cambia")

    titolo("voicing: senza posto suonano meno voci")
    val basso = mostra(45, c7, VoicingCfg(TipoVoicing.CLOSE, 4))
    check(basso.size < 4) { "con la lead in basso non ci stanno quattro voci" }

    titolo("voicing: quanto costa")
    val cfgB = VoicingCfg(TipoVoicing.DROP2, 8)
    val precB = IntArray(MAX_VOCI_VOICING + 2) { -1 }
    voicer.costruisci(72, c7, cfgB, precB, -1)
    var t0 = System.nanoTime()
    val giri = 20000
    for (k in 0 until giri) voicer.costruisci(72 - (k % 12), c7, cfgB, precB, -1)
    val perNota = (System.nanoTime() - t0).toDouble() / giri / 1000.0
    println("  %.2f microsecondi per nota (budget: qualche millisecondo)".format(perNota))
    check(perNota < 500.0)

    println("\nTUTTO OK")
}
