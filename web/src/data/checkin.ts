// Тренд веса и недельный чек-ин режима «Сжигание жира». Чистый модуль, как
// fatplan.ts: без IndexedDB, React и «сейчас» — всё приходит во входе, поэтому
// правила легко проверить скриптом. Пороги — в CHECKIN, чтобы тюнить в одном месте.
import { type FatPlan, type PlanInput, PLAN, addIsoDays, computePlan, daysBetween, macrosFor, planFloor, plural } from './fatplan'
import { type DayActivity, SCORE, averageScore, gradeDay } from './dayscore'

export const CHECKIN = {
  /** Сглаживание тренда за один день: 0,25 — тренд «помнит» примерно неделю. */
  alpha: 0.25,
  /** Не чаще одного чек-ина за 5 дней — иначе неделя не успевает набраться. */
  minGapDays: 5,
  /** Период чек-ина, дней: обычно неделя; пропустил — до двух. */
  minPeriod: 7,
  maxPeriod: 14,
  /** Темп по тренду считаем, только если тренд растянут хотя бы на 4 дня. */
  minRateDays: 4,
  /** «Дневник полный»: 5 записанных дней из 7. */
  diaryDays: 5,
  /**
   * «Хороший день» с 3.23 — оценка дня от 70 (dayscore.ts). Для дней без записи
   * активности остаётся правило 3.22: калории от −10% до +5% плана и белок от 90% цели.
   */
  goodLo: 0.9,
  goodHi: 1.05,
  proteinShare: 0.9,
  /** Белок от 90% нормы — хотя бы 5 дней из 7. */
  proteinDays: 5,
  /** Темп, % веса в неделю: < 0,2 — вес стоит, 0,4–1,0 — норма, > 1 — слишком быстро. */
  flat: 0.2,
  okLo: 0.4,
  fast: 1.0,
  fastKcal: 100,
  muscleKcal: 100,
  hungerKcal: 100,
  /** Вес стоит две недели: −100, если совсем не уходит (или растёт), иначе −50. */
  flatCut: 100,
  flatCutSmall: 50,
  /** Сколько недель «стоит», чтобы перестать резать и предложить перерыв. */
  flatBreakWeeks: 3,
  workoutGoalDefault: 3,
  workoutGoalMin: 2,
  workoutGoalMax: 5,
  /** День чек-ина по ISO: 1 — понедельник … 7 — воскресенье (как java.time.DayOfWeek). */
  weekdayDefault: 7
} as const

// ---------- Тренд ----------

export interface TrendPoint { date: string; raw: number; value: number }

/**
 * Экспоненциально сглаженный тренд с учётом пропусков: между точками через
 * g дней вес нового значения — 1 − (1 − α)^g. Взвесился через неделю — новая
 * цифра почти целиком входит в тренд; каждый день — тренд гасит скачки воды.
 */
export function trendSeries(points: { date: string; value: number }[], alpha: number = CHECKIN.alpha): TrendPoint[] {
  const sorted = points.filter((p) => Number.isFinite(p.value) && p.value > 0).sort((a, b) => a.date.localeCompare(b.date))
  const out: TrendPoint[] = []
  for (const p of sorted) {
    const prev = out.at(-1)
    if (!prev) { out.push({ date: p.date, raw: p.value, value: p.value }); continue }
    const gap = Math.max(1, daysBetween(prev.date, p.date))
    const a = 1 - Math.pow(1 - alpha, gap)
    out.push({ date: p.date, raw: p.value, value: prev.value + a * (p.value - prev.value) })
  }
  return out
}

/** Последняя точка тренда не позже даты. */
export function trendAt(series: TrendPoint[], date: string): TrendPoint | null {
  let r: TrendPoint | null = null
  for (const p of series) if (p.date <= date) r = p
  return r
}

export interface TrendChange {
  /** Изменение тренда, кг (минус — вес уходит). */
  kg: number
  days: number
  /** Темп, % веса в неделю; плюс — вес уходит (как в плане). */
  pctPerWeek: number
  start: number
  end: number
}

/** Изменение тренда между датами; null — точек мало или они слишком близко. */
export function trendChange(series: TrendPoint[], from: string, to: string): TrendChange | null {
  const end = trendAt(series, to)
  if (!end) return null
  // Нет точки до начала периода — берём первую внутри (первая неделя режима).
  const start = trendAt(series, from) ?? series.find((p) => p.date > from && p.date <= to) ?? null
  if (!start) return null
  const days = daysBetween(start.date, end.date)
  if (days < CHECKIN.minRateDays) return null
  const kg = end.value - start.value
  return { kg, days, pctPerWeek: ((-kg / start.value) * 100 * 7) / days, start: start.value, end: end.value }
}

