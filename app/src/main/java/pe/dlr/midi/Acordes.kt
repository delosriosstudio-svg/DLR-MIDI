package pe.dlr.midi

import java.util.Random

/** Ajustes que recuerda cada cuadro. */
class Ajustes(
    var inv: Int = 0,           // 0 fundamental, 1..3 inversiones
    var siete: Boolean = false,
    var nueve: Boolean = false,
    var bajo: Int = -1,         // -1 = normal; 0..11 = bajo en esa nota
    var bajoOct: Int = -1,      // -1 = automatico; si no, octava (Mi2 = 2)
    var seis: Boolean = false,
    var once: Boolean = false,
    var trece: Boolean = false,
    var sus: Int = 0,           // 0, 2 (sus2) o 4 (sus4)
    var quinta: Int = 0,        // 0, -1 (b5) o +1 (#5)
) {
    fun guardar() = listOf(inv, b(siete), b(nueve), bajo, bajoOct, b(seis), b(once), b(trece), sus, quinta).joinToString(",")
    private fun b(x: Boolean) = if (x) 1 else 0
    fun copia() = leer(guardar())
    companion object {
        fun leer(s: String?): Ajustes {
            val p = s?.split(",") ?: return Ajustes()
            val a = Ajustes()
            try {
                a.inv = p[0].toInt(); a.siete = p[1] == "1"; a.nueve = p[2] == "1"; a.bajo = p[3].toInt()
                if (p.size >= 10) {
                    a.bajoOct = p[4].toInt(); a.seis = p[5] == "1"; a.once = p[6] == "1"; a.trece = p[7] == "1"
                    a.sus = p[8].toInt(); a.quinta = p[9].toInt()
                }
            } catch (_: Exception) { }
            return a
        }
    }
}

/** Un tipo de acorde de la lista. */
class Tipo(val nombre: String, val intervalos: IntArray)

/** Acorde de un cuadro libre: raiz + tipo, o notas exactas (tocadas en el mini piano), o vacio. */
class Libre(var raiz: Int = -1, var tipo: Int = 0, var notas: List<Int> = emptyList()) {
    val vacio get() = raiz < 0 && notas.isEmpty()
    val exacto get() = notas.isNotEmpty()
    fun guardar() = if (exacto) "n:" + notas.joinToString(".") else "$raiz,$tipo"
    companion object {
        fun leer(s: String?): Libre {
            if (s == null) return Libre()
            return try {
                if (s.startsWith("n:")) Libre(notas = s.substring(2).split(".").map { it.toInt() }.sorted())
                else { val p = s.split(","); Libre(p[0].toInt(), p[1].toInt()) }
            } catch (_: Exception) { Libre() }
        }
    }
}

object Acordes {
    val TIPOS = listOf(
        Tipo("Mayor", intArrayOf(0, 4, 7)), Tipo("Menor", intArrayOf(0, 3, 7)),
        Tipo("Disminuido", intArrayOf(0, 3, 6)), Tipo("Aumentado", intArrayOf(0, 4, 8)),
        Tipo("Sus2", intArrayOf(0, 2, 7)), Tipo("Sus4", intArrayOf(0, 5, 7)),
        Tipo("7 (dominante)", intArrayOf(0, 4, 7, 10)), Tipo("Maj7", intArrayOf(0, 4, 7, 11)),
        Tipo("m7", intArrayOf(0, 3, 7, 10)), Tipo("m7b5 (semidisminuido)", intArrayOf(0, 3, 6, 10)),
        Tipo("Dim7", intArrayOf(0, 3, 6, 9)), Tipo("m(maj7)", intArrayOf(0, 3, 7, 11)),
        Tipo("6", intArrayOf(0, 4, 7, 9)), Tipo("m6", intArrayOf(0, 3, 7, 9)),
        Tipo("7sus4", intArrayOf(0, 5, 7, 10)),
    )

    private val LETRAS_S = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val LETRAS_B = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    fun letra(semi: Int, bemoles: Boolean) = (if (bemoles) LETRAS_B else LETRAS_S)[((semi % 12) + 12) % 12]
    fun nombreMidi(n: Int, bemoles: Boolean) = letra(n, bemoles) + (n / 12 - 1)

