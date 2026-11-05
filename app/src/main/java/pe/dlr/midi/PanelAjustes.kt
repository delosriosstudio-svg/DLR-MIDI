package pe.dlr.midi

import android.content.SharedPreferences
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Panel de AJUSTES (se abre deslizando desde el borde izquierdo o con el boton AJUSTES):
 * tonalidad, escala, bloqueo de escala, canal MIDI, velocidad, humanize y sonido interno.
 * Los botones se crean en MainActivity (para que sigan conectados a su logica) y aqui solo se ordenan.
 */
class PanelAjustes(
    private val act: MainActivity,
    private val prefs: SharedPreferences,
    btnTonica: Boton, btnEscala: Boton, btnBloqueo: Boton,
    btnCanal: Boton, vel: Valor, hum: Valor, grosor: Valor, btnModoSonido: Boton,
    private val alCerrar: () -> Unit,
) : LinearLayout(act) {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val bInstrumento: Boton

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1B1C20.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val cab = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cab.addView(texto("AJUSTES", 20f, Estilo.TEXTO, true), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = texto("✕", 24f, Estilo.TEXTO, false).apply { setPadding(dp(14), dp(4), dp(6), dp(4)); setOnClickListener { alCerrar() } }
        cab.addView(x, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(cab, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val cuerpo = LinearLayout(act).apply { orientation = VERTICAL }

        cuerpo.addView(titulo("ESCALA"))
        val f1 = fila(); f1.addView(btnTonica, LayoutParams(0, dp(48), 1.3f)); f1.addView(btnBloqueo, LayoutParams(0, dp(48), 1f))
        cuerpo.addView(f1)
        val f2 = fila(); f2.addView(btnEscala, LayoutParams(0, dp(48), 1f))
        cuerpo.addView(f2)

        cuerpo.addView(titulo("SALIDA MIDI"))
        val f3 = fila()
        f3.addView(btnCanal, LayoutParams(0, dp(48), 1f))
        f3.addView(vel, LayoutParams(0, dp(48), 1f))
        f3.addView(hum, LayoutParams(0, dp(48), 1.2f))
        cuerpo.addView(f3)
        cuerpo.addView(texto("VEL y HUMANIZE: arrastra hacia arriba/derecha para subir · doble toque = valor inicial", 12f, Estilo.TENUE, false).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) })

        cuerpo.addView(titulo("TECLADO"))
        val fg = fila(); fg.addView(grosor, LayoutParams(0, dp(48), 1f))
        cuerpo.addView(fg)
        cuerpo.addView(texto("GROSOR TECLAS: sube = teclas más anchas (menos teclas a la vista) · baja = más teclas más finas", 12f, Estilo.TENUE, false).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) })

        cuerpo.addView(titulo("SONIDO INTERNO"))
        val f4 = fila(); f4.addView(btnModoSonido, LayoutParams(0, dp(48), 1f))
        cuerpo.addView(f4)
        val f5 = fila()
        f5.addView(texto("INSTRUMENTO", 13f, Estilo.TENUE, false).apply { setPadding(dp(4), 0, 0, 0) }, LayoutParams(0, dp(48), 0.9f))
        bInstrumento = Boton(act, "") {
            act.dialogo("Sonido interno", Sintetizador.SONIDOS, prefs.getInt("sinte_sonido", 0)) { i -> act.ajustarSinte("sinte_sonido", i); actualizar() }
        }.apply { tamTexto = 20f }
        f5.addView(bInstrumento, LayoutParams(0, dp(48), 2.1f))
        cuerpo.addView(f5)
        val f6 = fila()
        val vol = Valor(act, "VOLUMEN", 0, 100, 80, { "$it %" }) { act.ajustarSinte("sinte_vol", it) }
        vol.valor = prefs.getInt("sinte_vol", 80); vol.tamTexto = 20f
        val rev = Valor(act, "REVERB", 0, 100, 25, { if (it == 0) "OFF" else "$it %" }) { act.ajustarSinte("sinte_rev", it) }
        rev.valor = prefs.getInt("sinte_rev", 25); rev.tamTexto = 20f
        f6.addView(vol, LayoutParams(0, dp(48), 1f)); f6.addView(rev, LayoutParams(0, dp(48), 1f))
        cuerpo.addView(f6)

        val sv = ScrollView(act); sv.addView(cuerpo)
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actualizar()
    }

    private fun fila() = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }

    private fun titulo(t: String) = texto(t, 17f, Estilo.TEXTO, true).apply { setPadding(dp(4), dp(12), 0, dp(4)) }

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean) = TextView(act).apply {
        text = t; textSize = tam; setTextColor(color)
        if (negrita) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun actualizar() {
        bInstrumento.texto = Sintetizador.SONIDOS[prefs.getInt("sinte_sonido", 0).coerceIn(0, Sintetizador.SONIDOS.size - 1)]
        bInstrumento.invalidate()
    }
}