// ---------- Когда чек-ин ----------

/** ISO-день недели даты YYYY-MM-DD: 1 — понедельник … 7 — воскресенье. */
export function isoWeekday(date: string): number {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d)).getUTCDay() || 7
}

/**
 * Дата следующего чек-ина: первый «день чек-ина» не раньше, чем через 5 дней
 * после прошлого чек-ина (или старта плана). Пропустил воскресенье — чек-ин
 * висит до выполнения; сделал во вторник — следующий снова в воскресенье.
 */
export function nextCheckinDate(anchor: string, weekday: number): string {
  let d = addIsoDays(anchor, CHECKIN.minGapDays)
  while (isoWeekday(d) !== weekday) d = addIsoDays(d, 1)
  return d
}

export const checkinDue = (anchor: string, weekday: number, today: string): boolean => today >= nextCheckinDate(anchor, weekday)

export const WEEKDAYS_SHORT = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']
const WEEKDAYS_ACC = ['в понедельник', 'во вторник', 'в среду', 'в четверг', 'в пятницу', 'в субботу', 'в воскресенье']
export const weekdayAcc = (wd: number): string => WEEKDAYS_ACC[(wd - 1 + 7) % 7]

/** «сегодня» / «завтра» / «в воскресенье, через 3 дня». */
export function checkinWhen(next: string, today: string): string {
  const left = daysBetween(today, next)
  if (left <= 0) return 'сегодня'
  if (left === 1) return 'завтра'
  return `${weekdayAcc(isoWeekday(next))}, через ${left} ${plural(left, 'день', 'дня', 'дней')}`
}

// ---------- Итог недели ----------

export type Signal = 'green' | 'yellow' | 'red'
export interface Ratings { hunger: number | null; energy: number | null; sleep: number | null }
export interface PlanMacros { calories: number; protein: number; fat: number; carbs: number }

export interface CheckIn extends Ratings {
  date: string
  /** Первый день периода (включительно); период — до вчера, сегодняшняя еда ещё не съедена. */
  periodStart: string
  periodDays: number
  weight: number
  trendWeight: number
  trendChangeKg: number | null
  /** % веса в неделю по тренду, плюс — вес уходит. */
  ratePerWeek: number | null
  fatPercent: number | null
  fatChange: number | null
  diaryDays: number
  goodDays: number
  /** Средняя оценка дня за период (3.23); null — старый чек-ин или дней с данными нет. */
  avgScore: number | null
  proteinDays: number
  workouts: number
  workoutGoal: number
  signal: Signal
  signalText: string
  /** Сколько чек-инов подряд вес стоит (при полном дневнике). */
  flatWeeks: number
  planBefore: PlanMacros | null
  planAfter: PlanMacros
  /** Почему план такой. */
  note: string
  /** Одно конкретное действие на неделю. */
  action: string
  /**
   * Смена этапа программы на этом чек-ине (3.24): «Блок 1 завершён → 2 недели
   * перерыва» и почему. null/нет — этап не менялся (и у старых чек-инов).
   */
  phaseTitle?: string | null
  phaseText?: string | null
}

export interface CheckinInput {
  today: string
  /** Вход планировщика (вес, взвешивания, дневник за окно, замер…); prev — план до чек-ина. */
  plan: PlanInput & { prev: FatPlan }
  /** Еда по дням: калории и белок (хватает дней периода). */
  days: { date: string; kcal: number; protein: number }[]
  /** Даты отмеченных силовых. */
  workouts: string[]
  /** Шаги и сон по дням (3.23); нет записи за день — день оценивается по правилу 3.22. */
  activity?: DayActivity[]
  stepGoal?: number
  /** Взвешивания с запасом до начала периода — чтобы тренд успел «разогреться». */
  weights: { date: string; weight: number }[]
  measurements: { date: string; fatPercent: number }[]
  workoutGoal: number
  /** Прошлый чек-ин; null — первый. */
  prev: CheckIn | null
  ratings: Ratings
}

export interface CheckinResult { checkin: CheckIn; plan: FatPlan; breakNote: boolean }

const macros = (p: PlanMacros): PlanMacros => ({ calories: p.calories, protein: p.protein, fat: p.fat, carbs: p.carbs })
const f1 = (v: number) => v.toFixed(1).replace('.', ',')
const dn = (n: number) => `${n} ${plural(n, 'день', 'дня', 'дней')}`

