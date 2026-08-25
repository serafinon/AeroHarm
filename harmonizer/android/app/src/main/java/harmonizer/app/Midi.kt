package harmonizer.app

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * Livello MIDI Android. Spec §10.
 *
 * L'AE-20 con USB Driver = Generic è class-compliant: nessun driver.
 * Su un dispositivo USB MIDI, dal punto di vista dell'app:
 *   openInputPort()  -> ci si SCRIVE, va verso lo strumento
 *   openOutputPort() -> ci si LEGGE,  arriva dallo strumento
 *
 * NON si trasmette mai Active Sensing (FEH): se l'AE-20 lo riceve e poi
 * passano più di 420 ms senza messaggi, esegue da solo All Sound Off,
 * All Notes Off e Reset All Controllers. Spec §2.6.
 */
class MidiEngine(
    private val context: Context,
    private val onEvent: (MidiEvent) -> Unit,
    private val onLog: (String) -> Unit
) : MidiOut {

    data class MidiEvent(
        val status: Int, val ch: Int, val d1: Int, val d2: Int, val nanos: Long
    )

    private val manager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private var device: MidiDevice? = null
    private var toDevice: MidiInputPort? = null
    private var fromDevice: MidiOutputPort? = null

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private val outBuf = ByteArray(3)
    private val outLock = Any()

    var connectedName: String? = null
        private set

    // ------------------------------------------------------------- apertura

    fun availableDevices(): List<MidiDeviceInfo> =
        manager?.devices?.toList() ?: emptyList()

    fun start() {
        thread = HandlerThread("midi-io", Process.THREAD_PRIORITY_URGENT_AUDIO)
        thread.start()
        handler = Handler(thread.looper)
    }

    fun stop() {
        try { toDevice?.close(); fromDevice?.close(); device?.close() } catch (_: Exception) {}
        toDevice = null; fromDevice = null; device = null
        connectedName = null
        if (::thread.isInitialized) thread.quitSafely()
    }

    /** Apre il primo dispositivo con almeno un ingresso e un'uscita. */
    fun openFirst() {
        val mm = manager ?: run { onLog("MidiManager non disponibile"); return }
        val infos = mm.devices
        if (infos.isEmpty()) { onLog("Nessun dispositivo MIDI collegato"); return }
        val info = infos.firstOrNull { it.inputPortCount > 0 && it.outputPortCount > 0 }
            ?: infos.first()
        open(info)
    }

    fun open(info: MidiDeviceInfo) {
        val mm = manager ?: return
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI"
        mm.openDevice(info, { dev ->
            if (dev == null) { onLog("Apertura fallita: $name"); return@openDevice }
            device = dev
            connectedName = name
            try {
                if (info.inputPortCount > 0) toDevice = dev.openInputPort(0)
                if (info.outputPortCount > 0) {
                    fromDevice = dev.openOutputPort(0)
                    fromDevice?.connect(Receiver())
                }
                onLog("Collegato: $name  (in ${info.inputPortCount} / out ${info.outputPortCount})")
            } catch (e: Exception) {
                onLog("Errore porte: ${e.message}")
            }
        }, handler)
    }

    // ------------------------------------------------------------- ricezione

    private inner class Receiver : MidiReceiver() {
        private var status = 0
        private var d1 = -1

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val now = System.nanoTime()
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                i++
                if (b >= 0xF8) continue                 // realtime: ignorato
                if (b >= 0x80) {                        // nuovo status
                    if (b in 0xF0..0xF7) { status = 0; d1 = -1; continue }
                    status = b; d1 = -1; continue
                }
                if (status == 0) continue
                val type = status and 0xF0
                val ch = (status and 0x0F) + 1
                when (type) {
                    0xC0, 0xD0 -> {                     // un solo byte dati
                        onEvent(MidiEvent(type, ch, b, 0, now))
                    }
                    else -> {
                        if (d1 < 0) { d1 = b } else {
                            onEvent(MidiEvent(type, ch, d1, b, now))
                            d1 = -1
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- invio

    private fun send(b0: Int, b1: Int, b2: Int, len: Int) {
        val port = toDevice ?: return
        synchronized(outLock) {
            outBuf[0] = b0.toByte(); outBuf[1] = b1.toByte(); outBuf[2] = b2.toByte()
            try { port.send(outBuf, 0, len) } catch (_: Exception) {}
        }
    }

    override fun noteOn(ch: Int, note: Int, vel: Int) =
        send(0x90 or ((ch - 1) and 0x0F), note and 0x7F, vel.coerceIn(1, 127), 3)

    override fun noteOff(ch: Int, note: Int) =
        send(0x80 or ((ch - 1) and 0x0F), note and 0x7F, 0, 3)

    override fun cc(ch: Int, num: Int, value: Int) =
        send(0xB0 or ((ch - 1) and 0x0F), num and 0x7F, value.coerceIn(0, 127), 3)

    override fun pitchBend(ch: Int, value14: Int) {
        val v = value14.coerceIn(0, 16383)
        send(0xE0 or ((ch - 1) and 0x0F), v and 0x7F, (v shr 7) and 0x7F, 3)
    }

    override fun aftertouch(ch: Int, value: Int) =
        send(0xD0 or ((ch - 1) and 0x0F), value.coerceIn(0, 127), 0, 2)

    override fun programChange(ch: Int, pc: Int) =
        send(0xC0 or ((ch - 1) and 0x0F), (pc - 1).coerceIn(0, 127), 0, 2)

    /** Richiamo di scena dell'AE-20: Bank Select + Program Change. */
    fun selezionaScena(ch: Int, msb: Int, lsb: Int, numero: Int) {
        cc(ch, 0, msb); cc(ch, 32, lsb); programChange(ch, numero)
    }

    /** Stato noto all'avvio: spec §8.7. */
    fun resetChannels(channels: IntArray) {
        for (c in channels) { cc(c, 123, 0); cc(c, 121, 0) }
    }
}
