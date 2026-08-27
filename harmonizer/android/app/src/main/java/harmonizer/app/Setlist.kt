package harmonizer.app

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import harmonizer.core.*

/**
 * Scena dell'AE-20, richiamata via Bank Select + Program Change.
 *
 * Dalla MIDI Implementation:
 *   MSB 085 + LSB 000-011 + PC 001-050  ->  banchi utente U01-U12
 *   MSB 085 + LSB 064-075 + PC 001-...  ->  categorie preset P01-P12
 */
data class ScenaAE20(val utente: Boolean, val banco: Int, val numero: Int) {
    fun lsb(): Int = if (utente) banco.coerceIn(0, 11) else 64 + banco.coerceIn(0, 11)
    fun etichetta(): String =
        (if (utente) "U%02d-%02d" else "P%02d-%02d").format(banco + 1, numero)
    companion object { const val MSB = 85 }
}

data class Brano(
    var nome: String,
    var progressione: Progression,
    var bpm: Double,
    var scena: ScenaAE20?,
    /** Movimenti per battuta: 3, 4 o 5. */
    var battiti: Int = BATTITI_DEFAULT,
    /**
     * L'effetto attivo e le configurazioni di **tutti** gli effetti: si
     * memorizzano entrambe, anche quella dell'effetto spento, cosi'
     * passare da uno all'altro non perde le regolazioni. Spec §14.
     */
    var fx: FxTipo = FxTipo.HARMONIZER,
    var harmCfg: HarmonizerCfg = HarmonizerCfg(),
    var arpCfg: ArpCfg = ArpCfg()
)

/** Scaletta con salvataggio nelle preferenze: niente librerie esterne. */
class Setlist(val brani: MutableList<Brano> = mutableListOf()) {

    companion object {
        private const val PREF = "aeroharm"
        private const val KEY = "setlist"

        fun carica(ctx: Context): Setlist {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            // v2: durate in quarti di battuta. I dati piu' vecchi si scartano.
            // v4: nuova progressione predefinita.
            // v5: configurazione degli effetti per brano.
            return if (s.isNullOrBlank() || !s.startsWith("v5\n")) predefinita()
                   else deserializza(s.removePrefix("v5\n"))
        }

        fun predefinita(): Setlist = Setlist(mutableListOf(
            Brano("IV-II-V-I in Do", progressioneDefault(), 120.0, null)
        ))

        private fun deserializza(s: String): Setlist {
            val out = mutableListOf<Brano>()
            for (riga in s.split("\n")) {
                if (riga.isBlank()) continue
                try {
                    val campi = riga.split("¦")
                    val nome = campi[0]
                    val bpm = campi[1].toDouble()
                    val batt = campi.getOrNull(4)?.toIntOrNull() ?: BATTITI_DEFAULT
                    val scena = if (campi[2].isEmpty()) null else campi[2].split(",").let {
                        ScenaAE20(it[0] == "u", it[1].toInt(), it[2].toInt())
                    }
                    val passi = campi[3].split(";").filter { it.isNotBlank() }.map { p ->
                        val q = p.split(",")
                        ChordStep(
                            Chord(q[0].toInt(), ChordQuality.valueOf(q[1])),
                            Scales.ALL.firstOrNull { sc -> sc.id == q[2] } ?: Scales.IONICA,
                            q[3].toInt()
                        )
                    }
                    val fx = FxTipo.da(campi.getOrNull(5) ?: "h")
                    val hc = leggiHarm(campi.getOrNull(6))
                    val ac = leggiArp(campi.getOrNull(7))
                    if (passi.isNotEmpty())
                        out.add(Brano(nome, Progression(nome, passi), bpm, scena, batt, fx, hc, ac))
                } catch (_: Exception) { }
            }
            return if (out.isEmpty()) predefinita() else Setlist(out)
        }

        /**
         * "voci,modo,g1..g4,minGlob,maxGlob,min1,max1,...,min4,max4"
         *
         * I due valori globali sono la vecchia forma, quando l'intervallo era
         * uno per tutte le voci: se gli intervalli per voce non ci sono, si
         * usano quelli per riempirli tutti, cosi' una configurazione salvata
         * prima non si perde.
         */
        private fun leggiHarm(t: String?): HarmonizerCfg {
            val c = HarmonizerCfg()
            if (t.isNullOrBlank()) return c
            try {
                val q = t.split(",")
                c.voci = q[0].toInt()
                c.modo = SelectionMode.valueOf(q[1])
                for (i in 0 until MAX_VOCI) q.getOrNull(2 + i)?.toIntOrNull()?.let { c.gradi[i] = it }
                val globMin = q.getOrNull(2 + MAX_VOCI)?.toIntOrNull()
                val globMax = q.getOrNull(3 + MAX_VOCI)?.toIntOrNull()
                val perVoce = q.size >= 4 + MAX_VOCI + 2 * MAX_VOCI
                for (i in 0 until MAX_VOCI) {
                    if (perVoce) {
                        q.getOrNull(4 + MAX_VOCI + 2 * i)?.toIntOrNull()?.let { c.gradiMin[i] = it }
                        q.getOrNull(5 + MAX_VOCI + 2 * i)?.toIntOrNull()?.let { c.gradiMax[i] = it }
                    } else {
                        globMin?.let { c.gradiMin[i] = it }
                        globMax?.let { c.gradiMax[i] = it }
                    }
                }
            } catch (_: Exception) { }
            c.normalizza()
            return c
        }

        /** "pattern,notePerQuarto,ottave,gate,swing,soloAccordo" */
        private fun leggiArp(t: String?): ArpCfg {
            val c = ArpCfg()
            if (t.isNullOrBlank()) return c
            try {
                val q = t.split(",")
                c.pattern = ArpPattern.valueOf(q[0])
                c.notePerQuarto = q[1].toInt()
                c.ottave = q[2].toInt()
                c.gate = q[3].toInt()
                c.swing = q[4].toInt()
                c.soloAccordo = q[5] == "1"
            } catch (_: Exception) { }
            c.normalizza()
            return c
        }
    }

