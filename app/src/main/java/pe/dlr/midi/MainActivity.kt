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
import android.view.MotionEvent
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
 * Siempre horizontal. Columna izquierda fija: modos (TECLADO / ACORDES / FADERS) + transporte + Program Change.
 * Panel izquierdo deslizable: AJUSTES (tonalidad, escala, bloqueo, canal, VEL, humanize, sonido).
 * Panel derecho deslizable: acordes y TEMPLATES.
 */
class MainActivity : Activity(), MainActivityApi {

    private lateinit var prefs: SharedPreferences
    private lateinit var midi: Midi
    private lateinit var interp: Interprete
    private lateinit var estado: Estado
    private lateinit var contenido: FrameLayout
    private lateinit var pagTeclado: View
    private lateinit var pagFaders: View
    private lateinit var teclado: Teclado
    private lateinit var sustain: Sustain
    private lateinit var etiquetaOct: Boton
    private lateinit var btnEscala: Boton
    private lateinit var btnTonica: Boton
    private lateinit var btnBloqueo: Boton
    private lateinit var btnCCDedo: Boton
    private lateinit var btnCanal: Boton
    private lateinit var tabTeclado: Boton
    private lateinit var tabFaders: Boton
    private lateinit var tabAcordes: Boton
    private lateinit var pagAcordes: PaginaAcordes
    private lateinit var marco: FrameLayout
    private lateinit var velo: View
    private lateinit var menu: PanelPlantillas
    private lateinit var ajustes: PanelAjustes
    private lateinit var btnPrograma: Boton
    private var programa = 1
    private var bordeIzq = -1f
    private var cargando = false
    val sinte = Sintetizador()
    private var estadoMidi = ""
    private lateinit var btnSonido: Boton
    private var enPantalla = false
    private var bordeX = -1f
    private val faders = ArrayList<Fader>()
    private var pestana = 0

