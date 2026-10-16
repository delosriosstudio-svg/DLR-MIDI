package pe.dlr.midi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Sonido interno de la app (sin computadora): sintetizador FM liviano.
 *  0 = Piano electrico (estilo Rhodes, calido con campana suave)
 *  1 = Caja de musica / pluck (corto y brillante)
 * Recibe los mismos mensajes MIDI que se mandan a REAPER.
 */
class Sintetizador {
    companion object { val SONIDOS = arrayOf("Piano eléctrico", "Caja de música / pluck") }

    @Volatile var sonido = 0
    @Volatile var volumen = 0.8f
    @Volatile var reverb = 0.25f

    private val sr: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).let { if (it in 22050..96000) it else 48000 }
    private var track: AudioTrack? = null
    private var hilo: Thread? = null
    @Volatile private var corriendo = false
    private val cola = ConcurrentLinkedQueue<IntArray>()

    // ---- tabla de seno (rapida)
    private val TN = 4096
    private val tabla = FloatArray(TN + 1) { kotlin.math.sin(2.0 * Math.PI * it / TN).toFloat() }
    private fun seno(fase: Float): Float {           // fase en ciclos (0..1 y mas)
        val x = (fase - kotlin.math.floor(fase)) * TN
        val i = x.toInt(); val f = x - i
        return tabla[i] + (tabla[i + 1] - tabla[i]) * f
    }

    // ---- voces
    private inner class Voz {
        var activa = false; var nota = 0; var edad = 0L
        var f = 0f; var fc = 0f; var fm = 0f; var ft = 0f    // fases (ciclos)
        var dc = 0f; var ratio = 1f
        var amp = 0f; var dec = 1f; var idx = 0f; var idxDec = 1f; var tine = 0f; var tineDec = 1f
        var ataque = 0; var ataqueN = 1
        var suelta = false; var sostenida = false; var rel = 1f
        var vel = 1f
    }
    private val voces = Array(24) { Voz() }
    private var reloj = 0L
    private var pedal = false
    private var bend = 1f
    private var vol7 = 1f
    private var vib = 0f; private var vibF = 0f

    fun midi(a: Int, b: Int, c: Int) { cola.add(intArrayOf(a, b, c)) }

    private fun procesar(m: IntArray) {
        val st = m[0] and 0xF0
        when {
            st == 0x90 && m[2] > 0 -> notaOn(m[1], m[2])
            st == 0x80 || (st == 0x90 && m[2] == 0) -> notaOff(m[1])
            st == 0xB0 -> when (m[1]) {
                64 -> { pedal = m[2] >= 64; if (!pedal) for (v in voces) if (v.activa && v.sostenida) { v.sostenida = false; v.suelta = true } }
                7 -> vol7 = m[2] / 127f
                1 -> vib = m[2] / 127f
                123, 120 -> for (v in voces) { if (v.activa) { v.suelta = true; v.sostenida = false } }
            }
            st == 0xE0 -> { val v = (m[1] or (m[2] shl 7)) - 8192; bend = 2f.pow(2f * v / 8192f / 12f) }
        }
    }

    private fun notaOn(n: Int, velocidad: Int) {
        // la misma nota sonando: se re-ataca en esa voz
        var v = voces.firstOrNull { it.activa && it.nota == n }
        if (v == null) v = voces.firstOrNull { !it.activa }
        if (v == null) v = voces.filter { it.suelta || it.sostenida }.minByOrNull { it.amp } ?: voces.minByOrNull { it.edad }!!
        val vel = velocidad / 127f
        val f = (440.0 * 2.0.pow((n - 69) / 12.0)).toFloat()
        v.activa = true; v.nota = n; v.edad = reloj++; v.f = f
        v.suelta = false; v.sostenida = false; v.vel = vel
        v.fc = 0f; v.fm = 0f; v.ft = 0f
        // notas agudas decaen mas rapido
        val alto = ((n - 36) / 60f).coerceIn(0f, 1f)
        if (sonido == 0) {     // PIANO ELECTRICO
            v.ratio = 1f
            v.amp = 0.28f * (0.35f + 0.65f * vel)
            v.dec = coef(4.5f - 3.2f * alto)              // caida larga
            v.idx = 0.6f + 2.2f * vel * vel               // mas fuerte = mas brillo
            v.idxDec = coef(0.6f)
            v.tine = if (f * 14f < sr * 0.4f) 0.35f * vel else 0f
            v.tineDec = coef(0.05f)
            v.rel = coef(0.25f)
            v.ataqueN = (0.002f * sr).toInt()
        } else {               // CAJA DE MUSICA / PLUCK
            v.ratio = 3.5f
            v.amp = 0.24f * (0.4f + 0.6f * vel)
            v.dec = coef(1.4f - 0.8f * alto)
            v.idx = 0.8f + 1.4f * vel
            v.idxDec = coef(0.25f)
            v.tine = if (f * 7f < sr * 0.4f) 0.25f * vel else 0f
            v.tineDec = coef(0.03f)
            v.rel = coef(0.12f)
            v.ataqueN = (0.001f * sr).toInt()
        }
        v.ataque = 0
    }

    private fun notaOff(n: Int) {
        for (v in voces) if (v.activa && v.nota == n && !v.suelta) { if (pedal) v.sostenida = true else v.suelta = true }
    }

    /** coeficiente por muestra para caer 60 dB en t segundos */
    private fun coef(t: Float) = exp(-6.9f / (t.coerceAtLeast(0.005f) * sr))

    // ---- reverb sencilla (4 peines + 2 pasatodo) por canal
    private inner class Rev(desfase: Int) {
        private val largos = intArrayOf(1116, 1188, 1277, 1356).map { ((it + desfase) * sr / 44100f).toInt() }
        private val peines = largos.map { FloatArray(it) }
        private val pos = IntArray(4)
        private val filt = FloatArray(4)
        private val ap = intArrayOf(556, 441).map { FloatArray(((it + desfase) * sr / 44100f).toInt()) }
        private val apPos = IntArray(2)
        fun paso(x: Float): Float {
            var s = 0f
            for (i in 0 until 4) {
                val b = peines[i]; val y = b[pos[i]]
                filt[i] = y * 0.75f + filt[i] * 0.25f
                b[pos[i]] = x + filt[i] * 0.80f
                pos[i] = (pos[i] + 1) % b.size
                s += y
            }
            for (i in 0 until 2) {
                val b = ap[i]; val y = b[apPos[i]]
                b[apPos[i]] = s + y * 0.5f
                s = y - s * 0.5f
                apPos[i] = (apPos[i] + 1) % b.size
            }
            return s * 0.25f
        }
    }
    private val revL = Rev(0)
    private val revR = Rev(23)

    // ---- audio
    fun encender() {
        if (corriendo) return
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(min)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        corriendo = true
        t.play()
        hilo = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val N = 128
            val buf = FloatArray(N * 2)
            while (corriendo) {
                render(buf, N)
                t.write(buf, 0, N * 2, AudioTrack.WRITE_BLOCKING)
            }
        }.apply { name = "DLR-sinte"; start() }
    }

    fun apagar() {
        corriendo = false
        try { hilo?.join(300) } catch (_: InterruptedException) { }
        hilo = null
        try { track?.stop() } catch (_: Exception) { }
        track?.release(); track = null
        for (v in voces) v.activa = false
        cola.clear()
    }

    val encendido get() = corriendo

    private fun render(buf: FloatArray, n: Int) {
        while (true) { val m = cola.poll() ?: break; procesar(m) }
        val inv = 1f / sr
        val mv = volumen * vol7
        val rv = reverb
        for (i in 0 until n) {
            // vibrato (CC1)
            vibF += 5.5f * inv
            val mod = if (vib > 0.01f) 1f + vib * 0.006f * seno(vibF) else 1f
            val pb = bend * mod
            var s = 0f
            for (v in voces) {
                if (!v.activa) continue
                val inc = v.f * pb * inv
                v.fc += inc; v.fm += inc * v.ratio; v.ft += inc * (if (sonido == 0) 14f else 7f)
                if (v.fc > 1000f) { v.fc -= 1000f; v.fm -= 1000f * v.ratio; v.ft = v.ft - kotlin.math.floor(v.ft) }
                var a = v.amp
                if (v.ataque < v.ataqueN) { a *= v.ataque.toFloat() / v.ataqueN; v.ataque++ }
                val m1 = v.idx * seno(v.fm)
                var x = seno(v.fc + m1 * 0.159f) * a
                if (v.tine > 0.0005f) { x += seno(v.ft) * v.tine * a; v.tine *= v.tineDec }
                s += x
                v.amp *= if (v.suelta) v.rel else v.dec
                v.idx *= v.idxDec
                if (v.amp < 0.00005f) v.activa = false
            }
            s *= mv
            val l = s + revL.paso(s) * rv
            val r = s + revR.paso(s) * rv
            buf[2 * i] = tanh(l)
            buf[2 * i + 1] = tanh(r)
        }
    }
}