    fun salva(ctx: Context) {
        val sb = StringBuilder("v5\n")
        for (b in brani) {
            sb.append(b.nome).append("¦").append(b.bpm).append("¦")
            b.scena?.let { sb.append(if (it.utente) "u" else "p").append(",")
                .append(it.banco).append(",").append(it.numero) }
            sb.append("¦")
            for (p in b.progressione.steps)
                sb.append(p.chord.root).append(",").append(p.chord.quality.name).append(",")
                    .append(p.scale.id).append(",").append(p.quarti).append(";")
            sb.append("\u00a6").append(b.battiti)
            sb.append("\u00a6").append(b.fx.codice())
            sb.append("\u00a6").append(b.harmCfg.voci).append(",").append(b.harmCfg.modo.name)
            for (g in b.harmCfg.gradi) sb.append(",").append(g)
            // i due globali restano per compatibilita': sono l'intervallo della voce 1
            sb.append(",").append(b.harmCfg.estremoBasso(0)).append(",").append(b.harmCfg.estremoAlto(0))
            for (i in 0 until MAX_VOCI)
                sb.append(",").append(b.harmCfg.gradiMin[i]).append(",").append(b.harmCfg.gradiMax[i])
            sb.append("\u00a6").append(b.arpCfg.pattern.name).append(",")
                .append(b.arpCfg.notePerQuarto).append(",").append(b.arpCfg.ottave).append(",")
                .append(b.arpCfg.gate).append(",").append(b.arpCfg.swing).append(",")
                .append(if (b.arpCfg.soloAccordo) "1" else "0")
            sb.append("\n")
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, sb.toString()).apply()
    }
}

/**
 * Schermata della scaletta. Selezionando un brano si caricano progressione,
 * BPM e — se impostata — si richiama la scena dell'AE-20: un tocco e hai
 * anche il suono giusto.
 */
class SetlistView(private val act: Activity, private val setlist: Setlist) {

    var indiceAttivo = 0
    var onScegli: ((Int) -> Unit)? = null
    var onModificaProgressione: ((Int) -> Unit)? = null
    var onIndietro: (() -> Unit)? = null
    var onCambia: (() -> Unit)? = null

