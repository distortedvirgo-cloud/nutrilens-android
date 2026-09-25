package com.nutrilens.app.leaderboard

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.nutrilens.app.BuildConfig
import com.nutrilens.app.ai.mealJson
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Синхронизация лидерборда через GitHub Contents API: свой день кладётся PUT-ом
 * в data/<ник>.json, участники читаются списком каталога data/. Репозиторий и
 * токен приходят из BuildConfig (leaderboard.properties; в git файл не попадает).
 */
class LeaderboardSync(context: Context) {

    private val appContext = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Мержит сегодняшние ккал/цель в свой файл (хранит скользящее окно 35 дней) и PUT-ит.
     * Ник пуст или BuildConfig-конфиги пусты — no-op, вернуть false.
     */
    suspend fun pushOwn(): Boolean {
        if (BuildConfig.LEADERBOARD_REPO.isBlank() || BuildConfig.LEADERBOARD_TOKEN.isBlank()) {
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val db = NutriLensDatabase.getInstance(appContext)
                val settings = SettingsRepository(db.settingsDao()).get()
                val nickname = settings.leaderboardNickname.trim()
                if (nickname.isEmpty()) return@withContext false

                // Наш день: сумма ккал за сегодня + цель из настроек.
                val today = LocalDate.now().toString()
                val calories = db.mealDao().mealsBetween(today, today).sumOf { it.calories }

                // 409 — файл перезаписали между нашим GET и PUT (в т.ч. параллельный
                // пуш самого приложения: стартовый + «Обновить» на экране).
                // Один раз перечитываем sha и пробуем снова.
                var lastError: IOException? = null
                repeat(2) { attempt ->
                    try {
                        // Текущий файл (если есть): нужен и старый контент, и sha для PUT.
                        val existing = getOwnFile(nickname)
                        val previous = existing?.second ?: LeaderboardEntry(nickname = nickname)
                        val days = previous.days + (today to LeaderboardDay(calories, settings.dailyGoal))
                        val entry = previous.copy(
                            nickname = nickname,
                            // Скользящее окно: ключи «yyyy-MM-dd» сортируются как строки —
                            // оставляем последние 35 дней.
                            days = days.toSortedMap().toList().takeLast(MAX_DAYS).toMap(),
                            updatedAt = System.currentTimeMillis()
                        )
                        put(nickname, mealJson.encodeToString(entry), existing?.first)
                        return@withContext true
                    } catch (e: IOException) {
                        lastError = e
                        if (e.message?.contains("409") != true) throw e
                        if (attempt == 0) Log.w(TAG, "Пуш лидерборда: 409, повторяю с свежим sha")
                    }
                }
                throw lastError ?: IOException("GitHub PUT failed")
            } catch (e: Exception) {
                Log.w(TAG, "Пуш лидерборда не удался: ${e.message}")
                false
            }
        }
    }

    /** Читает все json-файлы участников из data. Без токена — emptyList().
     *  (Не писать в блочном комментарии «data/ слэш-звёздочка .json»: в Kotlin
     *  блочные комментарии вкладываются, лишний комментарий-открывашка
     *  съедает конец файла.) */
    suspend fun fetchAll(): List<LeaderboardEntry> {
        if (BuildConfig.LEADERBOARD_REPO.isBlank() || BuildConfig.LEADERBOARD_TOKEN.isBlank()) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            try {
                val listUrl = "$API_BASE/${BuildConfig.LEADERBOARD_REPO}/contents/$DATA_DIR"
                // 404 — каталог data/ ещё не создан: участников пока нет.
                val body = get(listUrl) ?: return@withContext emptyList()
                val files = mealJson.decodeFromString<List<GitHubContentDto>>(body)
                    .filter { it.type == "file" && it.name.endsWith(".json") }
                files.mapNotNull { file ->
                    runCatching {
                        // GET файла возвращает КОНВЕРТ Contents API (name/sha/content),
                        // сама запись лежит в content в base64: декод конверта как записи
                        // молча дал бы пустышки из-за ignoreUnknownKeys.
                        val dto = mealJson.decodeFromString<GitHubContentDto>(
                            get(fileUrl(Uri.decode(file.name.removeSuffix(".json")))) ?: return@runCatching null
                        )
                        val content = dto.content?.filterNot { it.isWhitespace() }.orEmpty()
                        if (content.isEmpty()) return@runCatching null
                        mealJson.decodeFromString<LeaderboardEntry>(
                            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
                        )
                    }.getOrNull()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Чтение лидерборда не удалось: ${e.message}")
                emptyList()
            }
        }
    }

    /** GET своего файла: пара (sha, запись); null — файла ещё нет (404). */
    private fun getOwnFile(nickname: String): Pair<String, LeaderboardEntry>? {
        val body = get(fileUrl(nickname)) ?: return null
        val dto = mealJson.decodeFromString<GitHubContentDto>(body)
        val content = dto.content?.filterNot { it.isWhitespace() }.orEmpty()
        if (dto.sha == null || content.isEmpty()) return null
        val json = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        return dto.sha to mealJson.decodeFromString<LeaderboardEntry>(json)
    }

    /** PUT файла участника; sha передаётся, если файл уже существовал. */
    private fun put(nickname: String, json: String, sha: String?) {
        val payload = mealJson.encodeToString(
            GitHubPutDto(
                message = COMMIT_MESSAGE,
                content = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                sha = sha
            )
        )
        val request = baseRequest(fileUrl(nickname))
            .put(payload.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("GitHub PUT ${resp.code}")
            }
        }
    }

    /** GET к Contents API; null — 404, прочие ошибки HTTP — IOException. */
    private fun get(url: String): String? {
        val request = baseRequest(url).get().build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("GitHub GET ${resp.code}: ${body.take(200)}")
            return body
        }
    }

    /** Базовый запрос к GitHub API: токен и Accept по правилам Contents API. */
    private fun baseRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${BuildConfig.LEADERBOARD_TOKEN}")
            .header("Accept", GITHUB_ACCEPT)

    /** URL файла участника; ник URL-кодируется как сегмент пути. */
    private fun fileUrl(nickname: String): String =
        "$API_BASE/${BuildConfig.LEADERBOARD_REPO}/contents/$DATA_DIR/${Uri.encode(nickname)}.json"

    companion object {
        private const val TAG = "LeaderboardSync"
        private const val API_BASE = "https://api.github.com/repos"
        private const val DATA_DIR = "data"
        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val COMMIT_MESSAGE = "leaderboard update"

        /** Скользящее окно дней в файле участника. */
        private const val MAX_DAYS = 35

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Fire-and-forget пуш своего дня в лидерборд: после каждого изменения
         * приёма пищи и один раз при старте приложения. Ошибки — только в лог.
         */
        fun pushAsync(context: Context) {
            val appContext = context.applicationContext
            pushScope.launch {
                runCatching { LeaderboardSync(appContext).pushOwn() }
                    .onFailure { Log.w(TAG, "Пуш лидерборда не удался: ${it.message}") }
            }
        }
    }
}

/** Элемент ответа Contents API (файл каталога data/ или сам файл). */
@Serializable
internal data class GitHubContentDto(
    val name: String = "",
    val type: String = "",
    val sha: String? = null,
    val content: String? = null
)

/** Тело PUT в Contents API: контент файла в base64 и sha предыдущей версии. */
@Serializable
internal data class GitHubPutDto(
    val message: String,
    val content: String,
    val sha: String? = null
)
