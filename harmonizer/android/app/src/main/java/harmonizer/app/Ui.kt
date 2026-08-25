package harmonizer.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import harmonizer.core.*

object Pal {
    val bg = Color.parseColor("#101413")
    val surf = Color.parseColor("#181D1C")
    val surf2 = Color.parseColor("#222827")
    val fg = Color.parseColor("#E9EDE9")
    val dim = Color.parseColor("#7C8683")
    val acc = Color.parseColor("#6AC7C4")
    val warn = Color.parseColor("#DEA359")
    val rule = Color.parseColor("#2A312F")
}

/**
 * Striscia compatta di scelte. Le prime voci sono "in evidenza": sono le
 * più probabili, secondo l'ordinamento del catalogo.
 */
class Strip(ctx: Context, private val evidenza: Int = 4) : HorizontalScrollView(ctx) {

    private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private var etichette: List<String> = emptyList()
    private var sel = 0
    var onSelect: ((Int) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        setPadding(0, 8, 0, 8)
    }

    fun set(items: List<String>, selected: Int) {
        etichette = items
        sel = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        rebuild()
    }

    fun selected(): Int = sel

    private fun rebuild() {
        row.removeAllViews()
        for ((i, t) in etichette.withIndex()) {
            val chip = TextView(context).apply {
                text = t
                textSize = 14f
                setPadding(30, 20, 30, 20)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                val on = i == sel
                setTextColor(if (on) Pal.bg else if (i < evidenza) Pal.fg else Pal.dim)
                background = GradientDrawable().apply {
                    cornerRadius = 10f
                    setColor(if (on) Pal.acc else Pal.surf2)
                    if (!on && i < evidenza) setStroke(2, Pal.rule)
                }
                setOnClickListener { sel = i; rebuild(); onSelect?.invoke(i) }
            }
            row.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                rightMargin = 12
            })
        }
    }
}

/**
 * Schermata di scelta dell'accordo: circolo delle quinte a tutto schermo,
 * più una striscia per la qualità e una per la scala. Il default della
 * scala dipende dalla qualità; cambiando qualità la scala si sposta solo
 * se quella corrente non è più compatibile.
 */
