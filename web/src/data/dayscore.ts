// Оценка дня режима «Сжигание жира» (3.23): калории, белок, движение и сон —
// 0–100 баллов. Чистый модуль, как fatplan.ts и checkin.ts: без IndexedDB, React
// и «сейчас» — всё приходит во входе, поэтому правила легко проверить скриптом.
// Пороги — в SCORE, чтобы тюнить в одном месте (и одинаково с Android).

export const SCORE = {
  /** Баллы частей: вместе 100. Калории и белок — главное для жира и мышц. */
  kcalPts: 35,
  proteinPts: 30,
  movePts: 20,
  sleepPts: 15,
  /** Калории — полный балл в коридоре −10%…+5% плана. */
  kcalLo: 0.9,
  kcalHi: 1.05,
  /** Линейно до нуля к −30% и к +25%: сильный недобор опасен для мышц так же, как перебор — для жира. */
  kcalZeroLo: 0.7,
  kcalZeroHi: 1.25,
  /** Белок — полный балл от 90% цели. */
  proteinShare: 0.9,
  /** «Хороший день» — от 70; цвета: 70+ хорошо, 40–69 средне, ниже — слабо. */
  good: 70,
  medium: 40,
  /** Цель по шагам: 8000 по умолчанию, 4000–15000 с шагом 500. */
  stepGoalDefault: 8000,
  stepGoalMin: 4000,
  stepGoalMax: 15000,
  stepGoalStep: 500,
  /** Неделя программы засчитана: от 5 хороших дней и сделанный чек-ин. */
  weekGoodDays: 5
} as const

export type StepsSource = 'health_connect' | 'sensor' | 'manual'

/** Запись активности за дату: одна на день, обновляется частями. null — не указано. */
export interface DayActivity {
  date: string // YYYY-MM-DD
  steps: number | null
  stepsSource: StepsSource | null
  /** «Поспал 8 часов» про прошлую ночь; null — не ответил. */
  sleptWell: boolean | null
}

/** Что было за день. */
export interface DayFacts {
  kcal: number
  protein: number
  /** Отмечена силовая. */
  workout: boolean
  steps: number | null
  sleptWell: boolean | null
}

/** План дня: калории и белок из плана режима, цель по шагам — из настроек. */
export interface DayTargets {
  calories: number
  protein: number
  stepGoal: number
}

export interface DayScore {
  /** 0–100, целое. */
  total: number
  /** Баллы частей, до 0,1 — для подсказок и помощника. */
  calories: number
  protein: number
  movement: number
  sleep: number
}

const clamp01 = (v: number) => Math.max(0, Math.min(1, v))
const r1 = (v: number) => Math.round(v * 10) / 10

/** Калории: коридор — полный балл; за ним — линейный спад до нуля; нет еды — 0. */
export function caloriePoints(kcal: number, plan: number): number {
  if (!(kcal > 0) || !(plan > 0)) return 0
  const r = kcal / plan
  if (r >= SCORE.kcalLo && r <= SCORE.kcalHi) return SCORE.kcalPts
  const k = r < SCORE.kcalLo
    ? (r - SCORE.kcalZeroLo) / (SCORE.kcalLo - SCORE.kcalZeroLo)
    : (SCORE.kcalZeroHi - r) / (SCORE.kcalZeroHi - SCORE.kcalHi)
  return SCORE.kcalPts * clamp01(k)
}

/** Белок: полный балл от 90% цели, ниже — пропорционально. */
export function proteinPoints(protein: number, target: number): number {
  if (!(target > 0)) return 0
  return SCORE.proteinPts * clamp01(Math.max(0, protein) / (SCORE.proteinShare * target))
}

/** Движение: силовая или цель по шагам — полный балл; иначе доля шагов. */
export function movementPoints(workout: boolean, steps: number | null, goal: number): number {
  if (workout) return SCORE.movePts
  if (steps == null || !(goal > 0)) return 0
  return SCORE.movePts * clamp01(steps / goal)
}

export const sleepPoints = (slept: boolean | null): number => (slept === true ? SCORE.sleepPts : 0)

export function scoreDay(f: DayFacts, t: DayTargets): DayScore {
  const calories = caloriePoints(f.kcal, t.calories)
  const protein = f.kcal > 0 || f.protein > 0 ? proteinPoints(f.protein, t.protein) : 0
  const movement = movementPoints(f.workout, f.steps, t.stepGoal)
  const sleep = sleepPoints(f.sleptWell)
  return {
    total: Math.round(calories + protein + movement + sleep),
    calories: r1(calories), protein: r1(protein), movement: r1(movement), sleep: r1(sleep)
  }
}

/**
 * «Хороший день» по правилу 3.22: калории −10%…+5% плана и белок от 90%.
 * Нужен для дней без записи активности (до 3.23) — чтобы история не стала
 * задним числом «плохой» из-за сна и шагов, которых тогда не было.
 */