    private val COLORES = intArrayOf(Estilo.ACENTO, Estilo.AZUL, Estilo.VERDE, 0xFFC882E8.toInt())
    private val CC_DEFECTO = intArrayOf(1, 11, 7, 74)
    private val GROSOR_DEFECTO = 7
    private val CANAL_CC = 16
    private val CC_PLAY = 115
    private val CC_STOP = 116
    private val CC_GRABAR = 117
    private val CC_INICIO = 118

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dlr_midi", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        estado = Estado(this)
        midi = Midi(this) { texto, ok -> estadoMidi = texto; estado.fijar(texto, ok); actualizarSonido() }
        sinte.sonido = prefs.getInt("sinte_sonido", 0)
        sinte.volumen = prefs.getInt("sinte_vol", 80) / 100f
        sinte.reverb = prefs.getInt("sinte_rev", 25) / 100f
        midi.sinte = sinte
        midi.canal = prefs.getInt("canal", 1)
        interp = Interprete(midi)
        interp.velBase = prefs.getInt("velBase", 100)
        interp.humanize = prefs.getInt("humanize", 0) / 100f

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        programa = prefs.getInt("programa", 1).coerceIn(1, 128)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Estilo.FONDO)
        }

        // ---- botones de ajustes (viven en el panel izquierdo deslizable)
        btnCanal = Boton(this, "") { elegirCanal() }.apply { tamTexto = 22f }
        btnSonido = Boton(this, "") { ciclarSonido() }.apply { tamTexto = 20f; colorActivo = Estilo.VERDE }
        val vel = Valor(this, "VEL", 1, 127, 100, { "$it" }) { interp.velBase = it; prefs.edit().putInt("velBase", it).apply() }
        vel.valor = interp.velBase
        val hum = Valor(this, "HUMANIZE", 0, 100, 0, { if (it == 0) "OFF" else "$it %" }) { interp.humanize = it / 100f; prefs.edit().putInt("humanize", it).apply() }
        hum.valor = (interp.humanize * 100f + 0.5f).toInt()
        vel.tamTexto = 20f; hum.tamTexto = 20f
        val grosor = Valor(this, "GROSOR TECLAS", 1, 10, GROSOR_DEFECTO, { "$it" }) { g ->
            prefs.edit().putInt("grosor", g).apply()
            aplicarGrosor(g)
        }
        grosor.valor = prefs.getInt("grosor", GROSOR_DEFECTO); grosor.tamTexto = 20f

        // ---- columna izquierda fija: modos + transporte + program change
        val columna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(4), dp(4)) }
        fun fila(peso: Float, vararg items: Pair<View, Float>) {
            val f = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            for ((v, w) in items) f.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w))
            columna.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, peso))
        }
        tabTeclado = Boton(this, "TECLADO") { mostrar(0) }.apply { tamTexto = 19f }
        tabAcordes = Boton(this, "ACORDES") { mostrar(2) }.apply { tamTexto = 19f }
        tabFaders = Boton(this, "FADERS") { mostrar(1) }.apply { tamTexto = 19f }
        val btnAjustes = Boton(this, "⚙ AJUSTES") { abrirAjustes() }.apply { tamTexto = 17f }
        val btnMenu = Boton(this, "☰") { abrirMenu() }.apply { tamTexto = 26f }
        fila(0.9f, estado to 1f)
        fila(0.8f, btnAjustes to 2.2f, btnMenu to 1f)
        fila(1f, tabTeclado to 1f)
        fila(1f, tabAcordes to 1f)
        fila(1f, tabFaders to 1f)
        fila(1f,
            Boton(this, "⏮\nINICIO") { transporte(0x44, CC_INICIO, "Inicio") }.apply { tamTexto = 15f } to 1f,
            Boton(this, "▶\nPLAY") { transporte(0x02, CC_PLAY, "Play") }.apply { tamTexto = 15f; colorActivo = Estilo.VERDE } to 1f)
        fila(1f,
            Boton(this, "■\nSTOP") { transporte(0x01, CC_STOP, "Stop") }.apply { tamTexto = 15f } to 1f,
            Boton(this, "●\nGRABAR") { transporte(0x06, CC_GRABAR, "Grabar") }.apply { tamTexto = 15f; colorActivo = Estilo.ROJO } to 1f)
        btnPrograma = Boton(this, "") { elegirPrograma() }.apply { tamTexto = 16f; colorActivo = Estilo.AZUL }
        fila(1f,
            Repetir(this, "◀") { moverPrograma(-1) }.apply { tamTexto = 20f } to 0.7f,
            btnPrograma to 1.4f,
            Repetir(this, "▶") { moverPrograma(1) }.apply { tamTexto = 20f } to 0.7f)
        raiz.addView(columna, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.MATCH_PARENT))

        contenido = FrameLayout(this)
        raiz.addView(contenido, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        pagTeclado = crearPaginaTeclado()
        pagFaders = crearPaginaFaders()
        contenido.addView(pagTeclado)
        contenido.addView(pagFaders)
        pagAcordes = PaginaAcordes(this, midi, interp, prefs)
        contenido.addView(pagAcordes)

        // ---- raiz con el menu lateral de TEMPLATES encima
        marco = FrameLayout(this)
        marco.addView(raiz, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        velo = View(this).apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE; setOnClickListener { cerrarMenu(); cerrarAjustes() } }
        marco.addView(velo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menu = PanelPlantillas(this, prefs, { cerrarMenu() }, { nombre -> recargarCon(nombre) }) { pagAcordes }
        menu.visibility = View.GONE
        marco.addView(menu, FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        // AJUSTES: se desliza desde el borde izquierdo
        ajustes = PanelAjustes(this, prefs, btnTonica, btnEscala, btnBloqueo, btnCanal, vel, hum, grosor, btnSonido) { cerrarAjustes() }
        ajustes.visibility = View.GONE
        marco.addView(ajustes, FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        // que el gesto "atras" de Android no se coma el deslizamiento desde los bordes
        marco.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height; val w = v.width
            v.systemGestureExclusionRects = listOf(
                android.graphics.Rect(w - dp(28), h / 2 - dp(100), w, h / 2 + dp(100)),
                android.graphics.Rect(0, h / 2 - dp(100), dp(28), h / 2 + dp(100)))
        }
        setContentView(marco)
        actualizarCanal()
        actualizarPrograma()
        mostrar(prefs.getInt("pestana", 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ TECLADO
    private fun crearPaginaTeclado(): View {
        val pag = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 4, 12, 12) }
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        teclado = Teclado(this, midi, interp)
        // posicion (antes se guardaba la nota de inicio; se convierte)
        teclado.primeraBlanca = if (prefs.contains("primeraBlanca")) prefs.getInt("primeraBlanca", 28)
                                else prefs.getInt("notaBase", 48) / 12 * 7
        teclado.teclasBlancas = teclasDeGrosor(prefs.getInt("grosor", GROSOR_DEFECTO))
        teclado.tonica = prefs.getInt("tonica", -1)
        teclado.escala = prefs.getInt("escala", prefs.getInt("modo", 0))
        teclado.bloqueo = prefs.getBoolean("bloqueo", false)
        teclado.ccDedo = prefs.getInt("ccDedo", -1)
        teclado.fijarCC(prefs.getFloat("valCCDedo", 0f))
        teclado.alCambiarCC = { actualizarCCDedo() }

        val chico = 22f
        val menos = Boton(this, "OCT -") { mover(-7) }.apply { tamTexto = chico }
        val izq = Repetir(this, "◀") { mover(-1) }.apply { tamTexto = 26f }
        etiquetaOct = Boton(this, "") { }.apply { tamTexto = chico; soloTexto = true }
        val der = Repetir(this, "▶") { mover(1) }.apply { tamTexto = 26f }
        val mas = Boton(this, "OCT +") { mover(7) }.apply { tamTexto = chico }
        btnTonica = Boton(this, "") { elegirTonica() }.apply { tamTexto = chico; colorActivo = 0xFFF0B97E.toInt() }
        btnEscala = Boton(this, "") { elegirEscala() }.apply { tamTexto = chico; colorActivo = 0xFFF0B97E.toInt() }
        btnBloqueo = Boton(this, "") { alternarBloqueo() }.apply { tamTexto = chico; colorActivo = Estilo.ROJO }
        btnCCDedo = Boton(this, "") { elegirCCDedo() }.apply { tamTexto = chico; colorActivo = Estilo.AZUL }
        sustain = Sustain(this, midi)

        val h = dp(38)
        fun agregar(v: View, peso: Float, alto: Int = h) = fila.addView(v, LinearLayout.LayoutParams(0, alto, peso))
        agregar(menos, 0.5f); agregar(izq, 0.32f); agregar(etiquetaOct, 0.55f); agregar(der, 0.32f); agregar(mas, 0.5f)
        agregar(View(this), 0.12f)
        agregar(btnCCDedo, 1.1f)
        agregar(View(this), 0.12f)
        agregar(sustain, 1.4f, dp(52))
        pag.addView(fila, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pag.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        pag.addView(teclado, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actualizarPosicion()
        actualizarEscala()
        actualizarCCDedo()
        return pag
    }

    /** grosor 1..10 -> teclas blancas a la vista: 1 = 22 (finas) ... 10 = 7 (gruesas) */
    private fun teclasDeGrosor(g: Int) = Math.round(22f - (g.coerceIn(1, 10) - 1) * 15f / 9f)

    private fun aplicarGrosor(g: Int) {
        teclado.teclasBlancas = teclasDeGrosor(g)
        teclado.primeraBlanca = teclado.primeraBlanca   // reajusta si se sale del rango
        prefs.edit().putInt("primeraBlanca", teclado.primeraBlanca).apply()
        actualizarPosicion()
    }

    /** mover el teclado: 1 = una tecla blanca, 7 = una octava */
    private fun mover(d: Int) {
        teclado.primeraBlanca = teclado.primeraBlanca + d
        prefs.edit().putInt("primeraBlanca", teclado.primeraBlanca).apply()
        actualizarPosicion()
    }

    private fun actualizarPosicion() {
        val a = teclado.primeraBlanca
        etiquetaOct.texto = Teclado.nombreBlanca(a) + " – " + Teclado.nombreBlanca(a + teclado.teclasBlancas - 1)
        etiquetaOct.invalidate()
    }

    private fun elegirTonica() {
        val items = arrayOf("Sin tonalidad (apagar ayuda)") + Escalas.TONICAS
        dialogo("Tonalidad", items, teclado.tonica + 1) { i ->
            teclado.tonica = i - 1
            prefs.edit().putInt("tonica", teclado.tonica).apply()
            actualizarEscala()
        }
    }

    private fun elegirEscala() {
        val items = Array(Escalas.LISTA.size) { Escalas.LISTA[it].nombre }
        dialogo("Escala", items, teclado.escala) { i ->
            teclado.escala = i
            prefs.edit().putInt("escala", i).apply()
            actualizarEscala()
        }
    }

    private fun alternarBloqueo() {
        if (teclado.tonica < 0) { estado.fijarAviso("Elige una tonalidad para bloquear"); return }
        teclado.bloqueo = !teclado.bloqueo
        prefs.edit().putBoolean("bloqueo", teclado.bloqueo).apply()
        actualizarEscala()
    }

    private fun actualizarEscala() {
        val on = teclado.tonica >= 0
        btnTonica.texto = Escalas.nombreTonica(teclado.tonica, teclado.escala)
        btnTonica.activo = on
        btnEscala.texto = Escalas.escala(teclado.escala).nombre.uppercase()
        btnEscala.activo = on
        btnBloqueo.texto = if (teclado.bloqueo) "BLOQ.\nON" else "BLOQ."
        btnBloqueo.activo = on && teclado.bloqueo
        listOf(btnTonica, btnEscala, btnBloqueo).forEach { it.invalidate() }
    }

    private fun elegirCCDedo() {
        val items = arrayOf("Apagado") + Array(128) { n -> "CC $n  " + Cc.nombre(n) }
        dialogo("CC con la altura del dedo", items, teclado.ccDedo + 1) { i ->
            teclado.ccDedo = i - 1
            prefs.edit().putInt("ccDedo", teclado.ccDedo).apply()
            actualizarCCDedo()
        }
    }

    private fun actualizarCCDedo() {
        val cc = teclado.ccDedo
        btnCCDedo.texto = if (cc < 0) "CC DEDO\nOFF" else "DEDO CC$cc\n" + (teclado.valorCC * 127f + 0.5f).toInt()
        btnCCDedo.activo = cc >= 0
        btnCCDedo.invalidate()
    }

    /** Ventana de seleccion con una X para cerrar. */
    override fun contexto(): Context = this

    override fun dialogo(titulo: String, items: Array<String>, marcado: Int, alElegir: (Int) -> Unit) {
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
            teclado.soltarTodo(); sustain.reiniciar(); pagAcordes.soltarTodo()
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
        pagAcordes.visibility = if (p == 2) View.VISIBLE else View.GONE
        tabTeclado.activo = p == 0
        tabFaders.activo = p == 1
        tabAcordes.activo = p == 2
        if (p != 0) teclado.soltarTodo()
        if (p != 2) pagAcordes.soltarTodo()
    }

    // ------------------------------------------------------------ permiso de Bluetooth
    private var pidioBt = false
    private fun pedirPermisoBluetooth() {
        val faltan = BleMidi.permisos().filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (faltan.isNotEmpty() && !pidioBt) { pidioBt = true; requestPermissions(faltan.toTypedArray(), 77) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) midi.reiniciarBluetooth()
            else aviso("Sin permiso de Bluetooth: solo USB y Wi-Fi")
        }
    }

    // ------------------------------------------------------------ ciclo de vida
    override fun onResume() {
        super.onResume()
        pedirPermisoBluetooth()
        pantallaCompleta()
        enPantalla = true
        midi.iniciar()
        actualizarSonido()
    }

    override fun onPause() {
        teclado.soltarTodo()
        pagAcordes.soltarTodo()
        sustain.reiniciar()
        if (!cargando) guardarEstado()
        midi.detener()
        enPantalla = false
        midi.sonar = false
        sinte.apagar()
        super.onPause()
    }

    /** guarda lo que vive en memoria (faders, CC dedo, acordes) */
    fun guardarEstado() {
        pagAcordes.guardar()
        val e = prefs.edit()
        faders.forEachIndexed { i, f -> e.putFloat("val$i", f.valor) }
        e.putFloat("valCCDedo", teclado.valorCC)
        e.apply()
    }

    // ------------------------------------------------------------ SONIDO interno
    /** 0 = AUTO (suena si no hay cable), 1 = siempre, 2 = apagado */
    private fun modoSonido() = prefs.getInt("sinte_modo", 0)

    private fun ciclarSonido() {
        val m = (modoSonido() + 1) % 3
        prefs.edit().putInt("sinte_modo", m).apply()
        actualizarSonido()
        aviso(when (m) { 0 -> "Sonido interno: AUTO (suena sin cable)"; 1 -> "Sonido interno: SIEMPRE"; else -> "Sonido interno: APAGADO" })
    }

    fun actualizarSonido() {
        if (!::btnSonido.isInitialized) return
        val m = modoSonido()
        val suena = enPantalla && (m == 1 || (m == 0 && !midi.conectado))
        if (suena && !sinte.encendido) sinte.encender()
        if (!suena && sinte.encendido) sinte.apagar()
        midi.sonar = suena
        btnSonido.texto = when (m) { 0 -> "🔊 AUTO"; 1 -> "🔊 ON"; else -> "🔇 OFF" }
        btnSonido.activo = suena
        btnSonido.invalidate()
        if (suena && !midi.conectado) estado.fijar("Sin cable · sonido interno", true)
        else if (estadoMidi.isNotEmpty()) estado.fijar(estadoMidi, midi.conectado)
    }

    fun ajustarSinte(clave: String, v: Int) {
        prefs.edit().putInt(clave, v).apply()
        when (clave) {
            "sinte_sonido" -> sinte.sonido = v
            "sinte_vol" -> sinte.volumen = v / 100f
            "sinte_rev" -> sinte.reverb = v / 100f
        }
    }

    // ------------------------------------------------------------ menu de TEMPLATES
    fun abrirMenu() {
        if (menu.visibility == View.VISIBLE || ajustes.visibility == View.VISIBLE) return
        teclado.soltarTodo()
        menu.refrescar()
        menu.visibility = View.VISIBLE; velo.visibility = View.VISIBLE
        menu.translationX = dp(340).toFloat()
        menu.animate().translationX(0f).setDuration(180).start()
    }

    fun cerrarMenu() {
        if (menu.visibility != View.VISIBLE) return
        menu.animate().translationX(dp(340).toFloat()).setDuration(160).withEndAction {
            menu.visibility = View.GONE
            if (ajustes.visibility != View.VISIBLE) velo.visibility = View.GONE
        }.start()
    }

    fun aviso(t: String) = estado.fijarAviso(t)

    // ------------------------------------------------------------ panel de AJUSTES (izquierda)
    fun abrirAjustes() {
        if (ajustes.visibility == View.VISIBLE || menu.visibility == View.VISIBLE) return
        teclado.soltarTodo()
        ajustes.actualizar()
        ajustes.visibility = View.VISIBLE; velo.visibility = View.VISIBLE
        ajustes.translationX = -dp(340).toFloat()
        ajustes.animate().translationX(0f).setDuration(180).start()
    }

    fun cerrarAjustes() {
        if (ajustes.visibility != View.VISIBLE) return
        ajustes.animate().translationX(-dp(340).toFloat()).setDuration(160).withEndAction {
            ajustes.visibility = View.GONE
            if (menu.visibility != View.VISIBLE) velo.visibility = View.GONE
        }.start()
    }

    // ------------------------------------------------------------ TRANSPORTE y PROGRAM CHANGE (columna izquierda)
    /** Manda MMC y ademas un CC en el canal 16 (para asignarlo en REAPER con "Learn") */
    private fun transporte(mmc: Int, cc: Int, nombre: String) {
        midi.mmc(mmc)
        midi.ccEnCanal(CANAL_CC, cc, 127)
        btnPrograma.postDelayed({ midi.ccEnCanal(CANAL_CC, cc, 0) }, 60)
        aviso(if (midi.conectado) nombre else "$nombre (sin conexión)")
    }

    private fun moverPrograma(d: Int) {
        val n = (programa + d).coerceIn(1, 128)
        if (n == programa) return
        programa = n
        mandarPrograma()
    }

    private fun mandarPrograma() {
        midi.programa(programa)
        prefs.edit().putInt("programa", programa).apply()
        actualizarPrograma()
    }

    private fun elegirPrograma() {
        val items = Array(128) { "Preset ${it + 1}   (Program Change ${it})" }
        dialogo("Preset (Program Change) · canal ${midi.canal}", items, programa - 1) { i -> programa = i + 1; mandarPrograma() }
    }

    private fun actualizarPrograma() { btnPrograma.texto = "PRESET\n$programa"; btnPrograma.invalidate() }

    /** despues de cargar un template se reinicia la pantalla con los ajustes nuevos */
    private fun recargarCon(nombre: String) {
        cargando = true
        prefs.edit().putString("ultimo_template", nombre).commit()
        recreate()
    }

    /** deslizar desde el borde derecho abre los TEMPLATES; desde el borde izquierdo, los AJUSTES */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (menu.visibility != View.VISIBLE && ajustes.visibility != View.VISIBLE) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bordeX = -1f; bordeIzq = -1f
                    if (e.x > marco.width - dp(22)) { bordeX = e.x; return true }
                    if (e.x < dp(16)) { bordeIzq = e.x; return true }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (bordeX >= 0f) {
                        if (bordeX - e.x > dp(40)) { bordeX = -1f; abrirMenu() }
                        return true
                    }
                    if (bordeIzq >= 0f) {
                        if (e.x - bordeIzq > dp(40)) { bordeIzq = -1f; abrirAjustes() }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (bordeX >= 0f || bordeIzq >= 0f) { bordeX = -1f; bordeIzq = -1f; return true }
                }
            }
        }
        return super.dispatchTouchEvent(e)
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

    private var base = texto
    fun fijar(t: String, conectado: Boolean) { texto = t; base = t; ok = conectado; invalidate() }
    /** mensaje por 2 segundos */
    fun fijarAviso(t: String) {
        texto = t; invalidate()
        postDelayed({ texto = base; invalidate() }, 2000)
    }

    override fun onDraw(c: Canvas) {
        pPunto.color = if (ok) Estilo.VERDE else Estilo.ROJO
        c.drawCircle(16f, height / 2f, 9f, pPunto)
        pTexto.textSize = 20f
        // el texto se reparte en hasta 2 lineas (la columna es angosta)
        val maxAncho = width - 40f
        val lineas = ArrayList<String>()
        var actual = ""
        for (palabra in texto.split(" ")) {
            val prueba = if (actual.isEmpty()) palabra else "$actual $palabra"
            if (pTexto.measureText(prueba) <= maxAncho || actual.isEmpty()) actual = prueba
            else { lineas.add(actual); actual = palabra }
        }
        if (actual.isNotEmpty()) lineas.add(actual)
        var vis = lineas.take(2).toMutableList()
        if (lineas.size > 2) vis[1] = vis[1] + "…"
        vis = vis.map { l -> var t = l; while (t.length > 3 && pTexto.measureText(t) > maxAncho) t = t.dropLast(2) + "…"; t }.toMutableList()
        val alto = 24f
        var y = height / 2f - (vis.size - 1) * alto / 2f - (pTexto.ascent() + pTexto.descent()) / 2f
        for (l in vis) { c.drawText(l, 34f, y, pTexto); y += alto }
    }
}
