package pe.dlr.midi

import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Menu lateral de TEMPLATES (se abre deslizando desde el borde derecho). */
class PanelPlantillas(
    private val act: MainActivity,
    private val prefs: SharedPreferences,
    private val alCerrar: () -> Unit,
    private val alCargar: (String) -> Unit,
) : LinearLayout(act) {

    private val lista = LinearLayout(act).apply { orientation = VERTICAL }
    private val abiertas = HashSet<String>().apply { add(Plantillas.FABRICA) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1B1C20.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val cab = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cab.addView(texto("TEMPLATES", 20f, Estilo.TEXTO, true), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = texto("✕", 24f, Estilo.TEXTO, false).apply { setPadding(dp(14), dp(4), dp(6), dp(4)); setOnClickListener { alCerrar() } }
        cab.addView(x, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(cab, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val fb = LinearLayout(act).apply { orientation = HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        fb.addView(Boton(act, "GUARDAR ACTUAL") { pedirNombre() }.apply { tamTexto = 22f; colorActivo = Estilo.VERDE; activo = true },
            LayoutParams(0, dp(44), 1.3f))
        fb.addView(Boton(act, "+ CARPETA") { nuevaCarpeta(null) }.apply { tamTexto = 22f }, LayoutParams(0, dp(44), 1f))
        addView(fb, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val sv = ScrollView(act); sv.addView(lista)
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(texto("Toca un template para cargarlo · mantén presionado para renombrar, mover o borrar", 12f, Estilo.TENUE, false),
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refrescar()
    }

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean) = TextView(act).apply {
        text = t; textSize = tam; setTextColor(color)
        if (negrita) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun refrescar() {
        lista.removeAllViews()
        for (c in Plantillas.carpetas(act)) {
            val abierta = c in abiertas
            val items = Plantillas.listar(act, c)
            val fila = texto((if (abierta) "▾  " else "▸  ") + "📁 " + c + "   (" + items.size + ")", 17f, Estilo.TEXTO, true).apply {
                setPadding(dp(6), dp(12), dp(6), dp(12))
                setOnClickListener { if (!abiertas.remove(c)) abiertas.add(c); refrescar() }
                setOnLongClickListener { opcionesCarpeta(c); true }
            }
            lista.addView(fila)
            if (!abierta) continue
            if (items.isEmpty()) lista.addView(texto("      (vacía)", 14f, Estilo.TENUE, false).apply { setPadding(dp(24), dp(4), 0, dp(8)) })
            for (item in items) {
                val t = texto("🎹  " + item.nombre, 16f, if (item.fabrica) 0xFFF0B97E.toInt() else Estilo.TEXTO, false).apply {
                    setPadding(dp(30), dp(10), dp(6), dp(10))
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener { confirmarCarga(item) }
                    setOnLongClickListener { opcionesItem(item); true }
                }
                lista.addView(t)
            }
        }
    }

    private fun confirmarCarga(item: Plantillas.Item) {
        act.dialogo("¿Cargar \"${item.nombre}\"? (reemplaza los ajustes actuales; guarda antes si quieres conservarlos)",
            arrayOf("Sí, cargar"), -1) {
            if (Plantillas.cargar(act, prefs, item)) alCargar(item.nombre)
            else act.aviso("No se pudo cargar")
        }
    }

    private fun pedirTexto(titulo: String, inicial: String, alAceptar: (String) -> Unit) {
        val et = EditText(act).apply {
            setText(inicial); setSelection(inicial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(Estilo.TEXTO)
        }
        val cont = LinearLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog)
            .setTitle(titulo).setView(cont)
            .setPositiveButton("Aceptar") { _, _ -> val s = et.text.toString().trim(); if (s.isNotEmpty()) alAceptar(s) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirNombre() {
        pedirTexto("Nombre del template", "") { nombre ->
            val propias = Plantillas.carpetas(act).filter { it != Plantillas.FABRICA }
            val items = propias.toTypedArray() + arrayOf("+ Nueva carpeta…")
            act.dialogo("¿En qué carpeta?", items, -1) { i ->
                if (i < propias.size) guardarEn(propias[i], nombre)
                else nuevaCarpeta { c -> guardarEn(c, nombre) }
            }
        }
    }

    private fun guardarEn(carpeta: String, nombre: String) {
        act.guardarEstado()
        Plantillas.guardar(act, prefs, carpeta, nombre)
        abiertas.add(Plantillas.limpio(carpeta))
        act.aviso("Guardado: $carpeta / $nombre")
        refrescar()
    }

    private fun nuevaCarpeta(despues: ((String) -> Unit)?) {
        pedirTexto("Nombre de la carpeta", "") { n ->
            if (Plantillas.crearCarpeta(act, n)) { abiertas.add(Plantillas.limpio(n)); refrescar(); despues?.invoke(Plantillas.limpio(n)) }
            else act.aviso("Ese nombre no se puede usar")
        }
    }

    private fun opcionesItem(item: Plantillas.Item) {
        val it = item
        if (it.fabrica) { act.aviso("Los templates DLR no se pueden cambiar (puedes cargarlo y guardarlo con otro nombre)"); return }
        act.dialogo(it.nombre, arrayOf("Renombrar", "Mover a otra carpeta", "Borrar"), -1) { op ->
            when (op) {
                0 -> pedirTexto("Nuevo nombre", it.nombre) { n -> Plantillas.renombrar(act, it, n); refrescar() }
                1 -> {
                    val cs = Plantillas.carpetas(act).filter { c -> c != Plantillas.FABRICA && c != it.carpeta }
                    if (cs.isEmpty()) act.aviso("Crea otra carpeta primero")
                    else act.dialogo("Mover a…", cs.toTypedArray(), -1) { i -> Plantillas.mover(act, it, cs[i]); abiertas.add(cs[i]); refrescar() }
                }
                2 -> act.dialogo("¿Borrar \"${it.nombre}\"?", arrayOf("Sí, borrar"), -1) { Plantillas.borrar(act, item); refrescar() }
            }
        }
    }

    private fun opcionesCarpeta(c: String) {
        if (c == Plantillas.FABRICA) return
        act.dialogo("Carpeta $c", arrayOf("Borrar la carpeta y sus templates"), -1) {
            act.dialogo("¿Seguro? Se borran todos los templates de \"$c\"", arrayOf("Sí, borrar"), -1) { Plantillas.borrarCarpeta(act, c); refrescar() }
        }
    }
}
