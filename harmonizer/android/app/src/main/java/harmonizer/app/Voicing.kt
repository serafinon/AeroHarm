package harmonizer.app

import harmonizer.core.*

/**
 * Il terzo effetto: **voicing**. Logica pura, nessuna dipendenza da Android.
 * Spec §15.
 *
 * Non serve per i soli — quello e' l'armonizzatore — ma per gli stacchi di
 * sezione e gli accompagnamenti. Differenze di fondo rispetto al primo
 * armonizzatore:
 *
 * - le note escono dai **gradi dell'accordo** (1 3 5 7 9 11 13), non dai gradi
 *   della scala;
 * - stanno **sotto** la nota suonata: qui il musicista e' sempre la lead;
 * - il numero di voci e' un **massimo**, non un obbligo: se non c'e' posto
 *   suonano meno note;
 * - la regola che governa tutto e' che le voci si muovano il meno possibile.
 */

/** Massimo di voci generate. Il tetto vero e' la polifonia dell'AE-20, non documentata. */
const val MAX_VOCI_VOICING = 8

/** Parti disponibili per l'armonia: canali 2-5, perche' l'AE-20 riceve su 1-5. */
const val PARTI_ARMONIA = 4

/** Ampiezza massima fra la lead e la voce piu' bassa. */
const val AMPIEZZA_MAX = 36

// --------------------------------------------------------- gradi d'accordo

/**
 * Grado d'accordo -> semitoni sopra la fondamentale.
 *
 * Due sorgenti, in ordine:
 *
 * 1. **il nome dell'accordo**, dove e' esplicito: su `C7` il 3 e' `E` e il 7 e'
 *    `Bb`, su `Cm7b5` il 5 e' `Gb`. Nessuna scala puo' contraddirlo.
 * 2. **la scala del passo**, per tutto il resto — 9, 11, 13, e il 7 sulle
 *    triadi. Cosi' su `C7` misolidia il 9 e' `D` e il 13 e' `A`, su `C7`
 *    alterata diventano `Db` e `Ab`. La specie esce dalla scala invece di
 *    essere indovinata, come nel primo motore la qualita' dell'intervallo.
 *
 * Se la scala del passo non ha sette note — blues, pentatoniche, esatonale,
 * diminuite — i gradi per indice non sono definiti e si ripiega sulla scala di
 * riferimento della qualita', che ogni [ChordQuality] porta con se'.
 */
object GradiAccordo {

    fun nome(g: Int): String = when (g) {
        1 -> "1"; 3 -> "3"; 4 -> "4"; 5 -> "5"; 6 -> "6"
        7 -> "7"; 9 -> "9"; 11 -> "11"; 13 -> "13"; else -> "$g"
    }

    /** Quello che il nome dell'accordo dichiara. Null = non lo dichiara. */
    private fun esplicito(q: ChordQuality, grado: Int): Int? = when (q) {
        ChordQuality.MAJ -> when (grado) { 1 -> 0; 3 -> 4; 5 -> 7; else -> null }
        ChordQuality.MAJ7 -> when (grado) { 1 -> 0; 3 -> 4; 5 -> 7; 7 -> 11; else -> null }
        ChordQuality.MAJ6 -> when (grado) { 1 -> 0; 3 -> 4; 5 -> 7; 6, 13 -> 9; else -> null }
        ChordQuality.DOM7 -> when (grado) { 1 -> 0; 3 -> 4; 5 -> 7; 7 -> 10; else -> null }
        // sus4: la terza non esiste, chi la chiede riceve la quarta
        ChordQuality.DOM7SUS4 -> when (grado) { 1 -> 0; 3, 4 -> 5; 5 -> 7; 7 -> 10; else -> null }
        // 7#11 non ha quinta: il 5 lo risolve la scala (lidia dominante)
        ChordQuality.DOM7SHARP11 -> when (grado) { 1 -> 0; 3 -> 4; 7 -> 10; 11 -> 6; else -> null }
        ChordQuality.DOM7ALT -> when (grado) { 1 -> 0; 3 -> 4; 5 -> 8; 7 -> 10; else -> null }
        ChordQuality.MIN -> when (grado) { 1 -> 0; 3 -> 3; 5 -> 7; else -> null }
        ChordQuality.MIN7 -> when (grado) { 1 -> 0; 3 -> 3; 5 -> 7; 7 -> 10; else -> null }
        ChordQuality.MIN6 -> when (grado) { 1 -> 0; 3 -> 3; 5 -> 7; 6, 13 -> 9; else -> null }
        ChordQuality.MIN7B5 -> when (grado) { 1 -> 0; 3 -> 3; 5 -> 6; 7 -> 10; else -> null }
        ChordQuality.DIM7 -> when (grado) { 1 -> 0; 3 -> 3; 5 -> 6; 7 -> 9; else -> null }
    }

