package pe.dlr.midi

import android.content.SharedPreferences
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Seccion ACORDES del menu lateral: transponer, forma de tocar, BPM y LATCH. */
class PanelTocar(
    private val act: MainActivity,
    private val prefs: SharedPreferences,
    private val acordes: () -> PaginaAcordes,
) : LinearLayout(act) {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val variable = LinearLayout(act).apply { orientation = VERTICAL }
    private lateinit var bTransp: Boton
    private lateinit var bModo: Boton

    init {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(10))
        addView(titulo("ACORDES"))
        // transponer
        val ft = fila()
        ft.addView(etiqueta("TRANSPONER"), LayoutParams(0, dp(44), 1.2f))
        ft.addView(Repetir(act, "−") { transponer(-1) }.apply { tamTexto = 30f }, LayoutParams(0, dp(44), 0.6f))
        bTransp = Boton(act, "") { transponerA(0) }.apply { tamTexto = 22f }
        ft.addView(bTransp, LayoutParams(0, dp(44), 0.9f))
        ft.addView(Repetir(act, "+") { transponer(1) }.apply { tamTexto = 30f }, LayoutParams(0, dp(44), 0.6f))
        addView(ft)
        // forma de tocar
        val fm = fila()
        fm.addView(etiqueta("FORMA"), LayoutParams(0, dp(44), 0.8f))
        bModo = Boton(act, "") { elegir("Forma de tocar", Reproductor.MODOS, "ac_modo") }.apply { tamTexto = 22f; colorActivo = Estilo.AZUL }
        fm.addView(bModo, LayoutParams(0, dp(44), 2.5f))
        addView(fm)
        addView(variable)
        refrescar()
    }

    private fun fila() = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun titulo(t: String) = TextView(act).apply {
        text = t; textSize = 17f; setTextColor(Estilo.TEXTO); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(6), 0, dp(6))
    }
    private fun etiqueta(t: String) = TextView(act).apply { text = t; textSize = 13f; setTextColor(Estilo.TENUE); setPadding(dp(4), 0, 0, 0) }

    private fun transponer(d: Int) = transponerA(acordes().transposicion() + d)
    private fun transponerA(st: Int) { acordes().transponer(st); refrescar() }

    private fun elegir(t: String, items: Array<String>, clave: String) {
        act.dialogo(t, items, prefs.getInt(clave, 0)) { i ->
            prefs.edit().putInt(clave, i).apply(); acordes().releerForma(); refrescar()
        }
    }

    private fun opcion(etq: String, items: Array<String>, clave: String, defecto: Int) = fila().apply {
        addView(etiqueta(etq), LayoutParams(0, dp(42), 0.8f))
        val i = prefs.getInt(clave, defecto).coerceIn(0, items.size - 1)
        addView(Boton(act, items[i]) {
            act.dialogo(etq, items, prefs.getInt(clave, defecto)) { n -> prefs.edit().putInt(clave, n).apply(); acordes().releerForma(); refrescar() }
        }.apply { tamTexto = 20f }, LayoutParams(0, dp(42), 2.5f))
    }

    private fun valor(etq: String, min: Int, max: Int, defecto: Int, clave: String, fmt: (Int) -> String) = fila().apply {
        val v = Valor(act, etq, min, max, defecto, fmt) { n -> prefs.edit().putInt(clave, n).apply(); acordes().releerForma() }
        v.valor = prefs.getInt(clave, defecto); v.tamTexto = 20f
        addView(v, LayoutParams(0, dp(46), 1f))
    }

    fun refrescar() {
        val st = acordes().transposicion()
        bTransp.texto = (if (st > 0) "+" else "") + st + " st"; bTransp.activo = st != 0; bTransp.invalidate()
        val modo = prefs.getInt("ac_modo", 0).coerceIn(0, Reproductor.MODOS.size - 1)
        bModo.texto = Reproductor.MODOS[modo]; bModo.activo = modo != 0; bModo.invalidate()
        variable.removeAllViews()
        when (modo) {
            1 -> variable.addView(valor("SEPARACIÓN (arrastra)", 0, 80, 0, "ac_sep") { if (it == 0) "AUTO" else "$it ms" })
            2 -> {
                variable.addView(opcion("PATRÓN", Reproductor.ARP_PATRONES, "ac_arp", 0))
                variable.addView(opcion("DIVISIÓN", Reproductor.DIVISIONES, "ac_div", 2))
                variable.addView(valor("OCTAVAS", 1, 3, 1, "ac_arpoct") { "$it" })
            }
            3 -> {
                variable.addView(opcion("RITMO", Array(Reproductor.RITMOS.size) { Reproductor.RITMOS[it].nombre }, "ac_ritmo", 3))
                variable.addView(opcion("NOTA", Reproductor.PULSO_NOTAS, "ac_pulso", 0))
            }
            4 -> variable.addView(opcion("RITMO", Array(Reproductor.RITMOS.size) { Reproductor.RITMOS[it].nombre }, "ac_ritmo", 3))
        }
        val fb = fila()
        if (modo >= 2) {
            val b = Valor(act, "BPM", 40, 240, 100, { "$it" }) { n -> prefs.edit().putInt("bpm", n).apply(); acordes().reproductor.leer() }
            b.valor = prefs.getInt("bpm", 100); b.tamTexto = 20f
            fb.addView(b, LayoutParams(0, dp(46), 1f))
        }
        val latch = prefs.getBoolean("ac_latch", false)
        fb.addView(Boton(act, if (latch) "LATCH ON" else "LATCH") {
            prefs.edit().putBoolean("ac_latch", !prefs.getBoolean("ac_latch", false)).apply(); acordes().releerForma(); refrescar()
        }.apply { tamTexto = 20f; colorActivo = Estilo.ACENTO; activo = latch }, LayoutParams(0, dp(46), 1f))
        variable.addView(fb)
    }
}
