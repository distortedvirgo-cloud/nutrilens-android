package com.nutrilens.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class ReleaseInfo(val version: String, val apkUrl: String, val notes: String)

object UpdateChecker {
    private val client = OkHttpClient()

    /** GET https://api.github.com/repos/{repo}/releases/latest (OkHttp, suspend через execute на Dispatchers.IO). Заголовки: Accept: application/vnd.github+json, User-Agent: NutriLensAndroid. HTTP 404 → вернуть null (релизов нет). Иной не-200 → RuntimeException("GitHub HTTP <code>"). Парсинг через org.json: version = tag_name без ведущей "v"; apkUrl = browser_download_url первого элемента assets, чьё name заканчивается на ".apk" (если такого нет → null); notes = body (или ""). */
    suspend fun checkLatest(repo: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NutriLensAndroid")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw RuntimeException("GitHub HTTP ${response.code}")
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val version = json.optString("tag_name", "").removePrefix("v")
            val notes = json.optString("body", "")
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name", "").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "").ifEmpty { null }
                        break
                    }
                }
            }
            ReleaseInfo(version, apkUrl ?: "", notes)
        }
    }

    /** Сравнение версий "1.2.3": разбить по '.', сравнивать первые 3 числовых компонента (toIntOrNull ?: 0). true если latest > current. */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val parse = { s: String ->
            val parts = s.split('.').map { it.toIntOrNull() ?: 0 }
            (parts + List(3) { 0 }).take(3)
        }
        val latestParts = parse(latest)
        val currentParts = parse(current)
        for (i in 0 until 3) {
            if (latestParts[i] > currentParts[i]) return true
            if (latestParts[i] < currentParts[i]) return false
        }
        return false
    }

    /** Стримит APK в File(context.cacheDir, "updates/nutrilens-update.apk") (создать директорию, перезаписать). onProgress(percent: Int) — 0..100 по contentLength; если contentLength <= 0 — вызвать onProgress(-1) один раз. Вернуть файл. Ошибки сети — пробрасывать. */
    suspend fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val updateDir = File(context.cacheDir, "updates")
            updateDir.mkdirs()
            val target = File(updateDir, "nutrilens-update.apk")

            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RuntimeException("Download HTTP ${response.code}")
                val body = response.body ?: throw RuntimeException("Empty response body")
                val contentLength = body.contentLength()
                if (contentLength <= 0) {
                    onProgress(-1)
                }
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                            if (contentLength > 0) {
                                val percent = ((total * 100) / contentLength).toInt().coerceIn(0, 100)
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            target
        }

    /** PackageManager.canRequestPackageInstalls() (API 26+, minSdk 26 — без проверок версии). */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Открыть настройки установки неизвестных приложений для нашего пакета: Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)), FLAG_ACTIVITY_NEW_TASK. */
    fun openInstallUnknownAppsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Установка: Uri через androidx.core.content.FileProvider.getUriForFile(context, "com.nutrilens.app.fileprovider", file); Intent(Intent.ACTION_VIEW) с setDataAndType(uri, "application/vnd.android.package-archive"), addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK); context.startActivity. */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "com.nutrilens.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}