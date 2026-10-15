package pe.dlr.midi

/** Escalas de ayuda (mayor y menor natural) y nombres de notas en espanol. */
object Escalas {
    private val MAYOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MENOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    fun intervalos(modo: Int) = if (modo == 1) MENOR else MAYOR

    private val SOSTENIDOS = arrayOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")
    private val BEMOLES = arrayOf("Do", "Reb", "Re", "Mib", "Mi", "Fa", "Solb", "Sol", "Lab", "La", "Sib", "Si")
    private val LETRAS_S = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val LETRAS_B = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    // tonicas como se escriben normalmente (Mib mayor, no Re# mayor; Do# menor, no Reb menor)
    private val TONICA_MAYOR = arrayOf("Do", "Reb", "Re", "Mib", "Mi", "Fa", "Fa#", "Sol", "Lab", "La", "Sib", "Si")
    private val TONICA_MENOR = arrayOf("Do", "Do#", "Re", "Mib", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "Sib", "Si")

    /** true si la tonalidad se escribe con bemoles */
    private fun usaBemoles(tonica: Int, modo: Int): Boolean {
        // tonalidad mayor relativa
        val rel = if (modo == 1) (tonica + 3) % 12 else tonica
        return rel in intArrayOf(5, 10, 3, 8, 1)          // Fa, Sib, Mib, Lab, Reb
    }

    fun nombreNota(semi: Int, tonica: Int, modo: Int): String =
        if (tonica >= 0 && usaBemoles(tonica, modo)) BEMOLES[semi] else SOSTENIDOS[semi]

    fun nombreEscala(tonica: Int, modo: Int): String {
        if (tonica < 0) return "Sin escala"
        val t = if (modo == 1) TONICA_MENOR[tonica] else TONICA_MAYOR[tonica]
        val l = if (usaBemoles(tonica, modo)) LETRAS_B[tonica] else LETRAS_S[tonica]
        return "$t " + (if (modo == 1) "menor" else "mayor") + " ($l" + (if (modo == 1) "m" else "") + ")"
    }

    /** lista para elegir: "Sin escala", luego todas las mayores, luego todas las menores */
    val opciones: List<Pair<Int, Int>> = listOf(-1 to 0) + (0 until 12).map { it to 0 } + (0 until 12).map { it to 1 }
}
