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
    private val pTitulo = Estilo.pincel(Estilo.TEXTO, 52f, true).apply { textAlign = Paint.Align.CENTER }
    private val pSub = Estilo.pincel(Estilo.TENUE, 26f).apply { textAlign = Paint.Align.CENTER }
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
        r.set(6f, 6f, width - 6f, height - 6f)
        pFondo.color = when {
            sonando -> Estilo.ACENTO
            diatonico -> 0xFF2A2B30.toInt()
            else -> 0xFF22262E.toInt()
        }
        c.drawRoundRect(r, 18f, 18f, pFondo)
        pBorde.color = if (seleccionado && editar) Estilo.AZUL else Estilo.BORDE
        pBorde.strokeWidth = if (seleccionado && editar) 7f else 3f
        c.drawRoundRect(r, 18f, 18f, pBorde)
        pTitulo.color = if (sonando) Estilo.FONDO else Estilo.TEXTO
        pTitulo.textSize = 52f
        while (pTitulo.measureText(titulo) > width - 24f && pTitulo.textSize > 22f) pTitulo.textSize -= 2f
        c.drawText(titulo, width / 2f, height / 2f + 8f, pTitulo)
        pSub.color = if (sonando) Estilo.FONDO else Estilo.TENUE
        c.drawText(sub, width / 2f, height / 2f + 50f, pSub)
    }
}

/**
 * Pestana ACORDES (vertical):
 *  arriba 7 acordes de la tonalidad, abajo 6 cuadros libres (escritos o aleatorios).
 *  EDITAR: tocar un cuadro lo selecciona y muestra inversion / 7a / 9a / bajo.
 *  Solo usa la VEL general (sin humanize).
 */
