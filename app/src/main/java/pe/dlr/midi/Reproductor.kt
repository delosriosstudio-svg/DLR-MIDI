package pe.dlr.midi

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Random

/**
 * Formas de tocar los acordes de la pestana ACORDES:
 *  NORMAL (bloque), RASGUEO (arpegio pequeno y realista), ARPEGIO (notas una por una a tempo),
 *  PULSO (una nota del acorde en ritmo), RITMO (el acorde entero en ritmo). LATCH opcional.
 */
class Reproductor(private val midi: Midi, private val prefs: SharedPreferences, private val vel: () -> Int) {

    companion object {
        val MODOS = arrayOf("NORMAL", "RASGUEO", "ARPEGIO", "PULSO", "RITMO")
        val ARP_PATRONES = arrayOf("Sube", "Baja", "Sube y baja", "Al azar", "Como está escrito")
        val DIVISIONES = arrayOf("1/4", "1/8", "1/16", "1/8 tresillo", "1/16 tresillo")
        private val DIV_FACTOR = floatArrayOf(1f, 0.5f, 0.25f, 1f / 3f, 1f / 6f)
        val PULSO_NOTAS = arrayOf("La más grave", "La más aguda", "Grave + su octava")

        /** ritmo: nombre, duracion de un paso (en negras), largo del ciclo, golpes (paso, duracion en pasos) */
        class Ritmo(val nombre: String, val paso: Float, val largo: Int, val golpes: List<Pair<Int, Int>>, val acentos: Set<Int>)
        val RITMOS = listOf(
            Ritmo("Negras", 0.25f, 16, listOf(0 to 4, 4 to 4, 8 to 4, 12 to 4), setOf(0)),
            Ritmo("Corcheas", 0.25f, 16, (0 until 16 step 2).map { it to 2 }, setOf(0, 8)),
            Ritmo("Semicorcheas", 0.25f, 16, (0 until 16).map { it to 1 }, setOf(0, 4, 8, 12)),
            Ritmo("Pop 3-3-2", 0.25f, 16, listOf(0 to 3, 3 to 3, 6 to 2, 8 to 3, 11 to 3, 14 to 2), setOf(0, 8)),
            Ritmo("Charleston", 0.25f, 16, listOf(0 to 2, 6 to 2), setOf(0)),
            Ritmo("Reggae (contratiempo)", 0.25f, 16, listOf(2 to 1, 6 to 1, 10 to 1, 14 to 1), setOf()),
            Ritmo("Galope", 0.25f, 16, (0 until 4).flatMap { b -> listOf(b * 4 to 2, b * 4 + 2 to 1, b * 4 + 3 to 1) }, setOf(0, 8)),
            Ritmo("Balada 6/8", 0.5f, 6, (0 until 6).map { it to 1 }, setOf(0, 3)),
            Ritmo("Cinemático (ostinato)", 0.25f, 16, listOf(0 to 2, 2 to 1, 3 to 1, 4 to 2, 6 to 1, 7 to 1, 8 to 2, 10 to 1, 11 to 1, 12 to 2, 14 to 1, 15 to 1), setOf(0, 4, 8, 12)),
        )
    }

    // ---- configuracion (vive en prefs, asi entra en los templates)
    var modo = 0; var sep = 0; var arp = 0; var div = 2; var arpOct = 1; var ritmo = 3; var pulso = 0; var bpm = 100; var latch = false
    fun leer() {
        modo = prefs.getInt("ac_modo", 0); sep = prefs.getInt("ac_sep", 0); arp = prefs.getInt("ac_arp", 0)
        div = prefs.getInt("ac_div", 2); arpOct = prefs.getInt("ac_arpoct", 1); ritmo = prefs.getInt("ac_ritmo", 3)
        pulso = prefs.getInt("ac_pulso", 0); bpm = prefs.getInt("bpm", 100); latch = prefs.getBoolean("ac_latch", false)
    }
    init { leer() }

    private val h = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private val conteo = IntArray(128)

    private fun on(n: Int, v: Int) { if (n !in 0..127) return; if (conteo[n] > 0) midi.notaOff(n); midi.notaOn(n, v.coerceIn(1, 127)); conteo[n]++ }
    private fun off(n: Int) { if (n !in 0..127 || conteo[n] == 0) return; conteo[n]--; if (conteo[n] == 0) midi.notaOff(n) }

    /** una voz = un cuadro sonando */
    private inner class Voz(val notas: List<Int>) {
        val sonando = ArrayList<Int>()
        val tareas = ArrayList<Runnable>()
        var vivo = true
        fun programar(enMs: Long, r: () -> Unit) {
            val t = object : Runnable { override fun run() { tareas.remove(this); if (vivo) r() } }
            tareas.add(t); h.postAtTime(t, SystemClock.uptimeMillis() + enMs.coerceAtLeast(0))
        }
        fun tocar(n: Int, v: Int) { on(n, v); sonando.add(n) }
        fun soltar(n: Int) { if (sonando.remove(n)) off(n) }
        fun parar() { vivo = false; for (t in tareas) h.removeCallbacks(t); tareas.clear(); for (n in sonando) off(n); sonando.clear() }
    }

    private val voces = HashMap<Any, Voz>()
    /** reloj comun de los patrones: al cambiar de acorde el ritmo no se reinicia */
    private var reloj0 = -1L
    /** en ARPEGIO / PULSO / RITMO los cuadros son interruptores (siempre LATCH) */
    val latchEfectivo get() = latch || modo >= 2
    val activos: Set<Any> get() = voces.keys

    fun estaSonando(clave: Any) = voces.containsKey(clave)

