package harmonizer.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import harmonizer.core.Spelling
import kotlin.math.*

/**
 * Selettore a circolo delle quinte, a tutto schermo.
 *
 * Due corone concentriche divise in dodici blocchi:
 *   esterna  maggiori          C  G  D  A  E  B  F#  C#  Ab  Eb  Bb  F
 *   interna  relative minori   a  e  b  f# c# g# eb  bb  f   c   g   d
 *
 * Maggiore e relativa minore condividono la posizione angolare, quindi il
 * raggio da solo sceglie il modo: un unico trascinamento seleziona
 * fondamentale e modo insieme. Scorrendo il dito il blocco si evidenzia e
 * si ingrandisce; si conferma al rilascio.
 */
class CircleOfFifthsView(context: Context) : View(context) {

    /** Chiamata al rilascio del dito: (fondamentale 0-11, minore) */
    var onPick: ((Int, Boolean) -> Unit)? = null

    var selectedRoot: Int = 10
    var selectedMinor: Boolean = false

    private var hoverIndex = -1
    private var hoverMinor = false

    private val bg = Color.parseColor("#101413")
    private val majBase = Color.parseColor("#1B2A2A")
    private val majText = Color.parseColor("#9DBAB9")
    private val majHot = Color.parseColor("#6AC7C4")
    private val minBase = Color.parseColor("#2A2119")
    private val minText = Color.parseColor("#C2A484")
    private val minHot = Color.parseColor("#DEA359")
    private val ink = Color.parseColor("#101413")
    private val dim = Color.parseColor("#7C8683")
    private val fg = Color.parseColor("#E9EDE9")

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#101413"); strokeWidth = 3f
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val rect = RectF()
    private val path = Path()

    private val SECTORS = 12
    private val SWEEP = 360f / SECTORS

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)

        val cx = width / 2f
        val cy = height / 2f
        val R = min(width, height) / 2f * 0.94f

        val rHole = R * 0.30f
        val rMid = R * 0.63f
        val rOut = R * 0.96f

        for (i in 0 until SECTORS) {
            val pcMaj = Spelling.CIRCLE_ORDER[i]
            val pcMin = Spelling.relativeMinor(pcMaj)

            // corona interna: relative minori
            val hotMin = (hoverIndex == i && hoverMinor)
            val selMin = (selectedMinor && selectedRoot == pcMin && hoverIndex < 0)
            drawSector(canvas, cx, cy, rHole, rMid, i,
                if (hotMin || selMin) minHot else minBase,
                if (hotMin) 1.10f else 1f)

            // corona esterna: maggiori
            val hotMaj = (hoverIndex == i && !hoverMinor)
            val selMaj = (!selectedMinor && selectedRoot == pcMaj && hoverIndex < 0)
            drawSector(canvas, cx, cy, rMid, rOut, i,
                if (hotMaj || selMaj) majHot else majBase,
                if (hotMaj) 1.06f else 1f)

            // etichette
            val a = Math.toRadians((i * SWEEP - 90f).toDouble())
            val rl1 = (rMid + rOut) / 2f
            val rl2 = (rHole + rMid) / 2f

            pText.color = if (hotMaj || selMaj) ink else majText
            pText.textSize = if (hotMaj) R * 0.115f else R * 0.088f
            canvas.drawText(Spelling.rootName(pcMaj, false),
                cx + (rl1 * cos(a)).toFloat(),
                cy + (rl1 * sin(a)).toFloat() + pText.textSize * 0.35f, pText)

            pText.color = if (hotMin || selMin) ink else minText
            pText.textSize = if (hotMin) R * 0.095f else R * 0.072f
            canvas.drawText(Spelling.rootName(pcMin, true).lowercase() + "-",
                cx + (rl2 * cos(a)).toFloat(),
                cy + (rl2 * sin(a)).toFloat() + pText.textSize * 0.35f, pText)
        }

        // centro: la scelta corrente
        pFill.color = Color.parseColor("#181D1C")
        canvas.drawCircle(cx, cy, rHole * 0.92f, pFill)

        val nome = Spelling.rootName(selectedRoot, selectedMinor) + if (selectedMinor) "-" else ""
        pText.color = fg
        pText.textSize = rHole * 0.52f
        canvas.drawText(nome, cx, cy + pText.textSize * 0.34f, pText)

        pText.color = dim
        pText.textSize = rHole * 0.20f
        canvas.drawText(if (selectedMinor) "minore" else "maggiore", cx, cy + rHole * 0.62f, pText)
    }

    private fun drawSector(
        c: Canvas, cx: Float, cy: Float, rIn: Float, rOut: Float,
        index: Int, color: Int, grow: Float
    ) {
        val start = index * SWEEP - 90f - SWEEP / 2f
        val ri = rIn
        val ro = rOut * grow

        path.reset()
        rect.set(cx - ro, cy - ro, cx + ro, cy + ro)
        path.arcTo(rect, start, SWEEP)
        rect.set(cx - ri, cy - ri, cx + ri, cy + ri)
        path.arcTo(rect, start + SWEEP, -SWEEP)
        path.close()

        pFill.color = color
        c.drawPath(path, pFill)
        c.drawPath(path, pLine)
    }

    // ------------------------------------------------------------- tocco

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val R = min(width, height) / 2f * 0.94f
        val rHole = R * 0.30f
        val rMid = R * 0.63f
        val rOut = R * 1.02f

        val dx = e.x - cx
        val dy = e.y - cy
        val dist = hypot(dx, dy)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (dist < rHole || dist > rOut) { hoverIndex = -1; invalidate(); return true }
                var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f
                if (deg < 0) deg += 360f
                hoverIndex = (((deg + SWEEP / 2f) % 360f) / SWEEP).toInt() % SECTORS
                hoverMinor = dist < rMid
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (hoverIndex >= 0) {
                    val pcMaj = Spelling.CIRCLE_ORDER[hoverIndex]
                    val root = if (hoverMinor) Spelling.relativeMinor(pcMaj) else pcMaj
                    selectedRoot = root
                    selectedMinor = hoverMinor
                    onPick?.invoke(root, hoverMinor)
                }
                hoverIndex = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { hoverIndex = -1; invalidate(); return true }
        }
        return super.onTouchEvent(e)
    }
}
