package pe.dlr.midi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.exp

/**
 * Teclado de piano multitouch.
 * - Varios dedos a la vez; deslizar pasa de una tecla a otra (glissando).
 * - Velocity: la pone el Interprete (VEL + HUMANIZE).
 * - Escala de ayuda: ilumina sus notas con nombre y grado; BLOQUEO = las demas no suenan.
 * - CC DEDO: subir/bajar el dedo sobre la tecla manda un CC (relativo, el ultimo dedo que se mueve).
 */
class Teclado(context: Context, private val midi: Midi, private val interp: Interprete) : View(context), Choreographer.FrameCallback {

    /** indice absoluto de la primera tecla blanca (0 = C-1). 28 = C3 */
    var primeraBlanca = 28
        set(v) { soltarTodo(); field = v.coerceIn(0, ULTIMA_BLANCA - teclasBlancas + 1); invalidate() }

    var teclasBlancas = 15
        set(v) { soltarTodo(); field = v.coerceIn(7, 29); invalidate() }

    /** tonica 0..11 (Do..Si), -1 = sin escala */
    var tonica = -1
        set(v) { field = v; recalcular() }
    var escala = 0
        set(v) { field = v; recalcular() }
    var bloqueo = false
        set(v) { soltarTodo(); field = v; invalidate() }

    /** CC que controla la altura del dedo; -1 = apagado */
    var ccDedo = -1
        set(v) { field = v; enviadoCC = -1; invalidate() }
    var valorCC = 0f                         // 0..1 (suavizado)
        private set
    private var objetivoCC = 0f
    private var enviadoCC = -1
    var alCambiarCC: (Int) -> Unit = {}

    private val dedos = HashMap<Int, Int>()          // id del dedo -> nota sonando
    private val ultY = HashMap<Int, Float>()         // id del dedo -> ultima altura (para el CC)
    private val sonando = IntArray(128)
    private var grados = Escalas.grados(0)
    private var intervalos = Escalas.escala(0).intervalos

    private val pBlanca = Estilo.pincel(0xFFE9EAEC.toInt())
    private val pBlancaOff = Estilo.pincel(0xFF8E9096.toInt())
    private val pBlancaOn = Estilo.pincel(Estilo.ACENTO)
    private val pNegra = Estilo.pincel(0xFF15161A.toInt())
    private val pNegraOn = Estilo.pincel(0xFFC77526.toInt())
    private val pEtiqueta = Estilo.pincel(0xFF6B6F78.toInt(), 26f, true).apply { textAlign = Paint.Align.CENTER }
    private val pBlancaEsc = Estilo.pincel(0xFFF3DEC4.toInt())
    private val pBlancaTon = Estilo.pincel(0xFFF0B97E.toInt())
    private val pNegraEsc = Estilo.pincel(0xFF5C4430.toInt())
    private val pNegraTon = Estilo.pincel(0xFF94602F.toInt())
    private val pPunto = Estilo.pincel(Estilo.ACENTO)
    private val pNombre = Estilo.pincel(0xFF7A5230.toInt(), 30f, true).apply { textAlign = Paint.Align.CENTER }
    private val pGrado = Estilo.pincel(0xFF3B2A1C.toInt(), 34f, true).apply { textAlign = Paint.Align.CENTER }
    private val pNombreN = Estilo.pincel(0xFFF3DEC4.toInt(), 24f, true).apply { textAlign = Paint.Align.CENTER }
    private val pGradoN = Estilo.pincel(0xFFFFFFFF.toInt(), 26f, true).apply { textAlign = Paint.Align.CENTER }
    private val pMedidor = Estilo.pincel(Estilo.AZUL)
    private val pMedidorF = Estilo.pincel(0x66000000)
    private val r = RectF()

    companion object {
        const val ULTIMA_BLANCA = 74                 // G9 = nota 127
        private val BLANCA_SEMI = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        fun notaDeBlanca(a: Int) = (a / 7) * 12 + BLANCA_SEMI[a % 7]
        /** hay tecla negra a la derecha de esta blanca? (C D F G A) */
        fun tieneNegra(a: Int) = (a % 7) in intArrayOf(0, 1, 3, 4, 5)
        fun nombreBlanca(a: Int): String = arrayOf("C", "D", "E", "F", "G", "A", "B")[a % 7] + (a / 7 - 1)
    }

    private fun recalcular() {
        soltarTodo()
        grados = Escalas.grados(escala)
        intervalos = Escalas.escala(escala).intervalos
        invalidate()
    }

    /** grado de la nota en la escala (0 = tonica), -1 si no esta o no hay escala */
    private fun grado(nota: Int): Int {
        if (tonica < 0) return -1
        return intervalos.indexOf(((nota - tonica) % 12 + 12) % 12)
    }

    private fun bloqueada(nota: Int) = bloqueo && tonica >= 0 && grado(nota) < 0

    private fun anchoBlanca() = width.toFloat() / teclasBlancas

    /** Que nota hay en (x, y). -1 si ninguna (o bloqueada). */
    private fun notaEn(x: Float, y: Float): Int {
        val w = anchoBlanca()
        if (x < 0 || x >= width || y < 0 || y > height) return -1
        var n = -1
        if (y < height * 0.6f) {
            val i = (x / w).toInt()
            for (lado in intArrayOf(i - 1, i)) {
                if (lado < 0 || lado >= teclasBlancas - 1) continue
                val a = primeraBlanca + lado
                if (!tieneNegra(a)) continue
                val cx = (lado + 1) * w
                if (x >= cx - w * 0.31f && x <= cx + w * 0.31f) { n = notaDeBlanca(a) + 1; break }
            }
        }
        if (n < 0) n = notaDeBlanca(primeraBlanca + (x / w).toInt().coerceIn(0, teclasBlancas - 1))
        if (n > 127 || bloqueada(n)) return -1
        return n
    }

