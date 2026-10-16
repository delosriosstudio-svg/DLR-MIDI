package pe.dlr.midi

/**
 * Analiza un acorde respecto a la tonalidad:
 *  - su GRADO en numeros romanos (I, bVII, iv, V7/V...)
 *  - su TENSION (0 = reposo, 1 = maxima) para el mapa de calor.
 */
object Analisis {
    private val ROM = arrayOf("I", "II", "III", "IV", "V", "VI", "VII")
    private val REF_MAYOR = arrayOf("I", "bII", "II", "bIII", "III", "IV", "#IV", "V", "bVI", "VI", "bVII", "VII")

    class Resultado(val grado: String, val tension: Float, val esTonica: Boolean)

    private fun rel(pcs: Set<Int>, r: Int) = pcs.map { ((it - r) % 12 + 12) % 12 }.toSet()

    /** numeral de un intervalo (desde la tonica) segun la escala */
    private fun numeral(intervalo: Int, esc: IntArray): String {
        val i = esc.indexOf(intervalo)
        return if (i >= 0 && esc.size == 7) ROM[i] else REF_MAYOR[intervalo]
    }

    fun analizar(notas: List<Int>, raizHint: Int, tonica: Int, escIdx: Int): Resultado {
        if (notas.isEmpty()) return Resultado("", 0f, false)
        val esc = Escalas.escala(escIdx).intervalos
        val pcs = notas.map { it % 12 }.toSet()
        val raiz = if (raizHint >= 0) raizHint else Acordes.adivinarRaiz(pcs, notas.minOrNull()!! % 12)
        val r = rel(pcs, raiz)
        val iv = ((raiz - tonica) % 12 + 12) % 12

        val menor = 3 in r && 4 !in r
        val dim = menor && 6 in r && 7 !in r
        val aum = 4 in r && 8 in r && 7 !in r
        val dom = 4 in r && 10 in r
        val maj7 = 11 in r
        val sept = 10 in r || maj7
        val fuera = pcs.count { ((it - tonica) % 12 + 12) % 12 !in esc }
        val diatonico = fuera == 0

        // ---- grado
        var num = numeral(iv, esc)
        if (menor || dim) num = num.lowercase().replace("b", "b").replace("#", "#")
        num += when {
            dim && 10 in r -> "ø7"
            dim && 9 in r -> "°7"
            dim -> "°"
            aum -> "+"
            dom -> "7"
            maj7 && !menor -> "maj7"
            sept -> "7"
            else -> ""
        }
        if (5 in r && 3 !in r && 4 !in r) num += "sus"
        // dominante secundario: acorde mayor/7 no diatonico que resuelve una quinta abajo a un grado de la escala
        var secundario = false
        val destino = ((iv - 7) % 12 + 12) % 12
        if (!diatonico && 4 in r && !aum && destino != 0 && esc.contains(destino) && esc.size == 7) {
            val gi = esc.indexOf(destino)
            // calidad del acorde destino en la escala
            val t = (esc[(gi + 2) % 7] - esc[gi] + 12) % 12
            val q = (esc[(gi + 4) % 7] - esc[gi] + 12) % 12
            var d = ROM[gi]; if (t == 3) d = d.lowercase(); if (t == 3 && q == 6) d += "°"
            num = (if (dom) "V7/" else "V/") + d
            secundario = true
        }

        // ---- tension
        val esTonica = iv == 0 && !dom && !dim && !aum
        var ten = when (iv) {
            0 -> 0.0f
            9, 8 -> 0.18f            // vi / bVI
            4, 3 -> 0.25f            // iii / bIII
            5 -> 0.3f                // IV / iv
            2 -> 0.42f               // ii
            10 -> 0.4f               // bVII
            7 -> 0.7f                // V
            11 -> 0.82f              // vii
            1 -> 0.75f               // bII (napolitano)
            6 -> 0.85f               // tritono
            else -> 0.5f
        }
        if (iv == 7 && menor) ten = 0.5f                     // v menor: dominante debil
        if (!esc.contains(iv)) ten += 0.08f
        if (dom) ten += 0.18f
        if (dim) ten += 0.22f
        if (aum) ten += 0.25f
        if (5 in r && 3 !in r && 4 !in r) ten += 0.08f      // sus4
        if (sept && !dom) ten += 0.05f
        for (a in intArrayOf(1, 6, 8)) if (a in r && 7 in r) ten += 0.1f   // b9, #11, b13
        if (3 in r && 4 in r) ten += 0.12f                  // #9
        ten += 0.06f * fuera
        if (secundario) ten = maxOf(ten, if (dom) 0.72f else 0.6f)
        if (esTonica) ten = 0f
        return Resultado(num, ten.coerceIn(0f, 1f), esTonica)
    }

    /** color del cuadro: tonica = azul profundo; resto de gris tranquilo a ambar y rojo intenso */
    fun color(r: Resultado): Int {
        if (r.esTonica) return 0xFF27466B.toInt()
        val t = r.tension
        val a = intArrayOf(0x2A, 0x2B, 0x30)          // tranquilo
        val b = intArrayOf(0x9A, 0x62, 0x1E)          // ambar
        val c = intArrayOf(0xB8, 0x2E, 0x2A)          // rojo
        val (x, y, k) = if (t < 0.5f) Triple(a, b, t / 0.5f) else Triple(b, c, (t - 0.5f) / 0.5f)
        fun m(i: Int) = (x[i] + (y[i] - x[i]) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(0) shl 16) or (m(1) shl 8) or m(2)
    }
}
