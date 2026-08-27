package harmonizer.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import harmonizer.core.*

class MainActivity : Activity() {

    private lateinit var midi: MidiEngine
    private lateinit var harm: Harmonizer
    private lateinit var setlist: Setlist

    private lateinit var contenitore: FrameLayout
    private lateinit var carosello: ChordCarousel
    private lateinit var log: TextView
    private lateinit var lblStato: TextView
    private lateinit var lblBrano: TextView
    private lateinit var btnArmonia: SymbolButton
    private lateinit var btnBpm: SymbolButton
    private lateinit var lblDomanda: TextView
    private lateinit var rigaRisposte: View
    private lateinit var sezioneCollega: View
    private lateinit var progView: ProgressionView

    private val ui = Handler(Looper.getMainLooper())
    private var wizardStep = -1
    private val esiti = LinkedHashMap<String, String>()
    private var monitorAttivo = true
    private val righeLog = ArrayList<String>()
    // 0 principale, 1 progressione, 2 picker, 3 scaletta, 4 test, 5 effetti
    private var schermata = 0
    private var branoAttivo = 0
    private var pickerDalCarosello = false
    private lateinit var orologio: OrologioFx

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        midi = MidiEngine(this, ::onMidiEvent) { m -> ui.post { logLine(m) } }
        harm = Harmonizer(midi)
        setlist = Setlist.carica(this)
        caricaBrano(0, richiamaScena = false)
        midi.start()
        HarmService.start(this)

        // il clock degli effetti sta su un thread proprio: l'arpeggiatore ha
        // bisogno di una risoluzione che il tick dell'interfaccia non da'.
        // All'armonizzatore basta accorgersi dei cambi di accordo, quindi la
        // si dirada: e' inutile svegliarsi 500 volte al secondo per guardare
        // la battuta.
        orologio = OrologioFx(
            { if (harm.config.fx == FxTipo.ARPEGGIATOR) 2L else 20L },
            { now -> harm.tick(now) })
        orologio.start()

        chiediPermessi()

