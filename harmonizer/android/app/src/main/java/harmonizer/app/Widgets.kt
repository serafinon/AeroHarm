package harmonizer.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import harmonizer.core.ChordStep
import kotlin.math.*

/**
 * Pulsante quadrato a simbolo disegnato, con etichetta minuscola nell'angolo
 * che ricorda a quale controllo fisico dell'AE-20 è associato.
 */
class SymbolButton(ctx: Context, private val simbolo: Sym, private val tag: String?) : View(ctx) {

    enum class Sym { ARMONIA, ARMONIA_MUTA, BATTUTA_1, STOP, TAP }

    var attivo = false
        set(v) { field = v; invalidate() }

    var testoSotto: String? = null
    var onTap: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rr = RectF()
    private var premuto = false

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) * 0.16f

        // sfondo
        rr.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.color = when {
            premuto -> Pal.surf2
            simbolo == Sym.STOP -> Color.parseColor("#241618")
            attivo -> Pal.surf2
            else -> Pal.surf
        }
        c.drawRoundRect(rr, r, r, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = when {
            simbolo == Sym.STOP -> Color.parseColor("#8A3A3A")
            attivo -> Pal.acc
            else -> Pal.rule
        }
        c.drawRoundRect(rr, r, r, p)

        val cx = w / 2f
        val cy = h / 2f - (if (testoSotto != null) h * 0.07f else 0f)
        val s = min(w, h) * 0.30f

        p.style = Paint.Style.FILL
        when (simbolo) {
            Sym.ARMONIA, Sym.ARMONIA_MUTA -> disegnaArmonia(c, cx, cy, s, simbolo == Sym.ARMONIA_MUTA)
            Sym.BATTUTA_1 -> disegnaBattuta1(c, cx, cy, s)
            Sym.STOP -> disegnaStop(c, cx, cy, s)
            Sym.TAP -> disegnaTap(c, cx, cy, s)
        }

        testoSotto?.let {
            p.color = if (attivo) Pal.acc else Pal.dim
            p.textAlign = Paint.Align.CENTER
            p.textSize = if (simbolo == Sym.TAP) min(w * 0.075f, h * 0.16f)
                         else min(w, h) * 0.13f
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(it, cx, h - h * 0.12f, p)
        }

        // etichetta del controllo fisico, minuscola, in alto a destra
        tag?.let {
            p.color = Pal.dim
            p.textAlign = Paint.Align.RIGHT
            p.textSize = min(w, h) * 0.095f
            p.typeface = Typeface.MONOSPACE
            c.drawText(it, w - min(w, h) * 0.11f, h * 0.16f, p)
        }
    }

    /** Tre voci sovrapposte; con la sbarra quando è muta. */
    private fun disegnaArmonia(c: Canvas, cx: Float, cy: Float, s: Float, muta: Boolean) {
        p.color = if (muta) Pal.warn else Pal.acc
        val hh = s * 0.20f
        val larghezze = floatArrayOf(1.0f, 0.72f, 0.46f)
        for ((i, k) in larghezze.withIndex()) {
            val y = cy - s * 0.55f + i * s * 0.55f
            rr.set(cx - s * k, y - hh / 2, cx + s * k, y + hh / 2)
            c.drawRoundRect(rr, hh / 2, hh / 2, p)
        }
        if (muta) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = s * 0.16f
            p.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx - s * 0.95f, cy + s * 0.8f, cx + s * 0.95f, cy - s * 0.9f, p)
            p.style = Paint.Style.FILL
        }
    }

    /** Ritorno all'inizio: sbarra più due triangoli, come sui riproduttori. */
    private fun disegnaBattuta1(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.color = Pal.acc
        val b = s * 0.22f
        rr.set(cx - s * 0.95f, cy - s * 0.8f, cx - s * 0.95f + b, cy + s * 0.8f)
        c.drawRoundRect(rr, b * 0.3f, b * 0.3f, p)
        val path = Path()
        for (k in 0..1) {
            val x0 = cx - s * 0.55f + k * s * 0.72f
            path.reset()
            path.moveTo(x0 + s * 0.7f, cy - s * 0.8f)
            path.lineTo(x0 + s * 0.7f, cy + s * 0.8f)
            path.lineTo(x0 - s * 0.05f, cy)
            path.close()
            c.drawPath(path, p)
        }
    }

    /** Quadrato rosso di stop. */
    private fun disegnaStop(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.color = Color.parseColor("#E4534B")
        val q = s * 0.82f
        rr.set(cx - q, cy - q, cx + q, cy + q)
        c.drawRoundRect(rr, q * 0.18f, q * 0.18f, p)
    }

    /** Il TAP occupa lo spazio che ha: si dimensiona da solo. */
    private fun disegnaTap(c: Canvas, cx: Float, cy: Float, s: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = Pal.acc
        p.textAlign = Paint.Align.CENTER
        p.textSize = min(w * 0.24f, h * 0.44f)
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("TAP", cx, cy + p.textSize * 0.34f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { premuto = true; invalidate(); return true }
            MotionEvent.ACTION_UP -> { premuto = false; invalidate(); onTap?.invoke(); return true }
            MotionEvent.ACTION_CANCEL -> { premuto = false; invalidate(); return true }
        }
        return super.onTouchEvent(e)
    }
}

