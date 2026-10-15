package pe.dlr.midi

/** Escalas de ayuda, nombres de notas en espanol y grados en numeros romanos. */
object Escalas {
    class Escala(val nombre: String, val intervalos: IntArray, val armadura: Int)
    // armadura: semitonos para llegar a la tonalidad mayor "madre" (define si se escribe con # o b)
    val LISTA = listOf(
        Escala("Mayor", intArrayOf(0, 2, 4, 5, 7, 9, 11), 0),
        Escala("Menor natural", intArrayOf(0, 2, 3, 5, 7, 8, 10), 3),
        Escala("Menor armónica", intArrayOf(0, 2, 3, 5, 7, 8, 11), 3),
        Escala("Menor melódica", intArrayOf(0, 2, 3, 5, 7, 9, 11), 3),
        Escala("Dórica", intArrayOf(0, 2, 3, 5, 7, 9, 10), 10),
        Escala("Frigia", intArrayOf(0, 1, 3, 5, 7, 8, 10), 8),
        Escala("Lidia", intArrayOf(0, 2, 4, 6, 7, 9, 11), 7),
        Escala("Mixolidia", intArrayOf(0, 2, 4, 5, 7, 9, 10), 5),
        Escala("Locria", intArrayOf(0, 1, 3, 5, 6, 8, 10), 1),
        Escala("Pentatónica mayor", intArrayOf(0, 2, 4, 7, 9), 0),
        Escala("Pentatónica menor", intArrayOf(0, 3, 5, 7, 10), 3),
        Escala("Blues", intArrayOf(0, 3, 5, 6, 7, 10), 3),
    )

    fun escala(i: Int) = LISTA[i.coerceIn(0, LISTA.size - 1)]

    private val SOSTENIDOS = arrayOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")
    private val BEMOLES = arrayOf("Do", "Reb", "Re", "Mib", "Mi", "Fa", "Solb", "Sol", "Lab", "La", "Sib", "Si")
    val TONICAS = arrayOf("Do", "Do# / Reb", "Re", "Mib / Re#", "Mi", "Fa", "Fa# / Solb", "Sol", "Lab / Sol#", "La", "Sib / La#", "Si")

    /** true si la tonalidad se escribe con bemoles */
    fun usaBemoles(tonica: Int, esc: Int): Boolean {
        if (tonica < 0) return false
        val madre = (tonica + escala(esc).armadura) % 12
        return madre in intArrayOf(5, 10, 3, 8, 1)          // Fa, Sib, Mib, Lab, Reb
    }

    fun nombreNota(semi: Int, tonica: Int, esc: Int): String =
        if (usaBemoles(tonica, esc)) BEMOLES[semi] else SOSTENIDOS[semi]

    fun nombreTonica(tonica: Int, esc: Int): String = if (tonica < 0) "TONALIDAD" else nombreNota(tonica, tonica, esc)

    private val ROMANOS = arrayOf("I", "II", "III", "IV", "V", "VI", "VII")

    /** Grado de cada nota de la escala, en romanos. 7 notas: segun el acorde (I ii iii IV V vi vii°). */
    fun grados(esc: Int): Array<String> {
        val iv = escala(esc).intervalos
        if (iv.size == 7) {
            return Array(7) { g ->
                val r = iv[g]
                val t = (iv[(g + 2) % 7] - r + 12) % 12      // tercera
                val q = (iv[(g + 4) % 7] - r + 12) % 12      // quinta
                when {
                    t == 4 && q == 8 -> ROMANOS[g] + "+"
                    t == 4 -> ROMANOS[g]
                    t == 3 && q == 6 -> ROMANOS[g].lowercase() + "°"
                    else -> ROMANOS[g].lowercase()
                }
            }
        }
        // pentatonicas / blues: grado respecto a la escala mayor (bIII, IV, bV...)
        val nombres = arrayOf("I", "bII", "II", "bIII", "III", "IV", "bV", "V", "bVI", "VI", "bVII", "VII")
        return Array(iv.size) { nombres[iv[it]] }
    }
}