    private fun encender(nota: Int) {
        if (nota !in 0..127) return
        if (sonando[nota] == 0) interp.on(nota)
        sonando[nota]++
    }

    private fun apagar(nota: Int) {
        if (nota !in 0..127 || sonando[nota] == 0) return
        sonando[nota]--
        if (sonando[nota] == 0) interp.off(nota)
    }

    fun soltarTodo() {
        for (n in 0..127) if (sonando[n] > 0) { sonando[n] = 0; interp.off(n) }
        dedos.clear(); ultY.clear()
        invalidate()
    }

    fun fijarCC(v: Float) { objetivoCC = v.coerceIn(0f, 1f); valorCC = objetivoCC; enviadoCC = -1 }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val n = notaEn(e.getX(i), e.getY(i))
                if (n >= 0) {
                    dedos[e.getPointerId(i)] = n; ultY[e.getPointerId(i)] = e.getY(i)
                    encender(n)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                var mejorDy = 0f
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val antes = dedos[id] ?: -1
                    if (ccDedo >= 0) {
                        // con CC DEDO el dedo se queda en su nota; su altura mueve el CC
                        val y0 = ultY[id] ?: continue
                        val dy = y0 - e.getY(i)
                        if (abs(dy) > abs(mejorDy)) mejorDy = dy   // manda el dedo que mas se mueve
                        ultY[id] = e.getY(i)
                        continue
                    }
                    val n = notaEn(e.getX(i), e.getY(i))
                    if (n != antes) {
                        if (antes >= 0) apagar(antes)
                        if (n >= 0) { dedos[id] = n; encender(n) } else dedos.remove(id)
                    }
                }
                if (ccDedo >= 0 && mejorDy != 0f) {
                    objetivoCC = (objetivoCC + mejorDy / (height * 0.75f)).coerceIn(0f, 1f)
                    arrancar()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                dedos.remove(id)?.let { apagar(it) }
                ultY.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> soltarTodo()
        }
        invalidate()
        return true
    }

    // ---- suavizado del CC (igual que los faders)
    private var animando = false
    private var tAnterior = 0L
    private fun arrancar() {
        if (!animando) { animando = true; tAnterior = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun doFrame(t: Long) {
        val dt = if (tAnterior == 0L) 0.008f else ((t - tAnterior) / 1e9f).coerceIn(0.001f, 0.05f)
        tAnterior = t
        valorCC += (objetivoCC - valorCC) * (1f - exp(-dt / 0.05f))
        if (abs(objetivoCC - valorCC) < 0.0008f) valorCC = objetivoCC
        val v = (valorCC * 127f + 0.5f).toInt().coerceIn(0, 127)
        if (v != enviadoCC && ccDedo >= 0) { midi.cc(ccDedo, v); enviadoCC = v; alCambiarCC(v) }
        invalidate()
        if (valorCC != objetivoCC || dedos.isNotEmpty()) Choreographer.getInstance().postFrameCallback(this) else animando = false
    }

    override fun onDraw(c: Canvas) {
        val w = anchoBlanca()
        val h = height.toFloat()
        c.drawColor(Estilo.FONDO)
        for (i in 0 until teclasBlancas) {
            val a = primeraBlanca + i
            val n = notaDeBlanca(a)
            val g = grado(n)
            r.set(i * w + 2f, 0f, (i + 1) * w - 2f, h - 4f)
            val p = when {
                n in 0..127 && sonando[n] > 0 -> pBlancaOn
                bloqueada(n) -> pBlancaOff
                g == 0 -> pBlancaTon
                g > 0 -> pBlancaEsc
                else -> pBlanca
            }
            c.drawRoundRect(r, 10f, 10f, p)
            val cx = (i + 0.5f) * w
            if (a % 7 == 0) c.drawText(nombreBlanca(a), cx, h * 0.6f + 40f, pEtiqueta)
            if (g >= 0) {
                c.drawCircle(cx, h - 140f, if (g == 0) 13f else 8f, pPunto)
                c.drawText(grados[g], cx, h - 82f, pGrado)
                c.drawText(Escalas.nombreNota(n % 12, tonica, escala), cx, h - 32f, pNombre)
            }
        }
        for (i in 0 until teclasBlancas - 1) {
            val a = primeraBlanca + i
            if (!tieneNegra(a)) continue
            val n = notaDeBlanca(a) + 1
            val g = grado(n)
            val cx = (i + 1) * w
            r.set(cx - w * 0.31f, 0f, cx + w * 0.31f, h * 0.6f)
            val p = when {
                n in 0..127 && sonando[n] > 0 -> pNegraOn
                g == 0 -> pNegraTon
                g > 0 -> pNegraEsc
                else -> pNegra
            }
            c.drawRoundRect(r, 8f, 8f, p)
            if (g >= 0) {
                c.drawCircle(cx, h * 0.6f - 108f, if (g == 0) 11f else 7f, pPunto)
                c.drawText(grados[g], cx, h * 0.6f - 62f, pGradoN)
                c.drawText(Escalas.nombreNota(n % 12, tonica, escala), cx, h * 0.6f - 24f, pNombreN)
            }
        }
        // medidor del CC DEDO (borde derecho)
        if (ccDedo >= 0) {
            r.set(width - 14f, 8f, width - 4f, h - 8f); c.drawRoundRect(r, 5f, 5f, pMedidorF)
            r.set(width - 14f, h - 8f - (h - 16f) * valorCC, width - 4f, h - 8f); c.drawRoundRect(r, 5f, 5f, pMedidor)
        }
    }
}
