// Формулы и подсказки — дословный порт Android (insights/Insights.kt),
// чтобы цифры и советы совпадали на обеих платформах.
import type { Meal } from './db'

export interface DayTotals {
  calories: number
  protein: number
  fat: number
  carbs: number
  count: number
  lastEntryHour: number | null
}

export function dayTotals(meals: Meal[]): DayTotals {
  const t: DayTotals = { calories: 0, protein: 0, fat: 0, carbs: 0, count: meals.length, lastEntryHour: null }
  for (const m of meals) {
    t.calories += m.calories
    t.protein += m.protein
    t.fat += m.fat
    t.carbs += m.carbs
  }
  if (meals.length) {
    const last = [...meals].sort((a, b) => a.time.localeCompare(b.time)).at(-1)!
    const h = parseInt(last.time.split(':')[0], 10)
    if (!Number.isNaN(h)) t.lastEntryHour = h
  }
  return t
}

export function calcStreak(dates: Set<string>, now: Date): number {
  if (!dates.size) return 0
  let streak = 0
  let check = new Date(now)
  if (dates.has(iso(check))) streak++
  else {
    check = addDays(check, -1)
    if (!dates.has(iso(check))) return 0
  }
  for (;;) {
    check = addDays(check, -1)
    if (dates.has(iso(check))) streak++
    else break
  }
  return streak
}

export function greeting(hour: number): string {
  if (hour < 5) return 'Доброй ночи'
  if (hour < 12) return 'Доброе утро'
  if (hour < 18) return 'Добрый день'
  return 'Добрый вечер'
}

/** ~35 мл/кг (шаг 50 мл), +500 мл в день тренировки; без веса — 2000 мл. */
export function waterNorm(weightKg: number | null, workout: boolean): number {
  const base = weightKg != null ? Math.round((weightKg * 35) / 50) * 50 : 2000
  return workout ? base + 500 : base
}

export interface MacroGoals { protein: number; fat: number; carbs: number }

/** Свои цели из настроек, иначе: белок 1.8 г/кг, жиры 30% ккал, углеводы 40% ккал. */
export function macroGoals(
  proteinGoal: number | null, fatGoal: number | null, carbsGoal: number | null,
  dailyGoal: number, weightKg: number | null
): MacroGoals {
  const or = (v: number | null, f: () => number) => (v ? v : f())
  return {
    protein: or(proteinGoal, () => (weightKg != null ? Math.round(weightKg * 1.8) : Math.round((dailyGoal * 0.3) / 4))),
    fat: or(fatGoal, () => Math.round((dailyGoal * 0.3) / 9)),
    carbs: or(carbsGoal, () => Math.round((dailyGoal * 0.4) / 4))
  }
}

export interface Insight { id: string; emoji: string; title: string; text: string }

export function buildInsights(ctx: {
  hour: number; totals: DayTotals; dailyGoal: number; goals: MacroGoals
  waterMl: number; waterNormMl: number; streak: number; workout: boolean
}): Insight[] {
  const { hour, totals, dailyGoal, goals, waterMl, waterNormMl, streak, workout } = ctx
  const remaining = dailyGoal - totals.calories
  const out: { p: number; i: Insight }[] = []
  const add = (p: number, i: Insight) => out.push({ p, i })

  if (totals.count === 0) add(95, { id: 'empty', emoji: '📷', title: 'Пока нет записей', text: 'Запишите первый приём пищи — и я начну следить за балансом и подсказывать, что съесть дальше.' })
  if (totals.count > 0 && remaining < 0) add(100, { id: 'over', emoji: '⚠️', title: `Перебор на ${Math.round(-remaining)} ккал`, text: 'Не критично. Следующий приём сделайте легче: белок + овощи, без жирного и сладкого.' })

  const proteinDiff = goals.protein - totals.protein
  if (totals.count > 0 && proteinDiff > 15) add(80, { id: 'protein-low', emoji: '🍗', title: `Не хватает ${Math.round(proteinDiff)} г белка`, text: 'Белок даёт сытость и сохраняет мышцы. Добавьте творог, яйца, курицу или протеиновый коктейль.' })
  else if (totals.count > 0 && totals.protein >= goals.protein) add(40, { id: 'protein-ok', emoji: '🍗', title: 'Норма белка выполнена', text: `${Math.round(totals.protein)} г белка за день — отлично для мышц и контроля аппетита.` })

  if (waterMl < waterNormMl * 0.5) add(70, { id: 'water-low', emoji: '💧', title: `Выпито ${waterMl} из ${waterNormMl} мл`, text: 'Вода помогает контролировать аппетит и уровень энергии. Выпейте стакан прямо сейчас.' })
  else if (waterNormMl > 0 && waterMl >= waterNormMl) add(38, { id: 'water-ok', emoji: '💧', title: 'Норма воды выполнена', text: 'Отличная гидратация сегодня — так держать!' })

  if (totals.lastEntryHour != null && totals.count > 0) {
    const gap = hour - totals.lastEntryHour
    if (gap >= 5 && hour >= 6 && hour <= 23) add(60, { id: 'gap', emoji: '⏰', title: `Без записей уже ${gap} ч`, text: 'Долгие паузы часто ведут к перееданию вечером. Запишите перекус или приём пищи.' })
  }
  if (totals.count > 0 && remaining > 0 && hour >= 17 && remaining > dailyGoal * 0.35) add(55, { id: 'evening', emoji: '🍽️', title: `На ужин ещё ${Math.round(remaining)} ккал`, text: 'Не пропускайте ужин — иначе велик риск сорваться ночью. Выберите белок и овощи.' })
  if (totals.count > 0 && remaining > 0 && remaining < 200) add(35, { id: 'nearly', emoji: '🎯', title: 'Почти у цели', text: `Осталось всего ${Math.round(remaining)} ккал. Лёгкий перекус — и день идеально сбалансирован.` })
  if (workout) add(50, { id: 'workout', emoji: '💪', title: 'Тренировочный день', text: 'Поешьте за 1,5–2 ч до тренировки (углеводы + белок) и в течение часа после.' })
  if (streak >= 3) add(30, { id: 'streak', emoji: '🔥', title: `Серия ${streak} дн.`, text: 'Вы ведёте дневник без пропусков. Регулярность — главный секрет результата.' })

  return out.sort((a, b) => b.p - a.p).slice(0, 4).map((x) => x.i)
}

