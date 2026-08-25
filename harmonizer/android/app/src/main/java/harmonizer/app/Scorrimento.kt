package harmonizer.app

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Radice della vista principale che riconosce il trascinamento orizzontale per
 * richiamare la scaletta, che sta **a sinistra**.
 *
 * Intercetta solo sotto [sogliaY], cioe' sotto il carosello: sopra, il
 * trascinamento appartiene al carosello e non deve essere rubato. E intercetta
 * solo se il movimento e' chiaramente orizzontale e verso destra, altrimenti i
 * pulsanti sottostanti smetterebbero di rispondere.
 */
class RadiceScorrevole(ctx: Context) : LinearLayout(ctx) {

    /** Sotto questa quota il gesto e' attivo. Sopra c'e' il carosello. */
    var sogliaY = 0f

    /** +1 = si trascina verso destra, -1 = verso sinistra. */
    var verso = 1

    var onInizio: (() -> Unit)? = null
    var onTrascina: ((Float) -> Unit)? = null
    var onRilascio: ((Float, Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L
    private var attivo = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; t0 = System.nanoTime(); attivo = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (y0 <= sogliaY) return false
                val dx = (e.x - x0) * verso
                val dy = e.y - y0
                // orizzontale, nel verso giusto, e piu' orizzontale che verticale
                if (dx > slop && dx > abs(dy) * 1.5f) {
                    attivo = true
                    onInizio?.invoke()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (attivo) {
                    onTrascina?.invoke(((e.x - x0) * verso).coerceAtLeast(0f)); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (attivo) {
                    val dx = ((e.x - x0) * verso).coerceAtLeast(0f)
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
