package com.nutrilens.app.leaderboard

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Постоянный id игрока в лидерборде. Не зависит от ника: файл участника
 * называется по id, поэтому смена ника меняет только поле внутри файла.
 *
 * Первый запуск: id выводится из ANDROID_ID (он стабилен для пары
 * «устройство + ключ подписи», то есть переживает переустановку при
 * постоянной подписи), иначе — случайный UUID. Id попадает в бэкап, так что
 * переезд на новый телефон через экспорт/импорт сохраняет место в таблице.
 */
object PlayerIdentity {
    private const val PREFS = "leaderboard"
    private const val KEY_ID = "player_id"
    private const val KEY_LAST_NICK = "last_pushed_nickname"
    private const val KEY_ABSORBED = "absorbed_legacy"

    @SuppressLint("HardwareIds")
    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val seed = androidId?.takeIf { it.isNotBlank() }?.let { "${context.packageName}:$it" }
            ?: UUID.randomUUID().toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    /** Восстановление id из бэкапа (импорт данных на новом устройстве). */
    fun restore(context: Context, id: String) {
        if (id.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, id)
            .remove(KEY_ABSORBED)
            .apply()
    }

    fun lastPushedNickname(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_NICK, null)

    fun setLastPushedNickname(context: Context, nickname: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_NICK, nickname).apply()
    }

    /** Ники, чьи старые файлы (без id) уже слиты в файл игрока. */
    fun absorbed(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ABSORBED, emptySet()).orEmpty()

    fun markAbsorbed(context: Context, nickname: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ABSORBED, emptySet()).orEmpty() + nickname
        prefs.edit().putStringSet(KEY_ABSORBED, set).apply()
    }
}
