// Состав тела: формула ВМС США, сухая/жировая масса, целевой вес и белок
// для цели «процент жира». Чистые функции — без IndexedDB и React.
import type { BodyMeasurement, Settings } from './db'
import { macroGoals, type MacroGoals } from './insights'
import type { FatPlan } from './fatplan'

export const FAT_TARGET_MIN = 8
export const FAT_TARGET_MAX = 35

/** Рост и пол: из настроек, иначе из текста «О себе» (онбординг пишет его туда). */
export function bodyProfile(s: Pick<Settings, 'heightCm' | 'sex' | 'userContext'>): { heightCm: number | null; female: boolean | null } {
  const ctx = s.userContext.toLowerCase()
  let heightCm = s.heightCm ?? null
  if (heightCm == null) {
    const m = /рост\D{0,4}(\d{3})/.exec(ctx)
    const h = m ? Number(m[1]) : NaN
    if (h >= 120 && h <= 230) heightCm = h
  }
  let female: boolean | null = s.sex == null ? null : s.sex === 'female'
  if (female == null) {
    if (/женщин|девушк/.test(ctx)) female = true
    else if (/мужчин|парень/.test(ctx)) female = false
  }
  return { heightCm, female }
}

/** Процент жира по методу ВМС США (см, log10). Неверный ввод — null; итог 3..60. */
export function navyFatPercent(p: { female: boolean; heightCm: number; waistCm: number; neckCm: number; hipsCm?: number | null }): number | null {
  const { female, heightCm: h, waistCm: w, neckCm: n } = p
  if (!(h > 0 && w > 0 && n > 0)) return null
  let v: number
  if (female) {
    const hips = p.hipsCm ?? 0
    if (!(hips > 0) || w + hips - n <= 0) return null
    v = 495 / (1.29579 - 0.35004 * Math.log10(w + hips - n) + 0.221 * Math.log10(h)) - 450
  } else {
    if (w - n <= 0) return null
    v = 495 / (1.0324 - 0.19077 * Math.log10(w - n) + 0.15456 * Math.log10(h)) - 450
  }
  if (!Number.isFinite(v)) return null
  return Math.round(Math.max(3, Math.min(60, v)) * 10) / 10
}

/** Итоговый процент замера: свой (весы, калипер, DEXA) важнее формулы. */
export function measurementFat(manual: number | null | undefined, computed: number | null): number | null {
  if (manual != null && manual >= 3 && manual <= 60) return manual
  return computed
}

export interface Composition {
  fatPercent: number
  leanMass: number
  fatMass: number
  /** Вес при целевом проценте, если мышцы сохранятся. */
  targetWeight: number | null
  fatToLose: number | null
  etaWeeks: number | null
}

export function composition(weight: number, fatPercent: number, targetFat: number | null | undefined): Composition {
  const leanMass = weight * (1 - fatPercent / 100)
  const fatMass = weight - leanMass
  let targetWeight: number | null = null, fatToLose: number | null = null, etaWeeks: number | null = null
  if (targetFat != null && targetFat > 0 && targetFat < 100) {
    targetWeight = leanMass / (1 - targetFat / 100)
    fatToLose = Math.max(0, weight - targetWeight)
    // Безопасный темп — ~0,6% веса в неделю.
    etaWeeks = fatToLose > 0 ? fatToLose / (weight * 0.006) : 0
  }
  return { fatPercent, leanMass, fatMass, targetWeight, fatToLose, etaWeeks }
}

/** «≈ 7 недель» / «≈ 4 мес.»; 0 — цель достигнута. */
export function etaText(weeks: number): string {
  if (weeks <= 0.5) return 'цель достигнута'
  if (weeks <= 12) {
    const n = Math.max(1, Math.round(weeks))
    return `≈ ${n} ${plural(n, 'неделя', 'недели', 'недель')}`
  }
  const m = Math.round(weeks / 4.345)
  return `≈ ${m} ${plural(m, 'месяц', 'месяца', 'месяцев')}`
}

