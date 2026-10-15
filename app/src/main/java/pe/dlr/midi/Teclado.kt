package pe.dlr.midi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Teclado de piano multitouch.
 * - Varios dedos a la vez.
 * - Deslizar el dedo pasa de una tecla a otra (glissando).
 * - Velocidad segun la altura del toque: arriba suave, abajo fuerte.
 */
class Teclado(context: Context, private val midi: Midi) : View(context) {

    /** Nota de la primera tecla (siempre un Do). 48 = C3 */
    var notaBase = 48
        set(v) { soltarTodo(); field = v.coerceIn(0, 108); invalidate() }

    var teclasBlancas = 15
        set(v) { soltarTodo(); field = v.coerceIn(7, 29); invalidate() }

    private val dedos = HashMap<Int, Int>()          // id del dedo -> nota sonando
    private val sonando = IntArray(128)              // cuantos dedos tienen cada nota

    private val pBlanca = Estilo.pincel(0xFFE9EAEC.toInt())
    private val pBlancaOn = Estilo.pincel(Estilo.ACENTO)
    private val pNegra = Estilo.pincel(0xFF15161A.toInt())
    private val pNegraOn = Estilo.pincel(0xFFC77526.toInt())
    private val pEtiqueta = Estilo.pincel(0xFF6B6F78.toInt(), 30f, true).apply { textAlign = android.graphics.Paint.Align.CENTER }
    private val r = RectF()

    // posicion de las teclas negras dentro de la octava (despues de que blanca) y su semitono
    private val negraTras = intArrayOf(0, 1, 3, 4, 5)       // Do#, Re#, Fa#, Sol#, La#
    private val negraSemi = intArrayOf(1, 3, 6, 8, 10)
    private val blancaSemi = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    private fun anchoBlanca() = width.toFloat() / teclasBlancas

    private fun notaBlanca(i: Int) = notaBase + (i / 7) * 12 + blancaSemi[i % 7]

    /** Que nota hay en (x, y). -1 si ninguna. */
    private fun notaEn(x: Float, y: Float): Int {
        val w = anchoBlanca()
        if (x < 0 || x >= width || y < 0 || y > height) return -1
        if (y < height * 0.6f) {
            // teclas negras primero (estan encima)
            val i = (x / w).toInt()
            for (lado in intArrayOf(i - 1, i)) {
                if (lado < 0) continue
                val k = negraTras.indexOf(lado % 7)
                if (k < 0) continue
                val cx = (lado + 1) * w
                if (x >= cx - w * 0.31f && x <= cx + w * 0.31f && lado + 1 < teclasBlancas) {
                    return notaBase + (lado / 7) * 12 + negraSemi[k]
                }
            }
        }
        return notaBlanca((x / w).toInt().coerceIn(0, teclasBlancas - 1))
    }

    private fun velocidad(y: Float): Int = (35 + 92 * (y / height).coerceIn(0f, 1f)).toInt()

    private fun encender(nota: Int, vel: Int) {
        if (nota !in 0..127) return
        if (sonando[nota] == 0) midi.notaOn(nota, vel)
        sonando[nota]++
    }

    private fun apagar(nota: Int) {
        if (nota !in 0..127 || sonando[nota] == 0) return
        sonando[nota]--
        if (sonando[nota] == 0) midi.notaOff(nota)
    }

    fun soltarTodo() {
        for (n in 0..127) if (sonando[n] > 0) { sonando[n] = 0; midi.notaOff(n) }
        dedos.clear()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val n = notaEn(e.getX(i), e.getY(i))
                if (n >= 0) { dedos[e.getPointerId(i)] = n; encender(n, velocidad(e.getY(i))) }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val n = notaEn(e.getX(i), e.getY(i))
                    val antes = dedos[id] ?: -1
                    if (n != antes) {
                        if (antes >= 0) apagar(antes)
                        if (n >= 0) { dedos[id] = n; encender(n, velocidad(e.getY(i))) } else dedos.remove(id)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                dedos.remove(id)?.let { apagar(it) }
            }
            MotionEvent.ACTION_CANCEL -> soltarTodo()
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = anchoBlanca()
        val h = height.toFloat()
        c.drawColor(Estilo.FONDO)
        for (i in 0 until teclasBlancas) {
            val n = notaBlanca(i)
            r.set(i * w + 2f, 0f, (i + 1) * w - 2f, h - 4f)
            c.drawRoundRect(r, 10f, 10f, if (n in 0..127 && sonando[n] > 0) pBlancaOn else pBlanca)
            if (i % 7 == 0) c.drawText("C" + (n / 12 - 1), (i + 0.5f) * w, h - 26f, pEtiqueta)
        }
        for (i in 0 until teclasBlancas - 1) {
            val k = negraTras.indexOf(i % 7)
            if (k < 0) continue
            val n = notaBase + (i / 7) * 12 + negraSemi[k]
            val cx = (i + 1) * w
            r.set(cx - w * 0.31f, 0f, cx + w * 0.31f, h * 0.6f)
            c.drawRoundRect(r, 8f, 8f, if (n in 0..127 && sonando[n] > 0) pNegraOn else pNegra)
        }
    }
}