    /** Grado -> passo di scala. Il 4 e l'11 sono lo stesso passo, come il 6 e il 13. */
    private fun passoDiScala(grado: Int): Int? = when (grado) {
        1 -> 0; 9 -> 1; 3 -> 2; 4, 11 -> 3; 5 -> 4; 6, 13 -> 5; 7 -> 6
        else -> null
    }

    fun semitoni(grado: Int, chord: Chord, scala: Scale): Int? {
        esplicito(chord.quality, grado)?.let { return it }
        val passo = passoDiScala(grado) ?: return null
        val riferimento = if (scala.intervals.size == 7) scala.intervals else chord.quality.scale
        if (passo >= riferimento.size) return null
        return riferimento[passo]
    }

    fun classe(grado: Int, chord: Chord, scala: Scale): Int? =
        semitoni(grado, chord, scala)?.let { pitchClass(it + chord.root) }
}

/**
 * Passo fra le voci, in semitoni. E' il parametro di apertura.
 *
 * Dove il tipo prescrive la spaziatura si somma come **scostamento** da
 * [APERTURA_NEUTRA]: il tipo decide il carattere e l'apertura lo apre o lo
 * chiude. Su ogni tipo, senza limiti: il tipo sceglie i gradi e il loro ordine,
 * l'apertura quanto stanno distanti.
 */
val APERTURE = intArrayOf(3, 4, 6, 8, 12)
val NOMI_APERTURA = arrayOf("serrato", "chiuso", "medio", "aperto", "ampio")

/** L'apertura a cui i tipi suonano come da manuale. */
const val APERTURA_NEUTRA = 1

// -------------------------------------------------------------- i tipi

/** Da dove si scende, quando la struttura nasce dalla lead. */
enum class GrigliaVoicing { ACCORDO, SCALA }

