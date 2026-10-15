package pe.dlr.midi

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** Un cuadro de acorde. */
class Pad(context: Context, val diatonico: Boolean, val indice: Int, private val alTocar: (Pad, Boolean) -> Unit) : View(context) {
    var titulo = ""
    var sub = ""
    var seleccionado = false
    var sonando = false
    var editar = false
    private val pFondo = Estilo.pincel(Estilo.PANEL)
    private val pBorde = Estilo.pincel(Estilo.BORDE).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val pTitulo = Estilo.pincel(Estilo.TEXTO, 44f, true).apply { textAlign = Paint.Align.CENTER }
    private val pSub = Estilo.pincel(Estilo.TENUE, 22f).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> alTocar(this, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alTocar(this, false)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        r.set(4f, 4f, width - 4f, height - 4f)
        pFondo.color = when {
            sonando -> Estilo.ACENTO
            diatonico -> 0xFF2A2B30.toInt()
            else -> 0xFF22262E.toInt()
        }
        c.drawRoundRect(r, 16f, 16f, pFondo)
        val marcado = seleccionado && editar
        pBorde.color = if (marcado) Estilo.AZUL else Estilo.BORDE
        pBorde.strokeWidth = if (marcado) 7f else 3f
        c.drawRoundRect(r, 16f, 16f, pBorde)
        pTitulo.color = if (sonando) Estilo.FONDO else Estilo.TEXTO
        pTitulo.textSize = (height * 0.30f).coerceIn(22f, 46f)
        while (pTitulo.measureText(titulo) > width - 16f && pTitulo.textSize > 16f) pTitulo.textSize -= 2f
        val cy = height / 2f
        c.drawText(titulo, width / 2f, cy + pTitulo.textSize * 0.25f, pTitulo)
        pSub.color = if (sonando) Estilo.FONDO else Estilo.TENUE
        pSub.textSize = (height * 0.16f).coerceIn(14f, 22f)
        if (height > 70) c.drawText(sub, width / 2f, cy + pTitulo.textSize * 0.25f + pSub.textSize * 1.5f, pSub)
    }
}

/**
 * Pestana ACORDES (vertical):
 *  arriba 8 cuadros: los 7 acordes de la tonalidad + el I una octava arriba;
 *  abajo 12 cuadros libres (escritos en el mini piano, de la lista o aleatorios).
 *  EDITAR: los cuadros se achican y abajo aparecen las opciones del cuadro elegido.
 *  Solo usa la VEL general (sin humanize).
 */
