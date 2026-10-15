package pe.dlr.midi

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Random

/**
 * El "interprete": recibe las notas que tocas y las manda a REAPER con
 * una velocity base (VEL) y una humanizacion (HUMANIZE) de velocity y tiempo.
 * - La velocity varia como una mano real: las notas seguidas se parecen entre si
 *   (deriva lenta) + una pequena variacion propia de cada nota.
 * - El tiempo se corre unos milisegundos (hasta ~12 ms al 100 %).
 */
class Interprete(private val midi: Midi) {

    /** velocity base 1..127 */
    var velBase = 100
    /** 0..1 */
    var humanize = 0f

    private val rnd = Random()
    private val handler = Handler(Looper.getMainLooper())
    private var deriva = 0.0                       // la "mano": cambia lento entre notas
    private val pendiente = arrayOfNulls<Runnable>(128)   // notas que esperan su retraso
    private val saleEn = LongArray(128)            // cuando sale (o salio) el note-on

    private fun velocidad(): Int {
        val h = humanize.coerceIn(0f, 1f).toDouble()
        if (h <= 0.0) return velBase.coerceIn(1, 127)
        deriva = deriva * 0.8 + rnd.nextGaussian() * 0.6          // deriva suave
        val propia = rnd.nextGaussian() * 0.5
        val v = velBase + h * 22.0 * (deriva + propia).coerceIn(-2.0, 2.0) / 2.0 * 1.6
        return Math.round(v).toInt().coerceIn(1, 127)
    }

    private fun retrasoMs(): Long {
        val h = humanize.coerceIn(0f, 1f)
        if (h <= 0f) return 0L
        return (rnd.nextFloat() * 12f * h).toLong()
    }

    fun on(nota: Int) {
        if (nota !in 0..127) return
        val vel = velocidad()
        val d = retrasoMs()
        pendiente[nota]?.let { handler.removeCallbacks(it) }
        if (d <= 0L) { pendiente[nota] = null; saleEn[nota] = SystemClock.uptimeMillis(); midi.notaOn(nota, vel); return }
        val r = Runnable { pendiente[nota] = null; midi.notaOn(nota, vel) }
        pendiente[nota] = r
        saleEn[nota] = SystemClock.uptimeMillis() + d
        handler.postDelayed(r, d)
    }

    fun off(nota: Int) {
        if (nota !in 0..127) return
        val ahora = SystemClock.uptimeMillis()
        if (pendiente[nota] != null && saleEn[nota] > ahora) {
            // la nota todavia no salio: el note-off sale justo despues de ella
            handler.postAtTime({ midi.notaOff(nota) }, saleEn[nota] + 1)
        } else midi.notaOff(nota)
    }
}