/** Период чек-ина: с прошлого чек-ина (или старта плана), но от 7 до 14 дней. */
export function checkinPeriod(anchor: string | null, today: string): { start: string; days: number } {
  const since = anchor ? daysBetween(anchor, today) : CHECKIN.minPeriod
  const days = Math.min(CHECKIN.maxPeriod, Math.max(CHECKIN.minPeriod, since))
  return { start: addIsoDays(today, -days), days }
}

export function buildCheckin(input: CheckinInput): CheckinResult {
  const { today, prev, ratings } = input
  const prevPlan = input.plan.prev
  const { start, days: n } = checkinPeriod(prev?.date ?? prevPlan.date, today)
  // Пороги «из 7» растягиваем на длинный период (пропустил чек-ин — 10 дней и т.п.).
  const scale = n / 7
  const diaryNeed = Math.ceil(CHECKIN.diaryDays * scale)
  const proteinNeed = Math.ceil(CHECKIN.proteinDays * scale)
  const workoutNeed = Math.max(1, Math.round(input.workoutGoal * scale))
  const inPeriod = (d: string) => d >= start && d < today

  // 1. Дневник: записанные дни, «хорошие» дни, дни с белком.
  const period = input.days.filter((d) => inPeriod(d.date))
  const diaryDays = period.filter((d) => d.kcal >= PLAN.minDayKcal).length
  const proteinOk = (d: { protein: number }) => d.protein >= prevPlan.protein * CHECKIN.proteinShare
  const proteinDays = period.filter((d) => d.kcal >= PLAN.minDayKcal && proteinOk(d)).length
  // Хорошие дни — по оценке дня; день без записи шагов/сна — по старому правилу (внутри gradeDay).
  const workoutSet = new Set(input.workouts.filter(inPeriod))
  const actBy = new Map((input.activity ?? []).filter((a) => inPeriod(a.date)).map((a) => [a.date, a]))
  const foodBy = new Map(period.map((d) => [d.date, d]))
  const targets = { calories: prevPlan.calories, protein: prevPlan.protein, stepGoal: input.stepGoal ?? SCORE.stepGoalDefault }
  const grades = Array.from({ length: n }, (_, i) => {
    const date = addIsoDays(start, i)
    const f = foodBy.get(date)
    return gradeDay({ kcal: f?.kcal ?? 0, protein: f?.protein ?? 0, workout: workoutSet.has(date) }, actBy.get(date) ?? null, targets)
  })
  const goodDays = grades.filter((g) => g.good).length
  // Средний балл — только если в периоде есть дни с записью активности: у недели
  // целиком «до 3.23» сна в оценке нет, и цифра была бы заниженной.
  const avgScore = actBy.size ? averageScore(grades) : null
  const workouts = workoutSet.size
  const diaryComplete = diaryDays >= diaryNeed

  // 2. Вес по тренду — темп недели не прыгает от воды и соли.
  const series = trendSeries(input.weights.map((w) => ({ date: w.date, value: w.weight })))
  const trendNow = trendAt(series, today)
  const ch = trendChange(series, start, today)
  const rate = ch?.pctPerWeek ?? null
  const flat = rate != null && rate < CHECKIN.flat
  const fast = rate != null && rate > CHECKIN.fast
  // Неделя с неполным дневником счётчик «стоит» не сбрасывает и не растит: по ней вывода нет.
  const flatWeeks = !flat ? 0 : (prev?.flatWeeks ?? 0) + (diaryComplete ? 1 : 0)

  // 3. Процент жира: изменение между двумя последними замерами.
  const ms = [...input.measurements].sort((a, b) => a.date.localeCompare(b.date))
  const lastM = ms.at(-1) ?? null
  const fatChange = ms.length >= 2 ? lastM!.fatPercent - ms.at(-2)!.fatPercent : null

  // 4. Сигнал «мышцы целы»: данных о весах на штанге нет, поэтому по косвенным —
  // силовые, белок и не слишком быстрый темп. Каждый провал — шаг к красному.
  const fails: string[] = []
  if (workouts < workoutNeed) fails.push(`силовых ${workouts} из ${workoutNeed} — мышцам нужен повод оставаться`)
  if (proteinDays < proteinNeed) fails.push(`белка хватало ${dn(proteinDays)} из ${n} — добирай до ${prevPlan.protein} г`)
  if (fast) fails.push('вес уходит быстрее 1% в неделю — так уходят и мышцы')
  const signal: Signal = fails.length === 0 ? 'green' : fails.length === 1 ? 'yellow' : 'red'
  const signalText = signal === 'green'
    ? 'Мышцы под защитой: силовые, белок и темп — в норме.'
    : `${fails.join('; ')}.`.replace(/^./, (c) => c.toUpperCase())

  // 5. Новый план. Расход и БЖУ — тем же планировщиком; калории — по правилам чек-ина.
  const base = computePlan({ ...input.plan, prev: prevPlan })
  const floor = planFloor(input.plan.female, base.bmr)
  const hungry2 = ratings.hunger === 5 && prev?.hunger === 5
  // Вес стоит — значит, план уже около расхода; ниже плана «поддержку» не называем.
  const maintenance = Math.round(Math.max(base.tdee, prevPlan.calories) / 10) * 10
  let delta = 0
  let note: string
  let breakNote = false
  let keep = false
  if (!diaryComplete) {
    keep = true
    note = `Дневник заполнен ${diaryDays} из ${n} дней — по таким данным легко ошибиться, поэтому план не трогаю.`
  } else if (signal === 'red' || hungry2) {
    delta = signal === 'red' ? CHECKIN.muscleKcal : CHECKIN.hungerKcal
    note = signal === 'red'
      ? `+${delta} ккал: сейчас главное — сберечь мышцы, поэтому дефицит чуть мягче.`
      : `+${delta} ккал: ты вторую неделю очень голоден — так долго не продержаться, сделаем дефицит мягче.`
  } else if (fast) {
    delta = CHECKIN.fastKcal
    note = `Вес уходит быстро (${f1(rate!)}% в неделю) — +${delta} ккал, чтобы уходил жир, а не мышцы.`
  } else if (rate == null) {
    note = 'Взвешиваний пока мало, чтобы увидеть тренд, — план без изменений. Взвешивайся 3–4 раза в неделю, утром.'
  } else if (flat && flatWeeks >= CHECKIN.flatBreakWeeks) {
    breakNote = true
    note = `Вес стоит ${flatWeeks}-ю неделю. Дальше не урезаю: попробуй 1–2 недели поесть на уровне расхода (≈ ${maintenance} ккал) — это даст отдых голове и телу, потом продолжим.`
  } else if (flat && flatWeeks >= 2) {
    delta = -(rate! <= 0 ? CHECKIN.flatCut : CHECKIN.flatCutSmall)
    note = `Вес стоит вторую неделю при полном дневнике — −${-delta} ккал.`
  } else if (flat) {
    note = 'Вес за неделю почти не сдвинулся — так бывает из-за воды и соли. План оставил, смотрим ещё неделю.'
  } else if (rate >= CHECKIN.okLo) {
    note = `Темп ${f1(rate)}% в неделю — в самый раз, план без изменений.`
  } else {
    note = `Вес уходит медленно (${f1(rate)}% в неделю), но уходит — план оставил.`
  }

  let plan: FatPlan
  const nextDate = addIsoDays(today, 7)
  if (keep) {
    plan = { ...prevPlan, note, date: today, nextDate }
  } else {
    // Шаг — не больше ±150 за раз; пол важнее шага.
    let calories = Math.max(prevPlan.calories - PLAN.maxStep, Math.min(prevPlan.calories + PLAN.maxStep, prevPlan.calories + delta))
    calories = Math.max(calories, floor)
    const m = macrosFor(calories, input.plan.weight, input.plan.fatPercent ?? (input.plan.female ? 32 : 25))
    plan = { ...base, calories, ...m, note, date: today, nextDate }
  }

  const action = !diaryComplete
    ? 'Записывай еду каждый день — даже примерно и даже перекусы. Так я пойму, что работает.'
    : proteinDays < proteinNeed
      ? `Белок — в каждый приём пищи: творог, яйца, курица, рыба. Цель — ${plan.protein} г в день.`
      : workouts < workoutNeed
        ? `Силовые: ${input.workoutGoal} в неделю. Выбери дни прямо сейчас и отмечай каждую.`
        : fast
          ? 'Ешь весь план и не урезай сверх него — так уходит жир, а не мышцы.'
          : breakNote
            ? `Неделя-другая на ${maintenance} ккал — без вины, это часть плана.`
            : 'Продолжай так же — всё работает.'

  const checkin: CheckIn = {
    date: today,
    periodStart: start,
    periodDays: n,
    weight: input.plan.weight,
    trendWeight: Math.round((trendNow?.value ?? input.plan.weight) * 10) / 10,
    trendChangeKg: ch ? Math.round(ch.kg * 100) / 100 : null,
    ratePerWeek: rate == null ? null : Math.round(rate * 100) / 100,
    fatPercent: lastM?.fatPercent ?? null,
    fatChange: fatChange == null ? null : Math.round(fatChange * 10) / 10,
    ...ratings,
    diaryDays, goodDays, avgScore, proteinDays, workouts, workoutGoal: workoutNeed,
    signal, signalText, flatWeeks,
    planBefore: macros(prevPlan),
    planAfter: macros(plan),
    note, action
  }
  return { checkin, plan, breakNote }
}

