package pe.dlr.midi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Boton simple dibujado a mano (se ve igual en cualquier telefono). */
open class Boton(context: Context, var texto: String, var alTocar: () -> Unit = {}) : View(context) {
    var activo = false
        set(v) { field = v; invalidate() }
    var colorActivo = Estilo.ACENTO
    var tamTexto = 34f
    /** true = solo texto, sin caja (etiquetas) */
    var soloTexto = false
    protected var apretado = false
    protected val r = RectF()
    private val pFondo = Estilo.pincel(Estilo.PANEL)
    private val pBorde = Estilo.pincel(Estilo.BORDE).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pTexto = Estilo.pincel(Estilo.TEXTO, 34f, true).apply { textAlign = Paint.Align.CENTER }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { apretado = true; invalidate() }
            MotionEvent.ACTION_UP -> {
                val dentro = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                apretado = false; invalidate()
                if (dentro) alTocar()
            }
            MotionEvent.ACTION_CANCEL -> { apretado = false; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        r.set(4f, 4f, width - 4f, height - 4f)
        if (soloTexto) {
            pTexto.textSize = tamTexto; pTexto.color = Estilo.TENUE
            val lineas = texto.split("\n"); val alto = tamTexto * 1.15f
            var y = height / 2f - (lineas.size - 1) * alto / 2f - (pTexto.ascent() + pTexto.descent()) / 2f
            for ((i, l) in lineas.withIndex()) { pTexto.color = if (i == lineas.size - 1) Estilo.TEXTO else Estilo.TENUE; c.drawText(l, width / 2f, y, pTexto); y += alto }
            return
        }
        pFondo.color = when {
            activo -> colorActivo
            apretado -> Estilo.BORDE
            else -> Estilo.PANEL
        }
        c.drawRoundRect(r, 14f, 14f, pFondo)
        c.drawRoundRect(r, 14f, 14f, pBorde)
        pTexto.textSize = tamTexto
        pTexto.color = if (activo) Estilo.FONDO else Estilo.TEXTO
        val lineas = texto.split("\n")
        val alto = pTexto.textSize * 1.15f
        var y = height / 2f - (lineas.size - 1) * alto / 2f - (pTexto.ascent() + pTexto.descent()) / 2f
        for (l in lineas) { c.drawText(l, width / 2f, y, pTexto); y += alto }
    }
}

/**
 * Pedal de sustain (CC64).
 * - Mantener presionado = pedal pisado; al soltar se levanta.
 * - Doble toque = queda ENGANCHADO. Un toque mas lo suelta.
 */
class Sustain(context: Context, private val midi: Midi) : Boton(context, "SUSTAIN") {
    private var enganchado = false
    private var soltarAlLevantar = false
    private var ultimoToque = 0L

    init { tamTexto = 40f }

    private fun mandar(on: Boolean) { midi.cc(64, if (on) 127 else 0); activo = on }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ahora = System.currentTimeMillis()
                if (enganchado) {
                    soltarAlLevantar = true            // el siguiente toque lo libera
                } else {
                    if (ahora - ultimoToque < 320) { enganchado = true; soltarAlLevantar = false }
                    mandar(true)
                }
                ultimoToque = ahora
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (enganchado && soltarAlLevantar) { enganchado = false; soltarAlLevantar = false; mandar(false) }
                else if (!enganchado) mandar(false)
            }
        }
        texto = if (enganchado) "SUSTAIN\nENGANCHADO" else "SUSTAIN"
        tamTexto = if (enganchado) 30f else 40f
        invalidate()
        return true
    }

    fun reiniciar() { enganchado = false; soltarAlLevantar = false; texto = "SUSTAIN"; tamTexto = 40f; mandar(false) }
}

/** Boton que repite su accion mientras se mantiene presionado (flechas). */
class Repetir(context: Context, texto: String, private val accion: () -> Unit) : Boton(context, texto) {
    private val repetir = object : Runnable {
        override fun run() { accion(); postDelayed(this, 110) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { apretado = true; accion(); postDelayed(repetir, 400) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { apretado = false; removeCallbacks(repetir) }
        }
        invalidate()
        return true
    }
}

/**
 * Valor que se ajusta arrastrando el dedo (arriba/derecha = sube), sin abrir ventanas.
 * Doble toque = valor por defecto.
 */
class Valor(
    context: Context,
    private val etiqueta: String,
    private val min: Int, private val max: Int, private val defecto: Int,
    private val formato: (Int) -> String,
    private val alCambiar: (Int) -> Unit,
) : Boton(context, "") {
    var valor = defecto
        set(v) { field = v.coerceIn(min, max); actualizar() }
    private var acum = 0f
    private var x0 = 0f
    private var y0 = 0f
    private var ultimoToque = 0L

    init { colorActivo = Estilo.AZUL; actualizar() }

    private fun actualizar() { texto = etiqueta + "\n" + formato(valor); invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                apretado = true; x0 = e.x; y0 = e.y; acum = 0f
                val ahora = System.currentTimeMillis()
                if (ahora - ultimoToque < 300) { valor = defecto; alCambiar(valor) }
                ultimoToque = ahora
            }
            MotionEvent.ACTION_MOVE -> {
                // 6 px por paso; vale arrastrar hacia arriba o hacia la derecha
                acum += ((e.x - x0) + (y0 - e.y)) / 6f
                x0 = e.x; y0 = e.y
                val pasos = acum.toInt()
                if (pasos != 0) { acum -= pasos; val v = (valor + pasos).coerceIn(min, max); if (v != valor) { valor = v; alCambiar(v) } }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> apretado = false
        }
        invalidate()
        return true
    }
}
