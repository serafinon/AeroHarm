package harmonizer.app

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock

/**
 * Clock degli effetti. Spec §14.3.
 *
 * L'armonizzatore si accontentava del tick dell'interfaccia a 40 ms, perche'
 * doveva solo accorgersi dei cambi di accordo. L'arpeggiatore no: a 120 bpm in
 * sestine uno step dura 21 ms, e con 40 ms di risoluzione la sestina non
 * esisterebbe.
 *
 * Quindi un thread proprio, a priorita' audio come quello del MIDI. Il periodo
 * lo decide [periodo] a ogni giro: fitto quando serve, largo quando non serve,
 * perche' cinquecento risvegli al secondo per stare a guardare la battuta
 * sono batteria buttata.
 *
 * Si pianifica su tempi assoluti ([Handler.postAtTime]) e non a intervalli,
 * altrimenti l'errore di ogni giro si sommerebbe; se si accumula ritardo non
 * si rincorre, si riparte da ora. Il Runnable e' uno solo e si ripianifica da
 * se': nel percorso caldo non si allocano nemmeno i risvegli.
 */
class OrologioFx(
    private val periodo: () -> Long,
    private val azione: (Long) -> Unit
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var vivo = false
    private var prossimo = 0L

    private val giro = object : Runnable {
        override fun run() {
            if (!vivo) return
            try { azione(System.nanoTime()) } catch (_: Exception) { }
            pianifica()
        }
    }

    fun start() {
        if (vivo) return
        vivo = true
        val t = HandlerThread("fx-clock", Process.THREAD_PRIORITY_URGENT_AUDIO)
        t.start()
        thread = t
        handler = Handler(t.looper)
        prossimo = SystemClock.uptimeMillis()
        pianifica()
    }

    fun stop() {
        vivo = false
        handler?.removeCallbacks(giro)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private fun pianifica() {
        val h = handler ?: return
        prossimo = maxOf(prossimo + periodo().coerceIn(1L, 100L), SystemClock.uptimeMillis())
        h.postAtTime(giro, prossimo)
    }
}
