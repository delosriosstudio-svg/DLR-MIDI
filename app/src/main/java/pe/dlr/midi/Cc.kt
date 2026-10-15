package pe.dlr.midi

/** Nombres de los CC mas usados. */
object Cc {
    private val nombres = mapOf(
        0 to "Bank Select", 1 to "Modulacion", 2 to "Breath", 4 to "Pedal (foot)", 5 to "Portamento time",
        7 to "Volumen", 8 to "Balance", 10 to "Paneo", 11 to "Expresion",
        12 to "Efecto 1", 13 to "Efecto 2", 21 to "Dinamica (libreria)",
        64 to "Sustain", 65 to "Portamento", 66 to "Sostenuto", 67 to "Pedal suave",
        71 to "Resonancia", 72 to "Release", 73 to "Attack", 74 to "Cutoff (brillo)",
        75 to "Decay", 76 to "Vibrato rate", 77 to "Vibrato depth", 78 to "Vibrato delay",
        91 to "Reverb", 93 to "Chorus", 94 to "Detune", 95 to "Phaser",
    )
    fun nombre(n: Int) = nombres[n] ?: "CC libre"
    fun nombreCorto(n: Int): String = when (n) {
        1 -> "Modulacion"; 7 -> "Volumen"; 10 -> "Paneo"; 11 -> "Expresion"; 21 -> "Dinamica"
        64 -> "Sustain"; 74 -> "Cutoff"; 71 -> "Resonancia"; 91 -> "Reverb"; 93 -> "Chorus"
        else -> nombres[n]?.take(11) ?: "libre"
    }
}
