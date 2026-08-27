package harmonizer.app

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Radice di una vista che riconosce il trascinamento orizzontale per passare
 * alla vista vicina. Le vicine della principale sono due: la **scaletta a
 * sinistra** (dito verso destra) e gli **effetti a destra** (dito verso
 * sinistra).
 *
 * Intercetta solo sotto [sogliaY], cioe' sotto il carosello: sopra, il
 * trascinamento appartiene al carosello e non deve essere rubato. E intercetta
 * solo se il movimento e' chiaramente orizzontale e in uno dei versi ammessi,
 * altrimenti i pulsanti sottostanti smetterebbero di rispondere.
 *
 * Il verso riconosciuto viene passato a [onInizio]: +1 dito verso destra,
 * -1 verso sinistra. Le distanze in [onTrascina] e [onRilascio] sono sempre
 * positive, misurate nel verso del gesto.
 */
class RadiceScorrevole(ctx: Context) : LinearLayout(ctx) {

    /** Sotto questa quota il gesto e' attivo. Sopra c'e' il carosello. */
    var sogliaY = 0f

    /** Versi ammessi: +1 = dito verso destra, -1 = verso sinistra. */
    var versi = intArrayOf(1)

    var onInizio: ((Int) -> Unit)? = null
    var onTrascina: ((Float) -> Unit)? = null
    var onRilascio: ((Float, Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L
    private var attivo = false
    private var versoInCorso = 0

    private fun registra(e: MotionEvent) {
        x0 = e.x; y0 = e.y; t0 = System.nanoTime()
        attivo = false; versoInCorso = 0
    }

    /** Vero se il movimento e' abbastanza orizzontale in un verso ammesso. */
    private fun provaAdIniziare(e: MotionEvent): Boolean {
        if (y0 <= sogliaY) return false
        val dy = e.y - y0
        for (v in versi) {
            val dx = (e.x - x0) * v
            if (dx > slop && dx > abs(dy) * 1.5f) {
                attivo = true
                versoInCorso = v
                onInizio?.invoke(v)
                return true
            }
        }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> registra(e)
            MotionEvent.ACTION_MOVE -> if (provaAdIniziare(e)) return true
        }
        return false
    }

    private fun corsa(x: Float) = ((x - x0) * versoInCorso).coerceAtLeast(0f)

    /**
     * Serve anche qui, e non solo nell'intercettazione: quando un figlio
     * rifiuta il tocco — il selettore dei gradi lo fa se il dito non parte su
     * una maniglia — il DOWN arriva direttamente a noi, e da quel momento
     * l'intercettazione non viene piu' consultata. Senza questo ramo, sfiorare
     * il selettore disattiverebbe il cambio di vista.
     */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { registra(e); return true }
            MotionEvent.ACTION_MOVE -> {
                if (!attivo) provaAdIniziare(e)
                if (attivo) { onTrascina?.invoke(corsa(e.x)); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (attivo) {
                    val dx = corsa(e.x)
                    val secondi = (System.nanoTime() - t0) / 1_000_000_000.0
                    val velocita = if (secondi > 0) (dx / secondi).toFloat() else 0f
                    attivo = false
                    onRilascio?.invoke(dx, velocita)
                    return true
                }
            }
        }
        return attivo
    }
}