/**
 * Il catalogo. Due famiglie, e la differenza non e' cosmetica:
 *
 * - **struttura fissa** ([struttura] non vuota) — shell, rootless, spread,
 *   corale: la sequenza dei gradi e' decisa dal tipo, dal basso verso l'alto.
 *   L'unica libertà e' in quale ottava cade, e la sceglie il movimento minimo.
 * - **generata dalla lead** ([struttura] vuota) — close, drop, quartal,
 *   cluster: si scende da dove sei tu attraverso una griglia, e alcune voci si
 *   spostano di un'ottava dopo.
 *
 * Nelle sigle da sezione la lead conta come **prima voce dall'alto**: un
 * four-way close sono la tua nota piu' tre voci, quindi `voci = 3`. Per questo
 * [drop] conta le posizioni includendo la lead, com'e' d'uso: `drop 2` sposta
 * la seconda voce dall'alto, cioe' la prima generata.
 *
 * **Il tipo ha la precedenza sugli altri parametri**, ma solo dove ha davvero
 * qualcosa da dire, e sono due cose diverse:
 *
 * - [vociMin] e' un vincolo **strutturale**: un `drop 3` ha bisogno che la
 *   terza posizione esista, quindi di almeno due voci generate, altrimenti il
 *   nome promette uno spostamento che non avviene. In alto invece **non c'e'
 *   tetto**: le voci in piu' raddoppiano la struttura un'ottava sotto, che e'
 *   come si scrivono davvero i voicing grandi — uno shell a otto voci e' lo
 *   shell ripetuto per ottave, e un raddoppio d'ottava non e' un raddoppio di
 *   volume.
 * - **l'apertura invece e' libera su ogni tipo.** C'era un limite, ed era
 *   sbagliato: «shell stretto» e «shell largo» non sono lo stesso voicing piu'
 *   o meno spaziato, sono due **ordini di gradi** diversi — `1-3-7` contro
 *   `1-7-3`. Uno shell `1-3-7` disposto largo e' un terzo voicing che nessuno
 *   dei due tipi da', e vietarlo lo toglieva soltanto. Il tipo sceglie i gradi
 *   e il loro ordine, l'apertura quanto stanno distanti: sono due domande
 *   indipendenti, e restano indipendenti.
 *
 * Le voci sotto il minimo si spengono nell'interfaccia, e
 * [VoicingCfg.normalizza] riporta dentro i valori di una configurazione
 * salvata prima.
 */
