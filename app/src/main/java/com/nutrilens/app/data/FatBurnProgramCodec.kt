package com.nutrilens.app.data

import com.nutrilens.app.insights.FatBurnProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

/**
 * Программа режима «Сжигание жира» ↔ JSON-строка в settings (колонка fatBurnProgram).
 * Отдельная таблица ради одного объекта не нужна, а JSON легко попадает в бэкап.
 * Кодек ручной (без @Serializable): модель программы живёт в чистом insights и не
 * зависит от плагина сериализации. Битый JSON читается как «программы нет» — она
 * соберётся заново, а не уронит приложение. Без Android — проверяется отдельно.
 */
object FatBurnProgramCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(p: FatBurnProgram.Program): String = JsonObject(
        mapOf(
            "version" to JsonPrimitive(1),
            "startDate" to JsonPrimitive(p.startDate.toString()),
            "startFat" to JsonPrimitive(p.startFat),
            "startWeight" to JsonPrimitive(p.startWeight),
            "target" to JsonPrimitive(p.target),
            "current" to JsonPrimitive(p.current),
            "pausedSince" to date(p.pausedSince),
            "pausedDays" to JsonPrimitive(p.pausedDays),
            "completed" to JsonPrimitive(p.completed),
            "stayOnMaintenance" to JsonPrimitive(p.stayOnMaintenance),
            "migrated" to JsonPrimitive(p.migrated),
            "notice" to (p.notice?.let { n ->
                JsonObject(mapOf("title" to JsonPrimitive(n.title), "text" to JsonPrimitive(n.text)))
            } ?: JsonNull),
            "phases" to JsonArray(p.phases.map { phase ->
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive(phase.type.name.lowercase()),
                        "index" to JsonPrimitive(phase.index),
                        "plannedWeeks" to JsonPrimitive(phase.plannedWeeks),
                        "targetFat" to (phase.targetFat?.let { JsonPrimitive(it) } ?: JsonNull),
                        "startDate" to date(phase.startDate),
                        "endedDate" to date(phase.endedDate),
                        "endReason" to (phase.endReason?.let { JsonPrimitive(it.name.lowercase()) } ?: JsonNull),
                        "startFat" to (phase.startFat?.let { JsonPrimitive(it) } ?: JsonNull)
                    )
                )
            })
        )
    ).toString()

    /** null — пусто или не разобрать. */
    fun decode(text: String?): FatBurnProgram.Program? {
        if (text.isNullOrBlank()) return null
        return try {
            val o = json.parseToJsonElement(text).jsonObject
            val phases = (o["phases"] as? JsonArray).orEmpty().mapNotNull { el -> phase(el) }
            if (phases.isEmpty()) return null
            val startDate = parseDate(str(o, "startDate")) ?: return null
            FatBurnProgram.Program(
                startDate = startDate,
                startFat = dbl(o, "startFat") ?: return null,
                startWeight = dbl(o, "startWeight") ?: 0.0,
                target = dbl(o, "target") ?: return null,
                phases = phases,
                current = (int(o, "current") ?: 0).coerceIn(0, phases.size - 1),
                pausedSince = parseDate(str(o, "pausedSince")),
                pausedDays = (int(o, "pausedDays") ?: 0).coerceAtLeast(0),
                completed = bool(o, "completed") ?: false,
                stayOnMaintenance = bool(o, "stayOnMaintenance") ?: false,
                migrated = bool(o, "migrated") ?: false,
                notice = (o["notice"] as? JsonObject)?.let { n ->
                    val title = str(n, "title") ?: return@let null
                    FatBurnProgram.Notice(title, str(n, "text").orEmpty())
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun phase(el: JsonElement): FatBurnProgram.Phase? {
        val o = el as? JsonObject ?: return null
        val type = when (str(o, "type")) {
            "block" -> FatBurnProgram.PhaseType.BLOCK
            "break" -> FatBurnProgram.PhaseType.BREAK
            "maintenance" -> FatBurnProgram.PhaseType.MAINTENANCE
            else -> return null
        }
        val reason = str(o, "endReason")?.let { r ->
            FatBurnProgram.EndReason.entries.firstOrNull { it.name.equals(r, ignoreCase = true) }
        }
        return FatBurnProgram.Phase(
            type = type,
            index = int(o, "index") ?: 1,
            plannedWeeks = (int(o, "plannedWeeks") ?: FatBurnProgram.BLOCK_WEEKS).coerceAtLeast(1),
            targetFat = dbl(o, "targetFat"),
            startDate = parseDate(str(o, "startDate")),
            endedDate = parseDate(str(o, "endedDate")),
            endReason = reason,
            startFat = dbl(o, "startFat")
        )
    }

    private fun date(d: LocalDate?): JsonElement = d?.let { JsonPrimitive(it.toString()) } ?: JsonNull

    private fun prim(o: JsonObject, key: String): JsonPrimitive? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun str(o: JsonObject, key: String): String? = prim(o, key)?.contentOrNull

    private fun dbl(o: JsonObject, key: String): Double? = prim(o, key)?.doubleOrNull

    private fun int(o: JsonObject, key: String): Int? = prim(o, key)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

    private fun bool(o: JsonObject, key: String): Boolean? = prim(o, key)?.booleanOrNull

    private fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
