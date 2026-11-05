package com.nutrilens.app.bg

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Вход в Instagram для скачивания рилсов: Instagram всё чаще не отдаёт ролики без
 * аккаунта (особенно на адресах VPN). Пользователь входит на instagram.com во встроенном
 * браузере, куки остаются только на телефоне и передаются yt-dlp файлом cookies.txt.
 */
object InstagramSession {
    private const val SITE = "https://www.instagram.com"

    fun cookies(): String? = runCatching { CookieManager.getInstance().getCookie(SITE) }.getOrNull()

    fun isLoggedIn(): Boolean = cookies()?.split(';')?.any { it.trim().startsWith("sessionid=") && it.trim().length > 11 } == true

    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** cookies.txt (формат Netscape) для yt-dlp или null, если вход не выполнен. */
    fun cookieFile(context: Context): File? {
        if (!isLoggedIn()) return null
        val raw = cookies() ?: return null
        val expires = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val lines = raw.split(';').mapNotNull { part ->
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq <= 0) null else ".instagram.com\tTRUE\t/\tTRUE\t$expires\t${p.substring(0, eq)}\t${p.substring(eq + 1)}"
        }
        return File(context.cacheDir, "ig_cookies.txt").apply { writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n") }
    }
}