enum class TipoVoicing(
    val etichetta: String,
    /** Gradi dal basso verso l'alto. Vuoto = struttura generata dalla lead. */
    val struttura: List<Int> = emptyList(),
    val griglia: GrigliaVoicing = GrigliaVoicing.ACCORDO,
    /** Gradi ammessi quando la struttura nasce dalla lead. */
    val ammessi: List<Int> = emptyList(),
    /** Passo bersaglio fra voci adiacenti, in semitoni. 0 = decide l'apertura. */
    val passoFisso: Int = 0,
    /** Passo della prima voce sotto la lead, se diverso. */
    val passoPrimo: Int = 0,
    /** Voci spostate un'ottava sotto, contando la lead come 1. */
    val drop: List<Int> = emptyList(),
    /** La voce piu' bassa va messa in fondo, staccata: ottoni. */
    val bassoProfondo: Boolean = false,
    /** La lead va raddoppiata un'ottava sotto: scrittura a cinque delle ance. */
    val raddoppiaLead: Boolean = false,
    /** Sui dominanti il 5 diventa 13: e' la regola dei rootless. */
    val quintaInTredicesima: Boolean = false,
    /** Penalizza quinte e ottave parallele fra voci adiacenti: corale. */
    val evitaParallele: Boolean = false,
    val vociTipiche: Int = 3,
    /** Voci generate minime perche' il tipo sia quello che dice. */
    val vociMin: Int = 1,
    val descrizione: String = ""
) {
    SHELL_STRETTO("shell stretto", struttura = listOf(1, 3, 7), vociTipiche = 3,
        descrizione = "Fondamentale e guide tone serrati. Due note dicono l'accordo: " +
            "il 3 e il 7 sono quelle che cambiano, il 5 non serve."),

    SHELL_LARGO("shell largo", struttura = listOf(1, 7, 3), vociTipiche = 3,
        descrizione = "Lo stesso in posizione larga — fondamentale, settima, decima. " +
            "E' la mano sinistra di Bud Powell: lascia aria in mezzo."),

    ROOTLESS_A("rootless A", struttura = listOf(3, 5, 7, 9), quintaInTredicesima = true,
        vociTipiche = 4,
        descrizione = "Senza fondamentale, dal basso 3-5-7-9. Sui dominanti il 5 " +
            "diventa 13. E' il comping di Bill Evans: la fondamentale la sente l'orecchio."),

    ROOTLESS_B("rootless B", struttura = listOf(7, 9, 3, 5), quintaInTredicesima = true,
        vociTipiche = 4,
        descrizione = "La stessa in seconda posizione, dal basso 7-9-3-5. Si alterna " +
            "con la A per muovere poco: su un II-V-I una tiene le note dell'altra."),

    QUARTAL("quartal", ammessi = listOf(1, 9, 3, 11, 5, 13, 7), griglia = GrigliaVoicing.SCALA,
        passoPrimo = 4, passoFisso = 5, vociTipiche = 4,
        descrizione = "Quarte impilate, terza in cima: il voicing di «So What». " +
            "Modale, sospeso, non dichiara la qualita' dell'accordo."),

    CLOSE("close (four-way)", ammessi = listOf(1, 3, 5, 7), passoFisso = 3, vociTipiche = 3,
        descrizione = "I quattro chord tone serrati sotto la lead. E' la sezione " +
            "sax da big band: con 3 voci sono quattro parti contando la tua."),

    CLOSE_ANCE("close 5 ance", ammessi = listOf(1, 3, 5, 7), passoFisso = 3,
        raddoppiaLead = true, vociTipiche = 4,
        descrizione = "Four-way close con la lead raddoppiata un'ottava sotto: la " +
            "scrittura a cinque delle cinque ance. Il raddoppio d'ottava non e' " +
            "un raddoppio di volume, e' un'altra nota."),

    DROP2("drop 2", ammessi = listOf(1, 3, 5, 7), passoFisso = 3, drop = listOf(2),
        vociTipiche = 3,
        descrizione = "Close con la seconda voce dall'alto giu' di un'ottava. Il piu' " +
            "usato di tutti: apre il centro e toglie l'impasto del close."),

    DROP3("drop 3", ammessi = listOf(1, 3, 5, 7), passoFisso = 3, drop = listOf(3),
        vociTipiche = 3,
        vociMin = 2,
        descrizione = "Come drop 2 ma cade la terza voce: piu' largo in basso, tiene " +
            "unite le due di sopra."),

    DROP24("drop 2+4", ammessi = listOf(1, 3, 5, 7), passoFisso = 3, drop = listOf(2, 4),
        vociTipiche = 3,
        vociMin = 3,
        descrizione = "Due voci giu' di un'ottava. Il piu' aperto della famiglia: " +
            "tutti da big band, ottoni."),

    SPREAD("spread (ottoni)", struttura = listOf(1, 7, 3, 5), bassoProfondo = true,
        vociTipiche = 4,
        descrizione = "Fondamentale in fondo, staccata, e il resto raccolto sotto la " +
            "lead: largo in basso e stretto in alto, come la serie armonica. " +
            "Il tutti degli ottoni."),

    CLUSTER("cluster (ance)", ammessi = listOf(1, 9, 3, 11, 5, 13, 7),
        griglia = GrigliaVoicing.SCALA, passoFisso = 2, vociTipiche = 3,
        descrizione = "Seconde e terze di scala impilate sotto la lead. Denso, opaco: " +
            "le ance di Thad Jones e di Ellington."),

    CORALE_STRETTO("corale stretto", struttura = listOf(1, 3, 5), passoFisso = 3,
        evitaParallele = true, vociTipiche = 3,
        descrizione = "Tre voci sotto la tua: SATB in posizione stretta, con te sul " +
            "soprano. Evita quinte e ottave parallele e tiene le note comuni."),

    CORALE_LARGO("corale largo", struttura = listOf(1, 5, 3), passoFisso = 5,
        evitaParallele = true, vociTipiche = 3,
        descrizione = "Lo stesso in armonia aperta. Non e' la stessa cosa piu' larga: " +
            "e' un altro ordine dei gradi — 1-5-3 invece di 1-3-5 — e sono i gradi a " +
            "fare la posizione, perche' la spaziatura puo' solo saltare di ottave.");

    val dallaLead: Boolean get() = struttura.isEmpty()

    /** Sostituzione del 5 col 13 sui dominanti, regola dei rootless. */
    fun sostituisci(grado: Int, q: ChordQuality): Int =
        if (quintaInTredicesima && grado == 5 && !q.minor &&
            (q == ChordQuality.DOM7 || q == ChordQuality.DOM7SUS4 ||
             q == ChordQuality.DOM7SHARP11 || q == ChordQuality.DOM7ALT)) 13 else grado

    /** Tutti i gradi che questo tipo puo' usare. */
    fun gradiAmmessi(q: ChordQuality): List<Int> =
        (if (dallaLead) ammessi else struttura).map { sostituisci(it, q) }

    /**
     * Il grado prescritto per la voce [i] contata dall'alto, o -1 se libero.
     * Le posizioni oltre la struttura la ripetono un'ottava sotto, che e' come
     * si allargano davvero questi voicing.
     */
    fun gradoDellaVoce(i: Int, voci: Int, q: ChordQuality): Int {
        if (dallaLead) return -1
        val dalBasso = voci - 1 - i
        return sostituisci(struttura[dalBasso % struttura.size], q)
    }
}


