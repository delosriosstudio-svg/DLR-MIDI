package pe.dlr.midi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Mini piano para ESCRIBIR un acorde: tocar una tecla la marca (y suena mientras la tocas);
 * tocarla otra vez la desmarca. Muestra 2 octavas.
 */
class MiniPiano(context: Context, private val midi: Midi, private val vel: () -> Int, private val alCambiar: () -> Unit) : View(context) {
    val marcadas = sortedSetOf<Int>()
    /** indice de la primera blanca (28 = C3) */
    var primeraBlanca = 28
        set(v) { field = v.coerceIn(0, Teclado.ULTIMA_BLANCA - BLANCAS + 1); invalidate() }
    private val BLANCAS = 14
    private val dedos = HashMap<Int, Int>()

    private val pB = Estilo.pincel(0xFFE9EAEC.toInt())
    private val pBm = Estilo.pincel(Estilo.ACENTO)
    private val pN = Estilo.pincel(0xFF15161A.toInt())
    private val pNm = Estilo.pincel(0xFFC77526.toInt())
    private val pEt = Estilo.pincel(0xFF6B6F78.toInt(), 24f, true).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    private fun notaEn(x: Float, y: Float): Int {
        val w = width.toFloat() / BLANCAS
        if (y < height * 0.6f) {
            val i = (x / w).toInt()
            for (lado in intArrayOf(i - 1, i)) {
                if (lado < 0 || lado >= BLANCAS - 1) continue
                val a = primeraBlanca + lado
                if (!Teclado.tieneNegra(a)) continue
                val cx = (lado + 1) * w
                if (x >= cx - w * 0.31f && x <= cx + w * 0.31f) return Teclado.notaDeBlanca(a) + 1
            }
        }
        return Teclado.notaDeBlanca(primeraBlanca + (x / w).toInt().coerceIn(0, BLANCAS - 1))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val n = notaEn(e.getX(i), e.getY(i))
                if (n in 0..127) {
                    if (!marcadas.remove(n)) marcadas.add(n)
                    midi.notaOn(n, vel()); dedos[e.getPointerId(i)] = n
                    alCambiar()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                dedos.remove(e.getPointerId(e.actionIndex))?.let { midi.notaOff(it) }
            }
            MotionEvent.ACTION_CANCEL -> { for (n in dedos.values) midi.notaOff(n); dedos.clear() }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat() / BLANCAS
        val h = height.toFloat()
        for (i in 0 until BLANCAS) {
            val a = primeraBlanca + i
            val n = Teclado.notaDeBlanca(a)
            r.set(i * w + 2f, 0f, (i + 1) * w - 2f, h - 2f)
            c.drawRoundRect(r, 8f, 8f, if (n in marcadas) pBm else pB)
            if (a % 7 == 0) c.drawText(Teclado.nombreBlanca(a), (i + 0.5f) * w, h - 16f, pEt)
        }
        for (i in 0 until BLANCAS - 1) {
            val a = primeraBlanca + i
            if (!Teclado.tieneNegra(a)) continue
            val n = Teclado.notaDeBlanca(a) + 1
            val cx = (i + 1) * w
            r.set(cx - w * 0.31f, 0f, cx + w * 0.31f, h * 0.6f)
            c.drawRoundRect(r, 6f, 6f, if (n in marcadas) pNm else pN)
        }
    }
}