        contenitore = FrameLayout(this).apply { setBackgroundColor(Pal.bg) }
        setContentView(contenitore)
        mostraPrincipale()
        tickLoop()
    }

    override fun onDestroy() {
        orologio.stop()
        try { harm.panic() } catch (_: Exception) {}
        midi.stop(); HarmService.stop(this)
        super.onDestroy()
    }

    /**
     * Su Android 13+ le notifiche sono un permesso a runtime: senza, la
     * notifica persistente del servizio in primo piano non compare e il
     * servizio diventa piu' fragile.
     *
     * Il MIDI via MidiManager non richiede permessi: e' il servizio di sistema
     * a possedere il dispositivo USB.
     */
    private fun chiediPermessi() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ------------------------------------------------------------- scaletta

    private fun caricaBrano(i: Int, richiamaScena: Boolean) {
        if (i !in setlist.brani.indices) return
        branoAttivo = i
        val b = setlist.brani[i]
        harm.progression = b.progressione
        harm.transport.bpm = b.bpm
        harm.transport.beatsPerBar = b.battiti
        harm.applicaFx(b.fx, b.harmCfg, b.arpCfg, b.voicingCfg)
        if (richiamaScena) b.scena?.let {
            midi.selezionaScena(harm.config.leadChannel, ScenaAE20.MSB, it.lsb(), it.numero)
            logLine("Scena AE-20: ${it.etichetta()}")
        }
    }

    /**
     * Il tasto indietro di Android naviga fra le viste; dalla principale esce.
     * Il selettore torna da dove e' stato aperto: progressione o carosello.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (schermata) {
            3 -> scorriVerso(buildPrincipale(), daSinistra = false, nuovaSchermata = 0)
            5 -> scorriVerso(buildPrincipale(), daSinistra = true, nuovaSchermata = 0)
            1, 4 -> mostraPrincipale()
            2 -> if (pickerDalCarosello) mostraPrincipale() else mostraProgressione()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------ schermate

    private fun sostituisci(v: View) {
        contenitore.removeAllViews()
        contenitore.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun mostraPrincipale() {
        schermata = 0
        vEntrante = null; vUscente = null
        sostituisci(buildPrincipale())
    }

    private fun mostraScaletta() {
        scorriVerso(costruisciScaletta(), daSinistra = true, nuovaSchermata = 3)
    }

    private fun costruisciScaletta(): View {
        val interno = costruisciScalettaInterna()
        return RadiceScorrevole(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            versi = intArrayOf(-1)          // si tira verso sinistra: la principale sta a destra
            sogliaY = 0f                    // qui vale su tutta la vista
            addView(interno, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            onInizio = {
                preparaScorrimento(buildPrincipale(), daSinistra = false, destinazione = 0)
            }
            onTrascina = { dx -> trascinaScorrimento(dx) }
            onRilascio = { dx, v -> rilasciaScorrimento(dx, v) }
        }
    }

    private fun costruisciScalettaInterna(): View {
        val v = SetlistView(this, setlist).apply {
            indiceAttivo = branoAttivo
            // niente attesa: si scorre subito verso destra, verso la principale
            onScegli = { i ->
                caricaBrano(i, richiamaScena = true)
                scorriVerso(buildPrincipale(), daSinistra = false, nuovaSchermata = 0)
            }
            onModificaProgressione = { i -> caricaBrano(i, false); mostraProgressione() }
            onIndietro = { mostraPrincipale() }
            onCambia = { }
        }
        return v.build()
    }

    // ------------------------------------------------------------- effetti

    private fun mostraFx() {
        scorriVerso(costruisciFx(), daSinistra = false, nuovaSchermata = 5)
    }

    /**
     * La vista degli effetti sta a destra della principale, speculare alla
     * scaletta: da qui si torna trascinando verso destra.
     */
    private fun costruisciFx(): View {
        val b = setlist.brani[branoAttivo]
        val interno = FxView(this).apply {
            tipo = b.fx
            harmCfg = b.harmCfg
            arpCfg = b.arpCfg
            voicingCfg = b.voicingCfg
            nomeBrano = b.nome
            onCambia = {
                b.fx = tipo
                harm.applicaFx(b.fx, b.harmCfg, b.arpCfg, b.voicingCfg)
                setlist.salva(this@MainActivity)
            }
        }.build()

        return RadiceScorrevole(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            versi = intArrayOf(1)           // si tira verso destra: la principale sta a sinistra
            sogliaY = 0f
            addView(interno, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            onInizio = {
                preparaScorrimento(buildPrincipale(), daSinistra = true, destinazione = 0)
            }
            onTrascina = { dx -> trascinaScorrimento(dx) }
            onRilascio = { dx, v -> rilasciaScorrimento(dx, v) }
        }
    }

    private fun mostraProgressione() {
        schermata = 1
        progView = ProgressionView(this).apply {
            progression = harm.progression
            battiti = setlist.brani[branoAttivo].battiti
            currentStep = harm.currentStepIndex(System.nanoTime())
            onTempo = { b ->
                setlist.brani[branoAttivo].battiti = b
                harm.transport.beatsPerBar = b
                setlist.salva(this@MainActivity)
            }
            onEdit = { i -> mostraPicker(i) }
            onAdd = { mostraPicker(-1) }
            onDelete = { i ->
                val l = harm.progression.steps.toMutableList()
                if (l.size > 1) { l.removeAt(i); applicaProgressione(l) }
                mostraProgressione()
            }
        }
        sostituisci(progView.build())
    }

    private fun applicaProgressione(l: List<ChordStep>) {
        val p = Progression(setlist.brani[branoAttivo].nome, l)
        harm.progression = p
        setlist.brani[branoAttivo].progressione = p
        setlist.salva(this)
    }

    private fun mostraPicker(indice: Int, dalCarosello: Boolean = false) {
        schermata = 2
        pickerDalCarosello = dalCarosello
        val iniziale = if (indice >= 0) harm.progression.steps[indice]
                       else ChordStep.battute(Chord(10, ChordQuality.DOM7), Scales.MISOLIDIA, 1,
                                              setlist.brani[branoAttivo].battiti)
        val picker = ChordPicker(this, iniziale, setlist.brani[branoAttivo].battiti)
        val ritorno = { if (dalCarosello) mostraPrincipale() else mostraProgressione() }
        picker.onCancel = ritorno
        picker.onDone = { step ->
            val l = harm.progression.steps.toMutableList()
            if (indice >= 0) l[indice] = step else l.add(step)
            applicaProgressione(l)
            ritorno()
        }
        sostituisci(picker.build())
    }

    // -------------------------------------------------- schermata principale

    private fun etichettina(t: String) = TextView(this).apply {
        text = t; setTextColor(Pal.dim); textSize = 10f; letterSpacing = 0.12f
        setPadding(6, 18, 0, 4)
    }

    private fun testuale(t: String, azione: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Pal.dim); textSize = 13f
        gravity = Gravity.CENTER
        setPadding(10, 18, 10, 18)
        background = GradientDrawable().apply { cornerRadius = 8f; setColor(Pal.surf) }
        setOnClickListener { azione() }
    }

    private fun buildPrincipale(): View {
        val d = resources.displayMetrics
        val margine = (18 * d.density).toInt()
        val gap = (12 * d.density).toInt()
        val lato = (d.widthPixels - margine * 2 - gap) / 2

        val root = RadiceScorrevole(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            setPadding(margine, (20 * d.density).toInt(), margine, margine)
            // scaletta a sinistra (dito a destra), effetti a destra (dito a sinistra)
            versi = intArrayOf(1, -1)
            onInizio = { v ->
                if (v > 0) preparaScorrimento(costruisciScaletta(), daSinistra = true, destinazione = 3)
                else preparaScorrimento(costruisciFx(), daSinistra = false, destinazione = 5)
            }
            onTrascina = { dx -> trascinaScorrimento(dx) }
            onRilascio = { dx, v -> rilasciaScorrimento(dx, v) }
        }

        // nome del brano, piccolo, sopra la riga di stato
        lblBrano = TextView(this).apply {
            setTextColor(Pal.acc); textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (2 * d.density).toInt())
        }
        root.addView(lblBrano)

        // riga di stato compatta, sempre presente
        lblStato = TextView(this).apply {
            setTextColor(Pal.dim); textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * d.density).toInt())
        }
        root.addView(lblStato)

        // navigazione compatta
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(testuale("scaletta") { mostraScaletta() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = gap / 2 })
            addView(testuale("progressione") { mostraProgressione() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = gap / 2 })
            addView(testuale("test") { mostraTest() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = gap / 2 })
            addView(testuale("fx") { mostraFx() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })

        // carosello degli accordi
        carosello = ChordCarousel(this).apply {
            onJump = { i ->
                harm.jump(System.nanoTime(), harm.progression.inizioDi(i))
            }
            onModifica = { i -> mostraPicker(i, dalCarosello = true) }
            onAggiungi = { mostraPicker(-1, dalCarosello = true) }
        }
        root.addView(carosello, LinearLayout.LayoutParams(MATCH_PARENT, (118 * d.density).toInt())
            .apply { topMargin = gap })

        // collegamento: sparisce quando lo strumento è connesso
        sezioneCollega = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Collega l'AE-20 via USB, poi tocca «collega»."
                setTextColor(Pal.warn); textSize = 14f; setPadding(4, 10, 4, 8)
            })
            addView(testuale("collega") {
                midi.openFirst(); midi.resetChannels(intArrayOf(1, 2, 3, 4, 5))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(sezioneCollega)

        // due pulsanti quadrati, stessa riga
        btnArmonia = SymbolButton(this, SymbolButton.Sym.ARMONIA, "S1").apply {
            attivo = true
            testoSotto = "FX"
            onTap = { harm.setMuted(!harm.muted); aggiornaArmonia() }
        }
        val btnBatt = SymbolButton(this, SymbolButton.Sym.BATTUTA_1, "S2").apply {
            testoSotto = "BATT. 1"
            onTap = { harm.resync(System.nanoTime()) }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnArmonia, LinearLayout.LayoutParams(lato, lato).apply { rightMargin = gap })
            addView(btnBatt, LinearLayout.LayoutParams(lato, lato))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = gap })

        // stop: fascia a tutta larghezza, riga a sé
        val altezzaStop = (lato * 0.52f).toInt()
        root.addView(SymbolButton(this, SymbolButton.Sym.STOP, "hold S2").apply {
            onTap = { harm.stopAll(System.nanoTime()); aggiornaArmonia() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, altezzaStop).apply { topMargin = gap })

        // TAP: prende tutto lo spazio rimanente
        btnBpm = SymbolButton(this, SymbolButton.Sym.TAP, null).apply {
            testoSotto = "120 BPM"
            onTap = {
                harm.tapTempo.tap(System.nanoTime())?.let {
                    harm.transport.bpm = it
                    setlist.brani[branoAttivo].bpm = it
                    setlist.salva(this@MainActivity)
                }
            }
        }
        root.addView(btnBpm, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            .apply { topMargin = gap })

        aggiornaArmonia()
        aggiornaCollegamento()
        // sotto il carosello il trascinamento richiama la scaletta; sopra no,
        // altrimenti ruberebbe il gesto al carosello
        root.post { root.sogliaY = carosello.bottom.toFloat() }
        return root
    }

    // ------------------------------------------------- scorrimento laterale

    private var vEntrante: View? = null
    private var vUscente: View? = null
    private var entraDaSinistra = true
    private var schermataDestinazione = 0

    /**
     * Prepara il trascinamento: la vista che entra viene messa fuori campo dal
     * lato giusto, dietro a quella corrente. Le due non si sovrappongono mai,
     * perche' i bordi restano adiacenti per tutta la corsa.
     */
    private fun preparaScorrimento(nuova: View, daSinistra: Boolean, destinazione: Int) {
        if (vEntrante != null) return
        val w = contenitore.width.toFloat()
        vUscente = contenitore.getChildAt(0)
        entraDaSinistra = daSinistra
        schermataDestinazione = destinazione
        nuova.translationX = if (daSinistra) -w else w
        contenitore.addView(nuova, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        vEntrante = nuova
    }

    private fun trascinaScorrimento(d: Float) {
        val w = contenitore.width.toFloat()
        val k = d.coerceIn(0f, w)
        val segno = if (entraDaSinistra) 1f else -1f
        vUscente?.translationX = segno * k
        vEntrante?.translationX = segno * (k - w)
    }

    private fun rilasciaScorrimento(d: Float, velocita: Float) {
        val w = contenitore.width.toFloat()
        val completa = d > w * 0.33f || velocita > 1200f
        val segno = if (entraDaSinistra) 1f else -1f
        val k = if (completa) w else 0f
        val entrante = vEntrante
        val uscente = vUscente

        uscente?.animate()?.translationX(segno * k)?.setDuration(160)
            ?.setInterpolator(DecelerateInterpolator())?.start()
        entrante?.animate()?.translationX(segno * (k - w))?.setDuration(160)
            ?.setInterpolator(DecelerateInterpolator())
            ?.withEndAction {
                if (completa) {
                    uscente?.let { contenitore.removeView(it); it.translationX = 0f }
                    entrante.translationX = 0f
                    schermata = schermataDestinazione
                } else {
                    contenitore.removeView(entrante)
                    uscente?.translationX = 0f
                }
                vEntrante = null; vUscente = null
            }?.start()
    }

    /** Transizione a scorrimento fra due schermate. */
    private fun scorriVerso(nuova: View, daSinistra: Boolean, nuovaSchermata: Int) {
        val w = contenitore.width.toFloat()
        val vecchia = if (contenitore.childCount > 0) contenitore.getChildAt(0) else null
        contenitore.addView(nuova, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        nuova.translationX = if (daSinistra) -w else w
        nuova.animate().translationX(0f).setDuration(170)
            .setInterpolator(DecelerateInterpolator()).start()
        vecchia?.animate()?.translationX(if (daSinistra) w else -w)?.setDuration(170)
            ?.setInterpolator(DecelerateInterpolator())
            ?.withEndAction {
                contenitore.removeView(vecchia)
                vecchia.translationX = 0f
            }?.start()
        schermata = nuovaSchermata
        vEntrante = null
        vUscente = null
    }

    /** Schermata diagnostica: procedura guidata e monitor MIDI grezzo. */
    private fun mostraTest() {
        schermata = 4
        val d = resources.displayMetrics
        lblDomanda = TextView(this).apply {
            text = "Premi «avvia» per la procedura guidata."
            setTextColor(Pal.warn); textSize = 15f; setPadding(4, 10, 4, 10)
        }
        rigaRisposte = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(testuale("SÌ") { rispondi(true) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 12 })
            addView(testuale("NO") { rispondi(false) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            visibility = View.GONE
        }
        log = TextView(this).apply {
            setTextColor(Pal.dim); textSize = 11f; typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            background = GradientDrawable().apply { cornerRadius = 10f; setColor(Pal.surf) }
            setPadding(18, 14, 18, 14)
            text = righeLog.takeLast(120).joinToString("\n") + "\n"
        }
        sostituisci(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            setPadding((18 * d.density).toInt(), (24 * d.density).toInt(),
                       (18 * d.density).toInt(), (18 * d.density).toInt())
            addView(TextView(this@MainActivity).apply {
                text = "PROCEDURA GUIDATA E MONITOR"; setTextColor(Pal.dim)
                textSize = 10f; letterSpacing = 0.12f
            })
            addView(lblDomanda)
            addView(rigaRisposte)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(testuale("avvia") { avviaWizard() },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 12 })
                addView(testuale("panic") { harm.panic() },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 12 })
                addView(testuale("pulisci") { righeLog.clear(); log.text = "" },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            addView(log, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 16 })
        })
    }

    private fun aggiornaArmonia() {
        if (!::btnArmonia.isInitialized) return
        val padre = btnArmonia.parent as? LinearLayout ?: return
        val i = padre.indexOfChild(btnArmonia)
        val lp = btnArmonia.layoutParams
        val nuovo = SymbolButton(this,
            if (harm.muted) SymbolButton.Sym.ARMONIA_MUTA else SymbolButton.Sym.ARMONIA, "S1").apply {
            attivo = !harm.muted
            testoSotto = if (harm.muted) "FX MUTO" else "FX"
            onTap = { harm.setMuted(!harm.muted); aggiornaArmonia() }
        }
        padre.removeViewAt(i); padre.addView(nuovo, i, lp)
        btnArmonia = nuovo
    }

    private fun aggiornaCollegamento() {
        if (!::sezioneCollega.isInitialized) return
        sezioneCollega.visibility =
            if (midi.connectedName != null) View.GONE else View.VISIBLE
    }

    private fun logLine(s: String) {
        righeLog.add(s)
        if (righeLog.size > 300) repeat(150) { righeLog.removeAt(0) }
        if (schermata == 4 && ::log.isInitialized) {
            log.append(s + "\n")
        }
    }

    // -------------------------------------------------------------- eventi

    private fun onMidiEvent(e: MidiEngine.MidiEvent) {
        when (e.status) {
            0x90 -> if (e.d2 > 0) harm.onNoteOn(e.d1, e.d2, e.nanos) else harm.onNoteOff(e.d1, e.nanos)
            0x80 -> harm.onNoteOff(e.d1, e.nanos)
            0xB0 -> harm.onControlChange(e.d1, e.d2, e.nanos)
            0xE0 -> harm.onPitchBend(e.d1 or (e.d2 shl 7))
            0xD0 -> harm.onAftertouch(e.d1)
        }
        if (monitorAttivo && schermata == 4) ui.post {
            logLine("ch%-2d %02X %3d %3d".format(e.ch, e.status, e.d1, e.d2))
        }
    }

    private fun tickLoop() {
        ui.postDelayed({
            val now = System.nanoTime()
            if (schermata == 0 && ::carosello.isInitialized) {
                val steps = harm.progression.steps
                val q = harm.transport.quarto(now)
                val idx = harm.progression.stepIndexAt(q)
                val tot = harm.progression.totalQuarti
                val dentro = (((q % tot) + tot) % tot) - harm.progression.inizioDi(idx)
                carosello.battiti = setlist.brani[branoAttivo].battiti
                carosello.aggiorna(steps, idx, dentro.coerceAtLeast(0))
                btnBpm.testoSotto = "%.0f BPM".format(harm.transport.bpm)
                btnBpm.invalidate()
                lblBrano.text = setlist.brani[branoAttivo].nome
                lblStato.text = "%s · %.0f BPM · %s · voci %d · %.1fµs".format(
                    midi.connectedName ?: "non collegato",
                    harm.transport.bpm,
                    nomeTempo(setlist.brani[branoAttivo].battiti),
                    harm.activeVoices(),
                    harm.lastProcessNanos / 1000.0)
                aggiornaCollegamento()
            }
            tickLoop()
        }, 40)
    }

    // -------------------------------------------------------------- wizard

    private val passi: List<Pair<String, () -> Unit>> = listOf(
        "Test 1a — sullo strumento metti MIDI Ctrl Sound = ON.\nInvio una nota sul canale 1. La senti?" to { notaDiProva(1, 0, 0) },
        "Test 1b — ora metti MIDI Ctrl Sound = OFF.\nStessa nota. La senti ANCORA?" to { notaDiProva(1, 0, 0) },
        "Test 2 — nota senza espressione, canale 1.\nLa senti?" to { notaDiProva(1, 0, 0) },
        "Test 2b — nota preceduta da CC11 (Expression) = 100.\nLa senti?" to { notaDiProva(1, 11, 100) },
        "Test 2c — nota preceduta da CC2 (Breath) = 100.\nLa senti?" to { notaDiProva(1, 2, 100) },
        "Test 3 — stessa nota su ch1, ch2, ch3, ch4, ch5.\nHai sentito TUTTI e cinque?" to { scanCanali() },
        "Test 4 — due note sovrapposte sulla parte di armonia.\nHai sentito un NUOVO ATTACCO?" to { provaLegato(2) },
        "Test 5 — POLY: mando CC127 sulla parte 2, poi quattro note insieme.\nLe senti tutte e QUATTRO?" to { provaPoly(2) },
        "Test 6 — otto note di voicing, due per parte su ch2-ch5.\nLe senti tutte e OTTO?" to { provaVoicing() }
    )

    private val chiavi = listOf("MIDI Ctrl Sound On", "MIDI Ctrl Sound Off", "nota nuda",
        "CC11", "CC2", "canali 1-5", "legato riattacca", "poly su una parte", "otto voci")

    private fun avviaWizard() {
        wizardStep = -1; esiti.clear()
        rigaRisposte.visibility = View.VISIBLE
        avanza()
    }

    private fun avanza() {
        wizardStep++
        if (wizardStep >= passi.size) { concludi(); return }
        val (testo, azione) = passi[wizardStep]
        lblDomanda.text = testo
        monitorAttivo = false
        azione()
    }

    private fun rispondi(si: Boolean) {
        if (wizardStep !in passi.indices) return
        esiti[chiavi[wizardStep]] = if (si) "sì" else "no"
        avanza()
    }

    private fun concludi() {
        rigaRisposte.visibility = View.GONE
        monitorAttivo = true
        wizardStep = -1
        val sb = StringBuilder("\n=== CONFIGURAZIONE RICAVATA ===\n")
        for ((k, v) in esiti) sb.append("  %-22s %s\n".format(k, v))
        sb.append(if (esiti["MIDI Ctrl Sound Off"] == "sì")
            "  Topologia §4 valida: 'off' è un local off.\n"
        else "  ATTENZIONE: con 'off' il motore è muto del tutto.\n")
        sb.append("  Espressione: " + when {
            esiti["nota nuda"] == "sì" -> "non serve"
            esiti["CC11"] == "sì" -> "CC11"
            esiti["CC2"] == "sì" -> "CC2"
            else -> "nessuno funziona"
        } + "\n")
        sb.append(if (esiti["legato riattacca"] == "no")
            "  Legato senza retrigger ok: via §7.1.\n" else "  Il legato riattacca: usa il bend, §7.2.\n")
        sb.append(if (esiti["poly su una parte"] == "sì")
            "  CC127 funziona: il voicing puo' superare le quattro voci.\n"
        else "  La parte resta mono: massimo 4 voci nel voicing, una per parte.\n" +
             "  Controlla Unison Switch = Off, e se serve prepara la scena in POLY.\n")
        sb.append(if (esiti["otto voci"] == "sì")
            "  Polifonia: otto voci passano.\n"
        else "  Otto voci non passano: abbassa il massimo nel voicing.\n")
        // si rimette il mono, che e' quello che serve all'armonizzatore
        for (c in 2..5) midi.cc(c, 126, 0)
        lblDomanda.text = "Procedura conclusa. Risultati qui sotto."
        logLine(sb.toString())
    }

    private fun notaDiProva(ch: Int, ccNum: Int, ccVal: Int) {
        if (ccNum > 0) midi.cc(ch, ccNum, ccVal)
        midi.noteOn(ch, 72, 100)
        ui.postDelayed({ midi.noteOff(ch, 72) }, 1200)
    }

    private fun scanCanali() {
        for (c in 1..5) ui.postDelayed({
            logLine("canale $c"); midi.cc(c, 11, 100); midi.noteOn(c, 72, 100)
            ui.postDelayed({ midi.noteOff(c, 72) }, 600)
        }, ((c - 1) * 800).toLong())
    }

    /** Poly su una parte: quattro note insieme sullo stesso canale. */
    private fun provaPoly(ch: Int) {
        val note = intArrayOf(60, 64, 67, 71)
        midi.cc(ch, 127, 0)
        midi.cc(ch, 11, 100)
        for (n in note) midi.noteOn(ch, n, 100)
        ui.postDelayed({ for (n in note) midi.noteOff(ch, n) }, 1500)
    }

    /**
     * La polifonia con un voicing vero: otto note, due per parte, distribuite
     * come le distribuisce l'effetto. Serve a tarare il massimo di voci, che
     * nessun documento Roland dichiara.
     */
    private fun provaVoicing() {
        val note = intArrayOf(72, 69, 65, 62, 60, 57, 53, 50)
        for ((k, n) in note.withIndex()) {
            val ch = CANALE_VOCE_BASE + (k % PARTI_ARMONIA)
            midi.cc(ch, 127, 0)
            midi.cc(ch, 11, 100)
            midi.noteOn(ch, n, 100)
        }
        ui.postDelayed({
            for ((k, n) in note.withIndex())
                midi.noteOff(CANALE_VOCE_BASE + (k % PARTI_ARMONIA), n)
        }, 2000)
    }

    private fun provaLegato(ch: Int) {
        midi.cc(ch, 11, 100); midi.noteOn(ch, 72, 100)
        ui.postDelayed({ midi.noteOn(ch, 74, 100) }, 900)
        ui.postDelayed({ midi.noteOff(ch, 72) }, 950)
        ui.postDelayed({ midi.noteOff(ch, 74) }, 1900)
    }
}