/** Nota piu' bassa concessa alle voci generate. */
val REGISTRI = intArrayOf(36, 41, 45, 48)
val NOMI_REGISTRO = arrayOf("C2", "F2", "A2", "C3")

/**
 * Cosa fare quando la nota che suoni non e' uno dei gradi previsti dal
 * voicing. Tutte e tre le risposte sono pratica standard.
 */
enum class ModoPassaggio(val etichetta: String, val descrizione: String) {
    TIENI("tieni", "Il voicing resta fermo e si muove solo la tua nota. E' quello " +
        "che fa una sezione sulle linee veloci, e muove zero."),
    RIVOICING("rivoicing", "Ricostruisce comunque dai gradi dell'accordo, evitando la " +
        "seconda minore sotto la lead. Per le note lunghe fuori accordo."),
    PLANING("planing", "Tutto il voicing si muove parallelo alla tua nota, anche fuori " +
        "dall'accordo. E' il suono soli da big band: bello, e l'unico che " +
        "sospende la regola dei gradi."),
    AUTO("auto", "Tiene le note brevi e rivoicizza quelle lunghe, con la soglia qui " +
        "sotto. Sotto soglia sei di passaggio, sopra ti stai fermando.")
}

val SOGLIE_PASSAGGIO = intArrayOf(60, 120, 250, 500)

data class VoicingCfg(
    var tipo: TipoVoicing = TipoVoicing.CLOSE,
    /** Massimo di note generate, non un numero fisso. */
    var voci: Int = 3,
    /** Indice in [APERTURE]. */
    var apertura: Int = 1,
    /** Indice in [REGISTRI]. */
    var registro: Int = 1,
    var passaggio: ModoPassaggio = ModoPassaggio.AUTO,
    /** Indice in [SOGLIE_PASSAGGIO], in millisecondi. */
    var soglia: Int = 1
) {
    /**
     * Il tipo comanda: voci e apertura si riportano dentro quello che quel
     * voicing regge. Vale anche per una configurazione salvata con un tipo
     * diverso, cosi' non resta mai una scelta impossibile.
     */
    fun normalizza() {
        voci = voci.coerceIn(tipo.vociMin, MAX_VOCI_VOICING)
        apertura = apertura.coerceIn(0, APERTURE.size - 1)
        registro = registro.coerceIn(0, REGISTRI.size - 1)
        soglia = soglia.coerceIn(0, SOGLIE_PASSAGGIO.size - 1)
    }

    fun notaMinima(): Int = REGISTRI[registro.coerceIn(0, REGISTRI.size - 1)]
    fun passo(): Int = APERTURE[apertura.coerceIn(0, APERTURE.size - 1)]
    fun sogliaMs(): Int = SOGLIE_PASSAGGIO[soglia.coerceIn(0, SOGLIE_PASSAGGIO.size - 1)]

    fun copia(): VoicingCfg = VoicingCfg(tipo, voci, apertura, registro, passaggio, soglia)
}