/** «Что съесть дальше»: [заголовок, текст] или null, если записей ещё нет. */
export function nextMealFocus(totals: DayTotals, goals: MacroGoals): [string, string] | null {
  if (totals.count === 0) return null
  const d = [
    { k: 'protein', share: totals.protein / goals.protein, left: goals.protein - totals.protein },
    { k: 'fat', share: totals.fat / goals.fat, left: goals.fat - totals.fat },
    { k: 'carbs', share: totals.carbs / goals.carbs, left: goals.carbs - totals.carbs }
  ]
  if (d.every((x) => x.left <= 0)) return ['Все цели по БЖУ закрыты', 'Отличный баланс! Следующий приём сделайте лёгким: овощи + нежирный белок.']
  const w = d.reduce((a, b) => (b.share < a.share ? b : a))
  const left = Math.round(w.left)
  if (w.k === 'protein') return [`Акцент на белок (+${left} г)`, 'Курица, рыба, яйца, творог или бобовые. Добавьте овощи — они дадут объём без лишних калорий.']
  if (w.k === 'fat') return [`Доберите полезных жиров (+${left} г)`, 'Авокадо, орехи, оливковое масло или жирная рыба. Жиры важны для гормонов и сытости.']
  return [`Нужны углеводы (+${left} г)`, 'Крупы, цельнозерновой хлеб, фрукты. Это энергия — особенно если впереди тренировка.']
}

export function healthScoreOf(m: Meal): number {
  if (m.healthScore != null) return Math.max(0, Math.min(100, Math.round(m.healthScore)))
  if (m.calories <= 0) return 50
  const p = (m.protein * 4) / m.calories, f = (m.fat * 9) / m.calories, c = (m.carbs * 4) / m.calories
  let s = 55
  if (p >= 0.18 && p <= 0.42) s += 15
  else if (p >= 0.1 && p < 0.18) s += 5
  else if (p < 0.1 || p > 0.5) s -= 10
  if (f > 0.45) s -= 20
  else if (f > 0.35) s -= 8
  else if (f < 0.25) s += 5
  if (c > 0.7) s -= 8
  return Math.max(5, Math.min(95, Math.round(s)))
}

export function healthLabel(score: number): string {
  if (score >= 75) return 'Сбалансированный состав'
  if (score >= 50) return 'Нейтральный состав'
  if (score >= 25) return 'Плотное блюдо'
  return 'Тяжёлое блюдо'
}

// ---------- Даты ----------

export function iso(d: Date): string {
  const y = d.getFullYear(), m = String(d.getMonth() + 1).padStart(2, '0'), day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
export function addDays(d: Date, n: number): Date {
  const r = new Date(d)
  r.setDate(r.getDate() + n)
  return r
}
export function parseIso(s: string): Date {
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y, m - 1, d)
}
export function nowTime(): string {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
export function mondayOf(d: Date): Date {
  const r = new Date(d)
  const wd = (r.getDay() + 6) % 7
  r.setDate(r.getDate() - wd)
  return r
}

const MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
const WEEKDAYS = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота']
export function dayTitle(d: Date): string {
  const today = iso(new Date()), s = iso(d)
  if (s === today) return 'Сегодня'
  if (s === iso(addDays(new Date(), -1))) return 'Вчера'
  return `${d.getDate()} ${MONTHS[d.getMonth()]}`
}
export function dayOverline(d: Date): string {
  return `${WEEKDAYS[d.getDay()]}, ${d.getDate()} ${MONTHS[d.getMonth()]}`
}
export function daysWord(n: number): string {
  const m100 = n % 100, m10 = n % 10
  if (m100 >= 11 && m100 <= 14) return 'дней'
  if (m10 === 1) return 'день'
  if (m10 >= 2 && m10 <= 4) return 'дня'
  return 'дней'
}

export type Slot = 'BREAKFAST' | 'LUNCH' | 'SNACK' | 'DINNER'
export const SLOTS: { key: Slot; title: string; cta: string }[] = [
  { key: 'BREAKFAST', title: 'Завтрак', cta: 'Сфотографировать завтрак' },
  { key: 'LUNCH', title: 'Обед', cta: 'Сфотографировать обед' },
  { key: 'SNACK', title: 'Перекус', cta: 'Добавить перекус' },
  { key: 'DINNER', title: 'Ужин', cta: 'Сфотографировать ужин' }
]
export function slotOf(time: string): Slot {
  const h = parseInt(time.split(':')[0], 10)
  if (Number.isNaN(h)) return 'LUNCH'
  if (h < 11) return 'BREAKFAST'
  if (h < 16) return 'LUNCH'
  if (h < 18) return 'SNACK'
  return 'DINNER'
}
