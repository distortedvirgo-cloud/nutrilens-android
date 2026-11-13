// Подсказка коуча на сегодня: собирает данные из базы для чистого модуля nudges.ts.
// Что уже показывали — в localStorage: это удобство одного устройства, не данные.
import { activityBetween, getSettings, mealsBetween, type Settings } from './db'
import { fatGoalOn, livePlan } from './body'
import { addIsoDays } from './fatplan'
import { iso } from './insights'
import { type Nudge, type NudgeDay, type NudgeHistory, nextNudge } from './nudges'
import { PROGRAM, activeDays, currentPhase } from './program'

const HISTORY_KEY = 'nl-nudges'

function readHistory(): NudgeHistory {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '{}') as NudgeHistory } catch { return {} }
}

/** Отметить подсказку прочитанной: больше не покажется, а её тип отдохнёт cooldown дней. */
export function dismissNudge(key: string): void {
  try {
    const h = readHistory()
    h[key] = iso(new Date())
    // Старше 30 дней — не нужны: cooldown короче.
    const cut = addIsoDays(iso(new Date()), -30)
    for (const k of Object.keys(h)) if (h[k] < cut) delete h[k]
    localStorage.setItem(HISTORY_KEY, JSON.stringify(h))
  } catch { /* приватный режим — подсказка просто покажется ещё раз */ }
}

/** Подсказка на сегодня или null: только в идущей программе (не пауза, не завершена). */
export async function loadNudge(s?: Settings): Promise<Nudge | null> {
  s ??= await getSettings()
  const p = s.program
  if (!fatGoalOn(s) || !p || p.pausedSince || p.completedDate) return null
  const plan = livePlan(s)
  if (!plan) return null
  const today = iso(new Date())
  const start = addIsoDays(today, -7)
  const end = addIsoDays(today, -1)
  const food = new Map<string, { kcal: number; protein: number }>()
  for (const m of await mealsBetween(start, end)) {
    const d = food.get(m.date) ?? { kcal: 0, protein: 0 }
    food.set(m.date, { kcal: d.kcal + m.calories, protein: d.protein + m.protein })
  }
  const act = new Map((await activityBetween(start, end)).map((a) => [a.date, a]))
  // Дни до начала программы не считаем: «два дня без записей» до старта — не повод.
  const days: NudgeDay[] = []
  for (let d = start; d <= end; d = addIsoDays(d, 1)) {
    if (d < p.startDate) continue
    const f = food.get(d)
    days.push({ date: d, kcal: f?.kcal ?? 0, protein: f?.protein ?? 0, sleptWell: act.get(d)?.sleptWell ?? null })
  }
  const cur = currentPhase(p)
  const left = cur.type === 'break' ? PROGRAM.breakDays - activeDays(p, p.currentPhase, today) : null
  return nextNudge({
    today, days, proteinTarget: plan.protein,
    phase: cur.startDate
      ? { type: cur.type, startDate: cur.startDate, daysLeft: left, nextCalories: s.fatPlan?.calories ?? null }
      : null
  }, readHistory())
}
