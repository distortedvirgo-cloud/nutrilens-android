// Связка плана «Сжигание жира» с хранилищем: собрать вход из IndexedDB,
// пересчитать, если пора, и сохранить в настройки. Сам алгоритм — в fatplan.ts,
// правила чек-ина — в checkin.ts.
import {
  activityBetween, getSettings, lastCheckin, latestMeasurement, latestWeight, measurements, mealsBetween, putCheckin, saveSettings,
  weightsBetween, workoutsBetween, type Settings
} from './db'
import { activityFactor, bodyProfile, fatGoalOn } from './body'
import { type DataStatus, type FatPlan, type PlanInput, PLAN, addIsoDays, assessData, computePlan, planDue } from './fatplan'
import { type CheckIn, type Ratings, CHECKIN, buildCheckin, checkinDue, checkinPeriod, nextCheckinDate } from './checkin'
import { iso } from './insights'
import { type DayGrade, SCORE, gradeDay } from './dayscore'

/** Вход для алгоритма; null — режим выключен или веса ещё нет (без веса плана не посчитать). */
export async function loadPlanInput(s?: Settings): Promise<PlanInput | null> {
  s ??= await getSettings()
  if (!fatGoalOn(s)) return null
  const w = await latestWeight()
  if (!w) return null
  const today = iso(new Date())
  const meals = await mealsBetween(addIsoDays(today, -PLAN.windowDays), addIsoDays(today, -1))
  const byDay = new Map<string, number>()
  meals.forEach((m) => byDay.set(m.date, (byDay.get(m.date) ?? 0) + m.calories))
  return {
    female: bodyProfile(s).female ?? false,
    weight: w.weight,
    weights: await weightsBetween(addIsoDays(today, -(PLAN.windowDays - 1)), today),
    days: [...byDay].map(([date, kcal]) => ({ date, kcal })),
    fatPercent: (await latestMeasurement())?.fatPercent ?? null,
    targetFat: s.targetFatPercent!,
    activity: activityFactor(s),
    prev: s.fatPlan,
    today
  }
}

/** Что уже накоплено для расчёта по данным — для карточки плана (живое, не из сохранённого плана). */
export async function planDataStatus(): Promise<DataStatus | null> {
  const input = await loadPlanInput()
  return input ? assessData(input) : null
}

// Пересчёт без чек-ина и сохранение чек-ина не должны перетереть друг друга:
// оба пишут fatPlan. Идут по очереди — следующий ждёт предыдущий.
let queue: Promise<unknown> = Promise.resolve()
function serial<T>(fn: () => Promise<T>): Promise<T> {
  const run = queue.then(fn, fn)
  queue = run.catch(() => {})
  return run
}

let pending = false

/**
 * Пересчитывает план, если пора: режим только включили, сменили цель или
 * появился первый замер. Недельные изменения — только через чек-ин.
 * Вызывается при запуске и при изменении данных — дёшево: без пересчёта читает
 * только настройки и последний замер.
 */
export function ensureFatPlan(): Promise<void> {
  // Пока один пересчёт ждёт в очереди, второй не нужен — он прочтёт свежие данные.
  if (pending) return Promise.resolve()
  pending = true
  return serial(async () => {
    pending = false
    const s = await getSettings()
    if (!fatGoalOn(s)) return
    const fatPercent = (await latestMeasurement())?.fatPercent ?? null
    if (!planDue(s.fatPlan, { today: iso(new Date()), targetFat: s.targetFatPercent!, fatPercent })) return
    const input = await loadPlanInput(s)
    if (!input) return
    const plan = computePlan(input)
    const last = await lastCheckin()
    await saveSettings({ fatPlan: { ...plan, nextDate: nextCheckinDate(last?.date ?? plan.date, s.checkinWeekday) } })
  })
}

export interface CheckinStatus {
  due: boolean
  /** Дата ближайшего чек-ина (или с какого дня он ждёт). */
  next: string
  last: CheckIn | null
}

