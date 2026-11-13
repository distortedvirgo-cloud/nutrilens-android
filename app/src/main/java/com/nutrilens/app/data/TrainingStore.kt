package com.nutrilens.app.data

import android.content.Context
import android.net.Uri
import com.nutrilens.app.training.WorkoutCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Тренировки из экспорта Hevy (3.27). Экспорт всегда содержит всю историю,
 * поэтому храним только последний файл целиком — без своей таблицы и дублей:
 * новая загрузка просто заменяет старую. Файл лежит в памяти приложения и
 * никуда не уходит (ни в ИИ, ни в лидерборд).
 */
object TrainingStore {
    private const val DIR = "training"
    private const val FILE = "workouts.csv"
    private const val PREFS = "training"
    private const val KEY_IMPORTED = "importedAt"

    /** Итог загрузки для короткого сообщения. */
    data class ImportResult(val workouts: Int, val sets: Int, val first: LocalDate?, val last: LocalDate?)

    private fun file(context: Context): File = File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    @Volatile private var cache: Pair<Long, List<WorkoutCsv.SetRow>>? = null

    /** Дата последней загрузки; null — тренировки ещё не загружали. */
    fun importedAt(context: Context): LocalDate? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IMPORTED, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Загрузка файла по ссылке из «Поделиться» или выбора файла. */
    suspend fun importUri(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw WorkoutCsv.FormatException("Не получилось открыть файл")
        importText(context, text)
    }

    /**
     * Разбирает экспорт, сохраняет его и отмечает дни тренировок в дневнике — галочку
     * «Тренировка» больше не нужно ставить руками. Бросает [WorkoutCsv.FormatException].
     */
    suspend fun importText(context: Context, text: String): ImportResult = withContext(Dispatchers.IO) {
        val parsed = WorkoutCsv.parse(text)
        if (parsed.rows.isEmpty()) throw WorkoutCsv.FormatException("В файле нет ни одного подхода")
        val f = file(context)
        f.writeText(text)
        cache = f.lastModified() to parsed.rows
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_IMPORTED, LocalDate.now().toString())
            .apply()
        val dao = NutriLensDatabase.getInstance(context).workoutDao()
        val dates = parsed.workoutDates
        for (d in dates) dao.upsert(WorkoutEntity(d.toString(), true))
        DataEvents.notifyChanged()
        ImportResult(dates.size, parsed.rows.size, dates.firstOrNull(), dates.lastOrNull())
    }

    /** Все подходы последней загрузки; пусто — файла нет или он битый. */
    fun rows(context: Context): List<WorkoutCsv.SetRow> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val stamp = f.lastModified()
        cache?.takeIf { it.first == stamp }?.let { return it.second }
        val rows = runCatching { WorkoutCsv.parse(f.readText()).rows }.getOrDefault(emptyList())
        cache = stamp to rows
        return rows
    }

    /** «12 тренировок с 3 июня по 15 сентября» — для сообщения после загрузки. */
    fun describe(r: ImportResult): String {
        val n = r.workouts
        val word = when {
            n % 100 in 11..14 -> "тренировок"
            n % 10 == 1 -> "тренировка"
            n % 10 in 2..4 -> "тренировки"
            else -> "тренировок"
        }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale("ru", "RU"))
        val range = if (r.first != null && r.last != null) " с ${r.first.format(fmt)} по ${r.last.format(fmt)}" else ""
        return "Загружено $n $word$range — дни тренировок отмечены в дневнике"
    }
}