    /** Acorde armado: raiz (0..11), tonos del acorde (0, 3a, 5a, [7a]) y extensiones (9, 11, 13 = 14, 17, 21). */
    class Forma(val raiz: Int, val tonos: List<Int>, val ext: List<Int>, val exactas: List<Int> = emptyList())

    private fun alterar(t: MutableList<Int>, a: Ajustes) {
        if (a.sus != 0 && t.size > 1) t[1] = if (a.sus == 2) 2 else 5
        if (a.quinta != 0 && t.size > 2) t[2] = if (a.quinta < 0) 6 else 8
    }

    /** Acorde diatonico del grado g (0..6) de la escala. */
    fun diatonico(tonica: Int, esc: IntArray, g: Int, a: Ajustes): Forma {
        val n = esc.size
        fun sobre(k: Int): Int { val i = g + k; return esc[i % n] + 12 * (i / n) - esc[g] }
        val t = mutableListOf(0, sobre(2), sobre(4))
        if (a.siete) t.add(sobre(6))
        if (a.seis && !a.siete) t.add(sobre(5))
        alterar(t, a)
        val e = mutableListOf<Int>()
        if (a.nueve) e.add(sobre(8))
        if (a.once) e.add(sobre(10))
        if (a.trece) e.add(if (a.siete) sobre(12) else sobre(12))
        if (a.seis && a.siete) e.add(sobre(12))
        return Forma((tonica + esc[g]) % 12, t, e.distinct())
    }

    fun libre(l: Libre, a: Ajustes): Forma {
        if (l.exacto) return Forma(l.notas.first() % 12, emptyList(), emptyList(), l.notas)
        val t = TIPOS[l.tipo.coerceIn(0, TIPOS.size - 1)].intervalos.toMutableList()
        if (a.seis && !t.contains(9)) t.add(9)
        alterar(t, a)
        val e = mutableListOf<Int>()
        if (a.nueve) e.add(14)
        if (a.once) e.add(17)
        if (a.trece) e.add(21)
        return Forma(l.raiz, t, e)
    }

    /** Notas MIDI a tocar. octava: la raiz cae en esa octava (C3 = 3). */
    fun notas(f: Forma, a: Ajustes, octava: Int): List<Int> {
        val v: MutableList<Int>
        if (f.exactas.isNotEmpty()) {
            val base = f.exactas.sorted()
            val inv = a.inv.coerceIn(0, base.size - 1)
            v = base.mapIndexed { i, n -> if (i < inv) n + 12 else n }.toMutableList()
        } else {
            val r = 12 * (octava + 1) + f.raiz
            val t = f.tonos.sorted()
            val inv = a.inv.coerceIn(0, t.size - 1)
            v = t.mapIndexed { i, x -> if (i < inv) x + 12 else x }.map { r + it }.toMutableList()
            if ((v.minOrNull() ?: r) - r >= 7) for (i in v.indices) v[i] -= 12
            val top = v.maxOrNull() ?: r
            for (x in f.ext.sorted()) { var n = r + x; while (n <= top) n += 12; v.add(n) }
        }
        if (a.bajo >= 0) {
            val low = v.minOrNull() ?: 48
            var b: Int
            if (a.bajoOct >= 0) b = 12 * (a.bajoOct + 1) + a.bajo
            else { b = 12 * (octava + 1) + a.bajo; while (b > low - 5) b -= 12 }
            v.add(b)
        }
        return v.filter { it in 0..127 }.distinct().sorted()
    }

    /** octava automatica del bajo (para mostrarla y como punto de partida) */
    fun bajoAuto(f: Forma, a: Ajustes, octava: Int): Int {
        val sinBajo = notas(f, a.copia().also { it.bajo = -1 }, octava)
        val low = sinBajo.minOrNull() ?: 48
        var b = 12 * (octava + 1) + a.bajo.coerceAtLeast(0)
        while (b > low - 5) b -= 12
        return b / 12 - 1
    }