class ChordPicker(
    private val act: Activity,
    iniziale: ChordStep,
    private val battiti: Int = BATTITI_DEFAULT
) {

    private var root = iniziale.chord.root
    private var minore = iniziale.chord.quality.minor
    private var qualita = iniziale.chord.quality
    private var scala = iniziale.scale
    private var quarti = iniziale.quarti

    private lateinit var stripQualita: Strip
    private lateinit var stripScala: Strip
    private lateinit var lblSintesi: TextView

    var onDone: ((ChordStep) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private fun qualitaDisponibili(): List<ChordQuality> =
        ChordQuality.values().filter { it.minor == minore }

    fun build(): View {
        val root0 = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            setPadding(24, 24, 24, 24)
        }

        val circle = CircleOfFifthsView(act).apply {
            selectedRoot = this@ChordPicker.root
            selectedMinor = this@ChordPicker.minore
            onPick = { r, m ->
                this@ChordPicker.root = r
                this@ChordPicker.minore = m
                val disp = qualitaDisponibili()
                if (qualita.minor != m) qualita = if (m) ChordQuality.MIN7 else ChordQuality.DOM7
                aggiornaQualita(disp)
                aggiornaScala(true)
                sintesi()
            }
        }

        lblSintesi = TextView(act).apply {
            textSize = 22f; setTextColor(Pal.fg); gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        }

        stripQualita = Strip(act, 4).apply {
            onSelect = { i ->
                qualita = qualitaDisponibili()[i]
                aggiornaScala(false)
                sintesi()
            }
        }
        stripScala = Strip(act, 4).apply {
            onSelect = { i ->
                scala = Scales.compatibleWith(qualita)[i]
                sintesi()
            }
        }

        val lblBattute = TextView(act).apply {
            setTextColor(Pal.fg); textSize = 17f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        fun aggDurata() { lblBattute.text = "  ${formattaQuarti(quarti, battiti)} battute  " }
        val rigaBattute = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(act).apply {
                text = "−"; textSize = 20f
                setOnClickListener { quarti = (quarti - 1).coerceAtLeast(1); aggDurata() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(lblBattute, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
            addView(Button(act).apply {
                text = "+"; textSize = 20f
                setOnClickListener { quarti = (quarti + 1).coerceAtMost(battiti * 16); aggDurata() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        aggDurata()

        val azioni = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(act).apply { text = "annulla"; setOnClickListener { onCancel?.invoke() } },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(act).apply {
                text = "conferma"
                setOnClickListener {
                    onDone?.invoke(ChordStep(Chord(root, qualita), scala, quarti))
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        fun piena() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

        root0.addView(circle, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root0.addView(lblSintesi, piena())
        root0.addView(etichetta("QUALITÀ"), piena())
        root0.addView(stripQualita, piena())
        root0.addView(etichetta("SCALA DA SUONARCI SOPRA"), piena())
        root0.addView(stripScala, piena())
        root0.addView(etichetta(
            "DURATA — un quarto alla volta, in " + nomeTempo(battiti)), piena())
        root0.addView(rigaBattute, piena())
        root0.addView(azioni, piena())

        aggiornaQualita(qualitaDisponibili())
        aggiornaScala(false)
        sintesi()
        return root0
    }

    private fun etichetta(t: String) = TextView(act).apply {
        text = t; setTextColor(Pal.dim); textSize = 10f
        letterSpacing = 0.12f
        setPadding(4, 14, 0, 2)
    }

    private fun aggiornaQualita(disp: List<ChordQuality>) {
        val idx = disp.indexOf(qualita).coerceAtLeast(0)
        qualita = disp[idx]
        stripQualita.set(disp.map { if (it.label.isEmpty()) "triade" else it.label }, idx)
    }

    /** Cambia scala solo se quella corrente non è più compatibile. */
    private fun aggiornaScala(forza: Boolean) {
        val disp = Scales.compatibleWith(qualita)
        if (forza || scala !in disp) scala = Scales.defaultFor(qualita)
        stripScala.set(disp.map { it.name }, disp.indexOf(scala).coerceAtLeast(0))
    }

    private fun sintesi() {
        lblSintesi.text = "${Chord(root, qualita)}   ·   ${scala.name}"
    }
}

/** Griglia della progressione: passo corrente evidenziato, tocco per editare. */
class ProgressionView(private val act: Activity) {

    var progression: Progression = progressioneDefault()
    var currentStep: Int = -1
    /** Movimenti per battuta del brano. */
    var battiti: Int = BATTITI_DEFAULT
    var onEdit: ((Int) -> Unit)? = null
    var onAdd: (() -> Unit)? = null
    var onDelete: ((Int) -> Unit)? = null
    var onTempo: ((Int) -> Unit)? = null

    private val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    fun build(): View {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(act).apply {
            text = "TEMPO"; setTextColor(Pal.dim); textSize = 11f; letterSpacing = 0.12f
        })
        val stripTempo = Strip(act, TEMPI_AMMESSI.size).apply {
            set(TEMPI_AMMESSI.map { nomeTempo(it) },
                TEMPI_AMMESSI.indexOf(battiti).coerceAtLeast(0))
            onSelect = { i -> battiti = TEMPI_AMMESSI[i]; onTempo?.invoke(battiti); refresh() }
        }
        root.addView(stripTempo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TextView(act).apply {
            text = "PROGRESSIONE"; setTextColor(Pal.dim); textSize = 11f; letterSpacing = 0.12f
            setPadding(0, 22, 0, 0)
        })
        root.addView(ScrollView(act).apply { addView(container) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(Button(act).apply {
            text = "+ aggiungi accordo"
            textSize = 16f
            minimumHeight = (act.resources.displayMetrics.density * 60).toInt()
            setOnClickListener { onAdd?.invoke() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        refresh()
        return root
    }

    fun refresh() {
        container.removeAllViews()
        var quarto = 0
        for ((i, s) in progression.steps.withIndex()) {
            val attivo = i == currentStep
            val riga = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 22, 20, 22)
                background = GradientDrawable().apply {
                    cornerRadius = 10f
                    setColor(if (attivo) Pal.surf2 else Pal.surf)
                    if (attivo) setStroke(3, Pal.acc)
                }
                setOnClickListener { onEdit?.invoke(i) }
            }
            riga.addView(TextView(act).apply {
                val intere = quarto / battiti + 1
                val resto = quarto % battiti
                text = "bt " + intere + if (resto == 0) "" else "+$resto/4"
                setTextColor(Pal.dim); textSize = 12f
                typeface = Typeface.MONOSPACE
            })
            riga.addView(TextView(act).apply {
                text = "  ${s.chord}"; setTextColor(if (attivo) Pal.acc else Pal.fg); textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            riga.addView(TextView(act).apply {
                text = s.scale.name; setTextColor(Pal.dim); textSize = 12f
            })
            riga.addView(TextView(act).apply {
                text = "  " + s.durataTesto(battiti)
                setTextColor(if (attivo) Pal.acc else Pal.fg)
                textSize = 13f; typeface = Typeface.MONOSPACE
                setPadding(18, 0, 0, 0)
            })
            riga.addView(TextView(act).apply {
                text = "  ✕"; setTextColor(Pal.dim); textSize = 16f
                setOnClickListener { onDelete?.invoke(i) }
            })
            container.addView(riga, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = 10
            })
            quarto += s.quarti
        }
    }
}