/** Бэкап: проверяем форму записи, мусор отбрасываем. */
export function parseCheckin(v: unknown): CheckIn | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const date = o.date
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return null
  const n = (k: string, d = 0) => (Number.isFinite(Number(o[k])) && o[k] != null ? Number(o[k]) : d)
  const opt = (k: string) => (o[k] == null || !Number.isFinite(Number(o[k])) ? null : Number(o[k]))
  const rating = (k: string) => { const r = opt(k); return r != null && r >= 1 && r <= 5 ? Math.round(r) : null }
  const pm = (x: unknown): PlanMacros | null => {
    if (!x || typeof x !== 'object') return null
    const q = x as Record<string, unknown>
    const c = Number(q.calories)
    return Number.isFinite(c) && c > 0 ? { calories: c, protein: Number(q.protein) || 0, fat: Number(q.fat) || 0, carbs: Number(q.carbs) || 0 } : null
  }
  const after = pm(o.planAfter)
  if (!after) return null
  return {
    date,
    periodStart: typeof o.periodStart === 'string' ? o.periodStart : addIsoDays(date, -7),
    periodDays: n('periodDays', 7),
    weight: n('weight'), trendWeight: n('trendWeight', n('weight')),
    trendChangeKg: opt('trendChangeKg'), ratePerWeek: opt('ratePerWeek'),
    fatPercent: opt('fatPercent'), fatChange: opt('fatChange'),
    hunger: rating('hunger'), energy: rating('energy'), sleep: rating('sleep'),
    diaryDays: n('diaryDays'), goodDays: n('goodDays'), avgScore: opt('avgScore'), proteinDays: n('proteinDays'),
    workouts: n('workouts'), workoutGoal: n('workoutGoal', CHECKIN.workoutGoalDefault),
    signal: o.signal === 'green' || o.signal === 'red' ? o.signal : 'yellow',
    signalText: typeof o.signalText === 'string' ? o.signalText : '',
    flatWeeks: n('flatWeeks'),
    planBefore: pm(o.planBefore), planAfter: after,
    note: typeof o.note === 'string' ? o.note : '',
    action: typeof o.action === 'string' ? o.action : '',
    phaseTitle: typeof o.phaseTitle === 'string' ? o.phaseTitle : null,
    phaseText: typeof o.phaseText === 'string' ? o.phaseText : null
  }
}

