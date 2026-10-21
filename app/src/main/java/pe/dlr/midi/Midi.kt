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
 * Conexion MIDI por cable USB o por Wi-Fi.
 * - USB: con el telefono en modo USB "MIDI", el Mac lo ve como un teclado MIDI normal.
 * - Wi-Fi: "Red MIDI" de Apple (ver Wifi.kt). Se usa solo cuando no hay cable,
 *   para que REAPER no reciba cada nota dos veces.
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

    /** sonido interno: tambien recibe todo lo que se manda */
    var sinte: Sintetizador? = null
    @Volatile var sonar = false

    private val wifi = Wifi(context) { t, _ -> estadoWifi(t) }
    private var textoUsb = ""

    val conectado: Boolean get() = port != null || wifi.conectado
    private val porUsb: Boolean get() = port != null

    /** el estado que se muestra: el cable manda; si no hay cable, el Wi-Fi */
    private fun informar() {
        when {
            porUsb -> alCambiar("Conectado por USB", true)
            wifi.conectado -> alCambiar("Conectado por Wi-Fi · " + wifi.nombrePar, true)
            else -> alCambiar(ultimoWifi ?: textoUsb, false)
        }
    }
    private var ultimoWifi: String? = null
    private fun estadoWifi(t: String?) { ultimoWifi = if (wifi.conectado) null else t; informar() }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) { buscar() }
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (device.id == info?.id) cerrar()
            buscar()
        }
    }

    fun iniciar() {
        if (manager == null) { alCambiar("Este telefono no tiene MIDI", false); wifi.iniciar(); return }
        manager.registerDeviceCallback(callback, handler)
        buscar()
        wifi.iniciar()
    }

    fun detener() {
        manager?.unregisterDeviceCallback(callback)
        panico()
        cerrar()
        wifi.detener()
    }

    private fun cerrar() {
        try { port?.close() } catch (_: IOException) {}
        try { device?.close() } catch (_: IOException) {}
        port = null; device = null; info = null
        informar()
    }

    @Suppress("DEPRECATION")
    private fun buscar() {
        val m = manager ?: return
        if (port != null || abriendo) return
        val usb = m.devices.firstOrNull { it.type == MidiDeviceInfo.TYPE_USB && it.inputPortCount > 0 }
        if (usb == null) {
            textoUsb = "Sin conexion: cable USB > MIDI, o Red MIDI por Wi-Fi"
            informar()
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
            informar()
        }, handler)
    }

    private fun enviar(a: Int, b: Int, c: Int, n: Int = 3) {
        if (sonar) sinte?.midi(a and 0xFF, b and 0x7F, c and 0x7F)
        synchronized(buf) {
            buf[0] = a.toByte(); buf[1] = b.toByte(); buf[2] = c.toByte()
            crudo(buf, n)
        }
    }

    /** manda bytes tal cual por la conexion activa (USB si hay cable; si no, Wi-Fi) */
    private fun crudo(b: ByteArray, n: Int) {
        val p = port
        if (p != null) { try { p.send(b, 0, n) } catch (_: IOException) { } }
        else if (wifi.conectado) wifi.enviar(b, n)
    }

    /** cambio de preset del instrumento (1..128) en el canal de la app */
    fun programa(numero: Int) {
        synchronized(buf) {
            buf[0] = (0xC0 or ch).toByte(); buf[1] = (numero - 1).coerceIn(0, 127).toByte()
            crudo(buf, 2)
        }
    }

    /** transporte: MMC (Play, Stop, Grabar, Inicio) */
    fun mmc(orden: Int) {
        val m = when (orden) {
            0x44 -> byteArrayOf(0xF0.toByte(), 0x7F, 0x7F, 0x06, 0x44, 0x06, 0x01, 0, 0, 0, 0, 0, 0xF7.toByte())  // ir a 00:00:00:00
            else -> byteArrayOf(0xF0.toByte(), 0x7F, 0x7F, 0x06, orden.toByte(), 0xF7.toByte())
        }
        crudo(m, m.size)
    }

    /** CC en un canal concreto (1..16), sin pasar por el sonido interno */
    fun ccEnCanal(canalMidi: Int, numero: Int, valor: Int) {
        val c = (canalMidi - 1).coerceIn(0, 15)
        synchronized(buf) {
            buf[0] = (0xB0 or c).toByte(); buf[1] = numero.coerceIn(0, 127).toByte(); buf[2] = valor.coerceIn(0, 127).toByte()
            crudo(buf, 3)
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
