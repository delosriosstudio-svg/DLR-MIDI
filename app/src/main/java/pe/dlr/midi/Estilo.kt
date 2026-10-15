package pe.dlr.midi

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Colores y pinceles compartidos (estilo oscuro, como el Panel DLR). */
object Estilo {
    val FONDO = Color.rgb(18, 19, 22)
    val PANEL = Color.rgb(30, 32, 36)
    val BORDE = Color.rgb(55, 58, 64)
    val TEXTO = Color.rgb(225, 228, 233)
    val TENUE = Color.rgb(130, 134, 142)
    val ACENTO = Color.rgb(242, 150, 52)     // naranja
    val VERDE = Color.rgb(110, 205, 140)
    val ROJO = Color.rgb(222, 82, 72)
    val AZUL = Color.rgb(95, 160, 245)

    fun pincel(color: Int, tam: Float = 0f, negrita: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            if (tam > 0f) textSize = tam
            if (negrita) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
}
