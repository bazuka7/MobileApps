package com.bazuka.vozcomida

import java.text.Normalizer
import java.util.Calendar

data class ParsedItem(val name: String, val qty: String, val kcal: Int?)

data class ParsedMeal(val meal: String, val items: List<ParsedItem>)

object Meals {
    const val DESAYUNO = "Desayuno"
    const val ALMUERZO = "Almuerzo"
    const val MERIENDA = "Merienda"
    const val CENA = "Cena"
    val ALL = listOf(DESAYUNO, ALMUERZO, MERIENDA, CENA)
}

/** Convierte frases en español como "desayuno dos huevos y una tostada" en registros. */
object Parser {

    private val numberWords = mapOf(
        "un" to 1.0, "uno" to 1.0, "una" to 1.0, "dos" to 2.0, "tres" to 3.0,
        "cuatro" to 4.0, "cinco" to 5.0, "seis" to 6.0, "siete" to 7.0,
        "ocho" to 8.0, "nueve" to 9.0, "diez" to 10.0, "medio" to 0.5, "media" to 0.5
    )

    private val units = listOf(
        "gramos", "gramo", "g", "kilos", "kilo", "kg", "mililitros", "ml", "litros", "litro",
        "tazas", "taza", "vasos", "vaso", "platos", "plato", "porciones", "porcion",
        "rebanadas", "rebanada", "rodajas", "rodaja", "cucharadas", "cucharada",
        "cucharaditas", "cucharadita", "trozos", "trozo", "piezas", "pieza",
        "unidades", "unidad", "latas", "lata", "copas", "copa"
    )

    private val mealPatterns = listOf(
        Meals.DESAYUNO to Regex("\\b(desayuno|desayune|desayunando)\\b"),
        Meals.ALMUERZO to Regex("\\b(almuerzo|almorce|almorzando|comida)\\b"),
        Meals.MERIENDA to Regex("\\b(merienda|meriende|snack|colacion)\\b"),
        Meals.CENA to Regex("\\b(cena|cene|cenando)\\b")
    )

    private val leadFillers = Regex(
        "^(hoy |ayer |ahora |acabo de |he |hemos |me |para |en |de |el |la |al |a la |a el )*" +
            "(comi|comido|tome|tomado|bebi|bebido|ingeri|comiendo|tomando|consumi|consumido)?\\s*" +
            "(para |en |de |el |la |un |una )*"
    )

    private val kcalRegex = Regex("(\\d+)\\s*(kcal|calorias|caloria|cal)\\b")
    private val qtyRegex = Regex("^(\\d+(?:[.,]\\d+)?|[a-z]+)\\s*(?:(${units.joinToString("|")})\\b)?\\s*(de\\s+)?")

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("\\s+"), " ")

    /** true si la frase es una orden de "borrar el último registro". */
    fun isUndo(text: String): Boolean {
        val t = normalize(text)
        return Regex("^(borra|borrar|elimina|eliminar|deshaz|deshacer|quita|quitar|cancela)( el)?( ultimo| anterior| registro)*$").matches(t)
    }

    fun defaultMeal(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 11 -> Meals.DESAYUNO
            h < 16 -> Meals.ALMUERZO
            h < 20 -> Meals.MERIENDA
            else -> Meals.CENA
        }
    }

    fun parse(raw: String): ParsedMeal? {
        var t = normalize(raw)
        if (t.isEmpty()) return null

        var meal: String? = null
        for ((name, rx) in mealPatterns) {
            if (rx.containsMatchIn(t)) {
                meal = name
                t = rx.replace(t, " ")
                break
            }
        }
        t = t.replace(Regex("\\s+"), " ").trim().trimStart(',', ':', '.').trim()
        t = leadFillers.replace(t, "").trim()
        if (t.isEmpty()) return null

        val parts = t.split(Regex(",|;|\\by\\b|\\bademas\\b|\\bmas\\b"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val items = parts.mapNotNull { parseItem(it) }
        if (items.isEmpty()) return null
        return ParsedMeal(meal ?: defaultMeal(), items)
    }

    private fun parseItem(part: String): ParsedItem? {
        var s = part
        var kcal: Int? = null
        kcalRegex.find(s)?.let {
            kcal = it.groupValues[1].toIntOrNull()
            s = s.replace(it.value, " ")
        }
        s = s.replace(Regex("\\b(con|de|y|aproximadamente|unas|unos)\\s*$"), "").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null

        var qty = ""
        val m = qtyRegex.find(s)
        if (m != null) {
            val numStr = m.groupValues[1]
            val num = numStr.replace(',', '.').toDoubleOrNull() ?: numberWords[numStr]
            if (num != null) {
                val unit = m.groupValues[2]
                qty = formatNumber(num) + if (unit.isNotEmpty()) " $unit" else ""
                val rest = s.substring(m.value.length).trim()
                if (rest.isNotEmpty()) s = rest
            }
        }
        if (qty.isEmpty()) qty = "1"
        return ParsedItem(s.replaceFirstChar { it.uppercase() }, qty, kcal)
    }

    private fun formatNumber(d: Double): String =
        if (d == Math.floor(d)) d.toInt().toString() else d.toString()
}
