package com.nutrilens.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Калибровка процента жира (3.30). Формула по ленте (ВМС США) хорошо ловит изменения,
 * но в абсолютной цифре может сильно ошибаться — особенно у высоких людей и когда жир
 * лежит ниже пупка. Поэтому стартовую точку человек выбирает по виду, а дальше
 * процент = формула + поправка: талия ушла на сантиметр — процент снизился так же,
 * как по формуле, только от честной точки.
 */
object FatCalibration {
    /** Формула по обхватам замера — без поправки; null — обхватов не хватает. */
    fun rawFormula(settings: SettingsEntity, m: BodyMeasurementEntity): Double? = BodyComposition.navyFatPercent(
        BodyComposition.isMale(settings), BodyComposition.heightCm(settings), m.waistCm, m.neckCm, m.hipsCm
    )

    /** Процент замера с поправкой: свой процент (весы, DEXA) важнее, иначе формула + поправка. */
    fun calibrated(raw: Double?, offset: Double?): Double? =
        raw?.let { (it + (offset ?: 0.0)).coerceIn(3.0, 60.0) }

    /**
     * Откалибровать: [look] — процент, выбранный по виду, [reference] — замер, к которому
     * он относится (сегодняшний или последний). Поправка = выбранный − формула этого замера.
     * Все замеры без своего процента пересчитываются — тренд остаётся ровным.
     * false — у замера нет обхватов для формулы (тогда калибровать не от чего).
     */
    suspend fun apply(context: Context, look: Double, reference: BodyMeasurementEntity): Boolean = withContext(Dispatchers.IO) {
        val db = NutriLensDatabase.getInstance(context.applicationContext)
        val repo = SettingsRepository(db.settingsDao())
        val settings = repo.get()
        val raw = rawFormula(settings, reference) ?: return@withContext false
        val offset = ((look - raw) * 10).roundToInt() / 10.0
        repo.update { it.copy(fatOffset = offset) }
        val dao = db.bodyMeasurementDao()
        val all = (dao.all() + reference).associateBy { it.date }.values
        for (m in all) {
            if (m.manualFatPercent != null) {
                if (m.date == reference.date) dao.upsert(m)
                continue
            }
            val fat = calibrated(rawFormula(settings, m), offset) ?: continue
            dao.upsert(m.copy(fatPercent = (fat * 10).roundToInt() / 10.0))
        }
        DataEvents.notifyChanged()
        true
    }
}
