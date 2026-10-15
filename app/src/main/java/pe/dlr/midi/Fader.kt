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
 * Fader vertical MUY suave.
 * - Es relativo: no salta a donde pones el dedo; sigue el movimiento desde ahi.
 * - Usa todos los puntos del toque (incluidos los intermedios) y suaviza el valor,
 *   asi aunque el dedo se trabe sobre la pantalla, el fader avanza parejo.
 * - Toca la etiqueta de arriba para elegir el CC.
 */
class Fader(
    context: Context,
    private val midi: Midi,
    var numero: Int,                       // CC
    private val color: Int,
    private val alElegirCC: (Fader) -> Unit,
) : View(context), Choreographer.FrameCallback {

    var objetivo = 0f                      // hacia donde va el dedo (0..1)
        private set
    var valor = 0f                         // lo que suena (0..1), suavizado
        private set
    private var enviado = -1
    private var ultY = 0f
    private var tocando = false
    private var enEtiqueta = false
    private var animando = false
    private var tAnterior = 0L

    /** cuanto tarda en alcanzar al dedo (segundos). Mas alto = mas suave. */
    var suavidad = 0.06f

    private val pRiel = Estilo.pincel(0xFF0E0F12.toInt())
    private val pLleno = Estilo.pincel(color)
    private val pTapa = Estilo.pincel(0xFFD9DBE0.toInt())
    private val pRaya = Estilo.pincel(0xFF2A2C31.toInt()).apply { strokeWidth = 4f }
    private val pTexto = Estilo.pincel(Estilo.TEXTO, 34f, true).apply { textAlign = Paint.Align.CENTER }
    private val pTenue = Estilo.pincel(Estilo.TENUE, 26f).apply { textAlign = Paint.Align.CENTER }
    private val pCaja = Estilo.pincel(Estilo.PANEL)
    private val r = RectF()

    private val altoEtiqueta get() = 110f
    private val altoValor get() = 70f
    private val y0 get() = altoEtiqueta + 30f
    private val y1 get() = height - altoValor - 30f

    fun fijar(v: Float, mandar: Boolean = true) {
        objetivo = v.coerceIn(0f, 1f); valor = objetivo
        if (mandar) enviar() else enviado = (valor * 127f + 0.5f).toInt()
        invalidate()
    }

    private fun enviar() {
        val v = (valor * 127f + 0.5f).toInt().coerceIn(0, 127)
        if (v != enviado) { midi.cc(numero, v); enviado = v }
    }

    fun reenviar() { enviado = -1; enviar() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                enEtiqueta = e.y < altoEtiqueta
                tocando = !enEtiqueta
                ultY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (tocando) {
                val recorrido = (y1 - y0).coerceAtLeast(100f)
                // todos los puntos intermedios: el movimiento llega completo aunque el dedo se trabe
                for (h in 0 until e.historySize) { mover(e.getHistoricalY(h), recorrido) }
                mover(e.y, recorrido)
                arrancar()
            }
            MotionEvent.ACTION_UP -> {
                if (enEtiqueta && e.y < altoEtiqueta) alElegirCC(this)
                tocando = false; enEtiqueta = false
            }
            MotionEvent.ACTION_CANCEL -> { tocando = false; enEtiqueta = false }
        }
        return true
    }

    private fun mover(y: Float, recorrido: Float) {
        objetivo = (objetivo + (ultY - y) / recorrido).coerceIn(0f, 1f)
        ultY = y
    }

    private fun arrancar() {
        if (!animando) { animando = true; tAnterior = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun doFrame(t: Long) {
        val dt = if (tAnterior == 0L) 0.008f else ((t - tAnterior) / 1e9f).coerceIn(0.001f, 0.05f)
        tAnterior = t
        val a = 1f - exp(-dt / suavidad.coerceAtLeast(0.005f))
        valor += (objetivo - valor) * a
        if (abs(objetivo - valor) < 0.0008f) valor = objetivo
        enviar()
        invalidate()
        if (valor != objetivo || tocando) Choreographer.getInstance().postFrameCallback(this) else animando = false
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        // etiqueta (CC)
        r.set(6f, 6f, w - 6f, altoEtiqueta - 6f)
        c.drawRoundRect(r, 14f, 14f, pCaja)
        c.drawText("CC $numero", cx, 52f, pTexto)
        c.drawText(Cc.nombreCorto(numero), cx, 88f, pTenue)
        // riel
        val rw = 16f
        r.set(cx - rw / 2, y0, cx + rw / 2, y1)
        c.drawRoundRect(r, 8f, 8f, pRiel)
        val yv = y1 - (y1 - y0) * valor
        r.set(cx - rw / 2, yv, cx + rw / 2, y1)
        c.drawRoundRect(r, 8f, 8f, pLleno)
        // tapa
        val tw = (w * 0.62f).coerceAtMost(150f)
        r.set(cx - tw / 2, yv - 34f, cx + tw / 2, yv + 34f)
        c.drawRoundRect(r, 12f, 12f, pTapa)
        c.drawLine(cx - tw / 2 + 12f, yv, cx + tw / 2 - 12f, yv, pRaya)
        // valor
        c.drawText("${(valor * 127f + 0.5f).toInt()}", cx, height - 30f, pTexto)
    }
}

/** Rueda de pitch bend vertical: al soltarla vuelve sola al centro (imantada). */
class RuedaPitch(context: Context, private val midi: Midi) : View(context), Choreographer.FrameCallback {
    private var objetivo = 0f
    private var valor = 0f
    private var yInicio = 0f
    private var tocando = false
    private var animando = false
    private var tAnterior = 0L
    private var enviado = Float.NaN

    private val pFondo = Estilo.pincel(0xFF0E0F12.toInt())
    private val pRueda = Estilo.pincel(0xFF3A3D44.toInt())
    private val pRaya = Estilo.pincel(0xFF24262B.toInt()).apply { strokeWidth = 3f }
    private val pCentro = Estilo.pincel(Estilo.AZUL).apply { strokeWidth = 6f }
    private val pTexto = Estilo.pincel(Estilo.TEXTO, 30f, true).apply { textAlign = Paint.Align.CENTER }
    private val pTenue = Estilo.pincel(Estilo.TENUE, 24f).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    private val y0 get() = 140f
    private val y1 get() = height - 100f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tocando = true; yInicio = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                // relativo al punto donde pusiste el dedo: siempre empieza en el centro
                objetivo = ((yInicio - e.y) / ((y1 - y0) / 2f)).coerceIn(-1f, 1f)
                arrancar()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tocando = false; objetivo = 0f; arrancar() }
        }
        return true
    }

    private fun arrancar() {
        if (!animando) { animando = true; tAnterior = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun doFrame(t: Long) {
        val dt = if (tAnterior == 0L) 0.008f else ((t - tAnterior) / 1e9f).coerceIn(0.001f, 0.05f)
        tAnterior = t
        val tau = if (tocando) 0.025f else 0.07f       // al soltar vuelve al centro suave pero rapido
        valor += (objetivo - valor) * (1f - exp(-dt / tau))
        if (abs(objetivo - valor) < 0.0005f) valor = objetivo
        if (valor != enviado) { midi.pitchBend(valor); enviado = valor }
        invalidate()
        if (valor != objetivo || tocando) Choreographer.getInstance().postFrameCallback(this) else animando = false
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val cx = w / 2f
        c.drawText("PITCH", cx, 52f, pTexto)
        c.drawText("vuelve al 0", cx, 88f, pTenue)
        val rw = (w * 0.7f).coerceAtMost(120f)
        r.set(cx - rw / 2, y0, cx + rw / 2, y1)
        c.drawRoundRect(r, 24f, 24f, pFondo)
        // rueda: rayas que se desplazan con el valor
        val mid = (y0 + y1) / 2f
        val desplaz = -valor * (y1 - y0) * 0.35f
        r.set(cx - rw / 2 + 8f, y0 + 8f, cx + rw / 2 - 8f, y1 - 8f)
        c.drawRoundRect(r, 18f, 18f, pRueda)
        c.save(); c.clipRect(r)
        var y = mid + desplaz % 28f - 28f * 40
        while (y < y1 + 28f) { if (y > y0) c.drawLine(r.left, y, r.right, y, pRaya); y += 28f }
        c.restore()
        c.drawLine(cx - rw / 2 - 10f, mid, cx - rw / 2 + 2f, mid, pCentro)
        c.drawLine(cx + rw / 2 - 2f, mid, cx + rw / 2 + 10f, mid, pCentro)
        val yi = mid - valor * (y1 - y0) / 2f * 0.9f
        c.drawLine(r.left + 6f, yi, r.right - 6f, yi, pCentro)
        val v = (valor * 8191f).toInt()
        c.drawText(if (v > 0) "+$v" else "$v", cx, height - 40f, pTexto)
    }
}