    // ------------------------------------------------------------ reconocer acordes
    /** sufijo del acorde para los intervalos (relativos a la raiz, sin la raiz). */
    fun sufijo(rel: Set<Int>): String {
        val s = rel.toMutableSet(); s.remove(0)
        var tercera = when { 4 in s -> 4; 3 in s -> 3; else -> -1 }
        val alt = mutableListOf<String>()
        if (tercera == 4 && 3 in s) { s.remove(3); alt.add("#9") }
        var sus = ""
        if (tercera < 0) {
            if (5 in s) { sus = "sus4"; s.remove(5) } else if (2 in s) { sus = "sus2"; s.remove(2) }
        } else s.remove(tercera)
        var quinta = -1
        when {
            7 in s -> { quinta = 7; s.remove(7) }
            6 in s && tercera != 4 -> { quinta = 6; s.remove(6) }
            8 in s && tercera == 4 -> { quinta = 8; s.remove(8) }
            6 in s && tercera == 4 -> { quinta = 6; s.remove(6); alt.add("b5") }
        }
        var septima = -1
        when {
            10 in s -> { septima = 10; s.remove(10) }
            11 in s -> { septima = 11; s.remove(11) }
            9 in s && tercera == 3 && quinta == 6 -> { septima = 9; s.remove(9) }
        }
        val dim = tercera == 3 && quinta == 6
        val aum = tercera == 4 && quinta == 8
        var q = when {
            dim && septima == 9 -> "°7"
            dim && septima == 10 -> "m7b5"
            dim && septima == 11 -> "°(maj7)"
            dim -> "°"
            aum && septima == 10 -> "+7"
            aum && septima == 11 -> "+maj7"
            aum -> "+"
            tercera == 3 && septima == 10 -> "m7"
            tercera == 3 && septima == 11 -> "m(maj7)"
            tercera == 3 -> "m"
            septima == 10 -> "7"
            septima == 11 -> "maj7"
            else -> ""
        }
        // extensiones naturales
        val tiene9 = 2 in s; val tiene11 = 5 in s; val tiene13 = 9 in s
        if (septima == 10 || septima == 11) {
            val mayor = if (tiene13) 13 else if (tiene11) 11 else if (tiene9) 9 else 0
            if (mayor > 0 && q.endsWith("7") && !q.endsWith("b5") && q != "°7") {
                q = q.dropLast(1) + mayor
                s.remove(9); s.remove(5); s.remove(2)
                if (mayor == 13 && tiene11) { } // 11 queda implicita
            } else if (q == "m(maj7)" && mayor > 0) {
                q = "m(maj$mayor)"; s.remove(9); s.remove(5); s.remove(2)
            }
        } else {
            if (tiene13 && !dim) { q += "6"; s.remove(9); if (2 in s) { q += "/9"; s.remove(2) } }
        }
        if (2 in s) { alt.add("add9"); s.remove(2) }
        if (5 in s) { alt.add("add11"); s.remove(5) }
        if (9 in s) { alt.add("add13"); s.remove(9) }
        if (1 in s) alt.add("b9")
        if (6 in s) alt.add("#11")
        if (8 in s) alt.add("b13")
        if (3 in s) alt.add("#9")
        var r = q + sus
        if (alt.isNotEmpty()) {
            r += if (alt.size == 1 && alt[0].startsWith("add") && (q == "" || q == "+") && sus == "") alt[0] else "(" + alt.joinToString(",") + ")"
        }
        return r
    }

    /** Nombre del acorde a partir de sus notas MIDI. raiz = la raiz conocida (o -1 para adivinarla). */
    fun nombre(notas: List<Int>, raiz: Int, bemoles: Boolean): String {
        if (notas.isEmpty()) return ""
        val pcs = notas.map { it % 12 }.toSet()
        val grave = notas.minOrNull()!! % 12
        if (pcs.size == 1) return letra(grave, bemoles)
        if (pcs.size == 2) {
            // dos notas: quinta = "5" (power chord); el resto se nombra por su intervalo
            val otra = pcs.first { it != grave }
            val i = ((otra - grave) % 12 + 12) % 12
            val nom = arrayOf("", "(b9)", "(9)", "m(no5)", "(no5)", "sus4(no5)", "(b5)", "5", "(b6)", "6(no5)", "7(no3)", "maj7(no3)")[i]
            return letra(grave, bemoles) + nom
        }
        val r = if (raiz >= 0) raiz else adivinarRaiz(pcs, grave)
        val rel = pcs.map { ((it - r) % 12 + 12) % 12 }.toSet()
        var s = letra(r, bemoles) + sufijo(rel)
        if (grave != r) s += "/" + letra(grave, bemoles)
        return s
    }

