package pe.dlr.midi

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * Templates: guardan los ajustes de toda la app (acordes, teclado, faders, VEL/HUMANIZE, canal)
 * como JSON, ordenados en carpetas. La carpeta "DLR" trae templates de fabrica (solo lectura).
 */
object Plantillas {
    const val FABRICA = "DLR"

    class Item(val carpeta: String, val nombre: String, val fabrica: Boolean)

    private fun raiz(ctx: Context) = File(ctx.filesDir, "plantillas").apply { mkdirs() }

    /** nombre seguro para archivo (sin / ni caracteres raros) */
    fun limpio(s: String) = s.trim().replace(Regex("[/\\\\:*?\"<>|]"), "-").take(60)

    fun carpetas(ctx: Context): List<String> {
        val propias = raiz(ctx).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        return listOf(FABRICA) + propias.filter { it != FABRICA }
    }

    fun listar(ctx: Context, carpeta: String): List<Item> {
        if (carpeta == FABRICA) {
            val a = ctx.assets.list("plantillas/$FABRICA") ?: emptyArray()
            return a.filter { it.endsWith(".json") }.sorted().map { Item(FABRICA, it.removeSuffix(".json"), true) }
        }
        return File(raiz(ctx), carpeta).listFiles()?.filter { it.name.endsWith(".json") }
            ?.map { Item(carpeta, it.name.removeSuffix(".json"), false) }?.sortedBy { it.nombre.lowercase() } ?: emptyList()
    }

    fun crearCarpeta(ctx: Context, nombre: String): Boolean {
        val n = limpio(nombre)
        if (n.isEmpty() || n == FABRICA) return false
        return File(raiz(ctx), n).mkdirs() || File(raiz(ctx), n).isDirectory
    }

    fun borrarCarpeta(ctx: Context, carpeta: String): Boolean {
        if (carpeta == FABRICA) return false
        return File(raiz(ctx), carpeta).deleteRecursively()
    }

    /** guarda TODOS los ajustes actuales */
    fun guardar(ctx: Context, prefs: SharedPreferences, carpeta: String, nombre: String) {
        val dir = File(raiz(ctx), limpio(carpeta)).apply { mkdirs() }
        val datos = JSONObject()
        for ((k, v) in prefs.all) {
            if (k == "pestana") continue
            val o = JSONObject()
            when (v) {
                is Int -> { o.put("t", "i"); o.put("v", v) }
                is Float -> { o.put("t", "f"); o.put("v", v.toDouble()) }
                is Boolean -> { o.put("t", "b"); o.put("v", v) }
                is String -> { o.put("t", "s"); o.put("v", v) }
                is Long -> { o.put("t", "l"); o.put("v", v) }
                else -> continue
            }
            datos.put(k, o)
        }
        val raizJ = JSONObject().put("nombre", nombre).put("version", 1).put("ajustes", datos)
        File(dir, limpio(nombre) + ".json").writeText(raizJ.toString(2))
    }

    /** aplica un template sobre los ajustes (solo las claves que trae) */
    fun cargar(ctx: Context, prefs: SharedPreferences, it: Item): Boolean {
        return try {
            val texto = if (it.fabrica) ctx.assets.open("plantillas/$FABRICA/${it.nombre}.json").bufferedReader().readText()
                        else File(File(raiz(ctx), it.carpeta), it.nombre + ".json").readText()
            val datos = JSONObject(texto).getJSONObject("ajustes")
            val e = prefs.edit()
            for (k in datos.keys()) {
                val o = datos.getJSONObject(k)
                when (o.getString("t")) {
                    "i" -> e.putInt(k, o.getInt("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "s" -> e.putString(k, o.getString("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                }
            }
            e.commit()
            true
        } catch (_: Exception) { false }
    }

    fun borrar(ctx: Context, it: Item): Boolean = !it.fabrica && File(File(raiz(ctx), it.carpeta), it.nombre + ".json").delete()

    fun renombrar(ctx: Context, it: Item, nuevo: String): Boolean {
        if (it.fabrica) return false
        val dir = File(raiz(ctx), it.carpeta)
        return File(dir, it.nombre + ".json").renameTo(File(dir, limpio(nuevo) + ".json"))
    }

    fun mover(ctx: Context, it: Item, carpeta: String): Boolean {
        if (it.fabrica || carpeta == FABRICA) return false
        val destino = File(raiz(ctx), carpeta).apply { mkdirs() }
        return File(File(raiz(ctx), it.carpeta), it.nombre + ".json").renameTo(File(destino, it.nombre + ".json"))
    }
}
