package pe.dlr.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import java.io.IOException

/**
 * Conexion MIDI por cable USB.
 * Con el telefono en modo USB "MIDI", Android crea un puerto USB y el Mac lo ve
 * como un teclado MIDI normal. Esta clase abre ese puerto y manda los mensajes.
 */
class Midi(context: Context, private val alCambiar: (texto: String, conectado: Boolean) -> Unit) {

    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val handler = Handler(Looper.getMainLooper())
    private var device: MidiDevice? = null
    private var info: MidiDeviceInfo? = null
    private var port: MidiInputPort? = null
    private var abriendo = false
    private val buf = ByteArray(3)

    /** Canal MIDI global (1..16). */
    var canal = 1

    val conectado: Boolean get() = port != null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) { buscar() }
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (device.id == info?.id) cerrar()
            buscar()
        }
    }

    fun iniciar() {
        if (manager == null) { alCambiar("Este telefono no tiene MIDI", false); return }
        manager.registerDeviceCallback(callback, handler)
        buscar()
    }

    fun detener() {
        manager?.unregisterDeviceCallback(callback)
        panico()
        cerrar()
    }

    private fun cerrar() {
        try { port?.close() } catch (_: IOException) {}
        try { device?.close() } catch (_: IOException) {}
        port = null; device = null; info = null
    }

    @Suppress("DEPRECATION")
    private fun buscar() {
        val m = manager ?: return
        if (port != null || abriendo) return
        val usb = m.devices.firstOrNull { it.type == MidiDeviceInfo.TYPE_USB && it.inputPortCount > 0 }
        if (usb == null) {
            alCambiar("Sin conexion: conecta el cable y elige USB > MIDI", false)
            return
        }
        abriendo = true
        alCambiar("Conectando...", false)
        m.openDevice(usb, { d ->
            abriendo = false
            if (d == null) { alCambiar("No se pudo abrir el puerto USB MIDI", false); return@openDevice }
            val p = d.openInputPort(0)
            if (p == null) {
                try { d.close() } catch (_: IOException) {}
                alCambiar("El puerto USB MIDI esta ocupado", false)
                return@openDevice
            }
            device = d; info = usb; port = p
            alCambiar("Conectado por USB", true)
        }, handler)
    }

    private fun enviar(a: Int, b: Int, c: Int, n: Int = 3) {
        val p = port ?: return
        synchronized(buf) {
            buf[0] = a.toByte(); buf[1] = b.toByte(); buf[2] = c.toByte()
            try { p.send(buf, 0, n) } catch (_: IOException) { }
        }
    }

    private val ch get() = (canal - 1).coerceIn(0, 15)

    fun notaOn(nota: Int, vel: Int) { if (nota in 0..127) enviar(0x90 or ch, nota, vel.coerceIn(1, 127)) }
    fun notaOff(nota: Int) { if (nota in 0..127) enviar(0x80 or ch, nota, 0) }
    fun cc(numero: Int, valor: Int) { enviar(0xB0 or ch, numero.coerceIn(0, 127), valor.coerceIn(0, 127)) }

    /** valor -1..+1 (0 = centro) */
    fun pitchBend(valor: Float) {
        val v = (8192 + valor.coerceIn(-1f, 1f) * 8191f).toInt().coerceIn(0, 16383)
        enviar(0xE0 or ch, v and 0x7F, (v shr 7) and 0x7F)
    }

    /** Apaga todo en los 16 canales (por si queda una nota colgada). */
    fun panico() {
        for (c in 0 until 16) {
            enviar(0xB0 or c, 64, 0)
            enviar(0xB0 or c, 123, 0)
        }
    }
}
