package harmonizer.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import harmonizer.core.*
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vista degli effetti. Spec §14.
 *
 * Sta **a destra** della principale, speculare alla scaletta che sta a
 * sinistra: si raggiunge trascinando il dito verso sinistra, o dal pulsante
 * «fx». In cima il selettore dell'effetto, sotto i parametri di quello scelto.
 *
 * Le regolazioni si applicano subito e si salvano nel brano a ogni tocco: non
 * c'e' un «conferma», perche' qui si regola mentre si prova a suonare.
 *
 * I pannelli si costruiscono **una volta sola**. Toccare un chip ridipinge i
 * chip di quella griglia e nient'altro: ricostruire il pannello riportava lo
 * scorrimento in cima a ogni tocco.
 */
class FxView(private val act: Activity) {

    var tipo: FxTipo = FxTipo.HARMONIZER
    var harmCfg: HarmonizerCfg = HarmonizerCfg()
    var arpCfg: ArpCfg = ArpCfg()
    var voicingCfg: VoicingCfg = VoicingCfg()
    var nomeBrano: String = ""

    /** Chiamata a ogni modifica: il chiamante salva e applica al runtime. */
    var onCambia: (() -> Unit)? = null

    private lateinit var pannello: LinearLayout
    private var grigliaEffetti: Griglia? = null

    // riferimenti del pannello armonizzatore, aggiornati in loco
    private var slitter: SlitterGradi? = null
    private var lblGradi: TextView? = null
    private var lblSpiegazione: TextView? = null

    // riferimenti del pannello voicing
    private var lblTipoVoicing: TextView? = null
    private var lblPassaggio: TextView? = null

    private val d get() = act.resources.displayMetrics

    fun build(): View {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            setPadding((18 * d.density).toInt(), (22 * d.density).toInt(),
                       (18 * d.density).toInt(), (14 * d.density).toInt())
        }

        root.addView(TextView(act).apply {
            text = nomeBrano
            setTextColor(Pal.acc); textSize = 13f; gravity = Gravity.CENTER
        })
        root.addView(etichetta("EFFETTO — uno alla volta, tutti memorizzati"))

        val tipi = FxTipo.values()
        grigliaEffetti = Griglia(tipi.map { it.etichetta }, tipi.size, tipi.indexOf(tipo),
                                 grande = true) { i ->
            if (tipo != tipi[i]) {
                tipo = tipi[i]
                aggiornaPannello()
                onCambia?.invoke()
            }
        }
        root.addView(grigliaEffetti!!.vista, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        pannello = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pannello, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        aggiornaPannello()
        return root
    }

    /** L'unico ricambio di viste che resta: cambiare effetto cambia pannello. */
    private fun aggiornaPannello() {
        pannello.removeAllViews()
        slitter = null; lblGradi = null; lblSpiegazione = null
        lblTipoVoicing = null; lblPassaggio = null
        when (tipo) {
            FxTipo.HARMONIZER -> pannelloHarmonizer()
            FxTipo.ARPEGGIATOR -> pannelloArpeggiatore()
            FxTipo.VOICING -> pannelloVoicing()
        }
    }

    // ---------------------------------------------------------- harmonizer

    private fun pannelloHarmonizer() {
        harmCfg.normalizza()

        pannello.addView(etichetta("VOCI DI ARMONIA — si aggiungono alla melodia"))
        pannello.addView(Griglia((1..MAX_VOCI).map { "$it" }, MAX_VOCI, harmCfg.voci - 1) { i ->
            harmCfg.voci = i + 1
            configuraSlitter()
            onCambia?.invoke()
        }.vista)

        pannello.addView(etichetta("MOVIMENTO DELLE VOCI"))
        pannello.addView(Griglia(MODI_HARM.map { it.second }, 2,
            MODI_HARM.indexOfFirst { it.first == harmCfg.modo }.coerceAtLeast(0)) { i ->
            harmCfg.modo = MODI_HARM[i].first
            configuraSlitter()
            onCambia?.invoke()
        }.vista)

        lblSpiegazione = TextView(act).apply {
            setTextColor(Pal.dim); textSize = 12f
            setPadding(4, 0, 4, (6 * d.density).toInt())
        }
        pannello.addView(lblSpiegazione)

        lblGradi = etichetta("")
        pannello.addView(lblGradi)

        slitter = SlitterGradi(act).apply {
            // la spiegazione si aggiorna anche mentre si trascina: l'unisono
            // saltato compare quando l'intervallo lo scavalca, non al tocco
            // successivo su un chip
            onMuove = { aggiornaSpiegazione() }
        }
        pannello.addView(slitter, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = (4 * d.density).toInt()
        })