    private fun adivinarRaiz(pcs: Set<Int>, grave: Int): Int {
        var mejor = grave; var puntos = -999
        for (c in pcs) {
            val rel = pcs.map { ((it - c) % 12 + 12) % 12 }.toSet()
            var p = 0
            if (4 in rel || 3 in rel) p += 4
            if (7 in rel) p += 2
            if (c == grave) p += 3
            if (10 in rel || 11 in rel) p += 1
            if (1 in rel) p -= 2
            if (6 in rel && 7 in rel) p -= 1
            if (8 in rel && 7 in rel) p -= 1
            if ((4 in rel || 3 in rel) && 7 in rel) p += 2          // triada completa
            if (3 in rel && 8 in rel && 4 !in rel) p -= 3           // "m(b6)" casi siempre es otro acorde invertido
            if (4 in rel && 10 in rel) p += 3                        // forma de dominante
            if (p > puntos || (p == puntos && c == grave)) { puntos = p; mejor = c }
        }
        return mejor
    }

    // ------------------------------------------------------------ aleatorio
    private val rnd = Random()

    /**
     * Acorde aleatorio musical: casi siempre de la tonalidad; a veces prestado
     * del modo paralelo o un dominante secundario. Con 9a/inversion al azar.
     */
    fun aleatorio(tonica: Int, escIdx: Int): Pair<Libre, Ajustes> {
        val t = if (tonica < 0) 0 else tonica
        val esc = Escalas.escala(escIdx).intervalos.let { if (it.size == 7) it else Escalas.escala(0).intervalos }
        val a = Ajustes()
        val dado = rnd.nextFloat()
        val libre: Libre
        when {
            dado < 0.70f -> {
                val g = rnd.nextInt(7)
                val f = diatonico(t, esc, g, Ajustes(siete = rnd.nextFloat() < 0.4f))
                libre = Libre(f.raiz, tipoDe(f.tonos))
            }
            dado < 0.85f -> {
                val mayor = esc[2] == 4
                val opciones = if (mayor) listOf(3 to 0, 5 to 1, 8 to 0, 10 to 0) else listOf(5 to 0, 7 to 0, 2 to 1, 11 to 2)
                val (sem, tipo) = opciones[rnd.nextInt(opciones.size)]
                libre = Libre((t + sem) % 12, tipo)
            }
            else -> {
                val destino = esc[1 + rnd.nextInt(5)]
                libre = Libre((t + destino + 7) % 12, 6)
            }
        }
        a.nueve = rnd.nextFloat() < 0.2f
        if (rnd.nextFloat() < 0.1f && TIPOS[libre.tipo].intervalos.size == 3) a.sus = if (rnd.nextBoolean()) 2 else 4
        val r = rnd.nextFloat()
        a.inv = if (r < 0.5f) 0 else if (r < 0.8f) 1 else 2
        return libre to a
    }

    fun tipoDe(base: List<Int>): Int {
        val i = TIPOS.indexOfFirst { it.intervalos.toList() == base }
        return if (i >= 0) i else 0
    }

    /** como escribir la raiz: en la tonalidad usa su armadura; los prestados (bII bIII bVI bVII) con bemol */
    fun bemolesPara(raiz: Int, tonica: Int, escIdx: Int): Boolean {
        val i = ((raiz - tonica) % 12 + 12) % 12
        if (Escalas.escala(escIdx).intervalos.contains(i)) return Escalas.usaBemoles(tonica, escIdx)
        return i in intArrayOf(1, 3, 8, 10)
    }
}