// ------------------------------------------------------ motore di piazzamento

/**
 * Piazza le voci sotto la lead. Spec §15.3.
 *
 * Le tre regole chieste — le voci si muovono il meno possibile, la distanza fra
 * le voci e' un parametro, le note escono dai gradi previsti dal tipo — non
 * sono tre algoritmi: sono tre **costi** sullo stesso problema, e si risolvono
 * insieme con una programmazione dinamica.
 *
 * Si assegna dall'alto verso il basso, ogni voce sotto la precedente. E' il
 * vincolo d'ordine a rendere il problema risolubile esattamente: niente
 * incroci, quindi l'assegnamento e' monotono e la DP `(voce × candidato)` trova
 * l'optimum, non un'approssimazione.
 *
 * Conseguenza che risolve un problema per costruzione: **due voci non possono
 * finire sulla stessa nota**, perche' l'ordine e' strettamente discendente. Se
 * i candidati non bastano suonano meno voci, che e' la semantica di "massimo"
 * chiesta. I duplicati restano possibili solo dopo i drop e il raddoppio della
 * lead, e quelli si scartano.
 *
 * Tutti gli array sono preallocati: nel percorso della nota non si alloca.
 */
class Voicer {

    private val CAND = 96
    private val cand = IntArray(CAND)
    private var nCand = 0
    private val gradoDiClasse = IntArray(12)

    private val costo = Array(MAX_VOCI_VOICING) { DoubleArray(CAND) }
    private val daDove = Array(MAX_VOCI_VOICING) { IntArray(CAND) }

    /** Esito per posizione strutturale: la nota, o -1 se quella voce tace. */
    val note = IntArray(MAX_VOCI_VOICING + 2)
    var quante = 0
        private set

    private val INF = 1e18
    private val PESO_SPAZIO = 0.6
    private val PENALITA_PARALLELE = 24.0
    private val PREFERENZA_STRETTO = 0.15

    // ---------------------------------------------------------- preparazione

    private fun preparaClassi(step: ChordStep, tipo: TipoVoicing) {
        for (i in 0 until 12) gradoDiClasse[i] = -1
        for (g in tipo.gradiAmmessi(step.chord.quality)) {
            val pc = GradiAccordo.classe(g, step.chord, step.scale) ?: continue
            if (gradoDiClasse[pc] < 0) gradoDiClasse[pc] = g
        }
    }

    /** La nota suonata non e' fra i gradi previsti dal voicing. */
    fun estranea(lead: Int, step: ChordStep, tipo: TipoVoicing): Boolean {
        preparaClassi(step, tipo)
        return gradoDiClasse[pitchClass(lead)] < 0
    }

    private fun preparaCandidati(lead: Int, minima: Int) {
        nCand = 0
        var n = minima.coerceAtLeast(0)
        while (n < lead && nCand < CAND) {
            if (gradoDiClasse[pitchClass(n)] >= 0) cand[nCand++] = n
            n++
        }
    }

    /**
     * Il passo bersaglio fra la voce [i] e quella sopra. Dove il tipo lo
     * prescrive vince il tipo; dove non lo prescrive decide l'apertura.
     */
    private fun passoBersaglio(tipo: TipoVoicing, cfg: VoicingCfg, i: Int, voci: Int): Int {
        val scostamento = APERTURE[cfg.apertura] - APERTURE[APERTURA_NEUTRA]
        if (tipo.bassoProfondo && i == voci - 1) return 12 + scostamento.coerceAtLeast(0)
        if (i == 0 && tipo.passoPrimo > 0) return (tipo.passoPrimo + scostamento).coerceAtLeast(1)
        if (tipo.passoFisso > 0) return (tipo.passoFisso + scostamento).coerceAtLeast(1)
        return cfg.passo()
    }