        configuraSlitter()
    }

    /**
     * Il selettore verticale serve due domande diverse, quindi cambia forma:
     *
     * - a **grado fisso** una maniglia per voce: ciascuna tiene il suo grado;
     * - negli **altri modi** due maniglie, `da` e `a`: sono gli estremi
     *   dell'intervallo entro cui *tutte* le voci si muovono. Quante sono le
     *   voci non c'entra — con una voce sola e un insieme di un elemento non
     *   ci sarebbe nessun movimento da fare.
     */
    private fun configuraSlitter() {
        val s = slitter ?: return
        val n = harmCfg.voci.coerceIn(1, MAX_VOCI)
        s.etichette = List(n) { "${it + 1}" }

        if (harmCfg.modo == SelectionMode.FIXED) {
            s.modo = SlitterGradi.Modo.PUNTO
            s.punti = MutableList(n) { harmCfg.gradi[it] }
            s.onCambia = {
                for (i in s.punti.indices) harmCfg.gradi[i] = s.punti[i]
                onCambia?.invoke()
            }
            lblGradi?.text = if (n == 1) "GRADO DELLA VOCE DI ARMONIA"
                             else "GRADO DI OGNI VOCE — una maniglia per voce"
        } else {
            s.modo = SlitterGradi.Modo.INTERVALLO
            s.minimi = MutableList(n) { harmCfg.estremoBasso(it) }
            s.massimi = MutableList(n) { harmCfg.estremoAlto(it) }
            s.onCambia = {
                for (i in s.minimi.indices) {
                    harmCfg.gradiMin[i] = s.minimi[i]
                    harmCfg.gradiMax[i] = s.massimi[i]
                }
                onCambia?.invoke()
            }
            lblGradi?.text = if (n == 1)
                "INTERVALLO DELLA VOCE — trascina gli estremi, o la barra per spostarla"
            else "INTERVALLO DI OGNI VOCE — una barra per voce: estremi o barra intera"
        }

        aggiornaSpiegazione()
        s.invalidate()
    }

    /**
     * Il testo sotto la striscia dei modi. Si rilegge dal selettore, non dalla
     * configurazione, perche' durante il trascinamento la configurazione non e'
     * ancora stata scritta.
     */
    private fun aggiornaSpiegazione() {
        val spiega = MODI_HARM.firstOrNull { it.first == harmCfg.modo }?.third ?: ""
        val s = slitter
        val salta = harmCfg.modo != SelectionMode.FIXED && s != null &&
            s.minimi.indices.any { s.minimi[it] != s.massimi[it] &&
                                   0 in minOf(s.minimi[it], s.massimi[it])..maxOf(s.minimi[it], s.massimi[it]) }
        lblSpiegazione?.text =
            if (salta) "$spiega L'unisono dentro l'intervallo viene saltato."
            else spiega
    }

    // -------------------------------------------------------- arpeggiatore

    private fun pannelloArpeggiatore() {
        arpCfg.normalizza()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        col.addView(etichetta("DIREZIONE"))
        col.addView(Griglia(ArpPattern.values().map { it.etichetta }, 3,
            ArpPattern.values().indexOf(arpCfg.pattern)) { i ->
            arpCfg.pattern = ArpPattern.values()[i]; onCambia?.invoke()
        }.vista)

        col.addView(etichetta("ARTICOLAZIONE — suddivisioni e gruppi irregolari"))
        col.addView(Griglia(Suddivisioni.NOMI.toList(), 3,
            Suddivisioni.indiceDi(arpCfg.notePerQuarto)) { i ->
            arpCfg.notePerQuarto = Suddivisioni.VALORI[i]; onCambia?.invoke()
        }.vista)

        col.addView(etichetta("ESTENSIONE"))
        col.addView(Griglia(OTTAVE_AMMESSE.map { if (it == 1) "1 ottava" else "$it ottave" }, 4,
            OTTAVE_AMMESSE.indexOf(arpCfg.ottave).coerceAtLeast(0)) { i ->
            arpCfg.ottave = OTTAVE_AMMESSE[i]; onCambia?.invoke()
        }.vista)

        col.addView(etichetta("NOTE — l'arpeggio sale su queste"))
        col.addView(Griglia(listOf("note dell'accordo", "tutta la scala"), 2,
            if (arpCfg.soloAccordo) 0 else 1) { i ->
            arpCfg.soloAccordo = i == 0; onCambia?.invoke()
        }.vista)

        col.addView(etichetta("GATE — durata della nota nello step"))
        col.addView(Griglia(GATE_AMMESSI.map { "$it%" }, 4,
            indicePiuVicino(GATE_AMMESSI, arpCfg.gate)) { i ->
            arpCfg.gate = GATE_AMMESSI[i]; onCambia?.invoke()
        }.vista)

        col.addView(etichetta("SWING — ritardo degli step dispari"))
        col.addView(Griglia(SWING_AMMESSI.map { if (it == 0) "diritto" else "$it%" }, 3,
            indicePiuVicino(SWING_AMMESSI, arpCfg.swing)) { i ->
            arpCfg.swing = SWING_AMMESSI[i]; onCambia?.invoke()
        }.vista)

        col.addView(TextView(act).apply {
            text = "L'arpeggio parte dalla nota che suoni e sale sulle note del passo " +
                   "corrente della progressione: segue l'armonia da solo. Sta sulla " +
                   "griglia del transport, quindi BATT. 1 lo riallinea, STOP lo ferma, " +
                   "e FX lo silenzia lasciando scorrere la progressione. A transport " +
                   "fermo tace: la melodia passa comunque."
            setTextColor(Pal.dim); textSize = 12f
            setPadding(4, (18 * d.density).toInt(), 4, 0)
        })

        pannello.addView(ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    // ------------------------------------------------------------- voicing

    private fun pannelloVoicing() {
        voicingCfg.normalizza()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        col.addView(etichetta("VOCI — massimo, non un numero fisso. Tu sei la lead, sopra"))
        col.addView(Griglia((1..MAX_VOCI_VOICING).map { "$it" }, 4, voicingCfg.voci - 1) { i ->
            voicingCfg.voci = i + 1
            onCambia?.invoke()
        }.vista)

        col.addView(etichetta("TIPO DI VOICING"))
        val tipi = TipoVoicing.values()
        col.addView(Griglia(tipi.map { it.etichetta }, 2,
            tipi.indexOf(voicingCfg.tipo)) { i ->
            voicingCfg.tipo = tipi[i]
            aggiornaTipoVoicing()
            onCambia?.invoke()
        }.vista)

        lblTipoVoicing = TextView(act).apply {
            setTextColor(Pal.dim); textSize = 12f
            setPadding(4, 0, 4, (6 * d.density).toInt())
        }
        col.addView(lblTipoVoicing)

        col.addView(etichetta("APERTURA — distanza fra le voci"))
        col.addView(Griglia(NOMI_APERTURA.toList(), 3, voicingCfg.apertura) { i ->
            voicingCfg.apertura = i
            aggiornaTipoVoicing()
            onCambia?.invoke()
        }.vista)

        col.addView(etichetta("REGISTRO — nota piu' bassa concessa"))
        col.addView(Griglia(NOMI_REGISTRO.toList(), 4, voicingCfg.registro) { i ->
            voicingCfg.registro = i
            onCambia?.invoke()
        }.vista)

        col.addView(etichetta("QUANDO LA TUA NOTA NON E' NELL'ACCORDO"))
        val modi = ModoPassaggio.values()
        col.addView(Griglia(modi.map { it.etichetta }, 2,
            modi.indexOf(voicingCfg.passaggio)) { i ->
            voicingCfg.passaggio = modi[i]
            aggiornaPassaggio()
            onCambia?.invoke()
        }.vista)

        lblPassaggio = TextView(act).apply {
            setTextColor(Pal.dim); textSize = 12f
            setPadding(4, 0, 4, (6 * d.density).toInt())
        }
        col.addView(lblPassaggio)

        col.addView(etichetta("SOGLIA — sotto tieni, sopra rivoicizza"))
        col.addView(Griglia(SOGLIE_PASSAGGIO.map { "$it ms" }, 4, voicingCfg.soglia) { i ->
            voicingCfg.soglia = i
            onCambia?.invoke()
        }.vista)

        col.addView(TextView(act).apply {
            text = "Le voci escono dai gradi dell'accordo previsti dal tipo, stanno " +
                   "sempre sotto la tua nota e seguono il tuo fiato. Due voci non " +
                   "possono finire sulla stessa nota: se non c'e' posto suonano meno " +
                   "note. Oltre 4 voci le parti vanno in POLY — piu' note sulla " +
                   "stessa parte — e le voci riattaccano invece di legare."
            setTextColor(Pal.dim); textSize = 12f
            setPadding(4, (18 * d.density).toInt(), 4, 0)
        })

        pannello.addView(ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        aggiornaTipoVoicing()
        aggiornaPassaggio()
    }

    private fun aggiornaTipoVoicing() {
        val t = voicingCfg.tipo
        val prescrive = t.passoFisso > 0 || t.passoPrimo > 0
        lblTipoVoicing?.text = t.descrizione +
            "  Tipiche: ${t.vociTipiche} voci." +
            (if (prescrive) " La distanza fra le voci la decide il tipo; l'apertura " +
                            "la apre o la chiude a partire da li'." else "")
    }

    private fun aggiornaPassaggio() {
        lblPassaggio?.text = voicingCfg.passaggio.descrizione
    }

    private fun indicePiuVicino(valori: IntArray, v: Int): Int {
        var best = 0
        for (i in valori.indices)
            if (Math.abs(valori[i] - v) < Math.abs(valori[best] - v)) best = i
        return best
    }

    // ------------------------------------------------------------- mattoni

    private fun etichetta(t: String) = TextView(act).apply {
        text = t; setTextColor(Pal.dim); textSize = 10f
        letterSpacing = 0.12f
        setPadding(4, (16 * d.density).toInt(), 0, (4 * d.density).toInt())
    }

    /**
     * Chip a scelta unica su piu' righe. Non si scorre: cosi' tutte le scelte
     * sono visibili insieme — su un leggio non si cerca un elenco nascosto — e
     * il gesto orizzontale resta a disposizione del cambio di vista.
     *
     * La selezione si ridipinge **in loco**: le viste non si ricostruiscono,
     * altrimenti un pannello scorrevole tornerebbe in cima a ogni tocco.
     */
    private inner class Griglia(
        etichette: List<String>,
        perRiga: Int,
        private var selezionato: Int,
        private val grande: Boolean = false,
        private val onScegli: (Int) -> Unit
    ) {
        val vista: LinearLayout
        private val chip = ArrayList<TextView>(etichette.size)

        init {
            vista = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val gap = (8 * d.density).toInt()
            var i = 0
            while (i < etichette.size) {
                val riga = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0 until perRiga) {
                    val idx = i + k
                    if (idx >= etichette.size) {
                        // riempitivo: i chip dell'ultima riga restano larghi come gli altri
                        riga.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                    } else {
                        val c = creaChip(etichette[idx], idx)
                        chip.add(c)
                        riga.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    }
                    if (k < perRiga - 1) (riga.getChildAt(riga.childCount - 1)
                        .layoutParams as LinearLayout.LayoutParams).rightMargin = gap
                }
                vista.addView(riga, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .apply { bottomMargin = gap })
                i += perRiga
            }
        }

        private fun creaChip(testo: String, idx: Int) = TextView(act).apply {
            text = testo
            textSize = if (grande) 14f else 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            val v = ((if (grande) 18 else 14) * d.density).toInt()
            setPadding(8, v, 8, v)
            stile(this, idx == selezionato)
            setOnClickListener {
                if (selezionato != idx) {
                    selezionato = idx
                    for ((k, c) in chip.withIndex()) stile(c, k == selezionato)
                }
                onScegli(idx)
            }
        }

        private fun stile(c: TextView, attivo: Boolean) {
            c.setTextColor(if (attivo) Pal.bg else Pal.fg)
            c.background = GradientDrawable().apply {
                cornerRadius = 10f
                setColor(if (attivo) Pal.acc else Pal.surf2)
                if (!attivo) setStroke(2, Pal.rule)
            }
        }
    }
}

/**
 * Selettore verticale di gradi: una colonna per voce, dall'ottava sotto
 * all'ottava sopra.
 *
 * Verticale perche' e' un'altezza: in alto suona in alto. Una colonna per voce,
 * cosi' due voci sullo stesso grado non si nascondono a vicenda e si vede
 * l'accordo che si sta impilando.
 *
 * Due forme, perche' le due domande sono diverse:
 *
 * - [Modo.PUNTO] — una maniglia per voce: il grado fisso di quella voce;
 * - [Modo.INTERVALLO] — una **barra** per voce: gli estremi entro cui quella
 *   voce puo' muoversi. Si trascinano i due capi, o la barra intera per
 *   spostare l'intervallo senza cambiarne la larghezza.
 *
 * Il gesto si prende **solo se il dito parte vicino a una maniglia** o dentro
 * una barra. Altrove il tocco non viene consumato e passa alla vista sotto,
 * che lo usa per cambiare schermata: prima un qualunque sfioramento del
 * tracciato spostava un valore, e uno scorrimento laterale cambiava
 * l'armonia invece di cambiare vista.
 */
class SlitterGradi(ctx: Context) : View(ctx) {

    enum class Modo { PUNTO, INTERVALLO }

    var modo: Modo = Modo.PUNTO
        set(v) { field = v; presa = Presa.NESSUNA; invalidate() }

    /** Un grado per colonna. Usato in [Modo.PUNTO]. */
    var punti: MutableList<Int> = mutableListOf(Degrees.THIRD)
        set(v) { field = v; presa = Presa.NESSUNA; invalidate() }

    /** Estremi per colonna. Usati in [Modo.INTERVALLO]. */
    var minimi: MutableList<Int> = mutableListOf(Degrees.SECOND)
        set(v) { field = v; presa = Presa.NESSUNA; invalidate() }
    var massimi: MutableList<Int> = mutableListOf(Degrees.THIRD)
        set(v) { field = v; presa = Presa.NESSUNA; invalidate() }

    /** Testo in cima a ogni colonna: il numero della voce. */
    var etichette: List<String> = listOf("1")

    /** A gesto concluso: salva e applica. */
    var onCambia: (() -> Unit)? = null

    /** A ogni valore cambiato, anche durante il trascinamento. */
    var onMuove: (() -> Unit)? = null

    private enum class Presa { NESSUNA, PUNTO, BASSO, ALTO, CORPO }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rr = RectF()
    private var presa = Presa.NESSUNA
    private var colonnaAttiva = -1
    private var gradoIniziale = 0
    private var bassoIniziale = 0
    private var altoIniziale = 0

    private val righe = GRADO_MAX - GRADO_MIN + 1
    private val bandaColore = Color.parseColor("#1E3B3A")

    private fun colonne() = if (modo == Modo.PUNTO) punti.size else minimi.size

    private fun xEtichette() = width * 0.22f
    private fun larghezzaColonna() = (width - xEtichette()) / colonne().coerceAtLeast(1)
    private fun altezzaIntestazione() = min(height * 0.06f, 26f * resources.displayMetrics.density)
    private fun altezzaRiga() = (height - altezzaIntestazione()) / righe
    private fun yDi(grado: Int) =
        altezzaIntestazione() + (GRADO_MAX - grado + 0.5f) * altezzaRiga()
    private fun gradoDi(y: Float): Int =
        (GRADO_MAX - ((y - altezzaIntestazione()) / altezzaRiga() - 0.5f).roundToInt())
            .coerceIn(GRADO_MIN, GRADO_MAX)

    private fun basso(c: Int) = min(minimi[c], massimi[c])
    private fun alto(c: Int) = kotlin.math.max(minimi[c], massimi[c])

    // ------------------------------------------------------------ disegno

    override fun onDraw(c: Canvas) {
        val hr = altezzaRiga()
        val x0 = xEtichette()
        val cw = larghezzaColonna()
        val hi = altezzaIntestazione()

        p.style = Paint.Style.FILL
        p.color = Pal.surf
        rr.set(x0, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rr, 14f, 14f, p)

        // intestazione: il numero della voce sopra la sua colonna
        p.textAlign = Paint.Align.CENTER
        p.textSize = min(hi * 0.62f, 30f)
        p.typeface = Typeface.DEFAULT_BOLD
        for (i in 0 until colonne()) {
            p.color = if (i == colonnaAttiva) Pal.fg else Pal.dim
            c.drawText(etichette.getOrNull(i) ?: "${i + 1}",
                       x0 + i * cw + cw / 2, hi * 0.72f, p)
        }

        // le barre degli intervalli, dietro le righe
        if (modo == Modo.INTERVALLO) {
            for (i in 0 until colonne()) {
                val cx0 = x0 + i * cw
                p.style = Paint.Style.FILL
                p.color = bandaColore
                rr.set(cx0 + 5f, yDi(alto(i)) - hr * 0.5f,
                       cx0 + cw - 5f, yDi(basso(i)) + hr * 0.5f)
                c.drawRoundRect(rr, hr * 0.34f, hr * 0.34f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                p.color = if (i == colonnaAttiva) Pal.fg else Pal.acc
                c.drawRoundRect(rr, hr * 0.34f, hr * 0.34f, p)
            }
        }

        for (g in GRADO_MAX downTo GRADO_MIN) {
            val y = yDi(g)
            val dentro = (0 until colonne()).any {
                if (modo == Modo.PUNTO) punti[it] == g else g in basso(it)..alto(it)
            }
            val saltato = modo == Modo.INTERVALLO && g == 0 &&
                (0 until colonne()).any { g in basso(it)..alto(it) && basso(it) != alto(it) }

            p.color = if (g == 0) Pal.rule else Pal.surf2
            p.strokeWidth = if (g == 0) 3f else 1.5f
            p.style = Paint.Style.STROKE
            c.drawLine(x0 + 8f, y, width.toFloat() - 8f, y, p)

            p.style = Paint.Style.FILL
            p.color = when {
                saltato -> Pal.rule
                dentro -> Pal.acc
                else -> Pal.dim
            }
            p.textAlign = Paint.Align.RIGHT
            p.textSize = min(hr * 0.52f, 34f)
            p.typeface = if (dentro && !saltato) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.drawText(gradoBreve(g), x0 - 10f, y + p.textSize * 0.36f, p)
        }

        // maniglie
        for (i in 0 until colonne()) {
            val cx0 = x0 + i * cw
            val attiva = i == colonnaAttiva
            if (modo == Modo.PUNTO) {
                val y = yDi(punti[i])
                val h = hr * 0.86f
                rr.set(cx0 + 5f, y - h / 2, cx0 + cw - 5f, y + h / 2)
                p.style = Paint.Style.FILL
                p.color = if (attiva) Pal.fg else Pal.acc
                c.drawRoundRect(rr, h * 0.34f, h * 0.34f, p)
                p.color = Pal.bg
                p.textAlign = Paint.Align.CENTER
                p.textSize = min(h * 0.58f, cw * 0.42f)
                p.typeface = Typeface.DEFAULT_BOLD
                c.drawText(etichette.getOrNull(i) ?: "${i + 1}", cx0 + cw / 2,
                           y + p.textSize * 0.35f, p)
            } else {
                // i due capi: sono le prese, quindi si devono vedere come tali
                p.style = Paint.Style.FILL
                p.color = if (attiva) Pal.fg else Pal.acc
                for (g in intArrayOf(alto(i), basso(i))) {
                    val y = yDi(g)
                    val h = hr * 0.40f
                    rr.set(cx0 + 9f, y - h / 2, cx0 + cw - 9f, y + h / 2)
                    c.drawRoundRect(rr, h / 2, h / 2, p)
                }
            }
        }
    }

    // -------------------------------------------------------------- tocco

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val col = colonnaDi(e.x)
                if (col < 0) return false
                val pr = presaVicina(col, e.y)
                if (pr == Presa.NESSUNA) return false      // il gesto non e' nostro
                parent?.requestDisallowInterceptTouchEvent(true)
                presa = pr
                colonnaAttiva = col
                gradoIniziale = gradoDi(e.y)
                if (modo == Modo.INTERVALLO) {
                    bassoIniziale = basso(col); altoIniziale = alto(col)
                }
                muovi(e.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (presa == Presa.NESSUNA) return false
                muovi(e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (presa == Presa.NESSUNA) return false
                presa = Presa.NESSUNA
                colonnaAttiva = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onCambia?.invoke()
                return true
            }
        }
        return false
    }

    /** -1 se il dito e' sulla colonna delle etichette: la' non tocca a noi. */
    private fun colonnaDi(x: Float): Int {
        val cw = larghezzaColonna()
        if (cw <= 0f || x < xEtichette()) return -1
        return ((x - xEtichette()) / cw).toInt().coerceIn(0, colonne() - 1)
    }

    /**
     * Cosa c'e' sotto il dito. La tolleranza e' quasi una riga: piu' larga
     * prenderebbe tocchi che non intendevano prendere niente, piu' stretta
     * renderebbe difficile agganciare una maniglia alta pochi millimetri.
     */
    private fun presaVicina(col: Int, y: Float): Presa {
        val toll = altezzaRiga() * 0.85f
        if (modo == Modo.PUNTO) {
            return if (kotlin.math.abs(y - yDi(punti[col])) <= toll) Presa.PUNTO
                   else Presa.NESSUNA
        }
        val yAlto = yDi(alto(col))
        val yBasso = yDi(basso(col))
        val dAlto = kotlin.math.abs(y - yAlto)
        val dBasso = kotlin.math.abs(y - yBasso)
        if (min(dAlto, dBasso) <= toll)
            return if (dAlto <= dBasso) Presa.ALTO else Presa.BASSO
        return if (y in yAlto..yBasso) Presa.CORPO else Presa.NESSUNA
    }

    private fun muovi(y: Float) {
        val i = colonnaAttiva
        if (i < 0) return
        val g = gradoDi(y)
        val prima: Int
        when (presa) {
            Presa.PUNTO -> {
                if (i >= punti.size) return
                prima = punti[i]
                punti[i] = g
            }
            Presa.ALTO -> {
                if (i >= massimi.size) return
                prima = massimi[i]
                // i capi non si scavalcano: un intervallo rovesciato non
                // significherebbe niente e il trascinamento si incasserebbe
                massimi[i] = g.coerceAtLeast(minimi[i])
            }
            Presa.BASSO -> {
                if (i >= minimi.size) return
                prima = minimi[i]
                minimi[i] = g.coerceAtMost(massimi[i])
            }
            Presa.CORPO -> {
                if (i >= minimi.size) return
                prima = minimi[i]
                val d = (g - gradoIniziale)
                    .coerceAtLeast(GRADO_MIN - bassoIniziale)
                    .coerceAtMost(GRADO_MAX - altoIniziale)
                minimi[i] = bassoIniziale + d
                massimi[i] = altoIniziale + d
            }
            Presa.NESSUNA -> return
        }
        val ora = if (presa == Presa.PUNTO) punti[i]
                  else if (presa == Presa.ALTO) massimi[i] else minimi[i]
        if (ora != prima) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onMuove?.invoke()
        }
        invalidate()
    }
}