    private val contenitore = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    fun build(): View {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            setPadding(24, 30, 24, 20)
        }
        root.addView(TextView(act).apply {
            text = "SCALETTA"; setTextColor(Pal.dim); textSize = 11f; letterSpacing = 0.12f
        })
        root.addView(ScrollView(act).apply { addView(contenitore) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(Button(act).apply {
            text = "+ nuovo brano"
            textSize = 16f
            minimumHeight = (act.resources.displayMetrics.density * 60).toInt()
            setOnClickListener {
                setlist.brani.add(Brano("Nuovo brano", progressioneDefault(), 120.0, null))
                setlist.salva(act); refresh(); onCambia?.invoke()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        refresh()
        return root
    }

    fun refresh() {
        contenitore.removeAllViews()
        for ((i, b) in setlist.brani.withIndex()) {
            val attivo = i == indiceAttivo
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 22, 24, 22)
                background = GradientDrawable().apply {
                    cornerRadius = 12f
                    setColor(if (attivo) Pal.surf2 else Pal.surf)
                    if (attivo) setStroke(3, Pal.acc)
                }
                setOnClickListener { indiceAttivo = i; refresh(); onScegli?.invoke(i) }
            }
            card.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(EditText(act).apply {
                    setText(b.nome); setTextColor(if (attivo) Pal.acc else Pal.fg)
                    textSize = 19f; typeface = Typeface.DEFAULT_BOLD
                    background = null; setPadding(0, 0, 0, 0)
                    setOnFocusChangeListener { _, f ->
                        if (!f) { b.nome = text.toString(); setlist.salva(act) }
                    }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(TextView(act).apply {
                    text = "✕"; setTextColor(Pal.dim); textSize = 17f
                    setPadding(24, 0, 8, 0)
                    setOnClickListener {
                        if (setlist.brani.size > 1) {
                            setlist.brani.removeAt(i); setlist.salva(act)
                            if (indiceAttivo >= setlist.brani.size) indiceAttivo = 0
                            refresh(); onCambia?.invoke()
                        }
                    }
                })
            })

            card.addView(TextView(act).apply {
                text = "%s · %s".format(b.fx.etichetta, descrizioneFx(b))
                setTextColor(Pal.warn); textSize = 12f
                setPadding(0, 10, 0, 0)
            })

            card.addView(TextView(act).apply {
                text = "%.0f BPM · %s · %s battute · %s".format(
                    b.bpm, nomeTempo(b.battiti),
                    formattaQuarti(b.progressione.totalQuarti, b.battiti),
                    b.progressione.steps.take(4).joinToString(" ") { it.chord.toString() } +
                        if (b.progressione.steps.size > 4) " …" else "")
                setTextColor(Pal.dim); textSize = 12f
                setPadding(0, 8, 0, 0)
            })

            // scena dell'AE-20
            card.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 14, 0, 0)
                addView(TextView(act).apply {
                    text = "scena AE-20:  "; setTextColor(Pal.dim); textSize = 12f
                })
                addView(TextView(act).apply {
                    text = b.scena?.etichetta() ?: "nessuna"
                    setTextColor(if (b.scena != null) Pal.warn else Pal.dim)
                    textSize = 14f; typeface = Typeface.MONOSPACE
                })
                addView(Button(act).apply {
                    text = "imposta"; textSize = 11f
                    setOnClickListener { dialogoScena(b) }
                })
            })

            card.addView(Button(act).apply {
                text = "progressione ›"; textSize = 12f
                setOnClickListener { indiceAttivo = i; onModificaProgressione?.invoke(i) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            contenitore.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { bottomMargin = 14 })
        }
    }

    /** Sintesi dei parametri dell'effetto attivo, per la scheda del brano. */
    private fun descrizioneFx(b: Brano): String = when (b.fx) {
        FxTipo.HARMONIZER -> "%d %s · %s · %s".format(
            b.harmCfg.voci,
            if (b.harmCfg.voci == 1) "voce" else "voci",
            nomeModo(b.harmCfg.modo),
            if (b.harmCfg.modo == SelectionMode.FIXED)
                b.harmCfg.gradiAttivi().joinToString(" ") { Degrees.name(it) }
            // nomi brevi: "4a sotto-2a" si legge come una sottrazione
            else (0 until b.harmCfg.voci).joinToString(" · ") { v ->
                "${gradoBreve(b.harmCfg.estremoBasso(v))}…${gradoBreve(b.harmCfg.estremoAlto(v))}"
            })
        FxTipo.ARPEGGIATOR -> "%s · %s · %d ott.%s".format(
            b.arpCfg.pattern.etichetta,
            Suddivisioni.NOMI[Suddivisioni.indiceDi(b.arpCfg.notePerQuarto)],
            b.arpCfg.ottave,
            if (b.arpCfg.swing > 0) " · swing ${b.arpCfg.swing}%" else "")
    }

    private fun dialogoScena(b: Brano) {
        var utente = b.scena?.utente ?: true
        var banco = b.scena?.banco ?: 0
        var numero = b.scena?.numero ?: 1

        val lbl = TextView(act).apply {
            setTextColor(Pal.fg); textSize = 20f; gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        fun agg() { lbl.text = ScenaAE20(utente, banco, numero).etichetta() }
        agg()

        fun stepper(titolo: String, meno: () -> Unit, piu: () -> Unit) = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(act).apply {
                text = titolo; setTextColor(Pal.dim); textSize = 13f
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(act).apply { text = "−"; setOnClickListener { meno(); agg() } })
            addView(Button(act).apply { text = "+"; setOnClickListener { piu(); agg() } })
        }

        val corpo = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(lbl)
            addView(stepper("banco utente / preset",
                { utente = !utente }, { utente = !utente }))
            addView(stepper("banco",
                { banco = (banco - 1 + 12) % 12 }, { banco = (banco + 1) % 12 }))
            addView(stepper("numero",
                { numero = (numero - 1).coerceAtLeast(1) }, { numero = (numero + 1).coerceAtMost(50) }))
        }

        android.app.AlertDialog.Builder(act)
            .setTitle("Scena dell'AE-20")
            .setView(corpo)
            .setPositiveButton("imposta") { _, _ ->
                b.scena = ScenaAE20(utente, banco, numero); setlist.salva(act); refresh()
            }
            .setNeutralButton("nessuna") { _, _ ->
                b.scena = null; setlist.salva(act); refresh()
            }
            .setNegativeButton("annulla", null)
            .show()
    }
}
