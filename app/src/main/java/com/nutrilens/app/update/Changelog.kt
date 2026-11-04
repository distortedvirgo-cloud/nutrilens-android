package com.nutrilens.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nutrilens.app.BuildConfig

/** Одна версия в «Что нового»: код сборки, имя версии, заголовок и пункты. */
data class ChangelogEntry(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val items: List<String>
)

/**
 * Список изменений, новые сверху. Добавляя версию, допиши сюда запись с её
 * versionCode — после обновления приложение само покажет её один раз.
 */
val CHANGELOG: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        versionCode = 62,
        versionName = "3.5",
        title = "Книга рецептов",
        items = listOf(
            "Новая вкладка «Рецепты»: ссылка на YouTube, Shorts, TikTok, Instagram или сайт — и ИИ соберёт рецепт с ингредиентами, шагами и КБЖУ на порцию",
            "Увидели рецепт в другом приложении — «Поделиться» → NutriLens, разбор начнётся сам",
            "Можно загрузить само видео, фото рецепта или вставить текст; обложка — кадр с готовым блюдом",
            "Пересчёт на любое число порций, отметки «уже есть», список покупок в «Поделиться»",
            "Режим готовки: шаг за шагом крупным шрифтом, таймеры, экран не гаснет",
            "Порция из рецепта записывается в дневник одной кнопкой",
            "Профиль теперь открывается кружком в шапке «Сегодня»",
            "В лидерборде скрываются старые файлы игроков, сменивших ник до появления постоянного id"
        )
    ),
    ChangelogEntry(
        versionCode = 61,
        versionName = "3.4",
        title = "ИИ строго по режиму",
        items = listOf(
            "Режим больше не подменяет сервис: NanoGPT — только NanoGPT, Gemini — только Gemini, без тихих переключений",
            "Нет ключа выбранного сервиса — приложение сразу подскажет, какой именно ключ добавить (Профиль → Нейросеть)"
        )
    ),
    ChangelogEntry(
        versionCode = 60,
        versionName = "3.3",
        title = "Обновления и бесплатный режим",
        items = listOf(
            "Первый запуск: расскажите о себе — норма калорий посчитается сама — и вставьте ключ нейросети",
            "Режимы ИИ упрощены до двух сервисов: NanoGPT (рекомендуем) и Gemini (бесплатная альтернатива)",
            "Пока ключа нет, на «Сегодня» и в Профиле висит плашка «Подключите нейросеть»; Профиль → Нейросеть — со ссылками на получение ключа",
            "Вышла новая версия — при входе появляется окно со сводкой изменений: скачать, напомнить позже или не напоминать",
            "Бесплатный Gemini упёрся в лимит запросов в минуту — приложение само подождёт и повторит",
            "Закончился дневной лимит — анализ переключается на резервную модель Google с отдельной квотой",
            "Понятные сообщения вместо кодов ошибок: неверный ключ, регион, перегрузка серверов",
            "Появилась веб-версия NutriLens для iPhone — бэкап из Android переносится в неё целиком"
        )
    ),
    ChangelogEntry(
        versionCode = 59,
        versionName = "3.2",
        title = "Умнее ИИ",
        items = listOf(
            "Анализ еды сначала разбирает продукты и проверяет себя (вес × калорийность, 4Б+9Ж+4У), а потом считает итог",
            "Противоречивые расчёты больше не получают высокую уверенность",
            "Если на фото нет еды — сразу понятное сообщение, без пустых записей и повторов",
            "Неудавшийся анализ виден прямо на «Сегодня»: повторить, поправить описание или скрыть",
            "Диетолог в чате видит остаток калорий, БЖУ против целей, время и прошлые дни — советы конкретнее",
            "Чат отвечает быстрее и дешевле: старые фото не пересылаются заново",
            "Исправлено: в бесплатном режиме чат мог показать служебный JSON вместо ответа",
            "Исправлено: «Холодильник» и «Меню» в режимах Простой/Продвинутый не видели фото",
            "«Оценка от ИИ» в Прогрессе — с целями по БЖУ и конкретным планом на завтра",
            "Рецепты — с числом порций и КБЖУ на порцию"
        )
    ),
    ChangelogEntry(
        versionCode = 58,
        versionName = "3.1",
        title = "Единый стиль",
        items = listOf(
            "Все экраны приведены к одному стилю: те же плашки, поля, кнопки и скругления, что в карточке блюда",
            "Диалоги стали нижними листами — удобнее тянуть и закрывать жестом",
            "Карточка блюда разгружена: «В избранное» и «Удалить» теперь внутри неё",
            "Подсказки коуча получили фирменные иконки вместо эмодзи",
            "Прогресс: сводка периода в цветных плашках, ответ ИИ — в отдельной карточке",
            "Обновлены иконка приложения и виджеты; иконка поддерживает тематический режим Android 13+",
            "После обновления показывается это окно — чтобы было видно, что изменилось"
        )
    ),
    ChangelogEntry(
        versionCode = 57,
        versionName = "3.0",
        title = "Новая структура",
        items = listOf(
            "Вкладки Сегодня, Прогресс, Коуч и Профиль, лист быстрых действий «+»",
            "Виджеты «Сегодня», «Вода» и «Камера»",
            "Постоянная подпись: обновления встают поверх установленного приложения"
        )
    )
)

/**
 * Решает, показывать ли «Что нового». Свежая установка — не показываем (пользователю
 * всё в новинку), только запоминаем версию. Обновление — показываем записи новее
 * последней увиденной; если версия ещё не запоминалась (обновление с 3.0 и раньше) —
 * только запись текущей версии.
 */
object WhatsNew {
    private const val PREFS = "whats_new"
    private const val KEY_SEEN = "seen_version_code"

    fun pending(context: Context): List<ChangelogEntry> {
        val current = BuildConfig.VERSION_CODE
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getInt(KEY_SEEN, -1)
        if (seen >= current) return emptyList()
        if (seen == -1 && isFreshInstall(context)) {
            markSeen(context)
            return emptyList()
        }
        val from = if (seen == -1) current - 1 else seen
        return CHANGELOG.filter { it.versionCode in (from + 1)..current }
    }

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SEEN, BuildConfig.VERSION_CODE)
            .apply()
    }

    private fun isFreshInstall(context: Context): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)
}