export function legacyGood(kcal: number, protein: number, t: Pick<DayTargets, 'calories' | 'protein'>): boolean {
  return kcal >= t.calories * SCORE.kcalLo && kcal <= t.calories * SCORE.kcalHi && protein >= t.protein * SCORE.proteinShare
}

/** good / medium / low — по баллу; none — данных нет; pending — сегодня, день ещё идёт. */
export type ScoreLevel = 'good' | 'medium' | 'low' | 'none' | 'pending'

export const levelOf = (total: number): 'good' | 'medium' | 'low' =>
  total >= SCORE.good ? 'good' : total >= SCORE.medium ? 'medium' : 'low'

export interface DayGrade {
  score: DayScore | null
  good: boolean
  level: ScoreLevel
  /** День без записи активности — «хороший» считается по правилу 3.22. */
  legacy: boolean
}

/**
 * Итог дня для чек-ина, полосы недели и «Прогресса».
 * activity — запись дня или null (её нет — день «старый», правило 3.22).
 * isToday — день ещё идёт: низкий балл не называем плохим (pending).
 */
export function gradeDay(
  day: { kcal: number; protein: number; workout: boolean },
  activity: Pick<DayActivity, 'steps' | 'sleptWell'> | null,
  t: DayTargets,
  isToday = false
): DayGrade {
  if (!(day.kcal > 0) && !day.workout && activity == null) return { score: null, good: false, level: 'none', legacy: true }
  const score = scoreDay({ ...day, steps: activity?.steps ?? null, sleptWell: activity?.sleptWell ?? null }, t)
  if (activity == null) {
    const good = legacyGood(day.kcal, day.protein, t)
    // Старый день без хорошего итога — не красим «слабым»: тогда сна и шагов просто не было.
    return { score, good, level: good ? 'good' : isToday ? 'pending' : 'medium', legacy: true }
  }
  // Хороший день — не только сумма: калории должны быть хотя бы в −15%…+10% от плана (26 из 35).
  // Иначе переедание или сильный недобор «закрывались» бы сном и шагами.
  const good = score.total >= SCORE.good && score.calories >= 26
  const lv0 = levelOf(score.total)
  const lv = lv0 === 'good' && !good ? 'medium' : lv0
  return { score, good, level: isToday && lv !== 'good' ? 'pending' : lv, legacy: false }
}

/** Цели по шагам — в пределах и с шагом 500. */
export function clampStepGoal(v: number): number {
  if (!Number.isFinite(v)) return SCORE.stepGoalDefault
  const s = Math.round(v / SCORE.stepGoalStep) * SCORE.stepGoalStep
  return Math.min(SCORE.stepGoalMax, Math.max(SCORE.stepGoalMin, s))
}

/** «12 345» — с неразрывным пробелом, как пишут числа по-русски. */
export const fmtInt = (n: number): string => Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ')

/** Средний балл; null — нет ни одного дня. */
export function averageScore(grades: DayGrade[]): number | null {
  const s = grades.filter((g) => g.score != null).map((g) => g.score!.total)
  return s.length ? Math.round(s.reduce((a, b) => a + b, 0) / s.length) : null
}

/** Недели программы: чек-ины с 5+ хорошими днями. Подряд — с последнего назад; пропуск просто не считается. */
export function programWeeks(list: { goodDays: number }[]): { done: number; streak: number } {
  const ok = list.map((c) => c.goodDays >= SCORE.weekGoodDays)
  let streak = 0
  for (let i = ok.length - 1; i >= 0 && ok[i]; i--) streak++
  return { done: ok.filter(Boolean).length, streak }
}

/** Бэкап: запись активности; мусор — null. */
export function parseActivity(v: unknown): DayActivity | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const date = o.date
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return null
  const n = Number(o.steps)
  const steps = o.steps != null && Number.isFinite(n) && n >= 0 ? Math.round(n) : null
  const src = o.stepsSource
  const stepsSource: StepsSource | null = src === 'health_connect' || src === 'sensor' || src === 'manual' ? src : steps != null ? 'manual' : null
  const sleptWell = typeof o.sleptWell === 'boolean' ? o.sleptWell : null
  return { date, steps, stepsSource, sleptWell }
}

/** Строка для ИИ-помощника. */
export function scoreContext(s: DayScore, today: boolean, weekAvg: number | null): string {
  const parts = `калории ${s.calories}/${SCORE.kcalPts}, белок ${s.protein}/${SCORE.proteinPts}, движение ${s.movement}/${SCORE.movePts}, сон ${s.sleep}/${SCORE.sleepPts}`
  return `${today ? 'пока ' : ''}${s.total}/100 (${parts}); хороший день — от ${SCORE.good}` +
    (weekAvg != null ? `; средняя за последние 7 дней — ${weekAvg}` : '') + '.'
}
