package pe.dlr.midi

import android.content.SharedPreferences
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Panel de TRANSPORTE (se abre deslizando desde el borde izquierdo).
 * - Play / Stop / Grabar / Inicio: manda MMC y ademas un CC en el canal 16
 *   (CC 115 Play, 116 Stop, 117 Grabar, 118 Inicio) para asignarlo en REAPER con "Learn".
 * - PRESET: Program Change en el canal de la app (anterior / siguiente / elegir numero).
 */
class PanelTransporte(
    private val act: MainActivity,
    private val prefs: SharedPreferences,
    private val midi: Midi,
    private val alCerrar: () -> Unit,
) : LinearLayout(act) {

    companion object {
        const val CANAL_CC = 16
        const val CC_PLAY = 115
        const val CC_STOP = 116
        const val CC_GRABAR = 117
        const val CC_INICIO = 118
    }

    private var programa = prefs.getInt("programa", 1).coerceIn(1, 128)
    private val btnPrograma: Boton
    private val infoCanal: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1B1C20.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val cab = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cab.addView(texto("TRANSPORTE", 20f, Estilo.TEXTO, true), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = texto("✕", 24f, Estilo.TEXTO, false).apply { setPadding(dp(14), dp(4), dp(6), dp(4)); setOnClickListener { alCerrar() } }
        cab.addView(x, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(cab, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val cuerpo = LinearLayout(act).apply { orientation = VERTICAL }

        // ---- transporte (2 x 2)
        val f1 = fila(); val f2 = fila()
        f1.addView(boton("⏮\nINICIO", Estilo.TEXTO) { transporte(0x44, CC_INICIO, "Inicio") }, LayoutParams(0, dp(78), 1f))
        f1.addView(boton("▶\nPLAY", Estilo.VERDE) { transporte(0x02, CC_PLAY, "Play") }, LayoutParams(0, dp(78), 1.3f))
        f2.addView(boton("■\nSTOP", Estilo.TEXTO) { transporte(0x01, CC_STOP, "Stop") }, LayoutParams(0, dp(78), 1.3f))
        f2.addView(boton("●\nGRABAR", Estilo.ROJO) { transporte(0x06, CC_GRABAR, "Grabar") }, LayoutParams(0, dp(78), 1f))
        cuerpo.addView(f1); cuerpo.addView(f2)
        cuerpo.addView(texto("Manda MMC y CC $CC_PLAY Play · $CC_STOP Stop · $CC_GRABAR Grabar · $CC_INICIO Inicio (canal $CANAL_CC). En REAPER: Actions > elige la acción > Add > toca el botón.", 12f, Estilo.TENUE, false).apply { setPadding(dp(4), dp(6), dp(4), dp(14)) })

        // ---- preset (Program Change)
        cuerpo.addView(texto("PRESET (Program Change)", 17f, Estilo.TEXTO, true).apply { setPadding(dp(4), dp(4), 0, dp(6)) })
        val fp = fila()
        fp.addView(Repetir(act, "◀") { mover(-1) }.apply { tamTexto = 40f }, LayoutParams(0, dp(84), 1f))
        btnPrograma = Boton(act, "") { elegirPrograma() }.apply { tamTexto = 30f; colorActivo = Estilo.AZUL }
        fp.addView(btnPrograma, LayoutParams(0, dp(84), 1.4f))
        fp.addView(Repetir(act, "▶") { mover(1) }.apply { tamTexto = 40f }, LayoutParams(0, dp(84), 1f))
        cuerpo.addView(fp)
        val fr = fila()
        fr.addView(Boton(act, "REENVIAR") { mandarPrograma() }.apply { tamTexto = 20f }, LayoutParams(0, dp(44), 1f))
        fr.addView(Boton(act, "PRESET 1") { programa = 1; mandarPrograma() }.apply { tamTexto = 20f }, LayoutParams(0, dp(44), 1f))
        cuerpo.addView(fr)
        infoCanal = texto("", 12f, Estilo.TENUE, false).apply { setPadding(dp(4), dp(6), dp(4), dp(6)) }
        cuerpo.addView(infoCanal)

        val sv = ScrollView(act); sv.addView(cuerpo)
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actualizar()
    }

    private fun fila() = LinearLayout(act).apply { orientation = HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }

    private fun boton(t: String, color: Int, accion: () -> Unit) = Boton(act, t) { accion() }.apply {
        tamTexto = 26f; colorActivo = color
    }

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean) = TextView(act).apply {
        text = t; textSize = tam; setTextColor(color)
        if (negrita) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun transporte(mmc: Int, cc: Int, nombre: String) {
        midi.mmc(mmc)
        midi.ccEnCanal(CANAL_CC, cc, 127)
        postDelayed({ midi.ccEnCanal(CANAL_CC, cc, 0) }, 60)
        act.aviso(if (midi.conectado) nombre else "$nombre (sin conexión)")
    }

    private fun mover(d: Int) {
        val n = (programa + d).coerceIn(1, 128)
        if (n == programa) return
        programa = n
        mandarPrograma()
    }

    private fun mandarPrograma() {
        midi.programa(programa)
        prefs.edit().putInt("programa", programa).apply()
        actualizar()
    }

    private fun elegirPrograma() {
        val items = Array(128) { "Preset ${it + 1}   (Program Change ${it})" }
        act.dialogo("Preset (Program Change)", items, programa - 1) { i -> programa = i + 1; mandarPrograma() }
    }

    fun actualizar() {
        btnPrograma.texto = "PRESET\n$programa"
        btnPrograma.invalidate()
        infoCanal.text = "Se manda en el canal ${midi.canal} (el de la app). Numeración 1–128 = Program Change 0–127. " +
            "En REAPER el plugin cambia de preset si en su ventana activas: + (menú de presets) > Link to MIDI program change."
    }
}