class PaginaAcordes(
    private val act: MainActivityApi,
    private val midi: Midi,
    private val interp: Interprete,
    private val prefs: SharedPreferences,
) : LinearLayout(act.contexto()) {

    private var tonica = prefs.getInt("ac_tonica", 0)
    private var escala = prefs.getInt("ac_escala", 0)
    private var octava = prefs.getInt("ac_octava", 3)
    private var editar = false
    private val ajD = Array(7) { Ajustes.leer(prefs.getString("ac_d$it", null)) }
    private val ajL = Array(6) { Ajustes.leer(prefs.getString("ac_la$it", null)) }
    private val libres = Array(6) { Libre.leer(prefs.getString("ac_l$it", null)) }
    private val pads = ArrayList<Pad>()
    private var sel: Pad? = null
    private val conteo = IntArray(128)
    private val notasDe = HashMap<Pad, List<Int>>()

    private lateinit var bTonica: Boton
    private lateinit var bEscala: Boton
    private lateinit var bOct: Boton
    private lateinit var bEditar: Boton
    private lateinit var panel: LinearLayout
    private lateinit var pTitulo: Boton
    private val bInv = ArrayList<Boton>()
    private lateinit var b7: Boton
    private lateinit var b9: Boton
    private lateinit var bBajo: Boton
    private lateinit var bEscribir: Boton
    private lateinit var bAzar: Boton
    private lateinit var bBorrar: Boton

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun escala7(): IntArray = Escalas.escala(escala).intervalos
    private fun bemoles() = Escalas.usaBemoles(tonica, escala)

    init {
        orientation = VERTICAL
        setPadding(8, 4, 8, 8)
        val ctx = context
        // ---- cabecera
        val cab = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bTonica = Boton(ctx, "") { elegirTonica() }.apply { tamTexto = 24f; colorActivo = 0xFFF0B97E.toInt(); activo = true }
        bEscala = Boton(ctx, "") { elegirEscala() }.apply { tamTexto = 22f; colorActivo = 0xFFF0B97E.toInt(); activo = true }
        val om = Boton(ctx, "−") { cambiarOctava(-1) }.apply { tamTexto = 30f }
        bOct = Boton(ctx, "") { }.apply { soloTexto = true; tamTexto = 22f }
        val op = Boton(ctx, "+") { cambiarOctava(1) }.apply { tamTexto = 30f }
        bEditar = Boton(ctx, "EDITAR") { alternarEditar() }.apply { tamTexto = 24f; colorActivo = Estilo.AZUL }
        cab.addView(bTonica, LayoutParams(0, dp(40), 0.8f))
        cab.addView(bEscala, LayoutParams(0, dp(40), 1.3f))
        cab.addView(om, LayoutParams(0, dp(40), 0.4f))
        cab.addView(bOct, LayoutParams(0, dp(40), 0.6f))
        cab.addView(op, LayoutParams(0, dp(40), 0.4f))
        cab.addView(bEditar, LayoutParams(0, dp(40), 0.9f))
        addView(cab, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- 7 diatonicos (4 + 3)
        val f1 = fila(); val f2 = fila()
        for (g in 0 until 7) {
            val p = Pad(ctx, true, g) { pad, abajo -> tocar(pad, abajo) }
            pads.add(p)
            (if (g < 4) f1 else f2).addView(p, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        addView(f1, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(f2, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(separador("LIBRES"), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        // ---- 6 libres (2 x 3)
        for (fil in 0 until 3) {
            val f = fila()
            for (col in 0 until 2) {
                val i = fil * 2 + col
                val p = Pad(ctx, false, i) { pad, abajo -> tocar(pad, abajo) }
                pads.add(p)
                f.addView(p, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            addView(f, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // ---- panel de EDITAR
        panel = LinearLayout(ctx).apply { orientation = VERTICAL; visibility = GONE; setPadding(0, 8, 0, 0) }
        pTitulo = Boton(ctx, "Toca un cuadro para editarlo") { }.apply { soloTexto = true; tamTexto = 26f }
        panel.addView(pTitulo, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        val fi = fila()
        for ((k, t) in listOf("FUND.", "1ª INV", "2ª INV", "3ª INV").withIndex()) {
            val b = Boton(ctx, t) { fijarInv(k) }.apply { tamTexto = 22f; colorActivo = Estilo.AZUL }
            bInv.add(b); fi.addView(b, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        panel.addView(fi, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        val fx = fila()
        b7 = Boton(ctx, "+7ª") { alternar7() }.apply { tamTexto = 22f; colorActivo = Estilo.AZUL }
        b9 = Boton(ctx, "+9ª") { alternar9() }.apply { tamTexto = 22f; colorActivo = Estilo.AZUL }
        bBajo = Boton(ctx, "BAJO") { elegirBajo() }.apply { tamTexto = 22f; colorActivo = Estilo.AZUL }
        fx.addView(b7, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        fx.addView(b9, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        fx.addView(bBajo, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
        panel.addView(fx, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        val fl = fila()
        bEscribir = Boton(ctx, "ESCRIBIR") { escribir() }.apply { tamTexto = 22f }
        bAzar = Boton(ctx, "ALEATORIO") { azar() }.apply { tamTexto = 22f }
        bBorrar = Boton(ctx, "BORRAR") { borrar() }.apply { tamTexto = 22f }
        fl.addView(bEscribir, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        fl.addView(bAzar, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        fl.addView(bBorrar, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        panel.addView(fl, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        refrescar()
    }

    private fun fila() = LinearLayout(context).apply { orientation = HORIZONTAL }

    private fun separador(t: String) = object : View(context) {
        private val p = Estilo.pincel(Estilo.TENUE, 22f, true).apply { textAlign = Paint.Align.CENTER }
        private val l = Estilo.pincel(Estilo.BORDE).apply { strokeWidth = 2f }
        override fun onDraw(c: Canvas) {
            val y = height / 2f
            c.drawLine(20f, y, width / 2f - 70f, y, l); c.drawLine(width / 2f + 70f, y, width - 20f, y, l)
            c.drawText(t, width / 2f, y + 8f, p)
        }
    }

    // ------------------------------------------------------------ modelo
    private fun aj(p: Pad) = if (p.diatonico) ajD[p.indice] else ajL[p.indice]

    private fun forma(p: Pad): Acordes.Forma? =
        if (p.diatonico) Acordes.diatonico(tonica, escala7(), p.indice, ajD[p.indice])
        else if (libres[p.indice].vacio) null else Acordes.libre(libres[p.indice], ajL[p.indice])

    private fun refrescar() {
        val b = bemoles()
        val grados = Escalas.grados(escala)
        for (p in pads) {
            val f = forma(p)
            p.titulo = if (f == null) "+" else Acordes.nombre(f, aj(p), if (p.diatonico) b else Acordes.bemolesPara(f.raiz, tonica, escala))
            p.sub = if (p.diatonico) grados[p.indice] + " · " + Escalas.nombreNota(f!!.raiz, tonica, escala)
                    else if (f == null) (if (editar) "vacío" else "toca para escribir") else Escalas.nombreNota(f.raiz, tonica, escala)
            p.editar = editar
            p.seleccionado = p == sel
            p.invalidate()
        }
        bTonica.texto = Escalas.nombreNota(tonica, tonica, escala); bTonica.invalidate()
        bEscala.texto = Escalas.escala(escala).nombre.uppercase(); bEscala.invalidate()
        bOct.texto = "OCTAVA\nC$octava"; bOct.invalidate()
        bEditar.activo = editar
        panel.visibility = if (editar) VISIBLE else GONE
        refrescarPanel()
    }

    private fun refrescarPanel() {
        val p = sel
        val a = p?.let { aj(it) }
        val f = p?.let { forma(it) }
        pTitulo.texto = when {
            p == null -> "Toca un cuadro para editarlo"
            f == null -> "Cuadro libre ${p.indice + 1}: vacío"
            else -> (if (p.diatonico) "Grado " + Escalas.grados(escala)[p.indice] else "Libre ${p.indice + 1}") + ":  " +
                    Acordes.nombre(f, a!!, if (p.diatonico) bemoles() else Acordes.bemolesPara(f.raiz, tonica, escala))
        }
        pTitulo.invalidate()
        val nTonos = f?.base?.size ?: 3
        bInv.forEachIndexed { k, b -> b.activo = a != null && a.inv == k; b.alpha = if (k < nTonos) 1f else 0.3f; b.invalidate() }
        b7.activo = a?.siete == true; b7.alpha = if (p?.diatonico == true) 1f else 0.3f; b7.invalidate()
        b9.activo = a?.nueve == true; b9.invalidate()
        bBajo.texto = if (a == null || a.bajo < 0) "BAJO: normal" else "BAJO: " + Escalas.nombreNota(a.bajo, tonica, escala)
        bBajo.activo = a != null && a.bajo >= 0; bBajo.invalidate()
        val libre = p != null && !p.diatonico
        for (b in listOf(bEscribir, bAzar, bBorrar)) { b.alpha = if (libre) 1f else 0.3f; b.invalidate() }
    }

    private fun guardar() {
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
            if (abajo) { sel = p; refrescar() }
            return
        }
        if (abajo) {
            val f = forma(p)
            if (f == null) { sel = p; escribir(); return }
            val notas = Acordes.notas(f, aj(p), octava)
            soltar(p)
            for (n in notas) { if (conteo[n] == 0) midi.notaOn(n, interp.velBase) else { midi.notaOff(n); midi.notaOn(n, interp.velBase) }; conteo[n]++ }
            notasDe[p] = notas
            p.sonando = true
        } else {
            soltar(p)
            p.sonando = false
        }
        p.invalidate()
    }

    private fun soltar(p: Pad) {
        val ns = notasDe.remove(p) ?: return
        for (n in ns) { if (conteo[n] > 0) { conteo[n]--; if (conteo[n] == 0) midi.notaOff(n) } }
    }

    fun soltarTodo() {
        for (p in pads) { soltar(p); p.sonando = false; p.invalidate() }
        for (n in 0..127) if (conteo[n] > 0) { conteo[n] = 0; midi.notaOff(n) }
    }

    // ------------------------------------------------------------ acciones
    private fun alternarEditar() { soltarTodo(); editar = !editar; refrescar() }

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

    private fun fijarInv(k: Int) {
        val p = sel ?: return
        val f = forma(p) ?: return
        if (k >= f.base.size) return
        aj(p).inv = k; guardar(); refrescar()
    }

    private fun alternar7() {
        val p = sel ?: return
        if (!p.diatonico) return
        val a = aj(p); a.siete = !a.siete
        if (!a.siete && a.inv > 2) a.inv = 2
        guardar(); refrescar()
    }

    private fun alternar9() { val p = sel ?: return; aj(p).nueve = !aj(p).nueve; guardar(); refrescar() }

    private fun elegirBajo() {
        val p = sel ?: return
        val items = arrayOf("Normal (según la inversión)") + Array(12) { Escalas.nombreNota(it, tonica, escala) + "  (" + Acordes.letra(it, bemoles()) + ")" }
        act.dialogo("Nota del bajo", items, aj(p).bajo + 1) { i -> aj(p).bajo = i - 1; guardar(); refrescar() }
    }

    private fun escribir() {
        val p = sel ?: return
        if (p.diatonico) return
        val raices = Array(12) { Escalas.nombreNota(it, tonica, escala) + "  (" + Acordes.letra(it, bemoles()) + ")" }
        act.dialogo("Raíz del acorde", raices, libres[p.indice].raiz) { r ->
            val tipos = Array(Acordes.TIPOS.size) { Acordes.TIPOS[it].nombre }
            act.dialogo("Tipo de acorde", tipos, libres[p.indice].tipo) { t ->
                libres[p.indice] = Libre(r, t)
                val a = aj(p); a.siete = false
                if (a.inv >= Acordes.TIPOS[t].intervalos.size) a.inv = 0
                guardar(); refrescar()
            }
        }
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