/** Строка для ИИ-помощника. */
export function checkinContext(c: CheckIn): string {
  const parts = [`последний чек-ин ${c.date}: тренд веса ${f1(c.trendWeight)} кг`]
  if (c.ratePerWeek != null) parts.push(`темп ${c.ratePerWeek > 0 ? '−' : '+'}${f1(Math.abs(c.ratePerWeek))}% веса в неделю`)
  if (c.fatPercent != null) parts.push(`жир ${f1(c.fatPercent)}%${c.fatChange != null ? ` (${c.fatChange > 0 ? '+' : ''}${f1(c.fatChange)})` : ''}`)
  parts.push(`дневник ${c.diaryDays}/${c.periodDays} дней, хороших дней ${c.goodDays}${c.avgScore != null ? `, средняя оценка дня ${c.avgScore}/100` : ''}, силовых ${c.workouts}/${c.workoutGoal}`)
  parts.push(`сигнал мышц: ${c.signal === 'green' ? 'зелёный' : c.signal === 'yellow' ? 'жёлтый' : 'красный'}`)
  if (c.hunger != null) parts.push(`голод ${c.hunger}/5, энергия ${c.energy ?? '—'}/5, сон ${c.sleep ?? '—'}/5`)
  parts.push(`план ${c.planBefore ? `${c.planBefore.calories} → ` : ''}${c.planAfter.calories} ккал (${c.note})`)
  parts.push(`задача недели: ${c.action}`)
  if (c.phaseTitle) parts.push(`смена этапа: ${c.phaseTitle}`)
  return parts.join('; ') + '.'
}