export function plural(n: number, one: string, few: string, many: string): string {
  const m100 = n % 100, m10 = n % 10
  if (m100 >= 11 && m100 <= 14) return many
  if (m10 === 1) return one
  if (m10 >= 2 && m10 <= 4) return few
  return many
}

export const fatGoalOn = (s: Pick<Settings, 'targetFatPercent'>): boolean => s.targetFatPercent != null && s.targetFatPercent > 0

/** Белок при цели по жиру: 2,6 г на кг сухой массы (шаг 5 г); без замера — 2,0 г на кг веса. */
export function fatGoalProtein(weight: number | null, fatPercent: number | null): number | null {
  if (weight == null) return null
  const g = fatPercent != null ? weight * (1 - fatPercent / 100) * 2.6 : weight * 2.0
  return Math.round(g / 5) * 5
}

export interface EffectiveGoals extends MacroGoals {
  calories: number
  /** План режима «Сжигание жира», если цели посчитаны им; null — ручные цели. */
  plan: FatPlan | null
}

/**
 * Калорийная цель дня — единая точка для «Сегодня», коуча, лидерборда и снимка
 * цели в записи еды. В режиме сжигания жира — из плана, иначе ручная.
 */
export function effectiveCalories(s: Pick<Settings, 'targetFatPercent' | 'fatPlan' | 'dailyGoal'>): number {
  return fatGoalOn(s) && s.fatPlan ? s.fatPlan.calories : s.dailyGoal
}

/**
 * Цели, которые видит пользователь. Единая точка: в режиме сжигания жира — план
 * (калории и БЖУ считаются сами); пока плана нет (не записан вес) — ручные цели,
 * но белок уже от сухой массы.
 */
export function effectiveGoals(s: Settings, weight: number | null, latest: BodyMeasurement | null | undefined): EffectiveGoals {
  if (fatGoalOn(s) && s.fatPlan) {
    const p = s.fatPlan
    return { calories: p.calories, protein: p.protein, fat: p.fat, carbs: p.carbs, plan: p }
  }
  const g = { ...macroGoals(s.proteinGoal, s.fatGoal, s.carbsGoal, s.dailyGoal, weight), calories: s.dailyGoal, plan: null }
  if (!fatGoalOn(s)) return g
  const p = fatGoalProtein(weight, latest?.fatPercent ?? null)
  return p != null ? { ...g, protein: p } : g
}

/**
 * Множитель активности для плана. Отдельного поля нет — онбординг пишет уровень
 * в «О себе» («активность средняя»), берём оттуда; не нашли — null (будет 1,4).
 */
export function activityFactor(s: Pick<Settings, 'userContext'>): number | null {
  const m = /активност\S*\s+(низк|средн|высок)/i.exec(s.userContext)
  if (!m) return null
  return { 'низк': 1.3, 'средн': 1.5, 'высок': 1.7 }[m[1].toLowerCase() as 'низк' | 'средн' | 'высок']
}

/** Режим по умолчанию: 15% у мужчин, 23% у женщин. */
export const defaultFatTarget = (female: boolean | null): number => (female ? 23 : 15)

/** Строка для ИИ-коуча; пусто, если данных нет. */
export function bodyContext(s: Settings, weight: number | null, latest: BodyMeasurement | null | undefined): string {
  if (!latest) return fatGoalOn(s) ? `Цель по жиру: ${s.targetFatPercent}% (замеров пока нет), задача — сжечь жир и сохранить мышцы.` : ''
  const parts = [`Процент жира ${latest.fatPercent.toFixed(1)}% (замер ${latest.date})`]
  if (weight != null) parts.push(`сухая масса ≈ ${(weight * (1 - latest.fatPercent / 100)).toFixed(1)} кг`)
  if (fatGoalOn(s)) parts.push(`цель ${s.targetFatPercent}% жира с сохранением мышц`)
  return parts.join(', ') + '.'
}

export const fmt1 = (v: number): string => v.toFixed(1).replace('.', ',')