    /**
     * Quanto lontano cade questa nota dalla **piu' vicina** di quelle che
     * stanno suonando.
     *
     * Non dalla nota della stessa posizione strutturale: quello sembrava
     * naturale ed era sbagliato. Su `Dm7 -> G7` un rootless A passa da
     * `F A C E` a `B E F A`: sono quasi le stesse note, e un arrangiatore
     * tiene fermo quello che puo' e muove solo la `C` sulla `B`. Legando le
     * voci alla posizione, invece, il 9 di prima deve diventare il 9 di dopo e
     * si sposta di una quinta senza motivo. Le voci suonano tutte sulla stessa
     * parte: quello che l'orecchio sente e' l'insieme, non chi tiene cosa.
     *
     * Oltre due ottave la distanza non discrimina piu' nulla e si satura,
     * altrimenti dominerebbe la spaziatura.
     */
    private fun costoMovimento(nota: Int, precedenti: IntArray): Double {
        var best = 99.0
        for (p in precedenti) {
            if (p < 0) continue
            val d = Math.abs(nota - p).toDouble()
            if (d < best) best = d
        }
        if (best > 24.0) return 24.0
        return if (best > 90.0) 0.0 else best
    }

    /**
     * Scostamento dal passo bersaglio, con una preferenza per il **piu'
     * stretto**: a pari errore, un voicing raccolto sotto la lead suona meglio
     * di uno spalancato, e senza questa asimmetria i pareggi si risolvevano a
     * caso — la quartal prendeva una quarta dove voleva una terza.
     */
    private fun costoSpazio(d: Int, bersaglio: Int): Double {
        val err = Math.abs(d - bersaglio).toDouble()
        return if (d > bersaglio) err * (1.0 + PREFERENZA_STRETTO) else err
    }

    private fun spostamento(tipo: TipoVoicing, i: Int): Int =
        if (tipo.drop.contains(i + 2)) 12 else 0

    /**
     * Quinte e ottave parallele fra due voci adiacenti. Una nota tenuta non e'
     * moto, quindi non e' parallelismo: e' il caso piu' frequente e non va
     * penalizzato.
     */
    private fun parallele(
        sopraPrima: Int, sopraOra: Int, sottoPrima: Int, sottoOra: Int
    ): Double {
        if (sopraPrima < 0 || sottoPrima < 0) return 0.0
        val prima = sopraPrima - sottoPrima
        val ora = sopraOra - sottoOra
        if (prima != ora) return 0.0
        if (sopraOra == sopraPrima) return 0.0
        val cl = ((ora % 12) + 12) % 12
        return if (cl == 7 || cl == 0) PENALITA_PARALLELE else 0.0
    }

    // ------------------------------------------------------------------ la DP

