package com.nutrilens.app.update

import android.content.Context
import com.nutrilens.app.BuildConfig
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository

/** Что показать в окне обновления: версия, заголовок и пункты из заметок релиза. */
data class UpdateOffer(val release: ReleaseInfo, val title: String, val items: List<String>)

/**
 * Окно «Вышло обновление» при входе в приложение. Выбор пользователя:
 * «Напомнить позже» — тишина на сутки, «Не напоминать» — молчим про эту
 * версию (о следующей снова скажем). Ручная проверка в Профиль → Обновления
 * работает всегда.
 */
object UpdatePrompt {
    private const val PREFS = "updates"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val KEY_SKIPPED = "skipped_version"
    private const val SNOOZE_MS = 24L * 60 * 60 * 1000

    /** Есть ли что предложить прямо сейчас. Ошибки сети — тихо null. */
    suspend fun check(context: Context): UpdateOffer? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() < prefs.getLong(KEY_SNOOZE_UNTIL, 0L)) return null
        val repo = SettingsRepository(NutriLensDatabase.getInstance(context).settingsDao()).get().updateRepo
        val release = UpdateChecker.checkLatest(repo) ?: return null
        if (release.apkUrl.isEmpty()) return null
        if (!UpdateChecker.isNewerVersion(release.version, BuildConfig.VERSION_NAME)) return null
        if (prefs.getString(KEY_SKIPPED, null) == release.version) return null
        val (title, items) = parseNotes(release.notes)
        UpdateOffer(release, title, items)
    }.getOrNull()

    fun remindLater(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
            .apply()
    }

    fun skip(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SKIPPED, version)
            .apply()
    }

    /**
     * Заметки релиза → заголовок и пункты. Формат релизов: «## Заголовок» и
     * строки «- пункт» (их собирает release.yml из Changelog.kt). Для старых
     * релизов с текстом коммита — непустые строки без служебных подписей.
     */
    fun parseNotes(notes: String): Pair<String, List<String>> {
        val lines = notes.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { TRAILERS.any { t -> it.startsWith(t, ignoreCase = true) } }
        val title = lines.firstOrNull { it.startsWith("#") }?.trimStart('#', ' ').orEmpty()
        val bullets = lines.filter { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") }
            .map { it.drop(2).trim() }
        val items = bullets.ifEmpty { lines.filterNot { it.startsWith("#") } }
        return title to items.take(10)
    }

    private val TRAILERS = listOf("Co-Authored-By", "Claude-Session", "Signed-off-by", "https://claude.ai")
}