/**
 * Carosello orizzontale degli accordi. La scheda corrente e' evidenziata e
 * resta centrata; si scorre trascinando il dito, un tap salta al passo.
 *
 * Dentro ogni scheda i quadratini sono i **quarti**: raggruppati per battuta
 * secondo il tempo del pezzo, e **mandati a capo ogni due battute**. Un accordo
 * da quattro battute in 4/4 mostra quindi due file da otto.
 */
class ChordCarousel(ctx: Context) : View(ctx) {

    var steps: List<ChordStep> = emptyList()
    var indiceCorrente = 0
    var quartiTrascorsi = 0
    /** Movimenti per battuta: 3, 4 o 5. */
    var battiti = 4
    var onJump: ((Int) -> Unit)? = null
    /** Pressione lunga su una scheda: apre la modifica di quell'accordo. */
    var onModifica: ((Int) -> Unit)? = null
    /** Tocco sulla scheda "+" in coda. */
    var onAggiungi: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rr = RectF()
    private var offset = 0f
    private var target = 0f

    private val CARD_W = 210f
    private val GAP = 16f
    private val BATTUTE_PER_FILA = 2

    private var trascinando = false
    private var manuale = false
    private var indiceToccato = -1
    private var lungaScattata = false
    private var xIniziale = 0f
    private var xPrecedente = 0f
    private var tempoDown = 0L
    private var ultimoTocco = 0L
    private val slop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop }

    private fun larghezzaScheda() = CARD_W * resources.displayMetrics.density * 0.5f
    private fun distanza() = GAP * resources.displayMetrics.density * 0.5f
    /** La scheda "+" in coda e' piu' stretta delle altre. */
    private fun larghezzaPiu() = larghezzaScheda() * 0.42f

    private fun scorrimentoMassimo(): Float {
        val cw = larghezzaScheda(); val g = distanza()
        val totale = steps.size * (cw + g) + larghezzaPiu()
        return (totale - width).coerceAtLeast(0f)
    }

    private val attesaLunga = Runnable {
        if (!trascinando && indiceToccato in steps.indices) {
            lungaScattata = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onModifica?.invoke(indiceToccato)
        }
    }

    fun aggiorna(nuoviSteps: List<ChordStep>, corrente: Int, quartiNelPasso: Int) {
        steps = nuoviSteps
        indiceCorrente = corrente
        quartiTrascorsi = quartiNelPasso
        if (manuale && System.nanoTime() - ultimoTocco > 4_000_000_000L) manuale = false
        if (!manuale) {
            val cw = larghezzaScheda(); val g = distanza()
            target = (corrente * (cw + g) - width / 2f + cw / 2f)
                .coerceIn(0f, scorrimentoMassimo())
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.bg)
        if (steps.isEmpty()) return

        val cw = larghezzaScheda()
        val g = distanza()
        val h = height.toFloat()
        val ch = h * 0.86f
        val top = (h - ch) / 2f

        offset += (target - offset) * 0.25f
        if (abs(target - offset) > 0.5f) postInvalidateOnAnimation()

        for ((i, s) in steps.withIndex()) {
            val x = i * (cw + g) - offset
            if (x + cw < 0 || x > width) continue
            val attiva = i == indiceCorrente

            rr.set(x, top, x + cw, top + ch)
            p.style = Paint.Style.FILL
            p.color = if (attiva) Pal.acc else Pal.surf
            c.drawRoundRect(rr, 20f, 20f, p)
            if (!attiva) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Pal.rule
                c.drawRoundRect(rr, 20f, 20f, p)
                p.style = Paint.Style.FILL
            }

            // nome dell'accordo: dimensione di prima, ridotta solo se sfora
            p.color = if (attiva) Pal.bg else Pal.fg
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = ch * 0.34f
            val nome = s.chord.toString()
            val largoNome = p.measureText(nome)
            if (largoNome > cw * 0.86f) p.textSize = ch * 0.34f * (cw * 0.86f) / largoNome
            c.drawText(nome, x + cw / 2f, top + ch * 0.44f, p)

            // scala: stessa regola, con un minimo sotto il quale si rinuncia
            p.typeface = Typeface.DEFAULT
            p.textSize = ch * 0.115f
            p.color = if (attiva) Color.parseColor("#0E2C2B") else Pal.dim
            val nomeScala = s.scale.name
            val largoScala = p.measureText(nomeScala)
            if (largoScala > cw * 0.90f) p.textSize = ch * 0.115f * (cw * 0.90f) / largoScala
            if (p.textSize > ch * 0.070f) c.drawText(nomeScala, x + cw / 2f, top + ch * 0.60f, p)

            disegnaQuarti(c, s, attiva, x, top, cw, ch)
        }

        // scheda "+" in coda: aggiunge un accordo
        val xPiu = steps.size * (cw + g) - offset
        val wPiu = larghezzaPiu()
        if (xPiu + wPiu >= 0 && xPiu <= width) {
            rr.set(xPiu, top, xPiu + wPiu, top + ch)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Pal.rule
            c.drawRoundRect(rr, 20f, 20f, p)

            p.style = Paint.Style.FILL
            p.color = Pal.dim
            val cxP = xPiu + wPiu / 2f
            val cyP = top + ch / 2f
            val braccio = wPiu * 0.22f
            val spess = wPiu * 0.075f
            rr.set(cxP - braccio, cyP - spess / 2, cxP + braccio, cyP + spess / 2)
            c.drawRoundRect(rr, spess / 2, spess / 2, p)
            rr.set(cxP - spess / 2, cyP - braccio, cxP + spess / 2, cyP + braccio)
            c.drawRoundRect(rr, spess / 2, spess / 2, p)
        }
    }

    /**
     * Quadratini dei quarti: raggruppati per battuta secondo il tempo, e a capo
     * ogni due battute. Sotto la scheda attiva si accendono quelli trascorsi.
     */
    private fun disegnaQuarti(
        c: Canvas, s: ChordStep, attiva: Boolean,
        x: Float, top: Float, cw: Float, ch: Float
    ) {
        val perFila = battiti * BATTUTE_PER_FILA
        // oltre quattro file si passa a un quadratino per battuta: piu' di
        // cosi' non ci starebbero nella scheda
        val perQuarto = s.quarti <= perFila * 4
        val n = if (perQuarto) s.quarti.coerceAtLeast(1)
                else (s.quarti / battiti).coerceIn(1, perFila)
        val trascorse = if (perQuarto) quartiTrascorsi else quartiTrascorsi / battiti
        val elementiPerFila = if (perQuarto) perFila else perFila
        val file = ((n + elementiPerFila - 1) / elementiPerFila).coerceAtLeast(1)

        val tw = (cw * 0.048f).coerceIn(3f, 8f)
        val g1 = tw * 0.55f
        val g2 = tw * 1.5f
        fun stacco(indiceInFila: Int) =
            if (perQuarto && (indiceInFila + 1) % battiti == 0) g2 else g1

        // le file dei quarti stanno sotto il nome della scala; l'insieme si
        // centra nello spazio residuo, cosi' una sola fila non resta in alto
        val altezzaFila = ch * 0.045f
        val spazioFila = ch * 0.022f
        val fileTot = ((n + perFila - 1) / perFila).coerceAtLeast(1)
        val altezzaBlocco = fileTot * altezzaFila + (fileTot - 1) * spazioFila
        val yPartenza = top + ch * 0.72f + (ch * 0.24f - altezzaBlocco) / 2f

        for (f in 0 until file) {
            val primo = f * elementiPerFila
            val quanti = (n - primo).coerceAtMost(elementiPerFila)
            if (quanti <= 0) break

            var tot = 0f
            for (k in 0 until quanti) {
                tot += tw
                if (k < quanti - 1) tot += stacco(k)
            }
            var tx = x + (cw - tot) / 2f
            val ty = yPartenza + f * (altezzaFila + spazioFila)

            for (k in 0 until quanti) {
                val idx = primo + k
                val acceso = attiva && idx <= trascorse
                p.color = when {
                    attiva && acceso -> Pal.bg
                    attiva -> Color.parseColor("#2E8C8A")
                    else -> Pal.rule
                }
                rr.set(tx, ty, tx + tw, ty + altezzaFila)
                c.drawRoundRect(rr, tw * 0.32f, tw * 0.32f, p)
                tx += tw + stacco(k)
            }
        }
    }

    /** Indice sotto il dito; [steps].size indica la scheda "+". */
    private fun indiceA(px: Float): Int {
        val cw = larghezzaScheda(); val g = distanza()
        val pos = px + offset
        val i = (pos / (cw + g)).toInt()
        if (i in steps.indices) return i
        val xPiu = steps.size * (cw + g)
        if (pos >= xPiu && pos <= xPiu + larghezzaPiu()) return steps.size
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val ora = System.nanoTime()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                xIniziale = e.x; xPrecedente = e.x
                tempoDown = ora; trascinando = false; lungaScattata = false
                indiceToccato = indiceA(e.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(attesaLunga)
                if (indiceToccato in steps.indices) postDelayed(attesaLunga, 500)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trascinando && abs(e.x - xIniziale) > slop) {
                    trascinando = true
                    removeCallbacks(attesaLunga)
                }
                if (trascinando) {
                    offset = (offset - (e.x - xPrecedente)).coerceIn(0f, scorrimentoMassimo())
                    target = offset
                    manuale = true
                    ultimoTocco = ora
                    invalidate()
                }
                xPrecedente = e.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(attesaLunga)
                val durataMs = (ora - tempoDown) / 1_000_000
                if (!trascinando && !lungaScattata && durataMs < 350) {
                    when (val i = indiceA(e.x)) {
                        in steps.indices -> { manuale = false; onJump?.invoke(i) }
                        steps.size -> onAggiungi?.invoke()
                    }
                }
                ultimoTocco = ora
                trascinando = false; lungaScattata = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(attesaLunga)
                trascinando = false; lungaScattata = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
}
