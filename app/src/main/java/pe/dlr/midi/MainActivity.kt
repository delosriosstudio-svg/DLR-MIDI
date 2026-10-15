package pe.dlr.midi

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * DLR MIDI — controlador MIDI para REAPER.
 * Pestanas: TECLADO (horizontal) y FADERS (vertical).
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var midi: Midi
    private lateinit var estado: Estado
    private lateinit var contenido: FrameLayout
    private lateinit var pagTeclado: View
    private lateinit var pagFaders: View
    private lateinit var teclado: Teclado
    private lateinit var sustain: Sustain
    private lateinit var etiquetaOct: Boton
    private lateinit var btnEscala: Boton
    private lateinit var btnCanal: Boton
    private lateinit var tabTeclado: Boton
    private lateinit var tabFaders: Boton
    private val faders = ArrayList<Fader>()
    private var pestana = 0

    private val COLORES = intArrayOf(Estilo.ACENTO, Estilo.AZUL, Estilo.VERDE, 0xFFC882E8.toInt())
    private val CC_DEFECTO = intArrayOf(1, 11, 7, 74)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dlr_midi", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        estado = Estado(this)
        midi = Midi(this) { texto, ok -> estado.fijar(texto, ok) }
        midi.canal = prefs.getInt("canal", 1)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Estilo.FONDO)
        }

        // ---- barra superior: estado | canal | pestanas
        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(12, 8, 12, 4) }
        btnCanal = Boton(this, "") { elegirCanal() }.apply { tamTexto = 24f }
        tabTeclado = Boton(this, "TECLADO") { mostrar(0) }.apply { tamTexto = 24f }
        tabFaders = Boton(this, "FADERS") { mostrar(1) }.apply { tamTexto = 24f }
        barra.addView(estado, LinearLayout.LayoutParams(0, dp(34), 2f))
        barra.addView(btnCanal, LinearLayout.LayoutParams(0, dp(34), 0.7f))
        barra.addView(tabTeclado, LinearLayout.LayoutParams(0, dp(34), 0.7f))
        barra.addView(tabFaders, LinearLayout.LayoutParams(0, dp(34), 0.7f))
        raiz.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        contenido = FrameLayout(this)
        raiz.addView(contenido, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        pagTeclado = crearPaginaTeclado()
        pagFaders = crearPaginaFaders()
        contenido.addView(pagTeclado)
        contenido.addView(pagFaders)

        setContentView(raiz)
        actualizarCanal()
        mostrar(prefs.getInt("pestana", 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ TECLADO
    private fun crearPaginaTeclado(): View {
        val pag = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 4, 12, 12) }
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        teclado = Teclado(this, midi)
        teclado.notaBase = prefs.getInt("notaBase", 48)

        teclado.tonica = prefs.getInt("tonica", -1)
        teclado.modo = prefs.getInt("modo", 0)
        val menos = Boton(this, "OCT -") { cambiarOctava(-12) }.apply { tamTexto = 24f }
        etiquetaOct = Boton(this, "") { }.apply { tamTexto = 24f; soloTexto = true }
        val mas = Boton(this, "OCT +") { cambiarOctava(12) }.apply { tamTexto = 24f }
        btnEscala = Boton(this, "") { elegirEscala() }.apply { tamTexto = 24f; colorActivo = 0xFFF0B97E.toInt() }
        sustain = Sustain(this, midi)
        fila.addView(menos, LinearLayout.LayoutParams(0, dp(38), 0.55f))
        fila.addView(etiquetaOct, LinearLayout.LayoutParams(0, dp(38), 0.5f))
        fila.addView(mas, LinearLayout.LayoutParams(0, dp(38), 0.55f))
        fila.addView(View(this), LinearLayout.LayoutParams(0, dp(38), 0.25f))
        fila.addView(btnEscala, LinearLayout.LayoutParams(0, dp(38), 1.3f))
        fila.addView(View(this), LinearLayout.LayoutParams(0, dp(38), 0.4f))
        fila.addView(sustain, LinearLayout.LayoutParams(0, dp(56), 2f))
        pag.addView(fila, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pag.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        pag.addView(teclado, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actualizarOctava()
        actualizarEscala()
        return pag
    }

    private fun cambiarOctava(d: Int) {
        val n = (teclado.notaBase + d).coerceIn(12, 96)
        teclado.notaBase = n
        prefs.edit().putInt("notaBase", n).apply()
        actualizarOctava()
    }

    private fun actualizarOctava() { etiquetaOct.texto = "OCTAVA\nC" + (teclado.notaBase / 12 - 1); etiquetaOct.invalidate() }

    private fun elegirEscala() {
        val ops = Escalas.opciones
        val actual = ops.indexOfFirst { it.first == teclado.tonica && (teclado.tonica < 0 || it.second == teclado.modo) }
        dialogo("Escala (ilumina sus notas)", Array(ops.size) { Escalas.nombreEscala(ops[it].first, ops[it].second) }, actual) { i ->
            teclado.tonica = ops[i].first; teclado.modo = ops[i].second
            prefs.edit().putInt("tonica", teclado.tonica).putInt("modo", teclado.modo).apply()
            actualizarEscala()
        }
    }

    private fun actualizarEscala() {
        btnEscala.texto = if (teclado.tonica < 0) "ESCALA" else Escalas.nombreEscala(teclado.tonica, teclado.modo)
        btnEscala.activo = teclado.tonica >= 0
        btnEscala.invalidate()
    }

    /** Ventana de seleccion con una X para cerrar. */
    private fun dialogo(titulo: String, items: Array<String>, marcado: Int, alElegir: (Int) -> Unit) {
        val cab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(8), dp(8))
        }
        val t = TextView(this).apply { text = titulo; textSize = 18f; setTextColor(Estilo.TEXTO) }
        val x = TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Estilo.TEXTO); gravity = Gravity.CENTER
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        cab.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cab.addView(x, LinearLayout.LayoutParams(dp(52), dp(44)))
        val d = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setCustomTitle(cab)
            .setSingleChoiceItems(items, marcado) { dlg, i -> alElegir(i); dlg.dismiss() }
            .create()
        x.setOnClickListener { d.dismiss() }
        d.show()
    }

    // ------------------------------------------------------------ FADERS
    private fun crearPaginaFaders(): View {
        val pag = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 16) }
        for (i in 0 until 4) {
            val f = Fader(this, midi, prefs.getInt("cc$i", CC_DEFECTO[i]), COLORES[i]) { elegirCC(it) }
            f.fijar(prefs.getFloat("val$i", 0f), mandar = false)
            faders.add(f)
            pag.addView(f, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        pag.addView(RuedaPitch(this, midi), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f))
        return pag
    }

    private fun elegirCC(f: Fader) {
        val lista = Array(128) { n -> "CC $n  " + Cc.nombre(n) }
        dialogo("CC del fader", lista, f.numero) { n ->
            f.numero = n
            prefs.edit().putInt("cc" + faders.indexOf(f), n).apply()
            f.reenviar(); f.invalidate()
        }
    }

    // ------------------------------------------------------------ CANAL / PESTANAS
    private fun elegirCanal() {
        val lista = Array(16) { "Canal ${it + 1}" }
        dialogo("Canal MIDI", lista, midi.canal - 1) { n ->
            teclado.soltarTodo(); sustain.reiniciar()
            midi.canal = n + 1
            prefs.edit().putInt("canal", midi.canal).apply()
            actualizarCanal()
        }
    }

    private fun actualizarCanal() { btnCanal.texto = "CANAL ${midi.canal}"; btnCanal.invalidate() }

    private fun mostrar(p: Int) {
        pestana = p
        prefs.edit().putInt("pestana", p).apply()
        pagTeclado.visibility = if (p == 0) View.VISIBLE else View.GONE
        pagFaders.visibility = if (p == 1) View.VISIBLE else View.GONE
        tabTeclado.activo = p == 0
        tabFaders.activo = p == 1
        if (p != 0) teclado.soltarTodo()
        // teclado en horizontal, faders en vertical
        requestedOrientation = if (p == 0) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                               else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    // ------------------------------------------------------------ ciclo de vida
    override fun onResume() {
        super.onResume()
        pantallaCompleta()
        midi.iniciar()
    }

    override fun onPause() {
        teclado.soltarTodo()
        sustain.reiniciar()
        val e = prefs.edit()
        faders.forEachIndexed { i, f -> e.putFloat("val$i", f.valor) }
        e.apply()
        midi.detener()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pantallaCompleta()
    }

    private fun pantallaCompleta() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/** Indicador de conexion (punto verde / rojo + texto). */
class Estado(context: Context) : View(context) {
    private var texto = "Buscando..."
    private var ok = false
    private val pPunto = Estilo.pincel(Estilo.ROJO)
    private val pTexto = Estilo.pincel(Estilo.TEXTO, 28f).apply { textAlign = Paint.Align.LEFT }

    fun fijar(t: String, conectado: Boolean) { texto = t; ok = conectado; invalidate() }

    override fun onDraw(c: Canvas) {
        pPunto.color = if (ok) Estilo.VERDE else Estilo.ROJO
        c.drawCircle(22f, height / 2f, 11f, pPunto)
        pTexto.textSize = 28f
        var t = texto
        while (t.length > 4 && pTexto.measureText(t) > width - 50f) t = t.dropLast(1)
        if (t != texto) t = t.dropLast(1) + "…"
        c.drawText(t, 44f, height / 2f - (pTexto.ascent() + pTexto.descent()) / 2f, pTexto)
    }
}