    /** dedo abajo sobre un cuadro. Devuelve true si quedo sonando. */
    fun abajo(clave: Any, notas: List<Int>): Boolean {
        if (latchEfectivo) {
            if (voces.containsKey(clave)) { parar(clave); return false }
            // cambiar de acorde: se apaga el anterior pero el reloj sigue (el cambio cae a tiempo)
            for (k in voces.keys.toList()) voces.remove(k)?.parar()
        } else parar(clave)
        if (notas.isEmpty()) return false
        val v = Voz(notas.sorted())
        voces[clave] = v
        arrancar(v)
        return true
    }

    /** dedo arriba. Devuelve true si sigue sonando (LATCH). */
    fun arriba(clave: Any): Boolean {
        if (latchEfectivo) return voces.containsKey(clave)
        parar(clave); return false
    }

    fun parar(clave: Any) { voces.remove(clave)?.parar(); if (voces.isEmpty()) reloj0 = -1L }
    fun pararTodo() { for (k in voces.keys.toList()) parar(k); reloj0 = -1L; for (n in 0..127) if (conteo[n] > 0) { conteo[n] = 0; midi.notaOff(n) } }

    private fun negraMs() = 60000f / bpm.coerceIn(20, 300)

    private fun arrancar(v: Voz) {
        val base = vel()
        when (modo) {
            1 -> {   // RASGUEO: de grave a agudo; mas fuerte = mas rapido; pequena variacion natural
                val n = v.notas.size
                val auto = (30f - 18f * base / 127f) * (if (n > 4) 0.8f else 1f)
                val d = if (sep > 0) sep.toFloat() else auto
                var t = 0f
                v.notas.forEachIndexed { i, nota ->
                    val vi = base - i * 2 + rnd.nextInt(5) - 2       // las notas de arriba un poco mas suaves
                    if (i == 0) v.tocar(nota, vi) else v.programar(t.toLong()) { v.tocar(nota, vi) }
                    t += d * (0.75f + rnd.nextFloat() * 0.5f)
                }
            }
            2 -> {   // ARPEGIO
                val sube = ArrayList<Int>()
                for (o in 0 until arpOct.coerceIn(1, 3)) for (nota in v.notas) sube.add(nota + 12 * o)
                val seq: List<Int> = when (arp) {
                    1 -> sube.reversed()
                    2 -> if (sube.size > 2) sube + sube.subList(1, sube.size - 1).reversed() else sube
                    4 -> v.notas
                    else -> sube
                }
                val paso = negraMs() * DIV_FACTOR[div.coerceIn(0, 4)]
                val ahora = SystemClock.uptimeMillis()
                if (reloj0 < 0) reloj0 = ahora
                // entra en el siguiente paso del reloj (o ya mismo si recien arranca)
                val k0 = Math.ceil((ahora - reloj0) / paso.toDouble() - 0.02).toInt().coerceAtLeast(0)
                pasoArp(v, seq, k0, k0, paso, reloj0, base)
            }
            3, 4 -> {   // PULSO / RITMO
                val r = RITMOS[ritmo.coerceIn(0, RITMOS.size - 1)]
                val ahora = SystemClock.uptimeMillis()
                if (reloj0 < 0) reloj0 = ahora
                val ciclo = negraMs() * r.paso * r.largo
                val c = ((ahora - reloj0) / ciclo).toInt().coerceAtLeast(0)
                cicloRitmo(v, r, c, reloj0, base, primero = true)
            }
            else -> for (nota in v.notas) v.tocar(nota, base)   // NORMAL
        }
    }

    private fun pasoArp(v: Voz, seq: List<Int>, k: Int, k0: Int, paso: Float, t0: Long, base: Int) {
        if (!v.vivo || seq.isEmpty()) return
        val tk = t0 + (k * paso).toLong()
        val espera = tk - SystemClock.uptimeMillis()
        if (espera > 2) { v.programar(espera) { pasoArp(v, seq, k, k0, paso, t0, base) }; return }
        val i = k - k0
        val nota = if (arp == 3) seq[rnd.nextInt(seq.size)] else seq[i % seq.size]
        v.tocar(nota, base + (if (i % seq.size == 0) 8 else 0))
        v.programar((paso * 0.85f).toLong()) { v.soltar(nota) }
        val siguiente = t0 + ((k + 1) * paso).toLong()
        v.programar(siguiente - SystemClock.uptimeMillis()) { pasoArp(v, seq, k + 1, k0, paso, t0, base) }
    }

    private fun cicloRitmo(v: Voz, r: Ritmo, ciclo: Int, t0: Long, base: Int, primero: Boolean = false) {
        if (!v.vivo) return
        val paso = negraMs() * r.paso
        val inicio = t0 + (ciclo * r.largo * paso).toLong()
        val ahora = SystemClock.uptimeMillis()
        val notas: List<Int> = if (modo == 4) v.notas else when (pulso) {
            1 -> listOf(v.notas.last())
            else -> listOf(v.notas.first())
        }
        for ((i, g) in r.golpes.withIndex()) {
            val (s, dur) = g
            val vv = base + (if (s in r.acentos) 10 else 0) + (if (s == 0) 4 else 0)
            val tn = inicio + (s * paso).toLong() - ahora
            // al entrar a mitad de ciclo: los golpes que ya pasaron se saltan (el cambio cae a tiempo)
            if (primero && tn < -15) continue
            val octava = modo == 3 && pulso == 2 && i % 2 == 1
            v.programar(tn) { for (n in notas) v.tocar(if (octava) n + 12 else n, vv) }
            v.programar(tn + (dur * paso * 0.85f).toLong()) { for (n in notas) v.soltar(if (octava) n + 12 else n) }
        }
        v.programar(inicio + (r.largo * paso).toLong() - ahora) { cicloRitmo(v, r, ciclo + 1, t0, base) }
    }
}