/** Пора ли чек-ин; null — режим выключен или плана ещё нет (нечего подводить). */
export async function checkinStatus(s?: Settings): Promise<CheckinStatus | null> {
  s ??= await getSettings()
  if (!fatGoalOn(s) || !s.fatPlan) return null
  const last = (await lastCheckin()) ?? null
  const anchor = last?.date ?? s.fatPlan.date
  const today = iso(new Date())
  return { due: checkinDue(anchor, s.checkinWeekday, today), next: nextCheckinDate(anchor, s.checkinWeekday), last }
}

/**
 * Подводит неделю: собирает дневник, силовые, вес и замеры за период, считает
 * итог и новый план, сохраняет чек-ин и план. prevPlan — план на момент
 * открытия чек-ина: замер внутри чек-ина мог уже пересчитать план, а «было»
 * должно показывать то, по чему человек жил неделю.
 */
export function completeCheckin(ratings: Ratings, prevPlan: FatPlan): Promise<CheckIn | null> {
  return serial(async () => {
    const s = await getSettings()
    const input = await loadPlanInput(s)
    if (!input) return null
    const today = input.today
    const prev = (await lastCheckin()) ?? null
    const { start } = checkinPeriod(prev?.date ?? prevPlan.date, today)
    const meals = await mealsBetween(start, addIsoDays(today, -1))
    const byDay = new Map<string, { kcal: number; protein: number }>()
    meals.forEach((m) => {
      const d = byDay.get(m.date) ?? { kcal: 0, protein: 0 }
      byDay.set(m.date, { kcal: d.kcal + m.calories, protein: d.protein + m.protein })
    })
    const { checkin, plan } = buildCheckin({
      today,
      plan: { ...input, prev: prevPlan },
      days: [...byDay].map(([date, v]) => ({ date, ...v })),
      workouts: await workoutsBetween(start, addIsoDays(today, -1)),
      activity: await activityBetween(start, addIsoDays(today, -1)),
      stepGoal: s.stepGoal || SCORE.stepGoalDefault,
      // Месяц до начала периода — тренд успевает «разогреться» и не стартует с сырой цифры.
      weights: await weightsBetween(addIsoDays(start, -30), today),
      measurements: (await measurements()).map((m) => ({ date: m.date, fatPercent: m.fatPercent })),
      workoutGoal: s.workoutGoal || CHECKIN.workoutGoalDefault,
      prev,
      ratings
    })
    await saveSettings({ fatPlan: { ...plan, nextDate: nextCheckinDate(today, s.checkinWeekday) } })
    await putCheckin(checkin)
    return checkin
  })
}

/**
 * Оценки дней за даты (включительно) — для полосы недели и «Прогресса».
 * Прошлые дни считаем от текущего плана: исторических планов по дням нет,
 * а шаг плана за неделю — не больше ±150 ккал, оценка почти не сдвигается.
 * null — режим выключен или плана ещё нет.
 */
export async function dayGrades(start: string, end: string, s?: Settings): Promise<Map<string, DayGrade> | null> {
  s ??= await getSettings()
  if (!fatGoalOn(s) || !s.fatPlan) return null
  const today = iso(new Date())
  const food = new Map<string, { kcal: number; protein: number }>()
  for (const m of await mealsBetween(start, end)) {
    const d = food.get(m.date) ?? { kcal: 0, protein: 0 }
    food.set(m.date, { kcal: d.kcal + m.calories, protein: d.protein + m.protein })
  }
  const workouts = new Set(await workoutsBetween(start, end))
  const act = new Map((await activityBetween(start, end)).map((a) => [a.date, a]))
  const t = { calories: s.fatPlan.calories, protein: s.fatPlan.protein, stepGoal: s.stepGoal || SCORE.stepGoalDefault }
  const out = new Map<string, DayGrade>()
  for (let d = start; d <= end; d = addIsoDays(d, 1)) {
    if (d > today) break
    const f = food.get(d)
    out.set(d, gradeDay({ kcal: f?.kcal ?? 0, protein: f?.protein ?? 0, workout: workouts.has(d) }, act.get(d) ?? null, t, d === today))
  }
  return out
}