    /**
     * @param precedenti nota in suono per ogni posizione, -1 se tace.
     * @param leadPrecedente serve alle parallele col soprano.
     * @return quante posizioni sono state riempite.
     */
    fun costruisci(
        lead: Int,
        step: ChordStep,
        cfg: VoicingCfg,
        precedenti: IntArray,
        leadPrecedente: Int
    ): Int {
        quante = 0
        cfg.normalizza()
        val tipo = cfg.tipo
        val q = step.chord.quality
        val minima = cfg.notaMinima()

        preparaClassi(step, tipo)
        preparaCandidati(lead, minima)
        if (nCand == 0) return 0

        val voci = cfg.voci.coerceIn(1, MAX_VOCI_VOICING)
        // una seconda minore sotto la lead impasta, tranne nei cluster dove e'
        // proprio il colore che si cerca
        val distMinLead = if (tipo == TipoVoicing.CLUSTER) 1 else 2

        for (i in 0 until voci) {
            val grado = tipo.gradoDellaVoce(i, voci, q)
            val classe = if (grado < 0) -1
                         else GradiAccordo.classe(grado, step.chord, step.scale) ?: -1
            val passo = passoBersaglio(tipo, cfg, i, voci)
            val spost = spostamento(tipo, i)
            val spostSopra = if (i > 0) spostamento(tipo, i - 1) else 0
            // per le parallele serve *chi* stava dove, quindi la posizione conta
            val prec = if (i < precedenti.size) precedenti[i] else -1
            val precSopra = if (i > 0) {
                if (i - 1 < precedenti.size) precedenti[i - 1] else -1
            } else leadPrecedente

            for (j in 0 until nCand) {
                costo[i][j] = INF
                daDove[i][j] = -1

                if (classe >= 0 && pitchClass(cand[j]) != classe) continue
                val finale = cand[j] - spost
                if (finale < minima || lead - finale > AMPIEZZA_MAX) continue

                val mov = costoMovimento(finale, precedenti)

                if (i == 0) {
                    if (lead - cand[j] < distMinLead) continue
                    var c = mov + PESO_SPAZIO * costoSpazio(lead - cand[j], passo)
                    if (tipo.evitaParallele)
                        c += parallele(leadPrecedente, lead, prec, finale)
                    costo[i][j] = c
                } else {
                    var best = INF
                    var bj = -1
                    for (jp in j + 1 until nCand) {
                        val prima = costo[i - 1][jp]
                        if (prima >= INF) continue
                        val sopraFinale = cand[jp] - spostSopra
                        var c = prima + mov +
                                PESO_SPAZIO * costoSpazio(cand[jp] - cand[j], passo)
                        if (tipo.evitaParallele)
                            c += parallele(precSopra, sopraFinale, prec, finale)
                        if (c < best) { best = c; bj = jp }
                    }
                    if (bj >= 0) { costo[i][j] = best; daDove[i][j] = bj }
                }
            }
        }

        // quante voci si riescono a piazzare: si scende finche' ce n'e' una
        for (k in voci downTo 1) {
            var migliore = INF
            var mj = -1
            // dall'alto verso il basso: a pari costo vince la voce piu' acuta,
            // cioe' il voicing piu' raccolto
            for (j in nCand - 1 downTo 0) if (costo[k - 1][j] < migliore) {
                migliore = costo[k - 1][j]; mj = j
            }
            if (mj >= 0) { ricostruisci(k, mj, tipo); break }
        }
        if (quante == 0) return 0

        // il raddoppio della lead e' una nota in piu', non un raddoppio di volume
        if (tipo.raddoppiaLead && lead - 12 >= minima && quante < note.size)
            note[quante++] = lead - 12

        scartaDuplicati()
        return quante
    }

    private fun ricostruisci(k: Int, ultimo: Int, tipo: TipoVoicing) {
        var j = ultimo
        for (i in k - 1 downTo 0) {
            note[i] = cand[j] - spostamento(tipo, i)
            j = daDove[i][j]
            if (j < 0 && i > 0) { quante = 0; return }
        }
        quante = k
    }

    /** Dopo i drop e il raddoppio due posizioni possono coincidere: una tace. */
    private fun scartaDuplicati() {
        for (i in 1 until quante) {
            if (note[i] < 0) continue
            for (k in 0 until i) if (note[k] == note[i]) { note[i] = -1; break }
        }
    }

    /** Le note piazzate, col loro grado. Per il monitor e per i banchi. */
    fun descrizione(step: ChordStep): String {
        val sb = StringBuilder()
        for (i in 0 until quante) {
            if (note[i] < 0) { sb.append("— "); continue }
            val g = gradoDiClasse[pitchClass(note[i])]
            sb.append(Spelling.note(note[i], step.chord))
            if (g > 0) sb.append("(").append(GradiAccordo.nome(g)).append(")")
            sb.append(" ")
        }
        return sb.toString().trim()
    }
}
