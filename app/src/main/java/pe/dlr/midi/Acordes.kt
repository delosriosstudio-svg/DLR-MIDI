package pe.dlr.midi

import java.util.Random

/** Ajustes que recuerda cada cuadro. */
class Ajustes(
    var inv: Int = 0,          // 0 fundamental, 1 = 1a, 2 = 2a, 3 = 3a
    var siete: Boolean = false,
    var nueve: Boolean = false,
    var bajo: Int = -1,        // -1 = normal; 0..11 = bajo en esa nota
) {
    fun guardar() = "$inv,${if (siete) 1 else 0},${if (nueve) 1 else 0},$bajo"
    companion object {
        fun leer(s: String?): Ajustes {
            val p = s?.split(",") ?: return Ajustes()
            return try { Ajustes(p[0].toInt(), p[1] == "1", p[2] == "1", p[3].toInt()) } catch (_: Exception) { Ajustes() }
        }
    }
}

/** Un tipo de acorde escrito a mano. */
class Tipo(val nombre: String, val intervalos: IntArray)

/** Acorde de un cuadro libre: raiz + tipo (o vacio). */
class Libre(var raiz: Int = -1, var tipo: Int = 0) {
    val vacio get() = raiz < 0
    fun guardar() = "$raiz,$tipo"
    companion object {
        fun leer(s: String?): Libre {
            val p = s?.split(",") ?: return Libre()
            return try { Libre(p[0].toInt(), p[1].toInt()) } catch (_: Exception) { Libre() }
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

    private val SUFIJOS = mapOf(
        listOf(0, 4, 7) to "", listOf(0, 3, 7) to "m", listOf(0, 3, 6) to "°", listOf(0, 4, 8) to "+",
        listOf(0, 2, 7) to "sus2", listOf(0, 5, 7) to "sus4",
        listOf(0, 4, 7, 10) to "7", listOf(0, 4, 7, 11) to "maj7", listOf(0, 3, 7, 10) to "m7",
        listOf(0, 3, 7, 11) to "m(maj7)", listOf(0, 3, 6, 10) to "m7b5", listOf(0, 3, 6, 9) to "°7",
        listOf(0, 4, 8, 11) to "+maj7", listOf(0, 4, 8, 10) to "+7", listOf(0, 4, 7, 9) to "6",
        listOf(0, 3, 7, 9) to "m6", listOf(0, 5, 7, 10) to "7sus4",
    )

    private val LETRAS_S = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val LETRAS_B = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    fun letra(semi: Int, bemoles: Boolean) = (if (bemoles) LETRAS_B else LETRAS_S)[((semi % 12) + 12) % 12]

    /** intervalos del acorde (sin la 9a) + la 9a aparte (0 = no). */
    class Forma(val raiz: Int, val base: List<Int>, val novena: Int)

    /** Acorde diatonico del grado g (0..6) de la escala. */
    fun diatonico(tonica: Int, esc: IntArray, g: Int, a: Ajustes): Forma {
        val n = esc.size
        fun sobre(k: Int): Int { val i = g + k; return esc[i % n] + 12 * (i / n) - esc[g] }
        val base = mutableListOf(0, sobre(2), sobre(4))
        if (a.siete) base.add(sobre(6))
        val nov = if (a.nueve) sobre(8) else 0
        return Forma((tonica + esc[g]) % 12, base, nov)
    }

    fun libre(l: Libre, a: Ajustes): Forma = Forma(l.raiz, TIPOS[l.tipo.coerceIn(0, TIPOS.size - 1)].intervalos.toList(), if (a.nueve) 14 else 0)

    fun nombre(f: Forma, a: Ajustes, bemoles: Boolean): String {
        var suf = SUFIJOS[f.base] ?: ""
        if (f.novena > 0) {
            val nueve = if (f.novena == 13) "b9" else if (f.novena == 15) "#9" else "9"
            suf = when {
                f.base.size == 4 && nueve == "9" && suf in listOf("7", "maj7", "m7") -> suf.replace("7", "9")
                f.base.size == 4 -> "$suf($nueve)"
                else -> "$suf(add$nueve)"
            }
        }
        var s = letra(f.raiz, bemoles) + suf
        val grave = bajoReal(f, a)
        if (grave != f.raiz) s += "/" + letra(grave, bemoles)
        return s
    }

    /** nota (0..11) que queda en el bajo */
    fun bajoReal(f: Forma, a: Ajustes): Int {
        if (a.bajo >= 0) return a.bajo
        val inv = a.inv.coerceIn(0, f.base.size - 1)
        return (f.raiz + f.base[inv]) % 12
    }

    /** Notas MIDI a tocar. raizMidi = octava de la raiz (ej. 48 + raiz). */
    fun notas(f: Forma, a: Ajustes, octava: Int): List<Int> {
        val r = 12 * (octava + 1) + f.raiz
        val inv = a.inv.coerceIn(0, f.base.size - 1)
        var v = f.base.mapIndexed { i, x -> if (i < inv) x + 12 else x }.map { r + it }.toMutableList()
        // que las inversiones no suban demasiado: se mantienen cerca de la octava elegida
        if ((v.minOrNull() ?: r) - r >= 7) v = v.map { it - 12 }.toMutableList()
        if (f.novena > 0) v.add((v.maxOrNull() ?: r).let { top -> var n = r + f.novena; while (n <= top) n += 12; n })
        if (a.bajo >= 0) {
            val low = v.minOrNull() ?: r
            var b = 12 * (octava + 1) + a.bajo
            while (b > low - 5) b -= 12          // el bajo queda separado del acorde
            v.add(b)
        }
        return v.filter { it in 0..127 }.distinct()
    }

    private val rnd = Random()

    /**
     * Acorde aleatorio musical: casi siempre de la tonalidad; a veces prestado
     * del modo paralelo o un dominante secundario. Con 7a/9a/inversion al azar.
     * Devuelve (Libre, Ajustes).
     */
    fun aleatorio(tonica: Int, escIdx: Int): Pair<Libre, Ajustes> {
        val t = if (tonica < 0) 0 else tonica
        val esc = Escalas.escala(escIdx).intervalos.let { if (it.size == 7) it else Escalas.escala(0).intervalos }
        val a = Ajustes()
        val dado = rnd.nextFloat()
        val libre: Libre
        when {
            dado < 0.70f -> {           // diatonico
                val g = rnd.nextInt(7)
                a.siete = rnd.nextFloat() < 0.4f
                val f = diatonico(t, esc, g, Ajustes(siete = a.siete))
                libre = Libre(f.raiz, tipoDe(f.base))
            }
            dado < 0.85f -> {           // prestado del modo paralelo
                val mayor = esc[2] == 4
                val opciones = if (mayor) listOf(3 to 0, 5 to 1, 8 to 0, 10 to 0)      // bIII, iv, bVI, bVII
                               else listOf(5 to 0, 7 to 0, 2 to 1, 11 to 2)            // IV, V, ii, vii°
                val (sem, tipo) = opciones[rnd.nextInt(opciones.size)]
                libre = Libre((t + sem) % 12, tipo)
            }
            else -> {                   // dominante secundario: V7 de ii, iii, IV, V o vi
                val destino = esc[1 + rnd.nextInt(5)]
                libre = Libre((t + destino + 7) % 12, 6)
            }
        }
        a.siete = false                 // el tipo ya trae la 7a si la tiene
        a.nueve = rnd.nextFloat() < 0.2f
        val r = rnd.nextFloat()
        a.inv = if (r < 0.5f) 0 else if (r < 0.8f) 1 else 2
        return libre to a
    }

    /** como escribir la raiz: en la tonalidad usa su armadura; los prestados (bII bIII bVI bVII) con bemol */
    fun bemolesPara(raiz: Int, tonica: Int, escIdx: Int): Boolean {
        val i = ((raiz - tonica) % 12 + 12) % 12
        if (Escalas.escala(escIdx).intervalos.contains(i)) return Escalas.usaBemoles(tonica, escIdx)
        return i in intArrayOf(1, 3, 8, 10)
    }

    fun tipoDe(base: List<Int>): Int {
        val i = TIPOS.indexOfFirst { it.intervalos.toList() == base }
        return if (i >= 0) i else 0
    }
}