class PaginaAcordes(
    private val act: MainActivityApi,
    private val midi: Midi,
    private val interp: Interprete,
    private val prefs: SharedPreferences,
) : LinearLayout(act.contexto()) {

    companion object { const val N_DIAT = 8; const val N_LIBRES = 12 }

    private var tonica = prefs.getInt("ac_tonica", 0)
    private var escala = prefs.getInt("ac_escala", 0)
    private var octava = prefs.getInt("ac_octava", 3)
    private var editar = false
    private var piano = false
    private val ajD = Array(N_DIAT) { Ajustes.leer(prefs.getString("ac_d$it", null)) }
    private val ajL = Array(N_LIBRES) { Ajustes.leer(prefs.getString("ac_la$it", null)) }
    private val libres = Array(N_LIBRES) { Libre.leer(prefs.getString("ac_l$it", null)) }
    private val pads = ArrayList<Pad>()
    private var sel: Pad? = null
    private val conteo = IntArray(128)
    private val notasDe = HashMap<Pad, List<Int>>()

    private val zona: LinearLayout
    private val panel: LinearLayout
    private val panelPiano: LinearLayout
    private lateinit var bTonica: Boton
    private lateinit var bEscala: Boton
    private lateinit var bOct: Boton
    private lateinit var bEditar: Boton
    private lateinit var pTitulo: Boton
    private val bInv = ArrayList<Boton>()
    private lateinit var b7: Boton; private lateinit var b9: Boton; private lateinit var b6: Boton
    private lateinit var b11: Boton; private lateinit var b13: Boton
    private lateinit var bS2: Boton; private lateinit var bS4: Boton; private lateinit var bB5: Boton; private lateinit var bA5: Boton
    private lateinit var bBajo: Boton; private lateinit var bBajoOct: Boton
    private lateinit var bEscribir: Boton; private lateinit var bLista: Boton; private lateinit var bAzar: Boton; private lateinit var bBorrar: Boton
    private lateinit var mini: MiniPiano
    private lateinit var miniTitulo: Boton

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun escala7(): IntArray = Escalas.escala(escala).intervalos
    private fun bemoles() = Escalas.usaBemoles(tonica, escala)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    init {
        orientation = VERTICAL
        setPadding(8, 4, 8, 8)
        val ctx = context
        // ---- cabecera
        val cab = fila().apply { gravity = Gravity.CENTER_VERTICAL }
        bTonica = Boton(ctx, "") { elegirTonica() }.apply { tamTexto = 24f; colorActivo = 0xFFF0B97E.toInt(); activo = true }
        bEscala = Boton(ctx, "") { elegirEscala() }.apply { tamTexto = 20f; colorActivo = 0xFFF0B97E.toInt(); activo = true }
        val om = Boton(ctx, "−") { cambiarOctava(-1) }.apply { tamTexto = 30f }
        bOct = Boton(ctx, "") { }.apply { soloTexto = true; tamTexto = 20f }
        val op = Boton(ctx, "+") { cambiarOctava(1) }.apply { tamTexto = 30f }
        bEditar = Boton(ctx, "EDITAR") { alternarEditar() }.apply { tamTexto = 22f; colorActivo = Estilo.AZUL }
        cab.addView(bTonica, LayoutParams(0, dp(38), 0.7f))
        cab.addView(bEscala, LayoutParams(0, dp(38), 1.3f))
        cab.addView(om, LayoutParams(0, dp(38), 0.4f))
        cab.addView(bOct, LayoutParams(0, dp(38), 0.6f))
        cab.addView(op, LayoutParams(0, dp(38), 0.4f))
        cab.addView(bEditar, LayoutParams(0, dp(38), 0.9f))
        addView(cab, LayoutParams(MATCH, WRAP))

        // ---- zona de cuadros: 2 filas x 4 (tonalidad) + 3 filas x 4 (libres)
        zona = LinearLayout(ctx).apply { orientation = VERTICAL }
        for (fil in 0 until 2) {
            val f = fila()
            for (col in 0 until 4) {
                val p = Pad(ctx, true, fil * 4 + col) { pad, abajo -> tocar(pad, abajo) }
                pads.add(p); f.addView(p, LayoutParams(0, MATCH, 1f))
            }
            zona.addView(f, LayoutParams(MATCH, 0, 1f))
        }
        zona.addView(separador("LIBRES"), LayoutParams(MATCH, dp(18)))
        for (fil in 0 until 3) {
            val f = fila()
            for (col in 0 until 4) {
                val p = Pad(ctx, false, fil * 4 + col) { pad, abajo -> tocar(pad, abajo) }
                pads.add(p); f.addView(p, LayoutParams(0, MATCH, 1f))
            }
            zona.addView(f, LayoutParams(MATCH, 0, 1f))
        }
        addView(zona, LayoutParams(MATCH, 0, 1f))

        // ---- panel EDITAR (un tercio de la pantalla)
        panel = LinearLayout(ctx).apply { orientation = VERTICAL; visibility = GONE; setPadding(0, 6, 0, 0) }
        val ft = fila().apply { gravity = Gravity.CENTER_VERTICAL }
        pTitulo = Boton(ctx, "") { }.apply { soloTexto = true; tamTexto = 26f }
        val bReset = Boton(ctx, "RESET") { confirmarReset() }.apply { tamTexto = 20f }
        ft.addView(pTitulo, LayoutParams(0, MATCH, 3f)); ft.addView(bReset, LayoutParams(0, MATCH, 0.8f))
        panel.addView(ft, LayoutParams(MATCH, 0, 1f))
        val fi = fila()
        for ((k, t) in listOf("FUND.", "1ª INV", "2ª INV", "3ª INV").withIndex()) {
            val b = boton(t) { fijarInv(k) }; bInv.add(b); fi.addView(b, LayoutParams(0, MATCH, 1f))
        }
        panel.addView(fi, LayoutParams(MATCH, 0, 1f))
        val fx = fila()
        b7 = boton("+7ª") { cambiar { it.siete = !it.siete; if (!it.siete && it.inv > 2) it.inv = 2 } }
        b9 = boton("+9ª") { cambiar { it.nueve = !it.nueve } }
        b6 = boton("+6ª") { cambiar { it.seis = !it.seis } }
        b11 = boton("+11ª") { cambiar { it.once = !it.once } }
        b13 = boton("+13ª") { cambiar { it.trece = !it.trece } }
        for (b in listOf(b7, b9, b6, b11, b13)) fx.addView(b, LayoutParams(0, MATCH, 1f))
        panel.addView(fx, LayoutParams(MATCH, 0, 1f))
        val fs = fila()
        bS2 = boton("SUS2") { cambiar { it.sus = if (it.sus == 2) 0 else 2 } }
        bS4 = boton("SUS4") { cambiar { it.sus = if (it.sus == 4) 0 else 4 } }
        bB5 = boton("b5 (dim)") { cambiar { it.quinta = if (it.quinta == -1) 0 else -1 } }
        bA5 = boton("#5 (aum)") { cambiar { it.quinta = if (it.quinta == 1) 0 else 1 } }
        for (b in listOf(bS2, bS4, bB5, bA5)) fs.addView(b, LayoutParams(0, MATCH, 1f))
        panel.addView(fs, LayoutParams(MATCH, 0, 1f))
        val fb = fila()
        bBajo = boton("BAJO") { elegirBajo() }
        val bOm = boton("OCT −") { octavaBajo(-1) }
        bBajoOct = Boton(ctx, "") { }.apply { soloTexto = true; tamTexto = 22f }
        val bOp = boton("OCT +") { octavaBajo(1) }
        fb.addView(bBajo, LayoutParams(0, MATCH, 1.5f)); fb.addView(bOm, LayoutParams(0, MATCH, 0.8f))
        fb.addView(bBajoOct, LayoutParams(0, MATCH, 0.8f)); fb.addView(bOp, LayoutParams(0, MATCH, 0.8f))
        panel.addView(fb, LayoutParams(MATCH, 0, 1f))
        val fl = fila()
        bEscribir = boton("ESCRIBIR") { abrirPiano() }
        bLista = boton("LISTA") { escribirLista() }
        bAzar = boton("ALEATORIO") { azar() }
        bBorrar = boton("BORRAR") { borrar() }
        for (b in listOf(bEscribir, bLista, bAzar, bBorrar)) fl.addView(b, LayoutParams(0, MATCH, 1f))
        panel.addView(fl, LayoutParams(MATCH, 0, 1f))
        addView(panel, LayoutParams(MATCH, 0, 0f))

        // ---- panel MINI PIANO (escribir tocando)
        panelPiano = LinearLayout(ctx).apply { orientation = VERTICAL; visibility = GONE; setPadding(0, 6, 0, 0) }
        miniTitulo = Boton(ctx, "") { }.apply { soloTexto = true; tamTexto = 28f }
        panelPiano.addView(miniTitulo, LayoutParams(MATCH, dp(44)))
        mini = MiniPiano(ctx, midi, { interp.velBase }) { actualizarMini() }
        mini.primeraBlanca = prefs.getInt("ac_mini", 28)
        panelPiano.addView(mini, LayoutParams(MATCH, 0, 1f))
        val fm = fila()
        val mIzq = Repetir(ctx, "◀") { mini.primeraBlanca -= 1; prefs.edit().putInt("ac_mini", mini.primeraBlanca).apply() }.apply { tamTexto = 24f }
        val mDer = Repetir(ctx, "▶") { mini.primeraBlanca += 1; prefs.edit().putInt("ac_mini", mini.primeraBlanca).apply() }.apply { tamTexto = 24f }
        val oir = object : Boton(ctx, "OÍR") {
            init { tamTexto = 20f }
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { activo = true; for (n in mini.marcadas) midi.notaOn(n, interp.velBase) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { activo = false; for (n in mini.marcadas) midi.notaOff(n) }
                }
                return true
            }
        }
        val mLimpiar = boton("LIMPIAR") { mini.marcadas.clear(); mini.invalidate(); actualizarMini() }
        val mCancelar = boton("CANCELAR") { cerrarPiano() }
        val mGuardar = boton("GUARDAR") { guardarPiano() }.apply { colorActivo = Estilo.VERDE; activo = true }
        fm.addView(mIzq, LayoutParams(0, MATCH, 0.5f)); fm.addView(mDer, LayoutParams(0, MATCH, 0.5f))
        fm.addView(oir, LayoutParams(0, MATCH, 0.7f)); fm.addView(mLimpiar, LayoutParams(0, MATCH, 0.9f))
        fm.addView(mCancelar, LayoutParams(0, MATCH, 1f)); fm.addView(mGuardar, LayoutParams(0, MATCH, 1f))
        panelPiano.addView(fm, LayoutParams(MATCH, dp(46)))
        addView(panelPiano, LayoutParams(MATCH, 0, 0f))

        refrescar()
    }

    private fun fila() = LinearLayout(context).apply { orientation = HORIZONTAL }
    private fun boton(t: String, f: () -> Unit) = Boton(context, t, f).apply { tamTexto = 20f; colorActivo = Estilo.AZUL }

    private fun separador(t: String) = object : View(context) {
        private val p = Estilo.pincel(Estilo.TENUE, 20f, true).apply { textAlign = Paint.Align.CENTER }
        private val l = Estilo.pincel(Estilo.BORDE).apply { strokeWidth = 2f }
        override fun onDraw(c: Canvas) {
            val y = height / 2f
            c.drawLine(20f, y, width / 2f - 60f, y, l); c.drawLine(width / 2f + 60f, y, width - 20f, y, l)
            c.drawText(t, width / 2f, y + 7f, p)
        }
    }

    // ------------------------------------------------------------ modelo
    private fun aj(p: Pad) = if (p.diatonico) ajD[p.indice] else ajL[p.indice]

    private fun forma(p: Pad): Acordes.Forma? =
        if (p.diatonico) Acordes.diatonico(tonica, escala7(), p.indice % 7, ajD[p.indice])
        else if (libres[p.indice].vacio) null else Acordes.libre(libres[p.indice], ajL[p.indice])

    private fun octavaDe(p: Pad) = if (p.diatonico && p.indice == 7) octava + 1 else octava

    private fun notasDe(p: Pad): List<Int>? { val f = forma(p) ?: return null; return Acordes.notas(f, aj(p), octavaDe(p)) }

    private fun bemolesDe(p: Pad, f: Acordes.Forma) =
        if (p.diatonico) bemoles() else if (f.exactas.isNotEmpty()) bemoles() else Acordes.bemolesPara(f.raiz, tonica, escala)

    private fun nombreDe(p: Pad): String {
        val f = forma(p) ?: return "+"
        val n = Acordes.notas(f, aj(p), octavaDe(p))
        return Acordes.nombre(n, if (f.exactas.isNotEmpty()) -1 else f.raiz, bemolesDe(p, f))
    }

    private fun refrescar() {
        val grados = Escalas.grados(escala)
        for (p in pads) {
            val f = forma(p)
            p.titulo = nombreDe(p)
            p.sub = when {
                p.diatonico -> (if (p.indice == 7) "I · 8ª" else grados[p.indice]) + " · " + Escalas.nombreNota(f!!.raiz, tonica, escala)
                f == null -> if (editar) "vacío" else "toca: escribir"
                f.exactas.isNotEmpty() -> "tocado"
                else -> Escalas.nombreNota(f.raiz, tonica, escala)
            }
            p.editar = editar
            p.seleccionado = p == sel
            p.invalidate()
        }
        bTonica.texto = Escalas.nombreNota(tonica, tonica, escala); bTonica.invalidate()
        bEscala.texto = Escalas.escala(escala).nombre.uppercase(); bEscala.invalidate()
        bOct.texto = "OCTAVA\nC$octava"; bOct.invalidate()
        bEditar.activo = editar
        // tamanos: con EDITAR los cuadros ocupan 2/3 y el panel 1/3
        (zona.layoutParams as LayoutParams).weight = if (editar) (if (piano) 1.1f else 2f) else 1f
        panel.visibility = if (editar && !piano) VISIBLE else GONE
        (panel.layoutParams as LayoutParams).weight = if (editar && !piano) 1f else 0f
        panelPiano.visibility = if (editar && piano) VISIBLE else GONE
        (panelPiano.layoutParams as LayoutParams).weight = if (editar && piano) 1f else 0f
        requestLayout()
        refrescarPanel()
    }

    private fun refrescarPanel() {
        val p = sel
        val a = p?.let { aj(it) }
        val f = p?.let { forma(it) }
        pTitulo.texto = when {
            p == null -> "Toca un cuadro para editarlo"
            f == null -> "Libre ${p.indice + 1}: vacío"
            else -> (if (p.diatonico) (if (p.indice == 7) "I (8ª)" else "Grado " + Escalas.grados(escala)[p.indice]) else "Libre ${p.indice + 1}") + ":  " + nombreDe(p)
        }
        pTitulo.invalidate()
        val exacto = f?.exactas?.isNotEmpty() == true
        val nTonos = if (f == null) 3 else if (exacto) f.exactas.size else f.tonos.size
        bInv.forEachIndexed { k, b -> b.activo = a != null && a.inv == k; b.alpha = if (k < nTonos) 1f else 0.3f; b.invalidate() }
        fun tog(b: Boton, on: Boolean, usable: Boolean) { b.activo = on && usable; b.alpha = if (usable) 1f else 0.3f; b.invalidate() }
        val armable = f != null && !exacto
        tog(b7, a?.siete == true, armable && p?.diatonico == true)
        tog(b9, a?.nueve == true, armable); tog(b6, a?.seis == true, armable)
        tog(b11, a?.once == true, armable); tog(b13, a?.trece == true, armable)
        tog(bS2, a?.sus == 2, armable); tog(bS4, a?.sus == 4, armable)
        tog(bB5, a?.quinta == -1, armable); tog(bA5, a?.quinta == 1, armable)
        val conBajo = a != null && a.bajo >= 0
        bBajo.texto = if (!conBajo) "BAJO: normal" else "BAJO: " + Escalas.nombreNota(a!!.bajo, tonica, escala)
        bBajo.activo = conBajo; bBajo.invalidate()
        bBajoOct.texto = if (!conBajo || f == null) "—" else {
            val o = if (a!!.bajoOct >= 0) a.bajoOct + octavaDe(p!!) - Acordes.OCT_REF else Acordes.bajoAuto(f, a, octavaDe(p))
            Acordes.letra(a.bajo, bemoles()) + o + if (a.bajoOct < 0) "\nauto" else ""
        }
        bBajoOct.invalidate()
        val libre = p != null && !p.diatonico
        for (b in listOf(bEscribir, bLista, bAzar, bBorrar)) { b.alpha = if (libre) 1f else 0.3f; b.invalidate() }
    }

    fun guardar() {
        val e = prefs.edit()
        e.putInt("ac_tonica", tonica).putInt("ac_escala", escala).putInt("ac_octava", octava)
        ajD.forEachIndexed { i, a -> e.putString("ac_d$i", a.guardar()) }
        ajL.forEachIndexed { i, a -> e.putString("ac_la$i", a.guardar()) }
        libres.forEachIndexed { i, l -> e.putString("ac_l$i", l.guardar()) }
        e.apply()
    }

    // ------------------------------------------------------------ tocar
    private fun tocar(p: Pad, abajo: Boolean) {
        if (editar) {
            if (abajo) {
                if (piano) return
                sel = p; refrescar()
                // en EDITAR el cuadro suena mientras lo tocas, para oir el cambio
                notasDe(p)?.let { ns -> for (n in ns) midi.notaOn(n, interp.velBase); notasDe[p] = ns }
            } else soltar(p)
            return
        }
        if (abajo) {
            val ns = notasDe(p)
            if (ns == null) { sel = p; editar = true; refrescar(); abrirPiano(); return }
            soltar(p)
            for (n in ns) { if (conteo[n] > 0) midi.notaOff(n); midi.notaOn(n, interp.velBase); conteo[n]++ }
            notasDe[p] = ns
            p.sonando = true
        } else {
            soltar(p); p.sonando = false
        }
        p.invalidate()
    }

    private fun soltar(p: Pad) {
        val ns = notasDe.remove(p) ?: return
        if (editar) { for (n in ns) midi.notaOff(n); return }
        for (n in ns) { if (conteo[n] > 0) { conteo[n]--; if (conteo[n] == 0) midi.notaOff(n) } }
    }

    fun soltarTodo() {
        for (p in pads) { soltar(p); p.sonando = false; p.invalidate() }
        for (n in 0..127) if (conteo[n] > 0) { conteo[n] = 0; midi.notaOff(n) }
    }

    // ------------------------------------------------------------ acciones
    private fun alternarEditar() { soltarTodo(); editar = !editar; piano = false; refrescar() }

    private fun cambiarOctava(d: Int) { soltarTodo(); octava = (octava + d).coerceIn(0, 7); guardar(); refrescar() }

    private fun elegirTonica() {
        act.dialogo("Tonalidad de los acordes", Escalas.TONICAS, tonica) { i -> soltarTodo(); tonica = i; guardar(); refrescar() }
    }

    private fun elegirEscala() {
        val siete = Escalas.LISTA.withIndex().filter { it.value.intervalos.size == 7 }
        act.dialogo("Escala", Array(siete.size) { siete[it].value.nombre }, siete.indexOfFirst { it.index == escala }) { i ->
            soltarTodo(); escala = siete[i].index; guardar(); refrescar()
        }
    }

    private fun cambiar(f: (Ajustes) -> Unit) {
        val p = sel ?: return
        val fo = forma(p) ?: return
        if (fo.exactas.isNotEmpty()) return
        f(aj(p)); guardar(); refrescar()
    }

    private fun fijarInv(k: Int) {
        val p = sel ?: return
        val f = forma(p) ?: return
        val n = if (f.exactas.isNotEmpty()) f.exactas.size else f.tonos.size
        if (k >= n) return
        aj(p).inv = k; guardar(); refrescar()
    }

    private fun elegirBajo() {
        val p = sel ?: return
        val items = arrayOf("Normal (según la inversión)") + Array(12) { Escalas.nombreNota(it, tonica, escala) + "  (" + Acordes.letra(it, bemoles()) + ")" }
        act.dialogo("Nota del bajo", items, aj(p).bajo + 1) { i -> aj(p).bajo = i - 1; guardar(); refrescar() }
    }

    private fun octavaBajo(d: Int) {
        val p = sel ?: return
        val f = forma(p) ?: return
        val a = aj(p)
        if (a.bajo < 0) return
        val mover = octavaDe(p) - Acordes.OCT_REF
        val actual = if (a.bajoOct >= 0) a.bajoOct + mover else Acordes.bajoAuto(f, a, octavaDe(p))
        a.bajoOct = ((actual + d).coerceIn(0, 7) - mover).coerceAtLeast(0)
        guardar(); refrescar()
    }

    private fun confirmarReset() {
        act.dialogo("RESET: ¿volver todos los cuadros a fundamental, sin 7ª/9ª/sus/bajo?",
            arrayOf("Sí, resetear los ajustes (los acordes escritos se quedan)"), -1) {
            for (i in ajD.indices) ajD[i] = Ajustes()
            for (i in ajL.indices) ajL[i] = Ajustes()
            guardar(); refrescar()
        }
    }

    private fun escribirLista() {
        val p = sel ?: return
        if (p.diatonico) return
        val raices = Array(12) { Escalas.nombreNota(it, tonica, escala) + "  (" + Acordes.letra(it, bemoles()) + ")" }
        act.dialogo("Raíz del acorde", raices, libres[p.indice].raiz) { r ->
            val tipos = Array(Acordes.TIPOS.size) { Acordes.TIPOS[it].nombre }
            act.dialogo("Tipo de acorde", tipos, libres[p.indice].tipo) { t ->
                libres[p.indice] = Libre(r, t); ajL[p.indice] = Ajustes()
                guardar(); refrescar()
            }
        }
    }

    private fun abrirPiano() {
        val p = sel ?: return
        if (p.diatonico) return
        soltarTodo()
        mini.marcadas.clear()
        notasDe(p)?.let { mini.marcadas.addAll(it) }
        piano = true; refrescar(); actualizarMini()
    }

    private fun actualizarMini() {
        val ns = mini.marcadas.toList()
        miniTitulo.texto = if (ns.isEmpty()) "Toca las notas del acorde" else
            Acordes.nombre(ns, -1, bemoles()) + "   ·   " + ns.joinToString(" ") { Acordes.nombreMidi(it, bemoles()) }
        miniTitulo.invalidate()
    }

    private fun cerrarPiano() { piano = false; refrescar() }

    private fun guardarPiano() {
        val p = sel ?: return
        if (mini.marcadas.isNotEmpty()) {
            // se guarda "en C3": asi sigue a la OCTAVA general
            val mover = (octavaDe(p) - Acordes.OCT_REF) * 12
            libres[p.indice] = Libre(notas = mini.marcadas.map { it - mover })
            ajL[p.indice] = Ajustes()
            guardar()
        }
        cerrarPiano()
    }

    private fun azar() {
        val p = sel ?: return
        if (p.diatonico) return
        val (l, a) = Acordes.aleatorio(tonica, escala)
        libres[p.indice] = l; ajL[p.indice] = a
        guardar(); refrescar()
    }

    private fun borrar() {
        val p = sel ?: return
        if (p.diatonico) return
        libres[p.indice] = Libre(); ajL[p.indice] = Ajustes()
        guardar(); refrescar()
    }
}

/** Lo que la pagina necesita de la actividad. */
interface MainActivityApi {
    fun contexto(): Context
    fun dialogo(titulo: String, items: Array<String>, marcado: Int, alElegir: (Int) -> Unit)
}
