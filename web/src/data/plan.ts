// Связка плана «Сжигание жира» с хранилищем: собрать вход из IndexedDB,
// пересчитать, если пора, и сохранить в настройки. Сам алгоритм — в fatplan.ts.
import { getSettings, latestMeasurement, latestWeight, mealsBetween, saveSettings, weightsBetween, type Settings } from './db'
import { activityFactor, bodyProfile, fatGoalOn } from './body'
import { type DataStatus, type PlanInput, PLAN, addIsoDays, assessData, computePlan, planDue } from './fatplan'
import { iso } from './insights'

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

let busy = false
let again = false

/**
 * Пересчитывает план, если пора (неделя прошла, режим только включили, сменили
 * цель, появился первый замер). Вызывается при запуске и при изменении данных —
 * дёшево: без пересчёта читает только настройки, вес и последний замер.
 */
export async function ensureFatPlan(): Promise<void> {
  // Изменение во время расчёта (сменили цель) не теряем — проверим ещё раз после.
  if (busy) { again = true; return }
  busy = true
  try {
    const s = await getSettings()
    if (!fatGoalOn(s)) return
    const fatPercent = (await latestMeasurement())?.fatPercent ?? null
    if (!planDue(s.fatPlan, { today: iso(new Date()), targetFat: s.targetFatPercent!, fatPercent })) return
    const input = await loadPlanInput(s)
    if (!input) return
    await saveSettings({ fatPlan: computePlan(input) })
  } finally {
    busy = false
    if (again) { again = false; void ensureFatPlan().catch(() => {}) }
  }
}
